import assert from 'node:assert/strict';
import test from 'node:test';

import {
  SyncContractError,
  SyncHttpError,
  createSyncSnapshot,
  fetchSyncPage,
  readSyncSnapshot
} from './sync-client';

const change = {
  sequence: 3,
  resourceType: 'EXPENSE',
  resourceId: 'exp_000000000000000001',
  operation: 'upsert',
  resourceVersion: 1,
  payload: { description: 'Dinner' },
  schemaVersion: '1',
  occurredAt: '2026-11-02T00:00:00Z'
};

test('fetches an incremental page with the cursor and limit', async () => {
  let requested: string | undefined;
  const page = await fetchSyncPage('https://api.example.test', 'token', 'cursor-1', 50,
    async (url) => {
      requested = url;
      return new Response(JSON.stringify({ data: { changes: [change], nextCursor: 'cursor-2' } }),
        { status: 200 });
    });
  assert.equal(page.changes[0]?.resourceId, change.resourceId);
  assert.equal(page.nextCursor, 'cursor-2');
  assert.match(String(requested), /cursor=cursor-1/);
  assert.match(String(requested), /limit=50/);
});

test('surfaces sync_cursor_expired so the client can request a snapshot', async () => {
  await assert.rejects(
    fetchSyncPage('https://api.example.test', 'token', null, 10,
      async () => new Response(JSON.stringify({
        error: { code: 'sync_cursor_expired', message: 'expired' }
      }), { status: 409 })),
    (error: unknown) => error instanceof SyncHttpError
      && error.status === 409
      && error.code === 'sync_cursor_expired'
  );
});

test('creates and reads a snapshot', async () => {
  const snapshot = await createSyncSnapshot('https://api.example.test', 'token',
    async () => new Response(JSON.stringify({ data: { token: 'snap', asOfSequence: 9 } }),
      { status: 200 }));
  assert.deepEqual(snapshot, { token: 'snap', asOfSequence: 9 });

  const page = await readSyncSnapshot('https://api.example.test', 'token', 'snap',
    async (url) => {
      assert.match(String(url), /sync-snapshots\/snap$/);
      return new Response(JSON.stringify({
        data: { changes: [change], cursor: 'cursor-9', asOfSequence: 9 }
      }), { status: 200 });
    });
  assert.equal(page.asOfSequence, 9);
  assert.equal(page.cursor, 'cursor-9');
});

test('rejects a malformed page and an out-of-range limit', async () => {
  await assert.rejects(
    fetchSyncPage('https://api.example.test', 'token', null, 0),
    SyncContractError);
  await assert.rejects(
    fetchSyncPage('https://api.example.test', 'token', 'cursor', 10,
      async () => new Response(JSON.stringify({ data: { changes: [{ sequence: 1 }] } }),
        { status: 200 })),
    SyncContractError);
});
