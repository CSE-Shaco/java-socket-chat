package client.gui;

import shared.protocol.Protocol.RoomSummary;
import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Lobby card: requests use the existing session. */
final class LobbyPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final ChatApplicationWindow owner;
    private final JPanel rooms = new JPanel();
    private final JButton create = new JButton("Create Room");
    private final JButton random = new JButton("Random Chat");
    private final JButton refresh = new JButton("Refresh");
    private boolean busy, loading;

    LobbyPanel(ChatApplicationWindow owner) {
        this.owner = owner;
        setLayout(new BorderLayout(10, 10)); setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        rooms.setLayout(new BoxLayout(rooms, BoxLayout.Y_AXIS));
        add(new JLabel("Available chat rooms:"), BorderLayout.NORTH);
        add(new JScrollPane(rooms), BorderLayout.CENTER);
        JPanel buttons = new JPanel(); buttons.add(create); buttons.add(random); buttons.add(refresh);
        add(buttons, BorderLayout.SOUTH);
        create.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(owner, "Room name (1–80 characters):");
            if (name == null) return;
            name = name.strip();
            if (name.isEmpty() || name.length() > shared.protocol.Protocol.MAX_NAME_LENGTH) { owner.showError("Enter a room name of 1–80 characters."); return; }
            owner.command(owner.client().createRoom(name), "Creating room…");
        });
        random.addActionListener(e -> owner.command(owner.client().startRandom(), "Starting random chat…"));
        refresh.addActionListener(e -> owner.refreshRooms());
    }
    void updateRooms(List<RoomSummary> list) {
        rooms.removeAll();
        if (list.isEmpty()) rooms.add(new JLabel("No public rooms yet. Create one to get started."));
        for (RoomSummary room : list) {
            JPanel row = new JPanel(new BorderLayout(8, 8));
            row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
            JLabel roomLabel = new JLabel();
            roomLabel.putClientProperty("html.disable", true);
            roomLabel.setText(room.name() + "  (Users: " + room.participantCount() + ")"); row.add(roomLabel, BorderLayout.CENTER);
            JButton join = new JButton("Join");
            join.addActionListener(e -> owner.command(owner.client().joinRoom(room.id()), "Joining room…"));
            row.add(join, BorderLayout.EAST); rooms.add(row); rooms.add(Box.createVerticalStrut(8));
        }
        enableActions(); rooms.revalidate(); rooms.repaint();
    }
    void setLoading(boolean value) { loading = value; enableActions(); }
    void setBusy(boolean value) { busy = value; enableActions(); }
    private void enableActions() {
        create.setEnabled(!busy); random.setEnabled(!busy); refresh.setEnabled(!busy && !loading);
        for (Component row : rooms.getComponents()) if (row instanceof JPanel panel)
            for (Component child : panel.getComponents()) if (child instanceof JButton) child.setEnabled(!busy);
    }
}
