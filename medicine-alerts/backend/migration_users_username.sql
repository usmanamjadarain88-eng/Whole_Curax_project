-- Combined signup display name (first + last from signup_sessions) on users row.
-- Run once on Postgres after migration_signup_sessions_names.sql (first_name/last_name on sessions).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS username VARCHAR(200) DEFAULT NULL;

COMMENT ON COLUMN users.username IS
    'Display handle from signup: trimmed "first_name last_name" (e.g. Usman Amjad). Filled at /signup/link-admin.';
