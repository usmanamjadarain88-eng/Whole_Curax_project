"""Data bus notify helpers (urllib only — no Flask)."""
from __future__ import annotations

import json
import os
import urllib.request


def _backend_dir() -> str:
    return os.path.abspath(os.path.dirname(os.path.dirname(__file__)))


def get_data_bus_url() -> str:
    url = (os.environ.get("DATA_BUS_URL") or "").strip()
    if url:
        return url.rstrip("/")
    for _path in [
        os.path.join(_backend_dir(), "data_bus_url.txt"),
        os.path.join(os.getcwd(), "backend", "data_bus_url.txt"),
    ]:
        if os.path.isfile(_path):
            try:
                with open(_path, "r", encoding="utf-8") as _f:
                    for _line in _f:
                        _line = _line.strip()
                        if _line and not _line.startswith("#"):
                            return _line.rstrip("/")
            except OSError:
                pass
    return "https://databus.vercel.app"


def notify_databus(access_code: str) -> None:
    code = (access_code or "").strip()
    if not code:
        return
    base = get_data_bus_url()
    if not base:
        return
    try:
        req = urllib.request.Request(
            base + "/notify_admin",
            data=json.dumps({"access_code": code}).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            if not (200 <= getattr(resp, "status", 0) < 300):
                print(f"  [notify_databus] data bus returned {getattr(resp, 'status', 0)}")
    except Exception as e:
        print(f"  [notify_databus] failed: {e}")
