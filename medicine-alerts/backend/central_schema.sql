-- =============================================================================
-- Central DB schema (PostgreSQL) — single source of truth for CuraX / medicine-alerts.
-- Safe to re-run on an existing database (uses IF NOT EXISTS / idempotent ALTERs).
--
--   psql "$DATABASE_URL" -f central_schema.sql
--   or: python -m backend.run_schema
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- admins
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    email VARCHAR(255),
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    admin_access_code VARCHAR(32),
    connection_code VARCHAR(32),
    is_admin BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    desktop_password_hash VARCHAR(128),
    UNIQUE(bot_id, api_key)
);

ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_access_code VARCHAR(32);
ALTER TABLE admins ADD COLUMN IF NOT EXISTS connection_code VARCHAR(32);
ALTER TABLE admins ADD COLUMN IF NOT EXISTS desktop_password_hash VARCHAR(128);
ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_settings JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE UNIQUE INDEX IF NOT EXISTS admins_admin_access_code_uidx ON admins (admin_access_code);
CREATE UNIQUE INDEX IF NOT EXISTS admins_connection_code_uidx ON admins (connection_code);

CREATE UNIQUE INDEX IF NOT EXISTS admins_email_unique
    ON admins (LOWER(TRIM(email)))
    WHERE TRIM(COALESCE(email, '')) <> '';

-- ---------------------------------------------------------------------------
-- admin_mobile_login_challenges (OTP step after password; mobile admin sign-in)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_mobile_login_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_normalized VARCHAR(320) NOT NULL,
    challenge_token VARCHAR(72) NOT NULL,
    otp_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT admin_mobile_challenges_token_len CHECK (char_length(challenge_token) >= 16)
);

CREATE UNIQUE INDEX IF NOT EXISTS admin_mobile_login_challenges_token_uidx
    ON admin_mobile_login_challenges (challenge_token);
CREATE INDEX IF NOT EXISTS admin_mobile_login_challenges_email_idx
    ON admin_mobile_login_challenges (email_normalized);
CREATE INDEX IF NOT EXISTS admin_mobile_login_challenges_exp_idx
    ON admin_mobile_login_challenges (expires_at);

-- ---------------------------------------------------------------------------
-- admin_email_signup_sessions (mobile-only independent admin registration)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_email_signup_sessions (
    email_normalized VARCHAR(320) PRIMARY KEY,
    password_hash TEXT NOT NULL,
    otp_hash VARCHAR(128) NOT NULL,
    otp_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS admin_email_signup_sessions_exp_idx
    ON admin_email_signup_sessions (otp_expires_at);

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    name VARCHAR(255),
    username VARCHAR(200) DEFAULT NULL,
    first_name VARCHAR(120) DEFAULT NULL,
    last_name VARCHAR(120) DEFAULT NULL,
    user_display_mode VARCHAR(32) DEFAULT NULL,
    email VARCHAR(255),
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    email_verified_at TIMESTAMPTZ DEFAULT NULL,
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    password_hash TEXT DEFAULT NULL,
    UNIQUE(bot_id, api_key),
    CONSTRAINT users_account_status_check CHECK (account_status IN (
        'PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING', 'ACTIVE'
    ))
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS username VARCHAR(200) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name VARCHAR(120) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name VARCHAR(120) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS user_display_mode VARCHAR(32) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash TEXT DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_picture TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS health_hub_plans JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE users ADD COLUMN IF NOT EXISTS timezone VARCHAR(64) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_otp_hash VARCHAR(128) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_otp_expires_at TIMESTAMPTZ DEFAULT NULL;

COMMENT ON COLUMN users.password_hash IS
    'PBKDF2 hash; set during signup / link-admin and password reset.';
COMMENT ON COLUMN users.password_reset_otp_hash IS
    'SHA256 hex (signup OTP scheme) for pending password reset';
COMMENT ON COLUMN users.password_reset_otp_expires_at IS
    'TTL for password_reset_otp_hash';
COMMENT ON COLUMN users.username IS
    'Display handle from signup: trimmed first_name + last_name; filled at link-admin.';
COMMENT ON COLUMN users.user_display_mode IS
    'CuraX user app mode: default or standalone; optional.';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'users_account_status_check'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT users_account_status_check
            CHECK (account_status IN (
                'PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING', 'ACTIVE'
            ));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_users_admin_id ON users(admin_id);
CREATE INDEX IF NOT EXISTS idx_users_admin_email ON users(admin_id, email);
CREATE INDEX IF NOT EXISTS idx_users_account_status_created ON users(account_status, created_at);
CREATE INDEX IF NOT EXISTS idx_users_pending_cleanup ON users(created_at)
    WHERE account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING');

UPDATE users
SET
    email_verified_at = COALESCE(email_verified_at, created_at),
    status_changed_at = COALESCE(status_changed_at, updated_at, created_at)
WHERE bot_id IS DISTINCT FROM 'dashboard';

ALTER TABLE users DROP COLUMN IF EXISTS desktop_linked_at;

-- One-time codes: admin Settings → Desktop linking code (PC) and user → Link desktop.
CREATE TABLE IF NOT EXISTS desktop_link_codes (
    code VARCHAR(16) PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_desktop_link_codes_expires ON desktop_link_codes(expires_at);

DROP TABLE IF EXISTS user_desktop_link_codes;

-- ---------------------------------------------------------------------------
-- signup_sessions (email-first signup staging)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS signup_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_normalized VARCHAR(255) NOT NULL,
    password_hash TEXT NOT NULL,
    account_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_EMAIL',
    email_otp_hash VARCHAR(128),
    email_otp_expires_at TIMESTAMPTZ,
    email_verified_at TIMESTAMPTZ,
    first_name VARCHAR(120) DEFAULT '',
    last_name VARCHAR(120) DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT signup_sessions_status_check CHECK (account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN')),
    CONSTRAINT signup_sessions_email_unique UNIQUE (email_normalized)
);

ALTER TABLE signup_sessions ADD COLUMN IF NOT EXISTS first_name VARCHAR(120) DEFAULT '';
ALTER TABLE signup_sessions ADD COLUMN IF NOT EXISTS last_name VARCHAR(120) DEFAULT '';
ALTER TABLE signup_sessions ADD COLUMN IF NOT EXISTS user_display_mode VARCHAR(32) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_signup_sessions_cleanup ON signup_sessions (created_at, account_status);

COMMENT ON COLUMN signup_sessions.user_display_mode IS
    'User-chosen app shell (default|standalone) while PENDING_ADMIN, before users row exists. Copied to users.user_display_mode when admin links the account.';

COMMENT ON TABLE signup_sessions IS
    'Email→OTP→admin code flow. Row removed when link-admin succeeds.';

-- ---------------------------------------------------------------------------
-- medicines, dose_logs, alert_settings, alerts, sync_logs, deleted_user_notifications
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS medicines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    box_id VARCHAR(10),
    dosage VARCHAR(100),
    times JSONB NOT NULL DEFAULT '[]',
    low_stock INT DEFAULT 5,
    quantity INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE medicines ADD COLUMN IF NOT EXISTS quantity INT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_medicines_user_id ON medicines(user_id);

CREATE TABLE IF NOT EXISTS dose_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    medicine_id UUID REFERENCES medicines(id) ON DELETE SET NULL,
    box_id VARCHAR(10),
    taken_at TIMESTAMPTZ DEFAULT NOW(),
    source VARCHAR(20) DEFAULT 'desktop',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dose_logs_user_id ON dose_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_dose_logs_taken_at ON dose_logs(taken_at);

CREATE TABLE IF NOT EXISTS alert_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    settings JSONB NOT NULL DEFAULT '{}',
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id)
);

CREATE INDEX IF NOT EXISTS idx_alert_settings_user_id ON alert_settings(user_id);

COMMENT ON TABLE alert_settings IS
    'Per-user JSON settings. Medical reminders (appointments, prescriptions, lab_tests, custom) live in settings.medical_reminders — not on users table.';

CREATE TABLE IF NOT EXISTS alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    type VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    sent_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS sync_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device VARCHAR(50) NOT NULL,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    last_sync TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS deleted_user_notifications (
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    user_name VARCHAR(255),
    deleted_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (bot_id, api_key)
);

CREATE INDEX IF NOT EXISTS idx_deleted_user_notifications_lookup ON deleted_user_notifications(bot_id, api_key);

-- ---------------------------------------------------------------------------
-- Legacy FCM push removed (relay WebSocket only)
-- ---------------------------------------------------------------------------
ALTER TABLE admins DROP COLUMN IF EXISTS fcm_token;
ALTER TABLE users DROP COLUMN IF EXISTS fcm_token;
ALTER TABLE user_admin_link_requests DROP COLUMN IF EXISTS fcm_token;

-- ---------------------------------------------------------------------------
-- Optional maintenance (uncomment if you want one-off cleanup; not required per deploy)
-- ---------------------------------------------------------------------------
-- DELETE FROM signup_sessions WHERE created_at < NOW() - INTERVAL '24 hours';
-- DELETE FROM users
-- WHERE bot_id IS DISTINCT FROM 'dashboard'
--   AND account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING')
--   AND created_at < NOW() - INTERVAL '24 hours';
