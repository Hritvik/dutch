package com.dutch.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
class OutboxRelayIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_outbox_test");

    private static JdbcTemplate jdbc;
    private static OutboxPublisher publisher;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        publisher = new OutboxPublisher(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void publishes_a_claimed_batch_once_and_marks_it_published() {
        TestTransport transport = new TestTransport(false);
        OutboxRelay relay = relay(transport);

        transactions.executeWithoutResult(status -> {
            publisher.publish("ledger.expense.committed.v1", "expense", "exp_012345678901234567",
                    1, "cor_1", null, Instant.now(), Map.of("amountMinorUnits", 100));
            publisher.publish("ledger.settlement.committed.v1", "settlement",
                    "stl_012345678901234567", 1, null, null, Instant.now(), Map.of("amount", 50));
        });

        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(transport.sent).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                WHERE published_at IS NOT NULL
                  AND aggregate_id IN ('exp_012345678901234567', 'stl_012345678901234567')
                """, Long.class)).isEqualTo(2);
        assertThat(relay.relayOnce()).isZero();
        assertThat(transport.sent).hasSize(2);
    }

    @Test
    void a_failing_transport_retries_then_moves_to_the_dead_letter_table() {
        TestTransport transport = new TestTransport(true);
        OutboxRelay relay = relay(transport);

        transactions.executeWithoutResult(status -> publisher.publish(
                "ledger.expense.committed.v1", "expense", "exp_112345678901234567", 1,
                null, null, Instant.now(), Map.of("amountMinorUnits", 100)));

        for (int attempt = 0; attempt < 5; attempt++) {
            relay.relayOnce();
            // The relay backs off; move the due time forward for the next attempt.
            jdbc.update("""
                    UPDATE outbox_event SET next_attempt_at = now() WHERE aggregate_id = ?
                    """, "exp_112345678901234567");
        }

        assertThat(jdbc.queryForObject(
                "SELECT publish_attempts FROM outbox_event WHERE aggregate_id = ?",
                Integer.class, "exp_112345678901234567")).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_dead_letter WHERE event_id IN "
                        + "(SELECT event_id FROM outbox_event WHERE aggregate_id = ?)",
                Long.class, "exp_112345678901234567")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE aggregate_id = ?",
                java.sql.Timestamp.class, "exp_112345678901234567")).isNull();
        assertThat(relay.relayOnce()).isZero();

        // Rehearsed recovery: clear the dead letter, reset the row, and replay safely.
        jdbc.update("""
                DELETE FROM outbox_dead_letter WHERE event_id IN
                    (SELECT event_id FROM outbox_event WHERE aggregate_id = ?)
                """, "exp_112345678901234567");
        jdbc.update("""
                UPDATE outbox_event SET published_at = NULL, claimed_at = NULL,
                    publish_attempts = 0, last_error = NULL, next_attempt_at = now()
                WHERE aggregate_id = ?
                """, "exp_112345678901234567");
        TestTransport recovered = new TestTransport(false);
        assertThat(relay(recovered).relayOnce()).isEqualTo(1);
        assertThat(recovered.sent).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE aggregate_id = ?",
                java.sql.Timestamp.class, "exp_112345678901234567")).isNotNull();
    }

    private static OutboxRelay relay(TestTransport transport) {
        return new OutboxRelay(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                transactions, transport, 10, 5);
    }

    private static final class TestTransport implements OutboxTransport {
        private final boolean failing;
        private final List<OutboxEvent> sent = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        private TestTransport(boolean failing) {
            this.failing = failing;
        }

        @Override
        public void send(OutboxEvent event) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("notification is unreachable");
            }
            sent.add(event);
        }
    }
}
