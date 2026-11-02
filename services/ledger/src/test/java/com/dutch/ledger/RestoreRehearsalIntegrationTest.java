package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.expense.Expense;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.JdbcExpenseRepository;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.outbox.OutboxPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Restore rehearsal against a throwaway PostgreSQL container: dump a migrated and
 * populated Ledger, restore it into a new database, and reconcile entries, outbox,
 * Flyway history, and the balance projection.
 */
@Testcontainers(disabledWithoutDocker = true)
class RestoreRehearsalIntegrationTest {

    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final ExpenseId EXPENSE = new ExpenseId("exp_012345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_rehearsal");

    @Test
    void a_dump_restores_with_the_same_entries_outbox_and_projection() throws Exception {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcGroupRepository groups = new JdbcGroupRepository(jdbc);
        JdbcExpenseRepository expenses = new JdbcExpenseRepository(jdbc);
        OutboxPublisher outbox = new OutboxPublisher(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(GROUP, "Restore flat", "INR", A, NOW);
            group.invite(A, B, GroupRole.MEMBER, NOW);
            group.activate(B, B, NOW);
            groups.saveNew(group);
        });
        Expense expense = Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW);
        transactions.executeWithoutResult(status -> {
            expenses.save(expense);
            outbox.publish("ledger.expense.committed.v1", "expense", EXPENSE.value(), 1,
                    "cor_restore", null, NOW, Map.of("amountMinorUnits", 100));
        });

        long entries = jdbc.queryForObject("SELECT count(*) FROM obligation_entry", Long.class);
        long outboxRows = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class);
        long migrations = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class);

        Path dump = Files.createTempFile("dutch-ledger-", ".sql");
        var dumpResult = database.execInContainer("pg_dump", "-U", database.getUsername(),
                "-d", database.getDatabaseName(), "--no-owner", "--no-privileges");
        Files.writeString(dump, dumpResult.getStdout());
        assertThat(dumpResult.getExitCode()).isZero();
        database.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/ledger-restore.sql");

        String restored = "ledger_restore_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(
                database.getJdbcUrl().replace("/" + database.getDatabaseName(), "/postgres"),
                database.getUsername(), database.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE \"" + restored + "\"");
        }
        var restore = database.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U",
                database.getUsername(), "-d", restored, "-f", "/tmp/ledger-restore.sql");
        assertThat(restore.getExitCode())
                .as(restore.getStderr())
                .isZero();

        String restoredUrl = database.getJdbcUrl().replace("/" + database.getDatabaseName(),
                "/" + restored);
        JdbcTemplate restoredJdbc = new JdbcTemplate(new DriverManagerDataSource(
                restoredUrl, database.getUsername(), database.getPassword()));
        assertThat(restoredJdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry", Long.class)).isEqualTo(entries);
        assertThat(restoredJdbc.queryForObject(
                "SELECT count(*) FROM outbox_event", Long.class)).isEqualTo(outboxRows);
        assertThat(restoredJdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(migrations);
        assertThat(restoredJdbc.queryForObject("""
                WITH expected AS (
                    SELECT stream_id, member_id, SUM(amount) AS balance FROM (
                        SELECT stream_id, creditor_member_id AS member_id, minor_units AS amount
                        FROM obligation_entry
                        UNION ALL
                        SELECT stream_id, debtor_member_id, -minor_units FROM obligation_entry
                    ) entries GROUP BY stream_id, member_id
                ), actual AS (
                    SELECT stream_id, member_id, balance_minor_units FROM member_balance_projection
                )
                SELECT count(*) FROM (
                    SELECT * FROM expected EXCEPT SELECT * FROM actual
                    UNION ALL
                    SELECT * FROM actual EXCEPT SELECT * FROM expected
                ) diff
                """, Long.class)).isZero();
        assertThat(restoredJdbc.queryForObject(
                "SELECT balance_minor_units FROM member_balance_projection WHERE stream_id = ? AND member_id = ?",
                Long.class, "group:" + GROUP.value() + ":INR", A.value())).isEqualTo(50L);
    }
}
