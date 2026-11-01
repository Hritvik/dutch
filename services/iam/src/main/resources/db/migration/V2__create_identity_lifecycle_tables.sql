CREATE TABLE identity (
    identity_id UUID PRIMARY KEY,
    security_state VARCHAR(40) NOT NULL CHECK (security_state IN ('PROFILE_REQUIRED', 'ACTIVE', 'LOCKED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE verified_identifier (
    identifier_id UUID PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES identity(identity_id),
    identifier_type VARCHAR(20) NOT NULL CHECK (identifier_type IN ('EMAIL', 'PHONE')),
    lookup_digest BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,
    key_version INTEGER NOT NULL CHECK (key_version > 0),
    identifier_version BIGINT NOT NULL CHECK (identifier_version > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('VERIFIED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (identifier_type, lookup_digest)
);

CREATE TABLE refresh_token_family (
    family_id UUID PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES identity(identity_id),
    session_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE TABLE refresh_token_digest (
    token_digest BYTEA PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES refresh_token_family(family_id),
    consumed_at TIMESTAMPTZ NOT NULL
);
