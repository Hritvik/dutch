import assert from 'node:assert/strict';
import test from 'node:test';

import {
  FullResyncRequired,
  applyPage,
  emptyCache,
  listResources,
  readResource
} from './cache';
import type { SyncChange, SyncPage } from './sync-client';

function change(overrides: Partial<SyncChange>): SyncChange {
  return {
    sequence: 1,
    resourceType: 'EXPENSE',
    resourceId: 'exp_000000000000000001',
    operation: 'upsert',
    resourceVersion: 1,
    payload: { description: 'Dinner' },
    schemaVersion: '1',
    occurredAt: '2026-11-02T00:00:00Z',
    ...overrides
  };
}

function page(changes: SyncChange[], nextCursor: string): SyncPage {
  return { changes, nextCursor };
}

test('applies a page and advances the cursor atomically', () => {
  const cache = applyPage(emptyCache(), page([change({})], 'cursor-1'));
  assert.equal(cache.cursor, 'cursor-1');
  assert.equal(readResource(cache, 'EXPENSE', 'exp_000000000000000001')?.payload.description,
    'Dinner');
});

test('ignores an older resource version and converges on duplicates', () => {
  const first = applyPage(emptyCache(), page([change({ resourceVersion: 2, payload: { v: 2 } })], 'c1'));
  const stale = applyPage(first, page([change({ sequence: 2, resourceVersion: 1, payload: { v: 1 } })], 'c2'));
  assert.equal(readResource(stale, 'EXPENSE', 'exp_000000000000000001')?.payload.v, 2);
  const duplicate = applyPage(stale, page([change({ sequence: 2, resourceVersion: 2, payload: { v: 2 } })], 'c3'));
  assert.equal(readResource(duplicate, 'EXPENSE', 'exp_000000000000000001')?.payload.v, 2);
});

test('a tombstone wins and blocks a stale upsert from resurrecting the resource', () => {
  const live = applyPage(emptyCache(), page([change({ resourceVersion: 3 })], 'c1'));
  const gone = applyPage(live, page([
    change({ sequence: 2, operation: 'tombstone', resourceVersion: 4, payload: {} })
  ], 'c2'));
  assert.equal(readResource(gone, 'EXPENSE', 'exp_000000000000000001'), undefined);
  const resurrect = applyPage(gone, page([
    change({ sequence: 3, resourceVersion: 4 })
  ], 'c3'));
  assert.equal(readResource(resurrect, 'EXPENSE', 'exp_000000000000000001'), undefined);
  const newer = applyPage(gone, page([change({ sequence: 4, resourceVersion: 5 })], 'c4'));
  assert.equal(readResource(newer, 'EXPENSE', 'exp_000000000000000001')?.version, 5);
});

test('a full-resync marker fails closed instead of applying the page', () => {
  assert.throws(() => applyPage(emptyCache(), page([
    change({ operation: 'full_resync_required' })
  ], 'c1')), FullResyncRequired);
});

test('lists only the requested resource type', () => {
  const cache = applyPage(emptyCache(), page([
    change({}),
    change({ sequence: 2, resourceType: 'SERIES', resourceId: 'ser_000000000000000001' })
  ], 'c1'));
  assert.equal(listResources(cache, 'SERIES').length, 1);
  assert.equal(listResources(cache, 'EXPENSE').length, 1);
});
