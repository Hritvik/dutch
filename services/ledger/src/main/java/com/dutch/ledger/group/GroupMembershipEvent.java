package com.dutch.ledger.group;

import java.time.Instant;
import java.util.Objects;

/** One versioned membership fact for group-membership replay. */
public record GroupMembershipEvent(
        String eventId,
        GroupId groupId,
        long aggregateVersion,
        MemberId memberId,
        MembershipState state,
        GroupRole role,
        Instant occurredAt) {

    public GroupMembershipEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be positive");
        }
    }
}
