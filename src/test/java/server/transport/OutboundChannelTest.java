package server.transport;

import org.junit.jupiter.api.Test;
import server.config.ServerConfig;
import shared.domain.FileInfo;
import shared.protocol.Protocol;
import shared.protocol.WireIO;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class OutboundChannelTest {
    @Test void byteBudgetRejectsBeforeRetainingPayload() throws Exception {
        try (Connection connection = new Connection(config(2, 2048, 5_000))) {
            assertFalse(connection.channel.offer(file(1025)));
            assertEquals(0, connection.channel.getPendingBytes());
            assertEquals(0, connection.channel.getQueuedCount());
            assertTrue(connection.channel.offer(new Protocol.Lobby()));
            assertInstanceOf(Protocol.Lobby.class, WireIO.read(connection.peerIn));
        }
    }

    @Test void queueCapacityBoundsMessagesBehindABlockedWrite() throws Exception {
        try (Connection connection = new Connection(config(1, 20L * 1024 * 1024, 5_000))) {
            assertTrue(connection.channel.offer(file(Protocol.MAX_FILE_BYTES)));
            await(() -> connection.channel.getQueuedCount() == 0
                    && connection.channel.getPendingBytes() > 0);
            assertTrue(connection.channel.offer(new Protocol.Lobby()));
            assertFalse(connection.channel.offer(new Protocol.Waiting()));
            assertEquals(1, connection.channel.getQueuedCount());
            assertTrue(connection.channel.getPendingBytes() > Protocol.MAX_FILE_BYTES);
            connection.channel.close();
            assertTrue(connection.channel.awaitClosed(3_000));
            assertEquals(0, connection.channel.getPendingBytes());
        }
    }

    @Test void watchdogClosesStalledPeerWhileIndependentChannelKeepsDelivering() throws Exception {
        try (Connection stalled = new Connection(config(8, 20L * 1024 * 1024, 250));
             Connection healthy = new Connection(config(8, 20L * 1024 * 1024, 5_000))) {
            assertTrue(stalled.channel.offer(file(Protocol.MAX_FILE_BYTES)));
            Protocol.TextMessage text = new Protocol.TextMessage("id", "Name", "room", "responsive", false);
            assertTrue(healthy.channel.offer(text));
            assertEquals(text, WireIO.read(healthy.peerIn));
            assertTrue(stalled.channel.awaitClosed(4_000), "Stalled write must be interrupted by socket close");
            assertTrue(stalled.channel.isClosed());
            assertEquals(0, stalled.channel.getPendingBytes());
            assertTrue(healthy.channel.offer(new Protocol.Lobby()));
            assertInstanceOf(Protocol.Lobby.class, WireIO.read(healthy.peerIn));
            assertFalse(healthy.channel.isClosed());
        }
    }

    private static Protocol.FileMessage file(int size) {
        return new Protocol.FileMessage("id", "Name", "room", new FileInfo("test.bin", new byte[size], false));
    }
    private static ServerConfig config(int capacity, long bytes, int writeTimeout) {
        return new ServerConfig("127.0.0.1", 0, 0, 0, 10, 3_000, capacity, bytes, writeTimeout);
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Writer did not begin processing the queued payload");
    }
    private static final class Connection implements AutoCloseable {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
        final Socket peer;
        final ObjectOutputStream peerOut;
        final ObjectInputStream peerIn;
        final OutboundChannel channel;
        Connection(ServerConfig config) throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Future<OutboundChannel> accepted = worker.submit(() -> {
                    Socket socket = listener.accept();
                    socket.setSendBufferSize(1024);
                    return new OutboundChannel(socket, config, scheduler);
                });
                peer = new Socket();
                peer.setReceiveBufferSize(1024);
                peer.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()), 3_000);
                peer.setSoTimeout(3_000);
                peerOut = new ObjectOutputStream(peer.getOutputStream()); peerOut.flush();
                peerIn = new ObjectInputStream(peer.getInputStream()); WireIO.installFilter(peerIn);
                channel = accepted.get(3, TimeUnit.SECONDS);
            }
        }
        @Override public void close() {
            channel.close();
            try { peer.close(); } catch (IOException ignored) {}
            scheduler.shutdownNow(); worker.shutdownNow();
        }
    }
}
