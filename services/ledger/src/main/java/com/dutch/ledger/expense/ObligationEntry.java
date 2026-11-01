package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.Objects;

/** One immutable amount one member owes another for a specific Expense revision. */
public record ObligationEntry(
        ExpenseId expenseId,
        long revision,
        MemberId debtor,
        MemberId creditor,
        long minorUnits,
        String currency,
        String algorithmVersion) {

    public ObligationEntry {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(debtor, "debtor");
        Objects.requireNonNull(creditor, "creditor");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        if (debtor.equals(creditor)) {
            throw new IllegalArgumentException("an obligation cannot be owed to oneself");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (minorUnits <= 0 || minorUnits > Money.MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("an obligation amount must be positive and bounded");
        }
        new Money(minorUnits, currency);
    }
}
