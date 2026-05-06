"""POST /notify_admin — delegates to databus_logic."""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler

from databus_logic import process_notify_admin


class handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        if path not in ("/api/notify_admin", "/notify_admin"):
            self._send_json(404, {"ok": False, "error": "not_found"})
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b"{}"
        status, obj = process_notify_admin(raw)
        self._send_json(status, obj)

    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/")
        if path in ("/api/notify_admin", "/notify_admin"):
            self.send_response(405)
            self.send_header("Allow", "POST")
            self.end_headers()
            return
        self.send_response(404)
        self.end_headers()

    def _send_json(self, status: int, obj: dict):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
