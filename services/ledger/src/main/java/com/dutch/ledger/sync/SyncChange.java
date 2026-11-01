package com.dutch.ledger.sync;

import java.time.Instant;
import java.util.Map;

/** One immutable recipient-scoped journal row. */
public record SyncChange(
        long sequence,
        String resourceType,
        String resourceId,
        String operation,
        long resourceVersion,
        Map<String, Object> payload,
        String actionId,
        String schemaVersion,
        Instant occurredAt) {
}
