package shared.protocol;

import shared.domain.FileInfo;
import shared.domain.User;
import java.io.Serializable;
import java.util.List;

/** Versioned, explicitly typed messages shared by the three socket channels. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final int MAX_TEXT_LENGTH = 4_000;
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_NAME_LENGTH = 80;
    private Protocol() {}

    public sealed interface Request extends Serializable permits Hello, CreateRoom, JoinRoom,
            LeaveRoom, StartRandom, CancelRandom, SendText, BindFile, SendFile, ListRooms {}
    public sealed interface Response extends Serializable permits Welcome, RoomJoined, Lobby,
            Waiting, MatchFound, TextMessage, FileReady, FileMessage, RoomList, Error, ServerClosing {}
    public record Hello(User user) implements Request {}
    public record CreateRoom(String name) implements Request {}
    public record JoinRoom(String roomId) implements Request {}
    public record LeaveRoom() implements Request {}
    public record StartRandom() implements Request {}
    public record CancelRandom() implements Request {}
    public record SendText(String content) implements Request {}
    public record BindFile(String token) implements Request {}
    public record SendFile(FileInfo file) implements Request {}
    public record ListRooms() implements Request {}
    public record Welcome(String sessionId, String token, User user) implements Response {}
    public record RoomJoined(RoomSummary room) implements Response {}
    public record Lobby() implements Response {}
    public record Waiting() implements Response {}
    public record MatchFound(RoomSummary room) implements Response {}
    public record TextMessage(String senderId, String sender, String roomId, String content,
                              boolean system) implements Response {}
    public record FileReady() implements Response {}
    public record FileMessage(String senderId, String sender, String roomId, FileInfo file) implements Response {}
    public record RoomList(List<RoomSummary> rooms) implements Response {
        public RoomList { rooms = List.copyOf(rooms); }
    }
    public record Error(String code, String message) implements Response {}
    public record ServerClosing(String reason) implements Response {}
    public record RoomSummary(String id, String name, int participantCount, boolean anonymous) implements Serializable {}
    public record WireMessage(int version, Serializable payload) implements Serializable {}
}
