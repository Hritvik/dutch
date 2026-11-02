export type SyncOperation = 'upsert' | 'tombstone' | 'access_changed' | 'full_resync_required';

export type SyncChange = {
  sequence: number;
  resourceType: string;
  resourceId: string;
  operation: SyncOperation;
  resourceVersion: number;
  payload: Record<string, unknown>;
  actionId?: string;
  schemaVersion: string;
  occurredAt: string;
};

export type SyncPage = {
  changes: SyncChange[];
  nextCursor: string;
};

export type SyncSnapshot = {
  token: string;
  asOfSequence: number;
};

export type SyncSnapshotPage = {
  changes: SyncChange[];
  cursor: string;
  asOfSequence: number;
  truncated: boolean;
  nextSnapshotCursor: number | null;
};

export class SyncContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SyncContractError';
  }
}

export class SyncHttpError extends Error {
  constructor(readonly status: number, message: string, readonly code: string = 'error') {
    super(message);
    this.name = 'SyncHttpError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const OPERATIONS: SyncOperation[] = ['upsert', 'tombstone', 'access_changed', 'full_resync_required'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function parseSyncChange(value: unknown): SyncChange {
  if (!isRecord(value)
      || typeof value.sequence !== 'number' || !Number.isInteger(value.sequence) || value.sequence < 1
      || typeof value.resourceType !== 'string' || value.resourceType.length === 0
      || typeof value.resourceId !== 'string' || value.resourceId.length === 0
      || !OPERATIONS.includes(value.operation as SyncOperation)
      || typeof value.resourceVersion !== 'number' || !Number.isInteger(value.resourceVersion)
      || value.resourceVersion < 0
      || !isRecord(value.payload)
      || (value.actionId !== undefined && value.actionId !== null
        && typeof value.actionId !== 'string')
      || typeof value.schemaVersion !== 'string'
      || typeof value.occurredAt !== 'string') {
    throw new SyncContractError('Sync change shape is invalid.');
  }
  return {
    sequence: value.sequence,
    resourceType: value.resourceType,
    resourceId: value.resourceId,
    operation: value.operation as SyncOperation,
    resourceVersion: value.resourceVersion,
    payload: value.payload,
    ...(typeof value.actionId === 'string' ? { actionId: value.actionId } : {}),
    schemaVersion: value.schemaVersion,
    occurredAt: value.occurredAt
  };
}

function parsePageData(value: unknown): Record<string, unknown> {
  if (!isRecord(value) || !isRecord(value.data)) {
    throw new SyncContractError('Sync response is missing data.');
  }
  return value.data;
}

function parseChanges(value: unknown): SyncChange[] {
  if (!Array.isArray(value)) {
    throw new SyncContractError('Sync changes must be an array.');
  }
  return value.map(parseSyncChange);
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

async function error(response: Response): Promise<SyncHttpError> {
  let code = 'error';
  try {
    const payload = await response.json();
    if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
      code = payload.error.code;
    }
  } catch {
    // Keep the generic code when the body is not JSON.
  }
  return new SyncHttpError(response.status, `Sync request failed with ${response.status}.`, code);
}

export async function fetchSyncPage(
  baseUrl: string,
  accessToken: string,
  cursor: string | null,
  limit: number,
  fetchImpl: ApiFetch = fetch
): Promise<SyncPage> {
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) {
    throw new SyncContractError('The sync limit must be between 1 and 200.');
  }
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor) query.set('cursor', cursor);
  const response = await fetchImpl(
    apiUrl(baseUrl, `/api/v1/ledger/sync?${query.toString()}`),
    { method: 'GET', headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` } }
  );
  if (!response.ok) {
    throw await error(response);
  }
  const data = parsePageData(await response.json());
  if (typeof data.nextCursor !== 'string') {
    throw new SyncContractError('Sync page is missing its cursor.');
  }
  return { changes: parseChanges(data.changes), nextCursor: data.nextCursor };
}

export async function createSyncSnapshot(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<SyncSnapshot> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/ledger/sync-snapshots'), {
    method: 'POST',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  if (!response.ok) {
    throw await error(response);
  }
  const data = parsePageData(await response.json());
  if (typeof data.token !== 'string'
      || typeof data.asOfSequence !== 'number' || !Number.isInteger(data.asOfSequence)) {
    throw new SyncContractError('Sync snapshot shape is invalid.');
  }
  return { token: data.token, asOfSequence: data.asOfSequence };
}

export async function readSyncSnapshot(
  baseUrl: string,
  accessToken: string,
  token: string,
  fetchImpl: ApiFetch = fetch,
  cursor?: number | null,
  limit = 200
): Promise<SyncSnapshotPage> {
  if (!Number.isInteger(limit) || limit < 1 || limit > 500) {
    throw new SyncContractError('The snapshot limit must be between 1 and 500.');
  }
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor !== undefined && cursor !== null) query.set('cursor', String(cursor));
  const response = await fetchImpl(
    apiUrl(baseUrl,
      `/api/v1/ledger/sync-snapshots/${encodeURIComponent(token)}?${query.toString()}`),
    { method: 'GET', headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` } }
  );
  if (!response.ok) {
    throw await error(response);
  }
  const data = parsePageData(await response.json());
  if (typeof data.cursor !== 'string'
      || typeof data.asOfSequence !== 'number' || !Number.isInteger(data.asOfSequence)
      || typeof data.truncated !== 'boolean'
      || (data.nextSnapshotCursor !== undefined && data.nextSnapshotCursor !== null
        && (typeof data.nextSnapshotCursor !== 'number'
          || !Number.isInteger(data.nextSnapshotCursor)))) {
    throw new SyncContractError('Sync snapshot page shape is invalid.');
  }
  return {
    changes: parseChanges(data.changes),
    cursor: data.cursor,
    asOfSequence: data.asOfSequence,
    truncated: data.truncated,
    nextSnapshotCursor: data.nextSnapshotCursor === undefined || data.nextSnapshotCursor === null
      ? null : data.nextSnapshotCursor
  };
}
