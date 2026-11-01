-- Recipient-scoped change journal backing mobile incremental sync.
CREATE TABLE member_sync_sequence (
    member_id TEXT PRIMARY KEY CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    next_sequence BIGINT NOT NULL DEFAULT 2 CHECK (next_sequence >= 2)
);

CREATE TABLE member_sync_change (
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    sequence BIGINT NOT NULL CHECK (sequence >= 1),
    resource_type TEXT NOT NULL CHECK (resource_type IN (
        'GROUP', 'EXPENSE', 'DIRECT_EXPENSE', 'SETTLEMENT', 'ACTIVITY', 'SERIES', 'OCCURRENCE')),
    resource_id TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN (
        'upsert', 'tombstone', 'access_changed', 'full_resync_required')),
    resource_version BIGINT NOT NULL CHECK (resource_version >= 0),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    action_id TEXT,
    schema_version TEXT NOT NULL DEFAULT '1',
    correlation_id TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, sequence)
);

CREATE INDEX member_sync_change_resource_idx
    ON member_sync_change (member_id, resource_type, resource_id, sequence DESC);

CREATE INDEX member_sync_change_created_idx
    ON member_sync_change (member_id, created_at);
