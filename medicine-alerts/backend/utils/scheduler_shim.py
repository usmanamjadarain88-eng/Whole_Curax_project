"""Event-based alert checks after API writes.

On a long-lived process (local ``python api_server.py`` without VERCEL_ENV), this
starts ``BackendAlertScheduler`` and ``trigger_alert_checks_for_admin`` runs
checks in a background thread.

On Vercel, the scheduler thread is not started (serverless). Use **Cron** to POST
``/maintenance/run-alert-checks`` with header ``X-Maintenance-Key`` (same secret as
``MAINTENANCE_API_KEY``) so ``BackendAlertScheduler.run_all_checks_once()`` runs
inside that invocation.
"""
from __future__ import annotations

import os
import threading

from utils.db import get_db

_inst = None


def _start_if_applicable() -> None:
    global _inst
    if _inst is not None:
        return
    if os.environ.get("DISABLE_ALERT_SCHEDULER", "").strip().lower() in ("1", "true", "yes"):
        return
    vercel_env = (os.environ.get("VERCEL_ENV") or "").strip()
    if vercel_env and os.environ.get("FORCE_ALERT_SCHEDULER", "").strip().lower() not in ("1", "true", "yes"):
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


def trigger_alert_checks_for_admin(admin_id) -> None:
    if not admin_id:
        return
    _start_if_applicable()
    if _inst is None:
        return
    t = threading.Thread(
        target=_inst.run_checks_for_admin,
        args=(str(admin_id),),
        daemon=True,
        name="AlertCheck",
    )
    t.start()
