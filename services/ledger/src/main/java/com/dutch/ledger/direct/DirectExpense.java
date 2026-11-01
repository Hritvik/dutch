package com.dutch.ledger.direct;

import com.dutch.ledger.expense.Allocation;
import com.dutch.ledger.expense.AllocationAlgorithmVersion;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.Money;
import com.dutch.ledger.expense.ObligationEntry;
import com.dutch.ledger.expense.ObligationMatcher;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;

/**
 * A one-off Expense outside any Group. It has the same exact payer/share
 * snapshot and Obligation Entries as a Group Expense, but each calculated entry
 * belongs to the canonical direct-pair stream for its two members.
 */
public record DirectExpense(
        ExpenseId id,
        String description,
        long totalMinorUnits,
        String currency,
        SplitMethod splitMethod,
        List<PayerContribution> payers,
        List<ParticipantShare> shares,
        Instant occurredAt,
        long revision) {

    private static final int MAX_DESCRIPTION = 200;
    private static final String ALGORITHM_VERSION =
            AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1;

    public DirectExpense {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (revision < 1) {
            throw new IllegalArgumentException("a revision starts at one");
        }
        if (description == null) {
            throw new IllegalArgumentException("description is required");
        }
        String trimmedDescription = description.trim();
        if (trimmedDescription.isEmpty() || trimmedDescription.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description must be between 1 and 200 characters");
        }
        new Money(totalMinorUnits, currency);
        Objects.requireNonNull(splitMethod, "splitMethod");
        List<PayerContribution> payerSnapshot = validatePayers(payers, totalMinorUnits);
        List<ParticipantShare> shareSnapshot = validateShares(shares, totalMinorUnits);

        Set<MemberId> involved = new HashSet<>();
        payerSnapshot.forEach(payer -> involved.add(payer.memberId()));
        shareSnapshot.forEach(share -> involved.add(share.memberId()));
        if (involved.size() < 2) {
            throw new IllegalArgumentException("a Direct Expense needs at least two members");
        }

        description = trimmedDescription;
        payers = payerSnapshot;
        shares = shareSnapshot;
    }

    public static DirectExpense create(ExpenseId id, String description, long totalMinorUnits,
                                       String currency, SplitMethod splitMethod,
                                       List<PayerContribution> payers,
                                       List<ParticipantShare> shares, Instant occurredAt) {
        return createAtRevision(id, description, totalMinorUnits, currency, splitMethod,
                payers, shares, occurredAt, 1);
    }

    public static DirectExpense createAtRevision(ExpenseId id, String description,
                                                 long totalMinorUnits, String currency,
                                                 SplitMethod splitMethod,
                                                 List<PayerContribution> payers,
                                                 List<ParticipantShare> shares,
                                                 Instant occurredAt, long revision) {
        return new DirectExpense(id, description, totalMinorUnits, currency, splitMethod,
                payers, shares, occurredAt, revision);
    }

    /** Builds a revision from an exact split command. */
    public static DirectExpense allocate(ExpenseId id, String description, long totalMinorUnits,
                                         String currency, SplitMethod splitMethod,
                                         List<PayerContribution> payers,
                                         Allocation allocation, Instant occurredAt) {
        if (!allocation.currency().equals(currency)) {
            throw new IllegalArgumentException("the allocation must use the expense currency");
        }
        return create(id, description, totalMinorUnits, currency, splitMethod, payers,
                allocation.shares(), occurredAt);
    }

    public List<ObligationEntry> obligationEntries() {
        return ObligationMatcher.entries(id, revision, currency, payers, shares, ALGORITHM_VERSION);
    }

    public SortedMap<MemberId, Long> netPositions() {
        return ObligationMatcher.netPositions(payers, shares);
    }

    public String algorithmVersion() {
        return ALGORITHM_VERSION;
    }

    private static List<PayerContribution> validatePayers(List<PayerContribution> payers,
                                                          long totalMinorUnits) {
        Objects.requireNonNull(payers, "payers");
        if (payers.isEmpty()) {
            throw new IllegalArgumentException("a Direct Expense needs at least one payer");
        }
        Set<MemberId> seen = new HashSet<>();
        long sum = 0;
        for (PayerContribution payer : payers) {
            Objects.requireNonNull(payer, "payer");
            if (!seen.add(payer.memberId())) {
                throw new IllegalArgumentException("a payer may appear only once");
            }
            if (payer.minorUnits() <= 0) {
                throw new IllegalArgumentException("a payer contribution must be positive");
            }
            sum = Math.addExact(sum, payer.minorUnits());
        }
        if (sum != totalMinorUnits) {
            throw new IllegalArgumentException("payer contributions must equal the total");
        }
        return List.copyOf(payers);
    }

    private static List<ParticipantShare> validateShares(List<ParticipantShare> shares,
                                                         long totalMinorUnits) {
        Objects.requireNonNull(shares, "shares");
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("a Direct Expense needs at least one participant");
        }
        Set<MemberId> seen = new HashSet<>();
        long sum = 0;
        for (ParticipantShare share : shares) {
            Objects.requireNonNull(share, "share");
            if (!seen.add(share.memberId())) {
                throw new IllegalArgumentException("a participant may appear only once");
            }
            if (share.minorUnits() < 0) {
                throw new IllegalArgumentException("a participant share cannot be negative");
            }
            sum = Math.addExact(sum, share.minorUnits());
        }
        if (sum != totalMinorUnits) {
            throw new IllegalArgumentException("participant shares must equal the total");
        }
        return List.copyOf(shares);
    }
}
