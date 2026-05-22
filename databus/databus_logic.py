"""Shared HTTP logic for Vercel main.py and api/*.py handlers (stdlib only)."""

from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request


def _central_api_url() -> str:
    return (os.environ.get("CENTRAL_API_URL") or "").strip().rstrip("/")


def _ably_api_key() -> str:
    return (os.environ.get("ABLY_API_KEY") or "").strip()


def normalize_access_code(code: str) -> str:
    return (code or "").strip().upper()


def fetch_admin_data(access_code: str, act_as_user_id: str | None = None) -> tuple[bool, dict]:
    base = _central_api_url()
    if not base or not access_code:
        return False, {}
    url = f"{base}/admin/data?access_code={urllib.parse.quote(access_code)}"
    act_as = (act_as_user_id or "").strip()
    if act_as:
        url += f"&act_as_user_id={urllib.parse.quote(act_as)}"
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=15) as resp:
            code = resp.getcode()
            if 200 <= code < 300:
                body = resp.read().decode("utf-8", errors="replace")
                return True, json.loads(body) if body else {}
            return False, {"message": f"HTTP {code}"}
    except Exception as e:
        return False, {"message": str(e)}


def ably_publish(channel_name: str, envelope_json: str) -> tuple[bool, str]:
    key = _ably_api_key()
    if not key:
        return False, "ABLY_API_KEY not configured"
    enc_ch = urllib.parse.quote(channel_name, safe="")
    url = f"https://rest.ably.io/channels/{enc_ch}/messages"
    payload = json.dumps([{"name": "push", "data": envelope_json}]).encode("utf-8")
    auth = base64.b64encode(f"{key}:".encode()).decode().replace("\n", "")
    req = urllib.request.Request(url, data=payload, method="POST")
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            c = resp.getcode()
            if 200 <= c < 300:
                return True, ""
            return False, f"HTTP {c}"
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode("utf-8", errors="replace")[:500]
        except Exception:
            detail = str(e)
        return False, detail


def health_payload() -> dict:
    return {
        "ok": True,
        "service": "databus",
        "deploy": "vercel",
        "notify_admin": "POST /notify_admin (rewritten to Python function)",
        "realtime": "Ably channel admin:<ACCESS_CODE_UPPER> (clients need subscribe API key)",
    }


def process_notify_admin(body_bytes: bytes) -> tuple[int, dict]:
    """POST /notify_admin — returns (http_status, json_body_dict)."""
    if not _ably_api_key():
        return 503, {"ok": False, "error": "ABLY_API_KEY not configured"}
    try:
        body = json.loads(body_bytes.decode("utf-8") if body_bytes else "{}")
    except Exception:
        return 400, {"ok": False, "error": "JSON body required"}
    access_code = (body.get("access_code") or "").strip()
    if not access_code:
        return 400, {"ok": False, "error": "access_code required"}
    act_as_user_id = (body.get("act_as_user_id") or "").strip()
    code = normalize_access_code(access_code)
    ok, data = fetch_admin_data(access_code, act_as_user_id=act_as_user_id or None)
    if not ok:
        msg = data.get("message", "Failed to fetch from Central API") if isinstance(data, dict) else "fetch failed"
        return 502, {"ok": False, "error": msg}
    envelope = json.dumps({"action": "data_sync", "payload": data})
    channel = f"admin:{code}"
    pub_ok, pub_err = ably_publish(channel, envelope)
    if not pub_ok:
        return 502, {"ok": False, "error": pub_err}
    return 200, {"ok": True, "access_code": code, "delivery": "ably", "channel": channel}
