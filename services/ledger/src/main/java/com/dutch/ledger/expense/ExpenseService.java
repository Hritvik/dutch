package com.dutch.ledger.expense;

import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupForbiddenException;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {

    static final String COMMAND = "create-expense";

    private final ExpenseRepository expenses;
    private final GroupRepository groups;
    private final MemberResolver members;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public ExpenseService(ExpenseRepository expenses, GroupRepository groups, MemberResolver members,
                          IdempotencyStore idempotency, ObjectMapper objectMapper) {
        this.expenses = expenses;
        this.groups = groups;
        this.members = members;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExpenseView create(String appSubject, String groupId, String idempotencyKey,
                              CreateExpenseRequest request) {
        Objects.requireNonNull(request, "request");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and bounded");
        }
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findByIdForUpdate(new GroupId(groupId)).orElseThrow(GroupAccessDeniedException::new);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        if (!group.canCreateExpense(actor)) {
            throw new GroupForbiddenException();
        }
        if (!group.currency().equals(request.currency())) {
            throw new IllegalArgumentException("currency must match the group currency");
        }

        String requestHash = hash(group.id().value(), request);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), COMMAND, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, group.id().value());
        }
        if (!idempotency.reserve(actor.value(), COMMAND, idempotencyKey, requestHash)) {
            IdempotencyStore.Outcome raced = idempotency.find(actor.value(), COMMAND, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
            return replay(raced, requestHash, group.id().value());
        }

        Expense expense = build(group, request);
        expenses.save(expense);
        ExpenseView view = ExpenseView.from(expense);
        idempotency.complete(actor.value(), COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    private ExpenseView replay(IdempotencyStore.Outcome outcome, String requestHash, String groupId) {
        if (!outcome.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            ExpenseView view = objectMapper.readValue(outcome.responseJson(), ExpenseView.class);
            if (!groupId.equals(view.groupId())) {
                throw new IdempotencyConflictException();
            }
            return view;
        } catch (IdempotencyConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("idempotent expense result is unavailable", exception);
        }
    }

    private Expense build(Group group, CreateExpenseRequest request) {
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
        List<PayerContribution> payers = request.payers().stream()
                .map(payer -> {
                    if (payer == null || payer.memberId() == null) {
                        throw new IllegalArgumentException("payer memberId is required");
                    }
                    return new PayerContribution(new MemberId(payer.memberId()), payer.minorUnits());
                })
                .toList();
        List<MemberId> participants = request.participants() == null ? List.of()
                : request.participants().stream().map(MemberId::new).toList();
        if (request.splitMethod() == null) {
            throw new IllegalArgumentException("splitMethod is required");
        }
        Allocation allocation = switch (request.splitMethod()) {
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
        Set<MemberId> involved = new HashSet<>();
        payers.forEach(payer -> involved.add(payer.memberId()));
        allocation.shares().forEach(share -> involved.add(share.memberId()));
        for (MemberId member : involved) {
            if (!group.canBeAllocated(member)) {
                throw new IllegalArgumentException("a payer or participant is not allocatable in this group");
            }
        }
        return Expense.create(newId(), group.id(), request.description(), request.totalMinorUnits(),
                request.currency(), request.splitMethod(), payers, allocation.shares(),
                request.occurredAt());
    }

    private String hash(String groupId, CreateExpenseRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((groupId + "|" + serialize(request)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("could not serialize the expense command", exception);
        }
    }

    private static ExpenseId newId() {
        return new ExpenseId("exp_" + UUID.randomUUID().toString().replace("-", ""));
    }
}
