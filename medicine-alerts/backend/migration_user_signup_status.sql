-- =============================================================================
-- Curax / medicine-alerts CENTRAL DB — user signup lifecycle (PostgreSQL)
-- Run in SQL editor (once per database). Idempotent where possible.
--
-- Flow (saved spec):
--   PENDING_EMAIL → (email verified) → PENDING_ADMIN → (admin code OK) → ACTIVE
--   PENDING = optional simpler path (admin code only, no email OTP yet)
--
-- Cleanup (required): pending users inactive 24h+ are deleted (see bottom).
-- =============================================================================

-- 1) Lifecycle column (existing rows stay ACTIVE so current apps keep working)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ DEFAULT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

COMMENT ON COLUMN users.account_status IS
    'PENDING_EMAIL | PENDING_ADMIN | PENDING | ACTIVE — gate app/API access until ACTIVE';
COMMENT ON COLUMN users.email_verified_at IS
    'Set when email step succeeds; NULL until verified.';
COMMENT ON COLUMN users.status_changed_at IS
    'Last time account_status changed; optional for TTL-per-stage later.';

-- 2) Allowed values (edit list if you drop PENDING)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'users_account_status_check'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT users_account_status_check
            CHECK (account_status IN (
                'PENDING_EMAIL',
                'PENDING_ADMIN',
                'PENDING',
                'ACTIVE'
            ));
    END IF;
END $$;

-- 3) Backfill legacy rows (default ACTIVE): set timestamps for analytics / email step
UPDATE users
SET
    email_verified_at = COALESCE(email_verified_at, created_at),
    status_changed_at = COALESCE(status_changed_at, updated_at, created_at)
WHERE bot_id IS DISTINCT FROM 'dashboard';

-- 4) Index for scheduled cleanup + admin dashboards
CREATE INDEX IF NOT EXISTS idx_users_account_status_created
    ON users (account_status, created_at);

CREATE INDEX IF NOT EXISTS idx_users_pending_cleanup
    ON users (created_at)
    WHERE account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING');

-- =============================================================================
-- 5) REQUIRED CLEANUP (same rule as POST /maintenance/cleanup-pending for users)
-- Deletes users that stayed PENDING_* (or PENDING) for 24+ hours — not optional.
-- Re-run this DELETE on a schedule in production, or rely on the maintenance API.
-- CASCADE removes medicines, dose_logs, alert_settings, alerts, etc. (per FKs)
-- =============================================================================

DELETE FROM users
WHERE bot_id IS DISTINCT FROM 'dashboard'
  AND account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING')
  AND created_at < NOW() - INTERVAL '24 hours';

-- =============================================================================
-- Done. Next steps (application code, not SQL):
-- - On first signup: INSERT/UPDATE account_status = 'PENDING_EMAIL', status_changed_at = NOW()
-- - After email verify: account_status = 'PENDING_ADMIN'
-- - After admin connection code: account_status = 'ACTIVE'
-- - Protected APIs: reject unless account_status = 'ACTIVE' (403 + message)
-- =============================================================================
