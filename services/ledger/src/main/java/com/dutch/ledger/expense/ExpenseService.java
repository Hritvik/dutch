package com.dutch.ledger.expense;

import com.dutch.ledger.activity.ActivityAction;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupForbiddenException;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import com.dutch.ledger.sync.SyncJournal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
    private final ActivityRepository activity;
    private final SyncJournal journal;
    private final ObjectMapper objectMapper;

    public ExpenseService(ExpenseRepository expenses, GroupRepository groups, MemberResolver members,
                          IdempotencyStore idempotency, ActivityRepository activity,
                          SyncJournal journal, ObjectMapper objectMapper) {
        this.expenses = expenses;
        this.groups = groups;
        this.members = members;
        this.idempotency = idempotency;
        this.activity = activity;
        this.journal = journal;
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
        Instant now = Instant.now();
        java.util.Map<String, String> details = new java.util.HashMap<>();
        String safeDescription = ActivityEntry.contactFree(expense.description());
        if (safeDescription != null) {
            details.put("description", safeDescription);
        }
        details.put("totalMinorUnits", Long.toString(expense.totalMinorUnits()));
        details.put("currency", expense.currency());
        activity.append(new ActivityEntry("act_" + UUID.randomUUID().toString().replace("-", ""),
                "group:" + group.id().value() + ":" + group.currency(), actor,
                ActivityAction.EXPENSE_CREATED, expense.id().value(), expense.revision(), now,
                details));
        ExpenseView view = ExpenseView.from(expense);
        journal.append(group.memberships().stream()
                        .filter(com.dutch.ledger.group.Membership::hasAppAccess)
                        .map(com.dutch.ledger.group.Membership::memberId).toList(),
                "EXPENSE", expense.id().value(), "upsert", expense.revision(),
                objectMapper.convertValue(view, java.util.Map.class), null, now);
        idempotency.complete(actor.value(), COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    @Transactional(readOnly = true)
    public ExpenseView read(String appSubject, String groupId, String expenseId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findById(new GroupId(groupId)).orElseThrow(GroupAccessDeniedException::new);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        Expense expense = expenses.findExpense(new ExpenseId(expenseId))
                .filter(found -> found.groupId().equals(group.id()))
                .orElseThrow(GroupAccessDeniedException::new);
        return ExpenseView.from(expense);
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
        return ExpenseCommandFactory.create(group, newId(), 1, request);
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
