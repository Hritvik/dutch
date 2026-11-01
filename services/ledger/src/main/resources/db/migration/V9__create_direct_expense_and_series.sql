-- Direct Expenses, direct-pair obligation entries, and recurring Series.
CREATE TABLE direct_expense (
    direct_expense_id TEXT PRIMARY KEY CHECK (direct_expense_id ~ '^exp_[A-Za-z0-9_-]{16,128}$'),
    description TEXT NOT NULL CHECK (char_length(description) BETWEEN 1 AND 200),
    total_minor_units BIGINT NOT NULL CHECK (total_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    split_method TEXT NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENTAGE')),
    algorithm_version TEXT NOT NULL,
    current_revision BIGINT NOT NULL DEFAULT 1 CHECK (current_revision >= 1),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE direct_expense_revision (
    direct_expense_id TEXT NOT NULL REFERENCES direct_expense (direct_expense_id),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    description TEXT NOT NULL CHECK (char_length(description) BETWEEN 1 AND 200),
    total_minor_units BIGINT NOT NULL CHECK (total_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    split_method TEXT NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENTAGE')),
    algorithm_version TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (direct_expense_id, revision)
);

CREATE TABLE direct_payer_contribution (
    direct_expense_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units > 0),
    PRIMARY KEY (direct_expense_id, revision, member_id),
    FOREIGN KEY (direct_expense_id, revision)
        REFERENCES direct_expense_revision (direct_expense_id, revision)
);

CREATE TABLE direct_participant_share (
    direct_expense_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units >= 0),
    PRIMARY KEY (direct_expense_id, revision, member_id),
    FOREIGN KEY (direct_expense_id, revision)
        REFERENCES direct_expense_revision (direct_expense_id, revision)
);

ALTER TABLE obligation_entry
    ADD COLUMN direct_expense_id TEXT,
    DROP CONSTRAINT IF EXISTS obligation_entry_single_source;

ALTER TABLE obligation_entry
    ADD CONSTRAINT obligation_entry_single_source
        CHECK (num_nonnulls(expense_id, settlement_id, direct_expense_id) = 1);

ALTER TABLE obligation_entry
    ADD CONSTRAINT obligation_entry_direct_fk
        FOREIGN KEY (direct_expense_id, revision)
        REFERENCES direct_expense_revision (direct_expense_id, revision);

CREATE UNIQUE INDEX obligation_entry_direct_pair
    ON obligation_entry (direct_expense_id, revision, debtor_member_id, creditor_member_id)
    WHERE direct_expense_id IS NOT NULL;

-- A direct revision's contributions and shares must equal its total, and its
-- entries must match each member's contribution-minus-share position.
CREATE OR REPLACE FUNCTION direct_revision_balances() RETURNS trigger AS $$
DECLARE
    eid TEXT;
    rev BIGINT;
    expected BIGINT;
    paid BIGINT;
    shared BIGINT;
BEGIN
    eid := COALESCE(NEW.direct_expense_id, OLD.direct_expense_id);
    rev := COALESCE(NEW.revision, OLD.revision);
    IF eid IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT total_minor_units INTO expected
        FROM direct_expense_revision WHERE direct_expense_id = eid AND revision = rev;
    IF expected IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(minor_units), 0) INTO paid
        FROM direct_payer_contribution WHERE direct_expense_id = eid AND revision = rev;
    SELECT COALESCE(SUM(minor_units), 0) INTO shared
        FROM direct_participant_share WHERE direct_expense_id = eid AND revision = rev;
    IF paid <> expected OR shared <> expected THEN
        RAISE EXCEPTION 'direct expense % revision % must equal %', eid, rev, expected;
    END IF;
    IF EXISTS (
        WITH entry_net AS (
            SELECT member_id, SUM(amount) AS net FROM (
                SELECT creditor_member_id AS member_id, minor_units AS amount
                FROM obligation_entry
                WHERE direct_expense_id = eid AND revision = rev
                UNION ALL
                SELECT debtor_member_id AS member_id, -minor_units AS amount
                FROM obligation_entry
                WHERE direct_expense_id = eid AND revision = rev
            ) entries GROUP BY member_id
        ), position_net AS (
            SELECT member_id, SUM(amount) AS net FROM (
                SELECT member_id, minor_units AS amount
                FROM direct_payer_contribution WHERE direct_expense_id = eid AND revision = rev
                UNION ALL
                SELECT member_id, -minor_units AS amount
                FROM direct_participant_share WHERE direct_expense_id = eid AND revision = rev
            ) positions GROUP BY member_id
        )
        SELECT 1 FROM entry_net FULL OUTER JOIN position_net USING (member_id)
        WHERE COALESCE(entry_net.net, 0) <> COALESCE(position_net.net, 0)
    ) THEN
        RAISE EXCEPTION 'direct expense % revision % entries must net to positions', eid, rev;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER direct_revision_requires_balance
AFTER INSERT ON direct_expense_revision
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION direct_revision_balances();

CREATE CONSTRAINT TRIGGER direct_contribution_requires_balance
AFTER INSERT ON direct_payer_contribution
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION direct_revision_balances();

CREATE CONSTRAINT TRIGGER direct_share_requires_balance
AFTER INSERT ON direct_participant_share
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION direct_revision_balances();

CREATE CONSTRAINT TRIGGER direct_entry_requires_balance
AFTER INSERT OR UPDATE OR DELETE ON obligation_entry
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION direct_revision_balances();

CREATE TRIGGER direct_expense_immutable BEFORE UPDATE OR DELETE ON direct_expense
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER direct_revision_immutable BEFORE UPDATE OR DELETE ON direct_expense_revision
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER direct_payer_immutable BEFORE UPDATE OR DELETE ON direct_payer_contribution
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER direct_share_immutable BEFORE UPDATE OR DELETE ON direct_participant_share
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();

-- Recurring Series and reviewable Pending Occurrences. An occurrence never
-- creates an entry or balance; only explicit submission produces an Expense.
CREATE TABLE expense_series (
    series_id TEXT PRIMARY KEY CHECK (series_id ~ '^ser_[A-Za-z0-9_-]{16,128}$'),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('GROUP', 'DIRECT')),
    group_id TEXT REFERENCES member_group (group_id),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    description TEXT NOT NULL CHECK (char_length(description) BETWEEN 1 AND 200),
    total_minor_units BIGINT NOT NULL CHECK (total_minor_units > 0),
    split_method TEXT NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENTAGE')),
    cadence TEXT NOT NULL CHECK (cadence IN ('WEEKLY', 'MONTHLY')),
    due_day INT NOT NULL,
    timezone TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    next_period_start DATE NOT NULL,
    template_version BIGINT NOT NULL DEFAULT 1 CHECK (template_version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((scope_type = 'GROUP' AND group_id IS NOT NULL)
        OR (scope_type = 'DIRECT' AND group_id IS NULL)),
    CHECK ((cadence = 'WEEKLY' AND due_day BETWEEN 1 AND 7)
        OR (cadence = 'MONTHLY' AND due_day BETWEEN 1 AND 31))
);

CREATE TABLE series_payer (
    series_id TEXT NOT NULL REFERENCES expense_series (series_id),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units > 0),
    PRIMARY KEY (series_id, member_id)
);

CREATE TABLE series_participant (
    series_id TEXT NOT NULL REFERENCES expense_series (series_id),
    member_id TEXT NOT NULL CHECK (member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    basis_points INT CHECK (basis_points BETWEEN 1 AND 10000),
    minor_units BIGINT CHECK (minor_units >= 0),
    PRIMARY KEY (series_id, member_id)
);

CREATE TABLE series_occurrence (
    occurrence_id TEXT PRIMARY KEY CHECK (occurrence_id ~ '^occ_[A-Za-z0-9_-]{16,128}$'),
    series_id TEXT NOT NULL REFERENCES expense_series (series_id),
    template_version BIGINT NOT NULL CHECK (template_version >= 1),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'SUBMITTED', 'SKIPPED', 'MISSED')),
    expense_id TEXT CHECK (expense_id ~ '^exp_[A-Za-z0-9_-]{16,128}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (series_id, period_start),
    CHECK (period_end >= period_start),
    CHECK ((status = 'SUBMITTED') = (expense_id IS NOT NULL))
);

CREATE INDEX series_occurrence_series_status_idx
    ON series_occurrence (series_id, status, period_start);
