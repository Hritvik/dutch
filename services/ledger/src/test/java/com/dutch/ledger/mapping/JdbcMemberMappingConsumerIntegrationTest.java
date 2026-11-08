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
class JdbcMemberMappingConsumerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_mapping_test");

    private static JdbcMemberMappingConsumer consumer;
    private static JdbcMemberAssociationReportConsumer associations;
    private static JdbcMemberResolver resolver;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        consumer = new JdbcMemberMappingConsumer(jdbc);
        associations = new JdbcMemberAssociationReportConsumer(jdbc);
        resolver = new JdbcMemberResolver(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void applies_once_and_then_ignores_the_duplicate() {
        MemberMappingEvent event = event("evt_1", "aps_one_0123456789012345678901",
                "mem_012345678901234567", 1, "ACTIVE");

        var applied = transactions.execute(status -> consumer.consume(event));
        var duplicate = transactions.execute(status -> consumer.consume(event));
        ready(associations, "aps_one_0123456789012345678901", 1, "evt_bind_00000000000001");
        var resolved = transactions.execute(status -> resolver.resolve("aps_one_0123456789012345678901"));
        assertThat(applied).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(resolved).contains(new MemberId("mem_012345678901234567"));
    }

    @Test
    void rejects_a_reused_event_id_with_another_payload() {
        MemberMappingEvent first = event("evt_2", "aps_two_0123456789012345678901",
                "mem_112345678901234567", 1, "ACTIVE");
        MemberMappingEvent conflict = event("evt_2", "aps_two_0123456789012345678901",
                "mem_212345678901234567", 1, "ACTIVE");

        transactions.execute(status -> consumer.consume(first));

        assertThatThrownBy(() -> transactions.execute(status -> consumer.consume(conflict)))
                .isInstanceOf(MemberMappingConflictException.class);
    }

    @Test
    void ignores_a_stale_mapping_version() {
        String subject = "aps_three_012345678901234567890";
        transactions.execute(status -> consumer.consume(
                event("evt_3", subject, "mem_312345678901234567", 3, "SUSPENDED")));

        ready(associations, subject, 1, "evt_bind_00000000000002");
        var stale = transactions.execute(status -> consumer.consume(
                event("evt_4", subject, "mem_412345678901234567", 2, "ACTIVE")));
        var resolved = transactions.execute(status -> resolver.resolve(subject));
        assertThat(stale).isFalse();
        assertThat(resolved).isEmpty();
    }

    @Test
    void a_newer_version_updates_the_projection_and_gates_on_active() {
        String subject = "aps_four_012345678901234567890";
        transactions.execute(status -> consumer.consume(
                event("evt_5", subject, "mem_512345678901234567", 1, "SUSPENDED")));
        ready(associations, subject, 1, "evt_bind_00000000000003");
        var suspended = transactions.execute(status -> resolver.resolve(subject));
        assertThat(suspended).isEmpty();

        var applied = transactions.execute(status -> consumer.consume(
                event("evt_6", subject, "mem_512345678901234567", 2, "ACTIVE")));
        var active = transactions.execute(status -> resolver.resolve(subject));
        assertThat(applied).isTrue();
        assertThat(active).contains(new MemberId("mem_512345678901234567"));
    }

    static void ready(JdbcMemberAssociationReportConsumer consumer, String subject,
                      long revision, String eventId) {
        consumer.consume(new MemberAssociationReport(eventId, subject, revision,
                "PROFILE_READY", "ACTIVE", null, 1L, "1", null, false, NOW));
    }

    private static MemberMappingEvent event(String eventId, String subject, String memberId,
                                            long version, String lifecycle) {
        return new MemberMappingEvent(eventId, subject, memberId, version, lifecycle, NOW);
    }
}
