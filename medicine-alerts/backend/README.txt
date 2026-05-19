Backend: REST API + Central DB (PostgreSQL).
- Run API locally: cd this folder (medicine-alerts/backend) then  python api_server.py  (stdlib HTTP, no Flask).
  Set DATABASE_URL or CENTRAL_DB_URL for PostgreSQL.
- Schema: apply central_schema.sql on your DB (single file; safe to re-run).

--- Vercel (serverless Python, /api/*.py) ---
1. In Vercel: New Project -> import Git repo -> Root Directory:  medicine-alerts/backend
2. Each public URL is rewritten to a separate function under api/ (see vercel.json). No Flask, no index.py WSGI wrapper.
3. Environment variables: at minimum DATABASE_URL (Neon). Optional: CENTRAL_DB_URL, DATA_BUS_URL, MAINTENANCE_API_KEY, SIGNUP_OTP_PEPPER, etc.
   Alert relay: set RELAY_URL to your cloud relay WebSocket (e.g. wss://curax-relay.onrender.com from curax_app/relay_cloud). Delivery is WebSocket-only — apps keep a live socket; backend sends action=alert. No FCM / Firebase on API or relay host.
4. Deploy. Test: GET https://<your>.vercel.app/health
5. Timed dose notifications: on the phone (AlarmManager). User Gmail (−30/−15/exact/+5/+15) via POST /user/medicine-reminder-email when email_alerts enabled. Admin +15/+30: relay popup + family_email via POST /user/missed-dose-escalate. Stock/expiry: user local + admin relay via POST /notify-event-by-user. BACKEND_TIMED_ALERT_CHECKS=1 only for legacy server cron.
6. Cold starts: keep requirements.txt minimal; same DATABASE_URL as before.
