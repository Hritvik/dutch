package com.dutch.ledger.direct;

import com.dutch.ledger.group.MemberId;
import java.util.Objects;

/**
 * Canonical direct-pair/currency stream identity. Ordering the two Member IDs
 * makes the identity independent of who created the record or which side paid.
 */
public final class DirectScope {

    private DirectScope() {
    }

    public static String streamId(MemberId first, MemberId second, String currency) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a direct scope needs two distinct members");
        }
        new com.dutch.ledger.expense.Money(1, currency);
        MemberId lower = first.compareTo(second) <= 0 ? first : second;
        MemberId upper = first.compareTo(second) <= 0 ? second : first;
        return "direct:" + lower.value() + ":" + upper.value() + ":" + currency;
    }
}
