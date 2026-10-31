# 빌드와 실행 안내

## 요구 사항과 검증

JDK 21이 필요합니다. Maven은 설치하지 않아도 됩니다. Wrapper는 Maven 3.9.11을 내려받습니다.

```bash
./mvnw --version
./mvnw verify
```

Windows PowerShell/CMD에서는 `./mvnw verify` 대신 `.\mvnw.cmd verify`를 실행합니다.

`verify`는 JUnit 단위 테스트와 `*IT` 통합 테스트를 실행합니다. 실제 성공 여부는 현재 실행한 빌드 결과를 확인하세요. 배포용 실행 JAR은 다음 명령으로 만듭니다.

```bash
./mvnw package
```

생성 위치: `target/chat-server.jar`, `target/chat-client.jar`.

## 말풍선 미리보기

빌드 후 실제 Swing 컴포넌트를 이미지로 렌더링할 수 있습니다.

```bash
java -cp target/test-classes:target/classes client.gui.BubblePreview
```

Windows에서는 클래스 경로 구분자로 세미콜론을 사용합니다.

```powershell
java -cp "target/test-classes;target/classes" client.gui.BubblePreview
```

결과는 `target/chat-bubbles-preview.png`입니다. 이 이미지는 UI 배치 검토용이며 실행 중인 창의 직접 조작 검증을 대신하지 않습니다. 이번 작업에서는 앱 접근 도구가 거절되어 창의 직접 조작 검증은 수행하지 못했습니다.

## 로컬에서 실행

서버 터미널:

```bash
java -jar target/chat-server.jar
```

클라이언트 터미널:

```bash
java -jar target/chat-client.jar
```

랜덤 매칭은 클라이언트를 두 번 실행해 이름을 입력하고 둘 다 `Random Chat`을 누릅니다. 공개 방 데모는 한 클라이언트에서 `Create Room`을 선택하고 다른 클라이언트에서 방 목록을 새로 고친 뒤 입장합니다. 채팅 화면에서 메시지를 보내거나 파일을 선택해 전달을 확인합니다.

기본 서버 포트는 메시지 12345, 파일 12346, 방 목록 12347입니다. 기본 바인딩은 로컬 전용 `127.0.0.1`입니다.

## 설정

서버는 이름 있는 인수와 값을 쌍으로 받습니다.

```bash
java -jar target/chat-server.jar --bind 127.0.0.1 --message-port 12345 --file-port 12346 --room-port 12347 --max-sessions 100 --handshake-timeout-ms 5000 --outbound-queue-capacity 64 --outbound-queue-bytes 20971520 --write-timeout-ms 5000
```

클라이언트는 서버 호스트와 각 채널 포트를 받습니다.

```bash
java -jar target/chat-client.jar --host localhost --message-port 12345 --file-port 12346 --room-port 12347
```

LAN 데모에서는 서버의 `--bind`에 LAN 인터페이스 주소를 지정하고 클라이언트 `--host`에는 해당 주소를 사용합니다. 서버 호스트 방화벽에서 세 TCP 포트를 허용해야 합니다. 이 모드는 같은 신뢰된 네트워크에서만 사용하세요. 연결은 TLS로 암호화되지 않고 토큰은 파일 소켓을 메시지 세션에 결합하는 값이지 사용자 신원 인증 수단이 아닙니다.

서버의 `--handshake-timeout-ms`, `--outbound-queue-capacity`, `--outbound-queue-bytes`, `--write-timeout-ms`는 연결 준비 제한, 세션별 대기열 항목 수·대기 바이트 수, 소켓 쓰기 제한 시간을 조정합니다. 기본값은 각각 5,000 ms, 64개, 20,971,520 bytes, 5,000 ms입니다. `--max-sessions` 기본값은 100입니다.

## 자주 확인할 문제

- 포트가 이미 사용 중이면 서버의 해당 포트를 비우거나 서로 다른 포트 값을 지정합니다.
- 클라이언트가 연결하지 못하면 서버가 먼저 실행 중인지, host/port가 맞는지, LAN 방화벽이 세 포트를 허용하는지 확인합니다.
- 파일 채널 연결이 실패하면 파일 전송이 준비되지 않습니다. 서버에 재연결한 뒤 파일 작업을 다시 시도합니다.
- `--bind 0.0.0.0`은 모든 인터페이스에서 수신합니다. 신뢰되지 않은 네트워크에 노출하지 않습니다.
- 서버는 메시지·방 상태를 프로세스 메모리에서만 관리합니다. 대화 이력은 서버나 디스크에 저장하지 않으며, 클라이언트 화면은 최근 최대 100개 메시지/파일 항목과 최대 20 MiB 첨부 데이터를 메모리에 보관합니다. 선택한 파일 저장은 예외로 사용자가 고른 경로에 파일이 기록됩니다.
