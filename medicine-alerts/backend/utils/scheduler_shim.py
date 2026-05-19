"""After API writes — optional email sweep; timed dose cron off by default.

Always via device POST (no cron required):
  - POST /user/missed-dose-escalate → admin relay + family_email (+15 / +30)
  - POST /user/medicine-reminder-email → user Gmail (pre-30 / pre-15 / exact)

BACKEND_EMAIL_ALERT_CHECKS defaults on (sync-triggered email windows).
"""
from __future__ import annotations

import os
import threading

from utils.db import get_db

_inst = None


def _env_flag(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    return str(raw).strip().lower() in ("1", "true", "yes")


_TIMED_CHECKS = _env_flag("BACKEND_TIMED_ALERT_CHECKS", False)
_EMAIL_CHECKS = _env_flag("BACKEND_EMAIL_ALERT_CHECKS", True)


def _start_if_applicable() -> None:
    global _inst
    if not _TIMED_CHECKS:
        return
    if _inst is not None:
        return
    if os.environ.get("DISABLE_ALERT_SCHEDULER", "").strip().lower() in ("1", "true", "yes"):
        return
    vercel_env = (os.environ.get("VERCEL_ENV") or "").strip()
    if vercel_env and os.environ.get("FORCE_ALERT_SCHEDULER", "").strip().lower() not in (
        "1",
        "true",
        "yes",
    ):
        return
    try:
        from alert_scheduler import BackendAlertScheduler
    except ImportError:
        return
    try:
        inst = BackendAlertScheduler(get_db)
        inst.start()
        _inst = inst
    except Exception as e:
        print(f"[AlertScheduler] Failed to start: {e}")


def _run_checks_for_admin_serverless(admin_id: str) -> None:
    try:
        from alert_scheduler import BackendAlertScheduler

        s = BackendAlertScheduler(get_db)
        if _TIMED_CHECKS:
            s.run_checks_for_admin(str(admin_id))
        elif _EMAIL_CHECKS:
            s.run_email_checks_for_admin(str(admin_id))
    except Exception as e:
        print(f"[AlertScheduler] serverless check failed ({admin_id}): {e}")


def trigger_alert_checks_for_admin(admin_id) -> None:
    """Email-only sweep on data sync (default). Full cron only if BACKEND_TIMED_ALERT_CHECKS=1."""
    if not admin_id or (not _EMAIL_CHECKS and not _TIMED_CHECKS):
        return
    _start_if_applicable()
    if _inst is not None and _TIMED_CHECKS:
        t = threading.Thread(
            target=_inst.run_checks_for_admin,
            args=(str(admin_id),),
            daemon=True,
            name="AlertCheck",
        )
        t.start()
        return
    threading.Thread(
        target=_run_checks_for_admin_serverless,
        args=(str(admin_id),),
        daemon=True,
        name="AlertCheckServerless",
    ).start()
