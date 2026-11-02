package com.dutch.ledger.series;

import com.dutch.ledger.activity.ActivityAction;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.direct.DirectExpenseService;
import com.dutch.ledger.direct.DirectExpenseView;
import com.dutch.ledger.expense.CreateExpenseRequest;
import com.dutch.ledger.expense.ExpenseService;
import com.dutch.ledger.expense.ExpenseView;
import com.dutch.ledger.expense.IdempotencyConflictException;
import com.dutch.ledger.expense.IdempotencyStore;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.ConcurrentGroupUpdateException;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupForbiddenException;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.MemberDirectory;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import com.dutch.ledger.sync.SyncJournal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeriesService {

    static final String CREATE = "create-series";
    static final String GENERATE = "generate-occurrence";
    static final String SKIP = "skip-occurrence";
    static final String SUBMIT = "submit-occurrence";
    static final String STATUS = "change-series-status";

    private final SeriesRepository series;
    private final GroupRepository groups;
    private final MemberResolver members;
    private final MemberDirectory directory;
    private final ExpenseService expenses;
    private final DirectExpenseService directExpenses;
    private final IdempotencyStore idempotency;
    private final ActivityRepository activity;
    private final SyncJournal journal;
    private final ObjectMapper objectMapper;

    public SeriesService(SeriesRepository series, GroupRepository groups, MemberResolver members,
                         MemberDirectory directory, ExpenseService expenses,
                         DirectExpenseService directExpenses, IdempotencyStore idempotency,
                         ActivityRepository activity, SyncJournal journal,
                         ObjectMapper objectMapper) {
        this.series = series;
        this.groups = groups;
        this.members = members;
        this.directory = directory;
        this.expenses = expenses;
        this.directExpenses = directExpenses;
        this.idempotency = idempotency;
        this.activity = activity;
        this.journal = journal;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SeriesView create(String appSubject, String idempotencyKey, CreateSeriesRequest request) {
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        String requestHash = hash(serialize(request));
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), CREATE, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, SeriesView.class)
                    .orElseThrow(() -> new IllegalStateException("series replay is empty"));
        }

        SeriesScope scope = scope(request.scope());
        SeriesTemplate template = template(request);
        authorizeScope(actor, scope, request);
        requireTemplateMembers(actor, scope, template, request);

        Cadence cadence = Cadence.valueOf(require(request.cadence(), "cadence"));
        LocalDate next = require(request.nextPeriodStart(), "nextPeriodStart");
        Instant now = Instant.now();
        SeriesId id = new SeriesId("ser_" + UUID.randomUUID().toString().replace("-", ""));
        RecurringSeries created = RecurringSeries.create(id, scope, template, cadence,
                request.dueDay(), ZoneId.of(require(request.timezone(), "timezone")), next, now);

        if (!idempotency.reserve(actor.value(), CREATE, idempotencyKey, requestHash)) {
            return replay(idempotency.find(actor.value(), CREATE, idempotencyKey).orElseThrow(),
                    requestHash, SeriesView.class)
                    .orElseThrow(() -> new IllegalStateException("series replay is empty"));
        }
        series.saveNew(created);
        appendActivity(created, actor, ActivityAction.SERIES_CREATED, Map.of(
                "status", created.status().name(),
                "cadence", created.cadence().name(),
                "scopeType", created.scope().type().name()));
        SeriesView view = SeriesView.from(created);
        journal.append(recipients(created), "SERIES", created.id().value(), "upsert",
                created.version(), objectMapper.convertValue(view, Map.class), null, now);
        idempotency.complete(actor.value(), CREATE, idempotencyKey, serialize(view));
        return view;
    }

    @Transactional(readOnly = true)
    public SeriesView read(String appSubject, String seriesId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        RecurringSeries found = authorizedReader(actor, new SeriesId(seriesId));
        return SeriesView.from(found);
    }

    @Transactional(readOnly = true)
    public List<SeriesView> forGroup(String appSubject, String groupId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findById(new GroupId(groupId)).orElseThrow(GroupAccessDeniedException::new);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        return series.findForGroup(group.id()).stream().map(SeriesView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SeriesView> forDirectMember(String appSubject) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        return series.findForDirectMember(actor).stream().map(SeriesView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<OccurrenceView> occurrences(String appSubject, String seriesId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        RecurringSeries found = authorizedReader(actor, new SeriesId(seriesId));
        return series.occurrencesFor(found.id()).stream().map(OccurrenceView::from).toList();
    }

    @Transactional
    public OccurrenceView generate(String appSubject, String seriesId, String idempotencyKey,
                                   long expectedVersion) {
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        String requestHash = hash(seriesId + "|" + expectedVersion);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), GENERATE, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, OccurrenceView.class)
                    .orElseThrow(() -> new IllegalStateException("occurrence replay is empty"));
        }
        RecurringSeries found = authorizedWriter(actor, new SeriesId(seriesId));
        if (found.version() != expectedVersion) {
            throw new ConcurrentGroupUpdateException();
        }
        if (found.status() != SeriesStatus.ACTIVE) {
            throw new GroupRuleException("A paused or archived series has no new occurrences.");
        }
        OccurrenceId occurrenceId = new OccurrenceId(
                "occ_" + UUID.randomUUID().toString().replace("-", ""));
        PendingOccurrence occurrence = found.occurrenceFor(occurrenceId, found.nextPeriodStart());
        if (!idempotency.reserve(actor.value(), GENERATE, idempotencyKey, requestHash)) {
            return replay(idempotency.find(actor.value(), GENERATE, idempotencyKey).orElseThrow(),
                    requestHash, OccurrenceView.class)
                    .orElseThrow(() -> new IllegalStateException("occurrence replay is empty"));
        }
        series.insertOccurrence(occurrence);
        RecurringSeries advanced = found.withNextPeriodStart(
                found.cadence().nextPeriodStart(found.nextPeriodStart()));
        series.update(advanced, expectedVersion);
        journal.append(recipients(advanced), "SERIES", advanced.id().value(), "upsert",
                advanced.version(), objectMapper.convertValue(SeriesView.from(advanced), Map.class),
                null, Instant.now());
        appendActivity(found, actor, ActivityAction.SERIES_OCCURRENCE_GENERATED, Map.of(
                "periodStart", occurrence.periodStart().toString(),
                "periodEnd", occurrence.periodEnd().toString(),
                "status", occurrence.status().name()));
        OccurrenceView view = OccurrenceView.from(occurrence);
        journal.append(recipients(found), "OCCURRENCE", occurrence.id().value(), "upsert",
                found.version(), objectMapper.convertValue(view, Map.class), null, Instant.now());
        idempotency.complete(actor.value(), GENERATE, idempotencyKey, serialize(view));
        return view;
    }

    @Transactional
    public OccurrenceView skip(String appSubject, String occurrenceId, String idempotencyKey) {
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        String requestHash = hash(occurrenceId);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), SKIP, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, OccurrenceView.class)
                    .orElseThrow(() -> new IllegalStateException("occurrence replay is empty"));
        }
        PendingOccurrence occurrence = occurrence(occurrenceId);
        RecurringSeries found = authorizedWriter(actor, occurrence.seriesId());
        if (occurrence.status() != OccurrenceStatus.PENDING) {
            throw new GroupRuleException("This occurrence is no longer pending.");
        }
        if (!idempotency.reserve(actor.value(), SKIP, idempotencyKey, requestHash)) {
            return replay(idempotency.find(actor.value(), SKIP, idempotencyKey).orElseThrow(),
                    requestHash, OccurrenceView.class)
                    .orElseThrow(() -> new IllegalStateException("occurrence replay is empty"));
        }
        PendingOccurrence skipped = occurrence.skipped();
        series.updateOccurrence(skipped);
        appendActivity(found, actor, ActivityAction.SERIES_OCCURRENCE_SKIPPED, Map.of(
                "periodStart", skipped.periodStart().toString(),
                "status", skipped.status().name()));
        OccurrenceView view = OccurrenceView.from(skipped);
        journal.append(recipients(found), "OCCURRENCE", skipped.id().value(), "upsert",
                found.version(), objectMapper.convertValue(view, Map.class), null, Instant.now());
        idempotency.complete(actor.value(), SKIP, idempotencyKey, serialize(view));
        return view;
    }

    @Transactional
    public SeriesSubmitResult submit(String appSubject, String occurrenceId,
                                     String idempotencyKey) {
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        String requestHash = hash(occurrenceId);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), SUBMIT, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, SeriesSubmitResult.class)
                    .orElseThrow(() -> new IllegalStateException("occurrence replay is empty"));
        }
        PendingOccurrence occurrence = occurrence(occurrenceId);
        RecurringSeries found = authorizedWriter(actor, occurrence.seriesId());
        if (occurrence.status() != OccurrenceStatus.PENDING) {
            throw new GroupRuleException("This occurrence is no longer pending.");
        }
        if (!idempotency.reserve(actor.value(), SUBMIT, idempotencyKey, requestHash)) {
            return replay(idempotency.find(actor.value(), SUBMIT, idempotencyKey).orElseThrow(),
                    requestHash, SeriesSubmitResult.class)
                    .orElseThrow(() -> new IllegalStateException("occurrence replay is empty"));
        }

        CreateExpenseRequest request = expenseRequest(found, occurrence);
        String expenseId;
        if (found.scope().type() == SeriesScope.ScopeType.GROUP) {
            ExpenseView created = expenses.create(appSubject, found.scope().groupId().value(),
                    idempotencyKey, request);
            expenseId = created.id();
        } else {
            DirectExpenseView created = directExpenses.create(appSubject, idempotencyKey, request);
            expenseId = created.id();
        }
        PendingOccurrence submitted = occurrence.submitted(
                new com.dutch.ledger.expense.ExpenseId(expenseId));
        series.updateOccurrence(submitted);
        appendActivity(found, actor, ActivityAction.SERIES_OCCURRENCE_SUBMITTED, Map.of(
                "periodStart", submitted.periodStart().toString(),
                "status", submitted.status().name()));

        SeriesSubmitResult result = new SeriesSubmitResult(
                OccurrenceView.from(submitted), expenseId);
        journal.append(recipients(found), "OCCURRENCE", submitted.id().value(), "upsert",
                found.version(), objectMapper.convertValue(OccurrenceView.from(submitted), Map.class),
                null, Instant.now());
        idempotency.complete(actor.value(), SUBMIT, idempotencyKey, serialize(result));
        return result;
    }

    @Transactional
    public SeriesView changeStatus(String appSubject, String seriesId, String idempotencyKey,
                                   String action, long expectedVersion) {
        requireKey(idempotencyKey);
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        String requestHash = hash(seriesId + "|" + action + "|" + expectedVersion);
        Optional<IdempotencyStore.Outcome> existing =
                idempotency.find(actor.value(), STATUS, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, SeriesView.class)
                    .orElseThrow(() -> new IllegalStateException("series replay is empty"));
        }
        RecurringSeries found = authorizedWriter(actor, new SeriesId(seriesId));
        if (found.version() != expectedVersion) {
            throw new ConcurrentGroupUpdateException();
        }
        RecurringSeries changed = switch (action == null ? "" : action.toUpperCase()) {
            case "PAUSE" -> found.paused();
            case "RESUME" -> found.resumed();
            case "ARCHIVE" -> found.archived();
            default -> throw new IllegalArgumentException("action must be PAUSE, RESUME, or ARCHIVE");
        };
        if (!idempotency.reserve(actor.value(), STATUS, idempotencyKey, requestHash)) {
            return replay(idempotency.find(actor.value(), STATUS, idempotencyKey).orElseThrow(),
                    requestHash, SeriesView.class)
                    .orElseThrow(() -> new IllegalStateException("series replay is empty"));
        }
        series.update(changed, expectedVersion);
        ActivityAction activityAction = switch (changed.status()) {
            case PAUSED -> ActivityAction.SERIES_PAUSED;
            case ARCHIVED -> ActivityAction.SERIES_ARCHIVED;
            default -> ActivityAction.SERIES_RESUMED;
        };
        appendActivity(changed, actor, activityAction, Map.of("status", changed.status().name()));
        SeriesView view = SeriesView.from(changed);
        journal.append(recipients(changed), "SERIES", changed.id().value(), "upsert",
                changed.version(), objectMapper.convertValue(view, Map.class), null, Instant.now());
        idempotency.complete(actor.value(), STATUS, idempotencyKey, serialize(view));
        return view;
    }

    private RecurringSeries authorizedReader(MemberId actor, SeriesId id) {
        RecurringSeries found = series.find(id).orElseThrow(GroupAccessDeniedException::new);
        if (found.scope().type() == SeriesScope.ScopeType.GROUP) {
            Group group = groups.findById(found.scope().groupId())
                    .orElseThrow(GroupAccessDeniedException::new);
            if (!group.canRead(actor)) {
                throw new GroupAccessDeniedException();
            }
        } else if (!found.scope().memberIds().contains(actor)) {
            throw new GroupAccessDeniedException();
        }
        return found;
    }

    /** Inactive membership is read-only: series commands require collaboration. */
    private RecurringSeries authorizedWriter(MemberId actor, SeriesId id) {
        RecurringSeries found = authorizedReader(actor, id);
        if (found.scope().type() == SeriesScope.ScopeType.GROUP) {
            Group group = groups.findById(found.scope().groupId())
                    .orElseThrow(GroupAccessDeniedException::new);
            if (!group.canCollaborate(actor)) {
                throw new GroupForbiddenException();
            }
        }
        return found;
    }

    private PendingOccurrence occurrence(String occurrenceId) {
        return series.findOccurrence(new OccurrenceId(occurrenceId))
                .orElseThrow(GroupAccessDeniedException::new);
    }

    private void authorizeScope(MemberId actor, SeriesScope scope, CreateSeriesRequest request) {
        if (scope.type() == SeriesScope.ScopeType.GROUP) {
            Group group = groups.findById(scope.groupId())
                    .orElseThrow(GroupAccessDeniedException::new);
            if (!group.canRead(actor)) {
                throw new GroupAccessDeniedException();
            }
            if (!group.canCreateExpense(actor)) {
                throw new GroupForbiddenException();
            }
            if (!group.currency().equals(request.currency())) {
                throw new IllegalArgumentException("currency must match the group currency");
            }
        } else if (!scope.memberIds().contains(actor)) {
            throw new GroupForbiddenException();
        }
    }

    private void requireTemplateMembers(MemberId actor, SeriesScope scope,
                                        SeriesTemplate template, CreateSeriesRequest request) {
        Set<MemberId> involved = new HashSet<>();
        template.payers().forEach(payer -> involved.add(payer.memberId()));
        template.participants().forEach(participant -> involved.add(participant.memberId()));
        if (scope.type() == SeriesScope.ScopeType.DIRECT) {
            if (!involved.equals(Set.copyOf(scope.memberIds()))) {
                throw new IllegalArgumentException(
                        "direct series members must match the payer and participant set");
            }
            for (MemberId member : involved) {
                if (!directory.isActive(member)) {
                    throw new IllegalArgumentException("every direct member must be active");
                }
            }
        } else {
            Group group = groups.findById(scope.groupId()).orElseThrow(GroupAccessDeniedException::new);
            for (MemberId member : involved) {
                if (!group.canBeAllocated(member)) {
                    throw new IllegalArgumentException(
                            "a payer or participant is not allocatable in this group");
                }
            }
        }
    }

    private SeriesScope scope(CreateSeriesRequest.ScopeRequest request) {
        if (request == null || request.type() == null) {
            throw new IllegalArgumentException("scope is required");
        }
        return switch (request.type()) {
            case "GROUP" -> SeriesScope.group(new GroupId(require(request.groupId(), "groupId")));
            case "DIRECT" -> SeriesScope.direct((request.memberIds() == null ? List.<String>of()
                    : request.memberIds()).stream().map(MemberId::new).toList());
            default -> throw new IllegalArgumentException("scope.type must be GROUP or DIRECT");
        };
    }

    private SeriesTemplate template(CreateSeriesRequest request) {
        List<PayerContribution> payers = request.payers() == null ? List.of()
                : request.payers().stream()
                .map(payer -> new PayerContribution(new MemberId(payer.memberId()),
                        payer.minorUnits()))
                .toList();
        List<SeriesParticipant> participants = request.participants() == null ? List.of()
                : request.participants().stream()
                .map(participant -> new SeriesParticipant(new MemberId(participant.memberId()),
                        participant.basisPoints(), participant.minorUnits()))
                .toList();
        return new SeriesTemplate(request.description(), request.totalMinorUnits(),
                request.currency(), request.splitMethod(), payers, participants);
    }

    private CreateExpenseRequest expenseRequest(RecurringSeries found, PendingOccurrence occurrence) {
        SeriesTemplate template = found.template();
        List<String> participants = template.participantIds().stream().map(MemberId::value).toList();
        List<CreateExpenseRequest.PayerRequest> payers = template.payers().stream()
                .map(payer -> new CreateExpenseRequest.PayerRequest(
                        payer.memberId().value(), payer.minorUnits()))
                .toList();
        List<CreateExpenseRequest.ShareRequest> exactShares =
                template.splitMethod() == SplitMethod.EXACT
                        ? template.participants().stream()
                        .map(participant -> new CreateExpenseRequest.ShareRequest(
                                participant.memberId().value(), participant.minorUnits()))
                        .toList()
                        : null;
        List<Integer> basisPoints = template.splitMethod() == SplitMethod.PERCENTAGE
                ? template.participants().stream().map(SeriesParticipant::basisPoints).toList()
                : null;
        return new CreateExpenseRequest(template.description(), template.totalMinorUnits(),
                template.currency(), template.splitMethod(), payers, participants,
                exactShares, basisPoints, occurrence.dueAt());
    }

    private void appendActivity(RecurringSeries found, MemberId actor, ActivityAction action,
                                Map<String, String> extra) {
        Map<String, String> details = new java.util.HashMap<>();
        String safeDescription = ActivityEntry.contactFree(found.template().description());
        if (safeDescription != null) {
            details.put("description", safeDescription);
        }
        details.put("totalMinorUnits", Long.toString(found.template().totalMinorUnits()));
        details.put("currency", found.template().currency());
        details.put("cadence", found.cadence().name());
        details.putAll(extra);
        activity.append(new ActivityEntry("act_" + UUID.randomUUID().toString().replace("-", ""),
                scopeId(found), actor, action, found.id().value(), found.version(), Instant.now(),
                details));
    }

    private List<MemberId> recipients(RecurringSeries found) {
        if (found.scope().type() == SeriesScope.ScopeType.DIRECT) {
            return found.scope().memberIds();
        }
        return groups.findById(found.scope().groupId())
                .map(group -> group.memberships().stream()
                        .filter(com.dutch.ledger.group.Membership::isActive)
                        .map(com.dutch.ledger.group.Membership::memberId)
                        .toList())
                .orElse(List.of());
    }

    static String scopeId(RecurringSeries series) {
        return series.scope().type() == SeriesScope.ScopeType.GROUP
                ? "group:" + series.scope().groupId().value() + ":" + series.template().currency()
                : "direct-series:" + series.id().value();
    }

    private <T> Optional<T> replay(IdempotencyStore.Outcome outcome, String requestHash,
                                   Class<T> type) {
        if (!outcome.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            return Optional.of(objectMapper.readValue(outcome.responseJson(), type));
        } catch (Exception exception) {
            throw new IllegalStateException("idempotent series result is unavailable", exception);
        }
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and bounded");
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
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
            throw new IllegalStateException("could not serialize the series command", exception);
        }
    }
}
