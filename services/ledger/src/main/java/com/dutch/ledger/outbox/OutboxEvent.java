package com.dutch.ledger.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One committed outbox fact in the pinned event envelope. */
public record OutboxEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String producer,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        String correlationId,
        UUID causationId,
        Instant occurredAt,
        Map<String, Object> payload) {
}
