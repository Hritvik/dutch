import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ExpenseContractError,
  createExpense,
  fetchExpense,
  formatMinor,
  parseExpenseView,
  parseMajorToMinor
} from './expenses-client';

const ADMIN = 'mem_000000000000000001';
const MEMBER = 'mem_000000000000000002';
const expense = {
  id: 'exp_000000000000000001',
  groupId: 'grp_000000000000000001',
  description: 'Dinner',
  totalMinorUnits: 100,
  currency: 'INR',
  splitMethod: 'EQUAL',
  payers: [{ memberId: ADMIN, minorUnits: 100 }],
  shares: [{ memberId: ADMIN, minorUnits: 50 }, { memberId: MEMBER, minorUnits: 50 }],
  entries: [{
    expenseId: 'exp_000000000000000001',
    revision: 1,
    debtor: MEMBER,
    creditor: ADMIN,
    minorUnits: 50,
    currency: 'INR',
    algorithmVersion: 'expense-allocation-v1'
  }],
  occurredAt: '2026-11-01T00:00:00Z',
  revision: 1
};

test('parses a closed expense result', () => {
  assert.deepEqual(parseExpenseView(expense), expense);
});

test('rejects shares that do not sum to the total', () => {
  const broken = JSON.parse(JSON.stringify(expense));
  broken.shares[1].minorUnits = 40;
  assert.throws(() => parseExpenseView(broken), ExpenseContractError);
});

test('parses and formats major-unit input', () => {
  assert.equal(parseMajorToMinor('12.50'), 1250);
  assert.equal(parseMajorToMinor('12.5'), 1250);
  assert.equal(parseMajorToMinor('12'), 1200);
  assert.throws(() => parseMajorToMinor('1.234'), ExpenseContractError);
  assert.throws(() => parseMajorToMinor('0'), ExpenseContractError);
  assert.equal(formatMinor(1250, 'INR'), '12.50 INR');
});

test('reads the latest expense revision for a member', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const read = await fetchExpense('https://api.example.test', 'token', expense.groupId,
    expense.id, async (url, init) => {
      requested = { url, init };
      return new Response(JSON.stringify({ data: expense }), { status: 200 });
    });
  assert.equal(read.revision, 1);
  assert.equal(requested?.url,
    `https://api.example.test/api/v1/groups/${expense.groupId}/expenses/${expense.id}`);
  const headers = new Headers(requested?.init?.headers);
  assert.equal(headers.get('authorization'), 'Bearer token');
});

test('creates an expense with the idempotency key and bearer token', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const created = await createExpense('https://api.example.test', 'token', expense.groupId,
    'key-1', {
      description: 'Dinner',
      totalMinorUnits: 100,
      currency: 'INR',
      splitMethod: 'EQUAL',
      payers: [{ memberId: ADMIN, minorUnits: 100 }],
      participants: [ADMIN, MEMBER],
      occurredAt: '2026-11-01T00:00:00Z'
    }, async (url, init) => {
      requested = { url, init };
      return new Response(JSON.stringify({ data: expense }), { status: 200 });
    });

  assert.equal(created.id, expense.id);
  assert.equal(requested?.url, `https://api.example.test/api/v1/groups/${expense.groupId}/expenses`);
  const headers = new Headers(requested?.init?.headers);
  assert.equal(headers.get('authorization'), 'Bearer token');
  assert.equal(headers.get('idempotency-key'), 'key-1');
  assert.match(String(requested?.init?.body), /"participants"/);
});
