package com.dutch.ums.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OnboardingFaultIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(),
                database.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    private static OnboardingReadyEvent event(String eventId, String subject, long onboardingVersion) {
        return new OnboardingReadyEvent(eventId, "dutch-development", subject,
                "act_012345678901234567", "ACTIVE", 2, "FIRST_ACTIVATION", onboardingVersion,
                "PROFILE_REQUIRED", Instant.parse("2026-10-31T00:00:00Z"), "cor_0123456789012345", null);
    }

    private MemberMappingChanged consumeInTransaction(OnboardingReadyEvent event) {
        var consumer = new JdbcMemberProfileConsumer(new JdbcTemplate(dataSource), "development");
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> consumer.consume(event));
    }

    @Test
    void concurrentDeliveryOfTheSameEventCreatesOneProfileAndOneMapping() throws Exception {
        var event = event("evt_012345678901234567", "aps_0123456789012345678901ab", 1);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await();
                return consumeInTransaction(event);
            });
            var second = executor.submit(() -> {
                barrier.await();
                return consumeInTransaction(event);
            });
            var one = first.get();
            var two = second.get();
            assertThat(one.memberId()).isEqualTo(two.memberId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void restartResumesFromTheInboxWithoutASecondEffect() {
        var event = event("evt_212345678901234567", "aps_1123456789012345678901ab", 1);
        var first = consumeInTransaction(event);

        var restarted = new JdbcMemberProfileConsumer(new JdbcTemplate(dataSource), "development");
        var afterRestart = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> restarted.consume(event));

        assertThat(afterRestart.memberId()).isEqualTo(first.memberId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void aConflictingPayloadForTheSameEventIdLeavesNoSecondEffect() {
        var event = event("evt_312345678901234567", "aps_2123456789012345678901ab", 1);
        consumeInTransaction(event);

        assertThatThrownBy(() -> consumeInTransaction(
                event("evt_312345678901234567", "aps_3123456789012345678901ab", 2)))
                .isInstanceOf(ConflictingOnboardingEventException.class);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM inbox_quarantine WHERE event_id = ?",
                String.class, "evt_312345678901234567")).isEqualTo("PAYLOAD_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_checkpoint", Integer.class)).isEqualTo(1);
    }
}
