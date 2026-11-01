package com.dutch.ledger.direct;

import com.dutch.ledger.expense.ObligationEntry;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import java.time.Instant;
import java.util.List;

public record DirectExpenseView(
        String id,
        String description,
        long totalMinorUnits,
        String currency,
        String splitMethod,
        List<PayerContribution> payers,
        List<ParticipantShare> shares,
        List<EntryView> entries,
        List<String> streamIds,
        Instant occurredAt,
        long revision) {

    public record EntryView(String expenseId, long revision, String debtor, String creditor,
                            long minorUnits, String currency, String algorithmVersion) {
    }

    public static DirectExpenseView from(DirectExpense expense) {
        List<EntryView> entries = expense.obligationEntries().stream()
                .map(DirectExpenseView::entry)
                .toList();
        List<String> streams = expense.obligationEntries().stream()
                .map(entry -> DirectScope.streamId(entry.debtor(), entry.creditor(),
                        expense.currency()))
                .distinct()
                .sorted()
                .toList();
        return new DirectExpenseView(expense.id().value(), expense.description(),
                expense.totalMinorUnits(), expense.currency(), expense.splitMethod().name(),
                expense.payers(), expense.shares(), entries, streams, expense.occurredAt(),
                expense.revision());
    }

    private static EntryView entry(ObligationEntry entry) {
        return new EntryView(entry.expenseId().value(), entry.revision(), entry.debtor().value(),
                entry.creditor().value(), entry.minorUnits(), entry.currency(),
                entry.algorithmVersion());
    }
}
