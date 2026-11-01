package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Minimum-transfer recommendation for one currency. It never mutates
 * Obligation Entries or balances; it is a rebuildable projection.
 */
public record SimplifiedSettlementPlan(
        String currency,
        List<Transfer> transfers,
        String algorithmVersion) {

    public SimplifiedSettlementPlan {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        transfers = List.copyOf(Objects.requireNonNull(transfers, "transfers"));
    }

    public static SimplifiedSettlementPlan from(String currency, Map<MemberId, Long> balances) {
        Objects.requireNonNull(balances, "balances");
        List<Position> debtors = new ArrayList<>();
        List<Position> creditors = new ArrayList<>();
        long sum = 0;
        for (Map.Entry<MemberId, Long> entry : new TreeMap<>(balances).entrySet()) {
            long amount = entry.getValue();
            sum = Math.addExact(sum, amount);
            if (amount < 0) {
                debtors.add(new Position(entry.getKey(), -amount));
            } else if (amount > 0) {
                creditors.add(new Position(entry.getKey(), amount));
            }
        }
        if (sum != 0) {
            throw new IllegalArgumentException("balances must net to zero");
        }
        debtors.sort(Comparator.comparingLong(Position::remaining).reversed()
                .thenComparing(Position::memberId));
        creditors.sort(Comparator.comparingLong(Position::remaining).reversed()
                .thenComparing(Position::memberId));

        List<Transfer> transfers = new ArrayList<>();
        int debtorIndex = 0;
        int creditorIndex = 0;
        while (debtorIndex < debtors.size() && creditorIndex < creditors.size()) {
            Position debtor = debtors.get(debtorIndex);
            Position creditor = creditors.get(creditorIndex);
            long amount = Math.min(debtor.remaining(), creditor.remaining());
            if (amount > 0) {
                transfers.add(new Transfer(debtor.memberId(), creditor.memberId(), amount));
            }
            debtor.consume(amount);
            creditor.consume(amount);
            if (debtor.remaining() == 0) {
                debtorIndex++;
            }
            if (creditor.remaining() == 0) {
                creditorIndex++;
            }
        }
        return new SimplifiedSettlementPlan(currency, transfers,
                AllocationAlgorithmVersion.SIMPLIFIED_PLAN_V1);
    }

    public record Transfer(MemberId from, MemberId to, long minorUnits) {
        public Transfer {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("a transfer needs two distinct members");
            }
            if (minorUnits <= 0) {
                throw new IllegalArgumentException("a transfer amount must be positive");
            }
        }
    }

    private static final class Position {
        private final MemberId memberId;
        private long remaining;

        private Position(MemberId memberId, long amount) {
            this.memberId = memberId;
            this.remaining = amount;
        }

        private MemberId memberId() {
            return memberId;
        }

        private long remaining() {
            return remaining;
        }

        private void consume(long amount) {
            remaining -= amount;
        }
    }
}
