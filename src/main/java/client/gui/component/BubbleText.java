package client.gui.component;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/** One shaping path for measuring and painting multilingual, plain message text. */
final class BubbleText extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private record Line(TextLayout layout, float baseline, float height) {}
    private final String text;
    private final int naturalWidth;
    private transient int measuredWidth = -1;
    private transient List<Line> lines = List.of();

    BubbleText(String value, Font font, Color color) {
        text = value == null ? "" : value;
        setFont(font);
        setForeground(color);
        setOpaque(false);
        setFocusable(true);
        setToolTipText("Right-click to copy text");
        getAccessibleContext().setAccessibleName("Message text");
        getAccessibleContext().setAccessibleDescription(text);
        int widest = 0;
        for (String paragraph : text.split("\\R", -1)) {
            if (!paragraph.isEmpty()) {
                widest = Math.max(widest, (int) Math.ceil(new TextLayout(paragraph, font, CONTEXT).getAdvance()));
            }
        }
        naturalWidth = widest + 2;
        Action copy = new AbstractAction("Copy text") {
            @Override public void actionPerformed(ActionEvent event) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            }
        };
        JPopupMenu menu = new JPopupMenu();
        menu.add(copy);
        setComponentPopupMenu(menu);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl C"), "copyText");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("meta C"), "copyText");
        getActionMap().put("copyText", copy);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { requestFocusInWindow(); }
        });
    }

    String text() { return text; }
    int naturalWidth() { return naturalWidth; }

    int heightForWidth(int width) {
        float height = 0;
        for (Line line : linesForWidth(width)) height += line.height();
        return Math.max(1, (int) Math.ceil(height));
    }

    @Override public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : Math.max(1, naturalWidth);
        return new Dimension(width, heightForWidth(width));
    }

    private List<Line> linesForWidth(int width) {
        width = Math.max(1, width);
        if (lines != null && measuredWidth == width) return lines;
        List<Line> measured = new ArrayList<>();
        float emptyHeight = (float) Math.ceil(getFont().getLineMetrics("Ag", CONTEXT).getHeight()) + 1;
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                measured.add(new Line(null, 0, emptyHeight));
                continue;
            }
            AttributedString attributed = new AttributedString(paragraph);
            attributed.addAttribute(TextAttribute.FONT, getFont());
            LineBreakMeasurer breaker = new LineBreakMeasurer(attributed.getIterator(), CONTEXT);
            while (breaker.getPosition() < paragraph.length()) {
                TextLayout layout = breaker.nextLayout(width);
                float height = (float) Math.ceil(layout.getAscent() + layout.getDescent() + layout.getLeading()) + 1;
                measured.add(new Line(layout, layout.getAscent(), height));
            }
        }
        measuredWidth = width;
        lines = List.copyOf(measured);
        return lines;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(getForeground());
            float y = 0;
            for (Line line : linesForWidth(getWidth())) {
                // TextLayout also preserves color/bitmap emoji that have no vector outline.
                if (line.layout() != null) line.layout().draw(g, 0, y + line.baseline());
                y += line.height();
            }
            if (isFocusOwner()) {
                g.setColor(getForeground());
                g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{1, 2}, 0));
                g.drawRect(0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1));
            }
        } finally { g.dispose(); }
    }
}
