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
        consumer = new JdbcMemberAssociationReportConsumer(jdbc);
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

        consume(new MemberAssociationReport("evt_regress_0000000000002", subject, 2,
                "PROFILE_REQUIRED", "ACTIVE", null, 1L, "1", null, false, NOW));
        assertThat(resolve(subject)).isEmpty();
    }

    @Test
    void an_erased_binding_denies_access() {
        String subject = "aps_erased_012345678901234567";
        map(subject, "mem_erased_0123456789", 1);
        consume(ready(subject, 1, "evt_erased_00000000000001", 1L));

        consume(new MemberAssociationReport("evt_erased_00000000000002", subject, 2,
                "PROFILE_REQUIRED", "ERASED", "ERASED", null, null, 1L, false, NOW));
        assertThat(resolve(subject)).isEmpty();
    }

    @Test
    void a_live_report_reusing_a_retired_generation_is_refused() {
        String subject = "aps_retired_01234567890123456";
        consume(new MemberAssociationReport("evt_retired_0000000000001", subject, 1,
                "PROFILE_REQUIRED", "ERASED", "ERASED", null, null, 3L, false, NOW));

        MemberAssociationReport resurrection = new MemberAssociationReport(
                "evt_retired_0000000000002", subject, 2, "PROFILE_READY", "ACTIVE",
                "REOPENED", 3L, "9", null, false, NOW);

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
        JdbcMemberAssociationReportConsumer restarted = new JdbcMemberAssociationReportConsumer(jdbc);
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

        boolean applied = consume(new MemberAssociationReport(
                "evt_stale_00000000000002", subject, 4, "PROFILE_REQUIRED", "ACTIVE",
                null, 1L, "1", null, false, NOW));

        assertThat(applied).isFalse();
        assertThat(resolve(subject)).isPresent();
    }

    @Test
    void a_reused_event_id_with_another_payload_is_a_conflict() {
        String subject = "aps_conflict_01234567890123";
        consume(ready(subject, 1, "evt_conflict_000000000001", 1L));

        MemberAssociationReport conflicting = new MemberAssociationReport(
                "evt_conflict_000000000001", subject, 2, "PROFILE_REQUIRED", "ACTIVE",
                null, 1L, "1", null, false, NOW);

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
        return new MemberAssociationReport(eventId, subject, revision, "PROFILE_READY",
                "ACTIVE", null, generation, "5", null, false, NOW);
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
}
