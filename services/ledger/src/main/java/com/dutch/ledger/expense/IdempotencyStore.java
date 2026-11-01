package com.dutch.ledger.expense;

import java.util.Optional;

/** Durable command idempotency keyed by actor, command, and client key. */
public interface IdempotencyStore {

    Optional<Outcome> find(String actorMemberId, String command, String idempotencyKey);

    boolean reserve(String actorMemberId, String command, String idempotencyKey, String requestHash);

    void complete(String actorMemberId, String command, String idempotencyKey, String responseJson);

    record Outcome(String requestHash, String responseJson) {
    }
}
