package com.dutch.ledger.mapping;

import java.time.Instant;
import java.util.Objects;

/** The Dutch-owned mapping fact consumed from UMS; contains no contact identifiers. */
public record MemberMappingEvent(
        String eventId,
        String appSubject,
        String memberId,
        long mappingVersion,
        String lifecycleState,
        Instant occurredAt) {

    public MemberMappingEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (appSubject == null || !appSubject.matches("^aps_[A-Za-z0-9_-]{22,128}$")) {
            throw new IllegalArgumentException("appSubject does not match the pinned format");
        }
        if (memberId == null || !memberId.matches("^mem_[A-Za-z0-9_-]{16,128}$")) {
            throw new IllegalArgumentException("memberId does not match the pinned format");
        }
        if (mappingVersion < 1) {
            throw new IllegalArgumentException("mappingVersion must be positive");
        }
        if (!Objects.equals(lifecycleState, "ACTIVE")
                && !Objects.equals(lifecycleState, "SUSPENDED")
                && !Objects.equals(lifecycleState, "DEPARTED")) {
            throw new IllegalArgumentException("lifecycleState is not a permitted value");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
