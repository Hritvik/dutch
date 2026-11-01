package com.dutch.iam.oauth;

import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists only a one-way authorization-code digest and an S256 PKCE challenge. */
public final class JdbcAuthorizationStateRepository {
    private static final String MOBILE_CLIENT_ID = "dutch-mobile";
    private static final String MOBILE_REDIRECT_URI = "dutch://oauth/callback";
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcAuthorizationStateRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    public void save(DurableAuthorizationState state) {
        if (!MOBILE_CLIENT_ID.equals(state.clientId())
                || !MOBILE_REDIRECT_URI.equals(state.redirectUri())) {
            throw new IllegalArgumentException("Client or redirect URI is invalid.");
        }
        jdbc.update("INSERT INTO oauth_authorization_state(authorization_id, identity_id, client_id, redirect_uri, authorization_code_digest, pkce_challenge, pkce_method, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, 'S256', ?, ?)",
                state.authorizationId(), state.identityId(), state.clientId(), state.redirectUri(),
                state.authorizationCodeDigest(), state.pkceChallenge(), state.createdAt(), state.expiresAt());
    }

    public boolean consume(byte[] authorizationCodeDigest) {
        return jdbc.update("UPDATE oauth_authorization_state SET consumed_at = ? WHERE authorization_code_digest = ? AND consumed_at IS NULL AND expires_at > ?",
                clock.instant(), authorizationCodeDigest, clock.instant()) == 1;
    }
}
