CREATE TABLE member_profile (
    member_id UUID PRIMARY KEY,
    identity_id UUID NOT NULL UNIQUE,
    lifecycle_state VARCHAR(20) NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE', 'ANONYMIZED')),
    mapping_version BIGINT NOT NULL CHECK (mapping_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX member_profile_identity_active_idx
    ON member_profile (identity_id) WHERE lifecycle_state = 'ACTIVE';
