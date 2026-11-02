import assert from 'node:assert/strict';
import test from 'node:test';

import { syncProjection, type SyncStore } from './sync-coordinator';
import type { SyncChange, SyncPage } from './sync-client';

const MEMBER = 'mem_000000000000000001';

function change(sequence: number): SyncChange {
  return {
    sequence,
    resourceType: 'GROUP',
    resourceId: 'grp_000000000000000001',
    operation: 'upsert',
    resourceVersion: sequence,
    payload: { name: 'Flat 12' },
    schemaVersion: '1',
    occurredAt: '2026-11-02T00:00:00Z'
  };
}

class FakeStore implements SyncStore {
  cursor: string | null;
  pages: SyncPage[] = [];
  snapshots: { changes: SyncChange[]; cursor: string }[] = [];
  active: string | null = null;

  constructor(cursor: string | null) {
    this.cursor = cursor;
  }

  async setActiveMember(memberId: string): Promise<void> {
    this.active = memberId;
  }

  async readCursor(): Promise<string | null> {
    return this.cursor;
  }

  async applyPage(_memberId: string, next: SyncPage): Promise<void> {
    this.pages.push(next);
    this.cursor = next.nextCursor;
  }

  async applySnapshot(_memberId: string, changes: SyncChange[], cursor: string): Promise<void> {
    this.snapshots.push({ changes, cursor });
    this.cursor = cursor;
  }
}

function respond(routes: Record<string, () => Response>): typeof fetch {
  return async (input) => {
    const url = String(input);
    for (const [fragment, handler] of Object.entries(routes)) {
      if (url.includes(fragment)) return handler();
    }
    return new Response(JSON.stringify({ error: { code: 'not_found' } }), { status: 404 });
  };
}

test('applies an incremental page from a stored cursor', async () => {
  const store = new FakeStore('cursor-1');
  const outcome = await syncProjection('https://api.test', 'token', MEMBER, 'id-1',
    respond({
      '/api/v1/ledger/sync?': () => new Response(JSON.stringify({
        data: { changes: [change(2)], nextCursor: 'cursor-2' }
      }), { status: 200 })
    }), store);
  assert.deepEqual(outcome, { mode: 'incremental', changes: 1 });
  assert.equal(store.cursor, 'cursor-2');
  assert.equal(store.active, MEMBER);
});

test('recovers an expired cursor with a snapshot and catch-up page', async () => {
  const store = new FakeStore('expired');
  let syncCalls = 0;
  const outcome = await syncProjection('https://api.test', 'token', MEMBER, 'id-1',
    respond({
      '/sync-snapshots/mock': () => new Response(JSON.stringify({
        data: { changes: [change(1)], cursor: 'snapshot-cursor', asOfSequence: 1,
          truncated: false, nextSnapshotCursor: null }
      }), { status: 200 }),
      '/sync-snapshots': () => new Response(JSON.stringify({
        data: { token: 'mock', asOfSequence: 1 }
      }), { status: 200 }),
      '/api/v1/ledger/sync?': () => {
        syncCalls += 1;
        if (syncCalls === 1) {
          return new Response(JSON.stringify({
            error: { code: 'sync_cursor_expired' }
          }), { status: 409 });
        }
        return new Response(JSON.stringify({
          data: { changes: [], nextCursor: 'snapshot-cursor' }
        }), { status: 200 });
      }
    }), store);
  assert.equal(outcome.mode, 'snapshot');
  assert.equal(outcome.changes, 1);
  assert.equal(store.snapshots.length, 1);
  assert.equal(store.cursor, 'snapshot-cursor');
});

test('a missing cursor starts with a snapshot', async () => {
  const store = new FakeStore(null);
  const outcome = await syncProjection('https://api.test', 'token', MEMBER, 'id-1',
    respond({
      '/sync-snapshots/mock': () => new Response(JSON.stringify({
        data: { changes: [change(1), change(2)], cursor: 'c2', asOfSequence: 2,
          truncated: false, nextSnapshotCursor: null }
      }), { status: 200 }),
      '/sync-snapshots': () => new Response(JSON.stringify({
        data: { token: 'mock', asOfSequence: 2 }
      }), { status: 200 }),
      '/api/v1/ledger/sync?': () => new Response(JSON.stringify({
        data: { changes: [], nextCursor: 'c2' }
      }), { status: 200 })
    }), store);
  assert.equal(outcome.mode, 'snapshot');
  assert.equal(outcome.changes, 2);
});

test('collects a truncated snapshot across pages before applying it once', async () => {
  const store = new FakeStore(null);
  let snapshotPages = 0;
  const outcome = await syncProjection('https://api.test', 'token', MEMBER, 'id-1',
    respond({
      '/sync-snapshots/mock?limit=200': () => {
        snapshotPages += 1;
        if (snapshotPages === 1) {
          return new Response(JSON.stringify({
            data: {
              changes: [change(1)], cursor: 'c2', asOfSequence: 2,
              truncated: true, nextSnapshotCursor: 1
            }
          }), { status: 200 });
        }
        return new Response(JSON.stringify({
          data: {
            changes: [change(2)], cursor: 'c2', asOfSequence: 2,
            truncated: false, nextSnapshotCursor: null
          }
        }), { status: 200 });
      },
      '/sync-snapshots': () => new Response(JSON.stringify({
        data: { token: 'mock', asOfSequence: 2 }
      }), { status: 200 }),
      '/api/v1/ledger/sync?': () => new Response(JSON.stringify({
        data: { changes: [], nextCursor: 'c2' }
      }), { status: 200 })
    }), store);
  assert.equal(outcome.mode, 'snapshot');
  assert.equal(outcome.changes, 2);
  assert.equal(store.snapshots.length, 1);
  assert.equal(store.snapshots[0]?.changes.length, 2);
});

test('a network failure reports offline and applies nothing', async () => {
  const store = new FakeStore('cursor-1');
  const outcome = await syncProjection('https://api.test', 'token', MEMBER, 'id-1',
    async () => {
      throw new TypeError('Network request failed');
    }, store);
  assert.deepEqual(outcome, { mode: 'offline', changes: 0 });
  assert.equal(store.pages.length, 0);
  assert.equal(store.cursor, 'cursor-1');
});
