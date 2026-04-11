"""
Run central_schema.sql (and migration) on the database given by DATABASE_URL.
Use this to set up your hosted DB (e.g. Neon) so the backend can use it.

From repo root:  python -m backend.run_schema
From backend/:   python run_schema.py

Requires: DATABASE_URL in env, or in backend/.env, or in backend/database_url.txt
"""
import os
import sys

# Load .env if present
_backend_dir = os.path.dirname(os.path.abspath(__file__))
_env_path = os.path.join(_backend_dir, ".env")
if os.path.isfile(_env_path):
    try:
        from dotenv import load_dotenv
        load_dotenv(_env_path)
    except ImportError:
        pass

# Load DATABASE_URL from file if not in env
if not os.environ.get("DATABASE_URL") and not os.environ.get("CENTRAL_DB_URL"):
    for _path in [
        os.path.join(_backend_dir, "database_url.txt"),
        os.path.join(os.getcwd(), "backend", "database_url.txt"),
    ]:
        if os.path.isfile(_path):
            try:
                with open(_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#"):
                            os.environ["DATABASE_URL"] = line
                            break
            except Exception:
                pass
            if os.environ.get("DATABASE_URL"):
                break

url = os.environ.get("DATABASE_URL") or os.environ.get("CENTRAL_DB_URL")
if not url:
    print("ERROR: DATABASE_URL not set. Set it in env, backend/.env, or backend/database_url.txt")
    sys.exit(1)

def main():
    try:
        import psycopg2
    except ImportError:
        print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
        sys.exit(1)

    schema_path = os.path.join(_backend_dir, "central_schema.sql")
    migration_paths = [
        os.path.join(_backend_dir, "central_migration_admin_access_code.sql"),
        os.path.join(_backend_dir, "central_migration_admin_email_unique.sql"),
        os.path.join(_backend_dir, "central_migration_desktop_password_hash.sql"),
    ]
    if not os.path.isfile(schema_path):
        print(f"ERROR: {schema_path} not found")
        sys.exit(1)

    print("Connecting to database...")
    conn = psycopg2.connect(url)
    conn.autocommit = True
    cur = conn.cursor()

    def run_sql_file(path):
        with open(path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        # Build statements: only split on ";" that ends a non-comment line (so we don't split inside comments)
        buf = []
        for line in lines:
            stripped = line.strip()
            if stripped.startswith("--"):
                continue
            buf.append(line)
            if stripped.endswith(";"):
                stmt = "".join(buf).strip()
                buf = []
                if stmt:
                    try:
                        cur.execute(stmt)
                    except Exception as e:
                        print(f"  Warning: {e}")
                        raise
        if buf:
            stmt = "".join(buf).strip()
            if stmt:
                cur.execute(stmt)

    try:
        print("Running central_schema.sql...")
        run_sql_file(schema_path)
        print("  central_schema.sql OK.")

        for migration_path in migration_paths:
            if os.path.isfile(migration_path):
                name = os.path.basename(migration_path)
                print(f"Running {name}...")
                run_sql_file(migration_path)
                print(f"  {name} OK.")

        print("Done. Database schema is ready. You can test the backend now.")
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()

if __name__ == "__main__":
    main()
