package client.core;

import client.attachment.AttachmentService;
import shared.config.NetworkConfig;
import shared.config.NetworkConfig.ClientConfig;
import shared.domain.User;
import shared.protocol.Protocol;
import shared.protocol.WireIO;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A Swing-independent session. Screen changes never replace its message connection. */
public final class ChatClient implements AutoCloseable {
    private final ClientConfig config;
    private final Consumer<Object> events;
    private final Consumer<String> disconnected;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("client-command-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Socket messageSocket, fileSocket;
    private volatile ObjectOutputStream messageOut, fileOut;
    private volatile boolean fileReady;
    private volatile User user;

    public ChatClient(ClientConfig config, Consumer<Object> events, Consumer<String> disconnected) {
        this.config = config;
        this.events = events;
        this.disconnected = disconnected;
    }

    public CompletableFuture<Void> connect(User requestedUser) {
        if (!started.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("This session has already connected."));
        return submit(() -> {
            try {
                messageSocket = open(config.messagePort());
                messageOut = output(messageSocket);
                ObjectInputStream in = input(messageSocket);
                WireIO.write(messageOut, new Protocol.Hello(requestedUser));
                Object first = WireIO.read(in);
                if (!(first instanceof Protocol.Welcome welcome)) throw rejection(first);
                user = welcome.user();
                events.accept(welcome);
                fileSocket = open(config.filePort());
                fileOut = output(fileSocket);
                ObjectInputStream files = input(fileSocket);
                WireIO.write(fileOut, new Protocol.BindFile(welcome.token()));
                Object ready = WireIO.read(files);
                if (!(ready instanceof Protocol.FileReady)) throw rejection(ready);
                fileReady = true;
                messageSocket.setSoTimeout(0);
                fileSocket.setSoTimeout(0);
                events.accept(ready);
                Thread.ofVirtual().name("client-messages").start(() -> receive(in));
                Thread.ofVirtual().name("client-files").start(() -> receive(files));
            } catch (Exception failure) {
                close();
                throw failure;
            }
        });
    }

    public User user() { return user; }
    public boolean isFileReady() { return fileReady && !closed.get(); }
    public CompletableFuture<Void> createRoom(String name) { return send(new Protocol.CreateRoom(name)); }
    public CompletableFuture<Void> joinRoom(String id) { return send(new Protocol.JoinRoom(id)); }
    public CompletableFuture<Void> leaveRoom() { return send(new Protocol.LeaveRoom()); }
    public CompletableFuture<Void> startRandom() { return send(new Protocol.StartRandom()); }
    public CompletableFuture<Void> cancelRandom() { return send(new Protocol.CancelRandom()); }

    public CompletableFuture<Void> sendText(String text) {
        if (text == null || text.isBlank() || text.length() > Protocol.MAX_TEXT_LENGTH)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter 1–4,000 characters."));
        return send(new Protocol.SendText(text));
    }

    public CompletableFuture<Void> sendFile(Path path) {
        return submit(() -> {
            if (!isFileReady()) throw new IOException("File connection is unavailable.");
            WireIO.write(fileOut, new Protocol.SendFile(AttachmentService.read(path)));
        });
    }

    public CompletableFuture<Protocol.RoomList> listRooms() {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("Connection is closed. Reconnect to continue."));
        try {
        return CompletableFuture.supplyAsync(() -> {
            if (closed.get()) throw new CompletionException(new IOException("Connection is closed."));
            try (Socket socket = open(config.roomPort())) {
                ObjectOutputStream out = output(socket);
                ObjectInputStream in = input(socket);
                WireIO.write(out, new Protocol.ListRooms());
                Object response = WireIO.read(in);
                if (response instanceof Protocol.RoomList rooms) return rooms;
                throw rejection(response);
            } catch (Exception e) { throw new CompletionException(e); }
        }, commands);
        } catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }

    private CompletableFuture<Void> send(Serializable request) {
        return submit(() -> {
            if (messageOut == null) throw new IOException("Connect to the server first.");
            try { WireIO.write(messageOut, request); }
            catch (IOException e) { fail("Connection lost. Reconnect to continue."); throw e; }
        });
    }

    private CompletableFuture<Void> submit(IOAction action) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("Connection is closed. Reconnect to continue."));
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    if (closed.get()) throw new IOException("Connection is closed. Reconnect to continue.");
                    action.run();
                }
                catch (Exception e) { throw new CompletionException(e); }
            }, commands);
        } catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }

    private Socket open(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(config.host(), port), NetworkConfig.CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(NetworkConfig.HANDSHAKE_TIMEOUT_MILLIS);
            if (closed.get()) throw new IOException("Connection was cancelled.");
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }
    private static ObjectOutputStream output(Socket socket) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream()); out.flush(); return out;
    }
    private static ObjectInputStream input(Socket socket) throws IOException {
        ObjectInputStream in = new ObjectInputStream(socket.getInputStream()); WireIO.installFilter(in); return in;
    }
    private static IOException rejection(Object response) {
        return new IOException(response instanceof Protocol.Error error ? error.message() : "Unexpected server response.");
    }
    private void receive(ObjectInputStream in) {
        try {
            while (!closed.get()) {
                Object response = WireIO.read(in);
                events.accept(response);
                if (response instanceof Protocol.ServerClosing closing) {
                    fail(closing.reason()); return;
                }
            }
        } catch (Exception e) { fail("Connection lost. Reconnect to continue."); }
    }
    private void fail(String reason) {
        if (closed.compareAndSet(false, true)) {
            release(); disconnected.accept(reason);
        }
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) release();
    }
    private void release() {
        fileReady = false;
        closeSocket(messageSocket); closeSocket(fileSocket); commands.shutdown();
    }
    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
    @FunctionalInterface private interface IOAction { void run() throws Exception; }
}
