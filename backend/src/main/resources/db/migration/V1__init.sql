CREATE TABLE device_account (
    id UUID PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    access_token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE TABLE trusted_contact (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES device_account(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    phone_e164 VARCHAR(16) NOT NULL,
    consent_status VARCHAR(20) NOT NULL CHECK (consent_status IN ('PENDING', 'GRANTED', 'REVOKED')),
    consent_token_hash BYTEA UNIQUE,
    consented_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(device_id, phone_e164)
);

CREATE INDEX idx_contact_device ON trusted_contact(device_id);

CREATE TABLE safety_alert (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES device_account(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash BYTEA NOT NULL,
    category VARCHAR(40) NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    location_captured_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED', 'PROCESSING', 'COMPLETE', 'PARTIAL', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '30 days',
    UNIQUE(device_id, idempotency_key),
    CHECK ((latitude IS NULL AND longitude IS NULL) OR
           (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);

CREATE TABLE whatsapp_delivery (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES safety_alert(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES trusted_contact(id) ON DELETE RESTRICT,
    recipient_phone VARCHAR(16) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'CLAIMED', 'ACCEPTED', 'SENT', 'DELIVERED', 'READ', 'RETRY', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    provider_message_id VARCHAR(255) UNIQUE,
    provider_error_code VARCHAR(80),
    last_error TEXT,
    claimed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(alert_id, contact_id)
);

CREATE INDEX idx_delivery_worker ON whatsapp_delivery(state, next_attempt_at);

CREATE TABLE webhook_event (
    event_id VARCHAR(255) PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
