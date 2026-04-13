"""
Local development API (stdlib only, no Flask).

Run from this directory:
  python api_server.py

Production: deploy this folder to Vercel; each route is `api/*.py` (see vercel.json rewrites).
"""
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from utils.dev_router import dispatch


def _flat_qs(qs: str) -> dict:
    return {k: (v[0] if v else "") for k, v in parse_qs(qs).items()}


class AppHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        try:
            code = args[1] if len(args) > 1 else ""
            print("  %s %s -> %s" % (self.command, self.path, code))
            except Exception:
                pass

    def _handle(self) -> None:
        u = urlparse(self.path)
        query = _flat_qs(u.query)
        path = u.path or "/"
        # Browsers request this automatically; we don't serve a site icon.
        if path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length > 0 else b""
        body: dict = {}
        if raw:
            try:
                parsed = json.loads(raw.decode("utf-8"))
                body = parsed if isinstance(parsed, dict) else {}
            except Exception:
                body = {}
        headers = {k.lower(): v for k, v in self.headers.items()}
        status, payload = dispatch(self.command, path, body, query, headers)
        code = int(status)
        if code == 204 or payload is None:
            self.send_response(204)
            self.end_headers()
        return
        data = json.dumps(payload, default=str).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        self._handle()

    def do_POST(self) -> None:
        self._handle()

    def do_PUT(self) -> None:
        self._handle()

    def do_PATCH(self) -> None:
        self._handle()

    def do_DELETE(self) -> None:
        self._handle()


def main() -> None:
    port = int(os.environ.get("PORT", 5050))
    print("")
    print("==========  API SERVER (stdlib, no Flask)  ==========")
    # get_db() loads backend/.env into os.environ first — check URL after that.
    from utils.db import get_db

    db = get_db()
    url = os.environ.get("DATABASE_URL") or os.environ.get("CENTRAL_DB_URL")
    if url:
        print("DATABASE_URL: set (%s...)" % (url[:50] if len(url) > 50 else url))
    else:
        print("DATABASE_URL: NOT SET — use backend/.env, database_url.txt, or env.")
    if db:
        print("Database connection: OK")
    else:
        print("Database connection: FAILED")
    print("Listening on http://0.0.0.0:%s" % port)
    print("")
    ThreadingHTTPServer(("0.0.0.0", port), AppHandler).serve_forever()


if __name__ == "__main__":
    main()
