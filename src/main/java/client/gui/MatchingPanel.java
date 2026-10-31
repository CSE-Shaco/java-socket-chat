package client.gui;

import javax.swing.*;
import java.awt.*;

/** Matching card; cancellation does not close the session. */
final class MatchingPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JButton cancel = new JButton("Cancel matching");
    MatchingPanel(ChatApplicationWindow owner) {
        setLayout(new BorderLayout(12, 12));
        add(new JLabel("Waiting for another user…", SwingConstants.CENTER), BorderLayout.CENTER);
        add(cancel, BorderLayout.SOUTH);
        cancel.addActionListener(e -> owner.command(owner.client().cancelRandom(), "Cancelling matching…"));
    }
    void setBusy(boolean busy) { cancel.setEnabled(!busy); }
}
