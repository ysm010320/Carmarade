import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

/** 사용자·방·멤버만 저장한다. 위치는 저장하지 않는다(메모리에서만 다룸). */
export function openDb(file) {
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      car TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS rooms (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      kind TEXT NOT NULL,
      visibility TEXT NOT NULL,
      dest_place_id TEXT,
      dest_name TEXT NOT NULL,
      dest_lat REAL NOT NULL,
      dest_lon REAL NOT NULL,
      waypoints_json TEXT NOT NULL,
      invite_code TEXT NOT NULL,
      host_id TEXT NOT NULL REFERENCES users(id),
      status TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS rooms_open_code ON rooms(invite_code) WHERE status = 'open';
    CREATE TABLE IF NOT EXISTS members (
      room_id TEXT NOT NULL REFERENCES rooms(id),
      user_id TEXT NOT NULL REFERENCES users(id),
      role TEXT NOT NULL,
      joined_at INTEGER NOT NULL,
      PRIMARY KEY (room_id, user_id)
    );
  `);
  return db;
}
