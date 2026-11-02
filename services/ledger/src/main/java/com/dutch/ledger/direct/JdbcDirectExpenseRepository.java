package com.dutch.ledger.direct;

import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.FinancialStreams;
import com.dutch.ledger.expense.ObligationEntry;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.MemberId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Appends a Direct Expense revision and its per-pair direct-stream entries. */
@Repository
public class JdbcDirectExpenseRepository implements DirectExpenseRepository {

    private final JdbcTemplate jdbc;
    private final FinancialStreams streams;

    public JdbcDirectExpenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.streams = new FinancialStreams(jdbc);
    }

    @Override
    @Transactional
    public void save(DirectExpense expense) {
        jdbc.update("""
                INSERT INTO direct_expense (direct_expense_id, description, total_minor_units,
                    currency, split_method, algorithm_version, current_revision, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, expense.id().value(), expense.description(), expense.totalMinorUnits(),
                expense.currency(), expense.splitMethod().name(), expense.algorithmVersion(),
                expense.revision(), Timestamp.from(expense.occurredAt()));
        jdbc.update("""
                INSERT INTO direct_expense_revision (direct_expense_id, revision, description,
                    total_minor_units, currency, split_method, algorithm_version, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, expense.id().value(), expense.revision(), expense.description(),
                expense.totalMinorUnits(), expense.currency(), expense.splitMethod().name(),
                expense.algorithmVersion(), Timestamp.from(expense.occurredAt()));
        for (PayerContribution payer : expense.payers()) {
            jdbc.update("""
                    INSERT INTO direct_payer_contribution
                        (direct_expense_id, revision, member_id, minor_units)
                    VALUES (?, ?, ?, ?)
                    """, expense.id().value(), expense.revision(), payer.memberId().value(),
                    payer.minorUnits());
        }
        for (ParticipantShare share : expense.shares()) {
            jdbc.update("""
                    INSERT INTO direct_participant_share
                        (direct_expense_id, revision, member_id, minor_units)
                    VALUES (?, ?, ?, ?)
                    """, expense.id().value(), expense.revision(), share.memberId().value(),
                    share.minorUnits());
        }

        Set<String> touched = new LinkedHashSet<>();
        for (ObligationEntry entry : expense.obligationEntries()) {
            String streamId = DirectScope.streamId(entry.debtor(), entry.creditor(),
                    expense.currency());
            if (touched.add(streamId)) {
                streams.lockDirect(streamId, expense.currency());
            }
            jdbc.update("""
                    INSERT INTO obligation_entry (expense_id, revision, group_id, stream_id,
                        debtor_member_id, creditor_member_id, minor_units, currency,
                        algorithm_version, entry_kind, direct_expense_id)
                    VALUES (NULL, ?, NULL, ?, ?, ?, ?, ?, ?, 'CHARGE', ?)
                    """, expense.revision(), streamId, entry.debtor().value(),
                    entry.creditor().value(), entry.minorUnits(), entry.currency(),
                    entry.algorithmVersion(), expense.id().value());
        }
        for (String streamId : touched) {
            streams.rebuild(streamId, expense.currency());
        }
    }

    @Override
    public Optional<DirectExpense> find(ExpenseId id) {
        List<DirectRevisionRow> rows = jdbc.query("""
                SELECT revision, description, total_minor_units, currency, split_method, occurred_at
                FROM direct_expense_revision
                WHERE direct_expense_id = ?
                ORDER BY revision DESC
                LIMIT 1
                """, (result, index) -> new DirectRevisionRow(
                result.getLong("revision"),
                result.getString("description"),
                result.getLong("total_minor_units"),
                result.getString("currency"),
                result.getString("split_method"),
                result.getTimestamp("occurred_at").toInstant()), id.value());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        DirectRevisionRow row = rows.get(0);
        List<PayerContribution> payers = jdbc.query("""
                SELECT member_id, minor_units FROM direct_payer_contribution
                WHERE direct_expense_id = ? AND revision = ? ORDER BY member_id
                """, (result, index) -> new PayerContribution(
                new MemberId(result.getString("member_id")),
                result.getLong("minor_units")), id.value(), row.revision());
        List<ParticipantShare> shares = jdbc.query("""
                SELECT member_id, minor_units FROM direct_participant_share
                WHERE direct_expense_id = ? AND revision = ? ORDER BY member_id
                """, (result, index) -> new ParticipantShare(
                new MemberId(result.getString("member_id")),
                result.getLong("minor_units")), id.value(), row.revision());
        return Optional.of(DirectExpense.createAtRevision(id, row.description(),
                row.totalMinorUnits(), row.currency(), SplitMethod.valueOf(row.splitMethod()),
                payers, shares, row.occurredAt(), row.revision()));
    }

    @Override
    public List<DirectExpense> findInvolving(com.dutch.ledger.group.MemberId memberId) {
        return jdbc.query("""
                SELECT d.direct_expense_id
                FROM direct_expense d
                WHERE EXISTS (
                    SELECT 1 FROM direct_payer_contribution p
                    WHERE p.direct_expense_id = d.direct_expense_id AND p.member_id = ?
                ) OR EXISTS (
                    SELECT 1 FROM direct_participant_share s
                    WHERE s.direct_expense_id = d.direct_expense_id AND s.member_id = ?
                )
                ORDER BY d.direct_expense_id
                """, (result, index) -> result.getString("direct_expense_id"),
                memberId.value(), memberId.value()).stream()
                .map(value -> find(new ExpenseId(value)))
                .flatMap(Optional::stream)
                .toList();
    }

    /** The direct-pair streams currently holding entries for this expense. */
    @Override
    public List<String> streamIds(ExpenseId id) {
        return new ArrayList<>(jdbc.queryForList("""
                SELECT DISTINCT stream_id FROM obligation_entry
                WHERE direct_expense_id = ? ORDER BY stream_id
                """, String.class, id.value()));
    }

    private record DirectRevisionRow(long revision, String description, long totalMinorUnits,
                                     String currency, String splitMethod,
                                     java.time.Instant occurredAt) {
    }
}
