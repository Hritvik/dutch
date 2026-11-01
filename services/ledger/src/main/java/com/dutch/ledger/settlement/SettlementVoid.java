package com.dutch.ledger.settlement;

import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** An immutable parent action that exactly reverses every active allocation. */
public record SettlementVoid(
        SettlementId settlementId,
        List<SettlementAllocation> reversedAllocations,
        MemberId actor,
        Instant occurredAt) {

    public SettlementVoid {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        reversedAllocations = List.copyOf(reversedAllocations);
    }

    public static SettlementVoid of(Settlement settlement, MemberId actor, Instant occurredAt) {
        Objects.requireNonNull(settlement, "settlement");
        List<SettlementAllocation> reversed = new ArrayList<>();
        for (SettlementAllocation allocation : settlement.allocations()) {
            reversed.add(new SettlementAllocation(
                    allocation.scopeId(), allocation.currency(), allocation.minorUnits(),
                    allocation.payee(), allocation.payer()));
        }
        return new SettlementVoid(settlement.id(), reversed, actor, occurredAt);
    }
}
