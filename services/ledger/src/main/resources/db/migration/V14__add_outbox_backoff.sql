ALTER TABLE outbox_event
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX outbox_event_due_idx
    ON outbox_event (next_attempt_at) WHERE published_at IS NULL;
