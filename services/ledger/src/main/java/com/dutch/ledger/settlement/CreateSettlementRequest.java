package com.dutch.ledger.settlement;

import java.time.Instant;
import java.util.List;

/** A completed external payment, optionally split across this group's scopes. */
public record CreateSettlementRequest(
        String payerMemberId,
        String payeeMemberId,
        long totalMinorUnits,
        String currency,
        Instant occurredAt,
        List<AllocationRequest> allocations) {

    public record AllocationRequest(String scopeId, long minorUnits) {
    }
}
