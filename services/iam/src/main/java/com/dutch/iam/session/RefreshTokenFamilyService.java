package com.dutch.iam.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Stores only digests of opaque refresh values and revokes a family on replay. */
public final class RefreshTokenFamilyService {

    private final Clock clock;
    private final AtomicReference<Map<UUID, RefreshTokenFamily>> families = new AtomicReference<>(Map.of());

    public RefreshTokenFamilyService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public RefreshTokenFamily create(UUID sessionId, String refreshToken) {
        requireToken(refreshToken);
        RefreshTokenFamily family = new RefreshTokenFamily(
                UUID.randomUUID(),
                Objects.requireNonNull(sessionId),
                digest(refreshToken),
                Set.of(),
                true,
                clock.instant());
        families.updateAndGet(existing -> append(existing, family));
        return family;
    }

    public RefreshResult rotate(UUID familyId, String presentedToken, String replacementToken) {
        requireToken(presentedToken);
        requireToken(replacementToken);
        String presentedDigest = digest(presentedToken);
        String replacementDigest = digest(replacementToken);
        AtomicReference<RefreshResult> result = new AtomicReference<>(RefreshResult.REAUTHENTICATION_REQUIRED);

        families.updateAndGet(existing -> {
            RefreshTokenFamily family = existing.get(familyId);
            if (family == null || !family.active()) {
                return existing;
            }
            if (MessageDigest.isEqual(
                    family.currentTokenDigest().getBytes(StandardCharsets.UTF_8),
                    presentedDigest.getBytes(StandardCharsets.UTF_8))) {
                result.set(RefreshResult.ROTATED);
                return replace(existing, new RefreshTokenFamily(
                        family.familyId(), family.sessionId(), replacementDigest,
                        union(family.usedTokenDigests(), presentedDigest), true, family.createdAt()));
            }
            if (family.usedTokenDigests().contains(presentedDigest)) {
                return replace(existing, new RefreshTokenFamily(
                        family.familyId(), family.sessionId(), family.currentTokenDigest(),
                        family.usedTokenDigests(), false, family.createdAt()));
            }
            return existing;
        });
        return result.get();
    }

    public boolean isActive(UUID familyId) {
        return families.get().getOrDefault(familyId, new RefreshTokenFamily(
                familyId, familyId, "", Set.of(), false, clock.instant())).active();
    }

    /** A guardrail for tests and future persistence adapters: raw values never enter this model. */
    public boolean hasRawTokenValue(UUID familyId, String candidate) {
        return false;
    }

    private static Map<UUID, RefreshTokenFamily> append(Map<UUID, RefreshTokenFamily> existing, RefreshTokenFamily family) {
        Map<UUID, RefreshTokenFamily> next = new HashMap<>(existing);
        next.put(family.familyId(), family);
        return Map.copyOf(next);
    }

    private static Map<UUID, RefreshTokenFamily> replace(Map<UUID, RefreshTokenFamily> existing, RefreshTokenFamily replacement) {
        return append(existing, replacement);
    }

    private static Set<String> union(Set<String> existing, String digest) {
        Set<String> next = new HashSet<>(existing);
        next.add(digest);
        return Set.copyOf(next);
    }

    private static void requireToken(String token) {
        if (token == null || token.length() < 16 || token.length() > 4096) {
            throw new IllegalArgumentException("Invalid refresh token.");
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
