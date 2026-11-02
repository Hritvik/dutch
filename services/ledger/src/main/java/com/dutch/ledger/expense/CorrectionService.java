package com.dutch.ledger.expense;

import com.dutch.ledger.activity.ActivityAction;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.group.ConcurrentGroupUpdateException;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorrectionService {

    static final String COMMAND = "correct-expense";

    private final ExpenseRepository expenses;
    private final GroupRepository groups;
    private final MemberResolver members;
    private final IdempotencyStore idempotency;
    private final CorrectionRepository corrections;
    private final ActivityRepository activity;
    private final SyncJournal journal;
    private final ObjectMapper objectMapper;

    public CorrectionService(ExpenseRepository expenses, GroupRepository groups, MemberResolver members,
                             IdempotencyStore idempotency, CorrectionRepository corrections,
                             ActivityRepository activity, SyncJournal journal,
                             ObjectMapper objectMapper) {
        this.expenses = expenses;
        this.groups = groups;
        this.members = members;
        this.idempotency = idempotency;
        this.corrections = corrections;
        this.activity = activity;
        this.journal = journal;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExpenseView correct(String appSubject, String groupId, String expenseId, String idempotencyKey,
                               long expectedRevision, CreateExpenseRequest request) {
        Objects.requireNonNull(request, "request");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and bounded");
        }
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findByIdForUpdate(new GroupId(groupId))
                .orElseThrow(GroupAccessDeniedException::new);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        if (!group.canCreateExpense(actor)) {
            throw new GroupForbiddenException();
        }
        // Replay before the revision precondition: a retry of an accepted correction
        // carries the original If-Match, so the resource has legitimately moved on.
        String requestHash = hash(groupId + "|" + expenseId + "|" + expectedRevision + "|" + serialize(request));
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), COMMAND, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, groupId, expenseId, expectedRevision + 1);
        }
        Expense current = expenses.findExpense(new ExpenseId(expenseId))
                .orElseThrow(GroupAccessDeniedException::new);
        if (!current.groupId().equals(group.id())) {
            throw new GroupAccessDeniedException();
        }
        if (current.revision() != expectedRevision) {
            throw new ConcurrentGroupUpdateException();
        }
        if (!idempotency.reserve(actor.value(), COMMAND, idempotencyKey, requestHash)) {
            IdempotencyStore.Outcome raced = idempotency.find(actor.value(), COMMAND, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
            return replay(raced, requestHash, groupId, expenseId, expectedRevision + 1);
        }

        Instant now = Instant.now();
        Expense replacement = ExpenseCommandFactory.create(group, current.id(), current.revision() + 1, request);
        ExpenseCorrection correction = ExpenseHistory.of(current).correct(replacement, actor, now);
        corrections.save(correction);
        java.util.Map<String, String> details = new java.util.HashMap<>();
        String safeDescription = ActivityEntry.contactFree(replacement.description());
        if (safeDescription != null) {
            details.put("description", safeDescription);
        }
        details.put("totalMinorUnits", Long.toString(replacement.totalMinorUnits()));
        details.put("currency", replacement.currency());
        details.put("fromRevision", Long.toString(current.revision()));
        details.put("toRevision", Long.toString(replacement.revision()));
        activity.append(new ActivityEntry("act_" + UUID.randomUUID().toString().replace("-", ""),
                scopeId(group, current.currency()), actor, ActivityAction.EXPENSE_CORRECTED,
                expenseId, replacement.revision(), now, details));

        ExpenseView view = ExpenseView.from(replacement);
        journal.append(group.memberships().stream()
                        .filter(com.dutch.ledger.group.Membership::isActive)
                        .map(com.dutch.ledger.group.Membership::memberId).toList(),
                "EXPENSE", replacement.id().value(), "upsert", replacement.revision(),
                objectMapper.convertValue(view, java.util.Map.class), null, now);
        idempotency.complete(actor.value(), COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    private ExpenseView replay(IdempotencyStore.Outcome outcome, String requestHash, String groupId,
                               String expenseId, long revision) {
        if (!outcome.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            ExpenseView view = objectMapper.readValue(outcome.responseJson(), ExpenseView.class);
            if (!groupId.equals(view.groupId()) || !expenseId.equals(view.id())
                    || view.revision() != revision) {
                throw new IdempotencyConflictException();
            }
            return view;
        } catch (IdempotencyConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("idempotent correction result is unavailable", exception);
        }
    }

    static String scopeId(Group group, String currency) {
        return "group:" + group.id().value() + ":" + currency;
    }

    private String hash(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException("could not serialize the correction command", exception);
        }
    }
}
