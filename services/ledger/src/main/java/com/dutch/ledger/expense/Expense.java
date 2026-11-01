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
        return createAtRevision(id, groupId, description, totalMinorUnits, currency, splitMethod,
                payers, shares, occurredAt, 1);
    }

    public static Expense createAtRevision(ExpenseId id, GroupId groupId, String description,
                                           long totalMinorUnits, String currency, SplitMethod splitMethod,
                                           List<PayerContribution> payers, List<ParticipantShare> shares,
                                           Instant occurredAt, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
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
                payerSnapshot, shareSnapshot, occurredAt, revision,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }

    public List<ObligationEntry> obligationEntries() {
        return ObligationMatcher.entries(id, revision, currency, payers, shares, algorithmVersion);
    }

    /** Positive means the member is owed; negative means the member owes. */
    public SortedMap<MemberId, Long> netPositions() {
        return ObligationMatcher.netPositions(payers, shares);
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
