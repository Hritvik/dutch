package com.dutch.iam.session;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record RefreshTokenFamily(
        UUID familyId,
        UUID sessionId,
        String currentTokenDigest,
        Set<String> usedTokenDigests,
        boolean active,
        Instant createdAt) {

    public RefreshTokenFamily {
        usedTokenDigests = Set.copyOf(usedTokenDigests);
    }
}
