package shared.config;

/** Defaults and command-line settings shared by the desktop entry point. */
public final class NetworkConfig {
    public static final String HOST = "localhost";
    public static final int MESSAGE_PORT = 12345;
    public static final int FILE_PORT = 12346;
    public static final int ROOM_PORT = 12347;
    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    private NetworkConfig() {}
    public record ClientConfig(String host, int messagePort, int filePort, int roomPort) {
        public ClientConfig {
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Host is required");
            checkPort(messagePort); checkPort(filePort); checkPort(roomPort);
        }
    }
    public static ClientConfig defaults() { return new ClientConfig(HOST, MESSAGE_PORT, FILE_PORT, ROOM_PORT); }
    public static ClientConfig fromArgs(String[] args) {
        String host = HOST; int message = MESSAGE_PORT, file = FILE_PORT, room = ROOM_PORT;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value: " + args[i]);
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--message-port" -> message = Integer.parseInt(args[i + 1]);
                case "--file-port" -> file = Integer.parseInt(args[i + 1]);
                case "--room-port" -> room = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        return new ClientConfig(host, message, file, room);
    }
    private static void checkPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
    }
}
