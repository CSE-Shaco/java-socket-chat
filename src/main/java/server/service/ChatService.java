package server.service;

import server.domain.ChatRoom;
import server.session.ClientSession;
import server.session.ClientSession.State;
import server.transport.Outbound;
import shared.domain.FileInfo;
import shared.domain.User;
import shared.protocol.Protocol;

import java.security.SecureRandom;
import java.util.*;

/**
 * Owns every membership and matching transition under one monitor. Output only enqueues;
 * serialization and socket writes happen on independent channel writers outside this monitor.
 */
public final class ChatService implements AutoCloseable {
    private final int maxSessions;
    private final Map<String, ClientSession> sessions = new LinkedHashMap<>();
    private final Map<String, String> tokens = new HashMap<>();
    private final Map<String, ChatRoom> rooms = new LinkedHashMap<>();
    private final LinkedHashSet<String> waiting = new LinkedHashSet<>();
    private final SecureRandom random = new SecureRandom();
    private boolean closed;

    public ChatService() { this(100); }
    public ChatService(int maxSessions) {
        if (maxSessions < 1) throw new IllegalArgumentException("maxSessions must be positive");
        this.maxSessions = maxSessions;
    }

    public synchronized String register(User requestedUser, Outbound messages) throws RequestRejected {
        Objects.requireNonNull(messages, "messages");
        if (closed) throw reject("SERVER_CLOSING", "Server is stopping.");
        if (sessions.size() >= maxSessions) throw reject("LIMIT_EXCEEDED", "Server session limit reached.");
        if (requestedUser == null) throw reject("INVALID_INPUT", "User is required.");
        String display = name(requestedUser.getUsername(), "Display name");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String id = UUID.randomUUID().toString();
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ClientSession session = new ClientSession(id, token, new User(id, display), messages);
        sessions.put(id, session); tokens.put(token, id);
        deliver(messages, new Protocol.Welcome(id, token, session.user()));
        return id;
    }

    public synchronized void handle(String sessionId, Protocol.Request request) {
        ClientSession session = sessions.get(sessionId);
        if (session == null) return;
        try {
            if (request instanceof Protocol.CreateRoom create) {
                requireLobby(session);
                String roomName = name(create.name(), "Room name");
                if (rooms.values().stream().anyMatch(r -> !r.anonymous()
                        && r.summary().name().equalsIgnoreCase(roomName)))
                    throw reject("ALREADY_EXISTS", "A room with that name already exists.");
                ChatRoom room = new ChatRoom(UUID.randomUUID().toString(), roomName, false);
                rooms.put(room.id(), room);
                join(session, room);
            } else if (request instanceof Protocol.JoinRoom join) {
                requireLobby(session);
                ChatRoom room = rooms.get(join.roomId());
                if (room == null || room.anonymous()) throw reject("NOT_FOUND", "Room is unavailable.");
                join(session, room);
            } else if (request instanceof Protocol.LeaveRoom) {
                leave(session, true);
                deliver(session.messages(), new Protocol.Lobby());
            } else if (request instanceof Protocol.StartRandom) {
                startRandom(session);
            } else if (request instanceof Protocol.CancelRandom) {
                if (session.state() == State.IN_ROOM)
                    throw reject("INVALID_STATE", "A match has already been made; leave the room to continue.");
                waiting.remove(session.id()); session.transition(State.LOBBY, null);
                deliver(session.messages(), new Protocol.Lobby());
            } else if (request instanceof Protocol.SendText text) {
                ChatRoom room = currentRoom(session);
                if (text.content() == null || text.content().isBlank()
                        || text.content().length() > Protocol.MAX_TEXT_LENGTH)
                    throw reject("INVALID_INPUT", "Message must contain 1 to 4000 characters.");
                broadcast(room, new Protocol.TextMessage(session.user().getUserId(), display(session, room),
                        room.id(), text.content(), false));
            } else if (request instanceof Protocol.ListRooms) {
                deliver(session.messages(), new Protocol.RoomList(rooms()));
            } else {
                throw reject("INVALID_STATE", "Request is not valid on the message channel.");
            }
        } catch (RequestRejected e) {
            deliver(session.messages(), new Protocol.Error(e.code(), e.getMessage()));
        }
    }

    public synchronized String bindFile(String token, Outbound output) throws RequestRejected {
        ClientSession session = token == null ? null : sessions.get(tokens.get(token));
        if (session == null) throw reject("UNAUTHORIZED", "Unknown or expired session token.");
        if (session.files() != null) throw reject("INVALID_STATE", "A file channel is already connected.");
        session.files(output);
        deliver(output, new Protocol.FileReady());
        return session.id();
    }

    public synchronized void sendFile(String sessionId, FileInfo file) {
        ClientSession session = sessions.get(sessionId);
        if (session == null || session.files() == null) return;
        try {
            ChatRoom room = currentRoom(session);
            validateFile(file);
            Protocol.FileMessage response = new Protocol.FileMessage(session.user().getUserId(),
                    display(session, room), room.id(), file);
            for (String participant : room.participants()) {
                ClientSession receiver = sessions.get(participant);
                if (receiver != null && receiver.files() != null) deliver(receiver.files(), response);
            }
        } catch (RequestRejected e) {
            deliver(session.files(), new Protocol.Error(e.code(), e.getMessage()));
        }
    }

    public synchronized void disconnect(String sessionId) {
        ClientSession session = sessions.get(sessionId);
        if (session == null) return;
        leave(session, true);
        sessions.remove(sessionId); tokens.remove(session.token());
        session.transition(State.CLOSED, null);
        session.messages().close();
        if (session.files() != null) session.files().close();
    }

    public synchronized void fileDisconnected(String sessionId, Outbound channel) {
        ClientSession session = sessions.get(sessionId);
        if (session != null && session.files() == channel) session.files(null);
    }

    public synchronized List<Protocol.RoomSummary> rooms() {
        return rooms.values().stream().filter(room -> !room.anonymous()).map(ChatRoom::summary)
                .sorted(Comparator.comparing(Protocol.RoomSummary::name)).toList();
    }
    public synchronized int getSessionCount() { return sessions.size(); }
    public synchronized int getWaitingCount() { return waiting.size(); }
    public synchronized int getRoomCount() { return rooms.size(); }
    public synchronized State getState(String sessionId) {
        ClientSession session = sessions.get(sessionId);
        return session == null ? State.CLOSED : session.state();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        List<ClientSession> closing = new ArrayList<>(sessions.values());
        sessions.clear(); tokens.clear(); waiting.clear(); rooms.clear();
        for (ClientSession session : closing) {
            session.transition(State.CLOSED, null);
            session.messages().close();
            if (session.files() != null) session.files().close();
        }
    }

    private void startRandom(ClientSession requester) throws RequestRejected {
        if (requester.state() == State.WAITING) {
            deliver(requester.messages(), new Protocol.Waiting()); return;
        }
        requireLobby(requester);
        enqueueRandom(requester);
        if (!matchRandom(requester)) deliver(requester.messages(), new Protocol.Waiting());
    }

    private void enqueueRandom(ClientSession session) {
        session.transition(State.WAITING, null);
        waiting.add(session.id());
    }

    /** Leaves incompatible candidates queued in their original order. */
    private boolean matchRandom(ClientSession requester) {
        ClientSession partner = null;
        Iterator<String> candidates = waiting.iterator();
        while (candidates.hasNext()) {
            String candidate = candidates.next();
            ClientSession possible = sessions.get(candidate);
            if (possible == null || possible.state() != State.WAITING) {
                candidates.remove();
            } else if (!candidate.equals(requester.id())
                    && !Objects.equals(requester.lastRandomPartnerId(), candidate)
                    && !Objects.equals(possible.lastRandomPartnerId(), requester.id())) {
                partner = possible;
                candidates.remove();
                break;
            }
        }
        if (partner == null) return false;
        waiting.remove(requester.id());
        ChatRoom room = new ChatRoom(UUID.randomUUID().toString(), "Random chat", true);
        rooms.put(room.id(), room);
        room.add(partner.id()); room.add(requester.id());
        partner.matchedWith(requester.id()); requester.matchedWith(partner.id());
        partner.transition(State.IN_ROOM, room.id()); requester.transition(State.IN_ROOM, room.id());
        Protocol.MatchFound matched = new Protocol.MatchFound(room.summary());
        deliver(partner.messages(), matched); deliver(requester.messages(), matched);
        return true;
    }

    private void join(ClientSession session, ChatRoom room) {
        room.add(session.id()); session.transition(State.IN_ROOM, room.id());
        deliver(session.messages(), new Protocol.RoomJoined(room.summary()));
        broadcast(room, system(room, display(session, room) + " has entered the room."));
    }
    private void leave(ClientSession session, boolean announce) {
        waiting.remove(session.id());
        ChatRoom room = rooms.get(session.roomId());
        session.transition(State.LOBBY, null);
        if (room == null) return;
        room.remove(session.id());
        if (room.anonymous()) {
            // A random room always ends when either participant leaves. The departing
            // session is already out of the queue before its peer can be matched again.
            rooms.remove(room.id());
            for (String participant : room.participants()) {
                ClientSession survivor = sessions.get(participant);
                if (survivor != null && survivor.state() == State.IN_ROOM
                        && room.id().equals(survivor.roomId())) {
                    enqueueRandom(survivor);
                    deliver(survivor.messages(), new Protocol.Waiting());
                    matchRandom(survivor);
                }
            }
        } else if (room.isEmpty()) rooms.remove(room.id());
        else if (announce) broadcast(room, system(room, display(session, room) + " has left the room."));
    }
    private ChatRoom currentRoom(ClientSession session) throws RequestRejected {
        ChatRoom room = rooms.get(session.roomId());
        if (session.state() != State.IN_ROOM || room == null)
            throw reject("INVALID_STATE", "Join a room first.");
        return room;
    }
    private void requireLobby(ClientSession session) throws RequestRejected {
        if (session.state() != State.LOBBY) throw reject("INVALID_STATE", "Return to the lobby first.");
    }
    private void broadcast(ChatRoom room, Protocol.Response response) {
        for (String id : room.participants()) {
            ClientSession session = sessions.get(id);
            if (session != null) deliver(session.messages(), response);
        }
    }
    private static void deliver(Outbound output, Protocol.Response response) {
        if (!output.offer(response)) output.close();
    }
    private static String display(ClientSession session, ChatRoom room) {
        return room.anonymous() ? "unknown" : session.user().getUsername();
    }
    private static Protocol.TextMessage system(ChatRoom room, String message) {
        return new Protocol.TextMessage("", "", room.id(), message, true);
    }
    private static String name(String value, String label) throws RequestRejected {
        if (value == null) throw reject("INVALID_INPUT", label + " is required.");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > Protocol.MAX_NAME_LENGTH
                || trimmed.chars().anyMatch(Character::isISOControl))
            throw reject("INVALID_INPUT", label + " must contain 1 to 80 printable characters.");
        return trimmed;
    }
    private static void validateFile(FileInfo file) throws RequestRejected {
        if (file == null || file.getData() == null || file.getData().length > Protocol.MAX_FILE_BYTES)
            throw reject("LIMIT_EXCEEDED", "Files must be at most 10 MiB.");
        String name = file.getFileName();
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.chars().anyMatch(Character::isISOControl))
            throw reject("INVALID_INPUT", "A valid file name is required.");
    }
    private static RequestRejected reject(String code, String message) { return new RequestRejected(code, message); }
}
