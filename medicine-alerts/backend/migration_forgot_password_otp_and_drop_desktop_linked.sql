-- Run once on PostgreSQL (idempotent). Does not replay older migrations.
-- 1) Forgot-password flow needs OTP columns on users (safe if already added).
-- 2) Removes per-user desktop link timestamp (product no longer tracks this).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_reset_otp_hash VARCHAR(128) DEFAULT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_reset_otp_expires_at TIMESTAMPTZ DEFAULT NULL;

COMMENT ON COLUMN users.password_reset_otp_hash IS 'SHA256 hex (signup OTP scheme) for pending password reset';
COMMENT ON COLUMN users.password_reset_otp_expires_at IS 'TTL for password_reset_otp_hash';

ALTER TABLE users DROP COLUMN IF EXISTS desktop_linked_at;
