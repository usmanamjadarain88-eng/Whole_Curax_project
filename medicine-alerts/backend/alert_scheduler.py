"""
Backend alert utilities — timed dose/stock/expiry scheduling is OFF (mobile AlarmManager).

Still used:
  - deliver_device_escalation() — +15/+30 → admin relay + family_email
  - deliver_device_medicine_email() — pre-30/pre-15/exact/+5/+15 → user Gmail
  - Optional daily admin summary at 23:00 on long-lived hosts (FORCE_ALERT_SCHEDULER)

Timed checks (_check_medicine_alerts, expiry, stock) are skipped unless
BACKEND_TIMED_ALERT_CHECKS=1 (legacy / desktop-only).
"""
import datetime
import time
import threading
import json
import re
import asyncio
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

import os
import schedule

def _env_flag(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    return str(raw).strip().lower() in ("1", "true", "yes")


# Phones: local notifications + device POST for emails/escalation. No server cron unless legacy flag.
BACKEND_TIMED_ALERT_CHECKS = _env_flag("BACKEND_TIMED_ALERT_CHECKS", False)
# Gmail to user at pre-30 / pre-15 / exact (on sync sweep + device POST). Default on.
BACKEND_EMAIL_ALERT_CHECKS = _env_flag("BACKEND_EMAIL_ALERT_CHECKS", True)

from utils.alert_time import (
    hm_in_window,
    minutes_late,
    now_in_tz,
    offset_hm,
    schedule_slots_for_medicine,
)
from utils.email_layout import (
    apply_urgent_notification_headers,
    curax_email_html,
    escape as curax_esc,
    plain_body_to_html_paragraphs,
)
from utils.relay_delivery import send_alert_via_relay

RELAY_URL = (os.environ.get("RELAY_URL") or "wss://curax-relay.onrender.com").strip()


class BackendAlertScheduler:
    def __init__(self, get_db_func):
        """get_db_func: callable that returns a CentralDB instance (or None)."""
        self._get_db = get_db_func
        self.running = False
        self._thread = None
        # Per-admin dedup state keyed by admin_id
        self._admin_state = {}

    # ---- Per-admin state container ----

    def _state(self, admin_id, user_id=None):
        """State keyed by (admin_id, user_id). Use user_id=None for dashboard."""
        key = f"{admin_id}_{user_id or 'dashboard'}"
        if key not in self._admin_state:
            self._admin_state[key] = {
                "sent_medicine": set(),
                "sent_escalation": set(),
                "sent_reminder": set(),
                "condition_alert": {},
            }
        return self._admin_state[key]

    # ---- Data loading helpers ----

    def _load_admin_context(self, db, admin):
        """Scheduler context for an admin: linked users + admin-wide settings (no dashboard user medicines)."""
        admin_id = admin["id"]
        settings_blob = {}
        if hasattr(db, "get_admin_settings_blob"):
            settings_blob = db.get_admin_settings_blob(admin_id) or {}
        if not isinstance(settings_blob, dict):
            settings_blob = {}

        alert_settings = settings_blob.get("alert_settings") if isinstance(settings_blob.get("alert_settings"), dict) else {}
        gmail_config = settings_blob.get("gmail_config") if isinstance(settings_blob.get("gmail_config"), dict) else {}
        medical_reminders = settings_blob.get("medical_reminders") if isinstance(settings_blob.get("medical_reminders"), dict) else {}
        admin_bot_config = settings_blob.get("admin_bot_config") if isinstance(settings_blob.get("admin_bot_config"), dict) else {}
        mobile_bot_config = settings_blob.get("mobile_bot_config") if isinstance(settings_blob.get("mobile_bot_config"), dict) else {}

        users = db.get_all_users_by_admin_id(admin_id) or []

        return {
            "admin_id": admin_id,
            "duid": None,
            "admin_bot_id": admin.get("bot_id", ""),
            "admin_api_key": admin.get("api_key", ""),
            "mobile_bot_config": mobile_bot_config,
            "users": users,
            "boxes": {},
            "alert_settings": alert_settings,
            "gmail_config": gmail_config,
            "medical_reminders": medical_reminders,
            "dose_logs": [],
        }

    def _load_user_context(
        self, db, admin_ctx, user_id, user_bot_id, user_api_key, user_name, user_display_mode="", user_timezone=""
    ):
        """Build context for one connected user: that user's medicines/boxes/dose_logs, same alert_settings/gmail/admin_bot. Admin receives all alerts from all users."""
        medicines_raw = db.list_medicines(user_id) or []
        settings_blob = db.get_alert_settings(user_id) or {}
        if not isinstance(settings_blob, dict):
            settings_blob = {}
        medicine_meta = settings_blob.get("medicine_meta") if isinstance(settings_blob.get("medicine_meta"), dict) else {}
        if not medicine_meta and hasattr(db, "get_admin_settings_blob"):
            admin_blob = db.get_admin_settings_blob(admin_ctx.get("admin_id")) or {}
            if isinstance(admin_blob, dict):
                medicine_meta = admin_blob.get("medicine_meta") if isinstance(admin_blob.get("medicine_meta"), dict) else {}
        user_alert_settings = settings_blob.get("alert_settings") if isinstance(settings_blob.get("alert_settings"), dict) else {}
        if not user_alert_settings:
            user_alert_settings = admin_ctx.get("alert_settings") if isinstance(admin_ctx.get("alert_settings"), dict) else {}

        boxes = {}
        for m in medicines_raw:
            box_id = (m.get("box_id") or "B1").strip().upper()
            times = m.get("times") if isinstance(m.get("times"), list) else []
            meta = medicine_meta.get(box_id) if isinstance(medicine_meta.get(box_id), dict) else {}
            exact_time = (meta.get("exact_time") or "").strip() or (times[0] if times else "08:00")
            expiry = (meta.get("expiry") or "").strip()
            qty = 0
            try:
                qty = int(m.get("quantity", 0))
            except (TypeError, ValueError):
                pass
            boxes[box_id] = {
                "name": (m.get("name") or "").strip() or "Medicine",
                "quantity": qty,
                "exact_time": exact_time,
                "expiry": expiry,
                "times": times,
            }

        dose_logs = db.list_dose_logs(user_id, limit=200) if hasattr(db, "list_dose_logs") else []
        prefix = f"[{user_name or 'User'}] " if (user_name or "").strip() else ""
        return {
            "admin_id": admin_ctx["admin_id"],
            "duid": user_id,
            "admin_bot_id": admin_ctx.get("admin_bot_id", ""),
            "admin_api_key": admin_ctx.get("admin_api_key", ""),
            "mobile_bot_config": {},
            "users": [],
            "boxes": boxes,
            "alert_settings": user_alert_settings,
            "gmail_config": admin_ctx.get("gmail_config") or {},
            "medical_reminders": admin_ctx.get("medical_reminders") or {},
            "dose_logs": dose_logs,
            "single_user_mode": True,
            "user_bot_id": (user_bot_id or "").strip(),
            "user_api_key": (user_api_key or "").strip(),
            "user_name": (user_name or "").strip(),
            "message_prefix": prefix,
            "user_display_mode": (user_display_mode or "").strip().lower(),
            "timezone": (user_timezone or "").strip(),
        }

    # ---- Missed-dose escalation (device + cron) ----

    def _is_standalone_ctx(self, ctx):
        return (ctx.get("user_display_mode") or "").strip().lower() == "standalone"

    def _admin_escalation_settings(self, ctx):
        """Missed-dose rules + family_email always from admin account (mobile admin Settings), not per-user blob."""
        db = self._get_db()
        admin_id = ctx.get("admin_id")
        if db and admin_id and hasattr(db, "get_admin_settings_blob"):
            try:
                blob = db.get_admin_settings_blob(admin_id) or {}
                inner = blob.get("alert_settings") if isinstance(blob.get("alert_settings"), dict) else {}
                esc = inner.get("missed_dose_escalation")
                if isinstance(esc, dict):
                    return esc
            except Exception:
                pass
        inner = ctx.get("alert_settings") if isinstance(ctx.get("alert_settings"), dict) else {}
        esc = inner.get("missed_dose_escalation")
        return esc if isinstance(esc, dict) else {}

    def _family_email_from(self, esc):
        return (esc.get("family_email") or "").strip()

    def _send_family_email_only(self, ctx, subject, body, esc):
        """Missed-dose admin email: server SIGNUP_SMTP_* sender, family_email recipient only."""
        family = self._family_email_from(esc)
        if not family:
            return False
        from utils.system_smtp import send_system_notification_email

        return send_system_notification_email(family, subject, body)

    def _build_escalation_copy(self, phase, ctx, name, box_id, schedule_time=""):
        user_name = (ctx.get("user_name") or "").strip()
        prefix = f"[{user_name}] " if user_name else ""
        sched = (schedule_time or "").strip()
        sched_bit = f" (scheduled {sched})" if sched else ""
        if str(phase) == "15":
            subject = f"URGENT: {name} ({box_id}) — 15 min overdue"
            body = (
                f"{prefix}URGENT: {name} from box {box_id}{sched_bit} is 15 minutes overdue. "
                "Immediate action required."
            )
            alert_type = "urgent"
        else:
            subject = f"MISSED DOSE: {name} ({box_id}) — mark window closed"
            body = (
                f"{prefix}MISSED: {name} from box {box_id}{sched_bit} was not taken within "
                "the 30-minute window."
            )
            alert_type = "family"
        return alert_type, subject, body

    def _dose_already_taken(self, ctx, box_id, today_str):
        box_id = (box_id or "").strip().upper()
        for e in ctx.get("dose_logs") or []:
            if not isinstance(e, dict):
                continue
            if (e.get("box_id") or "").strip().upper() != box_id:
                continue
            taken_at = str(e.get("taken_at") or "")
            if not taken_at.startswith(today_str):
                continue
            src = (e.get("source") or "").lower()
            if src in ("missed", "missed_auto"):
                continue
            return True
        return False

    def _deliver_escalation(self, ctx, phase, name, box_id, today_str, schedule_time="", record_state=None):
        esc = self._admin_escalation_settings(ctx)
        phase_s = str(phase)
        if phase_s == "15" and not esc.get("15_min_urgent", True):
            return False
        if phase_s == "30" and not esc.get("30_min_family", True):
            return False
        if self._dose_already_taken(ctx, box_id, today_str):
            return False

        db = self._get_db()
        uid = ctx.get("duid")
        sched_key = (schedule_time or "").strip().replace(":", "")
        dedup_tag = f"{phase_s}min:{sched_key}" if sched_key else f"{phase_s}min"
        if db and uid and hasattr(db, "try_record_missed_escalation"):
            if not db.try_record_missed_escalation(uid, box_id, today_str, phase_s):
                if record_state is not None:
                    record_state.add((box_id, today_str, dedup_tag))
                return False

        alert_type, subject, body = self._build_escalation_copy(phase_s, ctx, name, box_id, schedule_time)
        # User device already showed local +15/+30; relay targets admin (+ email family).
        self._send_admin_alert(ctx, alert_type, body)
        if db and uid and ctx.get("admin_id"):
            try:
                db.create_alert(uid, ctx["admin_id"], alert_type, body)
            except Exception:
                pass
        self._send_family_email_only(ctx, subject, body, esc)

        if record_state is not None:
            record_state.add((box_id, today_str, dedup_tag))
        return True

    def deliver_device_escalation(self, bot_id, api_key, phase, box_id, medicine_name, schedule_time, dose_date):
        """Instant escalation from user device alarm (POST /user/missed-dose-escalate)."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        phase_s = str(phase).strip()
        if phase_s not in ("15", "30"):
            return False, "invalid_phase"
        box_id = (box_id or "").strip().upper()
        if not box_id:
            return False, "box_id_required"
        dose_date = (dose_date or "").strip()[:10]
        if not dose_date:
            dose_date = datetime.datetime.now().strftime("%Y-%m-%d")
        db = self._get_db()
        if not db:
            return False, "no_db"
        info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
        if not info:
            return False, "user_not_found"
        admin_id = info["admin_id"]
        user_id = info["user_id"]
        admin_ctx = self._load_admin_context(db, {"id": admin_id, "bot_id": info.get("admin_bot_id", ""), "api_key": info.get("admin_api_key", "")})
        if not admin_ctx:
            return False, "admin_context_failed"
        display_mode = db.get_user_display_mode_for_user_id(user_id) if hasattr(db, "get_user_display_mode_for_user_id") else ""
        user_tz = db.get_user_timezone_for_user_id(user_id) if hasattr(db, "get_user_timezone_for_user_id") else ""
        ctx = self._load_user_context(
            db,
            admin_ctx,
            user_id,
            bot_id,
            api_key,
            info.get("user_name") or "User",
            display_mode,
            user_tz,
        )
        name = (medicine_name or "").strip() or (ctx.get("boxes", {}).get(box_id) or {}).get("name") or "Medicine"
        sched = (schedule_time or "").strip() or (ctx.get("boxes", {}).get(box_id) or {}).get("exact_time") or ""
        ok = self._deliver_escalation(ctx, phase_s, name, box_id, dose_date, sched, record_state=None)
        return (True, "ok") if ok else (False, "skipped")

    def deliver_device_medicine_email(
        self, bot_id, api_key, kind, box_id, medicine_name="", schedule_time="", dose_date=""
    ):
        """POST from user phone — Gmail to user (email_alerts): −30/−15/exact and +5/+15 late reminders."""
        kind = (kind or "").strip().lower()
        if kind not in ("pre30", "pre15", "exact", "post5", "post15"):
            return False, "invalid_kind"
        phase_key = {"pre30": "p30", "pre15": "p15", "exact": "exac", "post5": "lt5", "post15": "lt15"}[kind]
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        box_id = (box_id or "").strip().upper()
        if not bot_id or not api_key or not box_id:
            return False, "missing_fields"
        dose_date = (dose_date or "").strip()[:10] or datetime.datetime.now().strftime("%Y-%m-%d")
        db = self._get_db()
        if not db:
            return False, "no_db"
        info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
        if not info:
            return False, "user_not_found"
        admin_ctx = self._load_admin_context(
            db,
            {
                "id": info["admin_id"],
                "bot_id": info.get("admin_bot_id", ""),
                "api_key": info.get("admin_api_key", ""),
            },
        )
        if not admin_ctx:
            return False, "admin_context_failed"
        user_id = info["user_id"]
        uid = user_id
        display_mode = (
            db.get_user_display_mode_for_user_id(user_id)
            if hasattr(db, "get_user_display_mode_for_user_id")
            else ""
        )
        user_tz = (
            db.get_user_timezone_for_user_id(user_id)
            if hasattr(db, "get_user_timezone_for_user_id")
            else ""
        )
        ctx = self._load_user_context(
            db,
            admin_ctx,
            user_id,
            bot_id,
            api_key,
            info.get("user_name") or "User",
            display_mode,
            user_tz,
        )
        if self._dose_already_taken(ctx, box_id, dose_date):
            return False, "skipped"
        if db and uid and hasattr(db, "try_record_missed_escalation"):
            if not db.try_record_missed_escalation(uid, box_id, dose_date, phase_key):
                return False, "skipped"
        name = (medicine_name or "").strip() or (ctx.get("boxes", {}).get(box_id) or {}).get("name") or "Medicine"
        sched = (schedule_time or "").strip()
        if not self._send_medicine_email_for_kind(ctx, kind, name, box_id, sched):
            return False, "email_disabled_or_failed"
        return True, "ok"

    def _send_medicine_email_for_kind(self, ctx, kind, name, box_id, sched_label=""):
        """Gmail to user per alert_settings.email_alerts (unchanged legacy behaviour)."""
        if not ctx["alert_settings"].get("email_alerts", {}).get("enabled", False):
            return False
        if kind == "pre30":
            subject = f"Reminder: {name} from box {box_id} in 30 minutes"
            body = f"Medicine {name} from box {box_id} is due in 30 minutes."
        elif kind == "pre15":
            subject = f"Reminder: {name} from box {box_id} in 15 minutes"
            body = f"Medicine {name} from box {box_id} is due in 15 minutes. Time to take soon."
        elif kind == "post5":
            subject = f"Missed reminder: {name} from box {box_id} — 5 min late"
            body = (
                f"Medicine {name} from box {box_id} is 5 minutes past the scheduled time. "
                "Please take now if not taken."
            )
        elif kind == "post15":
            subject = f"URGENT: {name} from box {box_id} — 15 min late"
            body = (
                f"Medicine {name} from box {box_id} is 15 minutes past the scheduled time. "
                "Please take immediately."
            )
        else:
            subject = f"Time now: {name} from box {box_id}"
            body = f"Medicine {name} from box {box_id} – time to take now."
        try:
            self._send_gmail(ctx["gmail_config"], subject, body)
            return True
        except Exception as e:
            print(f"[AlertScheduler] medicine email ({kind}): {e}")
            return False

    # ---- Alert delivery ----

    def _send_via_relay(self, bot_id, api_key, alert_type, message, user_name=None):
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return False
        ok, err = send_alert_via_relay(
            bot_id, api_key, alert_type, message, user_name=user_name, relay_url=RELAY_URL
        )
        if not ok:
            print(f"[AlertScheduler] relay send failed ({bot_id[:6]}...): {err}")
        return ok

    def _send_user_alerts(self, ctx, alert_type, message):
        """Send alert to users. Desktop-synced mobile_bot_config is the essential
        source; users linked via connection code are additional recipients."""
        if ctx.get("single_user_mode") and ctx.get("user_bot_id") and ctx.get("user_api_key"):
            self._send_via_relay(ctx["user_bot_id"], ctx["user_api_key"], alert_type, message)
            return
        sent_keys = set()
        # Desktop-configured mobile_bot_config (essential -- admin enters on desktop)
        mbc = ctx.get("mobile_bot_config") or {}
        mbid = (mbc.get("bot_id") or "").strip()
        makey = (mbc.get("api_key") or "").strip()
        if mbid and makey:
            sent_keys.add((mbid, makey))
            self._send_via_relay(mbid, makey, alert_type, message)
        # Users linked via connection code (additional)
        for user in ctx["users"]:
            bid = (user.get("bot_id") or "").strip()
            akey = (user.get("api_key") or "").strip()
            if bid and akey and (bid, akey) not in sent_keys:
                sent_keys.add((bid, akey))
                self._send_via_relay(bid, akey, alert_type, message)

    def _send_admin_alert(self, ctx, alert_type, message):
        """Send alert to admin's mobile app. Credentials from admins table
        (auto-stored when admin registers on Android app with access code)."""
        bid = (ctx.get("admin_bot_id") or "").strip()
        akey = (ctx.get("admin_api_key") or "").strip()
        un = None
        if ctx.get("single_user_mode"):
            un = ((ctx.get("user_name") or "").strip() or None)
        if bid and akey:
            self._send_via_relay(bid, akey, alert_type, message, user_name=un)

    def _notify_user_and_admin(self, ctx, alert_type, subject, body, send_email=True):
        """Send to user(s) and always to admin. In single_user_mode sends to that user + admin; body is prefixed with [User name]."""
        prefix = ctx.get("message_prefix") or ""
        if prefix:
            body = prefix + body
            subject = prefix.strip() + " " + subject if subject else subject
        if send_email:
            email_enabled = ctx["alert_settings"].get("email_alerts", {}).get("enabled", False)
            if email_enabled:
                self._send_gmail(ctx["gmail_config"], subject, body)
        self._send_user_alerts(ctx, alert_type, body)
        self._send_admin_alert(ctx, alert_type, body)

    def _send_gmail(self, gmail_config, subject, body):
        try:
            if gmail_config.get("gmail_alerts_enabled") is False:
                return
            sender_email = (gmail_config.get("sender_email") or "").strip()
            sender_password = (gmail_config.get("sender_password") or "").strip()
            if not sender_email or not sender_password:
                return
            recipient_text = gmail_config.get("recipients", "")
            recipient_list = [e.strip() for e in recipient_text.split(",") if e.strip()] if recipient_text else [sender_email]
            self._send_email_to(sender_email, sender_password, subject, body, recipient_list)
        except Exception as e:
            print(f"[AlertScheduler] gmail error: {e}")

    def _send_email_to(self, sender_email, sender_password, subject, body, recipients, extra_recipients=None):
        try:
            if isinstance(recipients, str):
                recipients = [e.strip() for e in recipients.split(",") if e.strip()]
            if extra_recipients:
                if isinstance(extra_recipients, str):
                    recipients = recipients + [e.strip() for e in extra_recipients.split(",") if e.strip()]
                else:
                    recipients = recipients + list(extra_recipients)
            email_re = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
            valid = [e for e in recipients if email_re.match(e)]
            if not valid:
                return
            with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=25) as server:
                server.login(sender_email, sender_password)
                for rcpt in valid:
                    body_html = plain_body_to_html_paragraphs(body)
                    if not body_html.strip():
                        body_html = (
                            f'<p style="margin:0;font-size:14px;color:#374151;line-height:1.55;">'
                            f"{curax_esc(body)}</p>"
                        )
                    sent_ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    content_rows = (
                        '<tr><td style="padding:24px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
                        f'<div style="font-size:18px;font-weight:700;color:#111827;line-height:1.35;">'
                        f"{curax_esc(subject)}</div>"
                        f'<div style="margin-top:12px;">{body_html}</div>'
                        "</td></tr>"
                    )
                    html = curax_email_html(
                        content_rows,
                        footer_meta=[
                            ("Time", curax_esc(sent_ts)),
                            ("System", curax_esc("CuraX Intelligent Medicine System")),
                            (
                                "Recipients",
                                f'<span style="color:#2563eb;">{curax_esc(rcpt)}</span>',
                            ),
                        ],
                    )
                    msg = MIMEMultipart()
                    msg["From"] = sender_email
                    msg["To"] = rcpt
                    msg["Subject"] = subject
                    apply_urgent_notification_headers(msg)
                    msg.attach(MIMEText(html, "html"))
                    try:
                        server.send_message(msg)
                    except Exception:
                        continue
        except Exception as e:
            print(f"[AlertScheduler] email send error: {e}")

    # ---- Anti-spam / dedup ----

    def _allow_condition_alert(self, state, key):
        now_ts = time.time()
        now_date = datetime.datetime.now().strftime("%Y-%m-%d")
        cond = state["condition_alert"]
        entry = cond.get(key)

        if str(key).startswith("expiry:"):
            if not entry:
                cond[key] = {"count": 1, "next_ts": now_ts + 8 * 3600, "day": now_date}
                return True
            if entry.get("day") != now_date:
                return False
            if int(entry.get("count", 1)) >= 3:
                return False
            if now_ts >= float(entry.get("next_ts", 0)):
                count = int(entry.get("count", 1)) + 1
                cond[key] = {"count": count, "next_ts": now_ts + 8 * 3600, "day": now_date}
                return True
            return False

        if not entry:
            cond[key] = {"count": 1, "next_ts": now_ts + 2 * 3600}
            return True
        if now_ts >= float(entry.get("next_ts", 0)):
            count = int(entry.get("count", 1)) + 1
            gap = 24 * 3600 if count >= 2 else 2 * 3600
            cond[key] = {"count": count, "next_ts": now_ts + gap}
            return True
        return False

    def _clear_condition_alert(self, state, key):
        state["condition_alert"].pop(key, None)

    # ---- Check: medicine timing ----

    def _check_medicine_alerts(self, ctx, state):
        tz_name = (ctx.get("timezone") or "").strip() or None
        now = now_in_tz(tz_name)
        today_str = now.strftime("%Y-%m-%d")

        state["sent_medicine"] = {(b, d, t) for (b, d, t) in state["sent_medicine"] if d >= today_str}
        state["sent_escalation"] = {(b, d, t) for (b, d, t) in state["sent_escalation"] if d >= today_str}

        alert_cfg = ctx["alert_settings"].get("medicine_alerts", {})
        esc = ctx["alert_settings"].get("missed_dose_escalation", {})

        for box_id, medicine in ctx["boxes"].items():
            if not medicine:
                continue
            name = medicine.get("name", "Medicine")

            for h, m in schedule_slots_for_medicine(medicine):
                sched_label = f"{h:02d}:{m:02d}"
                slot_key = sched_label.replace(":", "")

                # 30 min before
                if alert_cfg.get("30_min_before", True):
                    h30, m30 = offset_hm(h, m, -30)
                    dedup = (box_id, today_str, f"pre30:{slot_key}")
                    if hm_in_window(now, h30, m30) and dedup not in state["sent_medicine"]:
                        state["sent_medicine"].add(dedup)
                        body = f"Medicine {name} from box {box_id} is due in 30 minutes."
                        email_enabled = ctx["alert_settings"].get("email_alerts", {}).get("enabled", False)
                        if email_enabled:
                            self._send_gmail(
                                ctx["gmail_config"],
                                f"Reminder: {name} from box {box_id} in 30 minutes",
                                body,
                            )
                        self._send_user_alerts(ctx, "pre30", body)

                # 15 min before
                if alert_cfg.get("15_min_before", True):
                    h15, m15 = offset_hm(h, m, -15)
                    dedup = (box_id, today_str, f"pre:{slot_key}")
                    if hm_in_window(now, h15, m15) and dedup not in state["sent_medicine"]:
                        state["sent_medicine"].add(dedup)
                        body = f"Medicine {name} from box {box_id} is due in 15 minutes. Time to take soon."
                        email_enabled = ctx["alert_settings"].get("email_alerts", {}).get("enabled", False)
                        if email_enabled:
                            self._send_gmail(
                                ctx["gmail_config"],
                                f"Reminder: {name} from box {box_id} in 15 minutes",
                                body,
                            )
                        self._send_user_alerts(ctx, "pre", body)

                # Exact time
                if alert_cfg.get("exact_time", True):
                    dedup = (box_id, today_str, f"time:{slot_key}")
                    if hm_in_window(now, h, m) and dedup not in state["sent_medicine"]:
                        state["sent_medicine"].add(dedup)
                        body = f"Medicine {name} from box {box_id} \u2013 time to take now."
                        email_enabled = ctx["alert_settings"].get("email_alerts", {}).get("enabled", False)
                        if email_enabled:
                            self._send_gmail(ctx["gmail_config"], f"Time now: {name} from box {box_id}", body)
                        self._send_user_alerts(ctx, "time", body)

                # Missed dose escalation (per schedule slot)
                late = minutes_late(now, h, m)
                if late < 5 or late > 180:
                    continue

                if esc.get("5_min_reminder", True):
                    dedup = (box_id, today_str, f"5min:{slot_key}")
                    if 5 <= late < 15 and dedup not in state["sent_escalation"]:
                        state["sent_escalation"].add(dedup)
                        body = (
                            f"Missed reminder: {name} from box {box_id} is 5 minutes late. "
                            "Please take now if not taken."
                        )
                        self._send_user_alerts(ctx, "missed_reminder", body)

                if esc.get("15_min_urgent", True):
                    dedup = (box_id, today_str, f"15min:{slot_key}")
                    if 15 <= late < 30 and dedup not in state["sent_escalation"]:
                        self._deliver_escalation(ctx, "15", name, box_id, today_str, sched_label, state["sent_escalation"])

                if esc.get("30_min_family", True):
                    dedup = (box_id, today_str, f"30min:{slot_key}")
                    if 30 <= late < 60 and dedup not in state["sent_escalation"]:
                        self._deliver_escalation(ctx, "30", name, box_id, today_str, sched_label, state["sent_escalation"])

                if esc.get("1_hour_log", True):
                    dedup = (box_id, today_str, f"1h:{slot_key}")
                    if 60 <= late <= 180 and dedup not in state["sent_escalation"]:
                        state["sent_escalation"].add(dedup)
                        db = self._get_db()
                        if db:
                            try:
                                ts = f"{today_str} {h:02d}:{m:02d}:00"
                                db.create_dose_log(ctx["duid"], medicine_id=None, box_id=box_id, taken_at=ts, source="missed")
                            except Exception as e:
                                print(f"[AlertScheduler] missed dose log error: {e}")

    def _allow_medical_reminder_once(self, state, key):
        """Fire each medical reminder offset at most once (survives for process lifetime)."""
        cond = state["condition_alert"]
        if cond.get(key):
            return False
        cond[key] = {"count": 1, "next_ts": time.time() + 365 * 24 * 3600}
        return True

    def _medical_reminder_cond_key(self, category, idx, r, offset_label):
        date_str = (r.get("date") or r.get("expiry_date") or "").strip()
        time_str = (r.get("time") or "09:00").strip()
        title = r.get("title", r.get("doctor", r.get("medicine", r.get("test_name", "Reminder"))))
        return f"med_rem:{category}:{idx}:{date_str}:{time_str}:{title}:{offset_label}"

    def _try_send_medical_reminder(self, ctx, state, category, idx, r, offset_label, trigger_dt, now, msg, email_subject):
        if now < trigger_dt:
            return
        # Only deliver near the scheduled offset — not days later on scheduler restart.
        if (now - trigger_dt).total_seconds() > 7200:
            return
        cond_key = self._medical_reminder_cond_key(category, idx, r, offset_label)
        if not self._allow_medical_reminder_once(state, cond_key):
            return
        self._send_gmail(ctx["gmail_config"], email_subject, msg)
        self._send_user_alerts(ctx, "reminder", msg)

    # ---- Check: medical reminders ----

    def _check_medical_reminders(self, ctx, state):
        now = datetime.datetime.now()
        reminders_data = ctx.get("medical_reminders") or {}

        for key in ["appointments", "prescriptions", "lab_tests", "custom"]:
            lst = reminders_data.get(key, [])
            for idx, r in enumerate(lst):
                date_str = (r.get("date") or r.get("expiry_date") or "").strip()
                time_str = (r.get("time") or "09:00").strip()
                if not date_str:
                    continue
                try:
                    dt = datetime.datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M")
                except Exception:
                    try:
                        dt = datetime.datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M:%S")
                    except Exception:
                        continue

                reminders = r.get("reminders", {})
                if reminders.get("alert") is False:
                    continue
                title = r.get("title", r.get("doctor", r.get("medicine", r.get("test_name", "Reminder"))))
                when_str = dt.strftime("%Y-%m-%d %H:%M")

                if key == "prescriptions":
                    if reminders.get("7d"):
                        t7 = dt - datetime.timedelta(days=7)
                        msg = f"Reminder: {title} in 7 days (at {when_str})"
                        self._try_send_medical_reminder(
                            ctx, state, key, idx, r, "7d", t7, now, msg, f"7 days before: {title}",
                        )
                    if reminders.get("3d"):
                        t3 = dt - datetime.timedelta(days=3)
                        msg = f"Reminder: {title} in 3 days (at {when_str})"
                        self._try_send_medical_reminder(
                            ctx, state, key, idx, r, "3d", t3, now, msg, f"3 days before: {title}",
                        )
                    if reminders.get("1d"):
                        t1 = dt - datetime.timedelta(days=1)
                        msg = f"Reminder: {title} tomorrow (at {when_str})"
                        self._try_send_medical_reminder(
                            ctx, state, key, idx, r, "1d", t1, now, msg, f"1 day before: {title}",
                        )
                else:
                    if reminders.get("24h"):
                        t24 = dt - datetime.timedelta(hours=24)
                        msg = f"Reminder: {title} in 24 hours (at {when_str})"
                        self._try_send_medical_reminder(
                            ctx, state, key, idx, r, "24h", t24, now, msg, f"24 hours before: {title}",
                        )
                    if reminders.get("2h"):
                        t2 = dt - datetime.timedelta(hours=2)
                        msg = f"Reminder: {title} in 2 hours (at {when_str})"
                        self._try_send_medical_reminder(
                            ctx, state, key, idx, r, "2h", t2, now, msg, f"2 hours before: {title}",
                        )

    # ---- Check: expiry alerts ----

    def _check_expiry_alerts(self, ctx, state):
        today = datetime.datetime.now().date()
        expiry_settings = ctx["alert_settings"].get("expiry_alerts", {})

        for box_id, medicine in ctx["boxes"].items():
            if not medicine:
                continue
            expiry_str = medicine.get("expiry", "")
            if not expiry_str:
                continue
            try:
                expiry_date = datetime.datetime.strptime(expiry_str.strip()[:10], "%Y-%m-%d").date()
            except Exception:
                continue

            delta = (expiry_date - today).days
            if delta < 0:
                for k in list(state["condition_alert"].keys()):
                    if k.startswith(f"expiry:{box_id}:{expiry_str}:"):
                        self._clear_condition_alert(state, k)
                continue

            name = medicine.get("name", "Medicine")
            matched_any = False
            for key, days in [("30_days_before", 30), ("15_days_before", 15), ("7_days_before", 7), ("1_day_before", 1)]:
                cond_key = f"expiry:{box_id}:{expiry_str}:{days}"
                if not expiry_settings.get(key, True) or delta != days:
                    self._clear_condition_alert(state, cond_key)
                    continue
                matched_any = True
                if not self._allow_condition_alert(state, cond_key):
                    continue
                subject = f"Expiry alert: {name} (Box {box_id}) in {days} day(s)"
                body = f"{name} in Box {box_id} expires on {expiry_str} ({days} day(s) from now)."
                self._notify_user_and_admin(ctx, "expiry", subject, body, send_email=True)

            if not matched_any:
                for k in list(state["condition_alert"].keys()):
                    if k.startswith(f"expiry:{box_id}:{expiry_str}:"):
                        self._clear_condition_alert(state, k)

    # ---- Check: stock alerts ----

    def _check_stock_alerts(self, ctx, state):
        stock_settings = ctx["alert_settings"].get("stock_alerts", {})
        if not stock_settings.get("enabled", True):
            return

        threshold = int(stock_settings.get("low_stock_threshold", 5))
        empty_alert = stock_settings.get("empty_alert", True)
        critical_alert = stock_settings.get("critical_alert", True)

        for box_id, medicine in ctx["boxes"].items():
            if not medicine:
                continue
            try:
                qty = int(medicine.get("quantity", 0))
            except (TypeError, ValueError):
                qty = 0

            name = medicine.get("name", "Medicine")
            empty_key = f"stock:{box_id}:empty"
            low_key = f"stock:{box_id}:low"

            if qty <= 0 and empty_alert:
                self._clear_condition_alert(state, low_key)
                if self._allow_condition_alert(state, empty_key):
                    subject = f"Stock alert: {name} (Box {box_id}) is empty"
                    body = f"{name} in Box {box_id} has no stock. Please refill."
                    self._notify_user_and_admin(ctx, "stock", subject, body, send_email=True)
            elif qty <= threshold and critical_alert:
                self._clear_condition_alert(state, empty_key)
                if self._allow_condition_alert(state, low_key):
                    subject = f"Low stock: {name} (Box {box_id}) - {qty} left"
                    body = f"{name} in Box {box_id} has low stock ({qty} left, threshold {threshold})."
                    self._notify_user_and_admin(ctx, "stock", subject, body, send_email=True)
            else:
                self._clear_condition_alert(state, empty_key)
                self._clear_condition_alert(state, low_key)

    # ---- Event-based: run checks for one admin (called from API after data changes) ----

    def _run_user_timed_checks(self, user_ctx, state):
        """Legacy server-side timed alerts (medicine windows, stock, expiry). Off by default."""
        if not BACKEND_TIMED_ALERT_CHECKS:
            return
        self._check_medicine_alerts(user_ctx, state)
        self._check_expiry_alerts(user_ctx, state)
        self._check_stock_alerts(user_ctx, state)

    def run_email_checks_for_admin(self, admin_id):
        """Gmail medicine reminders only (no relay / no server escalation)."""
        if not BACKEND_EMAIL_ALERT_CHECKS or not admin_id:
            return
        db = self._get_db()
        if not db:
            return
        try:
            admin = db.get_admin_by_id(admin_id)
            if not admin:
                return
            admin_ctx = self._load_admin_context(db, admin)
            if not admin_ctx:
                return
            aid = admin_ctx["admin_id"]
            for user in admin_ctx.get("users") or []:
                uid = user.get("id")
                bid = (user.get("bot_id") or "").strip()
                akey = (user.get("api_key") or "").strip()
                name = (user.get("name") or "").strip() or "User"
                if not uid:
                    continue
                user_tz = (user.get("timezone") or "").strip()
                if not user_tz and hasattr(db, "get_user_timezone_for_user_id"):
                    user_tz = db.get_user_timezone_for_user_id(uid) or ""
                user_ctx = self._load_user_context(
                    db,
                    admin_ctx,
                    uid,
                    bid,
                    akey,
                    name,
                    user.get("user_display_mode") or "",
                    user_tz,
                )
                if not user_ctx["boxes"]:
                    continue
                self._check_medicine_emails_only(user_ctx, self._state(aid, uid))
        except Exception as e:
            print(f"[AlertScheduler] run_email_checks_for_admin error ({admin_id}): {e}")

    def _check_medicine_emails_only(self, ctx, state):
        """User Gmail at 30/15/exact windows — no app relay, no +15/+30 (device handles those)."""
        tz_name = (ctx.get("timezone") or "").strip() or None
        now = now_in_tz(tz_name)
        today_str = now.strftime("%Y-%m-%d")
        alert_cfg = ctx["alert_settings"].get("medicine_alerts", {})
        if not ctx["alert_settings"].get("email_alerts", {}).get("enabled", False):
            return
        for box_id, medicine in ctx["boxes"].items():
            if not medicine:
                continue
            name = medicine.get("name", "Medicine")
            for h, m in schedule_slots_for_medicine(medicine):
                sched_label = f"{h:02d}:{m:02d}"
                slot_key = sched_label.replace(":", "")
                if alert_cfg.get("30_min_before", True):
                    h30, m30 = offset_hm(h, m, -30)
                    dedup = (box_id, today_str, f"pre30:{slot_key}")
                    if hm_in_window(now, h30, m30) and dedup not in state["sent_medicine"]:
                        state["sent_medicine"].add(dedup)
                        self._send_medicine_email_for_kind(ctx, "pre30", name, box_id, sched_label)
                if alert_cfg.get("15_min_before", True):
                    h15, m15 = offset_hm(h, m, -15)
                    dedup = (box_id, today_str, f"pre:{slot_key}")
                    if hm_in_window(now, h15, m15) and dedup not in state["sent_medicine"]:
                        state["sent_medicine"].add(dedup)
                        self._send_medicine_email_for_kind(ctx, "pre15", name, box_id, sched_label)
                if alert_cfg.get("exact_time", True):
                    dedup = (box_id, today_str, f"time:{slot_key}")
                    if hm_in_window(now, h, m) and dedup not in state["sent_medicine"]:
                        state["sent_medicine"].add(dedup)
                        self._send_medicine_email_for_kind(ctx, "exact", name, box_id, sched_label)

    def run_checks_for_admin(self, admin_id):
        """Full legacy sweep. Skipped unless BACKEND_TIMED_ALERT_CHECKS=1."""
        if not BACKEND_TIMED_ALERT_CHECKS or not admin_id:
            return
        db = self._get_db()
        if not db:
            return
        try:
            admin = db.get_admin_by_id(admin_id)
            if not admin:
                return
            ctx = self._load_admin_context(db, admin)
            if not ctx:
                return
            aid = ctx["admin_id"]
            duid = ctx["duid"]
            if ctx["boxes"]:
                self._run_user_timed_checks(ctx, self._state(aid, duid))
            self._check_medical_reminders(ctx, self._state(aid, duid))
            for user in ctx.get("users") or []:
                uid = user.get("id")
                bid = (user.get("bot_id") or "").strip()
                akey = (user.get("api_key") or "").strip()
                name = (user.get("name") or "").strip() or "User"
                if not uid:
                    continue
                user_ctx = self._load_user_context(db, ctx, uid, bid, akey, name)
                if not user_ctx["boxes"]:
                    continue
                self._run_user_timed_checks(user_ctx, self._state(aid, uid))
        except Exception as e:
            print(f"[AlertScheduler] run_checks_for_admin error ({admin_id}): {e}")

    # ---- Daily admin summary ----

    def _send_daily_admin_summary(self, ctx):
        try:
            today_str = datetime.datetime.now().strftime("%Y-%m-%d")
            lines = ["=== Medicine status ==="]
            for box_id, med in sorted(ctx["boxes"].items()):
                if med:
                    name = med.get("name", "Unknown")
                    qty = med.get("quantity", 0)
                    expiry = med.get("expiry", "") or "-"
                    lines.append(f"Box {box_id}: {name} \u2013 qty {qty}, expiry {expiry}")
                else:
                    lines.append(f"Box {box_id}: empty")
            lines.append("")
            lines.append("=== Today's dose log ===")
            dose_logs = ctx.get("dose_logs") or []
            today_entries = [e for e in dose_logs if isinstance(e, dict) and (e.get("taken_at") or "").startswith(today_str)]
            if today_entries:
                for e in today_entries[-20:]:
                    ts = e.get("taken_at", "")
                    box = e.get("box_id", "")
                    lines.append(f"  {ts} | {box}")
            else:
                lines.append("  No doses logged today.")
            body = "\n".join(lines)
            self._send_admin_alert(ctx, "daily_summary", body)
        except Exception as e:
            print(f"[AlertScheduler] daily summary error: {e}")

    def run_all_checks_once(self):
        """One-shot sweep — no-op unless BACKEND_TIMED_ALERT_CHECKS=1."""
        if not BACKEND_TIMED_ALERT_CHECKS:
            return
        self._tick()

    # ---- Main tick: run all checks for all admins ----

    def _tick(self):
        if not BACKEND_TIMED_ALERT_CHECKS:
            return
        db = self._get_db()
        if not db:
            return
        try:
            admins = db.get_all_active_admins()
        except Exception as e:
            print(f"[AlertScheduler] failed to load admins: {e}")
            return

        for admin in admins:
            try:
                ctx = self._load_admin_context(db, admin)
                if not ctx:
                    continue
                admin_id = ctx["admin_id"]
                # Linked users only — no legacy dashboard user medicines
                for user in ctx.get("users") or []:
                    uid = user.get("id")
                    bid = (user.get("bot_id") or "").strip()
                    akey = (user.get("api_key") or "").strip()
                    name = (user.get("name") or "").strip() or "User"
                    if not uid:
                        continue
                    user_tz = (user.get("timezone") or "").strip()
                    if not user_tz and hasattr(db, "get_user_timezone_for_user_id"):
                        user_tz = db.get_user_timezone_for_user_id(uid) or ""
                    user_ctx = self._load_user_context(
                        db,
                        ctx,
                        uid,
                        bid,
                        akey,
                        name,
                        user.get("user_display_mode") or "",
                        user_tz,
                    )
                    if not user_ctx["boxes"]:
                        continue
                    state = self._state(admin_id, uid)
                    self._run_user_timed_checks(user_ctx, state)
            except Exception as e:
                print(f"[AlertScheduler] error for admin {admin.get('id', '?')}: {e}")

    def _daily_summary_tick(self):
        db = self._get_db()
        if not db:
            return
        try:
            admins = db.get_all_active_admins()
        except Exception:
            return
        for admin in admins:
            try:
                ctx = self._load_admin_context(db, admin)
                if not ctx:
                    continue
                self._send_daily_admin_summary(ctx)
            except Exception as e:
                print(f"[AlertScheduler] daily summary error for admin {admin.get('id', '?')}: {e}")

    # ---- Lifecycle ----

    def start(self):
        if self.running:
            return
        self.running = True
        self._scheduler = schedule.Scheduler()
        self._scheduler.every().day.at("23:00").do(self._daily_summary_tick)

        print("[AlertScheduler] Event-based: checks run on data change; daily summary at 23:00")

        def run():
            while self.running:
                try:
                    self._scheduler.run_pending()
                except Exception as e:
                    print(f"[AlertScheduler] tick error: {e}")
                time.sleep(30)

        self._thread = threading.Thread(target=run, daemon=True, name="AlertScheduler")
        self._thread.start()

    def stop(self):
        self.running = False
        if hasattr(self, "_scheduler"):
            self._scheduler.clear()
