CREATE TABLE sandbox_activation_code (
    id UUID PRIMARY KEY,
    code_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    consumed_device_id UUID REFERENCES device_account(id) ON DELETE SET NULL,
    CHECK (expires_at <= created_at + interval '15 minutes'),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX idx_activation_code_expiry
    ON sandbox_activation_code(expires_at)
    WHERE consumed_at IS NULL;
