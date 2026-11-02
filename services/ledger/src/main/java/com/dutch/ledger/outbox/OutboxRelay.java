package com.dutch.ledger.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims a small batch with FOR UPDATE SKIP LOCKED, publishes outside the claim
 * transaction, and records the outcome. A crash after publish is harmless:
 * consumers deduplicate by event id.
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final OutboxTransport transport;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(JdbcTemplate jdbc, ObjectMapper objectMapper,
                       TransactionTemplate transactions, OutboxTransport transport,
                       @Value("${dutch.outbox.batch-size:20}") int batchSize,
                       @Value("${dutch.outbox.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.transport = transport;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${dutch.outbox.relay-delay-ms:2000}")
    public void scheduled() {
        try {
            relayOnce();
        } catch (RuntimeException exception) {
            LOG.warn("outbox relay pass failed", exception);
        }
    }

    public int relayOnce() {
        List<OutboxEvent> claimed = transactions.execute(status -> claim());
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (OutboxEvent event : claimed) {
            try {
                transport.send(event);
                markPublished(event.eventId());
                delivered++;
            } catch (RuntimeException failure) {
                recordFailure(event.eventId(), failure);
            }
        }
        return delivered;
    }

    private List<OutboxEvent> claim() {
        return jdbc.query("""
                UPDATE outbox_event SET claimed_at = now()
                WHERE event_id IN (
                    SELECT event_id FROM outbox_event
                    WHERE published_at IS NULL
                      AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                      AND (claimed_at IS NULL OR claimed_at < now() - interval '5 minutes')
                      AND NOT EXISTS (
                          SELECT 1 FROM outbox_dead_letter d
                          WHERE d.event_id = outbox_event.event_id
                      )
                    ORDER BY occurred_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING event_id, event_type, schema_version, producer, aggregate_type,
                    aggregate_id, aggregate_version, correlation_id, causation_id,
                    occurred_at, payload::text AS payload
                """, (result, index) -> new OutboxEvent(
                result.getObject("event_id", UUID.class),
                result.getString("event_type"),
                result.getInt("schema_version"),
                result.getString("producer"),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getLong("aggregate_version"),
                result.getString("correlation_id"),
                result.getObject("causation_id", UUID.class),
                result.getTimestamp("occurred_at").toInstant(),
                read(result.getString("payload"))), batchSize);
    }

    private void markPublished(UUID eventId) {
        jdbc.update("""
                UPDATE outbox_event SET published_at = now(), claimed_at = NULL, last_error = NULL
                WHERE event_id = ?
                """, eventId);
    }

    private void recordFailure(UUID eventId, RuntimeException failure) {
        String reason = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        Integer attempts = jdbc.queryForObject("""
                UPDATE outbox_event
                SET publish_attempts = publish_attempts + 1,
                    claimed_at = NULL,
                    last_error = ?,
                    next_attempt_at = now() + make_interval(secs =>
                        LEAST(3600, POWER(2, LEAST(publish_attempts + 1, 9))::int * 5))
                WHERE event_id = ?
                RETURNING publish_attempts
                """, Integer.class, reason, eventId);
        if (attempts != null && attempts >= maxAttempts) {
            jdbc.update("""
                    INSERT INTO outbox_dead_letter (event_id, reason) VALUES (?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, reason);
            // Leave published_at NULL so publish-lag alerts still see the event; the
            // claim query excludes dead-lettered rows.
            LOG.error("outbox event {} moved to the dead-letter table", eventId);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("outbox payload is not readable", exception);
        }
    }
}
