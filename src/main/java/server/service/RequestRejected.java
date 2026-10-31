package server.service;

/** A deliberate protocol rejection, distinct from transport failure. */
public final class RequestRejected extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;
    public RequestRejected(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
