package client.gui;

import client.core.ChatClient;
import shared.config.NetworkConfig.ClientConfig;
import shared.domain.User;
import shared.protocol.Protocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;

/** Owns one session across login, lobby, matching and chat screens. All UI access is on EDT. */
public final class ChatApplicationWindow extends JFrame {
    private static final long serialVersionUID = 1L;
    private final transient ClientConfig config;
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final JTextField username = new JTextField();
    private final JButton connect = new JButton("Connect");
    private final JLabel status = new JLabel("Enter a display name to connect.");
    private final LobbyPanel lobby;
    private final MatchingPanel waiting;
    private final ChatPanel chat;
    private transient ChatClient client;
    private boolean busy;
    private long generation;
    private String page = "login";

    public ChatApplicationWindow(ClientConfig config) {
        this.config = config;
        setTitle("Java Real-Time Chat");
        setSize(560, 500);
        setMinimumSize(new Dimension(460, 350));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel login = new JPanel(new GridBagLayout());
        JPanel form = new JPanel(new GridLayout(0, 1, 8, 8));
        JLabel label = new JLabel("Display name (1–80 characters)");
        label.setLabelFor(username);
        form.add(label); form.add(username); form.add(connect);
        form.add(new JLabel("Server: " + config.host() + ":" + config.messagePort()));
        login.add(form);
        lobby = new LobbyPanel(this);
        waiting = new MatchingPanel(this);
        chat = new ChatPanel(this);
        pages.add(login, "login"); pages.add(lobby, "lobby");
        pages.add(waiting, "waiting"); pages.add(chat, "chat");
        status.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        add(pages, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        connect.addActionListener(e -> connect()); username.addActionListener(e -> connect());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (client != null) client.close(); dispose();
            }
        });
        getRootPane().setDefaultButton(connect);
        setVisible(true);
    }

    ChatClient client() { return client; }
    boolean busy() { return busy; }

    private void connect() {
        if (busy) return;
        String name = username.getText().strip();
        if (name.isEmpty() || name.length() > Protocol.MAX_NAME_LENGTH) { showError("Enter a display name of 1–80 characters."); return; }
        if (client != null) client.close();
        long attempt = ++generation;
        ChatClient next = new ChatClient(config,
                event -> SwingUtilities.invokeLater(() -> { if (generation == attempt) onEvent(event); }),
                reason -> SwingUtilities.invokeLater(() -> { if (generation == attempt) connectionLost(reason); }));
        client = next;
        setBusy(true); status.setText("Connecting…");
        next.connect(new User(java.util.UUID.randomUUID().toString(), name)).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (client != next || generation != attempt || !isDisplayable()) return;
            setBusy(false);
            if (failure != null) {
                status.setText("Connection failed. Check the server and try again."); showError(message(failure));
            } else { showPage("lobby"); refreshRooms(); }
        }));
    }

    private void onEvent(Object event) {
        if (!isDisplayable()) return;
        if (event instanceof Protocol.RoomJoined joined) enterChat(joined.room());
        else if (event instanceof Protocol.MatchFound matched) enterChat(matched.room());
        else if (event instanceof Protocol.Waiting) {
            chat.clear(); setBusy(false); showPage("waiting"); status.setText("Waiting for another user…");
        } else if (event instanceof Protocol.Lobby) {
            setBusy(false); chat.clear(); showPage("lobby"); refreshRooms();
        } else if (event instanceof Protocol.TextMessage text) chat.append(text, client.user());
        else if (event instanceof Protocol.FileMessage file) chat.append(file, client.user());
        else if (event instanceof Protocol.Error error) {
            setBusy(false); showError(error.message());
        }
    }

    private void enterChat(Protocol.RoomSummary room) {
        setBusy(false); chat.enter(room); showPage("chat"); status.setText("Connected · " + room.name());
    }
    private void connectionLost(String reason) {
        generation++;
        setBusy(false); chat.clear(); showPage("login"); status.setText(reason);
    }
    private void showPage(String name) {
        page = name; cards.show(pages, name);
        getRootPane().setDefaultButton("login".equals(name) ? connect : null);
        if ("chat".equals(name)) chat.focusInput();
    }
    void setBusy(boolean value) {
        busy = value; connect.setEnabled(!value); username.setEnabled(!value);
        lobby.setBusy(value); waiting.setBusy(value); chat.setBusy(value);
    }
    void command(CompletableFuture<Void> action, String pending) {
        long attempt = generation;
        setBusy(true); status.setText(pending);
        action.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (generation != attempt || !isDisplayable()) return;
            if (failure != null) { setBusy(false); showError(message(failure)); }
        }));
    }
    void refreshRooms() {
        long attempt = generation;
        lobby.setLoading(true); status.setText("Loading rooms…");
        client.listRooms().whenComplete((rooms, failure) -> SwingUtilities.invokeLater(() -> {
            if (generation != attempt || !isDisplayable() || !"lobby".equals(page)) return;
            lobby.setLoading(false);
            if (failure != null) { status.setText("Could not load rooms. Use Refresh to retry."); showError(message(failure)); }
            else { lobby.updateRooms(rooms.rooms()); status.setText("Connected · Choose a room or start random chat."); }
        }));
    }
    void showError(String text) { JOptionPane.showMessageDialog(this, text, "Chat", JOptionPane.ERROR_MESSAGE); }
    static String message(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
