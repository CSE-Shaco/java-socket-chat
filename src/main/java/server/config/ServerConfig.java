package server.config;

import shared.config.NetworkConfig;

/** Immutable settings; port zero is supported for isolated integration tests. */
public record ServerConfig(String bindHost, int messagePort, int filePort, int roomPort,
                           int maxSessions, int handshakeTimeoutMillis, int outboundQueueCapacity,
                           long outboundQueueBytes, int writeTimeoutMillis) {
    public ServerConfig {
        if (bindHost == null || bindHost.isBlank()) throw new IllegalArgumentException("Bind host is required");
        for (int port : new int[]{messagePort, filePort, roomPort})
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be 0..65535");
        if (maxSessions < 1 || handshakeTimeoutMillis < 1 || outboundQueueCapacity < 1
                || outboundQueueBytes < 1 || writeTimeoutMillis < 1)
            throw new IllegalArgumentException("Limits must be positive");
        if ((messagePort != 0 && (messagePort == filePort || messagePort == roomPort))
                || (filePort != 0 && filePort == roomPort))
            throw new IllegalArgumentException("Channel ports must differ");
    }
    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", NetworkConfig.MESSAGE_PORT, NetworkConfig.FILE_PORT,
                NetworkConfig.ROOM_PORT, 100, 5_000, 64, 20L * 1024 * 1024, 5_000);
    }
    public static ServerConfig localEphemeral() {
        return new ServerConfig("127.0.0.1", 0, 0, 0, 100, 5_000, 64, 20L * 1024 * 1024, 5_000);
    }
    public static ServerConfig fromArgs(String[] args) {
        ServerConfig d = defaults(); String bind = d.bindHost();
        int message = d.messagePort(), file = d.filePort(), room = d.roomPort(), max = d.maxSessions();
        int handshake = d.handshakeTimeoutMillis(), capacity = d.outboundQueueCapacity(), write = d.writeTimeoutMillis();
        long bytes = d.outboundQueueBytes();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value: " + args[i]);
            switch (args[i]) {
                case "--bind" -> bind = args[i + 1];
                case "--message-port" -> message = Integer.parseInt(args[i + 1]);
                case "--file-port" -> file = Integer.parseInt(args[i + 1]);
                case "--room-port" -> room = Integer.parseInt(args[i + 1]);
                case "--max-sessions" -> max = Integer.parseInt(args[i + 1]);
                case "--handshake-timeout-ms" -> handshake = Integer.parseInt(args[i + 1]);
                case "--outbound-queue-capacity" -> capacity = Integer.parseInt(args[i + 1]);
                case "--outbound-queue-bytes" -> bytes = Long.parseLong(args[i + 1]);
                case "--write-timeout-ms" -> write = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        return new ServerConfig(bind, message, file, room, max, handshake, capacity, bytes, write);
    }
}
