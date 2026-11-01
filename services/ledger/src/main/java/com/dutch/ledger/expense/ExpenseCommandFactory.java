package com.dutch.ledger.expense;

import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.MemberId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds a validated Expense revision from a command payload. */
public final class ExpenseCommandFactory {

    private ExpenseCommandFactory() {
    }

    public static Expense create(Group group, ExpenseId id, long revision, CreateExpenseRequest request) {
        requireBasics(request);
        if (!group.currency().equals(request.currency())) {
            throw new IllegalArgumentException("currency must match the group currency");
        }
        List<PayerContribution> payers = payers(request);
        Allocation allocation = allocation(request);
        for (MemberId member : involved(payers, allocation)) {
            if (!group.canBeAllocated(member)) {
                throw new IllegalArgumentException("a payer or participant is not allocatable in this group");
            }
        }
        return Expense.createAtRevision(id, group.id(), request.description(), request.totalMinorUnits(),
                request.currency(), request.splitMethod(), payers, allocation.shares(),
                request.occurredAt(), revision);
    }

    public static void requireBasics(CreateExpenseRequest request) {
        if (request.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (request.description() == null) {
            throw new IllegalArgumentException("description is required");
        }
        if (request.currency() == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (request.payers() == null) {
            throw new IllegalArgumentException("payers are required");
        }
    }

    public static List<PayerContribution> payers(CreateExpenseRequest request) {
        return request.payers().stream()
                .map(payer -> {
                    if (payer == null || payer.memberId() == null) {
                        throw new IllegalArgumentException("payer memberId is required");
                    }
                    return new PayerContribution(new MemberId(payer.memberId()), payer.minorUnits());
                })
                .toList();
    }

    public static Allocation allocation(CreateExpenseRequest request) {
        List<MemberId> participants = request.participants() == null ? List.of()
                : request.participants().stream().map(MemberId::new).toList();
        if (request.splitMethod() == null) {
            throw new IllegalArgumentException("splitMethod is required");
        }
        return switch (request.splitMethod()) {
            case EQUAL -> Allocation.equal(request.totalMinorUnits(), request.currency(), participants);
            case EXACT -> {
                if (request.exactShares() == null) {
                    throw new IllegalArgumentException("exactShares is required for EXACT");
                }
                yield Allocation.exact(request.totalMinorUnits(), request.currency(),
                        request.exactShares().stream()
                                .map(share -> {
                                    if (share == null || share.memberId() == null) {
                                        throw new IllegalArgumentException("share memberId is required");
                                    }
                                    return new ParticipantShare(
                                            new MemberId(share.memberId()), share.minorUnits());
                                })
                                .toList());
            }
            case PERCENTAGE -> {
                if (request.percentageBasisPoints() == null) {
                    throw new IllegalArgumentException("percentageBasisPoints is required for PERCENTAGE");
                }
                yield Allocation.percentage(request.totalMinorUnits(), request.currency(),
                        participants, request.percentageBasisPoints());
            }
        };
    }

    public static Set<MemberId> involved(List<PayerContribution> payers, Allocation allocation) {
        Set<MemberId> involved = new HashSet<>();
        payers.forEach(payer -> involved.add(payer.memberId()));
        allocation.shares().forEach(share -> involved.add(share.memberId()));
        return involved;
    }
}
