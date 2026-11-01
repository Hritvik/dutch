package com.dutch.ledger.expense;

import java.time.Instant;
import java.util.List;

/** The explicit inputs for one Expense command. */
public record CreateExpenseRequest(
        String description,
        long totalMinorUnits,
        String currency,
        SplitMethod splitMethod,
        List<PayerRequest> payers,
        List<String> participants,
        List<ShareRequest> exactShares,
        List<Integer> percentageBasisPoints,
        Instant occurredAt) {

    public record PayerRequest(String memberId, long minorUnits) {
    }

    public record ShareRequest(String memberId, long minorUnits) {
    }
}
