package com.dutch.ledger.settlement;

import com.dutch.ledger.expense.Money;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** An externally completed payment, allocated across one or more scopes by its parent. */
public record Settlement(
        SettlementId id,
        MemberId payer,
        MemberId payee,
        long totalMinorUnits,
        String currency,
        List<SettlementAllocation> allocations,
        Instant occurredAt) {

    public Settlement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(payee, "payee");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (payer.equals(payee)) {
            throw new IllegalArgumentException("a settlement needs two distinct members");
        }
        if (totalMinorUnits <= 0) {
            throw new IllegalArgumentException("a settlement total must be positive");
        }
        new Money(totalMinorUnits, currency);
        Objects.requireNonNull(allocations, "allocations");
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("a settlement needs at least one allocation");
        }
        long sum = 0;
        Set<String> scopes = new HashSet<>();
        for (SettlementAllocation allocation : allocations) {
            if (!allocation.currency().equals(currency)) {
                throw new IllegalArgumentException("allocations must use the settlement currency");
            }
            if (!allocation.payer().equals(payer) || !allocation.payee().equals(payee)) {
                throw new IllegalArgumentException("allocations must match the parent payer and payee");
            }
            if (!scopes.add(allocation.scopeId())) {
                throw new IllegalArgumentException("allocation scopes must be unique");
            }
            sum = Math.addExact(sum, allocation.minorUnits());
        }
        if (sum != totalMinorUnits) {
            throw new IllegalArgumentException("allocations must sum exactly to the settlement total");
        }
        allocations = List.copyOf(allocations);
    }

    public List<SettlementAllocation> allocations() {
        return List.copyOf(allocations);
    }
}
