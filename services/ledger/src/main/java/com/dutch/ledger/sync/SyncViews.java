package com.dutch.ledger.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SyncViews {

    private SyncViews() {
    }

    public record Change(long sequence, String resourceType, String resourceId, String operation,
                         long resourceVersion, Map<String, Object> payload, String actionId,
                         String schemaVersion, Instant occurredAt) {
        public static Change from(SyncChange change) {
            return new Change(change.sequence(), change.resourceType(), change.resourceId(),
                    change.operation(), change.resourceVersion(), change.payload(),
                    change.actionId(), change.schemaVersion(), change.occurredAt());
        }
    }

    public record Page(List<Change> changes, String nextCursor) {
    }

    public record Snapshot(String token, long asOfSequence) {
    }

    public record SnapshotPage(List<Change> changes, String cursor, long asOfSequence,
                               boolean truncated, Long nextSnapshotCursor) {
    }
}
