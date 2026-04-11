"""
Data bus WebSocket server: syncs admin data between Central API, desktop, and app.
- Clients (desktop + app) connect with access_code and client_type ("desktop" | "app").
- When admin is saved/created, Central API calls POST /notify_admin with access_code;
  data bus fetches GET central_api/admin/data and pushes to all clients for that admin.
- All data flows through Central API (single source of truth).
"""
import asyncio
import json
import os
import urllib.request
import urllib.parse
from typing import Dict, Set, Tuple

from aiohttp import WSMsgType, web

# Must point to the backend that serves GET /admin/data (e.g. https://web-production-050d.up.railway.app).
# When notify_admin is called, the data bus fetches from CENTRAL_API_URL/admin/data and pushes to connected clients.
CENTRAL_API_URL = (os.environ.get("CENTRAL_API_URL") or "").strip().rstrip("/")
PORT = int(os.environ.get("PORT", "5052"))

# Data bus does not use a database. It only calls Central API (CENTRAL_API_URL);
# the Central API uses DATABASE_URL / CENTRAL_DB_URL in its own deployment.

# access_code (normalized upper) -> set of (ws, client_type)
clients_by_access_code: Dict[str, Set[Tuple[web.WebSocketResponse, str]]] = {}
# ws -> access_code (for cleanup on disconnect)
ws_to_access_code: Dict[web.WebSocketResponse, str] = {}


def _fetch_admin_data_sync(access_code: str) -> Tuple[bool, dict]:
    """Sync GET Central API admin/data. Returns (ok, data_or_error)."""
    if not CENTRAL_API_URL or not access_code:
        return False, {}
    url = f"{CENTRAL_API_URL}/admin/data?access_code={urllib.parse.quote(access_code)}"
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=15) as r:
            if 200 <= r.status < 300:
                body = r.read().decode("utf-8", errors="replace")
                return True, json.loads(body) if body else {}
            return False, {"message": f"HTTP {r.status}"}
    except Exception as e:
        return False, {"message": str(e)}


async def fetch_admin_data(access_code: str) -> Tuple[bool, dict]:
    """Async wrapper for fetching admin data from Central API."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _fetch_admin_data_sync, access_code)


def normalize_access_code(code: str) -> str:
    return (code or "").strip().upper()


async def push_data_to_clients(access_code: str, payload: dict) -> int:
    """Send data_sync to all clients registered for this access_code. Returns count sent."""
    code = normalize_access_code(access_code)
    if code not in clients_by_access_code:
        return 0
    dead = set()
    msg = json.dumps({"action": "data_sync", "payload": payload})
    sent_count = 0
    for ws, ct in list(clients_by_access_code[code]):
        try:
            if not ws.closed:
                await ws.send_str(msg)
                sent_count += 1
        except Exception:
            dead.add((ws, ct))
    for item in dead:
        clients_by_access_code[code].discard(item)
        ws_to_access_code.pop(item[0], None)
    if not clients_by_access_code[code]:
        del clients_by_access_code[code]
    return sent_count


async def handle_notify_admin(request: web.Request) -> web.Response:
    """
    Central API calls this when admin is saved/created or data changed.
    POST /notify_admin JSON { "access_code": "..." }
    Data bus fetches GET central_api/admin/data and pushes to all clients for that admin.
    """
    if request.method != "POST":
        return web.Response(status=405, text="Method not allowed")
    try:
        body = await request.json()
    except Exception:
        return web.Response(status=400, text="JSON body required")
    access_code = (body.get("access_code") or "").strip()
    if not access_code:
        return web.Response(status=400, text="access_code required")
    code = normalize_access_code(access_code)
    ok, data = await fetch_admin_data(access_code)
    if not ok:
        return web.Response(status=502, text=data.get("message", "Failed to fetch from Central API"))
    sent = await push_data_to_clients(access_code, data)
    return web.json_response({"ok": True, "access_code": code, "clients_notified": sent})


async def handle_websocket(request: web.Request) -> web.WebSocketResponse:
    """Client connects with { "access_code", "client_type": "app"|"desktop"|"user_app" }. All types receive the same data_sync pushes."""
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)

    access_code: str = ""
    client_type: str = ""

    try:
        first = await asyncio.wait_for(ws.receive(), timeout=15.0)
        if first.type != WSMsgType.TEXT:
            await ws.close(code=4000, message=b"text json required")
            return ws

        data = json.loads(first.data)
        access_code = (data.get("access_code") or "").strip()
        client_type = (data.get("client_type") or "app").strip().lower()
        # user_app = linked user's phone in standalone mode (same room as admin; receives data_sync)
        if client_type not in ("app", "desktop", "user_app"):
            client_type = "app"

        if not access_code:
            await ws.close(code=4001, message=b"access_code required")
            return ws

        code = normalize_access_code(access_code)
        if code not in clients_by_access_code:
            clients_by_access_code[code] = set()
        clients_by_access_code[code].add((ws, client_type))
        ws_to_access_code[ws] = code
        print(f"  Data bus: client connected access_code={code[:8]}... client_type={client_type} (total for this admin: {len(clients_by_access_code[code])})")

        # Optional: send initial data on connect so client has latest state immediately
        ok, initial_data = await fetch_admin_data(access_code)
        if ok:
            await ws.send_str(json.dumps({"action": "data_sync", "payload": initial_data}))
        else:
            await ws.send_str(json.dumps({"action": "error", "message": initial_data.get("message", "Failed to load initial data")}))

        async for msg in ws:
            if msg.type in (WSMsgType.CLOSED, WSMsgType.CLOSE, WSMsgType.ERROR):
                break
            if msg.type == WSMsgType.TEXT:
                try:
                    obj = json.loads(msg.data)
                    if obj.get("action") == "ping":
                        await ws.send_str(json.dumps({"action": "pong"}))
                except Exception:
                    pass

    except asyncio.TimeoutError:
        await ws.close(code=4002, message=b"send access_code and client_type first")
    except Exception as e:
        print(f"  Data bus WS error: {e}")
        try:
            await ws.close(code=1011, message=str(e).encode("utf-8")[:123])
        except Exception:
            pass
    finally:
        if access_code:
            code = normalize_access_code(access_code)
            s = clients_by_access_code.get(code)
            if s:
                s.discard((ws, client_type))
                if not s:
                    del clients_by_access_code[code]
            ws_to_access_code.pop(ws, None)
            remaining = len(clients_by_access_code.get(code, set()))
            print(f"  Data bus: client disconnected access_code={code[:8]}... (remaining: {remaining})")
    return ws


async def root_handler(request: web.Request) -> web.StreamResponse:
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return await handle_websocket(request)
    return web.Response(text="Data bus: use WebSocket or POST /notify_admin")


def main() -> None:
    if not CENTRAL_API_URL:
        print("  WARNING: CENTRAL_API_URL not set; notify_admin will fail to fetch data")
    app = web.Application()
    app.router.add_post("/notify_admin", handle_notify_admin)
    app.router.add_route("*", "/", root_handler)
    print(f"Data bus: WebSocket + HTTP port {PORT}")
    print(f"  CENTRAL_API_URL = {CENTRAL_API_URL or '(not set)'}")
    print("  Clients register with access_code + client_type (app|desktop|user_app). Central API calls POST /notify_admin to push data.")
    web.run_app(app, host="0.0.0.0", port=PORT)


if __name__ == "__main__":
    main()
