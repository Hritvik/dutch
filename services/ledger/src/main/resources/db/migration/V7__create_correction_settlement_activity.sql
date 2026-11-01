-- Correction, settlement, and Activity evidence.
ALTER TABLE obligation_entry
    ADD COLUMN entry_kind TEXT NOT NULL DEFAULT 'CHARGE'
        CHECK (entry_kind IN ('CHARGE', 'REVERSAL', 'SETTLEMENT', 'SETTLEMENT_VOID')),
    ADD COLUMN correction_id TEXT,
    ADD COLUMN settlement_id TEXT,
    ALTER COLUMN expense_id DROP NOT NULL,
    ALTER COLUMN revision DROP NOT NULL,
    ALTER COLUMN group_id DROP NOT NULL,
    ADD CONSTRAINT obligation_entry_single_source
        CHECK ((expense_id IS NOT NULL) <> (settlement_id IS NOT NULL));

ALTER TABLE obligation_entry
    DROP CONSTRAINT IF EXISTS obligation_entry_expense_id_revision_debtor_member_id_credi_key;

CREATE UNIQUE INDEX obligation_entry_charge_pair
    ON obligation_entry (expense_id, revision, debtor_member_id, creditor_member_id)
    WHERE entry_kind = 'CHARGE';

CREATE UNIQUE INDEX obligation_entry_reversal_pair
    ON obligation_entry (correction_id, debtor_member_id, creditor_member_id)
    WHERE entry_kind = 'REVERSAL';

CREATE UNIQUE INDEX obligation_entry_settlement_pair
    ON obligation_entry (settlement_id, stream_id, debtor_member_id, creditor_member_id)
    WHERE settlement_id IS NOT NULL;

-- Reversal entries are evidence of a prior revision and are not part of the new
-- revision's contribution/share net; only CHARGE entries participate in the check.
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
                FROM obligation_entry
                WHERE expense_id = eid AND revision = rev AND entry_kind = 'CHARGE'
                UNION ALL
                SELECT debtor_member_id AS member_id, -minor_units AS amount
                FROM obligation_entry
                WHERE expense_id = eid AND revision = rev AND entry_kind = 'CHARGE'
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

CREATE TABLE expense_correction (
    correction_id TEXT PRIMARY KEY CHECK (correction_id ~ '^corr_[A-Za-z0-9_-]{16,128}$'),
    expense_id TEXT NOT NULL,
    from_revision BIGINT NOT NULL,
    to_revision BIGINT NOT NULL,
    actor_member_id TEXT NOT NULL CHECK (actor_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (to_revision = from_revision + 1),
    UNIQUE (expense_id, from_revision),
    FOREIGN KEY (expense_id, from_revision) REFERENCES expense_revision (expense_id, revision),
    FOREIGN KEY (expense_id, to_revision) REFERENCES expense_revision (expense_id, revision)
);

ALTER TABLE obligation_entry
    ADD CONSTRAINT obligation_entry_correction_fk
        FOREIGN KEY (correction_id) REFERENCES expense_correction (correction_id);

CREATE TABLE entry_reversal_link (
    reversal_entry_id BIGINT PRIMARY KEY REFERENCES obligation_entry (entry_id),
    original_entry_id BIGINT NOT NULL UNIQUE REFERENCES obligation_entry (entry_id)
);

CREATE TABLE settlement (
    settlement_id TEXT PRIMARY KEY CHECK (settlement_id ~ '^stl_[A-Za-z0-9_-]{16,128}$'),
    payer_member_id TEXT NOT NULL CHECK (payer_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    payee_member_id TEXT NOT NULL CHECK (payee_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    total_minor_units BIGINT NOT NULL CHECK (total_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'VOIDED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (payer_member_id <> payee_member_id)
);

CREATE TABLE settlement_allocation (
    settlement_id TEXT NOT NULL REFERENCES settlement (settlement_id),
    scope_id TEXT NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    minor_units BIGINT NOT NULL CHECK (minor_units > 0),
    payer_member_id TEXT NOT NULL CHECK (payer_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    payee_member_id TEXT NOT NULL CHECK (payee_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    PRIMARY KEY (settlement_id, scope_id),
    CHECK (payer_member_id <> payee_member_id)
);

CREATE TABLE settlement_void (
    void_id TEXT PRIMARY KEY CHECK (void_id ~ '^void_[A-Za-z0-9_-]{16,128}$'),
    settlement_id TEXT NOT NULL UNIQUE REFERENCES settlement (settlement_id),
    actor_member_id TEXT NOT NULL CHECK (actor_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE obligation_entry
    ADD CONSTRAINT obligation_entry_settlement_fk
        FOREIGN KEY (settlement_id) REFERENCES settlement (settlement_id);

CREATE TABLE activity_entry (
    activity_id TEXT PRIMARY KEY CHECK (activity_id ~ '^act_[A-Za-z0-9_-]{16,128}$'),
    scope_id TEXT NOT NULL,
    actor_member_id TEXT CHECK (actor_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    action TEXT NOT NULL CHECK (action IN (
        'EXPENSE_CREATED', 'EXPENSE_CORRECTED', 'EXPENSE_DELETED', 'EXPENSE_RESTORED',
        'SETTLEMENT_RECORDED', 'SETTLEMENT_VOIDED', 'MEMBERSHIP_CHANGED')),
    target_id TEXT NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Allocations must sum to their parent total and match its payer, payee, and currency.
CREATE OR REPLACE FUNCTION settlement_allocations_balance() RETURNS trigger AS $$
DECLARE
    sid TEXT;
    expected BIGINT;
    parent_payer TEXT;
    parent_payee TEXT;
    parent_currency CHAR(3);
    allocated BIGINT;
BEGIN
    sid := COALESCE(NEW.settlement_id, OLD.settlement_id);
    SELECT total_minor_units, payer_member_id, payee_member_id, currency
        INTO expected, parent_payer, parent_payee, parent_currency
        FROM settlement WHERE settlement_id = sid;
    IF expected IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(minor_units), 0) INTO allocated
        FROM settlement_allocation WHERE settlement_id = sid;
    IF allocated <> expected THEN
        RAISE EXCEPTION 'settlement % allocations must equal %', sid, expected;
    END IF;
    IF EXISTS (
        SELECT 1 FROM settlement_allocation
        WHERE settlement_id = sid
          AND (payer_member_id <> parent_payer
            OR payee_member_id <> parent_payee
            OR currency <> parent_currency)
    ) THEN
        RAISE EXCEPTION 'settlement % allocations do not match the parent', sid;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER settlement_requires_balance
AFTER INSERT ON settlement
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION settlement_allocations_balance();

CREATE CONSTRAINT TRIGGER settlement_allocation_requires_balance
AFTER INSERT OR UPDATE OR DELETE ON settlement_allocation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION settlement_allocations_balance();

CREATE TRIGGER expense_correction_immutable BEFORE UPDATE OR DELETE ON expense_correction
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER entry_reversal_link_immutable BEFORE UPDATE OR DELETE ON entry_reversal_link
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER settlement_allocation_immutable BEFORE UPDATE OR DELETE ON settlement_allocation
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER settlement_void_immutable BEFORE UPDATE OR DELETE ON settlement_void
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE TRIGGER activity_entry_immutable BEFORE UPDATE OR DELETE ON activity_entry
FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
