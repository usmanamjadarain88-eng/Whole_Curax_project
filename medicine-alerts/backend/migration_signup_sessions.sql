-- =============================================================================
-- Email-first signup staging (PostgreSQL). Run after migration_user_signup_status.sql
-- Idempotent. Does NOT modify behavior of existing tables beyond optional column.
-- =============================================================================

-- Optional: store password hash on linked user row (for future central auth)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_hash TEXT DEFAULT NULL;

COMMENT ON COLUMN users.password_hash IS
    'Optional PBKDF2 hash; set when user completes /signup/link-admin after email verify.';

CREATE TABLE IF NOT EXISTS signup_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_normalized VARCHAR(255) NOT NULL,
    password_hash TEXT NOT NULL,
    account_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_EMAIL',
    email_otp_hash VARCHAR(128),
    email_otp_expires_at TIMESTAMPTZ,
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT signup_sessions_status_check CHECK (account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN')),
    CONSTRAINT signup_sessions_email_unique UNIQUE (email_normalized)
);

CREATE INDEX IF NOT EXISTS idx_signup_sessions_cleanup
    ON signup_sessions (created_at, account_status);

COMMENT ON TABLE signup_sessions IS
    'Curax email→OTP→admin code flow. Row deleted when /signup/link-admin succeeds.';

-- Required: remove abandoned signup rows (never completed) after 24 hours
DELETE FROM signup_sessions
WHERE created_at < NOW() - INTERVAL '24 hours';
