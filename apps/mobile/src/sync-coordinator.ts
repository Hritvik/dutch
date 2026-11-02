import { parseGroupView, type GroupView } from './groups-client';
import {
  applySnapshotPage,
  applySyncPage,
  readCursor,
  readResources,
  setActiveMember
} from './projection-db';
import {
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

/**
 * One single-flight caller is expected (the app shell). A missing or expired
 * cursor falls back to a recipient-bound snapshot, then catches up.
 */
export async function syncProjection(
  baseUrl: string,
  accessToken: string,
  memberId: string,
  fetchImpl: ApiFetch = fetch
): Promise<SyncOutcome> {
  await setActiveMember(memberId);
  try {
    const cursor = await readCursor(memberId);
    if (!cursor) {
      return await fullSync(baseUrl, accessToken, memberId, fetchImpl);
    }
    const page = await fetchSyncPage(baseUrl, accessToken, cursor, PAGE_LIMIT, fetchImpl);
    await applySyncPage(memberId, page);
    return { mode: 'incremental', changes: page.changes.length };
  } catch (failure) {
    if (failure instanceof SyncHttpError && failure.code === 'sync_cursor_expired') {
      return await fullSync(baseUrl, accessToken, memberId, fetchImpl);
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
  fetchImpl: ApiFetch
): Promise<SyncOutcome> {
  try {
    const snapshot = await createSyncSnapshot(baseUrl, accessToken, fetchImpl);
    const page = await readSyncSnapshot(baseUrl, accessToken, snapshot.token, fetchImpl);
    await applySnapshotPage(memberId, page.changes, page.cursor);
    let changes = page.changes.length;
    let cursor = page.cursor;
    for (let attempt = 0; attempt < CATCH_UP_PAGES; attempt++) {
      const incremental = await fetchSyncPage(baseUrl, accessToken, cursor, PAGE_LIMIT, fetchImpl);
      await applySyncPage(memberId, incremental);
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

/** Authorized Group projections from the durable cache, newest server state only. */
export async function cachedGroups(memberId: string): Promise<GroupView[]> {
  const rows = await readResources(memberId, 'GROUP');
  const groups: GroupView[] = [];
  for (const row of rows) {
    try {
      groups.push(parseGroupView(row.payload));
    } catch {
      // A malformed cached row must never be shown as authoritative state.
    }
  }
  return groups.sort((left, right) => left.name.localeCompare(right.name));
}
