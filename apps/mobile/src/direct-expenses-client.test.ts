import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createDirectExpense,
  fetchDirectExpense,
  parseDirectExpenseView
} from './direct-expenses-client';
import { ExpenseContractError } from './expenses-client';

const A = 'mem_000000000000000001';
const B = 'mem_000000000000000002';
const view = {
  id: 'exp_000000000000000001',
  description: 'Dinner',
  totalMinorUnits: 100,
  currency: 'INR',
  splitMethod: 'EQUAL',
  payers: [{ memberId: A, minorUnits: 100 }],
  shares: [{ memberId: A, minorUnits: 50 }, { memberId: B, minorUnits: 50 }],
  entries: [{
    expenseId: 'exp_000000000000000001',
    revision: 1,
    debtor: B,
    creditor: A,
    minorUnits: 50,
    currency: 'INR',
    algorithmVersion: 'expense-allocation-v1'
  }],
  streamIds: [`direct:${A}:${B}:INR`],
  occurredAt: '2026-11-02T00:00:00Z',
  revision: 1
};

test('parses a closed Direct Expense with its pair stream', () => {
  assert.deepEqual(parseDirectExpenseView(view), view);
  assert.throws(() => parseDirectExpenseView({ ...view, streamIds: ['group:grp:INR'] }),
    ExpenseContractError);
  assert.throws(() => parseDirectExpenseView({
    ...view,
    shares: [{ memberId: A, minorUnits: 40 }, { memberId: B, minorUnits: 50 }]
  }), ExpenseContractError);
});

test('creates and reads a Direct Expense with the command key', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const created = await createDirectExpense('https://api.example.test', 'token', 'key-1', {
    description: 'Dinner',
    totalMinorUnits: 100,
    currency: 'INR',
    splitMethod: 'EQUAL',
    payers: [{ memberId: A, minorUnits: 100 }],
    participants: [A, B],
    occurredAt: '2026-11-02T00:00:00Z'
  }, async (url, init) => {
    requested = { url, init };
    return new Response(JSON.stringify({ data: view }), { status: 200 });
  });
  assert.equal(created.id, view.id);
  assert.equal(requested?.url, 'https://api.example.test/api/v1/direct-expenses');
  assert.equal(new Headers(requested?.init?.headers).get('idempotency-key'), 'key-1');

  const read = await fetchDirectExpense('https://api.example.test', 'token', view.id,
    async (url) => {
      assert.match(String(url), /direct-expenses\/exp_/);
      return new Response(JSON.stringify({ data: view }), { status: 200 });
    });
  assert.equal(read.revision, 1);
});
