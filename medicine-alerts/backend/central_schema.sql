-- Central DB schema (PostgreSQL). One admin ↔ their users; multiple admins supported.
-- Run this on your PostgreSQL server once (e.g. psql -f central_schema.sql).
-- Existing DB already provisioned? Run migration_user_signup_status.sql for user lifecycle columns.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Admins: from Admin Panel (bot_id + api_key). is_admin = true for all rows here.
-- admin_access_code: unique per admin; use in Android app to identify admin (generated on first save).
CREATE TABLE IF NOT EXISTS admins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    email VARCHAR(255),
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    admin_access_code VARCHAR(32) UNIQUE,
    connection_code VARCHAR(32) UNIQUE,
    fcm_token VARCHAR(255),
    is_admin BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(bot_id, api_key)
);

-- If table already exists without admin_access_code, run: ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_access_code VARCHAR(32) UNIQUE;
-- If table already exists without connection_code, run: ALTER TABLE admins ADD COLUMN IF NOT EXISTS connection_code VARCHAR(32) UNIQUE;

-- Users: from app (bot_id + api_key). Many users can link to one admin (by connection_code).
-- email: used to detect same person re-signing up; previous credentials for same admin+email are removed.
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
    fcm_token VARCHAR(255),
    desktop_linked_at TIMESTAMPTZ DEFAULT NULL,
    -- Signup lifecycle: see migration_user_signup_status.sql for existing DBs.
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

CREATE INDEX IF NOT EXISTS idx_users_admin_id ON users(admin_id);
CREATE INDEX IF NOT EXISTS idx_users_admin_email ON users(admin_id, email);
CREATE INDEX IF NOT EXISTS idx_users_account_status_created ON users(account_status, created_at);
CREATE INDEX IF NOT EXISTS idx_users_pending_cleanup ON users(created_at)
    WHERE account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING');

-- Existing installs created before first_name/last_name/user_display_mode: add columns (no-op if already present).
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name VARCHAR(120) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name VARCHAR(120) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS user_display_mode VARCHAR(32) DEFAULT NULL;

-- Email-first signup (staging). Existing DBs: run migration_signup_sessions.sql
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
CREATE INDEX IF NOT EXISTS idx_signup_sessions_cleanup ON signup_sessions (created_at, account_status);

-- Migration for existing DBs (run once): ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
-- Then (optional): CREATE INDEX IF NOT EXISTS idx_users_admin_email ON users(admin_id, email);

-- If you had UNIQUE(admin_id) and need many users per admin, run:
-- ALTER TABLE users DROP CONSTRAINT IF EXISTS users_admin_id_key;
-- ALTER TABLE users ADD CONSTRAINT users_bot_id_api_key_key UNIQUE (bot_id, api_key);

-- User has linked a desktop at least once (set when they use the 6-digit link code on desktop).
-- Migration for existing DBs: ALTER TABLE users ADD COLUMN IF NOT EXISTS desktop_linked_at TIMESTAMPTZ DEFAULT NULL;

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

-- If table already exists without quantity: ALTER TABLE medicines ADD COLUMN IF NOT EXISTS quantity INT DEFAULT 0;
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
-- If table already exists without ON DELETE CASCADE on user_id: ALTER TABLE sync_logs DROP CONSTRAINT IF EXISTS sync_logs_user_id_fkey; ALTER TABLE sync_logs ADD CONSTRAINT sync_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Desktop link codes: admin creates a code in the app, user enters it on desktop to link desktop to that admin (user view only).
CREATE TABLE IF NOT EXISTS desktop_link_codes (
    code VARCHAR(32) PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_desktop_link_codes_expires ON desktop_link_codes(expires_at);

-- User desktop link codes: user (app) creates a one-time code; desktop enters it to link to that user. Same 5-min, one-time use.
CREATE TABLE IF NOT EXISTS user_desktop_link_codes (
    code VARCHAR(32) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_user_desktop_link_codes_expires ON user_desktop_link_codes(expires_at);

-- When admin deletes a connected user, we store (bot_id, api_key) here so get-role can return 410 "deleted by admin".
-- User cannot delete themselves; only admin can remove a user. Deleted user is informed on next app/desktop request.
CREATE TABLE IF NOT EXISTS deleted_user_notifications (
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    user_name VARCHAR(255),
    deleted_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (bot_id, api_key)
);
CREATE INDEX IF NOT EXISTS idx_deleted_user_notifications_lookup ON deleted_user_notifications(bot_id, api_key);
