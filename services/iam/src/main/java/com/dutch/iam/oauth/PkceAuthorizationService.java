package com.dutch.iam.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Authorization-code proof rules for the public mobile client; no client secret is involved. */
public final class PkceAuthorizationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;
    private final RegisteredMobileClient client;
    private final AtomicReference<Map<String, AuthorizationGrant>> grants = new AtomicReference<>(Map.of());

    public PkceAuthorizationService(Clock clock, RegisteredMobileClient client) {
        this.clock = Objects.requireNonNull(clock);
        this.client = Objects.requireNonNull(client);
    }

    public AuthorizationCodeIssue authorize(AuthorizationRequest request) {
        if (!isValidRequest(request)) {
            return AuthorizationCodeIssue.rejected();
        }
        String code = generateCode();
        String codeDigest = digest(code);
        AuthorizationGrant grant = new AuthorizationGrant(request.redirectUri(), request.codeChallenge(), clock.instant());
        grants.updateAndGet(existing -> copyWith(existing, codeDigest, grant));
        return new AuthorizationCodeIssue(true, code);
    }

    public CodeRedemption redeem(String authorizationCode, String redirectUri, String codeVerifier) {
        if (!isValidVerifier(codeVerifier) || authorizationCode == null || redirectUri == null) {
            return CodeRedemption.REAUTHENTICATION_REQUIRED;
        }
        String codeDigest = digest(authorizationCode);
        AtomicReference<CodeRedemption> outcome = new AtomicReference<>(CodeRedemption.REAUTHENTICATION_REQUIRED);
        grants.updateAndGet(existing -> {
            AuthorizationGrant grant = existing.get(codeDigest);
            if (grant == null) {
                return existing;
            }
            Map<String, AuthorizationGrant> next = without(existing, codeDigest);
            if (grant.redirectUri().equals(redirectUri)
                    && MessageDigest.isEqual(
                    grant.codeChallenge().getBytes(StandardCharsets.UTF_8),
                    s256(codeVerifier).getBytes(StandardCharsets.UTF_8))) {
                outcome.set(CodeRedemption.ACCEPTED);
            }
            return next;
        });
        return outcome.get();
    }

    private boolean isValidRequest(AuthorizationRequest request) {
        return request != null
                && client.clientId().equals(request.clientId())
                && client.redirectUri().equals(request.redirectUri())
                && "S256".equals(request.codeChallengeMethod())
                && isValidChallenge(request.codeChallenge())
                && request.state() != null && request.state().length() >= 16 && request.state().length() <= 1024;
    }

    private static boolean isValidChallenge(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43,128}");
    }

    private static boolean isValidVerifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._~-]{43,128}");
    }

    private static String generateCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, AuthorizationGrant> copyWith(
            Map<String, AuthorizationGrant> values, String key, AuthorizationGrant value) {
        Map<String, AuthorizationGrant> next = new HashMap<>(values);
        next.put(key, value);
        return Map.copyOf(next);
    }

    private static Map<String, AuthorizationGrant> without(Map<String, AuthorizationGrant> values, String key) {
        Map<String, AuthorizationGrant> next = new HashMap<>(values);
        next.remove(key);
        return Map.copyOf(next);
    }

    private static String s256(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    private static String digest(String value) {
        return java.util.HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record AuthorizationGrant(String redirectUri, String codeChallenge, Instant issuedAt) {
    }
}
