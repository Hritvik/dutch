package com.dutch.ledger.mapping;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes UMS association reports into the Dutch-owned readiness projection.
 *
 * <p>The consumer never creates a member and never changes the mapping: it records
 * what UMS observed for an app subject so authorization can fail closed when the
 * profile regresses, is erased, or was never reported.
 */
@Service
public class JdbcMemberAssociationReportConsumer {

    private final JdbcTemplate jdbc;

    public JdbcMemberAssociationReportConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when the projection advanced, false for a duplicate or stale report
     * @throws MemberMappingConflictException when an event id is reused with another payload
     * @throws RetiredGenerationException when a live report reuses a generation UMS erased
     */
    @Transactional
    public boolean consume(MemberAssociationReport report) {
        Optional<Existing> byEvent = jdbc.query("""
                SELECT app_subject, binding_revision, profile_state, lifecycle_state,
                       lifecycle_reason, generation, retired_generation, content_obligation
                FROM ums_profile_binding WHERE source_event_id = ?
                """, (result, index) -> new Existing(
                result.getString("app_subject"),
                result.getLong("binding_revision"),
                result.getString("profile_state"),
                result.getString("lifecycle_state"),
                result.getString("lifecycle_reason"),
                result.getObject("generation", Long.class),
                result.getObject("retired_generation", Long.class),
                result.getBoolean("content_obligation")), report.eventId()).stream().findFirst();
        if (byEvent.isPresent()) {
            Existing previous = byEvent.get();
            if (!previous.matches(report)) {
                throw new MemberMappingConflictException();
            }
            return false;
        }

        Optional<Existing> current = jdbc.query("""
                SELECT app_subject, binding_revision, profile_state, lifecycle_state,
                       lifecycle_reason, generation, retired_generation, content_obligation
                FROM ums_profile_binding WHERE app_subject = ?
                """, (result, index) -> new Existing(
                result.getString("app_subject"),
                result.getLong("binding_revision"),
                result.getString("profile_state"),
                result.getString("lifecycle_state"),
                result.getString("lifecycle_reason"),
                result.getObject("generation", Long.class),
                result.getObject("retired_generation", Long.class),
                result.getBoolean("content_obligation")), report.appSubject()).stream().findFirst();

        if (current.isPresent()) {
            Existing previous = current.get();
            if (report.bindingRevision() <= previous.bindingRevision()) {
                return false;
            }
            if (report.reusesRetiredGeneration(previous.retiredGeneration())) {
                throw new RetiredGenerationException();
            }
            jdbc.update("""
                    UPDATE ums_profile_binding
                    SET binding_revision = ?, profile_state = ?, lifecycle_state = ?,
                        lifecycle_reason = ?, generation = ?, retired_generation = ?,
                        content_obligation = ?, source_event_id = ?, updated_at = ?
                    WHERE app_subject = ?
                    """, report.bindingRevision(), report.profileState(), report.lifecycleState(),
                    report.lifecycleReason(), report.generation(), report.retiredGeneration(),
                    report.contentObligation(), report.eventId(), Timestamp.from(report.occurredAt()),
                    report.appSubject());
            return true;
        }

        jdbc.update("""
                INSERT INTO ums_profile_binding
                    (app_subject, binding_revision, profile_state, lifecycle_state,
                     lifecycle_reason, generation, retired_generation, content_obligation,
                     source_event_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report.appSubject(), report.bindingRevision(), report.profileState(),
                report.lifecycleState(), report.lifecycleReason(), report.generation(),
                report.retiredGeneration(), report.contentObligation(), report.eventId(),
                Timestamp.from(report.occurredAt()));
        return true;
    }

    /** App subjects UMS reported that Dutch has no member mapping for. */
    public List<String> unreconciledSubjects() {
        return jdbc.queryForList("""
                SELECT b.app_subject FROM ums_profile_binding b
                LEFT JOIN app_subject_member_mapping m ON m.app_subject = b.app_subject
                WHERE m.app_subject IS NULL ORDER BY b.app_subject
                """, String.class);
    }

    private record Existing(
            String appSubject,
            long bindingRevision,
            String profileState,
            String lifecycleState,
            String lifecycleReason,
            Long generation,
            Long retiredGeneration,
            boolean contentObligation) {

        boolean matches(MemberAssociationReport report) {
            return appSubject.equals(report.appSubject())
                    && bindingRevision == report.bindingRevision()
                    && profileState.equals(report.profileState())
                    && lifecycleState.equals(report.lifecycleState())
                    && java.util.Objects.equals(lifecycleReason, report.lifecycleReason())
                    && java.util.Objects.equals(generation, report.generation())
                    && java.util.Objects.equals(retiredGeneration, report.retiredGeneration())
                    && contentObligation == report.contentObligation();
        }
    }
}
