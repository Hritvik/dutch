package com.dutch.ledger.expense;

import java.time.Instant;
import java.util.List;

/** The immutable Expense revision plus its derived entries. */
public record ExpenseView(
        String id,
        String groupId,
        String description,
        long totalMinorUnits,
        String currency,
        SplitMethod splitMethod,
        List<PayerContribution> payers,
        List<ParticipantShare> shares,
        List<ObligationEntry> entries,
        Instant occurredAt,
        long revision) {

    public ExpenseView {
        payers = List.copyOf(payers);
        shares = List.copyOf(shares);
        entries = List.copyOf(entries);
    }

    public static ExpenseView from(Expense expense) {
        return new ExpenseView(
                expense.id().value(),
                expense.groupId().value(),
                expense.description(),
                expense.totalMinorUnits(),
                expense.currency(),
                expense.splitMethod(),
                expense.payers(),
                expense.shares(),
                expense.obligationEntries(),
                expense.occurredAt(),
                expense.revision());
    }
}
