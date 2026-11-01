import assert from 'node:assert/strict';
import test from 'node:test';

import {
  GroupContractError,
  createGroup,
  fetchGroups,
  parseGroupList,
  parseGroupView
} from './groups-client';

const group = {
  id: 'grp_012345678901234567',
  name: 'Flat 12',
  currency: 'INR',
  state: 'ACTIVE',
  version: 1,
  memberships: [
    { memberId: 'mem_012345678901234567', role: 'ADMIN', state: 'ACTIVE' },
    { memberId: 'mem_112345678901234567', role: 'MEMBER', state: 'INACTIVE' }
  ]
};

test('parses a closed group view and list', () => {
  assert.deepEqual(parseGroupView(group), group);
  assert.deepEqual(parseGroupList({ data: [group] }), [group]);
});

test('fails closed on an unknown role, state, id, or currency', () => {
  for (const mutate of [
    (value: any) => { value.memberships[0].role = 'OWNER'; },
    (value: any) => { value.memberships[0].state = 'REMOVED'; },
    (value: any) => { value.id = 'team_012345678901234567'; },
    (value: any) => { value.currency = 'rupee'; },
    (value: any) => { value.version = -1; }
  ]) {
    const copy = JSON.parse(JSON.stringify(group));
    mutate(copy);
    assert.throws(() => parseGroupView(copy), GroupContractError);
  }
});

test('requests groups with the bearer token', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const groups = await fetchGroups('https://api.example.test/', 'token', async (url, init) => {
    requested = { url, init };
    return new Response(JSON.stringify({ data: [group] }), { status: 200 });
  });
  assert.equal(groups.length, 1);
  assert.equal(requested?.url, 'https://api.example.test/api/v1/groups');
  assert.equal(new Headers(requested?.init?.headers).get('authorization'), 'Bearer token');
});

test('creates a group with a JSON body and parses the answer', async () => {
  let body: string | undefined;
  const created = await createGroup('https://api.example.test', 'token', {
    name: 'Flat 12',
    currency: 'INR'
  }, async (_url, init) => {
    body = init?.body as string;
    return new Response(JSON.stringify({ data: group }), { status: 200 });
  });
  assert.equal(created.id, group.id);
  assert.deepEqual(JSON.parse(body ?? '{}'), { name: 'Flat 12', currency: 'INR' });
});

test('rejects a failed group request', async () => {
  await assert.rejects(
    fetchGroups('https://api.example.test', 'token', async () => new Response('', { status: 403 })),
    GroupContractError
  );
});
