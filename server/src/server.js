import http from 'node:http';
import crypto from 'node:crypto';
import { WebSocketServer } from 'ws';
import { openDb } from './db.js';

export const LIMITS = {
  maxMembers: 5,            // 앱과 같은 최대 5대
  nameLength: 24,
  titleLength: 40,
  maxWaypoints: 2,          // Pleos 내비 경유지 최대 2개
  sameDestinationKm: 1,     // 같은 목적지로 보는 기본 반경
  maxSearchKm: 5,
  staleLocationMs: 120_000, // 2분 동안 새 위치가 없으면 지움
  minLocationGapMs: 900,    // 위치는 1초에 한 번 정도만 받음
  codeFailures: 10,         // 초대 코드 오입력 허용 횟수(10분)
  codeWindowMs: 600_000,
  bodyBytes: 16_384,
  messageLength: 200,
  maxAudioFramesPerSec: 100, // 통화 음성은 20ms 단위(초당 50개)가 기본, 두 배까지만 받음
  noteLength: 200,          // 모집 글
  departAheadMs: 60 * 86_400_000, // 출발 시간은 60일 안쪽
  feedSize: 30,
  feedKeepAfterDepartMs: 6 * 3_600_000, // 출발 6시간이 지난 모집은 목록에서 뺌
};
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // 헷갈리는 0/O, 1/I/L 제외

class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const fail = (status, code, message) => { throw new ApiError(status, code, message); };

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newId = (prefix) => `${prefix}_${crypto.randomBytes(9).toString('base64url')}`;
const newToken = () => crypto.randomBytes(32).toString('base64url');
function newCode() {
  const bytes = crypto.randomBytes(6);
  return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');
}
export function normalizeCode(code) { return String(code ?? '').toUpperCase().replace(/[^A-Z0-9]/g, ''); }

export function haversineKm(lat1, lon1, lat2, lon2) {
  const r = (d) => (d * Math.PI) / 180;
  const a = Math.sin(r(lat2 - lat1) / 2) ** 2 + Math.cos(r(lat1)) * Math.cos(r(lat2)) * Math.sin(r(lon2 - lon1) / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ---------- 입력 검사 ----------
function text(value, field, max, { optional = false } = {}) {
  if (value === undefined || value === null || value === '') {
    if (optional) return null;
    fail(400, 'invalid', `${field}을(를) 입력해 주세요.`);
  }
  if (typeof value !== 'string') fail(400, 'invalid', `${field} 형식이 올바르지 않아요.`);
  const v = value.trim();
  if (!v || v.length > max) fail(400, 'invalid', `${field}은(는) 1~${max}자로 입력해 주세요.`);
  return v;
}
function coord(lat, lon) {
  if (typeof lat !== 'number' || typeof lon !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon) ||
      lat < -90 || lat > 90 || lon < -180 || lon > 180) fail(400, 'invalid', '올바르지 않은 위치입니다.');
  return { lat, lon };
}
function place(p, field) {
  if (!p || typeof p !== 'object') fail(400, 'invalid', `${field}를 선택해 주세요.`);
  const { lat, lon } = coord(p.lat, p.lon);
  return { placeId: text(p.placeId, `${field} ID`, 64, { optional: true }), name: text(p.name, `${field} 이름`, LIMITS.titleLength), lat, lon };
}

// ---------- 서버 ----------
export function createServer({ dbFile = ':memory:', now = () => Date.now(), log = () => {} } = {}) {
  const db = openDb(dbFile);
  const q = {
    insertUser: db.prepare('INSERT INTO users (id, name, car, token_hash, created_at) VALUES (?, ?, ?, ?, ?)'),
    userByToken: db.prepare('SELECT id, name, car FROM users WHERE token_hash = ?'),
    user: db.prepare('SELECT id, name, car FROM users WHERE id = ?'),
    insertRoom: db.prepare(`INSERT INTO rooms (id, title, kind, visibility, dest_place_id, dest_name, dest_lat, dest_lon,
      waypoints_json, invite_code, host_id, status, created_at, note, depart_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?)`),
    room: db.prepare('SELECT * FROM rooms WHERE id = ?'),
    roomByCode: db.prepare("SELECT * FROM rooms WHERE invite_code = ? AND status = 'open'"),
    openPublicRooms: db.prepare("SELECT * FROM rooms WHERE status = 'open' AND visibility = 'public' ORDER BY created_at DESC LIMIT 500"),
    myRooms: db.prepare(`SELECT r.* FROM rooms r JOIN members m ON m.room_id = r.id
      WHERE m.user_id = ? AND r.status = 'open' ORDER BY m.joined_at DESC`),
    members: db.prepare(`SELECT u.id, u.name, u.car, m.role, m.joined_at, m.accepted, m.phase FROM members m JOIN users u ON u.id = m.user_id
      WHERE m.room_id = ? ORDER BY m.joined_at, u.id`),
    memberCount: db.prepare('SELECT COUNT(*) AS n FROM members WHERE room_id = ?'),
    isMember: db.prepare('SELECT 1 AS ok FROM members WHERE room_id = ? AND user_id = ?'),
    addMember: db.prepare('INSERT INTO members (room_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)'),
    setAccepted: db.prepare('UPDATE members SET accepted = ? WHERE room_id = ? AND user_id = ?'),
    setPhase: db.prepare('UPDATE members SET phase = ? WHERE room_id = ? AND user_id = ?'),
    resetAccepted: db.prepare('UPDATE members SET accepted = 0 WHERE room_id = ?'),
    removeMember: db.prepare('DELETE FROM members WHERE room_id = ? AND user_id = ?'),
    setRole: db.prepare('UPDATE members SET role = ? WHERE room_id = ? AND user_id = ?'),
    setHost: db.prepare('UPDATE rooms SET host_id = ? WHERE id = ?'),
    closeRoom: db.prepare("UPDATE rooms SET status = 'closed' WHERE id = ?"),
    setRoute: db.prepare('UPDATE rooms SET dest_place_id = ?, dest_name = ?, dest_lat = ?, dest_lon = ?, waypoints_json = ? WHERE id = ?'),
  };

  // 실시간 상태(메모리): 방별 구독 소켓, 방별 마지막 위치
  const live = new Map();       // roomId -> Map<userId, Set<ws>>
  const locations = new Map();  // roomId -> Map<userId, location>
  const codeFailures = new Map(); // userId -> [timestamps]
  const voice = new Map();      // roomId -> Set<userId>  지금 통화 중인 사람들(메모리에만 둠, 음성은 저장하지 않음)

  const isMember = (roomId, userId) => !!q.isMember.get(roomId, userId);
  const memberList = (roomId) => q.members.all(roomId).map(({ id, name, car, role, accepted, phase }) => ({ id, name, car, role, accepted: !!accepted, phase }));
  const roomView = (r) => ({
    id: r.id, title: r.title, kind: r.kind, visibility: r.visibility, status: r.status,
    destination: { placeId: r.dest_place_id, name: r.dest_name, lat: r.dest_lat, lon: r.dest_lon },
    waypoints: JSON.parse(r.waypoints_json), inviteCode: r.invite_code, hostId: r.host_id,
    note: r.note ?? null, departAt: r.depart_at ?? null,
    maxMembers: LIMITS.maxMembers, members: memberList(r.id), createdAt: r.created_at,
  });
  /** 방 밖 사람에게 보여 주는 요약. 초대 코드와 멤버 ID는 빼고 인원·호스트 이름만. */
  const roomSummary = (r, userId, distanceKm) => ({
    id: r.id, title: r.title, kind: r.kind,
    destination: { placeId: r.dest_place_id, name: r.dest_name, lat: r.dest_lat, lon: r.dest_lon },
    waypoints: JSON.parse(r.waypoints_json), memberCount: q.memberCount.get(r.id).n, maxMembers: LIMITS.maxMembers,
    hostName: q.user.get(r.host_id)?.name ?? '', joined: isMember(r.id, userId),
    note: r.note ?? null, departAt: r.depart_at ?? null,
    distanceKm: distanceKm == null ? null : Math.round(distanceKm * 100) / 100, createdAt: r.created_at,
  });
  function openRoom(roomId) {
    const r = q.room.get(roomId);
    if (!r || r.status !== 'open') fail(404, 'room_not_found', '방을 찾을 수 없어요.');
    return r;
  }
  function memberRoom(roomId, userId) {
    const r = openRoom(roomId);
    if (!isMember(roomId, userId)) fail(403, 'not_member', '방 멤버만 볼 수 있어요.');
    return r;
  }
  function join(r, user) {
    if (isMember(r.id, user.id)) return roomView(r);
    if (q.memberCount.get(r.id).n >= LIMITS.maxMembers) fail(409, 'room_full', `이 방은 이미 ${LIMITS.maxMembers}대가 함께하고 있어요.`);
    q.addMember.run(r.id, user.id, 'member', now());
    log(`join room=${r.id} user=${user.id}`);
    pushMembers(r.id);
    return roomView(q.room.get(r.id));
  }
  function leave(roomId, userId) {
    const r = memberRoom(roomId, userId);
    q.removeMember.run(roomId, userId);
    dropLocation(roomId, userId);
    dropVoice(roomId, userId);
    unsubscribeUser(roomId, userId);
    const rest = q.members.all(roomId);
    if (rest.length === 0) { closeRoom(roomId); return; }
    if (r.host_id === userId) { q.setHost.run(rest[0].id, roomId); q.setRole.run('host', roomId, rest[0].id); }
    pushMembers(roomId);
  }
  function closeRoom(roomId) {
    q.closeRoom.run(roomId);
    locations.delete(roomId);
    voice.delete(roomId);
    broadcast(roomId, { type: 'room-closed', roomId });
    live.delete(roomId);
    log(`close room=${roomId}`);
  }

  // ---------- 실시간 ----------
  function sendWs(ws, msg) { if (ws.readyState === 1) ws.send(JSON.stringify(msg)); }
  function broadcast(roomId, msg, { except, only } = {}) {
    const subs = live.get(roomId);
    if (!subs) return;
    for (const [userId, sockets] of subs) {
      if (userId === except) continue;
      if (only && userId !== only) continue;
      for (const ws of sockets) sendWs(ws, msg);
    }
  }
  function pushMembers(roomId) { broadcast(roomId, { type: 'members', roomId, members: memberList(roomId) }); }
  function freshLocations(roomId) {
    const t = now();
    return [...(locations.get(roomId)?.values() ?? [])].filter((l) => t - l.at <= LIMITS.staleLocationMs);
  }
  function dropLocation(roomId, userId) {
    const m = locations.get(roomId);
    if (m?.delete(userId)) broadcast(roomId, { type: 'location-removed', roomId, userId });
  }
  function unsubscribeUser(roomId, userId) {
    const sockets = live.get(roomId)?.get(userId);
    if (!sockets) return;
    for (const ws of sockets) { ws.rooms?.delete(roomId); sendWs(ws, { type: 'removed', roomId }); }
    live.get(roomId).delete(userId);
  }
  function subscribe(ws, roomId) {
    memberRoom(roomId, ws.user.id);
    if (!live.has(roomId)) live.set(roomId, new Map());
    const subs = live.get(roomId);
    if (!subs.has(ws.user.id)) subs.set(ws.user.id, new Set());
    subs.get(ws.user.id).add(ws);
    ws.rooms.add(roomId);
    sendWs(ws, { type: 'snapshot', roomId, members: memberList(roomId), locations: freshLocations(roomId) });
  }
  function unsubscribe(ws, roomId) {
    ws.rooms.delete(roomId);
    const sockets = live.get(roomId)?.get(ws.user.id);
    if (!sockets) return;
    sockets.delete(ws);
    // 이 사용자의 연결이 모두 끊기면 위치도 지움(공유가 조용히 계속되지 않게).
    if (sockets.size === 0) { live.get(roomId).delete(ws.user.id); dropLocation(roomId, ws.user.id); dropVoice(roomId, ws.user.id); }
  }
  // ---------- 통화(음성 중계) ----------
  // 음성은 서버가 받은 즉시 같은 통화 중인 다른 멤버에게만 넘기고, 저장하거나 기록하지 않는다.
  function voiceMembers(roomId) { return [...(voice.get(roomId) ?? [])]; }
  function pushVoice(roomId) { broadcast(roomId, { type: 'call-members', roomId, members: voiceMembers(roomId) }); }
  function joinVoice(roomId, userId) {
    if (!voice.has(roomId)) voice.set(roomId, new Set());
    const set = voice.get(roomId);
    if (set.has(userId)) return;
    if (set.size >= LIMITS.maxMembers) fail(409, 'call_full', '통화 인원이 가득 찼어요.');
    set.add(userId); pushVoice(roomId);
  }
  function dropVoice(roomId, userId) {
    const set = voice.get(roomId);
    if (!set?.delete(userId)) return;
    if (set.size === 0) voice.delete(roomId);
    pushVoice(roomId);
  }
  function onCall(ws, m) {
    const roomId = String(m.roomId ?? '');
    if (!ws.rooms.has(roomId)) fail(403, 'not_subscribed', '먼저 방에 연결해 주세요.');
    memberRoom(roomId, ws.user.id);
    const me = ws.user.id;
    if (m.type === 'call-invite') {
      const to = m.to === 'all' ? 'all' : String(m.to ?? '');
      if (to !== 'all' && (to === me || !isMember(roomId, to))) fail(400, 'invalid', '받는 사람이 방 멤버가 아니에요.');
      joinVoice(roomId, me);
      const out = { type: 'call-invite', roomId, from: me, to };
      if (to === 'all') broadcast(roomId, out, { except: me }); else broadcast(roomId, out, { only: to });
    } else if (m.type === 'call-join') {
      joinVoice(roomId, me);
    } else if (m.type === 'call-leave') {
      dropVoice(roomId, me);
    } else if (m.type === 'call-decline') {
      const to = String(m.to ?? '');
      if (!isMember(roomId, to)) fail(400, 'invalid', '받는 사람이 방 멤버가 아니에요.');
      broadcast(roomId, { type: 'call-declined', roomId, from: me }, { only: to });
    }
  }
  /** 바이너리 프레임: [0x01][방 ID 길이][방 ID][음성]. 통화 중인 다른 멤버에게 [0x01][보낸 사람 ID 길이][보낸 사람 ID][음성]으로 넘김. */
  function onAudio(ws, data) {
    const buf = Buffer.isBuffer(data) ? data : Buffer.concat(Array.isArray(data) ? data : [Buffer.from(data)]);
    if (buf.length < 3 || buf[0] !== 0x01) return;
    const idLen = buf[1];
    if (buf.length < 2 + idLen + 1) return;
    const roomId = buf.subarray(2, 2 + idLen).toString('utf8');
    const me = ws.user.id;
    if (!ws.rooms.has(roomId) || !voice.get(roomId)?.has(me)) return; // 통화 중이 아니면 버림
    const t = now();
    if (t - ws.audioWindow.start >= 1000) { ws.audioWindow.start = t; ws.audioWindow.count = 0; }
    if (++ws.audioWindow.count > LIMITS.maxAudioFramesPerSec) return; // 너무 잦은 프레임은 버림
    const payload = buf.subarray(2 + idLen);
    const from = Buffer.from(me, 'utf8');
    const out = Buffer.concat([Buffer.from([0x01, from.length]), from, payload]);
    for (const uid of voice.get(roomId)) {
      if (uid === me) continue;
      for (const sock of live.get(roomId)?.get(uid) ?? []) if (sock.readyState === 1) sock.send(out, { binary: true });
    }
  }
  function onLocation(ws, m) {
    const roomId = String(m.roomId ?? '');
    if (!ws.rooms.has(roomId)) fail(403, 'not_subscribed', '먼저 방에 연결해 주세요.');
    memberRoom(roomId, ws.user.id);
    if (m.sharing === 'off') { dropLocation(roomId, ws.user.id); return; }
    if (m.sharing !== 'exact' && m.sharing !== 'approximate') fail(400, 'invalid', '공유 방식이 올바르지 않아요.');
    const t = now();
    const key = `${roomId}|${ws.user.id}`;
    if (t - (ws.lastLocation.get(key) ?? 0) < LIMITS.minLocationGapMs) return; // 너무 잦은 위치는 버림
    ws.lastLocation.set(key, t);
    let { lat, lon } = coord(m.lat, m.lon);
    const approximate = m.sharing === 'approximate';
    // 대략 위치는 서버에 정확한 값을 남기지 않도록 받자마자 약 1km 단위로 낮춤.
    if (approximate) { lat = Math.round(lat * 100) / 100; lon = Math.round(lon * 100) / 100; }
    const heading = typeof m.heading === 'number' && Number.isFinite(m.heading) ? ((m.heading % 360) + 360) % 360 : null;
    const loc = { userId: ws.user.id, lat, lon, heading: approximate ? null : heading, approximate, at: t };
    if (!locations.has(roomId)) locations.set(roomId, new Map());
    locations.get(roomId).set(ws.user.id, loc);
    broadcast(roomId, { type: 'location', roomId, ...loc }, { except: ws.user.id });
  }
  function onMessage(ws, m) {
    const roomId = String(m.roomId ?? '');
    if (!ws.rooms.has(roomId)) fail(403, 'not_subscribed', '먼저 방에 연결해 주세요.');
    memberRoom(roomId, ws.user.id);
    const recipient = m.recipient === 'all' ? 'all' : String(m.recipient ?? '');
    if (recipient !== 'all' && (recipient === ws.user.id || !isMember(roomId, recipient))) fail(400, 'invalid', '받는 사람이 방 멤버가 아니에요.');
    const body = text(m.text, '메시지', LIMITS.messageLength);
    const out = { type: 'message', roomId, from: ws.user.id, recipient, text: body, at: now() };
    if (recipient === 'all') broadcast(roomId, out, { except: ws.user.id });
    else broadcast(roomId, out, { only: recipient });
  }
  const sweeper = setInterval(() => {
    const t = now();
    for (const [roomId, m] of locations) for (const [userId, l] of m) if (t - l.at > LIMITS.staleLocationMs) dropLocation(roomId, userId);
  }, 15_000);
  sweeper.unref();

  // ---------- HTTP ----------
  function authUser(req) {
    const h = req.headers.authorization ?? '';
    const token = h.startsWith('Bearer ') ? h.slice(7).trim() : '';
    const user = token ? q.userByToken.get(sha256(token)) : null;
    if (!user) fail(401, 'unauthorized', '다시 로그인해 주세요.');
    return user;
  }
  function readJson(req) {
    return new Promise((resolve, reject) => {
      let size = 0; const chunks = [];
      req.on('data', (c) => { size += c.length; if (size > LIMITS.bodyBytes) { reject(new ApiError(413, 'too_large', '요청이 너무 커요.')); req.destroy(); } else chunks.push(c); });
      req.on('end', () => {
        if (!chunks.length) return resolve({});
        try { const v = JSON.parse(Buffer.concat(chunks).toString('utf8')); resolve(v && typeof v === 'object' ? v : {}); }
        catch { reject(new ApiError(400, 'bad_json', 'JSON 형식이 올바르지 않아요.')); }
      });
      req.on('error', reject);
    });
  }
  function checkCodeLimit(userId) {
    const t = now();
    const list = (codeFailures.get(userId) ?? []).filter((x) => t - x < LIMITS.codeWindowMs);
    codeFailures.set(userId, list);
    if (list.length >= LIMITS.codeFailures) fail(429, 'too_many_attempts', '초대 코드를 너무 많이 틀렸어요. 10분 뒤에 다시 시도해 주세요.');
  }

  const routes = [
    ['GET', /^\/health$/, async () => ({ ok: true })],
    ['POST', /^\/v1\/users$/, async (req) => {
      const b = await readJson(req);
      const name = text(b.name, '이름', LIMITS.nameLength);
      const car = text(b.car ?? '내 차량', '차량', LIMITS.nameLength);
      const id = newId('u'); const token = newToken();
      q.insertUser.run(id, name, car, sha256(token), now());
      return { status: 201, body: { user: { id, name, car }, token } };
    }],
    ['GET', /^\/v1\/me$/, async (req) => ({ user: authUser(req) })],
    ['GET', /^\/v1\/me\/rooms$/, async (req) => {
      const user = authUser(req);
      return { rooms: q.myRooms.all(user.id).map(roomView) };
    }],
    ['POST', /^\/v1\/rooms$/, async (req) => {
      const user = authUser(req); const b = await readJson(req);
      const title = text(b.title, '방 이름', LIMITS.titleLength);
      const kind = b.kind === 'crew' ? 'crew' : 'trip';
      const visibility = b.visibility === 'private' ? 'private' : 'public';
      const destination = place(b.destination, '목적지');
      const waypoints = Array.isArray(b.waypoints) ? b.waypoints.map((w) => place(w, '경유지')) : [];
      if (waypoints.length > LIMITS.maxWaypoints) fail(400, 'invalid', `경유지는 최대 ${LIMITS.maxWaypoints}개예요.`);
      const note = text(b.note, '모집 글', LIMITS.noteLength, { optional: true });
      const id = newId('r'); const t = now();
      let departAt = null;
      if (b.departAt !== undefined && b.departAt !== null) {
        departAt = Number(b.departAt);
        if (!Number.isFinite(departAt) || departAt < t - 3_600_000 || departAt > t + LIMITS.departAheadMs) fail(400, 'invalid', '출발 시간을 다시 골라 주세요.');
      }
      for (let i = 0; ; i++) {
        try {
          q.insertRoom.run(id, title, kind, visibility, destination.placeId, destination.name, destination.lat, destination.lon,
            JSON.stringify(waypoints), newCode(), user.id, t, note, departAt);
          break;
        } catch (e) { if (i >= 5 || !String(e.message).includes('UNIQUE')) throw e; } // 코드가 겹치면 다시 만듦
      }
      q.addMember.run(id, user.id, 'host', t);
      log(`create room=${id} user=${user.id} visibility=${visibility}`);
      return { status: 201, body: { room: roomView(q.room.get(id)) } };
    }],
    ['GET', /^\/v1\/rooms$/, async (req, url) => {
      const user = authUser(req);
      const lat = Number(url.searchParams.get('lat')); const lon = Number(url.searchParams.get('lon'));
      coord(lat, lon);
      const radius = Math.min(Math.max(Number(url.searchParams.get('radiusKm')) || LIMITS.sameDestinationKm, 0.1), LIMITS.maxSearchKm);
      const rooms = q.openPublicRooms.all()
        .map((r) => ({ r, d: haversineKm(lat, lon, r.dest_lat, r.dest_lon) }))
        .filter(({ d }) => d <= radius)
        .sort((a, b) => a.d - b.d || b.r.created_at - a.r.created_at)
        .slice(0, 20)
        .map(({ r, d }) => roomSummary(r, user.id, d));
      return { rooms };
    }],
    // 커뮤니티: 지금 모집 중인 공개 방(자리 있음). 출발 시간이 가까운 순, 시간 없는 방은 최근 순으로 뒤에.
    ['GET', /^\/v1\/rooms\/open$/, async (req, url) => {
      const user = authUser(req);
      const query = (url.searchParams.get('q') ?? '').trim().toLowerCase().slice(0, 40);
      const t = now();
      const rooms = q.openPublicRooms.all()
        .filter((r) => r.depart_at == null || r.depart_at >= t - LIMITS.feedKeepAfterDepartMs)
        .filter((r) => !query || [r.title, r.dest_name, r.note ?? '', r.waypoints_json].some((v) => v.toLowerCase().includes(query)))
        .filter((r) => q.memberCount.get(r.id).n < LIMITS.maxMembers || isMember(r.id, user.id))
        .sort((a, b) => (a.depart_at ?? Infinity) - (b.depart_at ?? Infinity) || b.created_at - a.created_at)
        .slice(0, LIMITS.feedSize)
        .map((r) => roomSummary(r, user.id, null));
      return { rooms };
    }],
    ['GET', /^\/v1\/rooms\/([\w-]+)$/, async (req, _url, [roomId]) => {
      const user = authUser(req);
      return { room: roomView(memberRoom(roomId, user.id)) };
    }],
    ['POST', /^\/v1\/rooms\/([\w-]+)\/join$/, async (req, _url, [roomId]) => {
      const user = authUser(req);
      const r = openRoom(roomId);
      if (r.visibility !== 'public' && !isMember(roomId, user.id)) fail(403, 'private_room', '비공개 방은 초대 코드로 들어올 수 있어요.');
      return { room: join(r, user) };
    }],
    ['POST', /^\/v1\/join$/, async (req) => {
      const user = authUser(req); const b = await readJson(req);
      checkCodeLimit(user.id);
      const code = normalizeCode(b.code);
      const r = code.length === 6 ? q.roomByCode.get(code) : null;
      if (!r) { codeFailures.get(user.id).push(now()); fail(404, 'code_not_found', '초대 코드를 확인해 주세요.'); }
      return { room: join(r, user) };
    }],
    ['POST', /^\/v1\/rooms\/([\w-]+)\/route$/, async (req, _url, [roomId]) => {
      const user = authUser(req); const b = await readJson(req);
      const r = memberRoom(roomId, user.id);
      if (r.host_id !== user.id) fail(403, 'not_host', '방장만 목적지를 바꿀 수 있어요.');
      const destination = place(b.destination, '목적지');
      const waypoints = Array.isArray(b.waypoints) ? b.waypoints.map((w) => place(w, '경유지')) : [];
      if (waypoints.length > LIMITS.maxWaypoints) fail(400, 'invalid', `경유지는 최대 ${LIMITS.maxWaypoints}개예요.`);
      q.setRoute.run(destination.placeId, destination.name, destination.lat, destination.lon, JSON.stringify(waypoints), roomId);
      q.resetAccepted.run(roomId); // 경로가 바뀌면 모두 다시 수락
      const room = roomView(q.room.get(roomId));
      broadcast(roomId, { type: 'room', roomId, room });
      pushMembers(roomId);
      return { room };
    }],
    // 내 상태 알리기: 최신 경로 수락, 출발(driving), 도착(arrived). 도착하면 서버의 내 위치도 지움.
    ['POST', /^\/v1\/rooms\/([\w-]+)\/status$/, async (req, _url, [roomId]) => {
      const user = authUser(req); const b = await readJson(req);
      memberRoom(roomId, user.id);
      if (b.accepted !== undefined) {
        if (typeof b.accepted !== 'boolean') fail(400, 'invalid', '수락 여부가 올바르지 않아요.');
        q.setAccepted.run(b.accepted ? 1 : 0, roomId, user.id);
      }
      if (b.phase !== undefined) {
        if (!['planning', 'driving', 'arrived'].includes(b.phase)) fail(400, 'invalid', '상태가 올바르지 않아요.');
        q.setPhase.run(b.phase, roomId, user.id);
        if (b.phase === 'arrived') { dropLocation(roomId, user.id); dropVoice(roomId, user.id); }
      }
      pushMembers(roomId);
      return { room: roomView(q.room.get(roomId)) };
    }],
    ['POST', /^\/v1\/rooms\/([\w-]+)\/leave$/, async (req, _url, [roomId]) => {
      const user = authUser(req); leave(roomId, user.id); return { ok: true };
    }],
    ['POST', /^\/v1\/rooms\/([\w-]+)\/close$/, async (req, _url, [roomId]) => {
      const user = authUser(req); const r = memberRoom(roomId, user.id);
      if (r.host_id !== user.id) fail(403, 'not_host', '방장만 방을 닫을 수 있어요.');
      closeRoom(roomId); return { ok: true };
    }],
  ];

  const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const reply = (status, body) => {
      res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
      res.end(JSON.stringify(body));
    };
    try {
      const route = routes.find(([m, re]) => m === req.method && re.test(url.pathname));
      if (!route) fail(404, 'not_found', '없는 주소예요.');
      const params = url.pathname.match(route[1]).slice(1);
      const out = await route[2](req, url, params);
      if (out && out.status) reply(out.status, out.body); else reply(200, out);
    } catch (e) {
      if (e instanceof ApiError) reply(e.status, { error: { code: e.code, message: e.message } });
      else { log(`error ${req.method} ${url.pathname}: ${e.stack ?? e}`); reply(500, { error: { code: 'server_error', message: '서버 오류가 났어요.' } }); }
    }
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: 4096 });
  httpServer.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');
    let user = null;
    try { if (url.pathname === '/v1/live') user = authUser(req); } catch { /* 아래에서 거절 */ }
    if (!user) { socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.user = user; ws.rooms = new Set(); ws.lastLocation = new Map(); ws.alive = true;
      ws.audioWindow = { start: 0, count: 0 };
      ws.on('pong', () => { ws.alive = true; });
      ws.on('message', (data, isBinary) => {
        if (isBinary) { try { onAudio(ws, data); } catch (e) { log(`audio error: ${e.stack ?? e}`); } return; }
        let m;
        try { m = JSON.parse(String(data)); } catch { return sendWs(ws, { type: 'error', code: 'bad_json', message: 'JSON 형식이 올바르지 않아요.' }); }
        try {
          if (m.type === 'subscribe') subscribe(ws, String(m.roomId ?? ''));
          else if (m.type === 'unsubscribe') unsubscribe(ws, String(m.roomId ?? ''));
          else if (m.type === 'location') onLocation(ws, m);
          else if (m.type === 'message') onMessage(ws, m);
          else if (typeof m.type === 'string' && m.type.startsWith('call-')) onCall(ws, m);
          else fail(400, 'unknown_type', '알 수 없는 요청이에요.');
        } catch (e) {
          if (e instanceof ApiError) sendWs(ws, { type: 'error', code: e.code, message: e.message, roomId: m.roomId });
          else { log(`ws error: ${e.stack ?? e}`); sendWs(ws, { type: 'error', code: 'server_error', message: '서버 오류가 났어요.' }); }
        }
      });
      ws.on('close', () => { for (const roomId of [...ws.rooms]) unsubscribe(ws, roomId); });
    });
  });
  // 응답 없는 연결 정리(30초)
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) { if (!ws.alive) { ws.terminate(); continue; } ws.alive = false; ws.ping(); }
  }, 30_000);
  heartbeat.unref();

  return {
    httpServer,
    listen(port = 0, host = '127.0.0.1') {
      return new Promise((resolve) => httpServer.listen(port, host, () => resolve(httpServer.address().port)));
    },
    async close() {
      clearInterval(sweeper); clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      await new Promise((r) => httpServer.close(r));
      db.close();
    },
  };
}
