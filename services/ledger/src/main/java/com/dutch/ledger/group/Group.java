package com.dutch.ledger.group;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Group and Membership aggregate. The aggregate owns the Admin invariant,
 * archive behaviour, and the access rules the API and Ledger command paths
 * rely on. It carries no contact identifiers.
 */
public final class Group {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_NAME = 120;

    private final GroupId id;
    private final String name;
    private final String currency;
    private final Map<MemberId, Membership> memberships;
    private final Set<String> appliedEventIds;
    private GroupState state;
    private long version;

    private Group(GroupId id, String name, String currency, GroupState state,
                  Map<MemberId, Membership> memberships, long version) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.state = state;
        this.memberships = new LinkedHashMap<>(memberships);
        this.appliedEventIds = new HashSet<>();
        this.version = version;
    }

    public static Group create(GroupId id, String name, String currency, MemberId creator, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(now, "now");
        String validatedName = validateName(name);
        String validatedCurrency = validateCurrency(currency);
        Membership admin = new Membership(creator, MembershipState.ACTIVE, GroupRole.ADMIN, now, now);
        return new Group(id, validatedName, validatedCurrency, GroupState.ACTIVE, Map.of(creator, admin), 0);
    }

    /** Rehydrates a persisted group. Invariants are re-checked on load, never trusted blindly. */
    public static Group restore(GroupId id, String name, String currency, GroupState state,
                                 long version, List<Membership> memberships) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(memberships, "memberships");
        Map<MemberId, Membership> map = new LinkedHashMap<>();
        for (Membership membership : memberships) {
            map.put(membership.memberId(), membership);
        }
        ensureActiveAdmin(map);
        return new Group(id, validateName(name), validateCurrency(currency), state, map, version);
    }

    public void invite(MemberId actor, MemberId invitee, GroupRole role, Instant now) {
        requireManager(actor);
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(role, "role");
        Membership existing = memberships.get(invitee);
        Map<MemberId, Membership> next = new LinkedHashMap<>(memberships);
        if (existing != null) {
            if (existing.state() != MembershipState.INACTIVE) {
                throw new GroupRuleException("The member already belongs to this group.");
            }
            next.put(invitee, new Membership(invitee, MembershipState.INVITED, role, existing.joinedAt(), now));
            commit(next);
            return;
        }
        next.put(invitee, new Membership(invitee, MembershipState.INVITED, role, now, now));
        commit(next);
    }

    public void addPlaceholder(MemberId actor, MemberId placeholder, GroupRole role, Instant now) {
        requireManager(actor);
        Objects.requireNonNull(placeholder, "placeholder");
        Objects.requireNonNull(role, "role");
        if (memberships.containsKey(placeholder)) {
            throw new GroupRuleException("The member already belongs to this group.");
        }
        Map<MemberId, Membership> next = new LinkedHashMap<>(memberships);
        next.put(placeholder, new Membership(placeholder, MembershipState.PLACEHOLDER, role, now, now));
        commit(next);
    }

    public void activate(MemberId actor, MemberId target, Instant now) {
        Objects.requireNonNull(target, "target");
        if (!actor.equals(target)) {
            requireManager(actor);
        } else {
            requireActiveGroup();
        }
        Membership current = requireMembership(target);
        if (current.state() == MembershipState.ACTIVE) {
            throw new GroupRuleException("The membership is already active.");
        }
        if (current.state() == MembershipState.INACTIVE) {
            throw new GroupRuleException("Reactivation requires a new invitation and acceptance.");
        }
        Map<MemberId, Membership> next = new LinkedHashMap<>(memberships);
        next.put(target, current.withState(MembershipState.ACTIVE, now));
        commit(next);
    }

    public void deactivate(MemberId actor, MemberId target, Instant now) {
        requireActiveGroup();
        Objects.requireNonNull(target, "target");
        if (!actor.equals(target)) {
            requireManager(actor);
        }
        Membership current = requireMembership(target);
        if (current.state() != MembershipState.ACTIVE) {
            throw new GroupRuleException("The membership is not active.");
        }
        if (current.isActiveAdmin() && activeAdminCount() == 1) {
            throw new LastAdminException();
        }
        Map<MemberId, Membership> next = new LinkedHashMap<>(memberships);
        next.put(target, current.withState(MembershipState.INACTIVE, now));
        commit(next);
    }

    /** Leaving and Admin removal both retain the historical membership as INACTIVE. */
    public void remove(MemberId actor, MemberId target, Instant now) {
        deactivate(actor, target, now);
    }

    public void changeRole(MemberId actor, MemberId target, GroupRole role, Instant now) {
        requireManager(actor);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(role, "role");
        Membership current = requireMembership(target);
        if (current.state() != MembershipState.ACTIVE) {
            throw new GroupRuleException("Only an active membership can change role.");
        }
        if (current.role() == GroupRole.ADMIN && role == GroupRole.MEMBER && activeAdminCount() == 1) {
            throw new LastAdminException();
        }
        Map<MemberId, Membership> next = new LinkedHashMap<>(memberships);
        next.put(target, current.withRole(role, now));
        commit(next);
    }

    public void archive(MemberId actor, Instant now) {
        requireManager(actor);
        if (state == GroupState.ARCHIVED) {
            throw new GroupRuleException("The group is already archived.");
        }
        state = GroupState.ARCHIVED;
        version += 1;
    }

    public void reopen(MemberId actor, Instant now) {
        requireAdmin(actor);
        if (state == GroupState.ACTIVE) {
            throw new GroupRuleException("The group is already active.");
        }
        state = GroupState.ACTIVE;
        version += 1;
    }

    /**
     * Applies one UMS-fed membership fact. Duplicate ids are no-ops and stale
     * aggregate versions never move the group backwards.
     */
    public boolean applyMembershipEvent(MembershipEvent event) {
        Objects.requireNonNull(event, "event");
        if (appliedEventIds.contains(event.eventId())) {
            return false;
        }
        if (event.aggregateVersion() <= version) {
            return false;
        }
        if (state == GroupState.ARCHIVED) {
            throw new GroupRuleException("The group is archived.");
        }
        Membership existing = memberships.get(event.memberId());
        if (existing != null && existing.state() == MembershipState.INACTIVE
                && event.state() == MembershipState.ACTIVE) {
            throw new GroupRuleException("Reactivation requires a new invitation and acceptance.");
        }
        Membership updated = new Membership(
                event.memberId(),
                event.state(),
                event.role(),
                existing == null ? event.occurredAt() : existing.joinedAt(),
                event.occurredAt());
        Map<MemberId, Membership> next = new LinkedHashMap<>(memberships);
        next.put(event.memberId(), updated);
        ensureActiveAdmin(next);
        memberships.clear();
        memberships.putAll(next);
        appliedEventIds.add(event.eventId());
        version = event.aggregateVersion();
        return true;
    }

    public boolean canRead(MemberId memberId) {
        Membership membership = memberships.get(memberId);
        return membership != null && membership.hasAppAccess();
    }

    public boolean canCollaborate(MemberId memberId) {
        return state == GroupState.ACTIVE && isActive(memberId);
    }

    public boolean canManageMembers(MemberId memberId) {
        return state == GroupState.ACTIVE && isActiveAdmin(memberId);
    }

    public boolean canCreateExpense(MemberId memberId) {
        return canCollaborate(memberId);
    }

    public boolean canRecordSettlement(MemberId memberId) {
        return isActive(memberId);
    }

    public boolean canCorrect(MemberId memberId) {
        return isActive(memberId);
    }

    public Optional<Membership> membership(MemberId memberId) {
        return Optional.ofNullable(memberships.get(memberId));
    }

    public List<Membership> memberships() {
        List<Membership> list = new ArrayList<>(memberships.values());
        list.sort(Comparator.comparing(membership -> membership.memberId().value()));
        return List.copyOf(list);
    }

    public GroupView view() {
        return GroupView.from(this);
    }

    public GroupId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String currency() {
        return currency;
    }

    public GroupState state() {
        return state;
    }

    public long version() {
        return version;
    }

    private void commit(Map<MemberId, Membership> next) {
        ensureActiveAdmin(next);
        memberships.clear();
        memberships.putAll(next);
        version += 1;
    }

    private static void ensureActiveAdmin(Map<MemberId, Membership> next) {
        if (next.values().stream().noneMatch(Membership::isActiveAdmin)) {
            throw new LastAdminException();
        }
    }

    private int activeAdminCount() {
        return (int) memberships.values().stream().filter(Membership::isActiveAdmin).count();
    }

    private boolean isActive(MemberId memberId) {
        Membership membership = memberships.get(memberId);
        return membership != null && membership.isActive();
    }

    private boolean isActiveAdmin(MemberId memberId) {
        Membership membership = memberships.get(memberId);
        return membership != null && membership.isActiveAdmin();
    }

    private Membership requireMembership(MemberId memberId) {
        Membership membership = memberships.get(memberId);
        if (membership == null) {
            throw new GroupAccessDeniedException();
        }
        return membership;
    }

    private void requireAdmin(MemberId actor) {
        if (!isActiveAdmin(actor)) {
            throw new GroupAccessDeniedException();
        }
    }

    private void requireManager(MemberId actor) {
        requireAdmin(actor);
        requireActiveGroup();
    }

    private void requireActiveGroup() {
        if (state == GroupState.ARCHIVED) {
            throw new GroupRuleException("The group is archived.");
        }
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("group name must be between 1 and 120 characters");
        }
        return trimmed;
    }

    private static String validateCurrency(String currency) {
        Objects.requireNonNull(currency, "currency");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        return currency;
    }
}
