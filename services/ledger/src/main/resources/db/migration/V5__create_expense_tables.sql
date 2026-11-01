-- Immutable expense evidence plus rebuildable balance projections.
CREATE TABLE financial_stream (
    stream_id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL CHECK (scope_type IN ('GROUP', 'DIRECT_PAIR')),
    group_id TEXT REFERENCES member_group (group_id),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    stream_version BIGINT NOT NULL DEFAULT 1 CHECK (stream_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((scope_type = 'GROUP' AND group_id IS NOT NULL)
        OR (scope_type = 'DIRECT_PAIR' AND group_id IS NULL)),
    UNIQUE (group_id, currency, stream_id)
);

CREATE TABLE expense (
    expense_id TEXT PRIMARY KEY CHECK (expense_id ~ '^exp_[A-Za-z0-9_-]{16,128}$'),
    group_id TEXT NOT NULL REFERENCES member_group (group_id),
    description VARCHAR(200) NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_minor_units BIGINT NOT NULL CHECK (total_minor_units > 0),
    split_method TEXT NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENTAGE')),
    algorithm_version TEXT NOT NULL,
    current_revision BIGINT NOT NULL DEFAULT 1 CHECK (current_revision > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (expense_id, group_id)
);

CREATE TABLE expense_revision (
    expense_id TEXT NOT NULL REFERENCES expense (expense_id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    description VARCHAR(200) NOT NULL,
    total_minor_units BIGINT NOT NULL CHECK (total_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    split_method TEXT NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENTAGE')),
    algorithm_version TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (expense_id, revision)
);

CREATE TABLE payer_contribution (
    expense_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    group_id TEXT NOT NULL,
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units > 0),
    PRIMARY KEY (expense_id, revision, member_id),
    FOREIGN KEY (expense_id, revision) REFERENCES expense_revision (expense_id, revision),
    FOREIGN KEY (expense_id, group_id) REFERENCES expense (expense_id, group_id),
    FOREIGN KEY (group_id, member_id) REFERENCES group_membership (group_id, member_id)
);

CREATE TABLE participant_share (
    expense_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    group_id TEXT NOT NULL,
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units > 0),
    PRIMARY KEY (expense_id, revision, member_id),
    FOREIGN KEY (expense_id, revision) REFERENCES expense_revision (expense_id, revision),
    FOREIGN KEY (expense_id, group_id) REFERENCES expense (expense_id, group_id),
    FOREIGN KEY (group_id, member_id) REFERENCES group_membership (group_id, member_id)
);

CREATE TABLE obligation_entry (
    entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expense_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    group_id TEXT NOT NULL,
    stream_id TEXT NOT NULL,
    debtor_member_id TEXT NOT NULL CHECK (debtor_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    creditor_member_id TEXT NOT NULL CHECK (creditor_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    algorithm_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (debtor_member_id <> creditor_member_id),
    FOREIGN KEY (expense_id, revision) REFERENCES expense_revision (expense_id, revision),
    FOREIGN KEY (expense_id, group_id) REFERENCES expense (expense_id, group_id),
    FOREIGN KEY (group_id, currency, stream_id)
        REFERENCES financial_stream (group_id, currency, stream_id),
    FOREIGN KEY (group_id, debtor_member_id) REFERENCES group_membership (group_id, member_id),
    FOREIGN KEY (group_id, creditor_member_id) REFERENCES group_membership (group_id, member_id),
    UNIQUE (expense_id, revision, debtor_member_id, creditor_member_id)
);

CREATE INDEX obligation_entry_stream_idx ON obligation_entry (stream_id, entry_id);

CREATE TABLE member_balance_projection (
    stream_id TEXT NOT NULL REFERENCES financial_stream (stream_id),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance_minor_units BIGINT NOT NULL,
    stream_version BIGINT NOT NULL CHECK (stream_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (stream_id, member_id)
);

-- Each revision's payer contributions and participant shares must equal the
-- revision total, and its Obligation Entries must net to zero. Deferred so the
-- write path can insert the revision, contributions, shares, and entries in any
-- order inside one transaction.
CREATE OR REPLACE FUNCTION expense_revision_balances() RETURNS trigger AS $$
DECLARE
    eid TEXT;
    rev BIGINT;
    expected BIGINT;
    paid BIGINT;
    shared BIGINT;
BEGIN
    eid := COALESCE(NEW.expense_id, OLD.expense_id);
    rev := COALESCE(NEW.revision, OLD.revision);
    SELECT total_minor_units INTO expected FROM expense_revision WHERE expense_id = eid AND revision = rev;
    IF expected IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(minor_units), 0) INTO paid
        FROM payer_contribution WHERE expense_id = eid AND revision = rev;
    SELECT COALESCE(SUM(minor_units), 0) INTO shared
        FROM participant_share WHERE expense_id = eid AND revision = rev;
    IF paid <> expected OR shared <> expected THEN
        RAISE EXCEPTION 'expense % revision % contributions/shares must equal %', eid, rev, expected;
    END IF;
    IF EXISTS (
        WITH entry_net AS (
            SELECT member_id, SUM(amount) AS net FROM (
                SELECT creditor_member_id AS member_id, minor_units AS amount
                FROM obligation_entry WHERE expense_id = eid AND revision = rev
                UNION ALL
                SELECT debtor_member_id AS member_id, -minor_units AS amount
                FROM obligation_entry WHERE expense_id = eid AND revision = rev
            ) entries GROUP BY member_id
        ), position_net AS (
            SELECT member_id, SUM(amount) AS net FROM (
                SELECT member_id, minor_units AS amount
                FROM payer_contribution WHERE expense_id = eid AND revision = rev
                UNION ALL
                SELECT member_id, -minor_units AS amount
                FROM participant_share WHERE expense_id = eid AND revision = rev
            ) positions GROUP BY member_id
        )
        SELECT 1 FROM entry_net FULL OUTER JOIN position_net USING (member_id)
        WHERE COALESCE(entry_net.net, 0) <> COALESCE(position_net.net, 0)
    ) THEN
        RAISE EXCEPTION 'expense % revision % entries must match contributions minus shares', eid, rev;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER expense_revision_requires_balance
AFTER INSERT ON expense_revision
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION expense_revision_balances();

CREATE CONSTRAINT TRIGGER payer_contribution_requires_balance
AFTER INSERT OR UPDATE OR DELETE ON payer_contribution
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION expense_revision_balances();

CREATE CONSTRAINT TRIGGER participant_share_requires_balance
AFTER INSERT OR UPDATE OR DELETE ON participant_share
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION expense_revision_balances();

CREATE CONSTRAINT TRIGGER obligation_entry_requires_balance
AFTER INSERT OR UPDATE OR DELETE ON obligation_entry
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION expense_revision_balances();

-- Financial evidence is insert-only; only the rebuildable projections may change.
CREATE OR REPLACE FUNCTION reject_financial_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is immutable financial evidence', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER expense_immutable BEFORE UPDATE OR DELETE ON expense
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER expense_revision_immutable BEFORE UPDATE OR DELETE ON expense_revision
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER payer_contribution_immutable BEFORE UPDATE OR DELETE ON payer_contribution
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER participant_share_immutable BEFORE UPDATE OR DELETE ON participant_share
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER obligation_entry_immutable BEFORE UPDATE OR DELETE ON obligation_entry
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
