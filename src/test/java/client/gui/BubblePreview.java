package client.gui;

import client.gui.component.ChatBubble;
import client.gui.component.ChatTranscript;
import shared.protocol.Protocol.TextMessage;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Standalone offscreen visual review: run after test-compile, outside the JUnit JVM. */
public final class BubblePreview {
    private BubblePreview() {}

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTranscript transcript = new ChatTranscript();
            transcript.add(new ChatBubble(new TextMessage("", "", "r", "민지가 대화에 참여했습니다", true), false));
            transcript.add(new ChatBubble(new TextMessage("u1", "민지", "r", "안녕하세요! 새로 정리한 채팅 화면이네요.\n줄바꿈도 자연스럽게 보여요.", false), false));
            transcript.add(new ChatBubble(new TextMessage("u2", "나", "r", "반가워요! 간결하고 편안한 대화를 시작해요. 🌱", false), true));
            transcript.add(new ChatBubble(new TextMessage("u1", "민지", "r", "Long URL: https://example.com/some/really-long-path-with-no-spaces-to-check-wrapping", false), false));
            transcript.add(ChatBubble.attachment("민지", "프로젝트-스크린샷.png", "184.3 KiB", false, new JButton("Save"), new JButton("Preview")));
            int width = 520;
            transcript.setSize(width, 1);
            int height = transcript.getPreferredSize().height;
            transcript.setSize(width, height);
            transcript.doLayout();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { transcript.paint(graphics); } finally { graphics.dispose(); }
            try {
                File output = new File("target/chat-bubbles-preview.png");
                output.getParentFile().mkdirs();
                ImageIO.write(image, "png", output);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
