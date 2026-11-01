package com.dutch.ledger.group;

import java.time.Instant;
import java.util.Objects;

/**
 * A versioned membership fact replayed from UMS. Duplicate event ids are
 * no-ops; older aggregate versions never move the Group backwards.
 */
public record MembershipEvent(
        String eventId,
        long aggregateVersion,
        MemberId memberId,
        MembershipState state,
        GroupRole role,
        Instant occurredAt) {

    public MembershipEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be positive");
        }
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
