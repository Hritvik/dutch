package com.dutch.iam.oauth;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record DurableAuthorizationState(
        UUID authorizationId, UUID identityId, String clientId, String redirectUri,
        byte[] authorizationCodeDigest, String pkceChallenge, Instant createdAt, Instant expiresAt) {
    public DurableAuthorizationState {
        authorizationCodeDigest = Arrays.copyOf(authorizationCodeDigest, authorizationCodeDigest.length);
    }
    @Override public byte[] authorizationCodeDigest() {
        return Arrays.copyOf(authorizationCodeDigest, authorizationCodeDigest.length);
    }
}
