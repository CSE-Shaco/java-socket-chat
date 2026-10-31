package server.transport;

import server.config.ServerConfig;
import shared.protocol.Protocol;
import shared.protocol.WireIO;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded per-connection writer with a deadline that closes stalled socket writes. */
public final class OutboundChannel implements Outbound {
    private record Pending(Protocol.Response response, long bytes) {}
    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    private final BlockingQueue<Pending> queue;
    private final long byteLimit;
    private final long writeTimeoutNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final Thread writer;
    private final ScheduledFuture<?> watchdog;
    private long pendingBytes;
    private boolean finishing;
    private volatile long writingSince;

    public OutboundChannel(Socket socket, ServerConfig config, ScheduledExecutorService scheduler) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(config.handshakeTimeoutMillis());
        out = new ObjectOutputStream(socket.getOutputStream()); out.flush();
        in = new ObjectInputStream(socket.getInputStream());
        WireIO.installFilter(in);
        queue = new ArrayBlockingQueue<>(config.outboundQueueCapacity());
        byteLimit = config.outboundQueueBytes();
        writeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.writeTimeoutMillis());
        writer = Thread.ofVirtual().name("chat-writer").unstarted(this::writeLoop);
        watchdog = scheduler.scheduleAtFixedRate(this::checkDeadline, 100, 100, TimeUnit.MILLISECONDS);
        writer.start();
    }
    public Serializable read() throws IOException, ClassNotFoundException { return WireIO.read(in); }
    public void authenticated() throws IOException { socket.setSoTimeout(0); }
    @Override public synchronized boolean offer(Protocol.Response response) {
        if (closed.get() || finishing) return false;
        long bytes = estimate(response);
        if (bytes > byteLimit - pendingBytes) return false;
        Pending pending = new Pending(response, bytes);
        if (!queue.offer(pending)) return false;
        pendingBytes += bytes;
        return true;
    }
    /** Flush already-enqueued one-shot responses before closing. */
    public synchronized void finish() { finishing = true; }
    public boolean awaitClosed(long millis) throws InterruptedException {
        return terminated.await(millis, TimeUnit.MILLISECONDS);
    }
    public boolean isClosed() { return closed.get(); }
    public synchronized long getPendingBytes() { return pendingBytes; }
    public int getQueuedCount() { return queue.size(); }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                Pending pending = queue.poll(100, TimeUnit.MILLISECONDS);
                if (pending == null) {
                    synchronized (this) { if (finishing && queue.isEmpty()) break; }
                    continue;
                }
                writingSince = System.nanoTime();
                try { WireIO.write(out, pending.response()); }
                finally {
                    writingSince = 0;
                    synchronized (this) { pendingBytes -= pending.bytes(); }
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // Closing the socket wakes its reader, which owns session cleanup.
        } finally {
            close(); terminated.countDown();
        }
    }
    private void checkDeadline() {
        long since = writingSince;
        if (since != 0 && System.nanoTime() - since > writeTimeoutNanos) close();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        watchdog.cancel(false);
        try { socket.close(); } catch (IOException ignored) {}
        writer.interrupt();
        synchronized (this) {
            Pending pending;
            while ((pending = queue.poll()) != null) pendingBytes -= pending.bytes();
        }
    }
    private static long estimate(Protocol.Response response) {
        if (response instanceof Protocol.FileMessage file) return 1024L + file.file().getData().length;
        if (response instanceof Protocol.TextMessage text) return 1024L + 3L * text.content().length();
        if (response instanceof Protocol.RoomList list) return 1024L + 1024L * list.rooms().size();
        return 2048;
    }
}
