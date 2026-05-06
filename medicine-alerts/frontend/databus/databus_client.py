"""
Data bus client for desktop: WebSocket (self-hosted server.py) or Ably (Vercel + DATABUS_ABLY_SUBSCRIBE_KEY).
Puts data_sync payloads into out_queue; backend pushes when data changes.
"""
import asyncio
import json
import os
import queue
import threading
import time

INITIAL_RECONNECT_DELAY = 5.0   # first reconnect after 5s
MAX_RECONNECT_DELAY = 120.0      # cap at 2 min when server is down
BACKOFF_MULTIPLIER = 1.5
RECONNECT_LOG_INTERVAL = 60.0    # log at most once per 60s when disconnected


def run_databus_client(ws_url: str, access_code: str, out_queue: queue.Queue, stop_event: threading.Event):
    """
    Real-time admin sync. Two transports:

    - If env DATABUS_ABLY_SUBSCRIBE_KEY is set: subscribe to Ably channel admin:<ACCESS_CODE_UPPER>
      (matches Vercel POST /notify_admin fan-out). ws_url is ignored.
    - Else: classic WebSocket to ws_url; register with access_code and client_type=desktop.

    Runs until stop_event is set.
    """
    code = (access_code or "").strip()
    if not code:
        return

    ably_key = (os.environ.get("DATABUS_ABLY_SUBSCRIBE_KEY") or "").strip()
    if ably_key:
        reconnect_delay = INITIAL_RECONNECT_DELAY
        while not stop_event.is_set():
            try:
                connected = asyncio.run(_ably_connect_loop(code, out_queue, stop_event, ably_key))
                if connected:
                    reconnect_delay = INITIAL_RECONNECT_DELAY
            except Exception:
                pass
            if stop_event.is_set():
                break
            time.sleep(reconnect_delay)
            reconnect_delay = min(MAX_RECONNECT_DELAY, reconnect_delay * BACKOFF_MULTIPLIER)
        return

    if not ws_url:
        return
    url = (ws_url or "").strip()
    if url.startswith("http://"):
        url = "ws://" + url[7:]
    elif url.startswith("https://"):
        url = "wss://" + url[8:]
    elif not url.startswith("ws"):
        url = "wss://" + url if "://" not in url else url
    url = url.rstrip("/")

    try:
        import websockets
    except ImportError:
        return

    reconnect_delay = INITIAL_RECONNECT_DELAY

    while not stop_event.is_set():
        try:
            connected = asyncio.run(_connect_loop(url, code, out_queue, stop_event))
            if connected:
                reconnect_delay = INITIAL_RECONNECT_DELAY  # reset backoff after good connection
        except Exception:
            pass
        if stop_event.is_set():
            break
        time.sleep(reconnect_delay)
        reconnect_delay = min(MAX_RECONNECT_DELAY, reconnect_delay * BACKOFF_MULTIPLIER)


async def _ably_connect_loop(
    access_code: str, out_queue: queue.Queue, stop_event: threading.Event, ably_key: str
) -> bool:
    """Subscribe via Ably; returns True if subscribe/attach succeeded at least once."""
    from ably import AblyRealtime

    client = AblyRealtime(ably_key)
    attached_ok = False
    try:
        ch = client.channels.get(f"admin:{access_code.strip().upper()}")

        async def listener(message):
            try:
                raw = message.data
                if isinstance(raw, dict):
                    obj = raw
                elif isinstance(raw, str):
                    obj = json.loads(raw)
                else:
                    obj = json.loads(str(raw))
                if obj.get("action") == "data_sync" and "payload" in obj:
                    out_queue.put(obj["payload"])
            except Exception:
                pass

        await ch.subscribe(listener)
        attached_ok = True
        while not stop_event.is_set():
            await asyncio.sleep(0.25)
    except Exception:
        attached_ok = False
    finally:
        try:
            await client.close()
        except Exception:
            pass
    return attached_ok


async def _connect_loop(ws_url: str, access_code: str, out_queue: queue.Queue, stop_event: threading.Event) -> bool:
    """Connect and receive data_sync only when backend pushes. Returns True if we had a connection (reset backoff)."""
    import websockets
    try:
        async with websockets.connect(ws_url, close_timeout=2, open_timeout=10) as ws:
            await ws.send(json.dumps({"access_code": access_code, "client_type": "desktop"}))
            while not stop_event.is_set():
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=30)
                except asyncio.TimeoutError:
                    continue
                except Exception:
                    break
                try:
                    obj = json.loads(msg)
                    if obj.get("action") == "data_sync" and "payload" in obj:
                        out_queue.put(obj["payload"])
                except Exception:
                    pass
            return True  # was connected; reset backoff for next run
    except Exception:
        return False  # connect failed; keep backoff
