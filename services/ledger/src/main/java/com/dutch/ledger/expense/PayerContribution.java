package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.Objects;

/** One member's exact payment toward an Expense. Always positive. */
public record PayerContribution(MemberId memberId, long minorUnits) {

    public PayerContribution {
        Objects.requireNonNull(memberId, "memberId");
        if (minorUnits <= 0) {
            throw new IllegalArgumentException("a payer contribution must be positive");
        }
        if (minorUnits > Money.MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("a payer contribution is outside the supported bounds");
        }
    }
}
