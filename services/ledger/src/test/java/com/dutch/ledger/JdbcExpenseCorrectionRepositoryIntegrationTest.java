package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.expense.Expense;
import com.dutch.ledger.expense.ExpenseCorrection;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.JdbcExpenseCorrectionRepository;
import com.dutch.ledger.expense.JdbcExpenseRepository;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.List;
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
class JdbcExpenseCorrectionRepositoryIntegrationTest {

    private static final GroupId GROUP = new GroupId("grp_612345678901234567");
    private static final ExpenseId EXPENSE = new ExpenseId("exp_612345678901234567");
    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_correction_test");

    private static JdbcTemplate jdbc;
    private static JdbcExpenseRepository expenses;
    private static JdbcExpenseCorrectionRepository corrections;
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
        corrections = new JdbcExpenseCorrectionRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcGroupRepository groups = new JdbcGroupRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(GROUP, "Flat", "INR", ADMIN, NOW);
            group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
            group.activate(MEMBER, MEMBER, NOW);
            groups.saveNew(group);
        });
    }

    @Test
    void appends_a_revision_reversal_and_replacement_and_rebuilds_the_projection() {
        Expense original = expense(1, 40, 60);
        Expense replacement = expense(2, 50, 50);
        ExpenseCorrection correction = ExpenseCorrection.of(original, replacement, ADMIN, NOW);

        transactions.executeWithoutResult(status -> expenses.save(original));
        transactions.executeWithoutResult(status -> corrections.save(correction));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM expense_revision WHERE expense_id = ?", Long.class, EXPENSE.value()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM expense_correction WHERE expense_id = ?", Long.class, EXPENSE.value()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry WHERE expense_id = ?", Long.class, EXPENSE.value()))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry WHERE expense_id = ? AND entry_kind = 'REVERSAL'",
                Long.class, EXPENSE.value())).isEqualTo(1);

        assertThat(expenses.projectionFor(GROUP, "INR").balances())
                .containsEntry(ADMIN, 50L)
                .containsEntry(MEMBER, -50L);

        assertThat(jdbc.queryForObject("""
                SELECT debtor_member_id FROM obligation_entry
                WHERE expense_id = ? AND entry_kind = 'REVERSAL'
                """, String.class, EXPENSE.value())).isEqualTo(ADMIN.value());
    }

    private static Expense expense(long revision, long adminShare, long memberShare) {
        return Expense.createAtRevision(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(ADMIN, 100)),
                List.of(new ParticipantShare(ADMIN, adminShare),
                        new ParticipantShare(MEMBER, memberShare)), NOW, revision);
    }
}
