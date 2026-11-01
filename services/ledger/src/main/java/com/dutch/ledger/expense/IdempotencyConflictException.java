package com.dutch.ledger.expense;

/** An idempotency key was reused with a different request payload. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("The idempotency key was reused with a different request.");
    }
}
