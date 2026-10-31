package client;

import client.gui.ChatApplicationWindow;
import shared.config.NetworkConfig;
import javax.swing.SwingUtilities;

/** Desktop entry point. Configuration errors are reported before opening a window. */
public final class Client {
    private Client() { }
    public static void main(String[] args) {
        try {
            var config = NetworkConfig.fromArgs(args);
            SwingUtilities.invokeLater(() -> new ChatApplicationWindow(config));
        } catch (IllegalArgumentException error) {
            System.err.println("Invalid client configuration: " + error.getMessage());
            System.exit(1);
        }
    }
}
