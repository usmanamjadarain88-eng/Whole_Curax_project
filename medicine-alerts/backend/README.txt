Backend: REST API + Central DB (PostgreSQL).
- Run API locally: cd this folder (medicine-alerts/backend) then  python api_server.py  (stdlib HTTP, no Flask).
  Set DATABASE_URL or CENTRAL_DB_URL for PostgreSQL.
- Schema: apply central_schema.sql (and central_migration_admin_access_code.sql if needed) on your DB.

--- Vercel (serverless Python, /api/*.py) ---
1. In Vercel: New Project -> import Git repo -> Root Directory:  medicine-alerts/backend
2. Each public URL is rewritten to a separate function under api/ (see vercel.json). No Flask, no index.py WSGI wrapper.
3. Environment variables: at minimum DATABASE_URL (Neon). Optional: CENTRAL_DB_URL, DATA_BUS_URL, MAINTENANCE_API_KEY, SIGNUP_OTP_PEPPER, etc.
   Firebase / FCM: this Python API does not load FIREBASE_SERVICE_ACCOUNT_JSON. It stores device FCM tokens in Postgres and sends alert payloads to the Relay WebSocket (RELAY_URL). The Relay service (e.g. curax_app/relay_cloud) reads FIREBASE_PROJECT_ID + FIREBASE_SERVICE_ACCOUNT_JSON and calls FCM — set those on the Relay host (Render/Railway), same as before, not on Vercel unless you run Relay there.
4. Deploy. Test: GET https://<your>.vercel.app/health
5. Alert checks: on Vercel there is no 24/7 background thread. Add Vercel Cron (e.g. every 5–15 min) to POST /maintenance/run-alert-checks with header X-Maintenance-Key matching MAINTENANCE_API_KEY. That runs one full alert sweep for all admins (BackendAlertScheduler). The 23:00 daily email summary only runs if the scheduler background thread is running (local/long-lived host), not from that cron alone.
6. Cold starts: keep requirements.txt minimal; same DATABASE_URL as before.
