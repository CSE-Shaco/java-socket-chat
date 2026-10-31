# Validation Record

## Public source snapshot

This public repository begins with a reviewed snapshot of the existing application. Earlier commits and detailed historical records are preserved in a separate private archive rather than included in the public Git history. Publication preparation changed documentation and excluded unused sample images; all 25 production and eight test source files were preserved byte-for-byte.

Before publication, those exact production and test sources passed full Java 21 Maven `verify`: **38 unit tests and 18 real TCP integration tests**, with zero failures, errors or skipped tests. Executable client/server JAR packaging completed in the same lifecycle. The public repository's [CI workflow](https://github.com/CSE-Shaco/java-socket-chat/actions/workflows/ci.yml) runs `./mvnw --batch-mode --no-transfer-progress verify` on Ubuntu with Temurin 21. Its current run provides the result for the public commit; the pre-publication result alone does not assert that every later commit passed.

## Checked coverage

| Scope | Verified behavior |
| --- | --- |
| Unit tests | 38 cases covering chat-state transitions, wire framing, outbound transport, attachment handling and Swing bubble behavior. |
| Server integration | 15 cases using real TCP connections, including protocol/session behavior, bounded transfers and server shutdown. |
| Client integration | Three cases using real TCP connections. |
| Packaging | Maven builds client and server executable JARs; entry points are `client.Client` and `server.Server`. |
| Self-contained image checks | Tests construct their own `BufferedImage` data; no external album artwork or historical attachment is required. |

Run the complete checked-in lifecycle from the repository root:

```sh
./mvnw verify
```

Requirements are JDK 21 and Maven 3.9.11 through the checked-in wrapper. Network access may be needed for the Maven distribution and dependencies on a fresh host.

## Shutdown regression

An earlier integration assertion assumed immediate reuse of a just-closed listening port and could fail with `BindException: Address already in use`. A local reproduction showed transient port-release timing rather than a persistent port leak in that case. The checked-in test retains the client-disconnection assertion and checks actual availability of all three listening ports for up to two seconds, polling only `BindException` at five-millisecond intervals. Other I/O errors propagate immediately. A held-listener negative control confirms that a persistently occupied port still fails the check.

This correction changed test synchronization only. Production socket settings and the shutdown implementation were not changed, no whole-test retry was introduced, and no test was skipped. A separate 5,000-cycle local shutdown probe was supplemental diagnostic evidence, not 5,000 additional Maven tests.

## Environment limits

Earlier restricted macOS verification could compile/package and pass a headless subset, but socket binding returned `Operation not permitted`. Those incomplete runs were not counted as passing full verification. Later unrestricted local verification and CI passed the complete 56-test lifecycle described above.

A successful Maven run does not establish interactive GUI usability, hostile-network security or behavior on every platform. Packaged GUI applications were not manually exercised as part of publication preparation. This project targets local/trusted-LAN demonstrations and does not provide TLS, authenticated persistent accounts or durable chat history; see the README for the application limits.

## Publication layout

The public tree excludes unused third-party image samples, historical uploads, private source archives, generated binaries and IDE caches. The original private history and external backups are preserved. No history rewrite or force-push was used to create this independent public snapshot. No project-wide license was selected automatically, and upstream wrapper notices remain intact.
