package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Deterministic largest-debtor/largest-creditor matching shared by Group and
 * Direct Expenses. Ties break by Member ID so entries are stable evidence.
 */
public final class ObligationMatcher {

    private ObligationMatcher() {
    }

    public static List<ObligationEntry> entries(ExpenseId id, long revision, String currency,
                                                List<PayerContribution> payers,
                                                List<ParticipantShare> shares,
                                                String algorithmVersion) {
        SortedMap<MemberId, Long> positions = new TreeMap<>(netPositions(payers, shares));
        List<Position> debtors = new ArrayList<>();
        List<Position> creditors = new ArrayList<>();
        for (var entry : positions.entrySet()) {
            long amount = entry.getValue();
            if (amount < 0) {
                debtors.add(new Position(entry.getKey(), -amount));
            } else if (amount > 0) {
                creditors.add(new Position(entry.getKey(), amount));
            }
        }
        debtors.sort(Comparator.comparingLong(Position::remaining).reversed()
                .thenComparing(Position::memberId));
        creditors.sort(Comparator.comparingLong(Position::remaining).reversed()
                .thenComparing(Position::memberId));

        List<ObligationEntry> entries = new ArrayList<>();
        int debtorIndex = 0;
        int creditorIndex = 0;
        while (debtorIndex < debtors.size() && creditorIndex < creditors.size()) {
            Position debtor = debtors.get(debtorIndex);
            Position creditor = creditors.get(creditorIndex);
            long amount = Math.min(debtor.remaining(), creditor.remaining());
            if (amount > 0) {
                entries.add(new ObligationEntry(id, revision, debtor.memberId(), creditor.memberId(),
                        amount, currency, algorithmVersion));
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
        return List.copyOf(entries);
    }

    /** Positive means the member is owed; negative means the member owes. */
    public static SortedMap<MemberId, Long> netPositions(List<PayerContribution> payers,
                                                         List<ParticipantShare> shares) {
        SortedMap<MemberId, Long> positions = new TreeMap<>();
        for (PayerContribution payer : payers) {
            positions.merge(payer.memberId(), payer.minorUnits(), Math::addExact);
        }
        for (ParticipantShare share : shares) {
            positions.merge(share.memberId(), -share.minorUnits(), Math::addExact);
        }
        long sum = 0;
        for (long value : positions.values()) {
            sum = Math.addExact(sum, value);
        }
        if (sum != 0) {
            throw new IllegalStateException("obligation positions must net to zero");
        }
        positions.entrySet().removeIf(entry -> entry.getValue() == 0);
        return positions;
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
