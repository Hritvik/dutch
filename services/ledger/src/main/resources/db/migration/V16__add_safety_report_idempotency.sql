ALTER TABLE safety_report
    ADD COLUMN idempotency_key TEXT CHECK (idempotency_key IS NULL OR char_length(idempotency_key) BETWEEN 1 AND 200);

CREATE UNIQUE INDEX safety_report_reporter_key_idx
    ON safety_report (reporter_member_id, idempotency_key);
