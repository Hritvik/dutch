-- Durable idempotency outcomes for financial commands.
CREATE TABLE idempotency_outcome (
    actor_member_id TEXT NOT NULL CHECK (actor_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    command TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_member_id, command, idempotency_key)
);
