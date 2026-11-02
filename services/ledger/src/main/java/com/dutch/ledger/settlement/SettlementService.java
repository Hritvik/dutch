package com.dutch.ledger.settlement;

import com.dutch.ledger.activity.ActivityAction;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.expense.IdempotencyConflictException;
import com.dutch.ledger.expense.IdempotencyStore;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupForbiddenException;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.GroupRuleException;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    static final String RECORD_COMMAND = "record-settlement";
    static final String VOID_COMMAND = "void-settlement";

    private final SettlementRepository settlements;
    private final GroupRepository groups;
    private final MemberResolver members;
    private final IdempotencyStore idempotency;
    private final ActivityRepository activity;
    private final SyncJournal journal;
    private final ObjectMapper objectMapper;

    public SettlementService(SettlementRepository settlements, GroupRepository groups,
                             MemberResolver members, IdempotencyStore idempotency,
                             ActivityRepository activity, SyncJournal journal,
                             ObjectMapper objectMapper) {
        this.settlements = settlements;
        this.groups = groups;
        this.members = members;
        this.idempotency = idempotency;
        this.activity = activity;
        this.journal = journal;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SettlementView create(String appSubject, String groupId, String idempotencyKey,
                                 CreateSettlementRequest request) {
        Objects.requireNonNull(request, "request");
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findByIdForUpdate(new GroupId(groupId))
                .orElseThrow(GroupAccessDeniedException::new);
        requireGroupExpenseRole(group, actor);

        String requestHash = hash(groupId + "|" + serialize(request));
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), RECORD_COMMAND, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, groupId, null, "ACTIVE");
        }
        if (!idempotency.reserve(actor.value(), RECORD_COMMAND, idempotencyKey, requestHash)) {
            IdempotencyStore.Outcome raced = idempotency.find(actor.value(), RECORD_COMMAND, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
            return replay(raced, requestHash, groupId, null, "ACTIVE");
        }

        String currency = requireCurrency(group, request);
        if (request.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (request.payerMemberId() == null || request.payeeMemberId() == null) {
            throw new IllegalArgumentException("payerMemberId and payeeMemberId are required");
        }
        MemberId payer = new MemberId(request.payerMemberId());
        MemberId payee = new MemberId(request.payeeMemberId());
        if (!group.canBeAllocated(payer) || !group.canBeAllocated(payee)) {
            throw new IllegalArgumentException("a settlement member is not allocatable in this group");
        }
        if (payer.equals(payee)) {
            throw new IllegalArgumentException("a settlement needs two distinct members");
        }
        List<SettlementAllocation> allocations = allocations(group, request, payer, payee, currency);
        Settlement settlement = new Settlement(new SettlementId(newId()),
                payer, payee, request.totalMinorUnits(), currency, allocations, request.occurredAt());

        Instant now = Instant.now();
        settlements.save(settlement);
        activity.append(new ActivityEntry(newActivityId(), scopeId(group),
                actor, ActivityAction.SETTLEMENT_RECORDED, settlement.id().value(), 1, now,
                Map.of("totalMinorUnits", Long.toString(settlement.totalMinorUnits()),
                        "currency", settlement.currency(), "status", "ACTIVE")));

        SettlementView view = SettlementView.from(settlement, groupId, "ACTIVE");
        journal.append(group.memberships().stream()
                        .filter(com.dutch.ledger.group.Membership::isActive)
                        .map(com.dutch.ledger.group.Membership::memberId).toList(),
                "SETTLEMENT", settlement.id().value(), "upsert", 1,
                objectMapper.convertValue(view, java.util.Map.class), null, now);
        idempotency.complete(actor.value(), RECORD_COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    @Transactional
    public SettlementView voidSettlement(String appSubject, String groupId, String settlementId,
                                         String idempotencyKey) {
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findByIdForUpdate(new GroupId(groupId))
                .orElseThrow(GroupAccessDeniedException::new);
        requireGroupExpenseRole(group, actor);

        String requestHash = hash(groupId + "|" + settlementId);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), VOID_COMMAND, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, groupId, settlementId, "VOIDED");
        }

        Settlement settlement = settlements.find(new SettlementId(settlementId))
                .filter(found -> scopeOf(found).equals(groupId))
                .orElseThrow(GroupAccessDeniedException::new);
        if (!"ACTIVE".equals(settlements.status(settlement.id()))) {
            throw new GroupRuleException("This settlement has already been voided.");
        }
        if (!idempotency.reserve(actor.value(), VOID_COMMAND, idempotencyKey, requestHash)) {
            IdempotencyStore.Outcome raced = idempotency.find(actor.value(), VOID_COMMAND, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
            return replay(raced, requestHash, groupId, settlementId, "VOIDED");
        }

        Instant now = Instant.now();
        settlements.voidSettlement(SettlementHistory.of(settlement).voidIt(actor, now));
        activity.append(new ActivityEntry(newActivityId(), scopeId(group),
                actor, ActivityAction.SETTLEMENT_VOIDED, settlement.id().value(), 2, now,
                Map.of("totalMinorUnits", Long.toString(settlement.totalMinorUnits()),
                        "currency", settlement.currency(), "status", "VOIDED")));

        SettlementView view = SettlementView.from(settlement, groupId, "VOIDED");
        journal.append(group.memberships().stream()
                        .filter(com.dutch.ledger.group.Membership::isActive)
                        .map(com.dutch.ledger.group.Membership::memberId).toList(),
                "SETTLEMENT", settlement.id().value(), "upsert", 2,
                objectMapper.convertValue(view, java.util.Map.class), null, now);
        idempotency.complete(actor.value(), VOID_COMMAND, idempotencyKey, serialize(view));
        return view;
    }

    private List<SettlementAllocation> allocations(Group group, CreateSettlementRequest request,
                                                    MemberId payer, MemberId payee, String currency) {
        String groupScope = scopeId(group);
        if (request.allocations() == null || request.allocations().isEmpty()) {
            return List.of(new SettlementAllocation(groupScope, currency, request.totalMinorUnits(),
                    payer, payee));
        }
        String prefix = "group:" + group.id().value() + ":";
        return request.allocations().stream()
                .map(allocation -> {
                    if (allocation == null || allocation.scopeId() == null) {
                        throw new IllegalArgumentException("each allocation needs a scopeId");
                    }
                    if (!allocation.scopeId().startsWith(prefix)
                            || !allocation.scopeId().equals(prefix + currency)) {
                        throw new IllegalArgumentException(
                                "an allocation must use this group and currency");
                    }
                    return new SettlementAllocation(allocation.scopeId(), currency,
                            allocation.minorUnits(), payer, payee);
                })
                .toList();
    }

    private static String requireCurrency(Group group, CreateSettlementRequest request) {
        if (request.currency() == null || !request.currency().equals(group.currency())) {
            throw new IllegalArgumentException("currency must match the group currency");
        }
        return request.currency();
    }

    private static void requireGroupExpenseRole(Group group, MemberId actor) {
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        if (!group.canCreateExpense(actor)) {
            throw new GroupForbiddenException();
        }
    }

    private SettlementView replay(IdempotencyStore.Outcome outcome, String requestHash,
                                  String groupId, String settlementId, String status) {
        if (!outcome.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            SettlementView view = objectMapper.readValue(outcome.responseJson(), SettlementView.class);
            if (!groupId.equals(view.groupId()) || !status.equals(view.status())
                    || (settlementId != null && !settlementId.equals(view.id()))) {
                throw new IdempotencyConflictException();
            }
            return view;
        } catch (IdempotencyConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("idempotent settlement result is unavailable", exception);
        }
    }

    static String scopeId(Group group) {
        return "group:" + group.id().value() + ":" + group.currency();
    }

    private static String scopeOf(Settlement settlement) {
        return settlement.allocations().get(0).scopeId().split(":", 3)[1];
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and bounded");
        }
    }

    private static String newId() {
        return "stl_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String newActivityId() {
        return "act_" + UUID.randomUUID().toString().replace("-", "");
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
            throw new IllegalStateException("could not serialize the settlement command", exception);
        }
    }
}
