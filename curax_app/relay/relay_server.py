import threading
from aiohttp import web
"""
CuraX Local Relay (PC) — optional dev/desktop bridge. WebSocket to phones on 5051.

Production uses curax_app/relay_cloud (Render): same idea, one wss URL, no FCM.
Desktop can POST /relay/alert here or send TCP JSON to port 5050.
"""
import asyncio
import sys
from pathlib import Path

try:
    import websockets
except ImportError:
    print("Install websockets: pip install websockets")
    sys.exit(1)

TCP_PORT = 5050
WS_PORT = 5051
HTTP_PORT = 8080
HOST = "0.0.0.0"

ws_clients = set()
WEB_DIR = Path(__file__).resolve().parent / "web"


async def handle_tcp_client(reader, writer):
    """Receive JSON alert from desktop and broadcast to all WebSocket clients."""
    try:
        data = await reader.read(4096)
        if not data:
            return
        text = data.decode("utf-8", errors="ignore").strip()
        if not text:
            return
        # Forward same JSON to all Android clients
        if ws_clients:
            dead = set()
            for ws in ws_clients:
                try:
                    await ws.send(text)
                except Exception:
                    dead.add(ws)
            for ws in dead:
                ws_clients.discard(ws)
            print(f"  -> Forwarded to {len(ws_clients)} app(s)")
        else:
            print("  -> No app connected, alert not forwarded")
    except Exception as e:
        print(f"  TCP error: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def handle_websocket(ws, path):
    """Register Android client and keep connection open."""
    peer = ws.remote_address
    ws_clients.add(ws)
    print(f"  App connected from {peer} (total: {len(ws_clients)})")
    try:
        async for _ in ws:
            pass
    except Exception:
        pass
    finally:
        ws_clients.discard(ws)
        print(f"  App disconnected from {peer} (remaining: {len(ws_clients)})")


async def main():
    tcp_server = await asyncio.start_server(handle_tcp_client, HOST, TCP_PORT)
    ws_server = await websockets.serve(handle_websocket, HOST, WS_PORT)
    print(f"TCP (desktop): {HOST}:{TCP_PORT}  |  WebSocket (app): {HOST}:{WS_PORT}")
    print("Desktop Server URL = localhost:5050  |  In app use PC IP and port", WS_PORT)

    # HTTP server for POST /relay/alert
    async def alert_handler(request):
        try:
            data = await request.json()
            alert = data.get("alert")
            if alert:
                # Broadcast alert to all WebSocket clients
                dead = set()
                for ws in ws_clients:
                    try:
                        await ws.send(alert)
                    except Exception:
                        dead.add(ws)
                for ws in dead:
                    ws_clients.discard(ws)
                print(f"  -> HTTP alert forwarded to {len(ws_clients)} app(s)")
            return web.Response(text="ok")
        except Exception as e:
            print(f"  HTTP alert error: {e}")
            return web.Response(status=500, text="error")

    app = web.Application()
    app.router.add_post("/relay/alert", alert_handler)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, HOST, HTTP_PORT)
    await site.start()

    async with tcp_server, ws_server:
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
