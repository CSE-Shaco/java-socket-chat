package shared.domain;

import java.io.Serializable;

/**
 * Represents a file being transferred between client and server.
 * Includes the file name, binary data, and whether it is an image.
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final byte[] data;
    private final boolean isImage;

    public FileInfo(String fileName, byte[] data, boolean isImage) {
        this.fileName = fileName;
        this.data = data;
        this.isImage = isImage;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getData() {
        return data;
    }

    public boolean isImage() {
        return isImage;
    }
}
