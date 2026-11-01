package com.dutch.iam.session;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSessionRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSessionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    public void save(DurableSession session) {
        jdbc.update("INSERT INTO iam_session(session_id, identity_id, status, authenticated_at, expires_at) VALUES (?, ?, 'ACTIVE', ?, ?)",
                session.sessionId(), session.identityId(), session.authenticatedAt(), session.expiresAt());
    }

    public boolean revoke(UUID sessionId) {
        return jdbc.update("UPDATE iam_session SET status = 'REVOKED', revoked_at = ? WHERE session_id = ? AND status = 'ACTIVE'",
                clock.instant(), sessionId) == 1;
    }
}
