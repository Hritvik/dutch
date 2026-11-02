import assert from 'node:assert/strict';
import test from 'node:test';

import {
  NotificationContractError,
  NotificationHttpError,
  deleteNotificationData,
  fetchPreferences,
  listDevices,
  parsePreferences,
  registerDevice,
  updatePreferences
} from './notifications-client';

const device = {
  deviceId: 'dev_000000000000000001',
  platform: 'ANDROID',
  active: true
};

test('parses devices and preferences', () => {
  assert.deepEqual(parsePreferences({ activityPush: true, settlementPush: false }),
    { activityPush: true, settlementPush: false });
  assert.throws(() => parsePreferences({ activityPush: 'yes' }), NotificationContractError);
});

test('registers, lists, updates, and deletes notification data', async () => {
  const registered = await registerDevice('https://api.test', 'token', {
    deviceId: device.deviceId,
    platform: 'ANDROID',
    pushToken: 'push-token-123456'
  }, async (url, init) => {
    assert.equal(url, 'https://api.test/api/v1/notifications/devices');
    assert.equal(new Headers(init?.headers).get('authorization'), 'Bearer token');
    return new Response(JSON.stringify({ data: device }), { status: 200 });
  });
  assert.equal(registered.deviceId, device.deviceId);

  const devices = await listDevices('https://api.test', 'token',
    async () => new Response(JSON.stringify({ data: [device] }), { status: 200 }));
  assert.equal(devices.length, 1);

  const updated = await updatePreferences('https://api.test', 'token', { settlementPush: false },
    async (url, init) => {
      assert.equal(url, 'https://api.test/api/v1/notifications/preferences');
      assert.match(String(init?.body), /settlementPush/);
      return new Response(JSON.stringify({
        data: { activityPush: true, settlementPush: false }
      }), { status: 200 });
    });
  assert.equal(updated.settlementPush, false);

  await deleteNotificationData('https://api.test', 'token',
    async () => new Response(null, { status: 204 }));
});

test('surfaces notification HTTP errors', async () => {
  await assert.rejects(
    fetchPreferences('https://api.test', 'token',
      async () => new Response(JSON.stringify({
        error: { code: 'unauthorized', message: 'auth' }
      }), { status: 401 })),
    (error: unknown) => error instanceof NotificationHttpError
      && error.status === 401 && error.code === 'unauthorized'
  );
});
