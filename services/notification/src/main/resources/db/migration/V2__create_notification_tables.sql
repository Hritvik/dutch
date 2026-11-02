CREATE TABLE app_subject_member_mapping (
    app_subject TEXT PRIMARY KEY CHECK (app_subject ~ '^aps_[A-Za-z0-9_-]{22,128}$'),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'DEPARTED')),
    mapping_version BIGINT NOT NULL CHECK (mapping_version > 0),
    source_event_id UUID NOT NULL UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX app_subject_member_mapping_member_idx
    ON app_subject_member_mapping (member_id) WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE notification_device (
    device_id TEXT PRIMARY KEY CHECK (device_id ~ '^dev_[A-Za-z0-9_-]{16,128}$'),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    platform TEXT NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    push_token TEXT NOT NULL CHECK (char_length(push_token) BETWEEN 8 AND 512),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX notification_device_member_idx
    ON notification_device (member_id) WHERE active;

CREATE TABLE notification_preference (
    member_id TEXT PRIMARY KEY CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    activity_push BOOLEAN NOT NULL DEFAULT true,
    settlement_push BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_delivery (
    delivery_id TEXT PRIMARY KEY CHECK (delivery_id ~ '^not_[A-Za-z0-9_-]{16,128}$'),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    device_id TEXT REFERENCES notification_device (device_id),
    event_id UUID NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('EXPENSE', 'SETTLEMENT', 'OTHER')),
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'SUPPRESSED', 'SKIPPED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, device_id)
);

CREATE INDEX notification_delivery_member_idx
    ON notification_delivery (member_id, created_at);
