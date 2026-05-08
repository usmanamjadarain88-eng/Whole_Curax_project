-- Password reset OTP for linked app users (PostgreSQL). Run once; idempotent.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_reset_otp_hash VARCHAR(128) DEFAULT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_reset_otp_expires_at TIMESTAMPTZ DEFAULT NULL;

COMMENT ON COLUMN users.password_reset_otp_hash IS 'SHA256 hex (signup OTP scheme) for pending password reset';
COMMENT ON COLUMN users.password_reset_otp_expires_at IS 'TTL for password_reset_otp_hash';
