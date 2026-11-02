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
    private static final MemberId E = new MemberId("mem_412345678901234567");
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
    void pages_a_snapshot_by_sequence_without_dropping_or_duplicating_rows() {
        transactions.executeWithoutResult(status -> {
            journal.append(List.of(E), "GROUP", "grp_112345678901234567", "upsert", 1,
                    Map.of("name", "One"), null, NOW);
            journal.append(List.of(E), "GROUP", "grp_212345678901234567", "upsert", 1,
                    Map.of("name", "Two"), null, NOW.plusSeconds(1));
            journal.append(List.of(E), "GROUP", "grp_312345678901234567", "upsert", 1,
                    Map.of("name", "Three"), null, NOW.plusSeconds(2));
        });
        long asOf = journal.maxSequence(E);
        var first = journal.snapshot(E, asOf, 0, 2);
        assertThat(first).hasSize(2);
        var second = journal.snapshot(E, asOf, first.get(first.size() - 1).sequence(), 2);
        assertThat(second).hasSize(1);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream())
                .map(com.dutch.ledger.sync.SyncChange::resourceId).distinct().count()).isEqualTo(3);

        transactions.executeWithoutResult(status -> journal.append(List.of(E), "GROUP",
                "grp_212345678901234567", "tombstone", 2, Map.of(), null, NOW.plusSeconds(3)));
        long tombstoneAsOf = journal.maxSequence(E);
        java.util.List<String> paged = new java.util.ArrayList<>();
        long cursor = 0;
        for (int page = 0; page < 10; page++) {
            var rows = journal.snapshot(E, tombstoneAsOf, cursor, 1);
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> paged.add(row.resourceId()));
            cursor = rows.get(rows.size() - 1).sequence();
        }
        assertThat(paged).containsExactly("grp_112345678901234567", "grp_312345678901234567")
                .doesNotContain("grp_212345678901234567");
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

        var snapshot = journal.snapshot(C, journal.maxSequence(C), 0, 1000);
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).resourceId()).isEqualTo("exp_112345678901234567");
        assertThat(snapshot.get(0).payload()).containsEntry("description", "New");
    }
}
