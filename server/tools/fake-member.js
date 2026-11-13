// 시연·테스트용 가상 멤버. 초대 코드로 방에 들어와, 서울숲 → 경유지 → 목적지를 따라 움직이며 위치를 보낸다.
// 사용: node tools/fake-member.js --code ABC234 [--name 지수] [--car "IONIQ 5"] [--minutes 4] [--offset 0.05] [--server http://127.0.0.1:8787]
// 입장 3초 뒤 경로 수락, 다시 3초 뒤 출발(--acceptAfter, --departAfter 초), 목적지에 닿으면 도착을 알리고 끝낸다.
import WebSocket from 'ws';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []));
const server = args.server ?? 'http://127.0.0.1:8787';
const code = args.code;
const name = args.name ?? '지수';
const car = args.car ?? 'IONIQ 5';
const minutes = Number(args.minutes ?? 4);
const startOffset = Number(args.offset ?? 0.05); // 출발 시 이미 지난 비율(내 차와 겹치지 않게)
if (!code) { console.error('--code 초대코드 가 필요해요'); process.exit(1); }

async function api(method, path, token, body) {
  const res = await fetch(server + path, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json();
  if (!res.ok) throw new Error(`${method} ${path} ${res.status}: ${json.error?.message}`);
  return json;
}

const { user, token } = await api('POST', '/v1/users', null, { name, car });
const { room } = await api('POST', '/v1/join', token, { code });
console.log(`${name}(${user.id}) → 방 "${room.title}" 입장, 멤버 ${room.members.length}명`);
// 앱처럼 경로 수락 → 출발 상태를 알림(잠깐씩 쉬어 화면에서 변화가 보이게).
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
await wait(Number(args.acceptAfter ?? 3) * 1000);
await api('POST', `/v1/rooms/${room.id}/status`, token, { accepted: true });
console.log('경로 수락');
await wait(Number(args.departAfter ?? 3) * 1000);
await api('POST', `/v1/rooms/${room.id}/status`, token, { phase: 'driving' });
console.log('출발');

const stops = [{ lat: 37.5445, lon: 127.0374 }, ...room.waypoints, room.destination];
const legs = stops.slice(1).map((b, i) => { const a = stops[i]; return { a, b, km: Math.hypot(b.lat - a.lat, (b.lon - a.lon) * 0.79) }; });
const total = legs.reduce((s, l) => s + l.km, 0);
function pointAt(f) {
  let left = Math.min(Math.max(f, 0), 1) * total;
  for (const l of legs) { if (left <= l.km) { const k = l.km ? left / l.km : 0; return { lat: l.a.lat + (l.b.lat - l.a.lat) * k, lon: l.a.lon + (l.b.lon - l.a.lon) * k }; } left -= l.km; }
  return stops.at(-1);
}

const ws = new WebSocket(server.replace(/^http/, 'ws') + '/v1/live', { headers: { authorization: `Bearer ${token}` } });
const startedAt = Date.now();
ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'subscribe', roomId: room.id }));
  const timer = setInterval(() => {
    const f = startOffset + (Date.now() - startedAt) / (minutes * 60_000);
    const p = pointAt(f);
    ws.send(JSON.stringify({ type: 'location', roomId: room.id, lat: p.lat, lon: p.lon, sharing: 'exact' }));
    if (f >= 1) {
      console.log('목적지 도착. 위치 공유를 끝내요.'); clearInterval(timer);
      api('POST', `/v1/rooms/${room.id}/status`, token, { phase: 'arrived' }).finally(() => setTimeout(() => process.exit(0), 500));
    }
  }, 2000);
  setTimeout(() => ws.send(JSON.stringify({ type: 'message', roomId: room.id, recipient: 'all', text: '잘 따라가고 있어요' })), 8000);
});
ws.on('message', (d) => {
  const m = JSON.parse(String(d));
  if (m.type === 'location') console.log(`위치 수신 ${m.userId}: ${m.lat.toFixed(4)}, ${m.lon.toFixed(4)}${m.approximate ? ' (대략)' : ''}`);
  else if (m.type === 'message') console.log(`메시지 ${m.from} → ${m.recipient}: ${m.text}`);
  else if (m.type !== 'snapshot') console.log(m.type, m.message ?? '');
});
ws.on('close', () => { console.log('연결 종료'); process.exit(0); });
