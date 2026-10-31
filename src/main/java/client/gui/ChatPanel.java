package client.gui;

import client.attachment.AttachmentService;
import client.gui.component.ChatBubble;
import client.gui.component.ChatTranscript;
import shared.domain.FileInfo;
import shared.domain.User;
import shared.protocol.Protocol;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Chat card. All disk/network work runs outside the event dispatch thread. */
final class ChatPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final ChatApplicationWindow owner;
    private final JLabel title = new JLabel();
    private final ChatTranscript messages = new ChatTranscript();
    private final JScrollPane scroll = new JScrollPane(messages);
    private final JTextField input = new JTextField();
    private final JButton send = new JButton("Send");
    private final JButton file = new JButton("Send File");
    private final JButton leave = new JButton("Leave Room");
    private boolean busy, sending;
    private Protocol.RoomSummary room;
    private final java.util.ArrayDeque<Integer> attachmentSizes = new java.util.ArrayDeque<>();
    private long attachmentBytes;
    private long viewGeneration;

    ChatPanel(ChatApplicationWindow owner) {
        this.owner = owner;
        title.putClientProperty("html.disable", true);
        setLayout(new BorderLayout(8, 8)); setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ChatTranscript.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(title, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        JLabel label = new JLabel("Message (up to 4,000 characters)"); label.setLabelFor(input);
        JPanel composer = new JPanel(new BorderLayout(4, 4)); composer.add(label, BorderLayout.NORTH); composer.add(input, BorderLayout.CENTER);
        footer.add(composer, BorderLayout.CENTER);
        JPanel buttons = new JPanel(); buttons.add(leave); buttons.add(file); buttons.add(send);
        footer.add(buttons, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        send.addActionListener(e -> send()); input.addActionListener(e -> send());
        file.addActionListener(e -> sendFile());
        leave.addActionListener(e -> owner.command(owner.client().leaveRoom(), "Leaving room…"));
    }
    void enter(Protocol.RoomSummary next) {
        if (room == null || !room.id().equals(next.id())) clear();
        room = next; title.setText(next.name() + (next.anonymous() ? " · Random chat" : ""));
        updateControls();
    }
    void clear() { viewGeneration++; room = null; sending = false; input.setText(""); attachmentSizes.clear(); attachmentBytes = 0; messages.removeAll(); messages.revalidate(); messages.repaint(); }
    void focusInput() { input.requestFocusInWindow(); }
    void setBusy(boolean value) { busy = value; updateControls(); }
    private void updateControls() {
        boolean active = !busy && !sending && room != null;
        input.setEnabled(active); send.setEnabled(active); file.setEnabled(active && owner.client() != null && owner.client().isFileReady());
        leave.setEnabled(!busy && room != null);
    }
    private void send() {
        if (busy || sending || room == null || input.getText().isBlank()) return;
        String content = input.getText();
        long attempt = viewGeneration;
        sending = true; updateControls();
        owner.client().sendText(content).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (attempt != viewGeneration) return;
            sending = false; updateControls();
            if (failure != null) owner.showError(ChatApplicationWindow.message(failure));
            else if (input.getText().equals(content)) input.setText("");
            focusInput();
        }));
    }
    private void sendFile() {
        if (busy || sending || room == null) return;
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Send file (up to 10 MiB)");
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        long attempt = viewGeneration;
        sending = true; updateControls();
        owner.client().sendFile(chooser.getSelectedFile().toPath()).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (attempt != viewGeneration) return;
            sending = false; updateControls();
            if (failure != null) owner.showError(ChatApplicationWindow.message(failure));
        }));
    }
    void append(Protocol.TextMessage message, User user) {
        if (room != null && !room.id().equals(message.roomId())) return;
        addMessage(new ChatBubble(message, user != null && user.getUserId().equals(message.senderId())));
    }
    void append(Protocol.FileMessage message, User user) {
        if (room != null && !room.id().equals(message.roomId())) return;
        FileInfo info = message.file();
        boolean mine = user != null && user.getUserId().equals(message.senderId());
        JButton save = new JButton("Save"); save.addActionListener(e -> save(info, save));
        JButton preview = null;
        if (info.isImage()) {
            preview = new JButton("Preview");
            JButton previewButton = preview;
            preview.addActionListener(e -> preview(info, previewButton));
        }
        String size = info.getData().length < 1024 ? info.getData().length + " B"
                : String.format(java.util.Locale.ROOT, "%.1f KiB", info.getData().length / 1024.0);
        addMessage(ChatBubble.attachment(message.sender(), info.getFileName(), size, mine, save, preview), info.getData().length);
    }
    private void addMessage(Component component) { addMessage(component, 0); }
    private void addMessage(Component component, int bytes) {
        messages.add(component); attachmentSizes.addLast(bytes); attachmentBytes += bytes;
        // Bound both message count and retained file data.
        while (messages.getComponentCount() > 100 || attachmentBytes > 20L * 1024 * 1024) {
            messages.remove(0); attachmentBytes -= attachmentSizes.removeFirst();
        }
        messages.revalidate(); messages.repaint();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
    }
    private void save(FileInfo info, JButton button) {
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new File(info.getFileName()));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(owner, "Replace the existing file?", "Save file", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        button.setEnabled(false);
        CompletableFuture.runAsync(() -> {
            try { AttachmentService.save(info, path); }
            catch (IOException e) { throw new CompletionException(e); }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            button.setEnabled(true);
            if (failure != null) owner.showError("Could not save file: " + ChatApplicationWindow.message(failure));
            else JOptionPane.showMessageDialog(owner, "File saved.");
        }));
    }
    private void preview(FileInfo info, JButton button) {
        button.setEnabled(false);
        CompletableFuture.supplyAsync(() -> {
            try { return AttachmentService.preview(info); }
            catch (IOException e) { throw new CompletionException(e); }
        }).whenComplete((image, failure) -> SwingUtilities.invokeLater(() -> {
            button.setEnabled(true);
            if (failure != null) owner.showError(ChatApplicationWindow.message(failure));
            else JOptionPane.showMessageDialog(owner, new JLabel(new ImageIcon(image)), info.getFileName(), JOptionPane.PLAIN_MESSAGE);
        }));
    }
}
