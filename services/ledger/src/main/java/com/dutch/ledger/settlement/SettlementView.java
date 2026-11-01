package com.dutch.ledger.settlement;

import java.time.Instant;
import java.util.List;

public record SettlementView(
        String id,
        String groupId,
        String payer,
        String payee,
        long totalMinorUnits,
        String currency,
        String status,
        List<AllocationView> allocations,
        Instant occurredAt) {

    public record AllocationView(String scopeId, String currency, long minorUnits,
                                 String payer, String payee) {
    }

    public static SettlementView from(Settlement settlement, String groupId, String status) {
        List<AllocationView> allocations = settlement.allocations().stream()
                .map(allocation -> new AllocationView(allocation.scopeId(), allocation.currency(),
                        allocation.minorUnits(), allocation.payer().value(), allocation.payee().value()))
                .toList();
        return new SettlementView(settlement.id().value(), groupId, settlement.payer().value(),
                settlement.payee().value(), settlement.totalMinorUnits(), settlement.currency(),
                status, allocations, settlement.occurredAt());
    }
}
