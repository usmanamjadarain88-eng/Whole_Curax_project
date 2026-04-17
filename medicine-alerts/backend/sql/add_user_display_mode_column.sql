-- Run once on PostgreSQL if the app has not already applied ALTER via central_db helpers.
-- Stores user app display mode: 'default' | 'standalone' (synced with Curax Android POST /user/display-mode).

ALTER TABLE users ADD COLUMN IF NOT EXISTS user_display_mode VARCHAR(32) DEFAULT NULL;

COMMENT ON COLUMN users.user_display_mode IS 'Curax user app mode: default (six-slot) or standalone; optional; admin may override in DB.';
