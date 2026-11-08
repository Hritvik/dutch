package com.dutch.ledger.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.JdbcMemberResolver;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMemberAssociationReportConsumerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_association_test");

    private static JdbcTemplate jdbc;
    private static JdbcMemberAssociationReportConsumer consumer;
    private static JdbcMemberMappingConsumer mappings;
    private static JdbcMemberResolver resolver;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        consumer = new JdbcMemberAssociationReportConsumer(jdbc, "dutch-development", "test");
        mappings = new JdbcMemberMappingConsumer(jdbc);
        resolver = new JdbcMemberResolver(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void a_ready_report_grants_access_only_when_the_mapping_exists() {
        String subject = "aps_ready_01234567890123456789";
        String member = "mem_ready_0123456789";

        consume(ready(subject, 1, "evt_ready_00000000000001", 1L));
        assertThat(resolve(subject)).isEmpty();

        map(subject, member, 1);
        assertThat(resolve(subject)).contains(new MemberId(member));
    }

    @Test
    void a_missing_binding_denies_access_even_with_an_active_mapping() {
        String subject = "aps_outage_012345678901234567";
        map(subject, "mem_outage_0123456789", 1);
        assertThat(resolve(subject)).isEmpty();
    }

    @Test
    void a_readiness_regression_denies_access() {
        String subject = "aps_regress_01234567890123456";
        String member = "mem_regress_0123456789";
        map(subject, member, 1);
        consume(ready(subject, 1, "evt_regress_0000000000001", 1L));
        assertThat(resolve(subject)).contains(new MemberId(member));

        consume(live("evt_regress_0000000000002", subject, 2, "PROFILE_REQUIRED",
                "ACTIVE", null, 1L, "1"));
        assertThat(resolve(subject)).isEmpty();
    }

    @Test
    void an_erased_binding_denies_access() {
        String subject = "aps_erased_012345678901234567";
        map(subject, "mem_erased_0123456789", 1);
        consume(ready(subject, 1, "evt_erased_00000000000001", 1L));

        consume(erased("evt_erased_00000000000002", subject, 2, 1L));
        assertThat(resolve(subject)).isEmpty();
    }

    @Test
    void a_live_report_reusing_a_retired_generation_is_refused() {
        String subject = "aps_retired_01234567890123456";
        consume(erased("evt_retired_0000000000001", subject, 1, 3L));

        MemberAssociationReport resurrection = live("evt_retired_0000000000002", subject, 2,
                "PROFILE_READY", "ACTIVE", "REOPENED", 3L, "9");

        assertThatThrownBy(() -> consume(resurrection))
                .isInstanceOf(RetiredGenerationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM ums_profile_binding WHERE app_subject = ?",
                String.class, subject)).isEqualTo("ERASED");
    }

    @Test
    void a_duplicate_report_creates_one_binding_across_restart() {
        String subject = "aps_dup_0123456789012345678901";
        MemberAssociationReport report = ready(subject, 4, "evt_dup_00000000000001", 2L);

        assertThat(consume(report)).isTrue();
        assertThat(consume(report)).isFalse();
        // a fresh consumer is what a process restart looks like
        JdbcMemberAssociationReportConsumer restarted =
                new JdbcMemberAssociationReportConsumer(jdbc, "dutch-development", "test");
        assertThat(consume(restarted, report)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ums_profile_binding WHERE app_subject = ?",
                Integer.class, subject)).isEqualTo(1);
    }

    @Test
    void a_stale_report_does_not_regress_readiness() {
        String subject = "aps_stale_012345678901234567";
        map(subject, "mem_stale_0123456789", 1);
        consume(ready(subject, 5, "evt_stale_00000000000001", 1L));

        boolean applied = consume(live("evt_stale_00000000000002", subject, 4,
                "PROFILE_REQUIRED", "ACTIVE", null, 1L, "1"));

        assertThat(applied).isFalse();
        assertThat(resolve(subject)).isPresent();
    }

    @Test
    void a_reused_event_id_with_another_payload_is_a_conflict() {
        String subject = "aps_conflict_01234567890123";
        consume(ready(subject, 1, "evt_conflict_000000000001", 1L));

        MemberAssociationReport conflicting = live("evt_conflict_000000000001", subject, 2,
                "PROFILE_REQUIRED", "ACTIVE", null, 1L, "1");

        assertThatThrownBy(() -> consume(conflicting))
                .isInstanceOf(MemberMappingConflictException.class);
    }

    @Test
    void a_report_without_a_member_is_held_unreconciled() {
        String subject = "aps_orphan_012345678901234567";
        consume(ready(subject, 1, "evt_orphan_0000000000001", 1L));

        assertThat(consumer.unreconciledSubjects()).contains(subject);
        assertThat(resolve(subject)).isEmpty();

        map(subject, "mem_orphan_0123456789", 1);
        assertThat(resolve(subject)).isPresent();
        assertThat(consumer.unreconciledSubjects()).doesNotContain(subject);
    }

    @Test
    void replaying_the_mapping_fact_keeps_exactly_one_mapping() {
        String subject = "aps_single_012345678901234567";
        MemberMappingEvent event = new MemberMappingEvent("evt_single_0000000000001", subject,
                "mem_single_0123456789", 1, "ACTIVE", NOW);
        consume(ready(subject, 1, "evt_single_0000000000002", 1L));

        transactions.execute(status -> mappings.consume(event));
        transactions.execute(status -> mappings.consume(event));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_subject_member_mapping WHERE app_subject = ?",
                Integer.class, subject)).isEqualTo(1);
        assertThat(resolve(subject)).contains(new MemberId("mem_single_0123456789"));
    }

    private static MemberAssociationReport ready(String subject, long revision, String eventId,
                                                 Long generation) {
        return live(eventId, subject, revision, "PROFILE_READY", "ACTIVE", null, generation, "5");
    }

    private static MemberAssociationReport live(String eventId, String subject, long revision,
                                                String profileState, String lifecycleState,
                                                String reason, Long generation,
                                                String profileRevision) {
        return new MemberAssociationReport(eventId, subject, subject, "dutch-development", "test",
                revision, profileState, lifecycleState, reason, generation, profileRevision,
                null, false, NOW);
    }

    private static MemberAssociationReport erased(String eventId, String subject, long revision,
                                                  Long retiredGeneration) {
        return new MemberAssociationReport(eventId, subject, subject, "dutch-development", "test",
                revision, "PROFILE_REQUIRED", "ERASED", "ERASED", null, null,
                retiredGeneration, false, NOW);
    }

    private static boolean consume(MemberAssociationReport report) {
        return consume(consumer, report);
    }

    private static boolean consume(JdbcMemberAssociationReportConsumer target,
                                   MemberAssociationReport report) {
        return Boolean.TRUE.equals(transactions.execute(status -> target.consume(report)));
    }

    private static void map(String subject, String member, long version) {
        transactions.execute(status -> mappings.consume(new MemberMappingEvent(
                "evt_map_" + subject.substring(4, 14), subject, member, version, "ACTIVE", NOW)));
    }

    private static java.util.Optional<MemberId> resolve(String subject) {
        return transactions.execute(status -> resolver.resolve(subject));
    }

    @Test
    void concurrent_revisions_leave_the_highest_applied() throws Exception {
        String subject = "aps_race_01234567890123456789";
        map(subject, "mem_race_012345678901", 1);
        consume(ready(subject, 1, "evt_race_00000000000001", 1L));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var slower = pool.submit(() -> consume(live("evt_race_00000000000002", subject, 2,
                    "PROFILE_READY", "ACTIVE", null, 1L, "5")));
            var faster = pool.submit(() -> consume(live("evt_race_00000000000003", subject, 3,
                    "PROFILE_REQUIRED", "ACTIVE", null, 1L, "5")));
            slower.get();
            faster.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT binding_revision FROM ums_profile_binding WHERE app_subject = ?",
                Long.class, subject)).isEqualTo(3L);
        assertThat(resolve(subject)).isEmpty();
    }

    @Test
    void concurrent_duplicate_delivery_is_a_smoke_test_for_the_insert_race() throws Exception {
        String subject = "aps_conc_01234567890123456789";
        MemberAssociationReport report = ready(subject, 1, "evt_conc_00000000000001", 1L);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> consume(report));
            var second = pool.submit(() -> consume(report));
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ums_profile_binding WHERE app_subject = ?",
                Integer.class, subject)).isEqualTo(1);
    }

    @Test
    void an_erasure_watermark_never_moves_backwards() {
        String subject = "aps_water_0123456789012345678";
        consume(erased("evt_water_00000000000001", subject, 1, 5L));

        assertThatThrownBy(() -> consume(erased("evt_water_00000000000002", subject, 2, 2L)))
                .isInstanceOf(RetiredGenerationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT retired_generation FROM ums_profile_binding WHERE app_subject = ?",
                Long.class, subject)).isEqualTo(5L);
    }

    @Test
    void an_erasure_below_the_live_generation_is_refused() {
        String subject = "aps_below_012345678901234567";
        consume(ready(subject, 1, "evt_below_00000000000001", 4L));

        assertThatThrownBy(() -> consume(erased("evt_below_00000000000002", subject, 2, 2L)))
                .isInstanceOf(RetiredGenerationException.class);
    }

    @Test
    void a_report_for_another_app_registration_is_refused() {
        String subject = "aps_scope_0123456789012345678";
        MemberAssociationReport foreign = new MemberAssociationReport(
                "evt_scope_00000000000001", subject, subject, "dutch-production", "test",
                1, "PROFILE_READY", "ACTIVE", null, 1L, "5", null, false, NOW);

        assertThatThrownBy(() -> consume(foreign))
                .isInstanceOf(ForeignAssociationReportException.class);
    }

    @Test
    void closure_and_pending_deletion_deny_access() {
        String closedSubject = "aps_closed_012345678901234567";
        map(closedSubject, "mem_closed_0123456789", 1);
        consume(ready(closedSubject, 1, "evt_closed_0000000000001", 1L));
        assertThat(resolve(closedSubject)).isPresent();

        consume(live("evt_closed_0000000000002", closedSubject, 2, "PROFILE_READY",
                "CLOSED", "CLOSED", 1L, "5"));
        assertThat(resolve(closedSubject)).isEmpty();

        String pendingSubject = "aps_pending_01234567890123456";
        map(pendingSubject, "mem_pending_0123456789", 1);
        consume(ready(pendingSubject, 1, "evt_pending_0000000000001", 1L));
        consume(live("evt_pending_0000000000002", pendingSubject, 2, "PROFILE_READY",
                "DELETION_PENDING", "DELETION_REQUESTED", 1L, "5"));
        assertThat(resolve(pendingSubject)).isEmpty();
    }

    @Test
    void a_reused_event_id_with_another_profile_revision_is_a_conflict() {
        String subject = "aps_pr_012345678901234567890";
        consume(ready(subject, 1, "evt_pr_0000000000000001", 1L));

        assertThatThrownBy(() -> consume(live("evt_pr_0000000000000001", subject, 1,
                "PROFILE_READY", "ACTIVE", null, 1L, "6")))
                .isInstanceOf(MemberMappingConflictException.class);
    }

    @Test
    void a_report_for_another_environment_is_refused() {
        String subject = "aps_env_012345678901234567890";
        MemberAssociationReport foreign = new MemberAssociationReport(
                "evt_env_0000000000000001", subject, subject, "dutch-development", "staging",
                1, "PROFILE_READY", "ACTIVE", null, 1L, "5", null, false, NOW);

        assertThatThrownBy(() -> consume(foreign))
                .isInstanceOf(ForeignAssociationReportException.class);
    }

    @Test
    void a_new_generation_is_accepted_and_a_late_erasure_below_it_is_refused() {
        String subject = "aps_life_01234567890123456";
        map(subject, "mem_life_012345678901", 1);
        consume(erased("evt_life_0000000000000001", subject, 1, 5L));

        consume(live("evt_life_0000000000000002", subject, 2, "PROFILE_READY", "ACTIVE",
                "REOPENED", 6L, "9"));
        assertThat(resolve(subject)).isPresent();

        assertThatThrownBy(() -> consume(erased("evt_life_0000000000000003", subject, 3, 4L)))
                .isInstanceOf(RetiredGenerationException.class);
        assertThat(resolve(subject)).isPresent();
    }

    @Test
    void data_subject_rights_survive_a_readiness_regression_and_outage() {
        String outage = "aps_rights_0123456789012345678";
        map(outage, "mem_rights_0123456789", 1);
        assertThat(resolve(outage)).isEmpty();
        java.util.Optional<MemberId> outageRights = transactions.execute(
                status -> resolver.resolveForDataSubjectRights(outage));
        assertThat(outageRights).isPresent();

        String regressed = "aps_rights_1123456789012345678";
        map(regressed, "mem_rights_1123456789", 1);
        consume(ready(regressed, 1, "evt_rights_00000000000001", 1L));
        consume(live("evt_rights_00000000000002", regressed, 2, "PROFILE_REQUIRED",
                "ACTIVE", null, 1L, "1"));
        assertThat(resolve(regressed)).isEmpty();
        java.util.Optional<MemberId> regressedRights = transactions.execute(
                status -> resolver.resolveForDataSubjectRights(regressed));
        assertThat(regressedRights).isPresent();
    }

    @Test
    void the_outage_direction_is_countable() {
        String member = "mem_gauge_0123456789";
        String subject = "aps_gauge_0123456789012345678";
        map(subject, member, 1);

        assertThat(consumer.unmappedReports()).contains(member);
        consume(ready(subject, 1, "evt_gauge_00000000000001", 1L));
        assertThat(consumer.unmappedReports()).doesNotContain(member);

        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new UnreconciledBindingMetrics(registry, consumer);
        assertThat(registry.get("dutch.ledger.association.unmapped").gauge().value())
                .isGreaterThanOrEqualTo(0.0d);
        assertThat(registry.get("dutch.ledger.association.unreconciled").gauge().value())
                .isGreaterThanOrEqualTo(0.0d);
    }

    @Test
    void an_event_id_reused_for_another_subject_is_a_conflict() {
        String first = "aps_evt_a_0123456789012345678";
        String second = "aps_evt_b_0123456789012345678";
        consume(ready(first, 1, "evt_shared_00000000000001", 1L));

        assertThatThrownBy(() -> consume(ready(second, 1, "evt_shared_00000000000001", 1L)))
                .isInstanceOf(MemberMappingConflictException.class);
        assertThat(resolve(second)).isEmpty();
    }

    @Test
    void data_subject_rights_still_require_an_active_mapping() {
        String subject = "aps_rights_2123456789012345678";
        transactions.execute(status -> mappings.consume(new MemberMappingEvent(
                "evt_rights_00000000000003", subject, "mem_rights_2123456789", 1,
                "DEPARTED", NOW)));

        java.util.Optional<MemberId> rights = transactions.execute(
                status -> resolver.resolveForDataSubjectRights(subject));
        assertThat(rights).isEmpty();
    }
}
