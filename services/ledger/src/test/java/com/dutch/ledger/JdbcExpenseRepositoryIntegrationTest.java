package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
class JdbcExpenseRepositoryIntegrationTest {

    private static final GroupId GROUP = new GroupId("grp_712345678901234567");
    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_expense_test");

    private static JdbcTemplate jdbc;
    private static JdbcExpenseRepository expenses;
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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcGroupRepository groups = new JdbcGroupRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(GROUP, "Flat", "INR", ADMIN, NOW);
            group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
            group.activate(MEMBER, MEMBER, NOW);
            groups.saveNew(group);
        });
    }

    @BeforeEach
    void cleanFinancialRows() {
        jdbc.execute("""
                TRUNCATE member_balance_projection, obligation_entry, participant_share,
                    payer_contribution, expense_revision, expense, financial_stream CASCADE
                """);
    }

    @Test
    void persists_entries_and_rebuilds_the_balance_projection() {
        Expense expense = Expense.create(new ExpenseId("exp_012345678901234567"), GROUP, "Dinner",
                100, "INR", SplitMethod.EQUAL,
                java.util.List.of(new PayerContribution(ADMIN, 100)),
                java.util.List.of(new ParticipantShare(ADMIN, 40),
                        new ParticipantShare(MEMBER, 60)), NOW);

        transactions.executeWithoutResult(status -> expenses.save(expense));

        assertThat(expenses.entriesFor(GROUP, "INR")).isEqualTo(expense.obligationEntries());
        assertThat(expenses.projectionFor(GROUP, "INR").balances())
                .isEqualTo(expense.netPositions());
    }

    @Test
    void a_second_expense_folds_into_the_same_stream() {
        Expense first = Expense.create(new ExpenseId("exp_112345678901234567"), GROUP, "Dinner",
                100, "INR", SplitMethod.EQUAL,
                java.util.List.of(new PayerContribution(ADMIN, 100)),
                java.util.List.of(new ParticipantShare(ADMIN, 50),
                        new ParticipantShare(MEMBER, 50)), NOW);
        Expense second = Expense.create(new ExpenseId("exp_212345678901234567"), GROUP, "Taxi",
                40, "INR", SplitMethod.EQUAL,
                java.util.List.of(new PayerContribution(MEMBER, 40)),
                java.util.List.of(new ParticipantShare(ADMIN, 20),
                        new ParticipantShare(MEMBER, 20)), NOW);

        transactions.executeWithoutResult(status -> {
            expenses.save(first);
            expenses.save(second);
        });

        assertThat(expenses.entriesFor(GROUP, "INR")).hasSize(2);
        assertThat(expenses.projectionFor(GROUP, "INR").balances())
                .containsEntry(ADMIN, 30L)
                .containsEntry(MEMBER, -30L);
    }

    @Test
    void rejects_an_entry_that_disagrees_with_the_positions() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO financial_stream (stream_id, scope_type, group_id, currency)
                    VALUES ('group:bad-entry:INR', 'GROUP', ?, 'INR')
                    """, GROUP.value());
            jdbc.update("""
                    INSERT INTO expense (expense_id, group_id, description, currency, total_minor_units,
                        split_method, algorithm_version, occurred_at)
                    VALUES ('exp_a12345678901234567', ?, 'Bad entry', 'INR', 100, 'EQUAL', 'v1', now())
                    """, GROUP.value());
            jdbc.update("""
                    INSERT INTO expense_revision (expense_id, revision, description, total_minor_units,
                        currency, split_method, algorithm_version, occurred_at)
                    VALUES ('exp_a12345678901234567', 1, 'Bad entry', 100, 'INR', 'EQUAL', 'v1', now())
                    """);
            jdbc.update("""
                    INSERT INTO payer_contribution
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES ('exp_a12345678901234567', 1, ?, ?, 100)
                    """, GROUP.value(), ADMIN.value());
            jdbc.update("""
                    INSERT INTO participant_share
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES ('exp_a12345678901234567', 1, ?, ?, 100)
                    """, GROUP.value(), ADMIN.value());
            jdbc.update("""
                    INSERT INTO obligation_entry (expense_id, revision, group_id, stream_id,
                        debtor_member_id, creditor_member_id, minor_units, currency, algorithm_version)
                    VALUES ('exp_a12345678901234567', 1, ?, 'group:bad-entry:INR', ?, ?, 10, 'INR', 'v1')
                    """, GROUP.value(), MEMBER.value(), ADMIN.value());
        })).hasStackTraceContaining("entries must match");
    }

    @Test
    void rejects_an_update_to_immutable_evidence() {
        Expense expense = Expense.create(new ExpenseId("exp_b12345678901234567"), GROUP, "Dinner",
                100, "INR", SplitMethod.EQUAL,
                java.util.List.of(new PayerContribution(ADMIN, 100)),
                java.util.List.of(new ParticipantShare(ADMIN, 50),
                        new ParticipantShare(MEMBER, 50)), NOW);
        transactions.executeWithoutResult(status -> expenses.save(expense));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE expense SET total_minor_units = 999 WHERE expense_id = ?",
                        expense.id().value())))
                .hasStackTraceContaining("immutable");
    }

    @Test
    void rejects_a_delete_of_immutable_evidence() {
        Expense expense = Expense.create(new ExpenseId("exp_e12345678901234567"), GROUP, "Dinner",
                100, "INR", SplitMethod.EQUAL,
                java.util.List.of(new PayerContribution(ADMIN, 100)),
                java.util.List.of(new ParticipantShare(ADMIN, 50),
                        new ParticipantShare(MEMBER, 50)), NOW);
        transactions.executeWithoutResult(status -> expenses.save(expense));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("DELETE FROM obligation_entry WHERE expense_id = ?", expense.id().value())))
                .hasStackTraceContaining("immutable");
    }

    @Test
    void rejects_a_payer_who_is_not_a_group_member() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO financial_stream (stream_id, scope_type, group_id, currency)
                    VALUES ('group:bad-member:INR', 'GROUP', ?, 'INR')
                    """, GROUP.value());
            jdbc.update("""
                    INSERT INTO expense (expense_id, group_id, description, currency, total_minor_units,
                        split_method, algorithm_version, occurred_at)
                    VALUES ('exp_c12345678901234567', ?, 'Bad member', 'INR', 100, 'EQUAL', 'v1', now())
                    """, GROUP.value());
            jdbc.update("""
                    INSERT INTO expense_revision (expense_id, revision, description, total_minor_units,
                        currency, split_method, algorithm_version, occurred_at)
                    VALUES ('exp_c12345678901234567', 1, 'Bad member', 100, 'INR', 'EQUAL', 'v1', now())
                    """);
            jdbc.update("""
                    INSERT INTO payer_contribution
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES ('exp_c12345678901234567', 1, ?, ?, 100)
                    """, GROUP.value(), "mem_999999999999999999");
        })).hasStackTraceContaining("foreign key");
    }

    @Test
    void rejects_an_entry_for_another_stream() {
        Expense expense = Expense.create(new ExpenseId("exp_d12345678901234567"), GROUP, "Dinner",
                100, "INR", SplitMethod.EQUAL,
                java.util.List.of(new PayerContribution(ADMIN, 100)),
                java.util.List.of(new ParticipantShare(ADMIN, 50),
                        new ParticipantShare(MEMBER, 50)), NOW);
        transactions.executeWithoutResult(status -> expenses.save(expense));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("""
                        INSERT INTO obligation_entry (expense_id, revision, group_id, stream_id,
                            debtor_member_id, creditor_member_id, minor_units, currency, algorithm_version)
                        VALUES (?, 1, ?, 'group:other:INR', ?, ?, 10, 'INR', 'v1')
                        """, expense.id().value(), GROUP.value(), ADMIN.value(), MEMBER.value())))
                .hasStackTraceContaining("foreign key");
    }

    @Test
    void rejects_a_revision_whose_contributions_do_not_match_the_total_at_commit() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO financial_stream (stream_id, scope_type, group_id, currency)
                    VALUES ('group:unbalanced:INR', 'GROUP', ?, 'INR')
                    """, GROUP.value());
            jdbc.update("""
                    INSERT INTO expense (expense_id, group_id, description, currency, total_minor_units,
                        split_method, algorithm_version, occurred_at)
                    VALUES ('exp_912345678901234567', ?, 'Bad', 'INR', 100, 'EQUAL', 'v1', now())
                    """, GROUP.value());
            jdbc.update("""
                    INSERT INTO expense_revision (expense_id, revision, description, total_minor_units,
                        currency, split_method, algorithm_version, occurred_at)
                    VALUES ('exp_912345678901234567', 1, 'Bad', 100, 'INR', 'EQUAL', 'v1', now())
                    """);
            jdbc.update("""
                    INSERT INTO payer_contribution
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES ('exp_912345678901234567', 1, ?, ?, 90)
                    """, GROUP.value(), ADMIN.value());
            jdbc.update("""
                    INSERT INTO participant_share
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES ('exp_912345678901234567', 1, ?, ?, 100)
                    """, GROUP.value(), ADMIN.value());
        })).hasStackTraceContaining("contributions/shares must equal 100");
    }
}
