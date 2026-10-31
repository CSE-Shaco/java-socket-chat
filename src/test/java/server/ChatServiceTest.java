package server;

import org.junit.jupiter.api.Test;
import server.service.ChatService;
import server.service.RequestRejected;
import server.session.ClientSession.State;
import server.transport.Outbound;
import shared.domain.FileInfo;
import shared.domain.User;
import shared.protocol.Protocol;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceTest {
    @Test void disconnectedRandomPeerReturnsSurvivorToWaitingAndThenANewRoom() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
        randomMatch(service, alice, bob);
        String oldRoom = lastMatch(bob).room().id();
        service.disconnect(alice.id());
        assertEquals(State.CLOSED, service.getState(alice.id()));
        assertEquals(State.WAITING, service.getState(bob.id()));
        assertEquals(0, service.getRoomCount());
        assertEquals(1, service.getWaitingCount());
        assertInstanceOf(Protocol.Waiting.class, List.copyOf(bob.messages.responses).getLast());

        Endpoint carol = register(service, "Carol");
        service.handle(carol.id(), new Protocol.StartRandom());
        assertEquals(State.IN_ROOM, service.getState(bob.id()));
        assertEquals(lastMatch(bob).room().id(), lastMatch(carol).room().id());
        assertNotEquals(oldRoom, lastMatch(bob).room().id());
        List<Protocol.Response> events = List.copyOf(bob.messages.responses);
        assertInstanceOf(Protocol.Waiting.class, events.get(events.size() - 2));
        assertInstanceOf(Protocol.MatchFound.class, events.getLast());
    }

    @Test void explicitRandomLeaveRematchesOnlyTheRemainingPeer() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob"), carol = register(service, "Carol");
        randomMatch(service, alice, bob);
        service.handle(carol.id(), new Protocol.StartRandom());
        service.handle(alice.id(), new Protocol.LeaveRoom());
        assertEquals(State.LOBBY, service.getState(alice.id()));
        assertInstanceOf(Protocol.Lobby.class, List.copyOf(alice.messages.responses).getLast());
        assertEquals(lastMatch(bob).room().id(), lastMatch(carol).room().id());
        assertEquals(1, service.getRoomCount());
        assertEquals(0, service.getWaitingCount());
        List<Protocol.Response> events = List.copyOf(bob.messages.responses);
        assertInstanceOf(Protocol.Waiting.class, events.get(events.size() - 2));
        assertInstanceOf(Protocol.MatchFound.class, events.getLast());
    }

    @Test void previousRandomPartnersStayWaitingAndCancellationDoesNotForgetThem() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
        randomMatch(service, alice, bob);
        service.handle(alice.id(), new Protocol.LeaveRoom());
        service.handle(alice.id(), new Protocol.StartRandom());
        assertEquals(0, service.getRoomCount());
        assertEquals(2, service.getWaitingCount());
        assertEquals(State.WAITING, service.getState(alice.id()));
        assertEquals(State.WAITING, service.getState(bob.id()));
        service.handle(bob.id(), new Protocol.CancelRandom());
        service.handle(bob.id(), new Protocol.StartRandom());
        service.handle(alice.id(), new Protocol.StartRandom());
        assertEquals(0, service.getRoomCount());
        assertEquals(2, service.getWaitingCount());
        assertEquals(1, alice.messages.of(Protocol.MatchFound.class).size());
        assertEquals(1, bob.messages.of(Protocol.MatchFound.class).size());
    }

    @Test void bothPartnersMustHaveMovedOnBeforeTheyCanMatchAgain() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
        Endpoint carol = register(service, "Carol"), dave = register(service, "Dave");
        randomMatch(service, alice, bob);
        service.handle(alice.id(), new Protocol.LeaveRoom());
        service.handle(alice.id(), new Protocol.StartRandom()); // Bob and Alice remain queued.
        service.handle(carol.id(), new Protocol.StartRandom()); // FIFO: Bob matches Carol.
        assertEquals(lastMatch(bob).room().id(), lastMatch(carol).room().id());
        service.handle(carol.id(), new Protocol.LeaveRoom()); // Bob has moved on, Alice has not.
        assertEquals(0, service.getRoomCount());
        assertEquals(2, service.getWaitingCount());
        assertEquals(1, alice.messages.of(Protocol.MatchFound.class).size());
        service.handle(dave.id(), new Protocol.StartRandom()); // FIFO: Alice matches Dave.
        assertEquals(lastMatch(alice).room().id(), lastMatch(dave).room().id());
        service.handle(dave.id(), new Protocol.LeaveRoom());
        assertEquals(lastMatch(alice).room().id(), lastMatch(bob).room().id());
        assertEquals(State.IN_ROOM, service.getState(alice.id()));
        assertEquals(State.IN_ROOM, service.getState(bob.id()));
        assertEquals(1, service.getRoomCount());
        assertEquals(0, service.getWaitingCount());
    }

    @Test void anIncompatibleQueueHeadDoesNotHideALaterCompatiblePartner() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob"), carol = register(service, "Carol");
        randomMatch(service, alice, carol);
        service.handle(carol.id(), new Protocol.LeaveRoom());
        service.handle(bob.id(), new Protocol.StartRandom()); // Alice now remembers Bob; Carol remembers Alice.
        service.handle(alice.id(), new Protocol.LeaveRoom());
        service.handle(bob.id(), new Protocol.CancelRandom());
        service.handle(alice.id(), new Protocol.StartRandom());
        service.handle(carol.id(), new Protocol.StartRandom()); // Queue: Alice, Carol. They cannot match.
        assertEquals(2, service.getWaitingCount());
        service.handle(bob.id(), new Protocol.StartRandom()); // Skip Alice, then match Carol.
        assertEquals(lastMatch(bob).room().id(), lastMatch(carol).room().id());
        assertEquals(State.WAITING, service.getState(alice.id()));
        assertEquals(1, service.getWaitingCount(), "Skipped candidates must remain queued");
        assertEquals(1, service.getRoomCount());
    }

    @Test void simultaneousRandomLeavesAndDisconnectsDoNotLeaveGhostRoomsOrWaiters() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            ChatService service = new ChatService(10);
            Endpoint alice = register(service, "Alice"), bob = register(service, "Bob"), carol = register(service, "Carol");
            randomMatch(service, alice, bob);
            service.handle(carol.id(), new Protocol.StartRandom());
            runConcurrently(List.of(
                    () -> service.disconnect(alice.id()),
                    () -> service.handle(bob.id(), new Protocol.LeaveRoom()),
                    () -> service.disconnect(carol.id())));
            assertEquals(State.LOBBY, service.getState(bob.id()));
            assertEquals(0, service.getRoomCount());
            assertEquals(0, service.getWaitingCount());
            assertEquals(1, service.getSessionCount());
            service.disconnect(bob.id());
            assertEquals(0, service.getSessionCount());
        }
    }

    @Test void leavingAPublicRoomDoesNotAutomaticallyMatchItsOtherParticipants() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob"), carol = register(service, "Carol");
        service.handle(alice.id(), new Protocol.CreateRoom("Public"));
        String roomId = alice.messages.of(Protocol.RoomJoined.class).getFirst().room().id();
        service.handle(bob.id(), new Protocol.JoinRoom(roomId));
        service.handle(carol.id(), new Protocol.StartRandom());
        service.handle(alice.id(), new Protocol.LeaveRoom());
        assertEquals(State.IN_ROOM, service.getState(bob.id()));
        assertEquals(State.WAITING, service.getState(carol.id()));
        assertEquals(1, service.getRoomCount());
        assertEquals(1, service.getWaitingCount());
        assertTrue(bob.messages.of(Protocol.Waiting.class).isEmpty());
    }

    @Test void disconnectedWaiterCannotBeMatched() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint gone = register(service, "Gone");
        service.handle(gone.id(), new Protocol.StartRandom());
        assertEquals(1, service.getWaitingCount());
        service.disconnect(gone.id());
        service.disconnect(gone.id());
        assertEquals(0, service.getWaitingCount());
        Endpoint next = register(service, "Next");
        service.handle(next.id(), new Protocol.StartRandom());
        assertEquals(1, service.getWaitingCount());
        assertEquals(0, service.getRoomCount());
        assertTrue(next.messages.of(Protocol.MatchFound.class).isEmpty());
    }

    @Test void simultaneousMatchingPairsEachSessionExactlyOnce() throws Exception {
        ChatService service = new ChatService(100);
        List<Endpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < 40; i++) endpoints.add(register(service, "User " + i));
        runConcurrently(endpoints.stream().<Runnable>map(endpoint ->
                () -> service.handle(endpoint.id(), new Protocol.StartRandom())).toList());
        assertEquals(0, service.getWaitingCount());
        assertEquals(20, service.getRoomCount());
        Map<String, Integer> participants = new HashMap<>();
        for (Endpoint endpoint : endpoints) {
            List<Protocol.MatchFound> matches = endpoint.messages.of(Protocol.MatchFound.class);
            assertEquals(1, matches.size());
            assertTrue(matches.getFirst().room().anonymous());
            participants.merge(matches.getFirst().room().id(), 1, Integer::sum);
        }
        assertEquals(20, participants.size());
        participants.values().forEach(count -> assertEquals(2, count));
        assertTrue(service.rooms().isEmpty());
        runConcurrently(endpoints.stream().<Runnable>map(endpoint -> () -> service.disconnect(endpoint.id())).toList());
        assertEquals(0, service.getSessionCount());
        assertEquals(0, service.getWaitingCount());
        assertEquals(0, service.getRoomCount());
    }

    @Test void repeatedConcurrentRandomRequestsCreateOnlyOneRoomForTheSamePair() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
        List<Runnable> retries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            retries.add(() -> service.handle(alice.id(), new Protocol.StartRandom()));
            retries.add(() -> service.handle(bob.id(), new Protocol.StartRandom()));
        }
        runConcurrently(retries);
        assertEquals(1, service.getRoomCount(), "Repeated requests must not create extra random rooms");
        assertEquals(0, service.getWaitingCount());
        assertEquals(1, alice.messages.of(Protocol.MatchFound.class).size());
        assertEquals(1, bob.messages.of(Protocol.MatchFound.class).size());
        assertEquals(alice.messages.of(Protocol.MatchFound.class).getFirst().room().id(),
                bob.messages.of(Protocol.MatchFound.class).getFirst().room().id());
        assertTrue(service.rooms().isEmpty(), "An anonymous room must never reappear as a public room");
        service.disconnect(alice.id());
        service.disconnect(bob.id());
        assertEquals(0, service.getRoomCount());
    }

    @Test void enteringAnAlreadyMatchedRoomCannotCreateASecondRoom() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
        service.handle(alice.id(), new Protocol.StartRandom());
        service.handle(bob.id(), new Protocol.StartRandom());
        String room = alice.messages.of(Protocol.MatchFound.class).getFirst().room().id();
        assertEquals(1, service.getRoomCount());
        // Regression: the old WaitingWindow opened a new ChatWindow which joined again.
        runConcurrently(List.of(
                () -> service.handle(alice.id(), new Protocol.JoinRoom(room)),
                () -> service.handle(bob.id(), new Protocol.JoinRoom(room))));
        assertEquals(1, service.getRoomCount());
        assertEquals(2, service.getSessionCount());
        assertEquals(1, alice.messages.of(Protocol.MatchFound.class).size());
        assertEquals(1, bob.messages.of(Protocol.MatchFound.class).size());
        assertTrue(alice.messages.of(Protocol.RoomJoined.class).isEmpty());
        assertTrue(bob.messages.of(Protocol.RoomJoined.class).isEmpty());
        assertFalse(alice.messages.of(Protocol.Error.class).isEmpty());
        assertFalse(bob.messages.of(Protocol.Error.class).isEmpty());
        assertTrue(service.rooms().isEmpty());
        service.handle(alice.id(), new Protocol.SendText("same matched room"));
        assertEquals(room, bob.messages.of(Protocol.TextMessage.class).getLast().roomId());
    }

    @Test void cancelAndMatchRaceNeverLeavesAnUntrackedParticipant() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            ChatService service = new ChatService(10);
            Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
            service.handle(alice.id(), new Protocol.StartRandom());
            runConcurrently(List.of(
                    () -> service.handle(alice.id(), new Protocol.CancelRandom()),
                    () -> service.handle(bob.id(), new Protocol.StartRandom())));
            // Either cancellation wins (Bob waits), or matching wins (both share one room).
            assertTrue((service.getWaitingCount() == 1 && service.getRoomCount() == 0)
                    || (service.getWaitingCount() == 0 && service.getRoomCount() == 1));
            service.disconnect(alice.id());
            service.disconnect(bob.id());
            assertEquals(0, service.getWaitingCount());
            assertEquals(0, service.getRoomCount());
        }
    }

    @Test void concurrentLastLeaveAndJoinNeverOrphansANonemptyRoom() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            ChatService service = new ChatService(10);
            Endpoint owner = register(service, "Owner"), guest = register(service, "Guest");
            service.handle(owner.id(), new Protocol.CreateRoom("Room"));
            String room = owner.messages.of(Protocol.RoomJoined.class).getFirst().room().id();
            runConcurrently(List.of(
                    () -> service.handle(owner.id(), new Protocol.LeaveRoom()),
                    () -> service.handle(guest.id(), new Protocol.JoinRoom(room))));
            boolean joined = !guest.messages.of(Protocol.RoomJoined.class).isEmpty();
            assertEquals(joined ? 1 : 0, service.getRoomCount());
            if (joined) assertEquals(1, service.rooms().getFirst().participantCount());
            else assertFalse(guest.messages.of(Protocol.Error.class).isEmpty());
            service.disconnect(owner.id());
            service.disconnect(guest.id());
            assertEquals(0, service.getRoomCount());
        }
    }

    @Test void capacityIsReleasedAfterDisconnect() throws Exception {
        ChatService service = new ChatService(1);
        Endpoint first = register(service, "First");
        assertEquals("LIMIT_EXCEEDED", assertThrows(RequestRejected.class,
                () -> register(service, "Rejected")).code());
        assertEquals(1, service.getSessionCount());
        service.disconnect(first.id());
        register(service, "Replacement");
        assertEquals(1, service.getSessionCount());
    }

    @Test void duplicateIdentityIsReplacedAndFileBindingCannotBeStolen() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob");
        assertNotEquals(alice.welcome().user().getUserId(), bob.welcome().user().getUserId());
        assertNotEquals("provided-id", alice.welcome().user().getUserId());
        assertEquals("UNAUTHORIZED", assertThrows(RequestRejected.class,
                () -> service.bindFile("unknown", new Capture())).code());
        Capture files = new Capture();
        assertEquals(alice.id(), service.bindFile(alice.welcome().token(), files));
        assertEquals("INVALID_STATE", assertThrows(RequestRejected.class,
                () -> service.bindFile(alice.welcome().token(), new Capture())).code());
    }

    @Test void anonymousRoomIsNeverRecreatedByAJoinRequest() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice"), bob = register(service, "Bob"), guest = register(service, "Guest");
        service.handle(alice.id(), new Protocol.StartRandom());
        service.handle(bob.id(), new Protocol.StartRandom());
        String room = alice.messages.of(Protocol.MatchFound.class).getFirst().room().id();
        service.disconnect(alice.id());
        service.disconnect(bob.id());
        service.handle(guest.id(), new Protocol.JoinRoom(room));
        assertFalse(guest.messages.of(Protocol.Error.class).isEmpty());
        assertEquals(0, service.getRoomCount());
        assertTrue(service.rooms().isEmpty());
    }

    @Test void oversizedAndUnsafeFilesAreRejectedBeforeDelivery() throws Exception {
        ChatService service = new ChatService(10);
        Endpoint alice = register(service, "Alice");
        service.handle(alice.id(), new Protocol.CreateRoom("Files"));
        Capture files = new Capture();
        service.bindFile(alice.welcome().token(), files);
        service.sendFile(alice.id(), new FileInfo("oversized.bin", new byte[Protocol.MAX_FILE_BYTES + 1], false));
        service.sendFile(alice.id(), new FileInfo("../escape", new byte[1], false));
        assertTrue(files.of(Protocol.FileMessage.class).isEmpty());
        // Errors may be sent to the file or control channel; neither request may be accepted.
        assertEquals(2, files.of(Protocol.Error.class).size() + alice.messages.of(Protocol.Error.class).size());
    }

    private static Endpoint register(ChatService service, String name) throws Exception {
        Capture messages = new Capture();
        String id = service.register(new User("provided-id", name), messages);
        return new Endpoint(id, messages, messages.of(Protocol.Welcome.class).getFirst());
    }

    private static void randomMatch(ChatService service, Endpoint first, Endpoint second) {
        service.handle(first.id(), new Protocol.StartRandom());
        service.handle(second.id(), new Protocol.StartRandom());
        assertEquals(lastMatch(first).room().id(), lastMatch(second).room().id());
    }

    private static Protocol.MatchFound lastMatch(Endpoint endpoint) {
        return endpoint.messages.of(Protocol.MatchFound.class).getLast();
    }

    private static void runConcurrently(List<Runnable> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<? extends Future<?>> futures = actions.stream().map(action -> executor.submit(() -> {
                start.await();
                action.run();
                return null;
            })).toList();
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        }
    }

    private record Endpoint(String id, Capture messages, Protocol.Welcome welcome) {}
    private static final class Capture implements Outbound {
        private final Queue<Protocol.Response> responses = new ConcurrentLinkedQueue<>();
        @Override public boolean offer(Protocol.Response response) { responses.add(response); return true; }
        @Override public void close() {}
        <T> List<T> of(Class<T> type) { return responses.stream().filter(type::isInstance).map(type::cast).toList(); }
    }
}
