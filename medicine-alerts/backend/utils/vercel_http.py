"""HTTP helpers for Vercel Python serverless (BaseHTTPRequestHandler)."""
from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler
from typing import Any, Dict, Mapping, Optional, Tuple, Union
from urllib.parse import parse_qs, urlparse


def parse_query(handler: BaseHTTPRequestHandler) -> Dict[str, str]:
    q = parse_qs(urlparse(handler.path).query)
    return {k: (v[0] if v else "") for k, v in q.items()}


def read_json_body(handler: BaseHTTPRequestHandler) -> dict:
    length = int(handler.headers.get("Content-Length", 0) or 0)
    if length <= 0:
        return {}
    raw = handler.rfile.read(length)
    if not raw:
        return {}
    try:
        out = json.loads(raw.decode("utf-8"))
        return out if isinstance(out, dict) else {}
    except Exception:
        return {}


def headers_dict(handler: BaseHTTPRequestHandler) -> Dict[str, str]:
    return {k: v for k, v in handler.headers.items()}


def send_json(
    handler: BaseHTTPRequestHandler,
    status: int,
    payload: Union[dict, list, str, None],
    *,
    no_body: bool = False,
) -> None:
    if no_body or status == 204:
        handler.send_response(204)
        handler.end_headers()
        return
    body_bytes = (
        payload.encode("utf-8")
        if isinstance(payload, str)
        else json.dumps(payload, default=str).encode("utf-8")
    )
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body_bytes)))
    handler.end_headers()
    handler.wfile.write(body_bytes)


def method_not_allowed(handler: BaseHTTPRequestHandler, allow: str) -> None:
    handler.send_response(405)
    handler.send_header("Allow", allow)
    handler.end_headers()


def bad_request(handler: BaseHTTPRequestHandler, msg: str) -> None:
    send_json(handler, 400, {"message": msg})
