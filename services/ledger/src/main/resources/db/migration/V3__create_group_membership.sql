-- Group and Membership source records. Rows are retained; removal becomes INACTIVE.
CREATE TABLE member_group (
    group_id TEXT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE group_membership (
    group_id TEXT NOT NULL REFERENCES member_group (group_id),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    state TEXT NOT NULL CHECK (state IN ('INVITED', 'PLACEHOLDER', 'ACTIVE', 'INACTIVE')),
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    joined_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (group_id, member_id)
);

CREATE INDEX group_membership_member_idx ON group_membership (member_id);

CREATE TABLE group_membership_event (
    event_id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES member_group (group_id),
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 1),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    state TEXT NOT NULL CHECK (state IN ('INVITED', 'PLACEHOLDER', 'ACTIVE', 'INACTIVE')),
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, aggregate_version)
);

CREATE INDEX group_membership_event_group_idx ON group_membership_event (group_id, aggregate_version);

-- Every group must retain at least one ACTIVE Admin, checked at commit so a
-- create/insert/update sequence inside one transaction stays valid.
CREATE OR REPLACE FUNCTION group_requires_active_admin() RETURNS trigger AS $$
DECLARE
    target_group TEXT;
BEGIN
    IF TG_TABLE_NAME = 'member_group' THEN
        target_group := NEW.group_id;
    ELSIF TG_OP = 'DELETE' THEN
        target_group := OLD.group_id;
    ELSE
        target_group := NEW.group_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM group_membership
        WHERE group_id = target_group AND state = 'ACTIVE' AND role = 'ADMIN'
    ) THEN
        RAISE EXCEPTION 'group % must retain at least one active Admin', target_group;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER member_group_requires_admin
AFTER INSERT ON member_group
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION group_requires_active_admin();

CREATE CONSTRAINT TRIGGER group_membership_requires_admin
AFTER INSERT OR UPDATE OR DELETE ON group_membership
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION group_requires_active_admin();
