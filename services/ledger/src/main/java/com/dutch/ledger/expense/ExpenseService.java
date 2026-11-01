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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findById(new GroupId(groupId)).orElseThrow(GroupAccessDeniedException::new);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        if (!group.canCreateExpense(actor)) {
            throw new GroupForbiddenException();
        }
        if (!group.currency().equals(request.currency())) {
            throw new IllegalArgumentException("currency must match the group currency");
        }

        String requestHash = hash(request);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), COMMAND, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        if (!idempotency.reserve(actor.value(), COMMAND, idempotencyKey, requestHash)) {
            IdempotencyStore.Outcome raced = idempotency.find(actor.value(), COMMAND, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
            return replay(raced, requestHash);
        }

        Expense expense = build(group, request);
        expenses.save(expense);
        ExpenseView view = ExpenseView.from(expense);
        idempotency.complete(actor.value(), COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    private ExpenseView replay(IdempotencyStore.Outcome outcome, String requestHash) {
        if (!outcome.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            return objectMapper.readValue(outcome.responseJson(), ExpenseView.class);
        } catch (Exception exception) {
            throw new IllegalStateException("idempotent expense result is unavailable", exception);
        }
    }

    private Expense build(Group group, CreateExpenseRequest request) {
        if (request.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        List<PayerContribution> payers = Objects.requireNonNull(request.payers(), "payers").stream()
                .map(payer -> new PayerContribution(
                        new MemberId(payer.memberId()), payer.minorUnits()))
                .toList();
        List<MemberId> participants = request.participants() == null ? List.of()
                : request.participants().stream().map(MemberId::new).toList();
        Allocation allocation = switch (request.splitMethod()) {
            case EQUAL -> Allocation.equal(request.totalMinorUnits(), request.currency(), participants);
            case EXACT -> Allocation.exact(request.totalMinorUnits(), request.currency(),
                    Objects.requireNonNull(request.exactShares(), "exactShares").stream()
                            .map(share -> new ParticipantShare(
                                    new MemberId(share.memberId()), share.minorUnits()))
                            .toList());
            case PERCENTAGE -> Allocation.percentage(request.totalMinorUnits(), request.currency(),
                    participants, Objects.requireNonNull(request.percentageBasisPoints(),
                            "percentageBasisPoints"));
            default -> throw new IllegalArgumentException("unsupported split method");
        };
        return Expense.create(newId(), group.id(), request.description(), request.totalMinorUnits(),
                request.currency(), request.splitMethod(), payers, allocation.shares(),
                request.occurredAt());
    }

    private String hash(CreateExpenseRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(serialize(request).getBytes(StandardCharsets.UTF_8));
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
