# Backend deployment: schema and queries to run

Use this when deploying the backend with updated `central_db.py` and `api_server.py`.

---

## 1. Fresh database (first-time deploy)

Run the full schema once (PostgreSQL):

```bash
psql "$DATABASE_URL" -f central_schema.sql
```

Or from `backend/`:

```bash
psql "$DATABASE_URL" -f central_schema.sql
```

This creates (if not exists):

- `pgcrypto` extension
- `admins` (with `admin_access_code`, `connection_code`, `fcm_token`)
- `users` (with `email`, `fcm_token`)
- `medicines`, `dose_logs`, `alert_settings`, `alerts`, `sync_logs`
- `desktop_link_codes` (admin-created codes for linking desktop)
- `user_desktop_link_codes` (user-created one-time codes for “Use existing admin”)
- `deleted_user_notifications` (for 410 when admin removes a user)
- All indexes

---

## 2. Existing database (already have admins/users, etc.)

If the DB already has `admins` and `users` but was created before these features, run only the following.

### 2a. New tables (run in order)

```sql
-- Desktop link codes: admin creates code in app, desktop links to that admin (user view).
CREATE TABLE IF NOT EXISTS desktop_link_codes (
    code VARCHAR(32) PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_desktop_link_codes_expires ON desktop_link_codes(expires_at);

-- User desktop link codes: user creates one-time code in app; desktop enters it for "Use existing admin".
CREATE TABLE IF NOT EXISTS user_desktop_link_codes (
    code VARCHAR(32) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_user_desktop_link_codes_expires ON user_desktop_link_codes(expires_at);

-- When admin deletes a user: store (bot_id, api_key) so get-role/verify-credentials return 410.
CREATE TABLE IF NOT EXISTS deleted_user_notifications (
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    user_name VARCHAR(255),
    deleted_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (bot_id, api_key)
);
CREATE INDEX IF NOT EXISTS idx_deleted_user_notifications_lookup ON deleted_user_notifications(bot_id, api_key);
```

### 2b. Optional: add missing columns to existing tables

Only if your `admins` / `users` were created before these columns existed:

```sql
-- Admins: for access_code login and FCM
ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_access_code VARCHAR(32) UNIQUE;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS connection_code VARCHAR(32) UNIQUE;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(255);

-- Users: for email dedup and FCM
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_users_admin_email ON users(admin_id, email);
```

---

## 3. Verification checklist (after deploy)

- **FCM / alerts**
  - `save-credentials` (POST) accepts `fcm_token` and stores it in `admins` or `users`.
  - `/notify-event` (admin) and `/notify-event-by-user` (user → admin) send to relay (`RELAY_URL`), which pushes to the app (FCM/WebSocket).
  - Alerts are also written to `alerts` for the admin Alerts tab.

- **Desktop link (Use existing admin)**
  - User creates a code in the app (user_desktop_link_codes).
  - Desktop calls POST `/user/desktop-by-code` with that code.
  - Backend uses `get_user_by_desktop_link_code` (joins `users` + `admins`) and returns `user_id`, `user_name`, `bot_id`, `api_key`, `admin_id`, `admin_name`; code is consumed (deleted).

- **410 (deleted by admin)**
  - When admin deletes a user, backend inserts into `deleted_user_notifications`.
  - `get-role` and `verify-credentials` (and `notify-event-by-user`, etc.) check this table and return 410 with `reason: "deleted_by_admin"` and message “The admin has removed you from their account.”

- **Env (backend)**
  - `DATABASE_URL` or `CENTRAL_DB_URL`: PostgreSQL connection string.
  - `RELAY_URL` (optional): default `wss://curax-relay.onrender.com` for push alerts.
  - `DATA_BUS_URL` (optional): for notifying desktop/app of data changes.

---

## 4. Single copy-paste script (existing DB, new tables + safe column adds)

Run this on an **existing** database to add only what’s missing (idempotent):

```sql
-- New tables
CREATE TABLE IF NOT EXISTS desktop_link_codes (
    code VARCHAR(32) PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_desktop_link_codes_expires ON desktop_link_codes(expires_at);

CREATE TABLE IF NOT EXISTS user_desktop_link_codes (
    code VARCHAR(32) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_user_desktop_link_codes_expires ON user_desktop_link_codes(expires_at);

CREATE TABLE IF NOT EXISTS deleted_user_notifications (
    bot_id VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    user_name VARCHAR(255),
    deleted_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (bot_id, api_key)
);
CREATE INDEX IF NOT EXISTS idx_deleted_user_notifications_lookup ON deleted_user_notifications(bot_id, api_key);

-- Optional: add columns if missing (safe to run multiple times)
ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_access_code VARCHAR(32) UNIQUE;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS connection_code VARCHAR(32) UNIQUE;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_users_admin_email ON users(admin_id, email);
```

Then deploy your updated `central_db.py` and `api_server.py`.
