package com.dutch.ledger.activity;

import com.dutch.ledger.group.GroupRuleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcActivityRepository implements ActivityRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcActivityRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void append(ActivityEntry entry) {
        try {
            jdbc.update("""
                    INSERT INTO activity_entry (activity_id, scope_id, actor_member_id, action,
                        target_id, target_revision, occurred_at, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, entry.activityId(), entry.scopeId(),
                    entry.actor() == null ? null : entry.actor().value(),
                    entry.action().name(), entry.targetId(), entry.targetRevision(),
                    Timestamp.from(entry.occurredAt()), details(entry));
        } catch (DuplicateKeyException exception) {
            throw new GroupRuleException("The activity id has already been recorded.");
        }
    }

    @Override
    public List<ActivityEntry> forScope(String scopeId) {
        return jdbc.query("""
                SELECT activity_id, scope_id, actor_member_id, action, target_id,
                    target_revision, occurred_at, details::text AS details
                FROM activity_entry WHERE scope_id = ?
                ORDER BY occurred_at, activity_id
                LIMIT 500
                """, (result, index) -> new ActivityEntry(
                result.getString("activity_id"),
                result.getString("scope_id"),
                result.getString("actor_member_id") == null ? null
                        : new com.dutch.ledger.group.MemberId(result.getString("actor_member_id")),
                ActivityAction.valueOf(result.getString("action")),
                result.getString("target_id"),
                result.getLong("target_revision"),
                result.getTimestamp("occurred_at").toInstant(),
                readDetails(result.getString("details"))), scopeId);
    }

    private String details(ActivityEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry.details());
        } catch (Exception exception) {
            throw new IllegalStateException("activity details are not serializable", exception);
        }
    }

    private Map<String, String> readDetails(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("activity details are not readable", exception);
        }
    }
}
