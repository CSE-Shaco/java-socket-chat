# 통신 규약

## 프레이밍

각 소켓은 `ObjectOutputStream`과 `ObjectInputStream`을 한 번씩 만들고, 객체 한 개를 한 메시지로 전달합니다. 프로토콜은 `Protocol.VERSION` 버전의 `WireMessage(version, payload)` 봉투를 사용합니다. 버전이 다르거나 요청·응답 타입이 아닌 봉투는 거부합니다. `WireIO.write`는 매 전송 뒤 스트림 핸들 테이블을 초기화해 장기 연결에서 객체 참조가 누적되지 않게 합니다.

세 개의 TCP 포트는 역할별로 나뉩니다.

| 채널 | 연결 수명 | 주 요청과 응답 |
| --- | --- | --- |
| 메시지 | 로그인 후 세션 종료까지 유지 | 인사, 방 생성·입장·나가기, 랜덤 매칭, 텍스트 |
| 파일 | 세션에 결합해 유지 | 토큰 바인딩, 파일 전송·브로드캐스트 |
| 방 목록 | 요청 1회 후 닫음 | 방 목록 조회와 응답 |

## 요청과 응답 타입

`shared.protocol.Protocol`의 sealed 타입이 현재 계약입니다.

| 요청 | 목적 | 대표 응답/이벤트 |
| --- | --- | --- |
| `Hello(User)` | 메시지 채널에서 세션 시작 | `Welcome(sessionId, token, user)` |
| `CreateRoom(name)`, `JoinRoom(roomId)`, `LeaveRoom()` | 공개 방 전환 | `RoomJoined`, `Lobby`, `Error` |
| `StartRandom()`, `CancelRandom()` | 매칭 시작 및 취소 | `Waiting`, `MatchFound`, `Lobby`, `Error` |
| `SendText(content)` | 현재 방으로 텍스트 전달 | `TextMessage` |
| `BindFile(token)`, `SendFile(file)` | 파일 채널 연결과 파일 전달 | `FileReady`, `FileMessage`, `Error` |
| `ListRooms()` | 방 목록 채널에서 조회 | `RoomList` |

서버는 클라이언트가 보낸 임의 사용자·방 정보를 발신 권한으로 사용하지 않습니다. 메시지 연결의 세션 상태가 작업 권한을 결정합니다. `ServerClosing` 응답 타입은 예약되어 있으며, 현재 서버 종료 경로는 진행 중 세션 소켓을 닫습니다.

`MatchFound`는 두 세션의 방 입장까지 완료됐음을 뜻합니다. 클라이언트는 채팅 화면으로 전환하며 별도의 `JoinRoom`을 보내지 않습니다. 파일은 같은 방에서 파일 채널이 준비된 참가자에게 전달하며, 준비되지 않은 수신자를 위한 보관·재전송은 제공하지 않습니다.

랜덤 상대가 퇴장하거나 연결이 끊기면 남은 클라이언트는 별도 요청 없이 `Waiting`을 받습니다. 서버가 직전 상대를 제외한 새 상대를 찾으면 이어서 `MatchFound`를 보냅니다. 이때 메시지·파일 연결은 유지되며, 사용자는 `CancelRandom`으로 로비에 돌아갈 수 있습니다. 직접 `LeaveRoom`을 보낸 사용자는 `Lobby`를 받습니다.

## 역직렬화와 입력 한도

`WireIO.installFilter`는 허용 클래스, 배열 크기, 객체 그래프 깊이를 기준으로 역직렬화를 제한합니다. 현재 계약 상한은 텍스트 4,000자, 파일 데이터 10 MiB, 표시 이름과 방 이름 각각 80자입니다. 파일명은 서버에서 255자로 제한합니다. 서버 기본 세션 한도는 100개입니다.

Java 직렬화 입력 필터는 허용되는 타입과 자원 지표를 확인하는 경계입니다. Oracle 문서는 역직렬화 사용처별 예상 타입을 정의하고 그 경계에 맞는 필터를 적용하도록 권고합니다([Java 21 ObjectInputFilter](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/ObjectInputFilter.html)). 필터 적용은 인터넷 공개를 위한 인증·암호화·악용 방지 기능이 아닙니다.
