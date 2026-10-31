package server;

import server.config.ServerConfig;
import server.service.ChatService;
import server.service.RequestRejected;
import server.transport.OutboundChannel;
import shared.protocol.Protocol;
import shared.util.LoggerUtil;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns all listeners, accepted connections and their shutdown lifecycle. */
public final class ChatServer implements AutoCloseable {
    private enum Channel { MESSAGE, FILE, ROOMS }
    private final ServerConfig config;
    private final ChatService service;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-write-watchdog"); thread.setDaemon(true); return thread;
    });
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private ServerSocket messageListener, fileListener, roomListener;
    private boolean started;

    public ChatServer(ServerConfig config) {
        this.config = config;
        this.service = new ChatService(config.maxSessions());
        this.connectionSlots = new Semaphore(Math.max(16, config.maxSessions() * 3));
    }
    public synchronized void start() throws IOException {
        if (started || closed.get()) throw new IllegalStateException("Server cannot be started twice");
        try {
            messageListener = bind(config.messagePort());
            fileListener = bind(config.filePort());
            roomListener = bind(config.roomPort());
            started = true;
            workers.execute(() -> accept(messageListener, Channel.MESSAGE));
            workers.execute(() -> accept(fileListener, Channel.FILE));
            workers.execute(() -> accept(roomListener, Channel.ROOMS));
        } catch (IOException | RuntimeException e) {
            close(); throw e;
        }
    }
    private ServerSocket bind(int port) throws IOException {
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(config.bindHost(), port));
            return listener;
        } catch (IOException e) { listener.close(); throw e; }
    }
    private void accept(ServerSocket listener, Channel kind) {
        try {
            while (!closed.get()) {
                Socket socket = listener.accept();
                if (!connectionSlots.tryAcquire()) { socket.close(); continue; }
                sockets.add(socket);
                if (closed.get()) {
                    sockets.remove(socket); connectionSlots.release(); socket.close(); break;
                }
                try { workers.execute(() -> handle(socket, kind)); }
                catch (RejectedExecutionException e) {
                    sockets.remove(socket); connectionSlots.release(); socket.close();
                    if (!closed.get()) throw e;
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!closed.get()) {
                LoggerUtil.error("Listener failed: " + kind, e);
                close();
            }
        }
    }
    private void handle(Socket socket, Channel kind) {
        OutboundChannel channel = null;
        String sessionId = null;
        boolean flushBeforeClose = false;
        try {
            channel = new OutboundChannel(socket, config, scheduler);
            Serializable first = channel.read();
            switch (kind) {
                case MESSAGE -> {
                    if (!(first instanceof Protocol.Hello hello))
                        throw new RequestRejected("INVALID_STATE", "Hello is required first.");
                    sessionId = service.register(hello.user(), channel);
                    channel.authenticated();
                    while (!closed.get()) {
                        Serializable payload = channel.read();
                        if (!(payload instanceof Protocol.Request request))
                            throw new RequestRejected("INVALID_INPUT", "Expected a client request.");
                        service.handle(sessionId, request);
                    }
                }
                case FILE -> {
                    if (!(first instanceof Protocol.BindFile bind))
                        throw new RequestRejected("UNAUTHORIZED", "BindFile is required first.");
                    sessionId = service.bindFile(bind.token(), channel);
                    channel.authenticated();
                    while (!closed.get()) {
                        Serializable payload = channel.read();
                        if (payload instanceof Protocol.SendFile file) service.sendFile(sessionId, file.file());
                        else if (!channel.offer(new Protocol.Error("INVALID_STATE", "Only SendFile is valid on the file channel.")))
                            break;
                    }
                }
                case ROOMS -> {
                    if (!(first instanceof Protocol.ListRooms))
                        throw new RequestRejected("INVALID_STATE", "ListRooms is required.");
                    channel.offer(new Protocol.RoomList(service.rooms()));
                    flushBeforeClose = true;
                }
            }
        } catch (RequestRejected e) {
            if (channel != null) {
                channel.offer(new Protocol.Error(e.code(), e.getMessage()));
                flushBeforeClose = true;
            }
        } catch (EOFException | SocketException | SocketTimeoutException ignored) {
            // Normal disconnect, handshake timeout, or a watchdog closing a slow connection.
        } catch (IOException | ClassNotFoundException e) {
            if (!closed.get()) LoggerUtil.log("Rejected or closed " + kind + " connection: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            if (!closed.get()) LoggerUtil.error("Connection handler failed", e);
        } finally {
            if (channel != null) {
                if (flushBeforeClose) {
                    channel.finish();
                    try { channel.awaitClosed(config.writeTimeoutMillis() + 1000L); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                channel.close();
            }
            if (sessionId != null) {
                if (kind == Channel.MESSAGE) service.disconnect(sessionId);
                else if (kind == Channel.FILE) service.fileDisconnected(sessionId, channel);
            }
            try { socket.close(); } catch (IOException ignored) {}
            sockets.remove(socket); connectionSlots.release();
        }
    }
    public synchronized int getMessagePort() { return port(messageListener); }
    public synchronized int getFilePort() { return port(fileListener); }
    public synchronized int getRoomPort() { return port(roomListener); }
    public ChatService getChatService() { return service; }
    public int getConnectionCount() { return sockets.size(); }
    private int port(ServerSocket listener) {
        if (listener == null || !started) throw new IllegalStateException("Server is not started");
        return listener.getLocalPort();
    }
    public void awaitTermination() throws InterruptedException { stopped.await(); }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ServerSocket listener : new ServerSocket[]{messageListener, fileListener, roomListener}) {
            if (listener != null) try { listener.close(); } catch (IOException ignored) {}
        }
        service.close();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        workers.shutdownNow(); scheduler.shutdownNow(); stopped.countDown();
    }
}
