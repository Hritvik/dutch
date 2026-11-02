export type Device = {
  deviceId: string;
  platform: 'ANDROID' | 'IOS';
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
};

export type Preferences = {
  activityPush: boolean;
  settlementPush: boolean;
};

export class NotificationHttpError extends Error {
  constructor(readonly status: number, message: string, readonly code: string = 'error') {
    super(message);
    this.name = 'NotificationHttpError';
  }
}

export class NotificationContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'NotificationContractError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const DEVICE_ID = /^dev_[A-Za-z0-9_-]{16,128}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseDevice(value: unknown): Device {
  if (!isRecord(value)
      || typeof value.deviceId !== 'string' || !DEVICE_ID.test(value.deviceId)
      || (value.platform !== 'ANDROID' && value.platform !== 'IOS')
      || typeof value.active !== 'boolean') {
    throw new NotificationContractError('Device shape is invalid.');
  }
  return {
    deviceId: value.deviceId,
    platform: value.platform,
    active: value.active,
    ...(typeof value.createdAt === 'string' ? { createdAt: value.createdAt } : {}),
    ...(typeof value.updatedAt === 'string' ? { updatedAt: value.updatedAt } : {})
  };
}

export function parsePreferences(value: unknown): Preferences {
  if (!isRecord(value)
      || typeof value.activityPush !== 'boolean'
      || typeof value.settlementPush !== 'boolean') {
    throw new NotificationContractError('Preference shape is invalid.');
  }
  return { activityPush: value.activityPush, settlementPush: value.settlementPush };
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

async function toError(response: Response): Promise<NotificationHttpError> {
  let code = 'error';
  try {
    const payload = await response.json();
    if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
      code = payload.error.code;
    }
  } catch {
    // Keep the generic code when the body is not JSON.
  }
  return new NotificationHttpError(
    response.status, `Notification request failed with ${response.status}.`, code);
}

async function readData(response: Response): Promise<unknown> {
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new NotificationContractError('Notification response is missing data.');
  }
  return payload.data;
}

export async function registerDevice(
  baseUrl: string,
  accessToken: string,
  device: { deviceId: string; platform: 'ANDROID' | 'IOS'; pushToken: string },
  fetchImpl: ApiFetch = fetch
): Promise<Device> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/notifications/devices'), {
    method: 'POST',
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify(device)
  });
  return parseDevice(await readData(response));
}

export async function listDevices(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<Device[]> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/notifications/devices'), {
    method: 'GET',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  const data = await readData(response);
  if (!Array.isArray(data)) {
    throw new NotificationContractError('Device list is invalid.');
  }
  return data.map(parseDevice);
}

export async function fetchPreferences(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<Preferences> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/notifications/preferences'), {
    method: 'GET',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  return parsePreferences(await readData(response));
}

export async function updatePreferences(
  baseUrl: string,
  accessToken: string,
  change: Partial<Preferences>,
  fetchImpl: ApiFetch = fetch
): Promise<Preferences> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/notifications/preferences'), {
    method: 'PUT',
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify(change)
  });
  return parsePreferences(await readData(response));
}

export async function deleteNotificationData(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<void> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/notifications/privacy'), {
    method: 'DELETE',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  if (!response.ok) {
    throw await toError(response);
  }
}
