ALTER TABLE notification_delivery
    ADD COLUMN provider_message_id TEXT,
    ADD COLUMN receipted_at TIMESTAMPTZ;
