package com.dutch.ledger.expense;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Outcome> find(String actorMemberId, String command, String idempotencyKey) {
        List<Outcome> outcomes = jdbc.query("""
                SELECT request_hash, response::text AS response
                FROM idempotency_outcome
                WHERE actor_member_id = ? AND command = ? AND idempotency_key = ?
                """, (result, index) -> new Outcome(
                result.getString("request_hash"), result.getString("response")),
                actorMemberId, command, idempotencyKey);
        return outcomes.stream().findFirst();
    }

    @Override
    public boolean reserve(String actorMemberId, String command, String idempotencyKey, String requestHash) {
        return jdbc.update("""
                INSERT INTO idempotency_outcome
                    (actor_member_id, command, idempotency_key, request_hash, response)
                VALUES (?, ?, ?, ?, '{}'::jsonb)
                ON CONFLICT (actor_member_id, command, idempotency_key) DO NOTHING
                """, actorMemberId, command, idempotencyKey, requestHash) == 1;
    }

    @Override
    public void complete(String actorMemberId, String command, String idempotencyKey, String responseJson) {
        jdbc.update("""
                UPDATE idempotency_outcome SET response = CAST(? AS jsonb)
                WHERE actor_member_id = ? AND command = ? AND idempotency_key = ?
                """, responseJson, actorMemberId, command, idempotencyKey);
    }
}
