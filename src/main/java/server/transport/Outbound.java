package server.transport;

import shared.protocol.Protocol;

/** Non-blocking output boundary: implementations enqueue and never perform network I/O here. */
public interface Outbound extends AutoCloseable {
    boolean offer(Protocol.Response response);
    @Override void close();
}
