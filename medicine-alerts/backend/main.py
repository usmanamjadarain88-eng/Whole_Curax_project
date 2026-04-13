"""
ASGI entrypoint required by Vercel's Python preset (``framework: python``).

With that preset, this ``app`` receives **all** HTTP traffic; individual
``api/*.py`` files are not used on Vercel. Routing is delegated to
``utils.dev_router`` (same logic as the local stdlib server).
"""
from __future__ import annotations

import json
from typing import Any, Dict, List, Tuple
from urllib.parse import parse_qs, urlparse

from utils import dev_router


def _headers_from_scope(scope: dict) -> Dict[str, str]:
    raw: List[Tuple[bytes, bytes]] = scope.get("headers") or []
    return {k.decode("latin-1").lower(): v.decode("latin-1", "replace") for k, v in raw}


def _merge_query(parsed_query: str, scope: dict) -> Dict[str, str]:
    out: Dict[str, str] = {}
    if parsed_query:
        for k, v in parse_qs(parsed_query, keep_blank_values=True).items():
            if v:
                out[k] = v[0]
    qsb = scope.get("query_string") or b""
    if qsb:
        for k, v in parse_qs(qsb.decode("utf-8", "replace"), keep_blank_values=True).items():
            if v and k not in out:
                out[k] = v[0]
    return out


async def _read_body(receive) -> bytes:
    chunks: List[bytes] = []
    while True:
        msg = await receive()
        if msg["type"] == "http.disconnect":
            break
        if msg["type"] != "http.request":
            continue
        chunks.append(msg.get("body") or b"")
        if not msg.get("more_body"):
            break
    return b"".join(chunks)


async def app(scope, receive, send):
    if scope["type"] != "http":
        return

    method = scope.get("method", b"GET").decode("ascii", "replace").upper()
    raw_path = scope.get("path", b"/").decode("utf-8", "replace")
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
