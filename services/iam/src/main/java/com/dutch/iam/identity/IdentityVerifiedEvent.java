package com.dutch.iam.identity;

import java.time.Instant;
import java.util.UUID;

public record IdentityVerifiedEvent(
        UUID eventId, UUID identityId, long identityVersion, Instant occurredAt,
        UUID correlationId, UUID causationId) {}
