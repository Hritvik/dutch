package com.dutch.ledger.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Writes the event in the caller's financial transaction. */
    @Transactional
    public UUID publish(String eventType, String aggregateType, String aggregateId,
                        long aggregateVersion, String correlationId, UUID causationId,
                        Instant occurredAt, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event (event_id, event_type, schema_version, producer,
                    aggregate_type, aggregate_id, aggregate_version, correlation_id,
                    causation_id, payload, occurred_at)
                VALUES (?, ?, 1, 'ledger', ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, eventId, eventType, aggregateType, aggregateId, aggregateVersion,
                correlationId, causationId, serialize(payload), Timestamp.from(occurredAt));
        return eventId;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("outbox payload is not serializable", exception);
        }
    }
}
