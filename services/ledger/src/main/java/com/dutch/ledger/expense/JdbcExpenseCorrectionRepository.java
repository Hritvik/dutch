package com.dutch.ledger.expense;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Appends a correction revision, its reversal entries, and the replacement entries. */
@Repository
public class JdbcExpenseCorrectionRepository {

    private final JdbcTemplate jdbc;

    public JdbcExpenseCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(ExpenseCorrection correction) {
        Expense replacement = correction.replacement();
        String streamId = JdbcExpenseRepository.streamId(correction.groupId(), replacement.currency());
        String correctionId = "corr_" + UUID.randomUUID().toString().replace("-", "");

        jdbc.update("""
                INSERT INTO expense_revision (expense_id, revision, description, total_minor_units,
                    currency, split_method, algorithm_version, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, replacement.id().value(), replacement.revision(), replacement.description(),
                replacement.totalMinorUnits(), replacement.currency(), replacement.splitMethod().name(),
                replacement.algorithmVersion(), Timestamp.from(replacement.occurredAt()));

        for (PayerContribution payer : replacement.payers()) {
            jdbc.update("""
                    INSERT INTO payer_contribution
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES (?, ?, ?, ?, ?)
                    """, replacement.id().value(), replacement.revision(),
                    replacement.groupId().value(), payer.memberId().value(), payer.minorUnits());
        }
        for (ParticipantShare share : replacement.shares()) {
            jdbc.update("""
                    INSERT INTO participant_share
                        (expense_id, revision, group_id, member_id, minor_units)
                    VALUES (?, ?, ?, ?, ?)
                    """, replacement.id().value(), replacement.revision(),
                    replacement.groupId().value(), share.memberId().value(), share.minorUnits());
        }

        try {
            jdbc.update("""
                    INSERT INTO expense_correction
                        (correction_id, expense_id, from_revision, to_revision, actor_member_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, correctionId, correction.expenseId().value(), correction.fromRevision(),
                    correction.toRevision(), correction.actor().value(),
                    Timestamp.from(correction.occurredAt()));
        } catch (DuplicateKeyException exception) {
            throw new ConcurrentGroupCorrectionException();
        }

        for (ObligationEntry entry : correction.reversalEntries()) {
            jdbc.update("""
                    INSERT INTO obligation_entry (expense_id, revision, group_id, stream_id,
                        debtor_member_id, creditor_member_id, minor_units, currency,
                        algorithm_version, entry_kind, correction_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REVERSAL', ?)
                    """, entry.expenseId().value(), entry.revision(), replacement.groupId().value(),
                    streamId, entry.debtor().value(), entry.creditor().value(), entry.minorUnits(),
                    entry.currency(), entry.algorithmVersion(), correctionId);
        }
        for (ObligationEntry entry : correction.replacementEntries()) {
            jdbc.update("""
                    INSERT INTO obligation_entry (expense_id, revision, group_id, stream_id,
                        debtor_member_id, creditor_member_id, minor_units, currency,
                        algorithm_version, entry_kind, correction_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CHARGE', NULL)
                    """, entry.expenseId().value(), entry.revision(), replacement.groupId().value(),
                    streamId, entry.debtor().value(), entry.creditor().value(), entry.minorUnits(),
                    entry.currency(), entry.algorithmVersion());
        }

        Long streamVersion = jdbc.queryForObject("""
                UPDATE financial_stream
                SET stream_version = stream_version + 1, updated_at = now()
                WHERE stream_id = ?
                RETURNING stream_version
                """, Long.class, streamId);
        if (streamVersion == null) {
            throw new IllegalStateException("financial stream is missing");
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
                """, streamId, replacement.currency(), streamVersion, streamId, streamId);
    }

    public static final class ConcurrentGroupCorrectionException extends RuntimeException {
        public ConcurrentGroupCorrectionException() {
            super("This expense revision has already been corrected.");
        }
    }
}
