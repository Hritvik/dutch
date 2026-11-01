-- W-11: the prototype identity-keyed tables are replaced by app-scoped onboarding state.
-- There is no live UMS population; the milestone starts from an empty development database.
DROP TABLE IF EXISTS outbox_event;
DROP TABLE IF EXISTS inbox_event;
DROP TABLE IF EXISTS member_profile;

CREATE TABLE member_profile (
    member_id TEXT PRIMARY KEY CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    app_registration_id TEXT NOT NULL CHECK (app_registration_id ~ '^[a-z][a-z0-9-]{2,63}$'),
    app_subject TEXT NOT NULL CHECK (app_subject ~ '^aps_[A-Za-z0-9_-]{22,128}$'),
    display_name TEXT CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 120),
    profile_state TEXT NOT NULL CHECK (profile_state IN ('PROFILE_REQUIRED','PROFILE_PROVISIONING','PROFILE_READY')),
    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN ('ACTIVE','SUSPENDED','DEPARTED')),
    mapping_version BIGINT NOT NULL CHECK (mapping_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (app_registration_id, app_subject)
);

CREATE TABLE inbox_event (
    producer TEXT NOT NULL CHECK (producer ~ '^[a-z][a-z0-9-]{1,63}$'),
    event_id TEXT NOT NULL CHECK (event_id ~ '^evt_[A-Za-z0-9_-]{16,128}$'),
    event_type TEXT NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (producer, event_id)
);

CREATE TABLE inbox_quarantine (
    producer TEXT NOT NULL,
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    reason_code TEXT NOT NULL CHECK (reason_code IN ('PAYLOAD_CONFLICT')),
    detected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (producer, event_id)
);

CREATE TABLE inbox_checkpoint (
    consumer TEXT PRIMARY KEY,
    last_event_id TEXT CHECK (last_event_id IS NULL OR last_event_id ~ '^evt_[A-Za-z0-9_-]{16,128}$'),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_event (
    event_id TEXT PRIMARY KEY CHECK (event_id ~ '^evt_[A-Za-z0-9_-]{16,128}$'),
    event_type TEXT NOT NULL CHECK (event_type = 'dutch.member-profile.mapping-changed.v1'),
    schema_version BIGINT NOT NULL CHECK (schema_version = 1),
    producer TEXT NOT NULL CHECK (producer = 'dutch-ums'),
    environment TEXT NOT NULL CHECK (environment IN ('development','test','staging','production')),
    aggregate_type TEXT NOT NULL CHECK (aggregate_type = 'MEMBER_PROFILE'),
    aggregate_id TEXT NOT NULL CHECK (aggregate_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version > 0),
    correlation_id TEXT NOT NULL CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    causation_id TEXT CHECK (causation_id IS NULL OR causation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    destination_app_registration_id TEXT NOT NULL CHECK (destination_app_registration_id ~ '^[a-z][a-z0-9-]{2,63}$'),
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (occurred_at) WHERE published_at IS NULL;
