CREATE TABLE ums_profile_binding (
    app_subject TEXT PRIMARY KEY CHECK (app_subject ~ '^aps_[A-Za-z0-9_-]{18,128}$'),
    binding_revision BIGINT NOT NULL CHECK (binding_revision > 0),
    profile_state TEXT NOT NULL CHECK (profile_state IN ('PROFILE_REQUIRED', 'PROFILE_READY')),
    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'CLOSED', 'DELETION_PENDING', 'ERASED')),
    lifecycle_reason TEXT CHECK (lifecycle_reason IN
        ('DELETION_REQUESTED', 'DELETION_CANCELLED', 'CLOSED', 'ERASED', 'REOPENED')),
    generation BIGINT CHECK (generation > 0),
    retired_generation BIGINT CHECK (retired_generation > 0),
    profile_revision TEXT,
    content_obligation BOOLEAN NOT NULL,
    app_registration_id TEXT NOT NULL,
    environment TEXT NOT NULL CHECK (environment IN ('development', 'test', 'staging', 'production')),
    source_event_id TEXT NOT NULL UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ums_profile_binding_tombstone_shape CHECK (
        (lifecycle_state = 'ERASED' AND retired_generation IS NOT NULL AND generation IS NULL)
        OR (lifecycle_state <> 'ERASED' AND retired_generation IS NULL AND generation IS NOT NULL)
    )
);

CREATE INDEX ums_profile_binding_grant_idx
    ON ums_profile_binding (app_subject)
    WHERE lifecycle_state = 'ACTIVE' AND profile_state = 'PROFILE_READY';
