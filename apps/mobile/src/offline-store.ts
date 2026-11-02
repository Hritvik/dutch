import { parseGroupView, type GroupView } from './groups-client';
import {
  applySnapshotPage,
  applySyncPage,
  readCursor,
  readResources,
  setActiveMember
} from './projection-db';
import type { SyncStore } from './sync-coordinator';

/** The SQLite-backed store used by the app shell; kept out of the pure coordinator. */
export const sqliteStore: SyncStore = {
  setActiveMember,
  readCursor,
  applyPage: applySyncPage,
  applySnapshot: applySnapshotPage
};

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
