-- App-scoped authorization projection fed by UMS. The legacy UUID-keyed
-- identity_member_mapping remains for the transition and is not read by new code.
CREATE TABLE app_subject_member_mapping (
    app_subject TEXT PRIMARY KEY CHECK (app_subject ~ '^aps_[A-Za-z0-9_-]{22,128}$'),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'DEPARTED')),
    mapping_version BIGINT NOT NULL CHECK (mapping_version > 0),
    source_event_id TEXT NOT NULL UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX app_subject_member_mapping_member_idx
    ON app_subject_member_mapping (member_id) WHERE lifecycle_state = 'ACTIVE';
