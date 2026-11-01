package com.dutch.iam.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcRefreshTokenFamilyService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcRefreshTokenFamilyService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public UUID create(UUID identityId, UUID sessionId, String refreshToken) {
        byte[] digest = digest(refreshToken);
        UUID familyId = UUID.randomUUID();
        jdbc.update("INSERT INTO refresh_token_family(family_id, identity_id, session_id, current_token_digest, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                familyId, identityId, sessionId, digest, clock.instant());
        return familyId;
    }

    public RefreshResult rotate(UUID familyId, String presentedToken, String replacementToken) {
        requireToken(presentedToken);
        requireToken(replacementToken);
        return transactions.execute(status -> rotateInTransaction(familyId, digest(presentedToken), digest(replacementToken)));
    }

    private RefreshResult rotateInTransaction(UUID familyId, byte[] presented, byte[] replacement) {
        var rows = jdbc.query("SELECT current_token_digest, active FROM refresh_token_family WHERE family_id = ? FOR UPDATE",
                (result, row) -> new State(result.getBytes(1), result.getBoolean(2)), familyId);
        if (rows.isEmpty() || !rows.getFirst().active()) return RefreshResult.REAUTHENTICATION_REQUIRED;
        State state = rows.getFirst();
        if (MessageDigest.isEqual(state.currentDigest(), presented)) {
            jdbc.update("INSERT INTO refresh_token_digest(token_digest, family_id, consumed_at) VALUES (?, ?, ?)",
                    presented, familyId, clock.instant());
            jdbc.update("UPDATE refresh_token_family SET current_token_digest = ? WHERE family_id = ?", replacement, familyId);
            return RefreshResult.ROTATED;
        }
        Integer consumed = jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token_digest WHERE family_id = ? AND token_digest = ?", Integer.class, familyId, presented);
        if (consumed != null && consumed > 0) {
            jdbc.update("UPDATE refresh_token_family SET active = FALSE, revoked_at = ? WHERE family_id = ?", clock.instant(), familyId);
        }
        return RefreshResult.REAUTHENTICATION_REQUIRED;
    }

    public boolean isActive(UUID familyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM refresh_token_family WHERE family_id = ?", Boolean.class, familyId));
    }

    private static byte[] digest(String token) {
        requireToken(token);
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable.", exception); }
    }

    private static void requireToken(String token) {
        if (token == null || token.length() < 16 || token.length() > 4096) throw new IllegalArgumentException("Invalid refresh token.");
    }

    private record State(byte[] currentDigest, boolean active) {}
}
