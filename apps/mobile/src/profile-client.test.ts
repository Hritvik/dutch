import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ProfileContractError,
  ProfileHttpError,
  fetchMemberProfile,
  fetchProfileState,
  parseMemberProfile,
  parseProfileState
} from './profile-client';

test('parses the required, provisioning, and ready profile states', () => {
  for (const state of ['PROFILE_REQUIRED', 'PROFILE_PROVISIONING', 'PROFILE_READY'] as const) {
    assert.deepEqual(parseProfileState({ data: { state } }), { state });
  }
});

test('keeps a positive bounded retry hint and rejects any other value', () => {
  assert.deepEqual(parseProfileState({ data: { state: 'PROFILE_REQUIRED', retryAfterSeconds: 5 } }), {
    state: 'PROFILE_REQUIRED',
    retryAfterSeconds: 5
  });
  for (const retryAfterSeconds of [0, -1, 1.5, '5', 3601]) {
    assert.throws(
      () => parseProfileState({ data: { state: 'PROFILE_REQUIRED', retryAfterSeconds } }),
      ProfileContractError
    );
  }
  assert.deepEqual(parseProfileState({ data: { state: 'PROFILE_REQUIRED', retryAfterSeconds: 3600 } }), {
    state: 'PROFILE_REQUIRED',
    retryAfterSeconds: 3600
  });
});

test('fails closed on an unknown or missing profile state', () => {
  assert.throws(() => parseProfileState({ data: { state: 'PROFILE_ACCEPTED' } }), ProfileContractError);
  assert.throws(() => parseProfileState({ data: {} }), ProfileContractError);
  assert.throws(() => parseProfileState({}), ProfileContractError);
});

test('parses only the closed member profile fields', () => {
  assert.deepEqual(
    parseMemberProfile({
      data: {
        memberId: 'mem_012345678901234567',
        displayName: null,
        profileState: 'PROFILE_READY',
        lifecycleState: 'ACTIVE'
      }
    }),
    {
      memberId: 'mem_012345678901234567',
      displayName: null,
      profileState: 'PROFILE_READY',
      lifecycleState: 'ACTIVE'
    }
  );
  assert.throws(
    () => parseMemberProfile({ data: { memberId: '', displayName: null, profileState: 'PROFILE_READY', lifecycleState: 'ACTIVE' } }),
    ProfileContractError
  );
  assert.throws(
    () => parseMemberProfile({ data: { memberId: 'mem_012345678901234567', displayName: null, profileState: 'PROFILE_PROVISIONING', lifecycleState: 'ACTIVE' } }),
    ProfileContractError
  );
  assert.throws(
    () => parseMemberProfile({ data: { memberId: 'mem_012345678901234567', displayName: null, profileState: 'PROFILE_READY', lifecycleState: 'SUSPENDED' } }),
    ProfileContractError
  );
  assert.throws(
    () => parseMemberProfile({ data: { memberId: 'usr_012345678901234567', displayName: null, profileState: 'PROFILE_READY', lifecycleState: 'ACTIVE' } }),
    ProfileContractError
  );
});

test('requests profile state with the bearer token and parses the answer', async () => {
  let requested: { url: string; init?: RequestInit } | undefined;
  const result = await fetchProfileState('https://api.example.test/', 'token', async (url, init) => {
    requested = { url, init };
    return new Response(JSON.stringify({ data: { state: 'PROFILE_PROVISIONING' } }), { status: 200 });
  });
  assert.deepEqual(result, { state: 'PROFILE_PROVISIONING' });
  assert.equal(requested?.url, 'https://api.example.test/api/v1/me/profile-state');
  assert.equal(new Headers(requested?.init?.headers).get('authorization'), 'Bearer token');
});

test('treats a 409 member-profile response as profile-required rather than an error', async () => {
  const profile = await fetchMemberProfile('https://api.example.test', 'token', async () =>
    new Response(JSON.stringify({ error: { code: 'profile_required' } }), { status: 409 })
  );
  assert.equal(profile, null);
});

test('surfaces an authentication failure as an HTTP error', async () => {
  await assert.rejects(
    fetchProfileState('https://api.example.test', 'token', async () => new Response('', { status: 401 })),
    (error: unknown) => error instanceof ProfileHttpError && error.status === 401
  );
});

test('surfaces an unavailable service as an HTTP error', async () => {
  await assert.rejects(
    fetchProfileState('https://api.example.test', 'token', async () => new Response('', { status: 503 })),
    (error: unknown) => error instanceof ProfileHttpError && error.status === 503
  );
});
