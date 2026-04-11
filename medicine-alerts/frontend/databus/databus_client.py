"""
Data bus WebSocket client for desktop. Runs in a background thread; puts data_sync
payloads into a queue so the main thread can apply them. No polling: backend pushes
only when real data changes. When disconnected we reconnect with exponential backoff
so we don't hammer the server every few seconds.
"""
import asyncio
import json
import queue
import threading
import time

INITIAL_RECONNECT_DELAY = 5.0   # first reconnect after 5s
MAX_RECONNECT_DELAY = 120.0      # cap at 2 min when server is down
BACKOFF_MULTIPLIER = 1.5
RECONNECT_LOG_INTERVAL = 60.0    # log at most once per 60s when disconnected


def run_databus_client(ws_url: str, access_code: str, out_queue: queue.Queue, stop_event: threading.Event):
    """
    Connect to data bus (WebSocket), register with access_code and client_type=desktop,
    and push every data_sync payload into out_queue. Backend sends only when data
    actually changes — no polling. Runs until stop_event is set.
    ws_url: e.g. ws://127.0.0.1:5052 or wss://databus.onrender.com
    """
    if not ws_url or not access_code:
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

    code = (access_code or "").strip()
    if not code:
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
