package client.gui.component;

import org.junit.jupiter.api.Test;
import shared.protocol.Protocol.TextMessage;

import javax.swing.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatBubbleTest {
    @Test void longTextWrapsAtTheAvailableWidthWithoutClipping() throws Exception {
        onEdt(() -> {
            String sample = "긴 한국어 메시지와 emoji 🌱, https://example.test/" + "long-url".repeat(90) + "\nnext line";
            ChatBubble bubble = new ChatBubble(new TextMessage("u", "Sender", "r", sample, false), false);
            int wide = bubble.preferredHeightForWidth(500);
            int narrow = bubble.preferredHeightForWidth(210);
            assertTrue(narrow > wide, "Narrow view should wrap to more lines");
            bubble.setBounds(0, 0, 210, narrow);
            bubble.doLayout();
            assertChildrenFit(bubble);
            BubbleText content = findText(bubble, sample);
            assertNotNull(content);
            assertTrue(content.getHeight() >= content.getPreferredSize().height, "Full text must fit vertically");
            assertEquals(sample, content.text());
        });
    }

    @Test void attachmentButtonsAndLongFilenameFitNarrowWindow() throws Exception {
        onEdt(() -> {
            JButton save = new JButton("Save"), preview = new JButton("Preview");
            ChatBubble attachment = ChatBubble.attachment("A sender", "long-file-name-".repeat(12) + ".png",
                    "1.2 KiB", false, save, preview);
            int height = attachment.preferredHeightForWidth(210);
            attachment.setBounds(0, 0, 210, height);
            attachment.doLayout();
            assertChildrenFit(attachment);
            assertTrue(save.getWidth() >= save.getPreferredSize().width);
            assertTrue(preview.getWidth() >= preview.getPreferredSize().width);
            assertTrue(preview.getY() >= save.getY() + save.getHeight(), "Buttons stack if there is not enough width");
        });
    }

    @Test void transcriptTracksViewportWidthAndRetainsEveryRow() throws Exception {
        onEdt(() -> {
            ChatTranscript transcript = new ChatTranscript();
            for (int i = 0; i < 8; i++) transcript.add(message("A message with a fairly long line " + i, i % 2 == 0));
            JScrollPane scroll = new JScrollPane(transcript);
            scroll.setSize(240, 160);
            scroll.doLayout();
            scroll.getViewport().doLayout();
            transcript.doLayout();
            assertTrue(transcript.getScrollableTracksViewportWidth());
            assertEquals(scroll.getViewport().getExtentSize().width, transcript.getWidth());
            assertTrue(transcript.getPreferredSize().height > scroll.getViewport().getExtentSize().height);
            assertChildrenFit(transcript);
        });
    }

    @Test void mixedLanguageAttachmentDoesNotEraseAsciiGlyphsInOtherMessages() throws Exception {
        onEdt(() -> {
            String ascii = "p n g - .";
            ChatBubble probe = message(ascii, false);
            ChatTranscript transcript = new ChatTranscript();
            transcript.add(message("안녕하세요. 한글과 emoji 🌱를 함께 표시합니다.", true));
            transcript.add(probe);
            transcript.add(ChatBubble.attachment("민지", "프로젝트-스크린샷.png", "184.3 KiB",
                    false, new JButton("Save"), new JButton("Preview")));
            transcript.setSize(520, 1);
            transcript.setSize(520, transcript.getPreferredSize().height);
            transcript.doLayout();
            paint(transcript);

            BubbleText body = findText(probe, ascii);
            assertNotNull(body);
            BufferedImage pixels = paint(body);
            TextLayout expected = new TextLayout(ascii, body.getFont(), new FontRenderContext(null, true, true));
            for (int index : new int[]{0, 2, 4, 6, 8}) {
                Rectangle2D bounds = expected.getBlackBoxBounds(index, index + 1).getBounds2D();
                bounds.setRect(bounds.getX(), bounds.getY() + expected.getAscent(), bounds.getWidth(), bounds.getHeight());
                assertTrue(hasInk(pixels, bounds), "Glyph disappeared after a mixed-language attachment: " + ascii.charAt(index));
            }
            assertNotNull(body.getComponentPopupMenu(), "Plain text must remain available to copy");
        });
    }

    @Test void maximumLengthMessageKeepsNewlinesEmojiAndItsLastLine() throws Exception {
        onEdt(() -> {
            String prefix = "한글 🌱😀\n";
            String text = prefix + "a".repeat(4_000 - prefix.length());
            ChatBubble bubble = message(text, true);
            bubble.setSize(240, bubble.preferredHeightForWidth(240));
            bubble.doLayout();
            assertChildrenFit(bubble);
            BubbleText body = findText(bubble, text);
            assertNotNull(body);
            assertEquals(4_000, body.text().length());
            BufferedImage pixels = paint(body);
            assertTrue(hasInk(pixels, new Rectangle(0, 0, pixels.getWidth(), 22)), "Korean and emoji line must render");
            assertTrue(hasInk(pixels, new Rectangle(0, pixels.getHeight() - 22, pixels.getWidth(), 22)),
                    "The last wrapped line must not be clipped");
        });
    }

    private static BufferedImage paint(Component component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { component.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    private static boolean hasInk(BufferedImage image, Rectangle2D bounds) {
        for (int y = Math.max(0, (int) Math.floor(bounds.getMinY())); y < Math.min(image.getHeight(), (int) Math.ceil(bounds.getMaxY())); y++) {
            for (int x = Math.max(0, (int) Math.floor(bounds.getMinX())); x < Math.min(image.getWidth(), (int) Math.ceil(bounds.getMaxX())); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    private static ChatBubble message(String text, boolean mine) {
        return new ChatBubble(new TextMessage("u", "Sender", "r", text, false), mine);
    }
    private static BubbleText findText(Container parent, String value) {
        for (Component child : parent.getComponents()) {
            if (child instanceof BubbleText text && text.text().equals(value)) return text;
            if (child instanceof Container nested) {
                BubbleText found = findText(nested, value);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void assertChildrenFit(Container parent) {
        for (Component child : parent.getComponents()) {
            assertTrue(child.getX() >= 0 && child.getY() >= 0, "Negative child position");
            assertTrue(child.getX() + child.getWidth() <= parent.getWidth(), "Child exceeds parent width: " + child);
            assertTrue(child.getY() + child.getHeight() <= parent.getHeight(), "Child exceeds parent height: " + child);
            if (child instanceof Container nested) assertChildrenFit(nested);
        }
    }
    private static void onEdt(Runnable work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { work.run(); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() instanceof Exception exception) throw exception;
        if (error.get() instanceof Error failure) throw failure;
    }
}
