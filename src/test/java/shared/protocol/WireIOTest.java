package shared.protocol;

import org.junit.jupiter.api.Test;
import shared.domain.FileInfo;
import shared.domain.User;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

class WireIOTest {
    @Test
    void roundTripsTypedRequestAndResponse() throws Exception {
        User user = new User("user-1", "Mina");
        Protocol.Request request = new Protocol.Hello(user);
        Protocol.Response response = new Protocol.FileMessage("user-1", "Mina", "room-1",
                new FileInfo("note.txt", new byte[]{1, 2, 3}, false));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            WireIO.write(out, request);
            WireIO.write(out, response);
        }

        try (ObjectInputStream in = input(bytes.toByteArray())) {
            Protocol.Hello actualRequest = assertInstanceOf(Protocol.Hello.class, WireIO.read(in));
            assertEquals(user.getUserId(), actualRequest.user().getUserId());
            assertEquals(user.getUsername(), actualRequest.user().getUsername());

            Protocol.FileMessage actualResponse = assertInstanceOf(Protocol.FileMessage.class, WireIO.read(in));
            assertEquals("user-1", actualResponse.senderId());
            assertEquals("Mina", actualResponse.sender());
            assertEquals("room-1", actualResponse.roomId());
            assertEquals("note.txt", actualResponse.file().getFileName());
            assertArrayEquals(new byte[]{1, 2, 3}, actualResponse.file().getData());
            assertFalse(actualResponse.file().isImage());
        }
    }

    @Test
    void rejectsAnEnvelopeFromAnotherProtocolVersion() throws Exception {
        byte[] bytes = serialize(new Protocol.WireMessage(Protocol.VERSION + 1,
                new Protocol.Hello(new User("u", "Name"))));
        try (ObjectInputStream in = input(bytes)) {
            assertThrows(java.io.IOException.class, () -> WireIO.read(in));
        }
    }

    @Test
    void rejectsClassesOutsideTheWireAllowlist() throws Exception {
        byte[] bytes = serialize(new java.util.Date());
        try (ObjectInputStream in = input(bytes)) {
            assertThrows(InvalidClassException.class, in::readObject);
        }
    }

    @Test
    void rejectsOversizedArrays() throws Exception {
        byte[] bytes = serialize(new Object[1_025]);
        try (ObjectInputStream in = input(bytes)) {
            assertThrows(InvalidClassException.class, in::readObject);
        }
    }

    @Test
    void rejectsOversizedFilePayloads() throws Exception {
        byte[] bytes = serialize(new Protocol.SendFile(new FileInfo("large.bin",
                new byte[Protocol.MAX_FILE_BYTES + 1], false)));
        try (ObjectInputStream in = input(bytes)) {
            assertThrows(InvalidClassException.class, in::readObject);
        }
    }

    @Test
    void rejectsObjectGraphsDeeperThanTheConfiguredLimit() throws Exception {
        Serializable nested = "leaf";
        for (int i = 0; i < 26; i++) {
            nested = new Object[]{nested};
        }
        byte[] bytes = serialize(nested);
        try (ObjectInputStream in = input(bytes)) {
            assertThrows(InvalidClassException.class, in::readObject);
        }
    }

    @Test
    void resetsObjectHandlesBetweenLongLivedWrites() throws Exception {
        Protocol.Request sameRequest = new Protocol.Hello(new User("u", "Name"));
        ByteArrayOutputStream oneMessage = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(oneMessage)) {
            WireIO.write(out, sameRequest);
        }

        ByteArrayOutputStream manyMessages = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(manyMessages)) {
            for (int i = 0; i < 20; i++) {
                WireIO.write(out, sameRequest);
            }
        }

        // Without TC_RESET the reused request would be encoded as a short handle reference.
        assertTrue(manyMessages.size() > oneMessage.size() * 15);
        try (ObjectInputStream in = input(manyMessages.toByteArray())) {
            for (int i = 0; i < 20; i++) {
                Protocol.Hello actual = assertInstanceOf(Protocol.Hello.class, WireIO.read(in));
                assertEquals("u", actual.user().getUserId());
                assertEquals("Name", actual.user().getUsername());
            }
        }
    }

    private static ObjectInputStream input(byte[] bytes) throws Exception {
        ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes));
        WireIO.installFilter(in);
        return in;
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
