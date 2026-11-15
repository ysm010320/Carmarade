import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { createServer, normalizeCode } from '../src/server.js';

const GANGNEUNG = { placeId: 'anmok', name: '강릉 안목해변', lat: 37.7712, lon: 128.9487 };
const HOENGSEONG = { placeId: 'hoengseong', name: '횡성휴게소', lat: 37.46, lon: 128.1341 };
let server; let base; let wsBase;

before(async () => {
  server = createServer();
  const port = await server.listen(0);
  base = `http://127.0.0.1:${port}`; wsBase = `ws://127.0.0.1:${port}/v1/live`;
});
after(() => server.close());

async function api(method, path, { token, body } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json() };
}
async function signup(name, car = 'IONIQ 5') {
  const r = await api('POST', '/v1/users', { body: { name, car } });
  assert.equal(r.status, 201);
  return r.body;
}
async function makeRoom(token, extra = {}) {
  const r = await api('POST', '/v1/rooms', { token, body: { title: '주말엔, 동해', destination: GANGNEUNG, waypoints: [HOENGSEONG], ...extra } });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  return r.body.room;
}
/** 실시간 연결. 받은 메시지를 모아 두고 원하는 메시지를 기다릴 수 있게 함. */
function connect(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsBase, { headers: { authorization: `Bearer ${token}` } });
    const inbox = []; const waiters = [];
    ws.audio = [];
    ws.on('message', (d, isBinary) => {
      if (isBinary) { ws.audio.push(Buffer.from(d)); return; }
      const m = JSON.parse(String(d)); inbox.push(m);
      for (const w of [...waiters]) if (w.match(m)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m); }
    });
    ws.inbox = inbox;
    ws.next = (match, ms = 2000) => new Promise((res, rej) => {
      const found = inbox.find(match);
      if (found) { inbox.splice(inbox.indexOf(found), 1); return res(found); }
      const w = { match: (m) => { if (match(m)) { inbox.splice(inbox.indexOf(m), 1); return true; } return false; }, resolve: res };
      waiters.push(w);
      setTimeout(() => { if (waiters.includes(w)) { waiters.splice(waiters.indexOf(w), 1); rej(new Error('timeout waiting for message')); } }, ms);
    });
    ws.send2 = (m) => ws.send(JSON.stringify(m));
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test('가입하면 토큰으로 내 정보를 볼 수 있고, 잘못된 토큰은 거절', async () => {
  const a = await signup('하늘');
  assert.equal((await api('GET', '/v1/me', { token: a.token })).body.user.name, '하늘');
  assert.equal((await api('GET', '/v1/me', { token: 'nope' })).status, 401);
  assert.equal((await api('POST', '/v1/users', { body: { name: '' } })).status, 400);
});

test('방을 만들면 방장이 되고 6자리 초대 코드가 생김', async () => {
  const a = await signup('방장');
  const room = await makeRoom(a.token);
  assert.match(room.inviteCode, /^[A-Z2-9]{6}$/);
  assert.equal(room.members.length, 1);
  assert.equal(room.members[0].role, 'host');
  assert.equal(room.hostId, a.user.id);
  const bad = await api('POST', '/v1/rooms', { token: a.token, body: { title: 'x', destination: GANGNEUNG, waypoints: [HOENGSEONG, HOENGSEONG, HOENGSEONG] } });
  assert.equal(bad.status, 400);
});

test('같은 목적지(반경 1km) 공개 방만 검색되고, 초대 코드는 노출되지 않음', async () => {
  const a = await signup('공개방장'); const b = await signup('찾는사람');
  const pub = await makeRoom(a.token, { title: '강릉 공개' });
  const priv = await makeRoom(a.token, { title: '강릉 비공개', visibility: 'private' });
  await makeRoom(a.token, { title: '춘천', destination: { name: '춘천 의암호', lat: 37.838, lon: 127.6744 } });
  const r = await api('GET', `/v1/rooms?lat=${GANGNEUNG.lat + 0.005}&lon=${GANGNEUNG.lon}`, { token: b.token });
  assert.equal(r.status, 200);
  const ids = r.body.rooms.map((x) => x.id);
  assert.ok(ids.includes(pub.id));
  assert.ok(!ids.includes(priv.id));
  assert.ok(r.body.rooms.every((x) => x.destination.name === '강릉 안목해변'));
  assert.ok(r.body.rooms.every((x) => x.inviteCode === undefined && x.members === undefined));
});

test('공개 방은 바로 참여, 비공개 방은 초대 코드로만 참여', async () => {
  const a = await signup('A'); const b = await signup('B');
  const pub = await makeRoom(a.token);
  const joined = await api('POST', `/v1/rooms/${pub.id}/join`, { token: b.token });
  assert.equal(joined.status, 200);
  assert.equal(joined.body.room.members.length, 2);
  const priv = await makeRoom(a.token, { visibility: 'private' });
  assert.equal((await api('POST', `/v1/rooms/${priv.id}/join`, { token: b.token })).status, 403);
  const byCode = await api('POST', '/v1/join', { token: b.token, body: { code: priv.inviteCode.toLowerCase().replace(/(...)/, '$1-') } });
  assert.equal(byCode.status, 200);
  assert.equal(byCode.body.room.id, priv.id);
  // 멤버가 아니면 방 정보를 볼 수 없음
  const c = await signup('C');
  assert.equal((await api('GET', `/v1/rooms/${priv.id}`, { token: c.token })).status, 403);
});

test('최대 5대, 초대 코드 오입력은 10번까지', async () => {
  const host = await signup('H'); const room = await makeRoom(host.token);
  for (let i = 0; i < 4; i++) { const u = await signup(`m${i}`); assert.equal((await api('POST', '/v1/join', { token: u.token, body: { code: room.inviteCode } })).status, 200); }
  const late = await signup('late');
  assert.equal((await api('POST', '/v1/join', { token: late.token, body: { code: room.inviteCode } })).status, 409);
  const guesser = await signup('guess');
  for (let i = 0; i < 10; i++) assert.equal((await api('POST', '/v1/join', { token: guesser.token, body: { code: 'ZZZZZ2' } })).status, 404);
  assert.equal((await api('POST', '/v1/join', { token: guesser.token, body: { code: 'ZZZZZ2' } })).status, 429);
});

test('위치는 같은 방 멤버에게만 전달되고, 대략 위치는 서버에서 낮춰 보냄', async () => {
  const a = await signup('보내는사람'); const b = await signup('같은방'); const outsider = await signup('다른방');
  const room = await makeRoom(a.token);
  await api('POST', '/v1/join', { token: b.token, body: { code: room.inviteCode } });
  const other = await makeRoom(outsider.token);
  const wa = await connect(a.token); const wb = await connect(b.token); const wo = await connect(outsider.token);
  wa.send2({ type: 'subscribe', roomId: room.id }); await wa.next((m) => m.type === 'snapshot');
  wb.send2({ type: 'subscribe', roomId: room.id }); const snap = await wb.next((m) => m.type === 'snapshot');
  assert.equal(snap.members.length, 2);
  wo.send2({ type: 'subscribe', roomId: room.id });
  assert.equal((await wo.next((m) => m.type === 'error')).code, 'not_member');
  wo.send2({ type: 'subscribe', roomId: other.id }); await wo.next((m) => m.type === 'snapshot');

  wa.send2({ type: 'location', roomId: room.id, lat: 37.54456, lon: 127.03749, heading: 90, sharing: 'approximate' });
  const got = await wb.next((m) => m.type === 'location');
  assert.equal(got.userId, a.user.id);
  assert.equal(got.lat, 37.54); assert.equal(got.lon, 127.04);
  assert.equal(got.approximate, true); assert.equal(got.heading, null);
  await sleep(200);
  assert.ok(!wo.inbox.some((m) => m.type === 'location'), '다른 방에는 위치가 가지 않음');
  assert.ok(!wa.inbox.some((m) => m.type === 'location'), '보낸 사람에게는 다시 보내지 않음');

  // 나중에 연결한 멤버도 마지막 위치를 받음
  const wb2 = await connect(b.token);
  wb2.send2({ type: 'subscribe', roomId: room.id });
  const snap2 = await wb2.next((m) => m.type === 'snapshot');
  assert.equal(snap2.locations.length, 1);

  // 공유 끄기 → 즉시 삭제 알림
  await sleep(1000);
  wa.send2({ type: 'location', roomId: room.id, sharing: 'off' });
  assert.equal((await wb.next((m) => m.type === 'location-removed')).userId, a.user.id);
  for (const w of [wa, wb, wb2, wo]) w.close();
});

test('메시지는 방 멤버에게, 지정한 사람에게만', async () => {
  const a = await signup('a'); const b = await signup('b'); const c = await signup('c');
  const room = await makeRoom(a.token);
  for (const u of [b, c]) await api('POST', '/v1/join', { token: u.token, body: { code: room.inviteCode } });
  const [wa, wb, wc] = await Promise.all([a, b, c].map((u) => connect(u.token)));
  for (const w of [wa, wb, wc]) { w.send2({ type: 'subscribe', roomId: room.id }); await w.next((m) => m.type === 'snapshot'); }
  wa.send2({ type: 'message', roomId: room.id, recipient: 'all', text: '잠깐 쉬어가요' });
  assert.equal((await wb.next((m) => m.type === 'message')).text, '잠깐 쉬어가요');
  assert.equal((await wc.next((m) => m.type === 'message')).from, a.user.id);
  wa.send2({ type: 'message', roomId: room.id, recipient: b.user.id, text: '먼저 갈게요' });
  assert.equal((await wb.next((m) => m.type === 'message')).recipient, b.user.id);
  await sleep(200);
  assert.ok(!wc.inbox.some((m) => m.type === 'message'));
  for (const w of [wa, wb, wc]) w.close();
});

test('방을 나가거나 연결이 끊기면 위치가 지워지고, 방장이 나가면 다음 사람이 방장', async () => {
  const a = await signup('host'); const b = await signup('member');
  const room = await makeRoom(a.token);
  await api('POST', '/v1/join', { token: b.token, body: { code: room.inviteCode } });
  const wa = await connect(a.token); const wb = await connect(b.token);
  for (const w of [wa, wb]) { w.send2({ type: 'subscribe', roomId: room.id }); await w.next((m) => m.type === 'snapshot'); }
  wa.send2({ type: 'location', roomId: room.id, lat: 37.5, lon: 127.0, sharing: 'exact' });
  await wb.next((m) => m.type === 'location');
  wa.close();
  assert.equal((await wb.next((m) => m.type === 'location-removed')).userId, a.user.id);

  assert.equal((await api('POST', `/v1/rooms/${room.id}/leave`, { token: a.token })).status, 200);
  const members = await wb.next((m) => m.type === 'members');
  assert.equal(members.members.length, 1);
  assert.equal(members.members[0].role, 'host');
  const info = await api('GET', `/v1/rooms/${room.id}`, { token: b.token });
  assert.equal(info.body.room.hostId, b.user.id);
  // 마지막 사람이 나가면 방이 닫히고 코드로도 못 들어옴
  await api('POST', `/v1/rooms/${room.id}/leave`, { token: b.token });
  const c = await signup('c');
  assert.equal((await api('POST', '/v1/join', { token: c.token, body: { code: room.inviteCode } })).status, 404);
  wb.close();
});

test('방장만 방을 닫을 수 있고, 닫히면 멤버에게 알림', async () => {
  const a = await signup('host2'); const b = await signup('member2');
  const room = await makeRoom(a.token);
  await api('POST', '/v1/join', { token: b.token, body: { code: room.inviteCode } });
  assert.equal((await api('POST', `/v1/rooms/${room.id}/close`, { token: b.token })).status, 403);
  const wb = await connect(b.token);
  wb.send2({ type: 'subscribe', roomId: room.id }); await wb.next((m) => m.type === 'snapshot');
  assert.equal((await api('POST', `/v1/rooms/${room.id}/close`, { token: a.token })).status, 200);
  await wb.next((m) => m.type === 'room-closed');
  assert.equal((await api('GET', '/v1/me/rooms', { token: b.token })).body.rooms.length, 0);
  wb.close();
});

test('방장만 목적지를 바꾸고, 연결된 멤버에게 바로 알림', async () => {
  const a = await signup('route-host'); const b = await signup('route-member');
  const room = await makeRoom(a.token);
  await api('POST', '/v1/join', { token: b.token, body: { code: room.inviteCode } });
  const wb = await connect(b.token);
  wb.send2({ type: 'subscribe', roomId: room.id }); await wb.next((m) => m.type === 'snapshot');
  const chuncheon = { placeId: 'chuncheon', name: '춘천 의암호', lat: 37.838, lon: 127.6744 };
  assert.equal((await api('POST', `/v1/rooms/${room.id}/route`, { token: b.token, body: { destination: chuncheon } })).status, 403);
  const ok = await api('POST', `/v1/rooms/${room.id}/route`, { token: a.token, body: { destination: chuncheon, waypoints: [] } });
  assert.equal(ok.status, 200);
  const pushed = await wb.next((m) => m.type === 'room');
  assert.equal(pushed.room.destination.name, '춘천 의암호');
  assert.equal(pushed.room.waypoints.length, 0);
  // 같은 목적지 검색도 바뀐 목적지 기준
  const near = await api('GET', `/v1/rooms?lat=${chuncheon.lat}&lon=${chuncheon.lon}`, { token: b.token });
  assert.ok(near.body.rooms.some((x) => x.id === room.id));
  wb.close();
});

test('커뮤니티: 모집 중인 공개 방만, 출발 시간이 가까운 순으로', async () => {
  const host = await signup('모집방장'); const viewer = await signup('구경꾼');
  const soon = Date.now() + 3_600_000; const later = Date.now() + 86_400_000;
  const tag = `동해-${Date.now()}`;
  const b = await makeRoom(host.token, { title: `${tag} 내일`, departAt: later, note: '천천히 가요' });
  const a = await makeRoom(host.token, { title: `${tag} 곧`, departAt: soon, note: '휴게소에서 커피 한잔' });
  const c = await makeRoom(host.token, { title: `${tag} 시간 미정` });
  await makeRoom(host.token, { title: `${tag} 비공개`, visibility: 'private' });
  const full = await makeRoom(host.token, { title: `${tag} 꽉참` });
  for (let i = 0; i < 4; i++) { const u = await signup(`f${i}`); await api('POST', '/v1/join', { token: u.token, body: { code: full.inviteCode } }); }
  const closed = await makeRoom(host.token, { title: `${tag} 닫힘` });
  await api('POST', `/v1/rooms/${closed.id}/close`, { token: host.token });

  const r = await api('GET', `/v1/rooms/open?q=${encodeURIComponent(tag)}`, { token: viewer.token });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.rooms.map((x) => x.id), [a.id, b.id, c.id]);
  assert.equal(r.body.rooms[0].note, '휴게소에서 커피 한잔');
  assert.equal(r.body.rooms[0].departAt, soon);
  assert.ok(r.body.rooms.every((x) => x.inviteCode === undefined));
  // 목적지 이름으로도 찾음
  const byDest = await api('GET', `/v1/rooms/open?q=${encodeURIComponent('안목')}`, { token: viewer.token });
  assert.ok(byDest.body.rooms.some((x) => x.id === a.id));
  // 출발 시간·모집 글 검사
  assert.equal((await api('POST', '/v1/rooms', { token: host.token, body: { title: 'x', destination: GANGNEUNG, departAt: Date.now() - 86_400_000 } })).status, 400);
  assert.equal((await api('POST', '/v1/rooms', { token: host.token, body: { title: 'x', destination: GANGNEUNG, note: 'ㅋ'.repeat(201) } })).status, 400);
});

test('멤버 상태: 수락·출발·도착이 바로 전해지고, 경로가 바뀌면 수락이 풀리고, 도착하면 위치가 지워짐', async () => {
  const a = await signup('status-host'); const b = await signup('status-member');
  const room = await makeRoom(a.token);
  await api('POST', '/v1/join', { token: b.token, body: { code: room.inviteCode } });
  const wa = await connect(a.token); const wb = await connect(b.token);
  for (const w of [wa, wb]) { w.send2({ type: 'subscribe', roomId: room.id }); await w.next((m) => m.type === 'snapshot'); }
  const mine = (ms, id) => ms.members.find((m) => m.id === id);

  assert.equal((await api('POST', `/v1/rooms/${room.id}/status`, { token: b.token, body: { accepted: true } })).status, 200);
  let pushed = await wa.next((m) => m.type === 'members');
  assert.deepEqual([mine(pushed, b.user.id).accepted, mine(pushed, b.user.id).phase], [true, 'planning']);

  // 방장이 경로를 바꾸면 모두 다시 수락해야 함
  await api('POST', `/v1/rooms/${room.id}/route`, { token: a.token, body: { destination: HOENGSEONG } });
  pushed = await wa.next((m) => m.type === 'members');
  assert.equal(mine(pushed, b.user.id).accepted, false);

  await api('POST', `/v1/rooms/${room.id}/status`, { token: b.token, body: { accepted: true, phase: 'driving' } });
  pushed = await wa.next((m) => m.type === 'members' && mine(m, b.user.id).phase === 'driving');
  assert.equal(mine(pushed, b.user.id).accepted, true);

  wb.send2({ type: 'location', roomId: room.id, lat: 37.6, lon: 128.5, sharing: 'exact' });
  await wa.next((m) => m.type === 'location');
  await api('POST', `/v1/rooms/${room.id}/status`, { token: b.token, body: { phase: 'arrived' } });
  assert.equal((await wa.next((m) => m.type === 'location-removed')).userId, b.user.id);

  assert.equal((await api('POST', `/v1/rooms/${room.id}/status`, { token: b.token, body: { phase: 'flying' } })).status, 400);
  const outsider = await signup('status-outsider');
  assert.equal((await api('POST', `/v1/rooms/${room.id}/status`, { token: outsider.token, body: { accepted: true } })).status, 403);
  wa.close(); wb.close();
});

/** 음성 프레임: [0x01][방 ID 길이][방 ID][음성] */
const frame = (roomId, payload) => Buffer.concat([Buffer.from([0x01, roomId.length]), Buffer.from(roomId), Buffer.from(payload)]);

test('통화: 초대받은 사람이 들어오면 음성이 통화 중인 다른 사람에게만 전달되고, 저장되지 않음', async () => {
  const a = await signup('하늘'); const b = await signup('바다'); const c = await signup('구름');
  const room = await makeRoom(a.token);
  await api('POST', `/v1/rooms/${room.id}/join`, { token: b.token });
  await api('POST', `/v1/rooms/${room.id}/join`, { token: c.token });
  const [wa, wb, wc] = await Promise.all([connect(a.token), connect(b.token), connect(c.token)]);
  for (const w of [wa, wb, wc]) { w.send2({ type: 'subscribe', roomId: room.id }); await w.next((m) => m.type === 'snapshot'); }
  wa.send2({ type: 'call-invite', roomId: room.id, to: b.user.id });
  const inv = await wb.next((m) => m.type === 'call-invite');
  assert.equal(inv.from, a.user.id);
  await sleep(100);
  assert.equal(wc.inbox.some((m) => m.type === 'call-invite'), false, '초대받지 않은 사람에겐 가지 않음');
  assert.deepEqual((await wa.next((m) => m.type === 'call-members')).members, [a.user.id]);
  // 아직 b는 통화에 안 들어옴 → 음성은 아무에게도 가지 않음
  wa.send(frame(room.id, [1, 2, 3]));
  await sleep(100);
  assert.equal(wb.audio.length, 0);
  wb.send2({ type: 'call-join', roomId: room.id });
  assert.deepEqual((await wa.next((m) => m.type === 'call-members' && m.members.length === 2)).members.sort(), [a.user.id, b.user.id].sort());
  wa.send(frame(room.id, [9, 8, 7, 6]));
  await sleep(150);
  assert.equal(wb.audio.length, 1);
  assert.equal(wb.audio[0][0], 0x01);
  const idLen = wb.audio[0][1];
  assert.equal(wb.audio[0].subarray(2, 2 + idLen).toString(), a.user.id);
  assert.deepEqual([...wb.audio[0].subarray(2 + idLen)], [9, 8, 7, 6]);
  assert.equal(wc.audio.length, 0, '통화 중이 아닌 사람에겐 가지 않음');
  assert.equal(wa.audio.length, 0, '보낸 사람에겐 되돌아오지 않음');
  // 통화에서 나가면 더 이상 받지 않음
  wb.send2({ type: 'call-leave', roomId: room.id });
  await wa.next((m) => m.type === 'call-members' && m.members.length === 1);
  wa.send(frame(room.id, [5]));
  await sleep(100);
  assert.equal(wb.audio.length, 1);
  for (const w of [wa, wb, wc]) w.close();
});

test('통화: 거절 알림, 방 밖 사람·잘못된 프레임은 무시, 연결이 끊기면 통화에서 빠짐', async () => {
  const a = await signup('하늘'); const b = await signup('바다'); const x = await signup('남');
  const room = await makeRoom(a.token);
  await api('POST', `/v1/rooms/${room.id}/join`, { token: b.token });
  const [wa, wb, wx] = await Promise.all([connect(a.token), connect(b.token), connect(x.token)]);
  for (const w of [wa, wb]) { w.send2({ type: 'subscribe', roomId: room.id }); await w.next((m) => m.type === 'snapshot'); }
  wa.send2({ type: 'call-invite', roomId: room.id, to: 'all' });
  await wb.next((m) => m.type === 'call-invite');
  wb.send2({ type: 'call-decline', roomId: room.id, to: a.user.id });
  assert.equal((await wa.next((m) => m.type === 'call-declined')).from, b.user.id);
  // 방 밖 사람은 통화를 시작할 수 없음
  wx.send2({ type: 'call-join', roomId: room.id });
  assert.equal((await wx.next((m) => m.type === 'error')).code, 'not_subscribed');
  wx.send(frame(room.id, [1]));            // 구독하지 않은 방의 음성은 버림
  wa.send(Buffer.from([0x01]));            // 짧은 프레임은 버림
  wa.send(Buffer.from([0x02, 1, 2, 3]));   // 모르는 종류는 버림
  await sleep(100);
  assert.equal(wb.audio.length, 0);
  // 통화 중에 연결이 끊기면 통화에서 빠지고 알림
  wb.send2({ type: 'call-join', roomId: room.id });
  await wa.next((m) => m.type === 'call-members' && m.members.length === 2);
  wb.close();
  assert.deepEqual((await wa.next((m) => m.type === 'call-members' && m.members.length === 1)).members, [a.user.id]);
  wa.close(); wx.close();
});

test('토큰 없는 실시간 연결은 거절', async () => {
  await assert.rejects(new Promise((resolve, reject) => {
    const ws = new WebSocket(wsBase); ws.on('open', resolve); ws.on('error', reject);
  }));
});

test('초대 코드 정리', () => {
  assert.equal(normalizeCode(' ab-c2 3d '), 'ABC23D');
});
