-- Admin desktop linking only (Settings → Desktop linking code on admin app).
-- Safe to re-run.

CREATE TABLE IF NOT EXISTS desktop_link_codes (
    code VARCHAR(16) PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_desktop_link_codes_expires ON desktop_link_codes(expires_at);

-- Retired: users no longer create desktop link codes from the mobile app.
DROP TABLE IF EXISTS user_desktop_link_codes;
