import assert from 'node:assert/strict';
import test from 'node:test';

import { correctExpense } from './corrections-client';
import { ExpenseHttpError } from './expenses-client';

const GROUP = 'grp_000000000000000001';
const EXPENSE = 'exp_000000000000000001';
const ADMIN = 'mem_000000000000000001';
const MEMBER = 'mem_000000000000000002';

const corrected = {
  id: EXPENSE,
  groupId: GROUP,
  description: 'Dinner corrected',
  totalMinorUnits: 120,
  currency: 'INR',
  splitMethod: 'EQUAL',
  payers: [{ memberId: ADMIN, minorUnits: 120 }],
  shares: [{ memberId: ADMIN, minorUnits: 60 }, { memberId: MEMBER, minorUnits: 60 }],
  entries: [{
    expenseId: EXPENSE,
    revision: 2,
    debtor: MEMBER,
    creditor: ADMIN,
    minorUnits: 60,
    currency: 'INR',
    algorithmVersion: 'expense-allocation-v1'
  }],
  occurredAt: '2026-11-01T00:00:00Z',
  revision: 2
};

const request = {
  description: 'Dinner corrected',
  totalMinorUnits: 120,
  currency: 'INR',
  splitMethod: 'EQUAL' as const,
  payers: [{ memberId: ADMIN, minorUnits: 120 }],
  participants: [ADMIN, MEMBER],
  occurredAt: '2026-11-01T00:00:00Z'
};

test('sends the If-Match revision and the idempotency key', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const result = await correctExpense(
    'https://api.example.test',
    'token',
    GROUP,
    EXPENSE,
    'key-1',
    1,
    request,
    async (url, init) => {
      requested = { url, init };
      return new Response(JSON.stringify({ data: corrected }), { status: 200 });
    }
  );
  assert.equal(result.revision, 2);
  assert.equal(
    requested?.url,
    `https://api.example.test/api/v1/groups/${GROUP}/expenses/${EXPENSE}/corrections`
  );
  const headers = new Headers(requested?.init?.headers);
  assert.equal(headers.get('authorization'), 'Bearer token');
  assert.equal(headers.get('idempotency-key'), 'key-1');
  assert.equal(headers.get('if-match'), '"1"');
});

test('surfaces stale_version so the draft can be kept', async () => {
  await assert.rejects(
    correctExpense('https://api.example.test', 'token', GROUP, EXPENSE, 'key-1', 1, request,
      async () => new Response(JSON.stringify({
        error: { code: 'stale_version', message: 'The group changed. Refresh and retry.' }
      }), { status: 409 })),
    (error: unknown) => error instanceof ExpenseHttpError
      && error.status === 409
      && error.code === 'stale_version'
  );
});
