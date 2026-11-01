import assert from 'node:assert/strict';
import test from 'node:test';

import { SessionStore } from './session-store';

test('persists only session material and wipes it deterministically', async () => {
  const values = new Map<string, string>();
  const store = new SessionStore({
    getItem: async (key) => values.get(key) ?? null,
    setItem: async (key, value) => { values.set(key, value); },
    deleteItem: async (key) => { values.delete(key); }
  });

  await store.save({ accessToken: 'access-token', refreshToken: 'refresh-token', identityId: 'identity-id' });
  assert.deepEqual(await store.load(), { accessToken: 'access-token', refreshToken: 'refresh-token', identityId: 'identity-id' });
  await store.wipe();
  assert.equal(await store.load(), null);
});
