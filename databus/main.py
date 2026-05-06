"""
Vercel Python entrypoint: ASGI `app` (required by Vercel's Python builder).

Local long-lived WebSocket relay stays in server.py (excluded from Vercel via .vercelignore).
"""

from __future__ import annotations

import json

from databus_logic import health_payload, process_notify_admin


async def _read_body(receive):
    body = b""
    while True:
        msg = await receive()
        if msg["type"] != "http.request":
            continue
        body += msg.get("body") or b""
        if not msg.get("more_body"):
            break
    return body


async def _send_json(send, status: int, payload: dict):
    raw = json.dumps(payload).encode("utf-8")
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [
                (b"content-type", b"application/json; charset=utf-8"),
                (b"content-length", str(len(raw)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": raw})


async def _send_empty(send, status: int, headers: list[tuple[bytes, bytes]] | None = None):
    h = headers or []
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": h,
        }
    )
    await send({"type": "http.response.body", "body": b""})


async def app(scope, receive, send):
    if scope["type"] != "http":
        return
    path = (scope.get("path") or "").split("?")[0].rstrip("/")
    method = scope.get("method") or "GET"

    if method == "GET" and path in ("", "/api/health"):
        await _send_json(send, 200, health_payload())
        return

    if method == "POST" and path in ("/notify_admin", "/api/notify_admin"):
        raw = await _read_body(receive)
        status, obj = process_notify_admin(raw)
        await _send_json(send, status, obj)
        return

    if method == "GET" and path in ("/notify_admin", "/api/notify_admin"):
        await _send_empty(send, 405, [(b"allow", b"POST")])
        return

    await _send_empty(send, 404)
