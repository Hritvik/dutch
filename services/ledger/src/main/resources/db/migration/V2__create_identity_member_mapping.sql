CREATE TABLE identity_member_mapping (
    identity_id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    mapping_version BIGINT NOT NULL CHECK (mapping_version > 0),
    source_event_id UUID NOT NULL UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX identity_member_mapping_active_idx
    ON identity_member_mapping (identity_id) WHERE lifecycle_state = 'ACTIVE';
