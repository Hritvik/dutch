CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version > 0),
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (occurred_at) WHERE published_at IS NULL;

CREATE TABLE inbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    producer VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
