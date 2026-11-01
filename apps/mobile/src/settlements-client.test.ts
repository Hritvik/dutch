import assert from 'node:assert/strict';
import test from 'node:test';

import {
  SettlementContractError,
  parseSettlementView,
  recordSettlement,
  voidSettlement
} from './settlements-client';

const GROUP = 'grp_000000000000000001';
const PAYER = 'mem_000000000000000001';
const PAYEE = 'mem_000000000000000002';
const settlement = {
  id: 'stl_000000000000000001',
  groupId: GROUP,
  payer: PAYER,
  payee: PAYEE,
  totalMinorUnits: 100,
  currency: 'INR',
  status: 'ACTIVE',
  allocations: [{
    scopeId: `group:${GROUP}:INR`,
    currency: 'INR',
    minorUnits: 100,
    payer: PAYER,
    payee: PAYEE
  }],
  occurredAt: '2026-11-01T00:00:00Z'
};

test('records a settlement and reads its scope', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const result = await recordSettlement(
    'https://api.example.test', 'token', GROUP, 'key-1',
    {
      payerMemberId: PAYER,
      payeeMemberId: PAYEE,
      totalMinorUnits: 100,
      currency: 'INR',
      occurredAt: '2026-11-01T00:00:00Z'
    },
    async (url, init) => {
      requested = { url, init };
      return new Response(JSON.stringify({ data: settlement }), { status: 200 });
    }
  );
  assert.equal(result.status, 'ACTIVE');
  assert.equal(result.allocations[0]?.scopeId, `group:${GROUP}:INR`);
  assert.equal(requested?.url, `https://api.example.test/api/v1/groups/${GROUP}/settlements`);
  const headers = new Headers(requested?.init?.headers);
  assert.equal(headers.get('idempotency-key'), 'key-1');
});

test('voids a settlement and forwards the idempotency key', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const result = await voidSettlement(
    'https://api.example.test', 'token', GROUP, settlement.id, 'void-key',
    async (url, init) => {
      requested = { url, init };
      return new Response(JSON.stringify({ data: { ...settlement, status: 'VOIDED' } }),
        { status: 200 });
    }
  );
  assert.equal(result.status, 'VOIDED');
  assert.equal(
    requested?.url,
    `https://api.example.test/api/v1/groups/${GROUP}/settlements/${settlement.id}/void`
  );
});

test('rejects allocations that do not sum to the settlement total', () => {
  const broken = {
    ...settlement,
    allocations: [{ ...settlement.allocations[0], minorUnits: 60 }]
  };
  assert.throws(() => parseSettlementView(broken), SettlementContractError);
});
