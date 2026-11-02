-- Real outbox envelope, claim/relay metadata, and a dead-letter record.
ALTER TABLE outbox_event
    ALTER COLUMN aggregate_id TYPE TEXT USING aggregate_id::text;

ALTER TABLE outbox_event
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    ADD COLUMN producer TEXT NOT NULL DEFAULT 'ledger',
    ADD COLUMN correlation_id TEXT,
    ADD COLUMN causation_id UUID,
    ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN last_error TEXT;

CREATE INDEX outbox_event_claim_idx
    ON outbox_event (occurred_at) WHERE published_at IS NULL;

CREATE TABLE outbox_dead_letter (
    event_id UUID PRIMARY KEY REFERENCES outbox_event (event_id),
    failed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT NOT NULL
);
