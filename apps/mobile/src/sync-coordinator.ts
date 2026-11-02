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
    const collected: SyncChange[] = [];
    let cursor = '';
    let snapshotCursor: number | null = null;
    let complete = false;
    for (let page = 0; page < 20; page++) {
      const snapshotPage = await readSyncSnapshot(baseUrl, accessToken, snapshot.token,
        fetchImpl, snapshotCursor, 200);
      collected.push(...snapshotPage.changes);
      cursor = snapshotPage.cursor;
      if (!snapshotPage.truncated) {
        complete = true;
        break;
      }
      if (snapshotPage.nextSnapshotCursor === null) {
        throw new SyncContractError('A truncated snapshot page has no continuation cursor.');
      }
      snapshotCursor = snapshotPage.nextSnapshotCursor;
    }
    if (!complete) {
      throw new SyncContractError('The snapshot did not complete within the page budget.');
    }
    await store.applySnapshot(memberId, collected, cursor);
    let changes = collected.length;
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
