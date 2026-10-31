package server.session;

import server.transport.Outbound;
import shared.domain.User;

/** Mutable state is accessed only while holding the owning ChatService monitor. */
public final class ClientSession {
    public enum State { LOBBY, WAITING, IN_ROOM, CLOSED }
    private final String id;
    private final String token;
    private final User user;
    private final Outbound messages;
    private Outbound files;
    private State state = State.LOBBY;
    private String roomId;
    private String lastRandomPartnerId;
    public ClientSession(String id, String token, User user, Outbound messages) {
        this.id = id; this.token = token; this.user = user; this.messages = messages;
    }
    public String id() { return id; }
    public String token() { return token; }
    public User user() { return user; }
    public Outbound messages() { return messages; }
    public Outbound files() { return files; }
    public void files(Outbound files) { this.files = files; }
    public State state() { return state; }
    public String roomId() { return roomId; }
    public String lastRandomPartnerId() { return lastRandomPartnerId; }
    public void matchedWith(String partnerId) { lastRandomPartnerId = partnerId; }
    public void transition(State state, String roomId) { this.state = state; this.roomId = roomId; }
}
