package com.dutch.iam.identity;

import java.time.Instant;
import java.util.UUID;

public record Identity(UUID identityId, String securityState, Instant createdAt, Instant updatedAt) {}
