import sqlite3 from "sqlite3";
import { open } from "sqlite";

export async function getDb() {
  const db = await open({
    filename: "./app.db",
    driver: sqlite3.Database,
  });
  return db;
}

export async function initDb() {
  const db = await getDb();

  await db.exec(`
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      email TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      firstname TEXT,
      middlename TEXT,
      surname TEXT,
      company TEXT,
      team TEXT,
      role TEXT NOT NULL CHECK (role IN ('streamer','inspector'))
    );

    CREATE TABLE IF NOT EXISTS revoked_tokens (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      token TEXT NOT NULL,
      revoked_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS channels (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      status TEXT NOT NULL CHECK (status IN ('active','passive'))
    );

    CREATE TABLE IF NOT EXISTS media (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      type TEXT NOT NULL CHECK (type IN ('image','video')),
      filename TEXT NOT NULL,
      path TEXT NOT NULL,
      uploaded_by_email TEXT NOT NULL,
      channel_id INTEGER NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      shot_date TEXT,
      shot_time TEXT,
      loc_x REAL,
      loc_y REAL,
      resolution_w INTEGER,
      resolution_h INTEGER,
      filesize_bytes INTEGER,
      duration_sec REAL,
      validation_status TEXT NULL CHECK (validation_status IN ('valid','not-valid')),
      validated_by_email TEXT NULL,
      FOREIGN KEY(channel_id) REFERENCES channels(id)
    );

    CREATE TABLE IF NOT EXISTS comments (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      media_id INTEGER NOT NULL,
      email TEXT NOT NULL,
      comment TEXT NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(media_id) REFERENCES media(id)
    );
  `);

  return db;
}
