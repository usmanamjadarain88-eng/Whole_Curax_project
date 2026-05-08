# Backend deployment: schema

All DDL lives in **`central_schema.sql`** only (no separate migration files). Safe to re-run on an existing PostgreSQL database.

## Fresh or upgraded database

```bash
psql "$DATABASE_URL" -f central_schema.sql
```

Or from repo root:

```bash
python -m backend.run_schema
```

This ensures extensions, tables, indexes, signup/password-reset columns, and removal of retired desktop-link artifacts (`desktop_linked_at`, `desktop_link_codes`, `user_desktop_link_codes`) match current code.

## After deploy

- **410 (deleted by admin):** `deleted_user_notifications` + `get-role` / `verify-credentials` behavior unchanged.
- **Env:** `DATABASE_URL` or `CENTRAL_DB_URL`, optional `RELAY_URL`, `DATA_BUS_URL`, etc.
