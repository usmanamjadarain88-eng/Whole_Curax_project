"""
ASGI entrypoint required by Vercel's Python preset (``framework: python``).

With that preset, this ``app`` receives **all** HTTP traffic; individual
``api/*.py`` files are not used on Vercel. Routing is delegated to
``utils.dev_router`` (same logic as the local stdlib server).

Vercel may pass ASGI ``scope`` fields as ``str`` or ``bytes``; normalize both.
"""
from __future__ import annotations

import json
import os
import sys
import traceback
from typing import Any, Dict, List, Tuple, Union
from urllib.parse import parse_qs, urlparse

_BACKEND_DIR = os.path.dirname(os.path.abspath(__file__))
if _BACKEND_DIR not in sys.path:
    sys.path.insert(0, _BACKEND_DIR)

from utils import dev_router

StrOrBytes = Union[str, bytes]


def _as_str(v: StrOrBytes | None, default: str = "") -> str:
    if v is None:
        return default
    if isinstance(v, bytes):
        return v.decode("utf-8", "replace")
    return str(v)


def _as_bytes(v: StrOrBytes | None) -> bytes:
    if v is None:
        return b""
    if isinstance(v, bytes):
        return v
    return str(v).encode("utf-8")


def _scope_http_path(scope: dict) -> str:
    root = _as_str(scope.get("root_path"), "")
    path = _as_str(scope.get("path"), "/")
    return (root or "") + (path or "/")


def _headers_from_scope(scope: dict) -> Dict[str, str]:
    raw = scope.get("headers") or []
    out: Dict[str, str] = {}
    for item in raw:
        if not item or len(item) != 2:
            continue
        k, v = item[0], item[1]
        out[_as_str(k, "").lower()] = _as_str(v, "")
    return out


def _merge_query(parsed_query: str, scope: dict) -> Dict[str, str]:
    out: Dict[str, str] = {}
    if parsed_query:
        for k, v in parse_qs(parsed_query, keep_blank_values=True).items():
            if v:
                out[k] = v[0]
    qsb = scope.get("query_string")
    if qsb:
        qs = _as_str(qsb)
        for k, v in parse_qs(qs, keep_blank_values=True).items():
            if v and k not in out:
                out[k] = v[0]
    return out


async def _read_body(receive) -> bytes:
    chunks: List[bytes] = []
    while True:
        msg = await receive()
        if msg["type"] == "http.disconnect":
            break
        if msg["type"] == "http.request":
            chunks.append(_as_bytes(msg.get("body")))
            if not msg.get("more_body"):
                break
            continue
        break
    return b"".join(chunks)


async def _send_json(send, status: int, payload: dict) -> None:
    body = json.dumps(payload, default=str).encode("utf-8")
    headers = [
        (b"content-type", b"application/json; charset=utf-8"),
        (b"content-length", str(len(body)).encode("ascii")),
        (b"access-control-allow-origin", b"*"),
    ]
    await send({"type": "http.response.start", "status": status, "headers": headers})
    await send({"type": "http.response.body", "body": body, "more_body": False})


def _http_method(scope: dict) -> str:
    return _as_str(scope.get("method"), "GET").upper()


async def app(scope, receive, send):
    if scope["type"] == "lifespan":
        while True:
            message = await receive()
            if message["type"] == "lifespan.startup":
                await send({"type": "lifespan.startup.complete"})
            elif message["type"] == "lifespan.shutdown":
                await send({"type": "lifespan.shutdown.complete"})
                return

    if scope["type"] != "http":
        return

    try:
        method = _http_method(scope)
        raw_path = _scope_http_path(scope)
        parsed = urlparse(raw_path)
        path_only = parsed.path or "/"
        query = _merge_query(parsed.query, scope)
        hdr = _headers_from_scope(scope)

        body: dict = {}
        raw = await _read_body(receive)
        if raw and method in ("POST", "PUT", "PATCH", "DELETE"):
            try:
                parsed_body = json.loads(raw.decode("utf-8"))
                body = parsed_body if isinstance(parsed_body, dict) else {}
            except (json.JSONDecodeError, UnicodeDecodeError):
                body = {}

        dispatch_path = dev_router.normalize_vercel_api_path(method, path_only, query)
        status, payload = dev_router.dispatch(method, dispatch_path, body, query, hdr)
        code = int(status)

        if code == 204 or payload is None:
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b"", "more_body": False})
            return

        out_body = json.dumps(payload, default=str).encode("utf-8")
        headers = [
            (b"content-type", b"application/json; charset=utf-8"),
            (b"content-length", str(len(out_body)).encode("ascii")),
            (b"access-control-allow-origin", b"*"),
        ]
        await send({"type": "http.response.start", "status": code, "headers": headers})
        await send({"type": "http.response.body", "body": out_body, "more_body": False})
    except Exception as e:
        tb = traceback.format_exc()
        try:
            await _send_json(
                send,
                500,
                {
                    "message": str(e)[:500],
                    "trace": tb[-4000:] if len(tb) > 4000 else tb,
                },
            )
        except Exception:
            pass
