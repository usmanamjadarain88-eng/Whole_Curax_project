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

This ensures extensions, tables, indexes, signup/password-reset columns, and admin desktop link table (`desktop_link_codes`) match current code. User desktop link (`user_desktop_link_codes`) is dropped.

If admin desktop linking returns **404** on create-code, run `sql/restore_desktop_link_tables.sql` (or re-run `central_schema.sql`) on the production database, then redeploy the backend.

## After deploy

- **410 (deleted by admin):** `deleted_user_notifications` + `get-role` / `verify-credentials` behavior unchanged.
- **Env:** `DATABASE_URL` or `CENTRAL_DB_URL`, optional `RELAY_URL`, `DATA_BUS_URL`, etc.
