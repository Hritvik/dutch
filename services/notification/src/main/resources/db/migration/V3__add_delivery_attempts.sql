ALTER TABLE notification_delivery
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0);

CREATE INDEX notification_delivery_retry_idx
    ON notification_delivery (status, created_at) WHERE status = 'FAILED';
