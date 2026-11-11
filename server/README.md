# 카마라드 방 서버

같은 목적지로 가는 사람들이 방을 만들고, 공개 방을 찾아 들어가거나 초대 코드로 입장해, 같은 방 멤버끼리만 위치와 빠른 메시지를 나누는 서버입니다.

- Node.js 22.13 이상(내장 SQLite 사용). 외부 패키지는 `ws` 하나.
- 사용자·방·멤버만 SQLite(`data/carmarade.db`)에 저장합니다. **위치는 메모리에만 두고 디스크에 남기지 않습니다.**

## 실행

```bash
cd server
npm install
npm start          # http://127.0.0.1:8787
npm test           # 동작 테스트 12개
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
| `POST /v1/rooms` `{title, kind, visibility, destination, waypoints}` | 방 만들기(초대 코드 포함) |
| `GET /v1/rooms?lat=&lon=&radiusKm=` | 같은 목적지의 공개 방 |
| `GET /v1/rooms/:id` | 방 정보(멤버만) |
| `POST /v1/rooms/:id/join` | 공개 방 참여 |
| `POST /v1/join` `{code}` | 초대 코드로 입장 |
| `POST /v1/rooms/:id/route` | 목적지·경유지 변경(방장) |
| `POST /v1/rooms/:id/leave`, `POST /v1/rooms/:id/close` | 나가기, 닫기(방장) |

장소 형식: `{placeId?, name, lat, lon}`.

### 실시간 `GET /v1/live` (WebSocket, Bearer 헤더)

| 보내기 | 받기 |
|---|---|
| `{type:"subscribe", roomId}` | `snapshot`(멤버, 최근 위치) |
| `{type:"location", roomId, lat, lon, heading?, sharing:"exact"\|"approximate"\|"off"}` | `location`, `location-removed` |
| `{type:"message", roomId, recipient:"all"\|userId, text}` | `message` |
| `{type:"unsubscribe", roomId}` | `members`, `room`(경로 변경), `room-closed`, `removed`, `error` |

## 실제 서비스 전에 할 일

- HTTPS 프록시(예: Caddy, Nginx) 뒤에 두고 `HOST=127.0.0.1` 유지. 앱은 https 주소만 허용합니다(개발용 10.0.2.2·localhost만 예외).
- 계정 복구·기기 변경(지금은 토큰을 잃으면 새 계정), 신고·차단, 방 만료 정리.
- 서버 여러 대로 늘릴 때는 실시간 상태(구독·위치)를 Redis 같은 공용 저장소로 옮겨야 합니다.
