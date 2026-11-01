import type { SyncChange, SyncPage } from './sync-client';

/**
 * The durable mobile projection cache. A page is applied atomically: resource
 * versions decide every upsert, a tombstone wins at its version, and the cursor
 * only advances with the page that produced it.
 */
export type CachedResource = {
  type: string;
  id: string;
  version: number;
  payload: Record<string, unknown>;
  updatedAt: string;
};

export type ProjectionCache = {
  resources: Record<string, CachedResource>;
  tombstones: Record<string, number>;
  cursor: string | null;
};

export class FullResyncRequired extends Error {
  constructor() {
    super('The sync generation changed; request a full snapshot.');
    this.name = 'FullResyncRequired';
  }
}

export function emptyCache(): ProjectionCache {
  return { resources: {}, tombstones: {}, cursor: null };
}

export function resourceKey(type: string, id: string): string {
  return `${type}:${id}`;
}

export function applyPage(cache: ProjectionCache, page: SyncPage): ProjectionCache {
  const resources = { ...cache.resources };
  const tombstones = { ...cache.tombstones };
  for (const change of page.changes) {
    applyChange(resources, tombstones, change);
  }
  return { resources, tombstones, cursor: page.nextCursor };
}

export function applySnapshot(
  cache: ProjectionCache,
  changes: SyncChange[],
  cursor: string
): ProjectionCache {
  const resources = { ...cache.resources };
  const tombstones = { ...cache.tombstones };
  for (const change of changes) {
    applyChange(resources, tombstones, change);
  }
  return { resources, tombstones, cursor };
}

function applyChange(
  resources: Record<string, CachedResource>,
  tombstones: Record<string, number>,
  change: SyncChange
): void {
  if (change.operation === 'full_resync_required') {
    throw new FullResyncRequired();
  }
  const key = resourceKey(change.resourceType, change.resourceId);
  const tombstone = tombstones[key];
  if (change.operation === 'tombstone') {
    if (tombstone === undefined || change.resourceVersion >= tombstone) {
      tombstones[key] = change.resourceVersion;
      delete resources[key];
    }
    return;
  }
  // A stale upsert never resurrects a tombstoned resource.
  if (tombstone !== undefined && change.resourceVersion <= tombstone) {
    return;
  }
  const current = resources[key];
  if (current !== undefined && change.resourceVersion <= current.version) {
    return;
  }
  resources[key] = {
    type: change.resourceType,
    id: change.resourceId,
    version: change.resourceVersion,
    payload: change.payload,
    updatedAt: change.occurredAt
  };
}

export function listResources(cache: ProjectionCache, type: string): CachedResource[] {
  return Object.values(cache.resources)
    .filter((resource) => resource.type === type)
    .sort((left, right) => left.id.localeCompare(right.id));
}

export function readResource(
  cache: ProjectionCache,
  type: string,
  id: string
): CachedResource | undefined {
  return cache.resources[resourceKey(type, id)];
}
