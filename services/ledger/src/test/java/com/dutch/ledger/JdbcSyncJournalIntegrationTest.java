package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.sync.JdbcSyncJournal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class JdbcSyncJournalIntegrationTest {

    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final MemberId C = new MemberId("mem_212345678901234567");
    private static final MemberId D = new MemberId("mem_312345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_sync_test");

    private static JdbcTemplate jdbc;
    private static JdbcSyncJournal journal;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        journal = new JdbcSyncJournal(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void allocates_one_independent_sequence_per_recipient() {
        transactions.executeWithoutResult(status -> {
            journal.append(List.of(A, B), "EXPENSE", "exp_012345678901234567", "upsert", 1,
                    Map.of("description", "Dinner"), null, NOW);
            journal.append(List.of(A), "SERIES", "ser_012345678901234567", "upsert", 1,
                    Map.of("cadence", "MONTHLY"), null, NOW.plusSeconds(1));
        });

        List<com.dutch.ledger.sync.SyncChange> forA = journal.after(A, 0, 10);
        assertThat(forA).extracting(com.dutch.ledger.sync.SyncChange::sequence)
                .containsExactly(1L, 2L);
        assertThat(forA.get(0).payload()).containsEntry("description", "Dinner");
        assertThat(journal.after(B, 0, 10)).hasSize(1);
        assertThat(journal.after(A, 1, 10)).hasSize(1);
        assertThat(journal.maxSequence(A)).isEqualTo(2);
    }

    @Test
    void snapshots_the_latest_non_tombstone_row_per_resource() {
        transactions.executeWithoutResult(status -> {
            journal.append(List.of(C), "EXPENSE", "exp_112345678901234567", "upsert", 1,
                    Map.of("description", "Old"), null, NOW);
            journal.append(List.of(C), "EXPENSE", "exp_112345678901234567", "upsert", 2,
                    Map.of("description", "New"), null, NOW.plusSeconds(1));
            journal.append(List.of(C), "EXPENSE", "exp_212345678901234567", "upsert", 1,
                    Map.of("description", "Gone"), null, NOW.plusSeconds(2));
            journal.append(List.of(C), "EXPENSE", "exp_212345678901234567", "tombstone", 2,
                    Map.of(), null, NOW.plusSeconds(3));
            journal.append(List.of(D), "EXPENSE", "exp_412345678901234567", "upsert", 1,
                    Map.of(), null, NOW);
        });

        var snapshot = journal.snapshot(C);
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).resourceId()).isEqualTo("exp_112345678901234567");
        assertThat(snapshot.get(0).payload()).containsEntry("description", "New");
    }
}
