Backend: REST API + Central DB (PostgreSQL).
- Run API: from repo root run  python -m backend.api_server
  Or from backend/:  python api_server.py
  Set DATABASE_URL or CENTRAL_DB_URL for PostgreSQL.
- Schema: apply central_schema.sql (and central_migration_admin_access_code.sql if needed) on your DB.
- You can replace this with FastAPI later; keep the same routes and central_db logic.
