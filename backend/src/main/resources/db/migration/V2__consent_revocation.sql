ALTER TABLE trusted_contact
    ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE INDEX idx_contact_consent_status
    ON trusted_contact(device_id, consent_status);
