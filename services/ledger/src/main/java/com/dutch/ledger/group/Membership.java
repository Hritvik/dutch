package com.dutch.ledger.group;

import java.time.Instant;
import java.util.Objects;

/** One member's participation in a Group. Rows are never deleted; they become INACTIVE. */
public record Membership(
        MemberId memberId,
        MembershipState state,
        GroupRole role,
        Instant joinedAt,
        Instant updatedAt) {

    public Membership {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean hasAppAccess() {
        return state == MembershipState.ACTIVE || state == MembershipState.INACTIVE;
    }

    public boolean isActive() {
        return state == MembershipState.ACTIVE;
    }

    public boolean isActiveAdmin() {
        return state == MembershipState.ACTIVE && role == GroupRole.ADMIN;
    }

    Membership withState(MembershipState next, Instant now) {
        return new Membership(memberId, next, role, joinedAt, now);
    }

    Membership withRole(GroupRole next, Instant now) {
        return new Membership(memberId, state, next, joinedAt, now);
    }
}
