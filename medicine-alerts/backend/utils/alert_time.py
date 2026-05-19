"""Timezone-aware scheduling helpers for medicine dose alerts."""
from __future__ import annotations

import datetime
import os
import re

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None  # type: ignore

DEFAULT_TZ = (os.environ.get("ALERT_TIMEZONE") or "UTC").strip() or "UTC"
# Cron may run every minute; allow 5-minute window so a single missed minute still fires.
MEDICINE_WINDOW_SEC = max(60, int((os.environ.get("ALERT_MEDICINE_WINDOW_SEC") or "300").strip() or "300"))


def resolve_tz(tz_name=None):
    name = (tz_name or "").strip() or DEFAULT_TZ
    if ZoneInfo is not None:
        try:
            return ZoneInfo(name)
        except Exception:
            pass
    try:
        return datetime.timezone.utc
    except Exception:
        return datetime.timezone(datetime.timedelta(0))


def now_in_tz(tz_name=None) -> datetime.datetime:
    tz = resolve_tz(tz_name)
    return datetime.datetime.now(tz)


def parse_hm(value) -> tuple[int, int] | None:
    s = str(value or "").strip()
    if not s:
        return None
    m = re.match(r"^(\d{1,2}):(\d{2})$", s)
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2))
    if 0 <= h <= 23 and 0 <= mi <= 59:
        return h, mi
    return None


def schedule_slots_for_medicine(medicine: dict) -> list[tuple[int, int]]:
    """All dose times for a box: times[] plus exact_time (deduped)."""
    seen: set[tuple[int, int]] = set()
    out: list[tuple[int, int]] = []
    times = medicine.get("times") if isinstance(medicine.get("times"), list) else []
    for t in times:
        hm = parse_hm(t)
        if hm and hm not in seen:
            seen.add(hm)
            out.append(hm)
    et = (medicine.get("exact_time") or "").strip()
    hm = parse_hm(et)
    if hm and hm not in seen:
        seen.add(hm)
        out.append(hm)
    if not out:
        out.append((8, 0))
    return out


def _today_at(now: datetime.datetime, hour: int, minute: int) -> datetime.datetime:
    return now.replace(hour=hour, minute=minute, second=0, microsecond=0)


def hm_in_window(now: datetime.datetime, hour: int, minute: int, window_sec: int | None = None) -> bool:
    """True when `now` is within window_sec of today's (hour, minute) in the same tz."""
    w = window_sec if window_sec is not None else MEDICINE_WINDOW_SEC
    try:
        target = _today_at(now, hour, minute)
    except ValueError:
        return False
    return abs((now - target).total_seconds()) <= w


def offset_hm(h: int, m: int, delta_minutes: int) -> tuple[int, int]:
    total = h * 60 + m + delta_minutes
    total %= 24 * 60
    return total // 60, total % 60


def minutes_late(now: datetime.datetime, dose_h: int, dose_m: int) -> float:
    """Minutes after scheduled dose time today (negative if before dose)."""
    try:
        dose_dt = _today_at(now, dose_h, dose_m)
    except ValueError:
        return -9999.0
    return (now - dose_dt).total_seconds() / 60.0
