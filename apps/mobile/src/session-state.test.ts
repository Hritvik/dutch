import assert from 'node:assert/strict';
import test from 'node:test';

import { initialSessionState, reduceSessionState } from './session-state';

test('keeps Ledger unavailable while IAM is authenticated but UMS profile creation is pending', () => {
  const state = reduceSessionState(initialSessionState, {
    type: 'iamAuthenticated',
    identityId: '2c93a1e2-b947-47f4-861f-5a0d7f03e0a7'
  });

  assert.deepEqual(state, {
    kind: 'profileRequired',
    identityId: '2c93a1e2-b947-47f4-861f-5a0d7f03e0a7',
    ledgerAvailable: false
  });
});

test('reaches the authenticated shell only after a matching Member profile becomes available', () => {
  const profileRequired = reduceSessionState(initialSessionState, {
    type: 'iamAuthenticated',
    identityId: '2c93a1e2-b947-47f4-861f-5a0d7f03e0a7'
  });

  const state = reduceSessionState(profileRequired, {
    type: 'memberProfileReady',
    identityId: '2c93a1e2-b947-47f4-861f-5a0d7f03e0a7',
    memberId: '6055eddf-dfbf-4fbb-a4d9-aa315f4261e5'
  });

  assert.equal(state.kind, 'authenticated');
  assert.equal(state.ledgerAvailable, true);
});

test('refresh reuse or logout wipes the active account before reauthentication', () => {
  const profileRequired = reduceSessionState(initialSessionState, {
    type: 'iamAuthenticated',
    identityId: '2c93a1e2-b947-47f4-861f-5a0d7f03e0a7'
  });

  const state = reduceSessionState(profileRequired, { type: 'refreshFamilyRevoked' });

  assert.deepEqual(state, initialSessionState);
});
