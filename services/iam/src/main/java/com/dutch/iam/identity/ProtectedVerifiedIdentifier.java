package com.dutch.iam.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record ProtectedVerifiedIdentifier(
        UUID identifierId, UUID identityId, IdentifierType type, byte[] lookupDigest,
        byte[] ciphertext, int keyVersion, long identifierVersion, Instant createdAt) {
    public ProtectedVerifiedIdentifier {
        lookupDigest = Arrays.copyOf(lookupDigest, lookupDigest.length);
        ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override public byte[] lookupDigest() { return Arrays.copyOf(lookupDigest, lookupDigest.length); }
    @Override public byte[] ciphertext() { return Arrays.copyOf(ciphertext, ciphertext.length); }
}
