import {
  type SyncChange,
  type SyncPage,
  SyncContractError,
  SyncHttpError,
  createSyncSnapshot,
  fetchSyncPage,
  readSyncSnapshot,
  type ApiFetch
} from './sync-client';

export type SyncMode = 'incremental' | 'snapshot' | 'offline';

export type SyncOutcome = {
  mode: SyncMode;
  changes: number;
};

const PAGE_LIMIT = 100;
const CATCH_UP_PAGES = 3;

export type SyncStore = {
  setActiveMember: (memberId: string, identityId?: string) => Promise<void>;
  readCursor: (memberId: string) => Promise<string | null>;
  applyPage: (memberId: string, page: SyncPage) => Promise<void>;
  applySnapshot: (memberId: string, changes: SyncChange[], cursor: string) => Promise<void>;
};

/**
 * One single-flight caller is expected (the app shell). A missing or expired
 * cursor falls back to a recipient-bound snapshot, then catches up.
 */
let inflight: Promise<SyncOutcome> | null = null;

/** Single-flight: concurrent app-shell callers share one sync run. */
export function syncProjection(
  baseUrl: string,
  accessToken: string,
  memberId: string,
  identityId: string | undefined,
  fetchImpl: ApiFetch,
  store: SyncStore
): Promise<SyncOutcome> {
  if (inflight) {
    return inflight;
  }
  inflight = runSync(baseUrl, accessToken, memberId, identityId, fetchImpl, store).finally(() => {
    inflight = null;
  });
  return inflight;
}

async function runSync(
  baseUrl: string,
  accessToken: string,
  memberId: string,
  identityId: string | undefined,
  fetchImpl: ApiFetch,
  store: SyncStore
): Promise<SyncOutcome> {
  await store.setActiveMember(memberId, identityId);
  try {
    const cursor = await store.readCursor(memberId);
    if (!cursor) {
      return await fullSync(baseUrl, accessToken, memberId, fetchImpl, store);
    }
    const page = await fetchSyncPage(baseUrl, accessToken, cursor, PAGE_LIMIT, fetchImpl);
    await store.applyPage(memberId, page);
    return { mode: 'incremental', changes: page.changes.length };
  } catch (failure) {
    if (failure instanceof SyncHttpError && failure.code === 'sync_cursor_expired') {
      return await fullSync(baseUrl, accessToken, memberId, fetchImpl, store);
    }
    if (failure instanceof SyncContractError) {
      throw failure;
    }
    return { mode: 'offline', changes: 0 };
  }
}

async function fullSync(
  baseUrl: string,
  accessToken: string,
  memberId: string,
  fetchImpl: ApiFetch,
  store: SyncStore
): Promise<SyncOutcome> {
  try {
    const snapshot = await createSyncSnapshot(baseUrl, accessToken, fetchImpl);
    const page = await readSyncSnapshot(baseUrl, accessToken, snapshot.token, fetchImpl);
    await store.applySnapshot(memberId, page.changes, page.cursor);
    let changes = page.changes.length;
    let cursor = page.cursor;
    for (let attempt = 0; attempt < CATCH_UP_PAGES; attempt++) {
      const incremental = await fetchSyncPage(baseUrl, accessToken, cursor, PAGE_LIMIT, fetchImpl);
      await store.applyPage(memberId, incremental);
      changes += incremental.changes.length;
      cursor = incremental.nextCursor;
      if (incremental.changes.length < PAGE_LIMIT) {
        break;
      }
    }
    return { mode: 'snapshot', changes };
  } catch (failure) {
    if (failure instanceof SyncContractError) {
      throw failure;
    }
    return { mode: 'offline', changes: 0 };
  }
}
