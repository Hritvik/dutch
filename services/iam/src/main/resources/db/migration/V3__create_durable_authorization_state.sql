CREATE TABLE iam_session (
    session_id UUID PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES identity(identity_id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    authenticated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE TABLE oauth_authorization_state (
    authorization_id UUID PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES identity(identity_id),
    client_id VARCHAR(100) NOT NULL,
    redirect_uri VARCHAR(500) NOT NULL,
    authorization_code_digest BYTEA NOT NULL UNIQUE,
    pkce_challenge VARCHAR(128) NOT NULL,
    pkce_method VARCHAR(10) NOT NULL CHECK (pkce_method = 'S256'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

ALTER TABLE refresh_token_family
    ADD COLUMN current_token_digest BYTEA;

-- Families created before digest rotation became durable cannot be recovered safely.
-- Revoke them during the additive upgrade instead of inventing token material.
UPDATE refresh_token_family
SET active = FALSE,
    revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
WHERE current_token_digest IS NULL;

ALTER TABLE refresh_token_family
    ADD CONSTRAINT refresh_token_family_active_digest_ck
    CHECK (NOT active OR current_token_digest IS NOT NULL) NOT VALID;

ALTER TABLE refresh_token_family
    VALIDATE CONSTRAINT refresh_token_family_active_digest_ck;

CREATE INDEX refresh_token_family_identity_idx ON refresh_token_family(identity_id);
CREATE INDEX iam_session_identity_idx ON iam_session(identity_id);
