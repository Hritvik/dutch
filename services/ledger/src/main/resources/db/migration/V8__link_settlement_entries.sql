-- Settlement Obligation Entries and the one-way void guard.
-- Split from V7 so a database that already applied V7 keeps its checksum.
ALTER TABLE obligation_entry
    ADD COLUMN settlement_id TEXT,
    ALTER COLUMN expense_id DROP NOT NULL,
    ALTER COLUMN revision DROP NOT NULL,
    ALTER COLUMN group_id DROP NOT NULL,
    ADD CONSTRAINT obligation_entry_single_source
        CHECK ((expense_id IS NOT NULL) <> (settlement_id IS NOT NULL));

ALTER TABLE obligation_entry
    DROP CONSTRAINT IF EXISTS obligation_entry_entry_kind_check;

ALTER TABLE obligation_entry
    ADD CONSTRAINT obligation_entry_entry_kind_check
        CHECK (entry_kind IN ('CHARGE', 'REVERSAL', 'SETTLEMENT', 'SETTLEMENT_VOID'));

CREATE UNIQUE INDEX obligation_entry_settlement_pair
    ON obligation_entry (settlement_id, stream_id, debtor_member_id, creditor_member_id)
    WHERE settlement_id IS NOT NULL;

ALTER TABLE obligation_entry
    ADD CONSTRAINT obligation_entry_settlement_fk
        FOREIGN KEY (settlement_id) REFERENCES settlement (settlement_id);

-- Allocations are insert-only, so the balance guard only needs the insert path.
DROP TRIGGER IF EXISTS settlement_allocation_requires_balance ON settlement_allocation;

CREATE CONSTRAINT TRIGGER settlement_allocation_requires_balance
AFTER INSERT ON settlement_allocation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION settlement_allocations_balance();

-- The parent may only move ACTIVE -> VOIDED once; its financial fields are fixed.
CREATE OR REPLACE FUNCTION settlement_only_voidable() RETURNS trigger AS $$
BEGIN
    IF NEW.settlement_id = OLD.settlement_id
       AND NEW.payer_member_id = OLD.payer_member_id
       AND NEW.payee_member_id = OLD.payee_member_id
       AND NEW.total_minor_units = OLD.total_minor_units
       AND NEW.currency = OLD.currency
       AND NEW.occurred_at = OLD.occurred_at
       AND OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'settlement is immutable except for a one-way void';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER settlement_only_voidable BEFORE UPDATE ON settlement
FOR EACH ROW EXECUTE FUNCTION settlement_only_voidable();
