package com.dutch.ledger.mapping;

import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes UMS association reports into the Dutch-owned readiness projection.
 *
 * <p>The consumer never creates a member and never changes the mapping: it records
 * what UMS observed for an app subject so authorization can fail closed when the
 * profile regresses, is erased, or was never reported.
 *
 * <p>Ordering is the binding revision only. The subject row is locked before the
 * revision is compared and the update repeats the revision predicate, so a slow
 * lower revision cannot overwrite a newer or erased fact.
 */
@Service
public class JdbcMemberAssociationReportConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcMemberAssociationReportConsumer.class);

    private final JdbcTemplate jdbc;
    private final String appRegistrationId;
    private final String environment;

    public JdbcMemberAssociationReportConsumer(
            JdbcTemplate jdbc,
            @Value("${dutch.ums.app-registration-id:dutch-development}") String appRegistrationId,
            @Value("${dutch.ums.environment:development}") String environment) {
        this.jdbc = jdbc;
        this.appRegistrationId = appRegistrationId;
        this.environment = environment;
    }

    /**
     * @return true when the projection advanced, false for a duplicate or stale report
     * @throws MemberMappingConflictException when an event id is reused with another payload
     * @throws RetiredGenerationException when a live report reuses a generation UMS erased
     * @throws ForeignAssociationReportException when the report targets another app or environment
     */
    @Transactional
    public boolean consume(MemberAssociationReport report) {
        if (!report.belongsTo(appRegistrationId, environment)) {
            throw new ForeignAssociationReportException();
        }
        Existing locked = lock(report.appSubject());
        if (locked != null) {
            return applyToExisting(report, locked);
        }

        int inserted = jdbc.update("""
                INSERT INTO ums_profile_binding
                    (app_subject, binding_revision, profile_state, lifecycle_state,
                     lifecycle_reason, generation, retired_generation, profile_revision,
                     content_obligation, app_registration_id, environment, source_event_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, report.appSubject(), report.bindingRevision(), report.profileState(),
                report.lifecycleState(), report.lifecycleReason(), report.generation(),
                report.retiredGeneration(), report.profileRevision(), report.contentObligation(),
                report.appRegistrationId(), report.environment(), report.eventId(),
                Timestamp.from(report.occurredAt()));
        if (inserted == 1) {
            noteIfUnreconciled(report.appSubject());
            return true;
        }
        // Another delivery won the insert race. Re-read under the lock and classify it
        // instead of failing the transaction: an at-least-once redelivery is not an error.
        Existing winner = lock(report.appSubject());
        if (winner == null) {
            // The only remaining unique key is the event id: it is already used for
            // another subject, which is the contract's "reused event id" conflict.
            throw new MemberMappingConflictException();
        }
        return applyToExisting(report, winner);
    }

    private boolean applyToExisting(MemberAssociationReport report, Existing previous) {
        if (previous.sourceEventId().equals(report.eventId())) {
            if (!previous.matches(report)) {
                throw new MemberMappingConflictException();
            }
            return false;
        }
        if (report.bindingRevision() <= previous.bindingRevision()) {
            return false;
        }
        if (report.reusesRetiredGeneration(previous.retiredGeneration())) {
            throw new RetiredGenerationException();
        }
        if (report.isErasure() && previous.retiredGeneration() != null
                && report.retiredGeneration() < previous.retiredGeneration()) {
            // An erasure watermark never moves backwards; a lower retired generation is
            // either a delayed tombstone or a corrupt one.
            throw new RetiredGenerationException();
        }
        if (report.isErasure() && previous.generation() != null
                && report.retiredGeneration() < previous.generation()) {
            // UMS cannot erase a generation it never assigned.
            throw new RetiredGenerationException();
        }

        int updated = jdbc.update("""
                UPDATE ums_profile_binding
                SET binding_revision = ?, profile_state = ?, lifecycle_state = ?,
                    lifecycle_reason = ?, generation = ?, retired_generation = ?,
                    profile_revision = ?, content_obligation = ?, app_registration_id = ?,
                    environment = ?, source_event_id = ?, updated_at = ?
                WHERE app_subject = ? AND binding_revision < ?
                """, report.bindingRevision(), report.profileState(), report.lifecycleState(),
                report.lifecycleReason(), report.generation(), report.retiredGeneration(),
                report.profileRevision(), report.contentObligation(), report.appRegistrationId(),
                report.environment(), report.eventId(), Timestamp.from(report.occurredAt()),
                report.appSubject(), report.bindingRevision());
        if (updated == 0) {
            return false;
        }
        noteIfUnreconciled(report.appSubject());
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

    /**
     * Members that hold an active mapping but have no UMS report. This is the
     * provider-outage shape: silence must not read as readiness, and it must be
     * visible rather than inferred from a denial.
     */
    public List<String> unmappedReports() {
        return jdbc.queryForList("""
                SELECT m.member_id FROM app_subject_member_mapping m
                LEFT JOIN ums_profile_binding b ON b.app_subject = m.app_subject
                WHERE m.lifecycle_state = 'ACTIVE' AND b.app_subject IS NULL
                ORDER BY m.member_id
                """, String.class);
    }

    private void noteIfUnreconciled(String appSubject) {
        Integer mapped = jdbc.queryForObject("""
                SELECT count(*) FROM app_subject_member_mapping WHERE app_subject = ?
                """, Integer.class, appSubject);
        if (mapped != null && mapped == 0) {
            log.warn("association report for {} has no Dutch member mapping", appSubject);
        }
    }

    private Existing lock(String appSubject) {
        return jdbc.query("""
                SELECT app_subject, binding_revision, profile_state, lifecycle_state,
                       lifecycle_reason, generation, retired_generation, profile_revision,
                       content_obligation, app_registration_id, environment, source_event_id
                FROM ums_profile_binding WHERE app_subject = ? FOR UPDATE
                """, (result, index) -> new Existing(
                result.getString("app_subject"),
                result.getLong("binding_revision"),
                result.getString("profile_state"),
                result.getString("lifecycle_state"),
                result.getString("lifecycle_reason"),
                result.getObject("generation", Long.class),
                result.getObject("retired_generation", Long.class),
                result.getString("profile_revision"),
                result.getBoolean("content_obligation"),
                result.getString("app_registration_id"),
                result.getString("environment"),
                result.getString("source_event_id")), appSubject).stream().findFirst().orElse(null);
    }

    private record Existing(
            String appSubject,
            long bindingRevision,
            String profileState,
            String lifecycleState,
            String lifecycleReason,
            Long generation,
            Long retiredGeneration,
            String profileRevision,
            boolean contentObligation,
            String appRegistrationId,
            String environment,
            String sourceEventId) {

        boolean matches(MemberAssociationReport report) {
            return appSubject.equals(report.appSubject())
                    && bindingRevision == report.bindingRevision()
                    && profileState.equals(report.profileState())
                    && lifecycleState.equals(report.lifecycleState())
                    && java.util.Objects.equals(lifecycleReason, report.lifecycleReason())
                    && java.util.Objects.equals(generation, report.generation())
                    && java.util.Objects.equals(retiredGeneration, report.retiredGeneration())
                    && java.util.Objects.equals(profileRevision, report.profileRevision())
                    && contentObligation == report.contentObligation()
                    && appRegistrationId.equals(report.appRegistrationId())
                    && environment.equals(report.environment());
        }
    }
}
