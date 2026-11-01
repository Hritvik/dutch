package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.Objects;

/** One participant's exact allocated share of an Expense. Always positive. */
public record ParticipantShare(MemberId memberId, long minorUnits) {

    public ParticipantShare {
        Objects.requireNonNull(memberId, "memberId");
        if (minorUnits <= 0) {
            throw new IllegalArgumentException("a participant share must be positive");
        }
        if (minorUnits > Money.MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("a participant share is outside the supported bounds");
        }
    }
}
