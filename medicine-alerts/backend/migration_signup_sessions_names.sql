-- Optional: store display name parts during email signup (run once on Postgres).
ALTER TABLE signup_sessions
    ADD COLUMN IF NOT EXISTS first_name VARCHAR(120) DEFAULT '',
    ADD COLUMN IF NOT EXISTS last_name VARCHAR(120) DEFAULT '';
