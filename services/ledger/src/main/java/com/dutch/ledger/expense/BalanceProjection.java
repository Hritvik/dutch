package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** A rebuildable fold of effective Obligation Entries within one currency. */
public record BalanceProjection(
        String currency,
        SortedMap<MemberId, Long> balances,
        String algorithmVersion) {

    public BalanceProjection {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        SortedMap<MemberId, Long> snapshot = new TreeMap<>();
        long sum = 0;
        for (var entry : Objects.requireNonNull(balances, "balances").entrySet()) {
            if (entry.getValue() != 0) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
            sum = Math.addExact(sum, entry.getValue());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("balances must net to zero");
        }
        balances = Collections.unmodifiableSortedMap(snapshot);
    }

    public static BalanceProjection from(List<ObligationEntry> entries, String currency) {
        Objects.requireNonNull(entries, "entries");
        SortedMap<MemberId, Long> balances = new TreeMap<>();
        for (ObligationEntry entry : entries) {
            if (!currency.equals(entry.currency())) {
                throw new IllegalArgumentException("entries must use one currency");
            }
            balances.merge(entry.debtor(), -entry.minorUnits(), Math::addExact);
            balances.merge(entry.creditor(), entry.minorUnits(), Math::addExact);
        }
        return new BalanceProjection(currency, balances,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }
}
