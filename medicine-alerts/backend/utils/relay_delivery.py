"""Deliver alerts to curax_app/relay_cloud over WebSocket (no FCM).

Backend opens a short-lived WS to RELAY_URL, sends action=alert, relay forwards
to the target app if that bot_id+api_key is registered on a live socket.
"""
from __future__ import annotations

import asyncio
import json
import os
import time

RELAY_URL = (os.environ.get("RELAY_URL") or "wss://curax-relay.onrender.com").strip()


def wake_relay_if_needed(relay_url: str | None = None) -> None:
    """GET /status on relay HTTP URL (wake Render cold start)."""
    url = (relay_url or RELAY_URL).strip()
    try:
        import urllib.request as _urllib

        http_url = url.replace("wss://", "https://", 1).replace("ws://", "http://", 1).rstrip("/")
        req = _urllib.Request(http_url + "/status", method="GET")
        _urllib.urlopen(req, timeout=45)
    except Exception:
        pass


def send_alert_via_relay(
    bot_id,
    api_key,
    alert_type,
    message,
    user_name=None,
    relay_url: str | None = None,
    **_ignored,
):
    """Send alert via cloud relay WebSocket. Returns (ok, error_message)."""
    bot_id = (bot_id or "").strip()
    api_key = (api_key or "").strip()
    if not bot_id or not api_key:
        return False, "Missing bot_id or api_key"
    url = (relay_url or RELAY_URL).strip()
    payload = {
        "action": "alert",
        "bot_id": bot_id,
        "api_key": api_key,
        "type": alert_type or "alert",
        "message": message or "",
    }
    un = (user_name or "").strip()
    if un:
        payload["user_name"] = un
    last_error = None
    wake_relay_if_needed(url)
    time.sleep(3)
    delays = (0, 5, 15, 30, 45)
    for attempt in range(5):
        if attempt > 0:
            time.sleep(delays[attempt])
        try:
            import websockets

            async def _ws_send():
                async with websockets.connect(url, close_timeout=15, open_timeout=60) as ws:
                    await ws.send(json.dumps(payload))

            asyncio.run(_ws_send())
            return True, None
        except Exception as e:
            last_error = str(e).strip() or repr(e)
            print(f"[relay_delivery] attempt {attempt + 1}/5 ({bot_id[:8]}…): {e}")
    return False, (last_error or "Unknown error")[:200]
