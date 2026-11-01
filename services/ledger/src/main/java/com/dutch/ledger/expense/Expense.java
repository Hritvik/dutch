package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An immutable Expense revision. It stores exact payer contributions and
 * participant shares; Obligation Entries are derived deterministically from
 * each member's contribution minus share position.
 */
public final class Expense {

    private static final int MAX_DESCRIPTION = 200;

    private final ExpenseId id;
    private final GroupId groupId;
    private final String description;
    private final long totalMinorUnits;
    private final String currency;
    private final SplitMethod splitMethod;
    private final List<PayerContribution> payers;
    private final List<ParticipantShare> shares;
    private final Instant occurredAt;
    private final long revision;
    private final String algorithmVersion;

    private Expense(ExpenseId id, GroupId groupId, String description, long totalMinorUnits,
                    String currency, SplitMethod splitMethod, List<PayerContribution> payers,
                    List<ParticipantShare> shares, Instant occurredAt, long revision,
                    String algorithmVersion) {
        this.id = id;
        this.groupId = groupId;
        this.description = description;
        this.totalMinorUnits = totalMinorUnits;
        this.currency = currency;
        this.splitMethod = splitMethod;
        this.payers = payers;
        this.shares = shares;
        this.occurredAt = occurredAt;
        this.revision = revision;
        this.algorithmVersion = algorithmVersion;
    }

    public static Expense create(ExpenseId id, GroupId groupId, String description, long totalMinorUnits,
                                 String currency, SplitMethod splitMethod,
                                 List<PayerContribution> payers, List<ParticipantShare> shares,
                                 Instant occurredAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(splitMethod, "splitMethod");
        Objects.requireNonNull(occurredAt, "occurredAt");
        new Money(totalMinorUnits, currency);
        if (totalMinorUnits <= 0) {
            throw new IllegalArgumentException("an expense total must be positive");
        }
        String trimmedDescription = Objects.requireNonNull(description, "description").trim();
        if (trimmedDescription.isEmpty() || trimmedDescription.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description must be between 1 and 200 characters");
        }
        List<PayerContribution> payerSnapshot = validatePayers(payers, totalMinorUnits);
        List<ParticipantShare> shareSnapshot = validateShares(shares, totalMinorUnits);
        return new Expense(id, groupId, trimmedDescription, totalMinorUnits, currency, splitMethod,
                payerSnapshot, shareSnapshot, occurredAt, 1,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }

    public List<ObligationEntry> obligationEntries() {
        SortedMap<MemberId, Long> positions = new TreeMap<>(netPositions());
        List<Position> debtors = new ArrayList<>();
        List<Position> creditors = new ArrayList<>();
        for (Map.Entry<MemberId, Long> entry : positions.entrySet()) {
            long amount = entry.getValue();
            if (amount < 0) {
                debtors.add(new Position(entry.getKey(), -amount));
            } else if (amount > 0) {
                creditors.add(new Position(entry.getKey(), amount));
            }
        }
        debtors.sort(Comparator.comparingLong(Position::amount).reversed()
                .thenComparing(Position::memberId));
        creditors.sort(Comparator.comparingLong(Position::amount).reversed()
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
    public SortedMap<MemberId, Long> netPositions() {
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
            throw new IllegalStateException("expense positions must net to zero");
        }
        positions.entrySet().removeIf(entry -> entry.getValue() == 0);
        return positions;
    }

    public ExpenseId id() {
        return id;
    }

    public GroupId groupId() {
        return groupId;
    }

    public String description() {
        return description;
    }

    public long totalMinorUnits() {
        return totalMinorUnits;
    }

    public String currency() {
        return currency;
    }

    public SplitMethod splitMethod() {
        return splitMethod;
    }

    public List<PayerContribution> payers() {
        return payers;
    }

    public List<ParticipantShare> shares() {
        return shares;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public long revision() {
        return revision;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }

    private static List<PayerContribution> validatePayers(List<PayerContribution> payers, long total) {
        Objects.requireNonNull(payers, "payers");
        if (payers.isEmpty()) {
            throw new IllegalArgumentException("an expense needs at least one payer");
        }
        long sum = 0;
        Set<MemberId> seen = new HashSet<>();
        for (PayerContribution payer : payers) {
            if (!seen.add(payer.memberId())) {
                throw new IllegalArgumentException("payers must be unique");
            }
            sum = Math.addExact(sum, payer.minorUnits());
        }
        if (sum != total) {
            throw new IllegalArgumentException("payer contributions must sum exactly to the total");
        }
        return List.copyOf(payers);
    }

    private static List<ParticipantShare> validateShares(List<ParticipantShare> shares, long total) {
        Objects.requireNonNull(shares, "shares");
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("an expense needs at least one participant");
        }
        long sum = 0;
        Set<MemberId> seen = new HashSet<>();
        for (ParticipantShare share : shares) {
            if (!seen.add(share.memberId())) {
                throw new IllegalArgumentException("participants must be unique");
            }
            sum = Math.addExact(sum, share.minorUnits());
        }
        if (sum != total) {
            throw new IllegalArgumentException("participant shares must sum exactly to the total");
        }
        return List.copyOf(shares);
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

        private long amount() {
            return remaining;
        }

        private long remaining() {
            return remaining;
        }

        private void consume(long amount) {
            remaining -= amount;
        }
    }
}
