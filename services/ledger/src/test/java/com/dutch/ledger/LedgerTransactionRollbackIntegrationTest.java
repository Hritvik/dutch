package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.activity.JdbcActivityRepository;
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

/** A failure after the journal append must roll back the source, entries, projection, and journal. */
@Testcontainers(disabledWithoutDocker = true)
class LedgerTransactionRollbackIntegrationTest {

    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final ExpenseId EXPENSE = new ExpenseId("exp_012345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_rollback_test");

    private static JdbcTemplate jdbc;
    private static JdbcExpenseRepository expenses;
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
        expenses = new JdbcExpenseRepository(jdbc);
        journal = new JdbcSyncJournal(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcGroupRepository groups = new JdbcGroupRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(GROUP, "Rollback flat", "INR", A, NOW);
            group.invite(A, B, GroupRole.MEMBER, NOW);
            group.activate(B, B, NOW);
            groups.saveNew(group);
        });
    }

    @Test
    void a_failure_after_the_journal_append_rolls_back_the_expense_and_projection() {
        Expense expense = Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            expenses.save(expense);
            journal.append(List.of(A, B), "EXPENSE", EXPENSE.value(), "upsert", 1,
                    Map.of("description", "Dinner"), null, NOW);
            throw new IllegalStateException("simulated journal failure");
        })).hasMessageContaining("simulated journal failure");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM expense WHERE expense_id = ?", Long.class, EXPENSE.value()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry WHERE expense_id = ?", Long.class,
                EXPENSE.value())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM member_sync_change", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM member_balance_projection", Long.class)).isZero();
    }
}
