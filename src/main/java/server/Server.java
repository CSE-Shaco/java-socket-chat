package server;

import server.config.ServerConfig;
import shared.util.LoggerUtil;

/** Command-line server entry point. Defaults to loopback; opt in to LAN using --bind. */
public final class Server {
    private Server() {}
    public static void main(String[] args) {
        try (ChatServer server = new ChatServer(ServerConfig.fromArgs(args))) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "chat-shutdown"));
            server.start();
            LoggerUtil.log("Server ready: message=" + server.getMessagePort()
                    + ", file=" + server.getFilePort() + ", rooms=" + server.getRoomPort());
            server.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LoggerUtil.error("Server startup failed", e);
            System.exit(1);
        }
    }
}
