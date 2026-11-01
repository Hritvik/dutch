package com.dutch.iam.session;

import java.time.Instant;
import java.util.UUID;

public record DurableSession(UUID sessionId, UUID identityId, Instant authenticatedAt, Instant expiresAt) {}
