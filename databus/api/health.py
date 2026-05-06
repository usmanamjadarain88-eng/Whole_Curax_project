"""Health check — delegates to databus_logic (also routed via root main.py on Vercel)."""

import json
from http.server import BaseHTTPRequestHandler

from databus_logic import health_payload


class handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        body = json.dumps(health_payload()).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
