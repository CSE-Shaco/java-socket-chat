package client.attachment;

import shared.domain.FileInfo;
import shared.protocol.Protocol;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** File and image operations shared by the client transport and Swing screens. */
public final class AttachmentService {
    private static final long MAX_IMAGE_PIXELS = 16_000_000;
    private static final int MAX_PREVIEW_DIMENSION = 700;

    private AttachmentService() {}

    /** Reads a bounded attachment; the image flag is a preview hint, not a content guarantee. */
    public static FileInfo read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Select a regular file.");
        if (Files.size(path) > Protocol.MAX_FILE_BYTES) throw tooLarge();
        byte[] data;
        try (InputStream input = Files.newInputStream(path)) {
            // The second check also covers a file that grows after its size was inspected.
            data = input.readNBytes(Protocol.MAX_FILE_BYTES + 1);
        }
        if (data.length > Protocol.MAX_FILE_BYTES) throw tooLarge();
        String name = path.getFileName().toString();
        boolean image = name.matches("(?i).+\\.(png|jpe?g|gif)$");
        return new FileInfo(name, data, image);
    }

    /** Writes to the caller-selected path. The screen owns overwrite confirmation. */
    public static void save(FileInfo file, Path destination) throws IOException {
        byte[] data = data(file);
        if (destination == null) throw new IOException("Select a destination file.");
        Files.write(destination, data);
    }

    /** Decodes only bounded images and returns an eager thumbnail without Swing dependencies. */
    public static BufferedImage preview(FileInfo file) throws IOException {
        byte[] data = data(file);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) throw invalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw invalidImage();
                if ((long) width * height > MAX_IMAGE_PIXELS)
                    throw new IOException("Image is too large to preview. Save it to open externally.");
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw invalidImage();
                int longest = Math.max(width, height);
                if (longest <= MAX_PREVIEW_DIMENSION) return decoded;
                double scale = (double) MAX_PREVIEW_DIMENSION / longest;
                int previewWidth = Math.max(1, (int) (width * scale));
                int previewHeight = Math.max(1, (int) (height * scale));
                BufferedImage preview = new BufferedImage(previewWidth, previewHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = preview.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, previewWidth, previewHeight, null);
                } finally {
                    graphics.dispose();
                }
                return preview;
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] data(FileInfo file) throws IOException {
        if (file == null || file.getData() == null) throw new IOException("Attachment data is unavailable.");
        if (file.getData().length > Protocol.MAX_FILE_BYTES) throw tooLarge();
        return file.getData();
    }

    private static IOException tooLarge() { return new IOException("Files must be 10 MiB or smaller."); }
    private static IOException invalidImage() { return new IOException("This file cannot be previewed as an image."); }
}
