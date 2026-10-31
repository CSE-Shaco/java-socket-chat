package client.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shared.domain.FileInfo;
import shared.protocol.Protocol;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentServiceTest {
    @TempDir Path directory;

    @Test void readsRegularFilesWithoutChangingTheirBytes() throws Exception {
        Path path = directory.resolve("사진.PNG");
        byte[] bytes = {0, 1, 2, (byte) 255};
        Files.write(path, bytes);
        FileInfo file = AttachmentService.read(path);
        assertEquals("사진.PNG", file.getFileName());
        assertArrayEquals(bytes, file.getData());
        assertTrue(file.isImage(), "The extension should enable the preview action");
        Path binary = directory.resolve("data.bin");
        Files.write(binary, bytes);
        assertFalse(AttachmentService.read(binary).isImage());
    }

    @Test void rejectsMissingDirectoriesAndFilesAboveTheTransferLimit() throws Exception {
        assertThrows(IOException.class, () -> AttachmentService.read(null));
        assertThrows(IOException.class, () -> AttachmentService.read(directory));
        assertThrows(IOException.class, () -> AttachmentService.read(directory.resolve("missing")));
        Path oversized = directory.resolve("oversized.bin");
        try (SeekableByteChannel output = Files.newByteChannel(oversized,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.position(Protocol.MAX_FILE_BYTES);
            output.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> AttachmentService.read(oversized));
    }

    @Test void savesToTheSelectedPathAndRejectsInvalidDataBeforeOverwriting() throws Exception {
        Path destination = directory.resolve("saved.bin");
        byte[] bytes = {9, 8, 7};
        AttachmentService.save(new FileInfo("download.bin", bytes, false), destination);
        assertArrayEquals(bytes, Files.readAllBytes(destination));
        assertThrows(IOException.class, () -> AttachmentService.save(null, destination));
        assertThrows(IOException.class, () -> AttachmentService.save(new FileInfo("empty.bin", null, false), destination));
        assertThrows(IOException.class, () -> AttachmentService.save(
                new FileInfo("large.bin", new byte[Protocol.MAX_FILE_BYTES + 1], false), destination));
        assertArrayEquals(bytes, Files.readAllBytes(destination), "Rejected data must leave an existing file intact");
    }

    @Test void previewsPreserveSmallImagesAndScaleLargeImagesWithoutLosingAspectRatio() throws Exception {
        BufferedImage small = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        small.setRGB(0, 0, 0x80402010);
        BufferedImage unchanged = AttachmentService.preview(png(small));
        assertEquals(4, unchanged.getWidth());
        assertEquals(3, unchanged.getHeight());
        assertEquals(0x80402010, unchanged.getRGB(0, 0));

        BufferedImage large = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        BufferedImage preview = AttachmentService.preview(png(large));
        assertEquals(700, preview.getWidth());
        assertEquals(350, preview.getHeight());
    }

    @Test void rejectsInvalidImagesAndHugeDimensionsBeforePixelDecoding() throws Exception {
        assertThrows(IOException.class, () -> AttachmentService.preview(
                new FileInfo("fake.png", new byte[]{1, 2, 3}, true)));
        assertThrows(IOException.class, () -> AttachmentService.preview(
                new FileInfo("missing.png", null, true)));
        IOException failure = assertThrows(IOException.class, () -> AttachmentService.preview(
                new FileInfo("huge.png", pngHeader(5000, 4000), true)));
        assertTrue(failure.getMessage().contains("too large"), "Dimensions must be rejected before decoding absent pixel data");
    }

    @Test void veryThinImagesStillHaveAVisiblePreview() throws Exception {
        BufferedImage horizontal = AttachmentService.preview(png(new BufferedImage(1400, 1, BufferedImage.TYPE_INT_ARGB)));
        assertEquals(700, horizontal.getWidth());
        assertEquals(1, horizontal.getHeight());
        BufferedImage vertical = AttachmentService.preview(png(new BufferedImage(1, 1400, BufferedImage.TYPE_INT_ARGB)));
        assertEquals(1, vertical.getWidth());
        assertEquals(700, vertical.getHeight());
    }

    private static FileInfo png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return new FileInfo("image.png", output.toByteArray(), true);
    }

    /** A metadata-only PNG lets the size guard be checked without allocating its advertised pixels. */
    private static byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeLong(0x89504e470d0a1a0aL);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            try (DataOutputStream fields = new DataOutputStream(header)) {
                fields.writeInt(width);
                fields.writeInt(height);
                fields.write(new byte[]{8, 2, 0, 0, 0});
            }
            chunk(data, 0x49484452, header.toByteArray());
            chunk(data, 0x49454e44, new byte[0]);
        }
        return output.toByteArray();
    }

    private static void chunk(DataOutputStream output, int type, byte[] payload) throws IOException {
        output.writeInt(payload.length);
        output.writeInt(type);
        output.write(payload);
        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(4).putInt(type).array());
        crc.update(payload);
        output.writeInt((int) crc.getValue());
    }
}
