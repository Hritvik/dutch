import assert from 'node:assert/strict';
import test from 'node:test';

import { screenFor } from './app-screen';
import type { SessionState } from './session-state';

const profileRequired: SessionState = { kind: 'profileRequired', identityId: 'aps_1', ledgerAvailable: false };
const authenticated: SessionState = { kind: 'authenticated', identityId: 'aps_1', memberId: 'mem_1', ledgerAvailable: true };

test('never grants the shell while booting or signed out', () => {
  assert.equal(screenFor({ session: { kind: 'booting', ledgerAvailable: false }, online: true }), 'loading');
  assert.equal(screenFor({ session: { kind: 'signedOut', ledgerAvailable: false }, online: true }), 'signedOut');
});

test('keeps the profile-required shell until the authoritative state is ready', () => {
  assert.equal(screenFor({ session: profileRequired, online: true }), 'profileRequired');
  assert.equal(
    screenFor({ session: profileRequired, online: true, profile: { state: 'PROFILE_REQUIRED' } }),
    'profileRequired'
  );
  assert.equal(
    screenFor({ session: profileRequired, online: true, profile: { state: 'PROFILE_PROVISIONING', retryAfterSeconds: 5 } }),
    'profileRequired'
  );
});

test('resolves the member profile only once the state is ready', () => {
  assert.equal(
    screenFor({ session: profileRequired, online: true, profile: { state: 'PROFILE_READY' } }),
    'profiling'
  );
});

test('shows an honest offline state instead of a stale ready shell', () => {
  assert.equal(
    screenFor({ session: profileRequired, online: false, profile: { state: 'PROFILE_READY' } }),
    'offline'
  );
});

test('an authenticated session reaches the home shell', () => {
  assert.equal(screenFor({ session: authenticated, online: true }), 'home');
  assert.equal(screenFor({ session: authenticated, online: false }), 'home');
});
