package com.dutch.ledger.mapping;

import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent, monotonic consumer for the UMS member-mapping fact. */
@Service
@ConditionalOnBean(DataSource.class)
public class JdbcMemberMappingConsumer {

    private final JdbcTemplate jdbc;

    public JdbcMemberMappingConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when the projection advanced, false for a duplicate or stale event
     * @throws MemberMappingConflictException when an event id is reused with another payload
     */
    @Transactional
    public boolean consume(MemberMappingEvent event) {
        List<Existing> existing = jdbc.query("""
                SELECT member_id, lifecycle_state, mapping_version
                FROM app_subject_member_mapping WHERE source_event_id = ?
                """, (result, index) -> new Existing(
                result.getString("member_id"),
                result.getString("lifecycle_state"),
                result.getLong("mapping_version")), event.eventId());
        if (!existing.isEmpty()) {
            Existing previous = existing.get(0);
            if (!previous.memberId().equals(event.memberId())
                    || !previous.lifecycleState().equals(event.lifecycleState())
                    || previous.mappingVersion() != event.mappingVersion()) {
                throw new MemberMappingConflictException();
            }
            return false;
        }

        try {
            int updated = jdbc.update("""
                    INSERT INTO app_subject_member_mapping
                        (app_subject, member_id, lifecycle_state, mapping_version, source_event_id, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (app_subject) DO UPDATE SET
                        member_id = EXCLUDED.member_id,
                        lifecycle_state = EXCLUDED.lifecycle_state,
                        mapping_version = EXCLUDED.mapping_version,
                        source_event_id = EXCLUDED.source_event_id,
                        updated_at = EXCLUDED.updated_at
                    WHERE EXCLUDED.mapping_version > app_subject_member_mapping.mapping_version
                    """, event.appSubject(), event.memberId(), event.lifecycleState(),
                    event.mappingVersion(), event.eventId(), Timestamp.from(event.occurredAt()));
            return updated == 1;
        } catch (DuplicateKeyException exception) {
            // A concurrent writer won the source_event_id race; re-read and classify it.
            List<Existing> raced = jdbc.query("""
                    SELECT member_id, lifecycle_state, mapping_version
                    FROM app_subject_member_mapping WHERE source_event_id = ?
                    """, (result, index) -> new Existing(
                    result.getString("member_id"),
                    result.getString("lifecycle_state"),
                    result.getLong("mapping_version")), event.eventId());
            if (raced.isEmpty()) {
                throw exception;
            }
            Existing previous = raced.get(0);
            if (!previous.memberId().equals(event.memberId())
                    || !previous.lifecycleState().equals(event.lifecycleState())
                    || previous.mappingVersion() != event.mappingVersion()) {
                throw new MemberMappingConflictException();
            }
            return false;
        }
    }

    private record Existing(String memberId, String lifecycleState, long mappingVersion) {
    }
}
