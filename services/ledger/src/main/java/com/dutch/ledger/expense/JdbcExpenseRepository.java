package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupId;
import java.sql.Timestamp;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists one immutable Expense revision and rebuilds its balance projection. */
@Repository
public class JdbcExpenseRepository implements ExpenseRepository {

    private final JdbcTemplate jdbc;

    public JdbcExpenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String streamId(GroupId groupId, String currency) {
        return "group:" + groupId.value() + ":" + currency;
    }

    @Override
    @Transactional
    public void save(Expense expense) {
        String streamId = streamId(expense.groupId(), expense.currency());
        Long streamVersion = jdbc.queryForObject("""
                INSERT INTO financial_stream (stream_id, scope_type, group_id, currency)
                VALUES (?, 'GROUP', ?, ?)
                ON CONFLICT (stream_id) DO UPDATE SET
                    stream_version = financial_stream.stream_version + 1,
                    updated_at = now()
                RETURNING stream_version
                """, Long.class, streamId, expense.groupId().value(), expense.currency());

        jdbc.update("""
                INSERT INTO expense (expense_id, group_id, description, currency, total_minor_units,
                    split_method, algorithm_version, current_revision, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, expense.id().value(), expense.groupId().value(), expense.description(),
                expense.currency(), expense.totalMinorUnits(), expense.splitMethod().name(),
                expense.algorithmVersion(), expense.revision(), Timestamp.from(expense.occurredAt()));

        jdbc.update("""
                INSERT INTO expense_revision (expense_id, revision, description, total_minor_units,
                    currency, split_method, algorithm_version, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, expense.id().value(), expense.revision(), expense.description(),
                expense.totalMinorUnits(), expense.currency(), expense.splitMethod().name(),
                expense.algorithmVersion(), Timestamp.from(expense.occurredAt()));

        for (PayerContribution payer : expense.payers()) {
            jdbc.update("""
                    INSERT INTO payer_contribution (expense_id, revision, member_id, minor_units)
                    VALUES (?, ?, ?, ?)
                    """, expense.id().value(), expense.revision(), payer.memberId().value(),
                    payer.minorUnits());
        }
        for (ParticipantShare share : expense.shares()) {
            jdbc.update("""
                    INSERT INTO participant_share (expense_id, revision, member_id, minor_units)
                    VALUES (?, ?, ?, ?)
                    """, expense.id().value(), expense.revision(), share.memberId().value(),
                    share.minorUnits());
        }
        for (ObligationEntry entry : expense.obligationEntries()) {
            jdbc.update("""
                    INSERT INTO obligation_entry (expense_id, revision, stream_id, debtor_member_id,
                        creditor_member_id, minor_units, currency, algorithm_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, entry.expenseId().value(), entry.revision(), streamId,
                    entry.debtor().value(), entry.creditor().value(), entry.minorUnits(),
                    entry.currency(), entry.algorithmVersion());
        }

        jdbc.update("DELETE FROM member_balance_projection WHERE stream_id = ?", streamId);
        jdbc.update("""
                INSERT INTO member_balance_projection
                    (stream_id, member_id, currency, balance_minor_units, stream_version)
                SELECT ?, member_id, ?, SUM(amount), ?
                FROM (
                    SELECT creditor_member_id AS member_id, minor_units AS amount
                    FROM obligation_entry WHERE stream_id = ?
                    UNION ALL
                    SELECT debtor_member_id AS member_id, -minor_units AS amount
                    FROM obligation_entry WHERE stream_id = ?
                ) net
                GROUP BY member_id
                """, streamId, expense.currency(), streamVersion, streamId, streamId);
    }

    @Override
    public List<ObligationEntry> entriesFor(GroupId groupId, String currency) {
        return jdbc.query("""
                SELECT expense_id, revision, debtor_member_id, creditor_member_id, minor_units,
                    currency, algorithm_version
                FROM obligation_entry WHERE stream_id = ? ORDER BY entry_id
                """, (result, index) -> new ObligationEntry(
                new ExpenseId(result.getString("expense_id")),
                result.getLong("revision"),
                new com.dutch.ledger.group.MemberId(result.getString("debtor_member_id")),
                new com.dutch.ledger.group.MemberId(result.getString("creditor_member_id")),
                result.getLong("minor_units"),
                result.getString("currency"),
                result.getString("algorithm_version")), streamId(groupId, currency));
    }

    @Override
    public BalanceProjection projectionFor(GroupId groupId, String currency) {
        String streamId = streamId(groupId, currency);
        SortedMap<com.dutch.ledger.group.MemberId, Long> balances = new TreeMap<>();
        jdbc.query("""
                SELECT member_id, balance_minor_units FROM member_balance_projection
                WHERE stream_id = ? ORDER BY member_id
                """, (result) -> {
            long amount = result.getLong("balance_minor_units");
            if (amount != 0) {
                balances.put(new com.dutch.ledger.group.MemberId(result.getString("member_id")), amount);
            }
        }, streamId);
        return new BalanceProjection(currency, balances,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }
}
