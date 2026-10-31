package server.domain;

import shared.protocol.Protocol.RoomSummary;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure room state; transport and synchronization are owned by ChatService. */
public final class ChatRoom {
    private final String id;
    private final String name;
    private final boolean anonymous;
    private final Set<String> participants = new LinkedHashSet<>();
    public ChatRoom(String id, String name, boolean anonymous) {
        this.id = id; this.name = name; this.anonymous = anonymous;
    }
    public String id() { return id; }
    public boolean anonymous() { return anonymous; }
    public void add(String sessionId) { participants.add(sessionId); }
    public void remove(String sessionId) { participants.remove(sessionId); }
    public List<String> participants() { return List.copyOf(participants); }
    public boolean isEmpty() { return participants.isEmpty(); }
    public RoomSummary summary() { return new RoomSummary(id, name, participants.size(), anonymous); }
}
