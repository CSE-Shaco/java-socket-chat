# Java Socket Chat

**What This Project Contains:** A Java 21 client-server chat application with a Swing desktop client, a multithreaded TCP server, a message protocol, and bounded file transfer. Individual source authorship is not inferred here.

**How It Works:** A persistent message connection carries session commands and chat events. Separate TCP connections handle file transfer and room-list requests. The server owns session and room state, validates commands against the active session, and serializes writes through bounded per-session queues.

**What I Verified:** A full Java 21 Maven `verify` run passed 38 unit tests and 18 real TCP integration tests, with no failures, errors or skipped tests. The shutdown test checks client disconnection and release of all three listening ports; a negative control ensures an occupied port still fails the release check. See the [latest CI status](https://github.com/CSE-Shaco/java-socket-chat/actions/workflows/ci.yml) and [validation record](docs/validation.md).

## Technical Highlights

- Keeps message, file, and room-list traffic on separate TCP channels with explicit lifetimes.
- Makes the server session authoritative for sender identity and room membership.
- Uses bounded outbound queues to isolate slow clients from synchronized room-state updates.
- Defines a versioned message envelope and applies an object-input filter at deserialization boundaries.
- Tests chat-state transitions and wire-format behavior alongside attachment handling and UI components.

## Architecture

```text
Swing client ── persistent message TCP ──┐
              ├─ session-bound file TCP ──┼── Java server
              └─ room-list request TCP ───┘
                       shared protocol types
```

`client.core.ChatClient` owns asynchronous communication independently of Swing. `client.attachment.AttachmentService` validates and stores attachments. The GUI panels run on Swing's event-dispatch thread. On the server, `ChatService` manages room and matchmaking state, while `OutboundChannel` preserves per-client message order and bounds queued output. Shared `WireIO` frames protocol messages and filters deserialized objects.

Detailed notes: [architecture](docs/architecture.md) · [protocol](docs/protocol.md) · [runbook](docs/runbook.md).

## Build and Run

Requirements: JDK 21. The Maven Wrapper downloads Maven 3.9.11 when it is not already available locally.

```sh
./mvnw verify
```

Package the runnable JARs:

```sh
./mvnw package
```

Start the server and a client in separate terminals:

```sh
java -jar target/chat-server.jar
java -jar target/chat-client.jar
```

Start a second client to try matchmaking. Default server ports are `12345` (messages), `12346` (files), and `12347` (room list). Host and port options are documented in the [runbook](docs/runbook.md).

## Tests and Validation

The standard `./mvnw verify` lifecycle runs 38 unit tests and 18 socket integration tests, and packages client/server JARs. A reproduced shutdown-test race was corrected by checking actual port availability within a bounded deadline, rather than assuming immediate reuse after socket close. This does not retry whole tests or suppress persistent bind failures. Earlier host-restricted verification and individual CI results are historical snapshots; consult the [current workflow](https://github.com/CSE-Shaco/java-socket-chat/actions/workflows/ci.yml) and [validation record](docs/validation.md).

## Scope and Limitations

This application supports anonymous sessions for local development and trusted-LAN demonstrations. It does not provide TLS, persistent accounts, or durable chat history. The server does not persist chat messages or received attachments. Clients retain a bounded recent transcript and attachment data in memory; a file is written only when a user chooses a save location. Java object deserialization is restricted by an allowlist and resource limits, but that filter does not replace authentication, encryption, or protection against hostile public-network traffic.

## Provenance

This public repository starts from a reviewed source snapshot of the existing Java 21 application and Maven tests. Earlier development history is preserved separately in a private archive and is not included in this repository. The fresh public history does not describe the timing or individual authorship of earlier development.

The snapshot contains application code, tests, build tooling and technical documentation. Unused third-party image samples and historical uploaded files are excluded. Production and test source files were preserved byte-for-byte during publication preparation. No project-wide license was selected automatically; existing upstream notices remain in the build-wrapper files.
