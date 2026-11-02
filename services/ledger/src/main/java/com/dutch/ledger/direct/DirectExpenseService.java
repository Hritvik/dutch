package com.dutch.ledger.direct;

import com.dutch.ledger.activity.ActivityAction;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.expense.Allocation;
import com.dutch.ledger.expense.CreateExpenseRequest;
import com.dutch.ledger.expense.ExpenseCommandFactory;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.IdempotencyConflictException;
import com.dutch.ledger.expense.IdempotencyStore;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupForbiddenException;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.MemberDirectory;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import com.dutch.ledger.sync.SyncJournal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectExpenseService {

    static final String COMMAND = "create-direct-expense";

    private final DirectExpenseRepository expenses;
    private final MemberResolver members;
    private final MemberDirectory directory;
    private final GroupRepository groups;
    private final IdempotencyStore idempotency;
    private final ActivityRepository activity;
    private final SyncJournal journal;
    private final ObjectMapper objectMapper;

    public DirectExpenseService(DirectExpenseRepository expenses, MemberResolver members,
                                MemberDirectory directory, GroupRepository groups,
                                IdempotencyStore idempotency, ActivityRepository activity,
                                SyncJournal journal, ObjectMapper objectMapper) {
        this.expenses = expenses;
        this.members = members;
        this.directory = directory;
        this.groups = groups;
        this.idempotency = idempotency;
        this.activity = activity;
        this.journal = journal;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DirectExpenseView create(String appSubject, String idempotencyKey,
                                    CreateExpenseRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and bounded");
        }
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        ExpenseCommandFactory.requireBasics(request);
        List<PayerContribution> payers = ExpenseCommandFactory.payers(request);
        Allocation allocation = ExpenseCommandFactory.allocation(request);
        Set<MemberId> involved = ExpenseCommandFactory.involved(payers, allocation);
        if (!involved.contains(actor)) {
            throw new GroupForbiddenException();
        }
        for (MemberId member : involved) {
            if (!directory.isActive(member)) {
                throw new IllegalArgumentException("every direct member must be active");
            }
            if (!member.equals(actor) && !sharesActiveGroup(actor, member)) {
                throw new GroupForbiddenException();
            }
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

        ExpenseId id = new ExpenseId("exp_" + UUID.randomUUID().toString().replace("-", ""));
        DirectExpense expense = DirectExpense.create(id, request.description(),
                request.totalMinorUnits(), request.currency(), request.splitMethod(), payers,
                allocation.shares(), request.occurredAt());
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
                scopeId(expense), actor, ActivityAction.EXPENSE_CREATED, expense.id().value(),
                expense.revision(), now, details));

        DirectExpenseView view = DirectExpenseView.from(expense);
        journal.append(new java.util.TreeSet<>(involved), "DIRECT_EXPENSE", expense.id().value(), "upsert",
                expense.revision(), objectMapper.convertValue(view, java.util.Map.class), null, now);
        idempotency.complete(actor.value(), COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    @Transactional(readOnly = true)
    public DirectExpenseView read(String appSubject, String expenseId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        DirectExpense expense = expenses.find(new ExpenseId(expenseId))
                .orElseThrow(GroupAccessDeniedException::new);
        if (!DirectInvolvement.involves(expense, actor)) {
            throw new GroupAccessDeniedException();
        }
        return DirectExpenseView.from(expense);
    }

    @Transactional(readOnly = true)
    public List<ActivityEntry> activity(String appSubject, String expenseId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        DirectExpense expense = expenses.find(new ExpenseId(expenseId))
                .orElseThrow(GroupAccessDeniedException::new);
        if (!DirectInvolvement.involves(expense, actor)) {
            throw new GroupAccessDeniedException();
        }
        return activity.forScope(scopeId(expense));
    }

    /** A Direct Expense may only name members the caller currently collaborates with. */
    private boolean sharesActiveGroup(MemberId actor, MemberId other) {
        return groups.findReadableBy(actor).stream()
                .filter(group -> group.canCollaborate(actor))
                .anyMatch(group -> group.membership(other)
                        .map(com.dutch.ledger.group.Membership::isActive)
                        .orElse(false));
    }

    private DirectExpenseView replay(IdempotencyStore.Outcome outcome, String requestHash) {
        if (!outcome.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            return objectMapper.readValue(outcome.responseJson(), DirectExpenseView.class);
        } catch (Exception exception) {
            throw new IllegalStateException("idempotent direct expense result is unavailable", exception);
        }
    }

    static String scopeId(DirectExpense expense) {
        return "direct-expense:" + expense.id().value();
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
            throw new IllegalStateException("could not serialize the direct expense", exception);
        }
    }
}
