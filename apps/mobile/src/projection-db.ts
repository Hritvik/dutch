import * as SQLite from 'expo-sqlite';

import type { SyncChange, SyncPage } from './sync-client';
import { resourceKey } from './cache';

/**
 * The durable, account-scoped projection. Every row is bound to the active
 * Member so an account switch can never expose another account's cache.
 */
const DATABASE = 'dutch-projection.db';

let opening: Promise<SQLite.SQLiteDatabase> | null = null;

async function database(): Promise<SQLite.SQLiteDatabase> {
  if (!opening) {
    opening = (async () => {
      const db = await SQLite.openDatabaseAsync(DATABASE);
      await db.execAsync(`
        PRAGMA journal_mode = WAL;
        CREATE TABLE IF NOT EXISTS meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS resources (
          scope TEXT NOT NULL,
          type TEXT NOT NULL,
          id TEXT NOT NULL,
          version INTEGER NOT NULL,
          payload TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          PRIMARY KEY (scope, type, id)
        );
        CREATE TABLE IF NOT EXISTS tombstones (
          scope TEXT NOT NULL,
          type TEXT NOT NULL,
          id TEXT NOT NULL,
          version INTEGER NOT NULL,
          PRIMARY KEY (scope, type, id)
        );
        CREATE TABLE IF NOT EXISTS drafts (
          scope TEXT NOT NULL,
          id TEXT NOT NULL,
          kind TEXT NOT NULL,
          payload TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          PRIMARY KEY (scope, id)
        );
        CREATE TABLE IF NOT EXISTS cursors (
          scope TEXT NOT NULL PRIMARY KEY,
          cursor TEXT NOT NULL,
          updated_at TEXT NOT NULL
        );
      `);
      return db;
    })();
  }
  return opening;
}

export async function setActiveMember(memberId: string): Promise<void> {
  const db = await database();
  const row = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM meta WHERE key = ?', 'active_member');
  if (row?.value === memberId) {
    return;
  }
  await db.withTransactionAsync(async () => {
    for (const table of ['resources', 'tombstones', 'drafts', 'cursors']) {
      await db.runAsync(`DELETE FROM ${table}`);
    }
    await db.runAsync(
      'INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = ?',
      'active_member', memberId, memberId);
  });
}

export async function activeMember(): Promise<string | null> {
  const db = await database();
  const row = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM meta WHERE key = ?', 'active_member');
  return row?.value ?? null;
}

export async function wipeProjection(): Promise<void> {
  const db = await database();
  await db.withTransactionAsync(async () => {
    for (const table of ['resources', 'tombstones', 'drafts', 'cursors', 'meta']) {
      await db.runAsync(`DELETE FROM ${table}`);
    }
  });
}

export async function readCursor(memberId: string): Promise<string | null> {
  const db = await database();
  const row = await db.getFirstAsync<{ cursor: string }>(
    'SELECT cursor FROM cursors WHERE scope = ?', memberId);
  return row?.cursor ?? null;
}

export async function applySyncPage(memberId: string, page: SyncPage): Promise<void> {
  const db = await database();
  await db.withTransactionAsync(async () => {
    await applyChanges(db, memberId, page.changes);
    await db.runAsync(
      `INSERT INTO cursors (scope, cursor, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(scope) DO UPDATE SET cursor = excluded.cursor, updated_at = excluded.updated_at`,
      memberId, page.nextCursor, new Date().toISOString());
  });
}

export async function applySnapshotPage(
  memberId: string,
  changes: SyncChange[],
  cursor: string
): Promise<void> {
  const db = await database();
  await db.withTransactionAsync(async () => {
    await db.runAsync('DELETE FROM resources WHERE scope = ?', memberId);
    await db.runAsync('DELETE FROM tombstones WHERE scope = ?', memberId);
    await applyChanges(db, memberId, changes);
    await db.runAsync(
      `INSERT INTO cursors (scope, cursor, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(scope) DO UPDATE SET cursor = excluded.cursor, updated_at = excluded.updated_at`,
      memberId, cursor, new Date().toISOString());
  });
}

async function applyChanges(
  db: SQLite.SQLiteDatabase,
  memberId: string,
  changes: SyncChange[]
): Promise<void> {
  for (const change of changes) {
    if (change.operation === 'full_resync_required') {
      throw new Error('The sync generation changed; request a full snapshot.');
    }
    if (change.operation === 'tombstone') {
      const tombstone = await db.getFirstAsync<{ version: number }>(
        'SELECT version FROM tombstones WHERE scope = ? AND type = ? AND id = ?',
        memberId, change.resourceType, change.resourceId);
      if (tombstone === null || change.resourceVersion >= tombstone.version) {
        await db.runAsync(
          `INSERT INTO tombstones (scope, type, id, version) VALUES (?, ?, ?, ?)
           ON CONFLICT(scope, type, id) DO UPDATE SET version = excluded.version`,
          memberId, change.resourceType, change.resourceId, change.resourceVersion);
        await db.runAsync(
          'DELETE FROM resources WHERE scope = ? AND type = ? AND id = ?',
          memberId, change.resourceType, change.resourceId);
      }
      continue;
    }
    const tombstone = await db.getFirstAsync<{ version: number }>(
      'SELECT version FROM tombstones WHERE scope = ? AND type = ? AND id = ?',
      memberId, change.resourceType, change.resourceId);
    if (tombstone && change.resourceVersion <= tombstone.version) {
      continue;
    }
    const current = await db.getFirstAsync<{ version: number }>(
      'SELECT version FROM resources WHERE scope = ? AND type = ? AND id = ?',
      memberId, change.resourceType, change.resourceId);
    if (current && change.resourceVersion <= current.version) {
      continue;
    }
    await db.runAsync(
      `INSERT INTO resources (scope, type, id, version, payload, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(scope, type, id) DO UPDATE SET
         version = excluded.version, payload = excluded.payload, updated_at = excluded.updated_at`,
      memberId, change.resourceType, change.resourceId, change.resourceVersion,
      JSON.stringify(change.payload), change.occurredAt);
  }
}

export type CachedRow = {
  type: string;
  id: string;
  version: number;
  payload: Record<string, unknown>;
  updatedAt: string;
};

export async function readResources(memberId: string, type: string): Promise<CachedRow[]> {
  const db = await database();
  const rows = await db.getAllAsync<{
    type: string; id: string; version: number; payload: string; updated_at: string;
  }>('SELECT type, id, version, payload, updated_at FROM resources '
    + 'WHERE scope = ? AND type = ? ORDER BY id', memberId, type);
  return rows.map((row) => ({
    type: row.type,
    id: row.id,
    version: row.version,
    payload: JSON.parse(row.payload) as Record<string, unknown>,
    updatedAt: row.updated_at
  }));
}

export async function readResource(
  memberId: string,
  type: string,
  id: string
): Promise<CachedRow | null> {
  const db = await database();
  const row = await db.getFirstAsync<{
    type: string; id: string; version: number; payload: string; updated_at: string;
  }>('SELECT type, id, version, payload, updated_at FROM resources '
    + 'WHERE scope = ? AND type = ? AND id = ?', memberId, type, id);
  if (!row) return null;
  return {
    type: row.type,
    id: row.id,
    version: row.version,
    payload: JSON.parse(row.payload) as Record<string, unknown>,
    updatedAt: row.updated_at
  };
}

export async function saveDraft(
  memberId: string,
  kind: string,
  payload: unknown
): Promise<void> {
  const db = await database();
  await db.runAsync(
    `INSERT INTO drafts (scope, id, kind, payload, updated_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(scope, id) DO UPDATE SET
       kind = excluded.kind, payload = excluded.payload, updated_at = excluded.updated_at`,
    memberId, kind, kind, JSON.stringify(payload), new Date().toISOString());
}

export async function loadDraft<T>(memberId: string, kind: string): Promise<T | null> {
  const db = await database();
  const row = await db.getFirstAsync<{ payload: string }>(
    'SELECT payload FROM drafts WHERE scope = ? AND id = ?', memberId, kind);
  return row ? JSON.parse(row.payload) as T : null;
}

export async function deleteDraft(memberId: string, kind: string): Promise<void> {
  const db = await database();
  await db.runAsync('DELETE FROM drafts WHERE scope = ? AND id = ?', memberId, kind);
}

export function cacheKey(type: string, id: string): string {
  return resourceKey(type, id);
}
