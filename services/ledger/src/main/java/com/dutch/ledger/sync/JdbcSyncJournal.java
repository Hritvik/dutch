package com.dutch.ledger.sync;

import com.dutch.ledger.group.MemberId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSyncJournal implements SyncJournal {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSyncJournal(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void append(Collection<MemberId> recipients, String resourceType, String resourceId,
                       String operation, long resourceVersion, Map<String, Object> payload,
                       String actionId, Instant occurredAt) {
        String json = serialize(payload);
        for (MemberId recipient : recipients) {
            Long sequence = jdbc.queryForObject("""
                    INSERT INTO member_sync_sequence (member_id, next_sequence) VALUES (?, 2)
                    ON CONFLICT (member_id) DO UPDATE SET
                        next_sequence = member_sync_sequence.next_sequence + 1
                    RETURNING next_sequence - 1
                    """, Long.class, recipient.value());
            if (sequence == null) {
                throw new IllegalStateException("the sync sequence was not allocated");
            }
            jdbc.update("""
                    INSERT INTO member_sync_change (member_id, sequence, resource_type,
                        resource_id, operation, resource_version, payload, action_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """, recipient.value(), sequence, resourceType, resourceId, operation,
                    resourceVersion, json, actionId, Timestamp.from(occurredAt));
        }
    }

    @Override
    public List<SyncChange> after(MemberId recipient, long sequence, int limit) {
        return jdbc.query("""
                SELECT sequence, resource_type, resource_id, operation, resource_version,
                    payload::text AS payload, action_id, schema_version, occurred_at
                FROM member_sync_change
                WHERE member_id = ? AND sequence > ?
                ORDER BY sequence
                LIMIT ?
                """, (result, index) -> change(result), recipient.value(), sequence, limit);
    }

    @Override
    public long maxSequence(MemberId recipient) {
        Long max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) FROM member_sync_change WHERE member_id = ?
                """, Long.class, recipient.value());
        return max == null ? 0 : max;
    }

    @Override
    public List<SyncChange> snapshot(MemberId recipient, long asOfSequence, int limit) {
        return jdbc.query("""
                SELECT sequence, resource_type, resource_id, operation, resource_version,
                    payload::text AS payload, action_id, schema_version, occurred_at
                FROM (
                    SELECT DISTINCT ON (resource_type, resource_id)
                        sequence, resource_type, resource_id, operation, resource_version,
                        payload, action_id, schema_version, occurred_at
                    FROM member_sync_change
                    WHERE member_id = ? AND sequence <= ?
                    ORDER BY resource_type, resource_id, sequence DESC
                ) latest
                WHERE operation <> 'tombstone'
                ORDER BY sequence
                LIMIT ?
                """, (result, index) -> change(result), recipient.value(), asOfSequence, limit);
    }

    private SyncChange change(java.sql.ResultSet result) throws java.sql.SQLException {
        return new SyncChange(
                result.getLong("sequence"),
                result.getString("resource_type"),
                result.getString("resource_id"),
                result.getString("operation"),
                result.getLong("resource_version"),
                read(result.getString("payload")),
                result.getString("action_id"),
                result.getString("schema_version"),
                result.getTimestamp("occurred_at").toInstant());
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("sync payload is not serializable", exception);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("sync payload is not readable", exception);
        }
    }
}
