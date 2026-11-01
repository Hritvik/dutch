import assert from 'node:assert/strict';
import test from 'node:test';

import { ActivityContractError, fetchActivity, parseActivityEntry } from './activity-client';

const GROUP = 'grp_000000000000000001';
const entry = {
  activityId: 'act_000000000000000001',
  scopeId: `group:${GROUP}:INR`,
  actor: 'mem_000000000000000001',
  action: 'EXPENSE_CORRECTED',
  targetId: 'exp_000000000000000001',
  targetRevision: 2,
  occurredAt: '2026-11-01T00:00:00Z',
  details: { fromRevision: '1', toRevision: '2', totalMinorUnits: '120' }
};

test('parses an activity entry with its privacy-safe details', () => {
  assert.deepEqual(parseActivityEntry(entry), entry);
});

test('accepts a Former member entry without an actor', () => {
  const { actor: _actor, ...former } = entry;
  assert.equal(parseActivityEntry({ ...former, action: 'MEMBERSHIP_CHANGED' }).actor, undefined);
});

test('rejects an unknown action and non-string details', () => {
  assert.throws(() => parseActivityEntry({ ...entry, action: 'PAYMENT_SENT' }),
    ActivityContractError);
  assert.throws(() => parseActivityEntry({ ...entry, details: { totalMinorUnits: 120 } }),
    ActivityContractError);
});

test('fetches the group activity feed', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const entries = await fetchActivity('https://api.example.test', 'token', GROUP,
    async (url, init) => {
      requested = { url, init };
      return new Response(JSON.stringify({ data: [entry] }), { status: 200 });
    });
  assert.equal(entries[0]?.action, 'EXPENSE_CORRECTED');
  assert.equal(requested?.url,
    `https://api.example.test/api/v1/groups/${GROUP}/activity`);
});
