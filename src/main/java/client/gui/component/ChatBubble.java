package client.gui.component;

import shared.protocol.Protocol.TextMessage;

import javax.swing.*;
import java.awt.*;

/** A plain-text message or attachment aligned within the transcript. */
public final class ChatBubble extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int MARGIN = 12;
    private static final int MAX_CARD_WIDTH = 380;
    private static final int PAD_X = 14;
    private static final int PAD_Y = 10;
    private static final Color SENT = new Color(28, 104, 91);
    private static final Color RECEIVED = Color.WHITE;
    private static final Color SYSTEM = new Color(227, 234, 231);
    private static final Color INK = new Color(35, 48, 46);
    private static final Color MUTED = new Color(79, 99, 94);
    private static final Color BORDER = new Color(220, 229, 225);

    private final boolean mine;
    private final boolean system;
    private final BubbleCard card;
    private final BubbleText body;
    private final BubbleText sender;
    private final JPanel actions;

    public ChatBubble(TextMessage message, boolean mine) {
        this(message.sender(), message.content(), mine, message.system(), null, null);
    }

    /** Creates an attachment bubble with the same alignment and readable wrapping as text. */
    public static ChatBubble attachment(String sender, String fileName, String size, boolean mine,
                                        JButton save, JButton preview) {
        return new ChatBubble(sender, fileName + "\n" + size, mine, false, save, preview);
    }

    private ChatBubble(String senderName, String content, boolean mine, boolean system,
                       JButton save, JButton preview) {
        this.mine = mine;
        this.system = system;
        setOpaque(false);
        setLayout(null);
        card = new BubbleCard(system ? SYSTEM : mine ? SENT : RECEIVED, !mine && !system);
        card.setLayout(null);
        add(card);

        Color foreground = mine && !system ? Color.WHITE : INK;
        Font base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        body = new BubbleText(content, base.deriveFont(13f), foreground);
        card.add(body);
        if (!mine && !system && senderName != null && !senderName.isBlank()) {
            sender = new BubbleText(senderName, base.deriveFont(Font.BOLD, 11f), MUTED);
            card.add(sender);
        } else {
            sender = null;
        }
        if (save != null) {
            actions = new JPanel(new GridLayout(1, preview == null ? 1 : 2, 6, 0));
            actions.setOpaque(false);
            actions.add(save);
            if (preview != null) actions.add(preview);
            card.add(actions);
        } else {
            actions = null;
        }
        getAccessibleContext().setAccessibleName(system ? "System message" : mine ? "Your message" : "Message from " + senderName);
    }

    /** Called by the transcript layout with its current viewport width. */
    public int preferredHeightForWidth(int width) {
        int cardWidth = cardWidth(width);
        return cardHeight(cardWidth) + 8;
    }

    @Override public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : 480;
        return new Dimension(width, preferredHeightForWidth(width));
    }

    @Override public void doLayout() {
        int width = getWidth();
        int cardWidth = cardWidth(width);
        int height = cardHeight(cardWidth);
        int x = system ? (width - cardWidth) / 2 : mine ? width - MARGIN - cardWidth : MARGIN;
        card.setBounds(Math.max(0, x), 4, cardWidth, height);
        int innerWidth = Math.max(1, cardWidth - PAD_X * 2);
        int y = PAD_Y;
        if (sender != null) {
            int senderHeight = sender.heightForWidth(innerWidth);
            sender.setBounds(PAD_X, y, innerWidth, senderHeight);
            y += senderHeight + 4;
        }
        int bodyHeight = body.heightForWidth(innerWidth);
        body.setBounds(PAD_X, y, innerWidth, bodyHeight);
        y += bodyHeight;
        if (actions != null) {
            y += 8;
            int actionHeight = actionHeight(innerWidth);
            actions.setBounds(PAD_X, y, innerWidth, actionHeight);
            actions.doLayout();
        }
    }

    private int cardWidth(int width) {
        int available = Math.max(1, width - 2 * MARGIN);
        int limit = Math.max(1, Math.min(MAX_CARD_WIDTH, (int) (available * (system ? .82 : .78))));
        int natural = body.naturalWidth();
        if (sender != null) natural = Math.max(natural, sender.naturalWidth());
        if (actions != null) natural = Math.max(natural, actions.getPreferredSize().width);
        return Math.min(limit, Math.max(Math.min(limit, 80), natural + PAD_X * 2 + 8));
    }

    private int cardHeight(int cardWidth) {
        int innerWidth = Math.max(1, cardWidth - PAD_X * 2);
        int height = 2 * PAD_Y + body.heightForWidth(innerWidth);
        if (sender != null) height += sender.heightForWidth(innerWidth) + 4;
        if (actions != null) height += 8 + actionHeight(innerWidth);
        return height;
    }

    private int actionHeight(int width) {
        GridLayout layout = (GridLayout) actions.getLayout();
        int count = actions.getComponentCount();
        int minButtonWidth = 0;
        for (Component component : actions.getComponents())
            minButtonWidth = Math.max(minButtonWidth, component.getPreferredSize().width);
        boolean stack = count > 1 && width < count * minButtonWidth + (count - 1) * layout.getHgap();
        layout.setRows(stack ? count : 1);
        layout.setColumns(stack ? 1 : count);
        layout.setVgap(stack ? 4 : 0);
        return actions.getPreferredSize().height;
    }

    private static final class BubbleCard extends JPanel {
        private static final long serialVersionUID = 1L;
        private final Color fill;
        private final boolean outline;
        BubbleCard(Color fill, boolean outline) { this.fill = fill; this.outline = outline; setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                if (outline) { g.setColor(BORDER); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20); }
            } finally { g.dispose(); }
        }
    }
}
