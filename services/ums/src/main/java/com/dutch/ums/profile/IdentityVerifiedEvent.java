package com.dutch.ums.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Local representation of the versioned IAM event. It deliberately has no identifier fields. */
public record IdentityVerifiedEvent(
        UUID eventId, UUID identityId, long identityVersion, Instant occurredAt,
        UUID correlationId, UUID causationId) {
    public IdentityVerifiedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        if (identityVersion < 1) {
            throw new IllegalArgumentException("identityVersion must be positive");
        }
    }

    public IdentityVerifiedEvent(UUID eventId, UUID identityId, long identityVersion, Instant occurredAt) {
        this(eventId, identityId, identityVersion, occurredAt, UUID.randomUUID(), eventId);
    }
}
