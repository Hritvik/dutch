package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.direct.DirectExpense;
import com.dutch.ledger.direct.JdbcDirectExpenseRepository;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
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
class JdbcDirectExpenseRepositoryIntegrationTest {

    private static final ExpenseId EXPENSE = new ExpenseId("exp_012345678901234567");
    private static final ExpenseId MULTI = new ExpenseId("exp_112345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final MemberId C = new MemberId("mem_212345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_direct_test");

    private static JdbcTemplate jdbc;
    private static JdbcDirectExpenseRepository expenses;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        expenses = new JdbcDirectExpenseRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void appends_a_direct_revision_and_rebuilds_each_pair_projection() {
        DirectExpense expense = DirectExpense.create(EXPENSE, "Dinner", 100, "INR",
                SplitMethod.EQUAL, List.of(new PayerContribution(B, 100)),
                List.of(new ParticipantShare(B, 50), new ParticipantShare(C, 50)), NOW);

        transactions.executeWithoutResult(status -> expenses.save(expense));

        assertThat(expenses.find(EXPENSE)).contains(expense);
        assertThat(expenses.streamIds(EXPENSE))
                .containsExactly("direct:" + B.value() + ":" + C.value() + ":INR");
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor_units FROM member_balance_projection WHERE stream_id = ? AND member_id = ?",
                Long.class, "direct:" + B.value() + ":" + C.value() + ":INR", B.value()))
                .isEqualTo(50L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor_units FROM member_balance_projection WHERE stream_id = ? AND member_id = ?",
                Long.class, "direct:" + B.value() + ":" + C.value() + ":INR", C.value()))
                .isEqualTo(-50L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry WHERE direct_expense_id = ?", Long.class,
                EXPENSE.value())).isEqualTo(1);
    }

    @Test
    void a_multi_member_direct_expense_uses_one_stream_per_pair() {
        DirectExpense expense = DirectExpense.create(MULTI, "Gift", 100, "INR",
                SplitMethod.EXACT, List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(B, 60), new ParticipantShare(C, 40)), NOW);

        transactions.executeWithoutResult(status -> expenses.save(expense));

        assertThat(expenses.streamIds(MULTI)).containsExactly(
                "direct:" + A.value() + ":" + B.value() + ":INR",
                "direct:" + A.value() + ":" + C.value() + ":INR");
        assertThat(jdbc.queryForObject("""
                SELECT balance_minor_units FROM member_balance_projection
                WHERE stream_id = ? AND member_id = ?
                """, Long.class, "direct:" + A.value() + ":" + C.value() + ":INR", A.value()))
                .isEqualTo(40L);
    }

    @Test
    void the_revision_constraints_reject_an_imbalanced_raw_write() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO direct_expense (direct_expense_id, description, total_minor_units,
                        currency, split_method, algorithm_version, current_revision, occurred_at)
                    VALUES ('exp_912345678901234567', 'Broken', 100, 'INR', 'EQUAL',
                        'expense-allocation-v1', 1, now())
                    """);
            jdbc.update("""
                    INSERT INTO direct_expense_revision (direct_expense_id, revision, description,
                        total_minor_units, currency, split_method, algorithm_version, occurred_at)
                    VALUES ('exp_912345678901234567', 1, 'Broken', 100, 'INR', 'EQUAL',
                        'expense-allocation-v1', now())
                    """);
            jdbc.update("""
                    INSERT INTO direct_payer_contribution
                        (direct_expense_id, revision, member_id, minor_units)
                    VALUES ('exp_912345678901234567', 1, ?, 90)
                    """, A.value());
        })).hasStackTraceContaining("must equal 100");
    }
}
