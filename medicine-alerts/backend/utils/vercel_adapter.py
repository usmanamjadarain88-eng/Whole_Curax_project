"""Build Vercel-compatible BaseHTTPRequestHandler from (status, body) route functions."""
from __future__ import annotations

from http.server import BaseHTTPRequestHandler
from typing import Any, Callable, Dict, Optional, Tuple

from utils.vercel_http import method_not_allowed, parse_query, read_json_body, send_json

RouteFn = Callable[[dict, dict, dict], Tuple[int, Any]]


def _lower_headers(headers) -> Dict[str, str]:
    return {str(k).lower(): v for k, v in headers.items()}


def _read_body(method: str, h: BaseHTTPRequestHandler) -> dict:
    if method in ("POST", "PUT", "PATCH", "DELETE"):
        return read_json_body(h)
    return {}


def make_handler(
    *,
    get_fn: Optional[RouteFn] = None,
    post_fn: Optional[RouteFn] = None,
    put_fn: Optional[RouteFn] = None,
    patch_fn: Optional[RouteFn] = None,
    delete_fn: Optional[RouteFn] = None,
) -> type:
    fns = {"GET": get_fn, "POST": post_fn, "PUT": put_fn, "PATCH": patch_fn, "DELETE": delete_fn}
    allowed = [m for m, fn in fns.items() if fn is not None]
    allow_header = ", ".join(allowed)

    class handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args) -> None:  # noqa: A003
            return

        def _dispatch(self, method: str) -> None:
            fn = fns.get(method)
            if not fn:
                method_not_allowed(self, allow_header or "GET")
                return
            q = parse_query(self)
            hdr = _lower_headers(self.headers)
            body = _read_body(method, self)
            try:
                status, payload = fn(body, q, hdr)
            except Exception as e:
                send_json(self, 500, {"message": str(e)[:500]})
                return
            code = int(status)
            if code == 204 or payload is None:
                self.send_response(204)
                self.end_headers()
                return
            send_json(self, code, payload)

        def do_GET(self) -> None:
            self._dispatch("GET")

        def do_POST(self) -> None:
            self._dispatch("POST")

        def do_PUT(self) -> None:
            self._dispatch("PUT")

        def do_PATCH(self) -> None:
            self._dispatch("PATCH")

        def do_DELETE(self) -> None:
            self._dispatch("DELETE")

    return handler
