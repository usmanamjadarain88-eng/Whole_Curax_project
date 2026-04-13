"""Central DB client (stateless per request — new connection via CentralDB each call)."""
from __future__ import annotations

import os


def _backend_dir() -> str:
    return os.path.abspath(os.path.dirname(os.path.dirname(__file__)))


def get_db():
    from central_db import CentralDB
    backend_root = _backend_dir()
    _env_path = os.path.join(backend_root, ".env")
    if os.path.isfile(_env_path):
        try:
            from dotenv import load_dotenv
            # Prefer values from backend/.env over stale DATABASE_URL from the OS
            # (e.g. old Railway/Neon left in Windows user env). Vercel has no .env file in the bundle.
            load_dotenv(_env_path, override=True)
        except ImportError:
            pass
    if not os.environ.get("DATABASE_URL") and not os.environ.get("CENTRAL_DB_URL"):
        for _path in [
            os.path.join(backend_root, "database_url.txt"),
            os.path.join(os.getcwd(), "backend", "database_url.txt"),
            os.path.join(os.getcwd(), "database_url.txt"),
        ]:
            if os.path.isfile(_path):
                try:
                    with open(_path, "r", encoding="utf-8") as _f:
                        for _line in _f:
                            _url = _line.strip()
                            if _url and not _url.startswith("#"):
                                os.environ["DATABASE_URL"] = _url
                                break
                    if os.environ.get("DATABASE_URL"):
                        break
                except OSError:
                    pass
    url = os.environ.get("DATABASE_URL") or os.environ.get("CENTRAL_DB_URL")
    if not url:
        return None
    db = CentralDB(connection_string=url)
    if not db.is_available():
        return None
    return db
