package client.gui.component;

import javax.swing.*;
import java.awt.*;

/** Width-tracking transcript that measures wrapped rows before laying them out. */
public final class ChatTranscript extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;
    public static final Color BACKGROUND = new Color(243, 247, 245);
    private int measuredWidth;

    public ChatTranscript() {
        setLayout(null);
        setBackground(BACKGROUND);
    }

    @Override public Dimension getPreferredSize() {
        int width = viewportWidth();
        int height = 16;
        for (Component child : getComponents()) {
            if (child instanceof ChatBubble bubble) height += bubble.preferredHeightForWidth(width);
        }
        return new Dimension(width, height);
    }

    @Override public void doLayout() {
        int width = getWidth();
        int y = 8;
        for (Component child : getComponents()) {
            if (child instanceof ChatBubble bubble) {
                int height = bubble.preferredHeightForWidth(width);
                bubble.setBounds(0, y, width, height);
                bubble.doLayout();
                y += height;
            }
        }
    }

    @Override public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        if (measuredWidth != width && width > 0) {
            measuredWidth = width;
            revalidate();
        }
    }

    private int viewportWidth() {
        if (getParent() instanceof JViewport viewport && viewport.getExtentSize().width > 0)
            return viewport.getExtentSize().width;
        return getWidth() > 0 ? getWidth() : 480;
    }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(480, 300); }
    @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 20; }
    @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return Math.max(20, visibleRect.height - 40); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
