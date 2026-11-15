# 카마라드 방 서버

같은 목적지로 가는 사람들이 방을 만들고, 공개 방을 찾아 들어가거나 초대 코드로 입장해, 같은 방 멤버끼리만 위치와 빠른 메시지를 나누는 서버입니다.

- Node.js 22.13 이상(내장 SQLite 사용). 외부 패키지는 `ws` 하나.
- 사용자·방·멤버만 SQLite(`data/carmarade.db`)에 저장합니다. **위치는 메모리에만 두고 디스크에 남기지 않습니다.**

## 실행

```bash
cd server
npm install
npm start          # http://127.0.0.1:8787
npm test           # 동작 테스트 14개
```

환경변수: `PORT`(기본 8787), `HOST`(기본 127.0.0.1), `CARMARADE_DB`(DB 파일 경로).
Android 에뮬레이터에서는 `http://10.0.2.2:8787`로 이 PC의 서버에 닿습니다(앱 기본값).

### 가상 멤버로 시험하기

```bash
node tools/fake-member.js --code 5XWG7F --name 지수 --car "IONIQ 5" --minutes 5
```

초대 코드로 입장한 뒤, 서울숲 → 경유지 → 목적지를 따라 2초마다 위치를 보냅니다. 8초 뒤 "잘 따라가고 있어요"를 보내고, 받은 위치·메시지를 출력합니다.

## 규칙

| 항목 | 내용 |
|---|---|
| 가입 | 닉네임·차량만 받고 토큰을 줍니다. 서버에는 토큰의 SHA-256만 저장합니다. |
| 방 | 공개/비공개, 크루/동행. 목적지 1곳 + 경유지 최대 2개. 최대 5대. 만든 사람이 방장입니다. |
| 커뮤니티 | 공개 방은 모집 글로 목록에 올라갑니다. 꽉 찬 방, 닫힌 방, 출발 6시간이 지난 방은 빠집니다. |
| 같은 목적지 찾기 | 목적지 좌표 반경 1km(최대 5km) 안의 **공개 방**만 보여 줍니다. 초대 코드와 멤버 ID는 보여 주지 않습니다. |
| 초대 코드 | 6자리, 헷갈리는 글자(0/O, 1/I/L) 제외. 공개·비공개 모두 코드로 입장할 수 있습니다. 틀린 코드는 10분에 10번까지만 받습니다. |
| 방장 | 목적지·경유지 변경, 방 닫기. 방장이 나가면 가장 먼저 들어온 멤버가 방장이 되고, 모두 나가면 방이 닫힙니다. |
| 위치 | 같은 방 멤버에게만 전달. 1초에 한 번 정도만 받습니다. "대략" 공유는 받자마자 약 1km(소수 둘째 자리)로 낮춰 정확한 값을 남기지 않습니다. |
| 위치 지우기 | 공유 끄기, 방 나가기, 방 닫힘, 연결 끊김, 2분 동안 새 위치 없음. 지울 때 멤버에게 알립니다. |
| 메시지 | 방 멤버 전체 또는 한 사람에게. 200자 이내. 저장하지 않고 전달만 합니다. |

## API

모든 요청은 JSON, 가입 외에는 `Authorization: Bearer <token>`이 필요합니다.

| 요청 | 설명 |
|---|---|
| `POST /v1/users` `{name, car}` | 가입 → `{user, token}` |
| `GET /v1/me`, `GET /v1/me/rooms` | 내 정보, 내가 들어간 방 |
| `POST /v1/rooms` `{title, kind, visibility, destination, waypoints, note?, departAt?}` | 방 만들기(초대 코드 포함). `note`는 모집 글(200자), `departAt`은 출발 예정 시각(ms) |
| `GET /v1/rooms/open?q=` | 커뮤니티: 지금 모집 중인 공개 방(자리 있음). 출발 시간이 가까운 순. `q`로 방 이름·목적지·모집 글 검색 |
| `GET /v1/rooms?lat=&lon=&radiusKm=` | 같은 목적지의 공개 방 |
| `GET /v1/rooms/:id` | 방 정보(멤버만) |
| `POST /v1/rooms/:id/join` | 공개 방 참여 |
| `POST /v1/join` `{code}` | 초대 코드로 입장 |
| `POST /v1/rooms/:id/route` | 목적지·경유지 변경(방장). 모든 멤버의 수락이 풀립니다. |
| `POST /v1/rooms/:id/status` `{accepted?, phase?}` | 내 상태: 최신 경로 수락, `planning`·`driving`·`arrived`. `arrived`면 서버의 내 위치도 지웁니다. |
| `POST /v1/rooms/:id/leave`, `POST /v1/rooms/:id/close` | 나가기, 닫기(방장) |

장소 형식: `{placeId?, name, lat, lon}`.

### 실시간 `GET /v1/live` (WebSocket, Bearer 헤더)

| 보내기 | 받기 |
|---|---|
| `{type:"subscribe", roomId}` | `snapshot`(멤버, 최근 위치) |
| `{type:"location", roomId, lat, lon, heading?, sharing:"exact"\|"approximate"\|"off"}` | `location`, `location-removed` |
| `{type:"message", roomId, recipient:"all"\|userId, text}` | `message` |
| `{type:"unsubscribe", roomId}` | `members`(수락·단계 포함), `room`(경로 변경), `room-closed`, `removed`, `error` |

## 배포 (HTTPS)

### 서버 한 대(VM)에 올리기: Docker + Caddy
1. 클라우드 VM(예: 국내 클라우드의 작은 리눅스 서버)에 Docker를 설치합니다. 방화벽은 80·443만 엽니다.
2. 도메인의 A 레코드를 VM 공인 IP로 연결합니다(예: `rooms.example.com`).
3. 이 저장소를 VM에 받은 뒤:
   ```bash
   cd server/deploy
   DOMAIN=rooms.example.com docker compose up -d --build
   ```
   Caddy가 Let's Encrypt 인증서를 자동으로 받고 갱신합니다. 방 서버는 컨테이너 안에서만 열리고, 데이터는 `rooms-data` 볼륨에 남습니다.
4. 확인: `curl https://rooms.example.com/health` → `{"ok":true}`

### 앱이 배포 서버를 쓰게 하기
- 빌드할 때 기본 주소를 넣습니다.
  ```bash
  ./gradlew :app:assembleRelease -Pcarmarade.serverUrl=https://rooms.example.com
  ```
- 이미 설치된 앱은 홈 → "함께 가는 방" → "서버 주소"에서 바꿀 수 있습니다. 앱은 https만 허용합니다(개발용 10.0.2.2·localhost만 http 예외).

### 아직 확인하지 못한 것
- 이 PC에 Docker가 없어 이미지 빌드·실행은 직접 해 보지 못했습니다. 첫 배포 때 `docker compose logs`로 확인하세요.

## 실제 서비스 전에 할 일

- HTTPS 프록시(예: Caddy, Nginx) 뒤에 두고 `HOST=127.0.0.1` 유지. 앱은 https 주소만 허용합니다(개발용 10.0.2.2·localhost만 예외).
- 계정 복구·기기 변경(지금은 토큰을 잃으면 새 계정), 신고·차단, 방 만료 정리.
- 서버 여러 대로 늘릴 때는 실시간 상태(구독·위치)를 Redis 같은 공용 저장소로 옮겨야 합니다.

## 통화(음성 중계)

같은 WebSocket(`/v1/live`)으로 통화 신호(JSON)와 음성(바이너리)을 주고받는다. 별도 유료 서비스·TURN 서버가 필요 없다(서버를 직접 운영하는 비용만 든다).

| 방향 | 내용 |
|---|---|
| 앱 → 서버 JSON | `call-invite {roomId, to}`(`to`는 멤버 ID 또는 `all`, 보낸 사람은 자동으로 통화에 들어감), `call-join`, `call-leave`, `call-decline {to}` |
| 서버 → 앱 JSON | `call-invite {from, to}`, `call-members {members}`(지금 통화 중인 멤버 ID), `call-declined {from}` |
| 앱 → 서버 바이너리 | `[0x01][방 ID 길이][방 ID][음성]` — 통화 중이 아니면 버림, 소켓당 초당 100프레임까지 |
| 서버 → 앱 바이너리 | `[0x01][보낸 사람 ID 길이][보낸 사람 ID][음성]` — 같은 통화 중인 다른 멤버에게만 |

- 음성은 서버가 해석·저장·기록하지 않고 받은 즉시 넘기기만 한다. 통화 상태도 메모리에만 있고, 연결이 끊기거나 방을 나가거나 도착·방 닫힘이면 통화에서 빠진다.
- 음성 형식은 16kHz 모노를 20ms(320샘플)씩 µ-law(초당 16KB)로 줄인 것이다(`core/Voice.kt`).
