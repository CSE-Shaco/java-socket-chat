package shared.protocol;

import java.io.*;

/** Serialization boundary. Each write resets the handle table for long-lived channels. */
public final class WireIO {
    private WireIO() {}
    public static void installFilter(ObjectInputStream in) {
        in.setObjectInputFilter(info -> {
            if (info.depth() > 24 || info.arrayLength() > Protocol.MAX_FILE_BYTES
                    || (info.serialClass() != byte[].class && info.arrayLength() > 1024)) {
                return ObjectInputFilter.Status.REJECTED;
            }
            Class<?> type = info.serialClass();
            if (type == null) return ObjectInputFilter.Status.UNDECIDED;
            if (type == byte[].class || type == Object[].class || type == String.class
                    || type == shared.domain.User.class || type == shared.domain.FileInfo.class
                    || type.getName().startsWith("shared.protocol.Protocol$")
                    || type.getName().equals("java.util.CollSer")
                    || type.getName().equals("java.util.ImmutableCollections$ListN")
                    || type.getName().equals("java.util.ImmutableCollections$List12")) {
                return ObjectInputFilter.Status.ALLOWED;
            }
            return ObjectInputFilter.Status.REJECTED;
        });
    }

    public static void write(ObjectOutputStream out, Serializable payload) throws IOException {
        if (!(payload instanceof Protocol.Request) && !(payload instanceof Protocol.Response)) {
            throw new IOException("Unsupported protocol payload");
        }
        synchronized (out) {
            out.writeObject(new Protocol.WireMessage(Protocol.VERSION, payload));
            out.reset();
            out.flush();
        }
    }

    public static Serializable read(ObjectInputStream in) throws IOException, ClassNotFoundException {
        Object value = in.readObject();
        if (!(value instanceof Protocol.WireMessage wire) || wire.version() != Protocol.VERSION) {
            throw new InvalidObjectException("Unsupported protocol version or envelope");
        }
        if (!(wire.payload() instanceof Protocol.Request) && !(wire.payload() instanceof Protocol.Response)) {
            throw new InvalidObjectException("Unsupported protocol payload");
        }
        return wire.payload();
    }
}
