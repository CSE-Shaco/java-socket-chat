package server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.config.ServerConfig;
import shared.domain.FileInfo;
import shared.domain.User;
import shared.protocol.Protocol;
import shared.protocol.WireIO;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real TCP tests: no GUI, mocks, fixed ports, or external server required. */
class ChatServerIT {
    private ChatServer server;
    private final List<Peer> peers = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        server = new ChatServer(ServerConfig.localEphemeral());
        server.start();
    }

    @AfterEach void stop() {
        peers.forEach(Peer::close);
        if (server != null) server.close();
    }

    @Test void roomMessagesUseServerIdentityAndStayInTheirRoom() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob"), outsider = login("Outsider");
        Protocol.RoomSummary room = create(alice, "Public room");
        bob.send(new Protocol.JoinRoom(room.id()));
        bob.until(Protocol.RoomJoined.class);
        create(outsider, "Other room");
        alice.send(new Protocol.SendText("hello"));
        Protocol.TextMessage delivered = bob.text("hello");
        assertEquals(alice.welcome.user().getUserId(), delivered.senderId());
        assertEquals("Alice", delivered.sender());
        assertEquals(room.id(), delivered.roomId());
        assertFalse(outsider.receivesText("hello"));
        Protocol.RoomList rooms = roomList();
        assertEquals(2, rooms.rooms().size());
        assertTrue(rooms.rooms().stream().anyMatch(r -> r.id().equals(room.id()) && r.participantCount() == 2));
    }

    @Test void leavingReturnsToLobbyWithoutReplacingConnection() throws Exception {
        Peer alice = login("Alice");
        String sessionId = alice.welcome.sessionId();
        Protocol.RoomSummary first = create(alice, "First");
        alice.send(new Protocol.LeaveRoom());
        alice.until(Protocol.Lobby.class);
        alice.send(new Protocol.SendText("not in a room"));
        alice.until(Protocol.Error.class);
        Protocol.RoomSummary second = create(alice, "Second");
        assertNotEquals(first.id(), second.id());
        alice.send(new Protocol.SendText("still connected"));
        assertEquals(alice.welcome.user().getUserId(), alice.text("still connected").senderId());
        assertEquals(sessionId, alice.welcome.sessionId());
        assertEquals(1, roomList().rooms().size());
    }

    @Test void randomMatchKeepsConnectionAndHidesAnonymousRoom() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob");
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        bob.send(new Protocol.StartRandom());
        Protocol.MatchFound first = alice.until(Protocol.MatchFound.class);
        Protocol.MatchFound second = bob.until(Protocol.MatchFound.class);
        assertEquals(first.room().id(), second.room().id());
        assertTrue(first.room().anonymous());
        assertTrue(roomList().rooms().isEmpty());
        // A legacy-style second join after matching must never create a duplicate public room.
        alice.send(new Protocol.JoinRoom(first.room().id()));
        alice.until(Protocol.Error.class);
        bob.send(new Protocol.JoinRoom(first.room().id()));
        bob.until(Protocol.Error.class);
        assertEquals(1, server.getChatService().getRoomCount());
        assertEquals(2, server.getChatService().getSessionCount());
        assertTrue(roomList().rooms().isEmpty());
        bob.send(new Protocol.SendText("matched"));
        assertEquals(bob.welcome.user().getUserId(), alice.text("matched").senderId());
        Peer outsider = login("Outsider");
        outsider.send(new Protocol.JoinRoom(first.room().id()));
        outsider.until(Protocol.Error.class);
    }

    @Test void randomPartnerDisconnectQueuesSurvivorForANewMatch() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob"), carol = login("Carol");
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        bob.send(new Protocol.StartRandom());
        Protocol.MatchFound firstAlice = alice.until(Protocol.MatchFound.class);
        Protocol.MatchFound firstBob = bob.until(Protocol.MatchFound.class);
        assertEquals(firstAlice.room().id(), firstBob.room().id());

        carol.send(new Protocol.StartRandom());
        carol.until(Protocol.Waiting.class);
        alice.close();
        bob.until(Protocol.Waiting.class);
        Protocol.MatchFound bobAgain = bob.until(Protocol.MatchFound.class);
        Protocol.MatchFound carolAgain = carol.until(Protocol.MatchFound.class);
        assertNotEquals(firstAlice.room().id(), bobAgain.room().id());
        assertEquals(bobAgain.room().id(), carolAgain.room().id());
        assertEquals(1, server.getChatService().getRoomCount());
        assertEquals(2, server.getChatService().getSessionCount());
        bob.send(new Protocol.SendText("after disconnect"));
        assertEquals(bobAgain.room().id(), carol.text("after disconnect").roomId());
        assertTrue(roomList().rooms().isEmpty(), "The rematched anonymous room stays private");
    }

    @Test void previousRandomPartnersRemainExcludedWhenBothReenterTheQueue() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob");
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        bob.send(new Protocol.StartRandom());
        Protocol.MatchFound first = alice.until(Protocol.MatchFound.class);
        assertEquals(first.room().id(), bob.until(Protocol.MatchFound.class).room().id());

        alice.send(new Protocol.LeaveRoom());
        alice.until(Protocol.Lobby.class);
        bob.until(Protocol.Waiting.class);
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        assertEquals(2, server.getChatService().getWaitingCount());
        assertEquals(0, server.getChatService().getRoomCount());
    }

    @Test void cancellationDoesNotLeaveAGhostMatch() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob"), carol = login("Carol");
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        alice.send(new Protocol.CancelRandom());
        alice.until(Protocol.Lobby.class);
        bob.send(new Protocol.StartRandom());
        bob.until(Protocol.Waiting.class);
        carol.send(new Protocol.StartRandom());
        assertEquals(bob.until(Protocol.MatchFound.class).room().id(),
                carol.until(Protocol.MatchFound.class).room().id());
        create(alice, "After cancel");
    }

    @Test void repeatedRandomRequestsOverTheSameSocketsNeverCreateDuplicateRooms() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob");
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        alice.send(new Protocol.StartRandom());
        alice.until(Protocol.Waiting.class);
        bob.send(new Protocol.StartRandom());
        String room = alice.until(Protocol.MatchFound.class).room().id();
        assertEquals(room, bob.until(Protocol.MatchFound.class).room().id());
        for (int i = 0; i < 5; i++) {
            alice.send(new Protocol.StartRandom());
            alice.until(Protocol.Error.class);
            bob.send(new Protocol.StartRandom());
            bob.until(Protocol.Error.class);
        }
        assertEquals(1, server.getChatService().getRoomCount());
        assertEquals(0, server.getChatService().getWaitingCount());
        assertTrue(roomList().rooms().isEmpty());
        alice.send(new Protocol.SendText("one random room"));
        assertEquals(room, bob.text("one random room").roomId());
        alice.send(new Protocol.LeaveRoom());
        alice.until(Protocol.Lobby.class);
        bob.send(new Protocol.LeaveRoom());
        bob.until(Protocol.Lobby.class);
        assertEquals(0, server.getChatService().getRoomCount());
    }

    @Test void fileChannelRequiresTokenAndPreservesBytes() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob");
        Protocol.RoomSummary room = create(alice, "Files");
        bob.send(new Protocol.JoinRoom(room.id()));
        bob.until(Protocol.RoomJoined.class);
        Peer invalid = peer(server.getFilePort());
        invalid.send(new Protocol.BindFile("invalid-token"));
        invalid.until(Protocol.Error.class);
        Peer aliceFiles = bind(alice), bobFiles = bind(bob);
        byte[] bytes = {0, 1, 2, 42, (byte) 255};
        aliceFiles.send(new Protocol.SendFile(new FileInfo("demo.bin", bytes, false)));
        Protocol.FileMessage delivered = bobFiles.until(Protocol.FileMessage.class);
        assertArrayEquals(bytes, delivered.file().getData());
        assertEquals("demo.bin", delivered.file().getFileName());
        assertEquals(alice.welcome.user().getUserId(), delivered.senderId());
        assertEquals(room.id(), delivered.roomId());
    }

    @Test void fileBeforeRecipientsBindAndInvalidFilenameDoNotBreakChat() throws Exception {
        Peer alice = login("Alice"), bob = login("Bob");
        Protocol.RoomSummary room = create(alice, "Files");
        bob.send(new Protocol.JoinRoom(room.id()));
        bob.until(Protocol.RoomJoined.class);
        Peer files = bind(alice);
        files.send(new Protocol.SendFile(new FileInfo("../escape.txt", new byte[]{1}, false)));
        files.until(Protocol.Error.class);
        files.send(new Protocol.SendFile(new FileInfo("safe.txt", new byte[]{2}, false)));
        alice.send(new Protocol.SendText("alive"));
        bob.text("alive");
    }

    @Test void invalidRequestsAreRejectedWithoutChangingRoomState() throws Exception {
        Peer alice = login("Alice");
        alice.send(new Protocol.JoinRoom("missing-room"));
        alice.until(Protocol.Error.class);
        create(alice, "Validation");
        alice.send(new Protocol.SendText("x".repeat(Protocol.MAX_TEXT_LENGTH + 1)));
        alice.until(Protocol.Error.class);
        alice.send(new Protocol.SendText(""));
        alice.until(Protocol.Error.class);
        alice.send(new Protocol.SendText("valid"));
        alice.text("valid");
    }

    @Test void serverShutdownClosesExistingConnectionsAndReleasesAllPorts() throws Exception {
        Peer alice = login("Alice");
        int messagePort = server.getMessagePort(), filePort = server.getFilePort(), roomPort = server.getRoomPort();
        server.close();
        assertThrows(IOException.class, () -> {
            while (true) alice.read();
        });
        for (int port : new int[]{messagePort, filePort, roomPort}) {
            assertPortReleased(port, 2_000);
        }
    }

    @Test void portReleaseCheckRejectsAListenerThatIsStillBound() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(true);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> assertPortReleased(occupied.getLocalPort(), 0));
            assertInstanceOf(BindException.class, failure.getCause());
        }
    }

    private static void assertPortReleased(int port, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            try (ServerSocket rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress("127.0.0.1", port));
                return;
            } catch (BindException busy) {
                // Closing sockets wakes asynchronous readers/writers. The OS can
                // briefly retain the endpoint while those operations unwind.
                if (System.nanoTime() >= deadline)
                    throw new AssertionError("Server port was not released: " + port, busy);
                Thread.sleep(5);
            }
        }
    }

    @Test void messageChannelRequiresHello() throws Exception {
        Peer unauthenticated = peer(server.getMessagePort());
        unauthenticated.send(new Protocol.SendText("without hello"));
        unauthenticated.until(Protocol.Error.class);
    }

    @Test void malformedSerializedObjectsAreRejectedAtTheSocketBoundary() throws Exception {
        Peer invalid = peer(server.getMessagePort());
        invalid.out.writeObject(new java.util.Date());
        invalid.out.flush();
        assertThrows(IOException.class, invalid::read);
        Peer healthy = login("Healthy");
        create(healthy, "Still running");
    }

    @Test void partialStartupFailureReleasesAlreadyBoundPorts() throws Exception {
        int availableMessagePort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            availableMessagePort = reservation.getLocalPort();
        }
        try (ServerSocket occupiedFilePort = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerConfig d = ServerConfig.localEphemeral();
            ServerConfig conflicting = new ServerConfig("127.0.0.1", availableMessagePort,
                    occupiedFilePort.getLocalPort(), 0, d.maxSessions(), d.handshakeTimeoutMillis(),
                    d.outboundQueueCapacity(), d.outboundQueueBytes(), d.writeTimeoutMillis());
            try (ChatServer failed = new ChatServer(conflicting)) {
                assertThrows(BindException.class, failed::start);
                assertThrows(IllegalStateException.class, failed::start);
                try (ServerSocket released = new ServerSocket()) {
                    released.setReuseAddress(true);
                    released.bind(new InetSocketAddress("127.0.0.1", availableMessagePort));
                }
            }
        }
    }

    private Peer login(String name) throws Exception {
        Peer peer = peer(server.getMessagePort());
        peer.send(new Protocol.Hello(new User("client-controlled-id", name)));
        peer.welcome = peer.until(Protocol.Welcome.class);
        assertNotEquals("client-controlled-id", peer.welcome.user().getUserId());
        return peer;
    }

    private Protocol.RoomSummary create(Peer peer, String name) throws Exception {
        peer.send(new Protocol.CreateRoom(name));
        return peer.until(Protocol.RoomJoined.class).room();
    }

    private Peer bind(Peer owner) throws Exception {
        Peer files = peer(server.getFilePort());
        files.send(new Protocol.BindFile(owner.welcome.token()));
        files.until(Protocol.FileReady.class);
        return files;
    }

    private Protocol.RoomList roomList() throws Exception {
        Peer peer = peer(server.getRoomPort());
        peer.send(new Protocol.ListRooms());
        return peer.until(Protocol.RoomList.class);
    }

    private Peer peer(int port) throws IOException {
        Peer peer = new Peer(port);
        peers.add(peer);
        return peer;
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final ObjectOutputStream out;
        final ObjectInputStream in;
        Protocol.Welcome welcome;

        Peer(int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            socket.setSoTimeout(3_000);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            WireIO.installFilter(in);
        }
        void send(Serializable value) throws IOException { WireIO.write(out, value); }
        Object read() throws IOException, ClassNotFoundException { return WireIO.read(in); }
        <T> T until(Class<T> type) throws Exception {
            for (int i = 0; i < 30; i++) {
                Object value = read();
                if (type.isInstance(value)) return type.cast(value);
                if (value instanceof Protocol.Error error) fail("Unexpected server error: " + error);
            }
            throw new AssertionError("Missing " + type.getSimpleName());
        }
        Protocol.TextMessage text(String text) throws Exception {
            for (int i = 0; i < 30; i++) {
                Protocol.TextMessage message = until(Protocol.TextMessage.class);
                if (!message.system() && text.equals(message.content())) return message;
            }
            throw new AssertionError("Missing text: " + text);
        }
        boolean receivesText(String text) throws Exception {
            socket.setSoTimeout(200);
            try { text(text); return true; }
            catch (SocketTimeoutException expected) { return false; }
            finally { socket.setSoTimeout(3_000); }
        }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }
}
