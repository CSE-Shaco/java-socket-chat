package client.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.ChatServer;
import server.config.ServerConfig;
import shared.config.NetworkConfig.ClientConfig;
import shared.domain.User;
import shared.protocol.Protocol;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ChatClientIT {
    @TempDir Path directory;

    @Test void keepsOneSessionAcrossRoomsMatchingAndFileTransfer() throws Exception {
        try (ChatServer server = new ChatServer(ServerConfig.localEphemeral())) {
            server.start();
            ClientConfig config = new ClientConfig("localhost", server.getMessagePort(), server.getFilePort(), server.getRoomPort());
            LinkedBlockingQueue<Object> aliceEvents = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<Object> bobEvents = new LinkedBlockingQueue<>();
            java.util.concurrent.atomic.AtomicInteger welcomes = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger bobWelcomes = new java.util.concurrent.atomic.AtomicInteger();
            try (ChatClient alice = new ChatClient(config, event -> {
                if (event instanceof Protocol.Welcome) welcomes.incrementAndGet();
                aliceEvents.add(event);
            }, aliceEvents::add);
                 ChatClient bob = new ChatClient(config, event -> {
                     if (event instanceof Protocol.Welcome) bobWelcomes.incrementAndGet();
                     bobEvents.add(event);
                 }, bobEvents::add)) {
                alice.connect(new User("alice", "Alice")).get(5, TimeUnit.SECONDS);
                bob.connect(new User("bob", "Bob")).get(5, TimeUnit.SECONDS);
                Protocol.Welcome welcome = await(aliceEvents, Protocol.Welcome.class, ignored -> true);
                assertEquals(welcome.user().getUserId(), alice.user().getUserId());
                assertNotEquals("alice", alice.user().getUserId());
                assertTrue(alice.isFileReady());
                alice.createRoom("Demo").get(5, TimeUnit.SECONDS);
                Protocol.RoomJoined joined = await(aliceEvents, Protocol.RoomJoined.class, ignored -> true);
                assertEquals(joined.room().id(), alice.listRooms().get(5, TimeUnit.SECONDS).rooms().getFirst().id());
                bob.joinRoom(joined.room().id()).get(5, TimeUnit.SECONDS);
                await(bobEvents, Protocol.RoomJoined.class, ignored -> true);
                alice.sendText("<html>plain text & 한글").get(5, TimeUnit.SECONDS);
                var received = await(bobEvents, Protocol.TextMessage.class, message -> !message.system());
                assertEquals("<html>plain text & 한글", received.content());
                Path file = directory.resolve("demo.txt"); Files.writeString(file, "shared file");
                alice.sendFile(file).get(5, TimeUnit.SECONDS);
                var attachment = await(bobEvents, Protocol.FileMessage.class, ignored -> true);
                assertEquals("demo.txt", attachment.file().getFileName());
                assertArrayEquals(Files.readAllBytes(file), attachment.file().getData());
                alice.leaveRoom().get(5, TimeUnit.SECONDS); bob.leaveRoom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Lobby.class, ignored -> true);
                await(bobEvents, Protocol.Lobby.class, ignored -> true);
                alice.startRandom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Waiting.class, ignored -> true);
                alice.cancelRandom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Lobby.class, ignored -> true);
                alice.startRandom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Waiting.class, ignored -> true);
                bob.startRandom().get(5, TimeUnit.SECONDS);
                var aliceMatch = await(aliceEvents, Protocol.MatchFound.class, ignored -> true);
                var bobMatch = await(bobEvents, Protocol.MatchFound.class, ignored -> true);
                assertEquals(aliceMatch.room().id(), bobMatch.room().id());
                assertTrue(aliceMatch.room().anonymous());
                // MatchFound already grants room membership. Entering the chat needs no join request.
                alice.sendText("Ready in the matched room").get(5, TimeUnit.SECONDS);
                var matchedMessage = await(bobEvents, Protocol.TextMessage.class, message -> !message.system());
                assertEquals(aliceMatch.room().id(), matchedMessage.roomId());
                assertEquals("Ready in the matched room", matchedMessage.content());
                assertEquals(1, server.getChatService().getRoomCount(), "Matching and entering chat must create only one room");
                assertEquals(2, server.getChatService().getSessionCount(), "Chat entry must preserve the two original sessions");
                assertTrue(alice.listRooms().get(5, TimeUnit.SECONDS).rooms().isEmpty(), "The matched room must remain private");
                assertNotNull(welcome.sessionId());
                assertEquals(1, welcomes.get(), "Alice must not reconnect on chat entry");
                assertEquals(1, bobWelcomes.get(), "Bob must not reconnect on chat entry");
                alice.leaveRoom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Lobby.class, ignored -> true);
                await(bobEvents, Protocol.Waiting.class, ignored -> true);
                bob.cancelRandom().get(5, TimeUnit.SECONDS);
                await(bobEvents, Protocol.Lobby.class, ignored -> true);
                assertEquals(0, server.getChatService().getRoomCount(), "The last departure must remove the one matched room");
                alice.close();
                assertTrue(alice.listRooms().isCompletedExceptionally());
                assertTrue(alice.sendText("closed").isCompletedExceptionally());
            }
        }
    }

    @Test void randomRoomDepartureRematchesWaitingPartnerWithoutReplacingSessions() throws Exception {
        try (ChatServer server = new ChatServer(ServerConfig.localEphemeral())) {
            server.start();
            ClientConfig config = new ClientConfig("localhost", server.getMessagePort(), server.getFilePort(), server.getRoomPort());
            LinkedBlockingQueue<Object> aliceEvents = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<Object> bobEvents = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<Object> carolEvents = new LinkedBlockingQueue<>();
            java.util.concurrent.atomic.AtomicInteger welcomes = new java.util.concurrent.atomic.AtomicInteger();
            try (ChatClient alice = new ChatClient(config, event -> { if (event instanceof Protocol.Welcome) welcomes.incrementAndGet(); aliceEvents.add(event); }, aliceEvents::add);
                 ChatClient bob = new ChatClient(config, event -> { if (event instanceof Protocol.Welcome) welcomes.incrementAndGet(); bobEvents.add(event); }, bobEvents::add);
                 ChatClient carol = new ChatClient(config, event -> { if (event instanceof Protocol.Welcome) welcomes.incrementAndGet(); carolEvents.add(event); }, carolEvents::add)) {
                alice.connect(new User("alice", "Alice")).get(5, TimeUnit.SECONDS);
                bob.connect(new User("bob", "Bob")).get(5, TimeUnit.SECONDS);
                carol.connect(new User("carol", "Carol")).get(5, TimeUnit.SECONDS);
                Protocol.Welcome aliceWelcome = await(aliceEvents, Protocol.Welcome.class, ignored -> true);
                Protocol.Welcome bobWelcome = await(bobEvents, Protocol.Welcome.class, ignored -> true);
                Protocol.Welcome carolWelcome = await(carolEvents, Protocol.Welcome.class, ignored -> true);

                alice.startRandom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Waiting.class, ignored -> true);
                bob.startRandom().get(5, TimeUnit.SECONDS);
                Protocol.MatchFound firstAliceMatch = await(aliceEvents, Protocol.MatchFound.class, ignored -> true);
                Protocol.MatchFound firstBobMatch = await(bobEvents, Protocol.MatchFound.class, ignored -> true);
                assertEquals(firstAliceMatch.room().id(), firstBobMatch.room().id());
                carol.startRandom().get(5, TimeUnit.SECONDS);
                await(carolEvents, Protocol.Waiting.class, ignored -> true);

                alice.leaveRoom().get(5, TimeUnit.SECONDS);
                await(aliceEvents, Protocol.Lobby.class, ignored -> true);
                await(bobEvents, Protocol.Waiting.class, ignored -> true);
                Protocol.MatchFound bobRematch = await(bobEvents, Protocol.MatchFound.class, ignored -> true);
                Protocol.MatchFound carolRematch = await(carolEvents, Protocol.MatchFound.class, ignored -> true);
                assertNotEquals(firstAliceMatch.room().id(), bobRematch.room().id());
                assertEquals(bobRematch.room().id(), carolRematch.room().id());
                assertEquals(3, server.getChatService().getSessionCount());
                assertEquals(3, welcomes.get(), "Rematching must keep each original message session");
                assertNotNull(aliceWelcome.sessionId());
                assertNotNull(bobWelcome.sessionId());
                assertNotNull(carolWelcome.sessionId());
                assertEquals(1, server.getChatService().getRoomCount());

                bob.sendText("Message after rematch").get(5, TimeUnit.SECONDS);
                var delivered = await(carolEvents, Protocol.TextMessage.class, message -> !message.system());
                assertEquals(bobRematch.room().id(), delivered.roomId());
                assertEquals("Message after rematch", delivered.content());
                assertEquals(3, welcomes.get(), "The new match must not trigger reconnects");
            }
        }
    }

    @Test void rejectsInvalidInputWithoutSwingOrNetworkAccess() {
        try (ChatClient client = new ChatClient(new ClientConfig("localhost", 1, 2, 3), ignored -> {}, ignored -> {})) {
            assertTrue(client.sendText(" ").isCompletedExceptionally());
            assertTrue(client.sendText("x".repeat(4_001)).isCompletedExceptionally());
        }
    }

    private static <T> T await(BlockingQueue<Object> events, Class<T> type, Predicate<T> predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            Object event = events.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (event instanceof Protocol.Error error) fail(error.code() + ": " + error.message());
            if (type.isInstance(event) && predicate.test(type.cast(event))) return type.cast(event);
        }
        throw new AssertionError("Timed out waiting for " + type.getSimpleName());
    }
}
