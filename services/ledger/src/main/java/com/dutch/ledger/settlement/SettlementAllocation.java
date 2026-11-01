package com.dutch.ledger.settlement;

import com.dutch.ledger.expense.Money;
import com.dutch.ledger.group.MemberId;
import java.util.Objects;

/** The parent-owned portion of a Settlement applied to one Group or direct scope. */
public record SettlementAllocation(
        String scopeId,
        String currency,
        long minorUnits,
        MemberId payer,
        MemberId payee) {

    public SettlementAllocation {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId is required");
        }
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(payee, "payee");
        if (payer.equals(payee)) {
            throw new IllegalArgumentException("a settlement needs two distinct members");
        }
        if (minorUnits <= 0 || minorUnits > Money.MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("an allocation amount must be positive and bounded");
        }
        new Money(minorUnits, currency);
    }
}
