"""
Central DB client (PostgreSQL). All admin/user and app data live here.
Local SQLite is for cache only. Multiple admins, each with their own users.
"""
import os
import re
import uuid
import json
import secrets
import hashlib
import base64
from datetime import datetime, timezone

# Safe alphabet for admin access code (no 0/O, 1/I)
_ADMIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
_ADMIN_CODE_LENGTH = 8

try:
    import psycopg2
    from psycopg2.extras import RealDictCursor
except ImportError:
    psycopg2 = None
    RealDictCursor = None


def _send_signup_otp_email(to_addr: str, otp_plain: str) -> bool:
    """
    Deliver signup OTP by email when SMTP is configured (e.g. on Vercel set env vars).

    Use *your* mailbox to send (Gmail + app password is typical):
      SIGNUP_SMTP_USER, SIGNUP_SMTP_PASSWORD — login for SMTP (your email + app password).

    Recipients see the friendly sender name (default **Curax system**), not your personal
    address as the headline — From display name is SIGNUP_EMAIL_FROM_NAME; the technical
    From address still defaults to SIGNUP_SMTP_USER (required by Gmail SMTP).

    Optional: SIGNUP_SMTP_HOST (default smtp.gmail.com), SIGNUP_SMTP_PORT (465 SSL or 587 STARTTLS),
    SIGNUP_SMTP_TIMEOUT seconds for connect/send (default 25 — fail fast instead of hanging),
    SIGNUP_EMAIL_FROM (defaults to SIGNUP_SMTP_USER), SIGNUP_EMAIL_FROM_NAME,
    SIGNUP_OTP_EMAIL_SUBJECT (default "Verification code"),
    SIGNUP_OTP_EMAIL_SUBJECT_PREFIX (optional, e.g. "[Action required]" — prepended for stronger inbox alert cues),
    SIGNUP_EMAIL_REPLY_TO.
    """
    import smtplib
    import ssl as ssl_mod
    import html as html_mod
    from email.message import EmailMessage
    from email.policy import SMTP
    from email.utils import formataddr

    smtp_user = (os.environ.get("SIGNUP_SMTP_USER") or "").strip()
    smtp_password = (os.environ.get("SIGNUP_SMTP_PASSWORD") or "").strip()
    if not smtp_user or not smtp_password:
        return False
    host = (os.environ.get("SIGNUP_SMTP_HOST") or "smtp.gmail.com").strip()
    try:
        port = int((os.environ.get("SIGNUP_SMTP_PORT") or "465").strip())
    except ValueError:
        port = 465
    try:
        smtp_timeout = float((os.environ.get("SIGNUP_SMTP_TIMEOUT") or "25").strip())
    except ValueError:
        smtp_timeout = 25.0
    smtp_timeout = max(5.0, min(smtp_timeout, 120.0))
    from_addr = (os.environ.get("SIGNUP_EMAIL_FROM") or smtp_user).strip()
    from_name = (os.environ.get("SIGNUP_EMAIL_FROM_NAME") or "Curax system").strip()
    subject = (os.environ.get("SIGNUP_OTP_EMAIL_SUBJECT") or "Verification code").strip()
    subj_prefix = (os.environ.get("SIGNUP_OTP_EMAIL_SUBJECT_PREFIX") or "").strip()
    if subj_prefix:
        subject = f"{subj_prefix.rstrip()} {subject}".strip()
    reply_to = (os.environ.get("SIGNUP_EMAIL_REPLY_TO") or "").strip()
    to_addr = (to_addr or "").strip()
    if "@" not in to_addr:
        return False

    from utils.email_layout import apply_urgent_notification_headers, curax_email_html, escape as _email_esc

    otp_esc = html_mod.escape((otp_plain or "").strip())
    name_esc = html_mod.escape(from_name)
    # One plain block + single sign-off (avoid repeating the sender / same sentence twice).
    text_body = (
        f"Your verification code is: {otp_plain}. This code expires in 15 minutes. "
        "If you did not request this, you can ignore this email.\n\n"
        f"— {from_name}\n"
    )
    sent_ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    content_rows = (
        '<tr><td style="padding:24px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<div style="font-size:20px;font-weight:700;color:#111827;line-height:1.3;">'
        "Verify your email"
        "</div>"
        '<div style="font-size:14px;color:#6b7280;margin-top:8px;line-height:1.55;">'
        "Use the code below to finish creating your account. It expires in "
        "<strong style=\"color:#374151;\">15 minutes</strong>."
        "</div>"
        "</td></tr>"
        '<tr><td style="padding:8px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<div style="background:#f9fafb;border:1px dashed #d1d5db;border-radius:10px;padding:18px 16px;'
        'text-align:center;">'
        '<div style="font-size:12px;color:#6b7280;text-transform:uppercase;letter-spacing:0.08em;">'
        "Your code"
        "</div>"
        f'<div style="font-size:28px;font-weight:700;letter-spacing:6px;color:#111827;margin-top:10px;">'
        f"{otp_esc}</div>"
        "</div>"
        '<p style="font-size:13px;color:#9ca3af;margin:18px 0 0 0;line-height:1.5;">'
        "If you didn’t request this email, you can safely ignore it."
        "</p>"
        f'<p style="font-size:12px;color:#9ca3af;margin:20px 0 0 0;">— {name_esc}</p>'
        "</td></tr>"
    )
    html_body = curax_email_html(
        content_rows,
        footer_meta=[
            ("Time", _email_esc(sent_ts)),
            ("System", _email_esc("CuraX Intelligent Medicine System")),
            (
                "Recipients",
                f'<span style="color:#2563eb;">{_email_esc(to_addr)}</span>',
            ),
        ],
    )

    msg = EmailMessage(policy=SMTP)
    msg["Subject"] = subject
    msg["From"] = formataddr((from_name, from_addr))
    msg["To"] = to_addr
    if reply_to and "@" in reply_to:
        msg["Reply-To"] = reply_to
    apply_urgent_notification_headers(msg)
    msg.set_content(text_body, subtype="plain", charset="utf-8")
    msg.add_alternative(html_body, subtype="html", charset="utf-8")

    # 587 + STARTTLS often negotiates faster than SSL-on-connect on some networks; 465 remains default.
    if port == 587:
        with smtplib.SMTP(host, port, timeout=smtp_timeout) as server:
            server.ehlo()
            server.starttls(context=ssl_mod.create_default_context())
            server.ehlo()
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    else:
        with smtplib.SMTP_SSL(host, port, timeout=smtp_timeout) as server:
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    return True


def _send_admin_mobile_otp_email(to_addr: str, otp_plain: str, admin_name: str = "") -> bool:
    """Same SMTP config as signup OTP; copy explains this is an admin mobile sign-in attempt."""
    import smtplib
    import ssl as ssl_mod
    import html as html_mod
    from email.message import EmailMessage
    from email.policy import SMTP
    from email.utils import formataddr

    smtp_user = (os.environ.get("SIGNUP_SMTP_USER") or "").strip()
    smtp_password = (os.environ.get("SIGNUP_SMTP_PASSWORD") or "").strip()
    if not smtp_user or not smtp_password:
        return False
    host = (os.environ.get("SIGNUP_SMTP_HOST") or "smtp.gmail.com").strip()
    try:
        port = int((os.environ.get("SIGNUP_SMTP_PORT") or "465").strip())
    except ValueError:
        port = 465
    try:
        smtp_timeout = float((os.environ.get("SIGNUP_SMTP_TIMEOUT") or "25").strip())
    except ValueError:
        smtp_timeout = 25.0
    smtp_timeout = max(5.0, min(smtp_timeout, 120.0))
    from_addr = (os.environ.get("SIGNUP_EMAIL_FROM") or smtp_user).strip()
    from_name = (os.environ.get("SIGNUP_EMAIL_FROM_NAME") or "Curax system").strip()
    subject = (os.environ.get("ADMIN_MOBILE_OTP_EMAIL_SUBJECT") or "Verify admin sign-in").strip()
    subj_prefix = (os.environ.get("SIGNUP_OTP_EMAIL_SUBJECT_PREFIX") or "").strip()
    if subj_prefix:
        subject = f"{subj_prefix.rstrip()} {subject}".strip()
    reply_to = (os.environ.get("SIGNUP_EMAIL_REPLY_TO") or "").strip()
    to_addr = (to_addr or "").strip()
    if "@" not in to_addr:
        return False

    from utils.email_layout import apply_urgent_notification_headers, curax_email_html, escape as _email_esc

    otp_esc = html_mod.escape((otp_plain or "").strip())
    name_esc = html_mod.escape(from_name)
    who = (admin_name or "").strip()
    who_line = f" ({who})" if who else ""
    text_body = (
        f"You are trying to sign in to the Curax admin mobile app{who_line}.\n\n"
        f"Your verification code is: {otp_plain}. This code expires in 15 minutes.\n"
        "If you did not try to sign in as an administrator, ignore this email and "
        "consider changing your desktop admin password under Settings.\n\n"
        f"— {from_name}\n"
    )
    sent_ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    headline = "Administrator sign-in"
    subhead = (
        "Someone entered your admin email and password in the <strong>Curax admin</strong> "
        "mobile app. Use the code below only if this was you. Code expires in "
        '<strong style="color:#374151;">15 minutes</strong>.'
    )
    content_rows = (
        '<tr><td style="padding:24px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<div style="font-size:20px;font-weight:700;color:#111827;line-height:1.3;">'
        f"{html_mod.escape(headline)}"
        "</div>"
        '<div style="font-size:14px;color:#6b7280;margin-top:8px;line-height:1.55;">'
        f"{subhead}"
        "</div>"
        "</td></tr>"
        '<tr><td style="padding:8px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<div style="background:#f9fafb;border:1px dashed #d1d5db;border-radius:10px;padding:18px 16px;'
        'text-align:center;">'
        '<div style="font-size:12px;color:#6b7280;text-transform:uppercase;letter-spacing:0.08em;">'
        "Your code"
        "</div>"
        f'<div style="font-size:28px;font-weight:700;letter-spacing:6px;color:#111827;margin-top:10px;">'
        f"{otp_esc}</div>"
        "</div>"
        '<p style="font-size:13px;color:#9ca3af;margin:18px 0 0 0;line-height:1.5;">'
        "If you did not attempt an admin sign-in, you can ignore this message."
        "</p>"
        f'<p style="font-size:12px;color:#9ca3af;margin:20px 0 0 0;">— {name_esc}</p>'
        "</td></tr>"
    )
    html_body = curax_email_html(
        content_rows,
        footer_meta=[
            ("Time", _email_esc(sent_ts)),
            ("System", _email_esc("CuraX Intelligent Medicine System")),
            ("Recipients", f'<span style="color:#2563eb;">{_email_esc(to_addr)}</span>'),
        ],
    )

    msg = EmailMessage(policy=SMTP)
    msg["Subject"] = subject
    msg["From"] = formataddr((from_name, from_addr))
    msg["To"] = to_addr
    if reply_to and "@" in reply_to:
        msg["Reply-To"] = reply_to
    apply_urgent_notification_headers(msg)
    msg.set_content(text_body, subtype="plain", charset="utf-8")
    msg.add_alternative(html_body, subtype="html", charset="utf-8")

    if port == 587:
        with smtplib.SMTP(host, port, timeout=smtp_timeout) as server:
            server.ehlo()
            server.starttls(context=ssl_mod.create_default_context())
            server.ehlo()
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    else:
        with smtplib.SMTP_SSL(host, port, timeout=smtp_timeout) as server:
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    return True


def _send_admin_email_signup_otp_email(to_addr: str, otp_plain: str) -> bool:
    """OTP for independent mobile administrator registration (new admin row after verify)."""
    import smtplib
    import ssl as ssl_mod
    import html as html_mod
    from email.message import EmailMessage
    from email.policy import SMTP
    from email.utils import formataddr

    smtp_user = (os.environ.get("SIGNUP_SMTP_USER") or "").strip()
    smtp_password = (os.environ.get("SIGNUP_SMTP_PASSWORD") or "").strip()
    if not smtp_user or not smtp_password:
        return False
    host = (os.environ.get("SIGNUP_SMTP_HOST") or "smtp.gmail.com").strip()
    try:
        port = int((os.environ.get("SIGNUP_SMTP_PORT") or "465").strip())
    except ValueError:
        port = 465
    try:
        smtp_timeout = float((os.environ.get("SIGNUP_SMTP_TIMEOUT") or "25").strip())
    except ValueError:
        smtp_timeout = 25.0
    smtp_timeout = max(5.0, min(smtp_timeout, 120.0))
    from_addr = (os.environ.get("SIGNUP_EMAIL_FROM") or smtp_user).strip()
    from_name = (os.environ.get("SIGNUP_EMAIL_FROM_NAME") or "Curax system").strip()
    subject = (os.environ.get("ADMIN_EMAIL_SIGNUP_OTP_SUBJECT") or "Confirm administrator account").strip()
    subj_prefix = (os.environ.get("SIGNUP_OTP_EMAIL_SUBJECT_PREFIX") or "").strip()
    if subj_prefix:
        subject = f"{subj_prefix.rstrip()} {subject}".strip()
    reply_to = (os.environ.get("SIGNUP_EMAIL_REPLY_TO") or "").strip()
    to_addr = (to_addr or "").strip()
    if "@" not in to_addr:
        return False

    from utils.email_layout import apply_urgent_notification_headers, curax_email_html, escape as _email_esc

    otp_esc = html_mod.escape((otp_plain or "").strip())
    name_esc = html_mod.escape(from_name)
    text_body = (
        "You are registering a new Curax administrator account on mobile.\n\n"
        f"Your verification code is: {otp_plain}. This code expires in 15 minutes.\n"
        "If you did not start this registration, ignore this email.\n\n"
        f"— {from_name}\n"
    )
    sent_ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    headline = "Administrator registration"
    subhead = (
        "Use the code below to finish creating your administrator account on mobile. "
        "Code expires in <strong style=\"color:#374151;\">15 minutes</strong>."
    )
    content_rows = (
        '<tr><td style="padding:24px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<div style="font-size:20px;font-weight:700;color:#111827;line-height:1.3;">'
        f"{html_mod.escape(headline)}"
        "</div>"
        '<div style="font-size:14px;color:#6b7280;margin-top:8px;line-height:1.55;">'
        f"{subhead}"
        "</div>"
        "</td></tr>"
        '<tr><td style="padding:8px 28px 8px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<div style="background:#f9fafb;border:1px dashed #d1d5db;border-radius:10px;padding:18px 16px;'
        'text-align:center;">'
        '<div style="font-size:12px;color:#6b7280;text-transform:uppercase;letter-spacing:0.08em;">'
        "Your code"
        "</div>"
        f'<div style="font-size:28px;font-weight:700;letter-spacing:6px;color:#111827;margin-top:10px;">'
        f"{otp_esc}</div>"
        "</div>"
        '<p style="font-size:13px;color:#9ca3af;margin:18px 0 0 0;line-height:1.5;">'
        "If you did not register as an administrator, you can ignore this message."
        "</p>"
        f'<p style="font-size:12px;color:#9ca3af;margin:20px 0 0 0;">— {name_esc}</p>'
        "</td></tr>"
    )
    html_body = curax_email_html(
        content_rows,
        footer_meta=[
            ("Time", _email_esc(sent_ts)),
            ("System", _email_esc("CuraX Intelligent Medicine System")),
            ("Recipients", f'<span style="color:#2563eb;">{_email_esc(to_addr)}</span>'),
        ],
    )

    msg = EmailMessage(policy=SMTP)
    msg["Subject"] = subject
    msg["From"] = formataddr((from_name, from_addr))
    msg["To"] = to_addr
    if reply_to and "@" in reply_to:
        msg["Reply-To"] = reply_to
    apply_urgent_notification_headers(msg)
    msg.set_content(text_body, subtype="plain", charset="utf-8")
    msg.add_alternative(html_body, subtype="html", charset="utf-8")

    if port == 587:
        with smtplib.SMTP(host, port, timeout=smtp_timeout) as server:
            server.ehlo()
            server.starttls(context=ssl_mod.create_default_context())
            server.ehlo()
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    else:
        with smtplib.SMTP_SSL(host, port, timeout=smtp_timeout) as server:
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    return True


def _get_connection_string(url=None, **kwargs):
    if url and str(url).strip():
        return url.strip()
    if kwargs.get("host"):
        u = kwargs.get("user") or os.environ.get("PGUSER", "")
        p = kwargs.get("password") or os.environ.get("PGPASSWORD", "")
        d = kwargs.get("dbname") or kwargs.get("database") or os.environ.get("PGDATABASE", "curax_central")
        port = kwargs.get("port") or os.environ.get("PGPORT", "5432")
        return f"postgresql://{u}:{p}@{kwargs['host']}:{port}/{d}"
    return (os.environ.get("DATABASE_URL") or os.environ.get("CENTRAL_DB_URL") or "").strip() or None


class EmailAlreadyUsedError(Exception):
    """Raised when creating or updating an admin with an email that another admin already has."""


class CentralDB:
    """PostgreSQL central DB: admins and users (one admin ↔ their users; multiple admins)."""

    def __init__(self, connection_string=None, **kwargs):
        self._conn_str = _get_connection_string(connection_string, **kwargs) or ""
        self._conn = None

    def connect(self):
        if not psycopg2:
            raise RuntimeError("Install psycopg2-binary: pip install psycopg2-binary")
        if not self._conn_str:
            raise ValueError("Central DB: set DATABASE_URL or CENTRAL_DB_URL, or pass connection_string/host")
        self._conn = psycopg2.connect(self._conn_str)
        return self._conn

    def _ensure_conn(self):
        if self._conn is None or self._conn.closed:
            self.connect()
        return self._conn

    def close(self):
        if self._conn and not self._conn.closed:
            self._conn.close()
            self._conn = None

    def is_available(self):
        """True if connection string is set and we can connect."""
        if not self._conn_str:
            return False
        try:
            self._ensure_conn()
            return True
        except Exception:
            return False

    def has_signup_sessions_table(self) -> bool:
        """True if public.signup_sessions exists (central_schema.sql applied)."""
        try:
            cur = self._ensure_conn().cursor()
            try:
                cur.execute(
                    """
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'signup_sessions'
                    LIMIT 1
                    """
                )
                return cur.fetchone() is not None
            finally:
                cur.close()
        except Exception:
            return False

    def has_admin_mobile_login_challenges_table(self) -> bool:
        try:
            cur = self._ensure_conn().cursor()
            try:
                cur.execute(
                    """
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'admin_mobile_login_challenges'
                    LIMIT 1
                    """
                )
                return cur.fetchone() is not None
            finally:
                cur.close()
        except Exception:
            return False

    def has_admin_email_signup_sessions_table(self) -> bool:
        try:
            cur = self._ensure_conn().cursor()
            try:
                cur.execute(
                    """
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'admin_email_signup_sessions'
                    LIMIT 1
                    """
                )
                return cur.fetchone() is not None
            finally:
                cur.close()
        except Exception:
            return False

    def _generate_admin_access_code(self):
        """Generate a unique 8-char admin access code (e.g. A1B2C3D4)."""
        for _ in range(20):
            code = "A" + "".join(secrets.choice(_ADMIN_CODE_ALPHABET) for _ in range(_ADMIN_CODE_LENGTH - 1))
            conn = self._ensure_conn()
            cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
            try:
                cur.execute("SELECT 1 FROM admins WHERE admin_access_code = %s LIMIT 1", (code,))
                if cur.fetchone() is None:
                    return code
            finally:
                cur.close()
        return "A" + (secrets.token_hex(4).upper()[: _ADMIN_CODE_LENGTH - 1])  # fallback

    def _generate_connection_code(self):
        """Generate a unique 8-char connection code (e.g. C1B2C3D4) for linking users to this admin."""
        for _ in range(20):
            code = "C" + "".join(secrets.choice(_ADMIN_CODE_ALPHABET) for _ in range(_ADMIN_CODE_LENGTH - 1))
            conn = self._ensure_conn()
            cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
            try:
                cur.execute("SELECT 1 FROM admins WHERE connection_code = %s LIMIT 1", (code,))
                if cur.fetchone() is None:
                    return code
            finally:
                cur.close()
        return "C" + (secrets.token_hex(4).upper()[: _ADMIN_CODE_LENGTH - 1])  # fallback

    def purge_expired_desktop_link_codes(self, admin_id=None):
        """Remove expired rows (and optionally stale rows for one admin). Keeps DB session-clean."""
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            if admin_id:
                cur.execute(
                    "DELETE FROM desktop_link_codes WHERE expires_at <= NOW() OR admin_id = %s",
                    (admin_id,),
                )
            else:
                cur.execute("DELETE FROM desktop_link_codes WHERE expires_at <= NOW()")
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB purge_expired_desktop_link_codes: {e}")
        finally:
            cur.close()

    def ensure_admin_access_code(self, admin_id):
        """Return admin_access_code for sync/databus; generate and persist if missing."""
        aid = (admin_id or "").strip()
        if not aid:
            return None
        existing = self.get_admin_access_code_by_id(aid)
        if existing:
            return existing
        code = self._generate_admin_access_code()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "UPDATE admins SET admin_access_code = %s, updated_at = NOW() "
                "WHERE id = %s AND (admin_access_code IS NULL OR admin_access_code = '') "
                "RETURNING admin_access_code",
                (code, aid),
            )
            row = cur.fetchone()
            conn.commit()
            if row and row[0]:
                return str(row[0]).strip()
            return self.get_admin_access_code_by_id(aid) or code
        except Exception as e:
            conn.rollback()
            print(f"CentralDB ensure_admin_access_code: {e}")
            return None
        finally:
            cur.close()

    def create_desktop_link_code_for_bot(self, bot_id, api_key, expires_seconds=300):
        """Admin app (signed in on phone): create one-time PC link code from this device's bot_id + api_key."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None, None, None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            self.purge_expired_desktop_link_codes()
            cur.execute(
                "SELECT id, name FROM admins WHERE bot_id = %s AND api_key = %s LIMIT 1",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if not row:
                return None, None, None
            admin_id = row["id"] if hasattr(row, "keys") else row[0]
            admin_name = (row["name"] if hasattr(row, "keys") else row[1]) or "Admin"
            # One active link session per admin: replace any previous code.
            cur.execute("DELETE FROM desktop_link_codes WHERE admin_id = %s", (admin_id,))
            from datetime import timedelta
            expires_at = datetime.now(timezone.utc) + timedelta(seconds=expires_seconds)
            for _ in range(20):
                link_code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
                try:
                    cur.execute(
                        "INSERT INTO desktop_link_codes (code, admin_id, expires_at) VALUES (%s, %s, %s)",
                        (link_code, admin_id, expires_at),
                    )
                    if cur.rowcount:
                        conn.commit()
                        return link_code, str(admin_id), admin_name
                except Exception:
                    conn.rollback()
                    continue
            return None, None, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB create_desktop_link_code_for_bot: {e}")
            return None, None, None
        finally:
            cur.close()

    def create_desktop_link_code(self, access_code, expires_seconds=300):
        """Legacy: resolve admin by admin_access_code. Prefer create_desktop_link_code_for_bot from the app."""
        code = (access_code or "").strip().upper()
        if not code:
            return None, None, None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id, name FROM admins WHERE admin_access_code = %s LIMIT 1",
                (code,),
            )
            row = cur.fetchone()
            if not row:
                return None, None, None
            admin_id = row["id"] if hasattr(row, "keys") else row[0]
            admin_name = (row["name"] if hasattr(row, "keys") else row[1]) or "Admin"
            from datetime import timedelta
            expires_at = datetime.now(timezone.utc) + timedelta(seconds=expires_seconds)
            for _ in range(20):
                link_code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
                try:
                    cur.execute(
                        "INSERT INTO desktop_link_codes (code, admin_id, expires_at) VALUES (%s, %s, %s)",
                        (link_code, admin_id, expires_at),
                    )
                    if cur.rowcount:
                        conn.commit()
                        return link_code, str(admin_id), admin_name
                except Exception:
                    conn.rollback()
                    continue
            return None, None, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB create_desktop_link_code: {e}")
            return None, None, None
        finally:
            cur.close()

    def redeem_desktop_link_code(self, code):
        """Validate code, load admin, delete code row. Returns dict or None. Purges expired rows first."""
        code = (code or "").strip().upper()
        if not code:
            return None
        self.purge_expired_desktop_link_codes()
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT d.admin_id, a.name, a.connection_code "
                "FROM desktop_link_codes d JOIN admins a ON a.id = d.admin_id "
                "WHERE d.code = %s AND d.expires_at > NOW() LIMIT 1",
                (code,),
            )
            row = cur.fetchone()
            if not row:
                return None
            if hasattr(row, "keys"):
                admin_id = row["admin_id"]
                admin_name = row["name"] or "Admin"
                connection_code = (row.get("connection_code") or "").strip()
            else:
                admin_id, admin_name, connection_code = row[0], row[1] or "Admin", (row[2] or "").strip()
            cur.execute("DELETE FROM desktop_link_codes WHERE code = %s", (code,))
            conn.commit()
            admin_id_str = str(admin_id)
            sync_key = self.ensure_admin_access_code(admin_id_str)
            return {
                "admin_id": admin_id_str,
                "admin_name": admin_name,
                "connection_code": connection_code,
                "sync_key": sync_key or "",
            }
        except Exception as e:
            conn.rollback()
            print(f"CentralDB redeem_desktop_link_code: {e}")
            return None
        finally:
            cur.close()

    def get_admin_by_desktop_link_code(self, code):
        """Legacy alias — prefer redeem_desktop_link_code."""
        return self.redeem_desktop_link_code(code)

    def _admin_id_by_email(self, email):
        """Return admin id that has this email (normalized: strip + lower), or None."""
        raw = (email or "").strip()
        if not raw:
            return None
        norm = raw.lower()
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id FROM admins WHERE LOWER(TRIM(email)) = %s LIMIT 1",
                (norm,),
            )
            row = cur.fetchone()
            if row:
                return str(row["id"]) if hasattr(row, "keys") else str(row[0])
            return None
        finally:
            cur.close()

    # ---- Admins (from Admin Panel: bot_id + api_key) ----
    def upsert_admin_from_bot(self, bot_id, api_key, name=None, email=None, fcm_token=None, desktop_password_plain=None):
        """Insert or update admin by (bot_id, api_key). Returns (admin_id, admin_access_code, connection_code) or (None, None, None).
        New admins get unique admin_access_code and connection_code; existing admins keep their codes.
        fcm_token: when provided, stored so backend can send push alerts to this admin.
        desktop_password_plain: when set (min length enforced by caller), stores PBKDF2 hash for mobile admin sign-in.
        """
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        email_val = (email or "").strip()
        fcm = (fcm_token or "").strip() or None
        dp = (desktop_password_plain or "").strip()
        pw_hash = self._hash_signup_password(dp) if len(dp) >= 6 else None
        if not bot_id or not api_key:
            return None, None, None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            if email_val:
                existing_id = self._admin_id_by_email(email_val)
                if existing_id:
                    cur.execute(
                        "SELECT id FROM admins WHERE bot_id = %s AND api_key = %s LIMIT 1",
                        (bot_id, api_key),
                    )
                    current = cur.fetchone()
                    current_id = str(current["id"]) if current and hasattr(current, "keys") else (str(current[0]) if current else None)
                    if current_id != existing_id:
                        raise EmailAlreadyUsedError("An admin with this email already exists.")
            access_code = self._generate_admin_access_code()
            connection_code = self._generate_connection_code()
            cur.execute(
                """
                INSERT INTO admins (name, email, bot_id, api_key, admin_access_code, connection_code, fcm_token, is_admin, updated_at, desktop_password_hash)
                VALUES (%s, %s, %s, %s, %s, %s, %s, true, NOW(), %s)
                ON CONFLICT (bot_id, api_key)
                DO UPDATE SET name = COALESCE(EXCLUDED.name, admins.name),
                              email = COALESCE(EXCLUDED.email, admins.email),
                              admin_access_code = COALESCE(admins.admin_access_code, EXCLUDED.admin_access_code),
                              connection_code = COALESCE(admins.connection_code, EXCLUDED.connection_code),
                              fcm_token = COALESCE(NULLIF(TRIM(EXCLUDED.fcm_token), ''), admins.fcm_token),
                              desktop_password_hash = COALESCE(EXCLUDED.desktop_password_hash, admins.desktop_password_hash),
                              is_admin = true,
                              updated_at = NOW()
                RETURNING id, admin_access_code, connection_code
                """,
                (name or "", email_val or "", bot_id, api_key, access_code, connection_code, fcm, pw_hash),
            )
            row = cur.fetchone()
            conn.commit()
            if row:
                aid = row["id"] if hasattr(row, "keys") else row[0]
                ac = (row["admin_access_code"] if hasattr(row, "keys") else row[1]) if row else None
                cc = (row["connection_code"] if hasattr(row, "keys") else row[2]) if row else None
                return (str(aid) if isinstance(aid, uuid.UUID) else aid), (ac or access_code), (cc or connection_code)
            return None, None, None
        except EmailAlreadyUsedError:
            conn.rollback()
            raise
        except Exception as e:
            conn.rollback()
            print(f"CentralDB upsert_admin_from_bot: {e}")
            return None, None, None
        finally:
            cur.close()

    def update_admin_bot_by_access_code(self, access_code, bot_id, api_key, name=None, email=None, fcm_token=None, desktop_password_plain=None):
        """Find admin by access_code and set their bot_id, api_key, name, email, fcm_token (e.g. when app registers).
        If another admin row has this (bot_id, api_key), delete it first so we keep one admin per access_code.
        Returns (admin_id, admin_access_code, connection_code) or (None, None, None).
        """
        code = (access_code or "").strip().upper()
        if not code or not (bot_id or "").strip() or not (api_key or "").strip():
            return None, None, None
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        email_val = (email or "").strip()
        dp = (desktop_password_plain or "").strip()
        pw_hash = self._hash_signup_password(dp) if len(dp) >= 6 else None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute("SELECT id FROM admins WHERE admin_access_code = %s LIMIT 1", (code,))
            target = cur.fetchone()
            if not target:
                return None, None, None
            target_id = target["id"] if hasattr(target, "keys") else target[0]
            target_id_str = str(target_id)
            if email_val:
                existing_id = self._admin_id_by_email(email_val)
                if existing_id and existing_id != target_id_str:
                    raise EmailAlreadyUsedError("An admin with this email already exists.")
            cur.execute(
                "DELETE FROM admins WHERE bot_id = %s AND api_key = %s AND id != %s::uuid",
                (bot_id, api_key, target_id),
            )
            fcm = (fcm_token or "").strip() or None
            cur.execute(
                """
                UPDATE admins SET bot_id = %s, api_key = %s, name = COALESCE(NULLIF(%s, ''), name),
                email = COALESCE(NULLIF(%s, ''), email), fcm_token = COALESCE(NULLIF(%s, ''), fcm_token),
                desktop_password_hash = COALESCE(%s, admins.desktop_password_hash),
                is_admin = true, updated_at = NOW()
                WHERE admin_access_code = %s
                RETURNING id, admin_access_code, connection_code
                """,
                (bot_id, api_key, name or "", email_val or "", fcm or "", pw_hash, code),
            )
            row = cur.fetchone()
            conn.commit()
            if row:
                aid = row["id"] if hasattr(row, "keys") else row[0]
                ac = (row["admin_access_code"] if hasattr(row, "keys") else row[1]) if row else None
                cc = (row["connection_code"] if hasattr(row, "keys") else row[2]) if row else None
                return (str(aid) if isinstance(aid, uuid.UUID) else aid), ac, cc
            return None, None, None
        except EmailAlreadyUsedError:
            conn.rollback()
            raise
        except Exception as e:
            conn.rollback()
            print(f"CentralDB update_admin_bot_by_access_code: {e}")
            return None, None, None
        finally:
            cur.close()

    def get_admin_bot_by_access_code(self, access_code):
        """Return { bot_id, api_key, fcm_token } for admin with this access_code, or None.
        Used when desktop notifies backend of events; fcm_token is passed to relay for FCM-first push."""
        code = (access_code or "").strip().upper()
        if not code:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute("SELECT bot_id, api_key, fcm_token FROM admins WHERE admin_access_code = %s LIMIT 1", (code,))
            row = cur.fetchone()
            if row and hasattr(row, "keys"):
                bid = (row.get("bot_id") or "").strip()
                akey = (row.get("api_key") or "").strip()
                fcm = (row.get("fcm_token") or "").strip() or None
                if bid and akey:
                    return {"bot_id": bid, "api_key": akey, "fcm_token": fcm}
            if row:
                bid = (row[0] or "").strip()
                akey = (row[1] or "").strip()
                fcm = (row[2] or "").strip() or None if len(row) > 2 else None
                if bid and akey:
                    return {"bot_id": bid, "api_key": akey, "fcm_token": fcm}
            return None
        except Exception as e:
            print(f"CentralDB get_admin_bot_by_access_code: {e}")
            return None
        finally:
            cur.close()

    def get_fcm_token_for_bot(self, bot_id, api_key):
        """Return fcm_token for this bot_id+api_key (admin or user). Used so relay can try FCM first."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT fcm_token FROM admins WHERE bot_id = %s AND api_key = %s LIMIT 1", (bot_id, api_key))
            row = cur.fetchone()
            if row and row[0]:
                t = (row[0] or "").strip()
                if t:
                    return t
            cur.execute("SELECT fcm_token FROM users WHERE bot_id = %s AND api_key = %s LIMIT 1", (bot_id, api_key))
            row = cur.fetchone()
            if row and row[0]:
                t = (row[0] or "").strip()
                if t:
                    return t
            return None
        except Exception as e:
            print(f"CentralDB get_fcm_token_for_bot: {e}")
            return None
        finally:
            cur.close()

    def update_admin_fcm_token_by_access_code(self, access_code, fcm_token):
        """Update only fcm_token for the admin with this access_code. Used when app gets FCM token after permission grant.
        Returns True if admin was found and updated."""
        code = (access_code or "").strip().upper()
        if not code:
            return False
        fcm = (fcm_token or "").strip() or None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "UPDATE admins SET fcm_token = %s, updated_at = NOW() WHERE admin_access_code = %s",
                (fcm or "", code),
            )
            conn.commit()
            return cur.rowcount > 0
        except Exception as e:
            conn.rollback()
            print(f"CentralDB update_admin_fcm_token_by_access_code: {e}")
            return False
        finally:
            cur.close()

    def get_admin_connection_status(self, access_code):
        """Return { connected: bool, fcm_token_set: bool } for admin with this access_code, or None if not found.
        connected = has bot_id and api_key; fcm_token_set = has non-empty fcm_token."""
        code = (access_code or "").strip().upper()
        if not code:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "SELECT bot_id, api_key, fcm_token FROM admins WHERE admin_access_code = %s LIMIT 1",
                (code,),
            )
            row = cur.fetchone()
            if not row:
                return None
            bid = (row[0] or "").strip()
            akey = (row[1] or "").strip()
            fcm = (row[2] or "").strip() if len(row) > 2 else ""
            return {
                "connected": bool(bid and akey),
                "fcm_token_set": bool(fcm),
            }
        except Exception as e:
            print(f"CentralDB get_admin_connection_status: {e}")
            return None
        finally:
            cur.close()

    def get_admin_by_access_code(self, access_code):
        """Return admin row { id, name, admin_access_code, connection_code } for this access code, or None.
        Used by Android app: enter code → identify as admin.
        """
        code = (access_code or "").strip().upper()
        if not code:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id, name, admin_access_code, connection_code FROM admins WHERE admin_access_code = %s LIMIT 1",
                (code,),
            )
            row = cur.fetchone()
            if row and hasattr(row, "keys"):
                return {
                    "id": str(row["id"]), "name": row["name"],
                    "admin_access_code": row["admin_access_code"],
                    "connection_code": row.get("connection_code") if hasattr(row, "get") else (row[3] if len(row) > 3 else None),
                }
            if row:
                return {"id": str(row[0]), "name": row[1], "admin_access_code": row[2], "connection_code": row[3] if len(row) > 3 else None}
            return None
        except Exception as e:
            print(f"CentralDB get_admin_by_access_code: {e}")
            return None
        finally:
            cur.close()

    def get_admin_by_connection_code(self, connection_code):
        """Return admin row { id, name, connection_code } for this connection code, or None.
        Used when a user enters admin's connection code to link to that admin.
        """
        code = (connection_code or "").strip().upper()
        if not code:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id, name, connection_code, admin_access_code FROM admins WHERE connection_code = %s LIMIT 1",
                (code,),
            )
            row = cur.fetchone()
            if row and hasattr(row, "keys"):
                return {
                    "id": str(row["id"]),
                    "name": row["name"],
                    "connection_code": row["connection_code"],
                    "admin_access_code": (row.get("admin_access_code") or "").strip(),
                }
            if row:
                return {
                    "id": str(row[0]),
                    "name": row[1],
                    "connection_code": row[2],
                    "admin_access_code": (row[3] or "").strip() if len(row) > 3 else "",
                }
            return None
        except Exception as e:
            print(f"CentralDB get_admin_by_connection_code: {e}")
            return None
        finally:
            cur.close()

    def get_admin_access_code_by_id(self, admin_id):
        """Return admin_access_code for this admin UUID (data bus room key)."""
        aid = (admin_id or "").strip()
        if not aid:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "SELECT admin_access_code FROM admins WHERE id = %s LIMIT 1",
                (aid,),
            )
            row = cur.fetchone()
            if row and row[0]:
                return str(row[0]).strip()
            return None
        except Exception as e:
            print(f"CentralDB get_admin_access_code_by_id: {e}")
            return None
        finally:
            cur.close()

    def delete_admin_by_access_code(self, access_code):
        """Delete admin and all data for that admin: users (and their medicines, dose_logs, alert_settings, alerts), sync_logs. DB is empty for this admin."""
        code = (access_code or "").strip().upper()
        if not code:
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT id, email FROM admins WHERE admin_access_code = %s LIMIT 1", (code,))
            row = cur.fetchone()
            if not row:
                return False
            admin_id = row[0]
            email_val = (row[1] or "").strip() if len(row) > 1 else ""

            # Get all admin_id(s) we are about to delete (this one, or all with same email)
            if email_val:
                cur.execute("SELECT id FROM admins WHERE LOWER(TRIM(email)) = LOWER(TRIM(%s))", (email_val,))
                admin_ids = [r[0] for r in cur.fetchall()]
            else:
                admin_ids = [admin_id]

            # Delete sync_logs for any user belonging to these admins (sync_logs has no ON DELETE CASCADE on user_id)
            if admin_ids:
                placeholders = ",".join(["%s::uuid"] * len(admin_ids))
                cur.execute(
                    f"DELETE FROM sync_logs WHERE user_id IN (SELECT id FROM users WHERE admin_id IN ({placeholders}))",
                    tuple(admin_ids),
                )

            # Delete admins; CASCADE will delete users -> medicines, dose_logs, alert_settings, alerts
            if email_val:
                cur.execute("DELETE FROM admins WHERE LOWER(TRIM(email)) = LOWER(TRIM(%s))", (email_val,))
            else:
                cur.execute("DELETE FROM admins WHERE admin_access_code = %s", (code,))
            conn.commit()
            return cur.rowcount > 0
        except Exception as e:
            conn.rollback()
            print(f"CentralDB delete_admin_by_access_code: {e}")
            return False
        finally:
            cur.close()

    def get_admin_id_by_bot(self, bot_id, api_key):
        """Return admin UUID (string) for this bot_id+api_key, or None."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id FROM admins WHERE bot_id = %s AND api_key = %s LIMIT 1",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if row:
                aid = row["id"] if hasattr(row, "keys") else row[0]
                return str(aid) if isinstance(aid, uuid.UUID) else aid
            return None
        except Exception as e:
            print(f"CentralDB get_admin_id_by_bot: {e}")
            return None
        finally:
            cur.close()

    def get_admin_id_by_user_id(self, user_id):
        """Return admin_id for the given user_id, or None. Used for event-based alert triggers."""
        if not user_id:
            return None
        try:
            uuid.UUID(str(user_id))
        except (ValueError, TypeError):
            return None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT admin_id FROM users WHERE id = %s::uuid LIMIT 1", (user_id,))
            row = cur.fetchone()
            if row:
                aid = row[0]
                return str(aid) if isinstance(aid, uuid.UUID) else aid
            return None
        except Exception as e:
            print(f"CentralDB get_admin_id_by_user_id: {e}")
            return None
        finally:
            cur.close()

    def get_admin_info_from_bot(self, bot_id=None, api_key=None):
        """Return admin row (id, name, email, bot_id, api_key, admin_access_code, connection_code) for display. If no args, return first admin (dev)."""
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            if bot_id and api_key:
                cur.execute(
                    "SELECT id, name, email, bot_id, api_key, admin_access_code, connection_code FROM admins WHERE bot_id = %s AND api_key = %s LIMIT 1",
                    (bot_id.strip(), api_key.strip()),
                )
            else:
                cur.execute("SELECT id, name, email, bot_id, api_key, admin_access_code, connection_code FROM admins LIMIT 1")
            row = cur.fetchone()
            if row:
                if hasattr(row, "keys"):
                    return {
                        "id": str(row["id"]), "name": row["name"], "email": row["email"],
                        "bot_id": row["bot_id"], "api_key": row["api_key"],
                        "admin_access_code": (row.get("admin_access_code") if hasattr(row, "get") else (row[5] if len(row) > 5 else None)),
                        "connection_code": (row.get("connection_code") if hasattr(row, "get") else (row[6] if len(row) > 6 else None)),
                    }
                return {"id": str(row[0]), "name": row[1], "email": row[2], "bot_id": row[3], "api_key": row[4], "admin_access_code": row[5] if len(row) > 5 else None, "connection_code": row[6] if len(row) > 6 else None}
            return None
        except Exception as e:
            print(f"CentralDB get_admin_info_from_bot: {e}")
            return None
        finally:
            cur.close()

    # ---- Users (from System Settings: bot_id + api_key, linked to admin_id) ----
    def _delete_other_users_by_admin_and_email(self, admin_id, email, keep_bot_id, keep_api_key, migrate_to_user_id=None):
        """Remove any other user rows for this admin_id + email (different device/install). Keeps the row for (keep_bot_id, keep_api_key).
        If migrate_to_user_id is provided, migrates medicines, dose_logs, and alert_settings from old users to the new user before deleting."""
        email = (email or "").strip()
        if not email:
            return
        try:
            uuid.UUID(str(admin_id))
        except (ValueError, TypeError):
            return
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            # First, find old user_ids that will be deleted
            cur.execute(
                """
                SELECT id FROM users
                WHERE admin_id = %s::uuid AND LOWER(TRIM(email)) = LOWER(TRIM(%s))
                AND (bot_id != %s OR api_key != %s)
                """,
                (admin_id, email, keep_bot_id, keep_api_key),
            )
            old_user_ids = [row["id"] if hasattr(row, "keys") else row[0] for row in cur.fetchall()]
            
            # Migrate data from old users to new user if provided
            if migrate_to_user_id and old_user_ids:
                try:
                    uuid.UUID(str(migrate_to_user_id))
                    for old_uid in old_user_ids:
                        # Migrate medicines (all medicines from old user to new user)
                        cur.execute(
                            "UPDATE medicines SET user_id = %s::uuid WHERE user_id = %s::uuid",
                            (migrate_to_user_id, old_uid)
                        )
                        # Migrate dose_logs (all dose logs from old user to new user)
                        cur.execute(
                            "UPDATE dose_logs SET user_id = %s::uuid WHERE user_id = %s::uuid",
                            (migrate_to_user_id, old_uid)
                        )
                        # Migrate alerts (all alerts from old user to new user)
                        cur.execute(
                            "UPDATE alerts SET user_id = %s::uuid WHERE user_id = %s::uuid",
                            (migrate_to_user_id, old_uid)
                        )
                        # Migrate sync_logs (sync history from old user to new user)
                        try:
                            cur.execute(
                                "UPDATE sync_logs SET user_id = %s::uuid WHERE user_id = %s::uuid",
                                (migrate_to_user_id, old_uid)
                            )
                        except Exception:
                            pass  # sync_logs might not exist or have different schema
                        # Migrate alert_settings: merge settings from old user into new user
                        # If new user already has settings, merge; otherwise copy old user's settings
                        try:
                            # Get old user's alert_settings
                            cur.execute(
                                "SELECT settings FROM alert_settings WHERE user_id = %s::uuid LIMIT 1",
                                (old_uid,)
                            )
                            old_settings_row = cur.fetchone()
                            if old_settings_row:
                                old_settings_raw = old_settings_row["settings"] if hasattr(old_settings_row, "keys") else old_settings_row[0]
                                old_settings = old_settings_raw if isinstance(old_settings_raw, dict) else (json.loads(old_settings_raw) if isinstance(old_settings_raw, str) and old_settings_raw else {})
                                
                                # Get new user's existing alert_settings (if any)
                                cur.execute(
                                    "SELECT settings FROM alert_settings WHERE user_id = %s::uuid LIMIT 1",
                                    (migrate_to_user_id,)
                                )
                                new_settings_row = cur.fetchone()
                                new_settings = {}
                                if new_settings_row:
                                    new_settings_raw = new_settings_row["settings"] if hasattr(new_settings_row, "keys") else new_settings_row[0]
                                    new_settings = new_settings_raw if isinstance(new_settings_raw, dict) else (json.loads(new_settings_raw) if isinstance(new_settings_raw, str) and new_settings_raw else {})
                                
                                # Merge: prioritize old user's data (they're re-signing up, so their previous data should be preserved)
                                # Start with old_settings, then merge in any new_settings that don't conflict
                                merged_settings = old_settings.copy()
                                # Only add new_settings keys that don't exist in old_settings (preserve old data)
                                for key, value in new_settings.items():
                                    if key not in merged_settings:
                                        merged_settings[key] = value
                                    # Special handling for nested dicts: merge them too
                                    elif isinstance(merged_settings[key], dict) and isinstance(value, dict):
                                        merged_dict = merged_settings[key].copy()
                                        merged_dict.update(value)
                                        merged_settings[key] = merged_dict
                                
                                # Upsert merged settings (old user's data is preserved)
                                merged_json = json.dumps(merged_settings)
                                cur.execute(
                                    """INSERT INTO alert_settings (user_id, settings, updated_at)
                                       VALUES (%s::uuid, %s::jsonb, NOW())
                                       ON CONFLICT (user_id) DO UPDATE SET settings = EXCLUDED.settings, updated_at = NOW()""",
                                    (migrate_to_user_id, merged_json)
                                )
                            else:
                                # No old settings, but update user_id if row exists (shouldn't happen, but safe)
                                cur.execute(
                                    "UPDATE alert_settings SET user_id = %s::uuid WHERE user_id = %s::uuid",
                                    (migrate_to_user_id, old_uid)
                                )
                        except Exception as e:
                            # If alert_settings table doesn't exist or has issues, log but continue
                            if "column" not in str(e).lower() and "table" not in str(e).lower():
                                print(f"CentralDB migrate alert_settings: {e}")
                except (ValueError, TypeError) as e:
                    print(f"CentralDB migrate data: invalid UUID - {e}")
                    pass  # Invalid UUID, skip migration
            
            # Now delete old users
            cur.execute(
                """
                DELETE FROM users
                WHERE admin_id = %s::uuid AND LOWER(TRIM(email)) = LOWER(TRIM(%s))
                AND (bot_id != %s OR api_key != %s)
                """,
                (admin_id, email, keep_bot_id, keep_api_key),
            )
            conn.commit()
        except Exception as e:
            conn.rollback()
            # Ignore if email column does not exist yet (run: ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255))
            if "column" not in str(e).lower() and "email" not in str(e).lower():
                print(f"CentralDB _delete_other_users_by_admin_and_email: {e}")
        finally:
            cur.close()

    def upsert_user_from_bot(self, bot_id, api_key, admin_id, name=None, email=None, fcm_token=None, username=None):
        """Insert or update the user by (bot_id, api_key); links this app to the given admin_id. Returns user id or None.
        If email is provided, any other user rows for the same admin_id + email (previous installs) are deleted first,
        but their medicines, dose_logs, and alert_settings are migrated to the new user to preserve data.
        fcm_token: when provided, stored for push alerts to this user.
        username: optional display handle (e.g. first + last from signup). When omitted, derived from name.
        """
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key or not admin_id:
            return None
        try:
            uuid.UUID(str(admin_id))
        except (ValueError, TypeError):
            return None
        email_clean = (email or "").strip()
        fcm = (fcm_token or "").strip() or None
        if username is not None:
            uname_val = str(username).strip() or None
        else:
            uname_val = ((name or "")).strip() or None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            # Schema: users may have email, fcm_token, username columns
            try:
                cur.execute(
                    """
                    INSERT INTO users (admin_id, name, email, username, bot_id, api_key, role, fcm_token, updated_at)
                    VALUES (%s::uuid, %s, %s, %s, %s, %s, 'user', %s, NOW())
                    ON CONFLICT (bot_id, api_key)
                    DO UPDATE SET admin_id = EXCLUDED.admin_id,
                                  name = COALESCE(EXCLUDED.name, users.name),
                                  email = COALESCE(EXCLUDED.email, users.email),
                                  username = COALESCE(NULLIF(TRIM(EXCLUDED.username), ''), users.username),
                                  fcm_token = COALESCE(NULLIF(TRIM(EXCLUDED.fcm_token), ''), users.fcm_token),
                                  updated_at = NOW()
                    RETURNING id
                    """,
                    (admin_id, name or "", email_clean or None, uname_val, bot_id, api_key, fcm),
                )
            except Exception as e0:
                conn.rollback()
                el0 = str(e0).lower()
                if "username" not in el0:
                    raise e0
                cur.execute(
                    """
                    INSERT INTO users (admin_id, name, email, bot_id, api_key, role, fcm_token, updated_at)
                    VALUES (%s::uuid, %s, %s, %s, %s, 'user', %s, NOW())
                    ON CONFLICT (bot_id, api_key)
                    DO UPDATE SET admin_id = EXCLUDED.admin_id,
                                  name = COALESCE(EXCLUDED.name, users.name),
                                  email = COALESCE(EXCLUDED.email, users.email),
                                  fcm_token = COALESCE(NULLIF(TRIM(EXCLUDED.fcm_token), ''), users.fcm_token),
                                  updated_at = NOW()
                    RETURNING id
                    """,
                    (admin_id, name or "", email_clean or None, bot_id, api_key, fcm),
                )
            row = cur.fetchone()
            conn.commit()
            if row:
                uid = row["id"] if hasattr(row, "keys") else row[0]
                new_user_id = str(uid) if isinstance(uid, uuid.UUID) else uid

                # After creating/updating new user, migrate data from old users (if email provided)
                if email_clean:
                    self._delete_other_users_by_admin_and_email(admin_id, email_clean, bot_id, api_key, migrate_to_user_id=new_user_id)

                return new_user_id
            return None
        except Exception as e:
            conn.rollback()
            # If email/fcm_token column doesn't exist yet, fallback to insert without them
            if "email" in str(e).lower() or "column" in str(e).lower() or "fcm_token" in str(e).lower():
                try:
                    cur.execute(
                        """
                        INSERT INTO users (admin_id, name, bot_id, api_key, role, updated_at)
                        VALUES (%s::uuid, %s, %s, %s, 'user', NOW())
                        ON CONFLICT (bot_id, api_key)
                        DO UPDATE SET admin_id = EXCLUDED.admin_id,
                                      name = COALESCE(EXCLUDED.name, users.name),
                                      updated_at = NOW()
                        RETURNING id
                        """,
                        (admin_id, name or "", bot_id, api_key),
                    )
                    row = cur.fetchone()
                    conn.commit()
                    if row:
                        uid = row["id"] if hasattr(row, "keys") else row[0]
                        return str(uid) if isinstance(uid, uuid.UUID) else uid
                except Exception as e2:
                    conn.rollback()
                    print(f"CentralDB upsert_user_from_bot fallback: {e2}")
            else:
                print(f"CentralDB upsert_user_from_bot: {e}")
            return None
        finally:
            cur.close()

    def get_user_by_admin_id(self, admin_id):
        """Return user row for this admin (for display)."""
        if not admin_id:
            return None
        try:
            uuid.UUID(str(admin_id))
        except (ValueError, TypeError):
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id, admin_id, name, bot_id, api_key, role FROM users WHERE admin_id = %s::uuid LIMIT 1",
                (admin_id,),
            )
            row = cur.fetchone()
            if row:
                if hasattr(row, "keys"):
                    return {
                        "id": str(row["id"]),
                        "admin_id": str(row["admin_id"]),
                        "name": row["name"],
                        "bot_id": row["bot_id"],
                        "api_key": row["api_key"],
                        "role": row["role"],
                    }
                return {
                    "id": str(row[0]),
                    "admin_id": str(row[1]),
                    "name": row[2],
                    "bot_id": row[3],
                    "api_key": row[4],
                    "role": row[5],
                }
            return None
        except Exception as e:
            print(f"CentralDB get_user_by_admin_id: {e}")
            return None
        finally:
            cur.close()

    def get_user_id_by_bot(self, bot_id, api_key):
        """Return user UUID (string) for this bot_id+api_key, or None."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id FROM users WHERE bot_id = %s AND api_key = %s LIMIT 1",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if row:
                uid = row["id"] if hasattr(row, "keys") else row[0]
                return str(uid) if isinstance(uid, uuid.UUID) else uid
            return None
        except Exception as e:
            print(f"CentralDB get_user_id_by_bot: {e}")
            return None
        finally:
            cur.close()

    def get_user_and_admin_bot_by_user_bot(self, bot_id, api_key):
        """Given a user's bot_id+api_key, return user_id, admin_id, user_name, and admin's bot_id/api_key for relaying alerts to admin. Returns dict or None."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """SELECT u.id AS user_id, u.admin_id, u.name AS user_name, a.bot_id AS admin_bot_id, a.api_key AS admin_api_key,
                          a.admin_access_code AS admin_access_code
                   FROM users u JOIN admins a ON a.id = u.admin_id
                   WHERE u.bot_id = %s AND u.api_key = %s AND u.bot_id != 'dashboard' LIMIT 1""",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if not row:
                return None
            if hasattr(row, "keys"):
                return {
                    "user_id": str(row["user_id"]),
                    "admin_id": str(row["admin_id"]),
                    "user_name": (row["user_name"] or "").strip() or "User",
                    "admin_bot_id": (row["admin_bot_id"] or "").strip(),
                    "admin_api_key": (row["admin_api_key"] or "").strip(),
                    "admin_access_code": str(row["admin_access_code"] or "").strip(),
                }
            return {
                "user_id": str(row[0]),
                "admin_id": str(row[1]),
                "user_name": (row[2] or "").strip() or "User",
                "admin_bot_id": (row[3] or "").strip(),
                "admin_api_key": (row[4] or "").strip(),
                "admin_access_code": (str(row[5]).strip() if len(row) > 5 and row[5] is not None else ""),
            }
        except Exception as e:
            print(f"CentralDB get_user_and_admin_bot_by_user_bot: {e}")
            return None
        finally:
            cur.close()

    def get_role_by_bot(self, bot_id, api_key):
        """Return 'admin' or 'user' and id for this bot_id+api_key. None if not found."""
        aid = self.get_admin_id_by_bot(bot_id, api_key)
        if aid:
            return "admin", aid
        uid = self.get_user_id_by_bot(bot_id, api_key)
        if uid:
            return "user", uid
        return None

    def get_role_by_access_code(self, access_code):
        """Return ('admin', admin_id) if access_code matches an admin; else None. For Android app."""
        info = self.get_admin_by_access_code(access_code)
        if info and info.get("id"):
            return "admin", info["id"]
        return None

    def get_dashboard_user_id(self, admin_id):
        """Return user_id for the admin's dashboard data (one user per admin with bot_id='dashboard').
        Creates that user if it does not exist. Used for syncing desktop medicine_boxes and for app GET /admin/data.
        """
        if not admin_id:
            return None
        try:
            uuid.UUID(str(admin_id))
        except (ValueError, TypeError):
            return None
        uid = self.get_user_id_by_bot("dashboard", str(admin_id))
        if uid:
            return uid
        return self.upsert_user_from_bot("dashboard", str(admin_id), admin_id, name="Admin dashboard")

    def get_admin_dashboard_data(self, admin_id, last_sync_time=None):
        """Return dashboard data for this admin. If last_sync_time (ISO) is set, return only data updated after that time (incremental).
        Always returns server_time for next poll. Used by Android GET /admin/data?access_code=...&last_sync_time=...
        """
        duid = self.get_dashboard_user_id(admin_id)
        if not duid:
            return None
        now = datetime.now(timezone.utc).isoformat()
        incremental = bool(last_sync_time and (last_sync_time or "").strip())
        if incremental:
            medicines = self.list_medicines(duid, since=last_sync_time)
            dose_logs = self.list_dose_logs(duid, from_=last_sync_time, limit=500)
            alert_settings = self.get_alert_settings_if_updated_since(duid, last_sync_time)
            alerts = self.list_alerts(admin_id=admin_id, since=last_sync_time, limit=200)
        else:
            medicines = self.list_medicines(duid)
            dose_logs = self.list_dose_logs(duid, limit=500)
            alert_settings = self.get_alert_settings(duid)
            alerts = self.list_alerts(admin_id=admin_id, limit=200)
        medical_reminders = (alert_settings or {}).get("medical_reminders") if alert_settings else None
        if medical_reminders is None and not incremental:
            medical_reminders = {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []}
        out = {
            "medicines": medicines,
            "dose_logs": dose_logs,
            "alert_settings": alert_settings,
            "alerts": alerts,
            "medical_reminders": medical_reminders,
            "server_time": now,
            "incremental": incremental,
        }
        if incremental:
            all_meds = self.list_medicines(duid)
            out["medicine_box_ids"] = [m.get("box_id") for m in all_meds if m.get("box_id")]
        return out

    def sync_admin_dashboard_data(self, admin_id, medicine_boxes, dose_log):
        """Sync desktop medicine_boxes and dose_log to Central DB for this admin.
        medicine_boxes: dict B1..B6 -> { name, quantity?, dose_per_day?, exact_time?, instructions?, ... }
        dose_log: list of { timestamp, box, medicine, dose_taken, remaining }.
        """
        duid = self.get_dashboard_user_id(admin_id)
        if not duid:
            return False
        existing_medicines = self.list_medicines(duid)
        by_box = {m.get("box_id"): m for m in existing_medicines if m.get("box_id")}

        for box_id in [f"B{i}" for i in range(1, 7)]:
            med = (medicine_boxes or {}).get(box_id) if isinstance(medicine_boxes, dict) else None
            if med and isinstance(med, dict):
                name = (med.get("name") or "").strip() or "Medicine"
                dosage = med.get("instructions") or str(med.get("dose_per_day") or "")
                exact_time = med.get("exact_time") or "08:00"
                times = [exact_time] if isinstance(exact_time, str) else (exact_time if isinstance(exact_time, (list, tuple)) else [])
                low_stock = 5
                if med.get("low_stock") is not None:
                    try:
                        low_stock = int(med.get("low_stock"))
                    except (TypeError, ValueError):
                        pass
                quantity = 0
                if med.get("quantity") is not None:
                    try:
                        quantity = int(med.get("quantity"))
                    except (TypeError, ValueError):
                        pass
                existing = by_box.get(box_id)
                if existing:
                    self.update_medicine(
                        existing.get("id"),
                        name=name,
                        box_id=box_id,
                        dosage=dosage,
                        times=times,
                        low_stock=low_stock,
                        quantity=quantity,
                    )
                else:
                    self.create_medicine(duid, name, box_id=box_id, dosage=dosage, times=times, low_stock=low_stock, quantity=quantity)
            else:
                existing = by_box.get(box_id)
                if existing:
                    self.delete_medicine(existing.get("id"))

        # Replace dose_logs for dashboard user: delete all then insert from dose_log
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("DELETE FROM dose_logs WHERE user_id = %s::uuid", (duid,))
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB sync_admin_dashboard_data delete dose_logs: {e}")
            cur.close()
            return True
        finally:
            cur.close()

        for entry in (dose_log or [])[:500]:
            if not isinstance(entry, dict):
                continue
            ts = entry.get("timestamp") or entry.get("taken_at")
            box = entry.get("box") or entry.get("box_id") or ""
            if ts:
                self.create_dose_log(duid, medicine_id=None, box_id=box, taken_at=ts, source="desktop")
        return True

    def sync_admin_dashboard_data_for_user(self, admin_id, user_id, medicine_boxes, dose_log):
        """Sync medicine_boxes and dose_log to a connected user (when admin 'acts as' that user)."""
        if not self.user_belongs_to_admin(user_id, admin_id):
            return False
        existing_medicines = self.list_medicines(user_id)
        by_box = {m.get("box_id"): m for m in existing_medicines if m.get("box_id")}

        for box_id in [f"B{i}" for i in range(1, 7)]:
            med = (medicine_boxes or {}).get(box_id) if isinstance(medicine_boxes, dict) else None
            if med and isinstance(med, dict):
                name = (med.get("name") or "").strip() or "Medicine"
                dosage = med.get("instructions") or str(med.get("dose_per_day") or "")
                exact_time = med.get("exact_time") or "08:00"
                times = [exact_time] if isinstance(exact_time, str) else (exact_time if isinstance(exact_time, (list, tuple)) else [])
                low_stock = 5
                if med.get("low_stock") is not None:
                    try:
                        low_stock = int(med.get("low_stock"))
                    except (TypeError, ValueError):
                        pass
                quantity = 0
                if med.get("quantity") is not None:
                    try:
                        quantity = int(med.get("quantity"))
                    except (TypeError, ValueError):
                        pass
                existing = by_box.get(box_id)
                if existing:
                    self.update_medicine(
                        existing.get("id"),
                        name=name,
                        box_id=box_id,
                        dosage=dosage,
                        times=times,
                        low_stock=low_stock,
                        quantity=quantity,
                    )
                else:
                    self.create_medicine(user_id, name, box_id=box_id, dosage=dosage, times=times, low_stock=low_stock, quantity=quantity)
            else:
                existing = by_box.get(box_id)
                if existing:
                    self.delete_medicine(existing.get("id"))

        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("DELETE FROM dose_logs WHERE user_id = %s::uuid", (user_id,))
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB sync_admin_dashboard_data_for_user delete dose_logs: {e}")
            cur.close()
            return True
        finally:
            cur.close()

        for entry in (dose_log or [])[:500]:
            if not isinstance(entry, dict):
                continue
            ts = entry.get("timestamp") or entry.get("taken_at")
            box = entry.get("box") or entry.get("box_id") or ""
            if ts:
                self.create_dose_log(user_id, medicine_id=None, box_id=box, taken_at=ts, source="desktop")
        return True

    def merge_user_system_settings_overlay(self, user_id, incoming):
        """Merge alert_settings + gmail_config from standalone app System screen (same gmail merge rules as admin PUT)."""
        if not user_id or not isinstance(incoming, dict):
            return True
        try:
            current = self.get_alert_settings(user_id) or {}
            if not isinstance(current, dict):
                current = {}
            incoming_alert = incoming.get("alert_settings")
            incoming_gmail = incoming.get("gmail_config")
            if isinstance(incoming_alert, dict):
                current["alert_settings"] = incoming_alert
            if isinstance(incoming_gmail, dict):
                prev_g = current.get("gmail_config")
                if isinstance(prev_g, dict):
                    merged_g = dict(prev_g)
                    for k, v in incoming_gmail.items():
                        if k in ("sender_email", "sender_password") and isinstance(v, str) and not v.strip():
                            continue
                        merged_g[k] = v
                    current["gmail_config"] = merged_g
                else:
                    current["gmail_config"] = dict(incoming_gmail)
            # App unlock PIN (user device): echoed for linked-user Care mode only; set/cleared via standalone-sync.
            if "care_app_unlock_pin" in incoming:
                pin = incoming.get("care_app_unlock_pin")
                if isinstance(pin, str):
                    p = pin.strip()
                    if p:
                        current["care_app_unlock_pin"] = p
                    else:
                        current.pop("care_app_unlock_pin", None)
            if "care_device_password" in incoming:
                dp = incoming.get("care_device_password")
                if isinstance(dp, str):
                    p = dp.strip()
                    if p:
                        current["care_device_password"] = p
                    else:
                        current.pop("care_device_password", None)
            return bool(self.upsert_alert_settings(user_id, current))
        except Exception as e:
            print(f"CentralDB merge_user_system_settings_overlay: {e}")
            return False

    def merge_user_standalone_sync_from_app(
        self,
        admin_id,
        user_id,
        medicines,
        dose_append,
        medical_reminders,
        client_ms,
        system_settings=None,
    ):
        """Merge user-app standalone changes: upsert medicines by box, append dose rows (deduped), merge medical_reminders.

        Latest client payload wins for supplied medicine boxes and reminder categories (timestamp via client_ms for future use).
        Optional system_settings: { alert_settings, gmail_config } from System tab save.
        """
        if not self.user_belongs_to_admin(user_id, admin_id):
            return False
        _ = client_ms  # reserved for conflict rules / audit
        try:
            if isinstance(medicines, list) and medicines:
                existing_medicines = self.list_medicines(user_id)
                by_box = {m.get("box_id"): m for m in existing_medicines if m.get("box_id")}
                for m in medicines:
                    if not isinstance(m, dict):
                        continue
                    box_id = (m.get("box_id") or "").strip().upper()
                    if not box_id.startswith("B"):
                        continue
                    name = (m.get("name") or "").strip() or "Medicine"
                    dosage = (m.get("instructions") or m.get("dosage") or str(m.get("dose_per_day") or "")).strip()
                    exact_time = (m.get("exact_time") or "08:00").strip() or "08:00"
                    times = m.get("times")
                    if isinstance(times, list) and times:
                        tlist = [str(x).strip() for x in times if str(x).strip()]
                    else:
                        tlist = [exact_time]
                    low_stock = 5
                    if m.get("low_stock") is not None:
                        try:
                            low_stock = int(m.get("low_stock"))
                        except (TypeError, ValueError):
                            pass
                    quantity = 0
                    if m.get("quantity") is not None:
                        try:
                            quantity = int(m.get("quantity"))
                        except (TypeError, ValueError):
                            pass
                    existing = by_box.get(box_id)
                    if existing:
                        self.update_medicine(
                            existing.get("id"),
                            name=name,
                            box_id=box_id,
                            dosage=dosage,
                            times=tlist,
                            low_stock=low_stock,
                            quantity=quantity,
                        )
                    else:
                        self.create_medicine(
                            user_id,
                            name,
                            box_id=box_id,
                            dosage=dosage,
                            times=tlist,
                            low_stock=low_stock,
                            quantity=quantity,
                        )
                        existing_medicines = self.list_medicines(user_id)
                        by_box = {x.get("box_id"): x for x in existing_medicines if x.get("box_id")}

            if isinstance(dose_append, list) and dose_append:
                recent = self.list_dose_logs(user_id, limit=400)
                seen = set()
                for r in recent:
                    bid = (r.get("box_id") or "").strip()
                    ts = str(r.get("taken_at") or "")
                    seen.add(f"{bid}|{ts[:19]}")
                for entry in dose_append[:200]:
                    if not isinstance(entry, dict):
                        continue
                    ts = entry.get("timestamp") or entry.get("taken_at") or ""
                    box = (entry.get("box") or entry.get("box_id") or "").strip()
                    if not ts or not box:
                        continue
                    key = f"{box}|{str(ts)[:19]}"
                    if key in seen:
                        continue
                    self.create_dose_log(user_id, medicine_id=None, box_id=box, taken_at=ts, source="app")
                    seen.add(key)

            if isinstance(medical_reminders, dict) and medical_reminders:
                settings = self.get_alert_settings(user_id) or {}
                if not isinstance(settings, dict):
                    settings = {}
                old_mr = settings.get("medical_reminders")
                merged_mr = dict(old_mr) if isinstance(old_mr, dict) else {}
                for cat in ("appointments", "prescriptions", "lab_tests", "custom"):
                    if cat in medical_reminders and isinstance(medical_reminders.get(cat), list):
                        merged_mr[cat] = medical_reminders[cat]
                settings["medical_reminders"] = merged_mr
                self.upsert_alert_settings(user_id, settings)
            if isinstance(system_settings, dict) and system_settings:
                if not self.merge_user_system_settings_overlay(user_id, system_settings):
                    return False
            return True
        except Exception as e:
            print(f"CentralDB merge_user_standalone_sync_from_app: {e}")
            return False

    # ---- Medicines, dose_logs, alert_settings, alerts, get_sync (abbreviated for length - same as pyqt) ----
    def list_medicines(self, user_id, since=None):
        if not user_id:
            return []
        try:
            uuid.UUID(str(user_id))
        except (ValueError, TypeError):
            return []
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        use_quantity = True
        try:
            cols = "id, user_id, name, box_id, dosage, times, low_stock, quantity"
            if since:
                cur.execute(
                    f"""SELECT {cols} FROM medicines
                       WHERE user_id = %s::uuid AND (updated_at > %s::timestamptz OR created_at > %s::timestamptz)
                       ORDER BY created_at""",
                    (user_id, since, since),
                )
            else:
                cur.execute(
                    f"SELECT {cols} FROM medicines WHERE user_id = %s::uuid ORDER BY created_at",
                    (user_id,),
                )
            rows = cur.fetchall()
        except Exception:
            use_quantity = False
            try:
                cols = "id, user_id, name, box_id, dosage, times, low_stock"
                if since:
                    cur.execute(
                        f"""SELECT {cols} FROM medicines
                           WHERE user_id = %s::uuid AND (updated_at > %s::timestamptz OR created_at > %s::timestamptz)
                           ORDER BY created_at""",
                        (user_id, since, since),
                    )
                else:
                    cur.execute(
                        f"SELECT {cols} FROM medicines WHERE user_id = %s::uuid ORDER BY created_at",
                        (user_id,),
                    )
                rows = cur.fetchall()
            except Exception as e:
                print(f"CentralDB list_medicines: {e}")
                cur.close()
                return []
        try:
            out = []
            for row in rows:
                r = row if hasattr(row, "keys") else None
                if r:
                    times = r["times"]
                    if isinstance(times, str):
                        try:
                            times = json.loads(times)
                        except Exception:
                            times = []
                    qty = 0
                    if use_quantity:
                        qty = r.get("quantity", 0)
                        if qty is None:
                            qty = 0
                        try:
                            qty = int(qty)
                        except (TypeError, ValueError):
                            qty = 0
                    out.append({
                        "id": str(r["id"]), "user_id": str(r["user_id"]), "name": r["name"],
                        "box_id": r["box_id"], "dosage": r["dosage"], "times": times, "low_stock": r["low_stock"],
                        "quantity": qty,
                    })
                else:
                    qty = int(row[7]) if len(row) > 7 else 0
                    out.append({
                        "id": str(row[0]), "user_id": str(row[1]), "name": row[2], "box_id": row[3],
                        "dosage": row[4], "times": json.loads(row[5]) if isinstance(row[5], str) else (row[5] or []), "low_stock": row[6],
                        "quantity": qty,
                    })
            return out
        except Exception as e:
            print(f"CentralDB list_medicines: {e}")
            return []
        finally:
            cur.close()

    def create_medicine(self, user_id, name, box_id=None, dosage=None, times=None, low_stock=5, quantity=0):
        if not user_id or not name:
            return None
        times = times if isinstance(times, (list, tuple)) else []
        times_json = json.dumps(times)
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """INSERT INTO medicines (user_id, name, box_id, dosage, times, low_stock, quantity)
                   VALUES (%s::uuid, %s, %s, %s, %s::jsonb, %s, %s)
                   RETURNING id""",
                (user_id, name, box_id or "", dosage or "", times_json, low_stock, quantity),
            )
            row = cur.fetchone()
            conn.commit()
            if row:
                mid = row["id"] if hasattr(row, "keys") else row[0]
                return str(mid) if isinstance(mid, uuid.UUID) else mid
            return None
        except Exception as e:
            conn.rollback()
            try:
                cur.execute(
                    """INSERT INTO medicines (user_id, name, box_id, dosage, times, low_stock)
                       VALUES (%s::uuid, %s, %s, %s, %s::jsonb, %s)
                       RETURNING id""",
                    (user_id, name, box_id or "", dosage or "", times_json, low_stock),
                )
                row = cur.fetchone()
                conn.commit()
                if row:
                    mid = row["id"] if hasattr(row, "keys") else row[0]
                    return str(mid) if isinstance(mid, uuid.UUID) else mid
            except Exception as e2:
                conn.rollback()
                print(f"CentralDB create_medicine: {e2}")
            return None
        finally:
            cur.close()

    def get_user_id_by_medicine_id(self, medicine_id):
        """Return user_id for the given medicine_id, or None. Used for event-based alert triggers."""
        if not medicine_id:
            return None
        try:
            uuid.UUID(str(medicine_id))
        except (ValueError, TypeError):
            return None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT user_id FROM medicines WHERE id = %s::uuid LIMIT 1", (medicine_id,))
            row = cur.fetchone()
            if row:
                uid = row[0]
                return str(uid) if isinstance(uid, uuid.UUID) else uid
            return None
        except Exception as e:
            print(f"CentralDB get_user_id_by_medicine_id: {e}")
            return None
        finally:
            cur.close()

    def update_medicine(self, medicine_id, name=None, box_id=None, dosage=None, times=None, low_stock=None, quantity=None):
        if not medicine_id:
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            updates = []
            params = []
            if name is not None:
                updates.append("name = %s")
                params.append(name)
            if box_id is not None:
                updates.append("box_id = %s")
                params.append(box_id)
            if dosage is not None:
                updates.append("dosage = %s")
                params.append(dosage)
            if times is not None:
                updates.append("times = %s::jsonb")
                params.append(json.dumps(times))
            if low_stock is not None:
                updates.append("low_stock = %s")
                params.append(low_stock)
            if quantity is not None:
                updates.append("quantity = %s")
                params.append(quantity)
            if not updates:
                return True
            updates.append("updated_at = NOW()")
            params.append(medicine_id)
            cur.execute(
                f"UPDATE medicines SET {', '.join(updates)} WHERE id = %s::uuid",
                params,
            )
            conn.commit()
            return cur.rowcount > 0
        except Exception as e:
            conn.rollback()
            print(f"CentralDB update_medicine: {e}")
            return False
        finally:
            cur.close()

    def delete_medicine(self, medicine_id):
        if not medicine_id:
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("DELETE FROM medicines WHERE id = %s::uuid", (medicine_id,))
            conn.commit()
            return cur.rowcount > 0
        except Exception as e:
            conn.rollback()
            print(f"CentralDB delete_medicine: {e}")
            return False
        finally:
            cur.close()

    def create_dose_log(self, user_id, medicine_id=None, box_id=None, taken_at=None, source="desktop"):
        if not user_id:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """INSERT INTO dose_logs (user_id, medicine_id, box_id, taken_at, source)
                   VALUES (%s::uuid, %s::uuid, %s, COALESCE(%s::timestamptz, NOW()), %s)
                   RETURNING id""",
                (user_id, medicine_id or None, box_id or "", taken_at, source),
            )
            row = cur.fetchone()
            conn.commit()
            if row:
                return str(row["id"]) if hasattr(row, "keys") else str(row[0])
            return None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB create_dose_log: {e}")
            return None
        finally:
            cur.close()

    def list_dose_logs(self, user_id, from_=None, to=None, limit=500):
        if not user_id:
            return []
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            q = "SELECT id, user_id, medicine_id, box_id, taken_at, source FROM dose_logs WHERE user_id = %s::uuid"
            params = [user_id]
            if from_:
                q += " AND taken_at > %s::timestamptz"
                params.append(from_)
            if to:
                q += " AND taken_at <= %s::timestamptz"
                params.append(to)
            q += " ORDER BY taken_at DESC LIMIT %s"
            params.append(limit)
            cur.execute(q, params)
            rows = cur.fetchall()
            out = []
            for row in rows:
                r = row if hasattr(row, "keys") else None
                if r:
                    out.append({
                        "id": str(r["id"]), "user_id": str(r["user_id"]),
                        "medicine_id": str(r["medicine_id"]) if r["medicine_id"] else None,
                        "box_id": r["box_id"],
                        "taken_at": r["taken_at"].isoformat() if hasattr(r["taken_at"], "isoformat") else str(r["taken_at"]),
                        "source": r["source"],
                    })
                else:
                    out.append({
                        "id": str(row[0]), "user_id": str(row[1]),
                        "medicine_id": str(row[2]) if row[2] else None, "box_id": row[3],
                        "taken_at": row[4].isoformat() if hasattr(row[4], "isoformat") else str(row[4]),
                        "source": row[5],
                    })
            return out
        except Exception as e:
            print(f"CentralDB list_dose_logs: {e}")
            return []
        finally:
            cur.close()

    def clear_dose_logs_for_linked_user(self, admin_id, user_id):
        """Delete all dose log rows for a user if they belong to this admin."""
        if not user_id or not admin_id:
            return False
        if not self.user_belongs_to_admin(user_id, admin_id):
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("DELETE FROM dose_logs WHERE user_id = %s::uuid", (user_id,))
            conn.commit()
            return True
        except Exception as e:
            conn.rollback()
            print(f"CentralDB clear_dose_logs_for_linked_user: {e}")
            return False
        finally:
            cur.close()

    def get_alert_settings(self, user_id):
        if not user_id:
            return {}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute("SELECT settings FROM alert_settings WHERE user_id = %s::uuid LIMIT 1", (user_id,))
            row = cur.fetchone()
            if row:
                s = row["settings"] if hasattr(row, "keys") else row[0]
                if isinstance(s, dict):
                    return s
                if isinstance(s, str):
                    return json.loads(s) if s else {}
                return {}
            return {}
        except Exception as e:
            print(f"CentralDB get_alert_settings: {e}")
            return {}
        finally:
            cur.close()

    def get_alert_settings_if_updated_since(self, user_id, since):
        """Return alert_settings only if row updated_at > since; else None (so client keeps previous)."""
        if not user_id or not since:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT settings FROM alert_settings WHERE user_id = %s::uuid AND updated_at > %s::timestamptz LIMIT 1",
                (user_id, since),
            )
            row = cur.fetchone()
            if row:
                s = row["settings"] if hasattr(row, "keys") else row[0]
                if isinstance(s, dict):
                    return s
                if isinstance(s, str):
                    return json.loads(s) if s else {}
            return None
        except Exception as e:
            print(f"CentralDB get_alert_settings_if_updated_since: {e}")
            return None
        finally:
            cur.close()

    def upsert_alert_settings(self, user_id, settings):
        if not user_id:
            return False
        s = json.dumps(settings) if isinstance(settings, dict) else "{}"
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """INSERT INTO alert_settings (user_id, settings, updated_at)
                   VALUES (%s::uuid, %s::jsonb, NOW())
                   ON CONFLICT (user_id) DO UPDATE SET settings = EXCLUDED.settings, updated_at = NOW()""",
                (user_id, s),
            )
            conn.commit()
            return True
        except Exception as e:
            conn.rollback()
            print(f"CentralDB upsert_alert_settings: {e}")
            return False
        finally:
            cur.close()

    def create_alert(self, user_id, admin_id, type_, message):
        if not user_id or not admin_id:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """INSERT INTO alerts (user_id, admin_id, type, message, status)
                   VALUES (%s::uuid, %s::uuid, %s, %s, 'pending')
                   RETURNING id""",
                (user_id, admin_id, type_, message),
            )
            row = cur.fetchone()
            conn.commit()
            if row:
                return str(row["id"]) if hasattr(row, "keys") else str(row[0])
            return None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB create_alert: {e}")
            return None
        finally:
            cur.close()

    def list_alerts(self, user_id=None, admin_id=None, status=None, since=None, limit=100):
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            q = """SELECT a.id, a.user_id, a.admin_id, a.type, a.message, a.status, a.created_at,
                    COALESCE(u.name, '') AS user_name
                    FROM alerts a
                    LEFT JOIN users u ON u.id = a.user_id
                    WHERE 1=1"""
            params = []
            if user_id:
                q += " AND a.user_id = %s::uuid"
                params.append(user_id)
            if admin_id:
                q += " AND a.admin_id = %s::uuid"
                params.append(admin_id)
            if status:
                q += " AND a.status = %s"
                params.append(status)
            if since:
                q += " AND a.created_at > %s::timestamptz"
                params.append(since)
            q += " ORDER BY a.created_at DESC LIMIT %s"
            params.append(limit)
            cur.execute(q, params)
            rows = cur.fetchall()
            out = []
            for row in rows:
                r = row if hasattr(row, "keys") else row
                if hasattr(r, "keys"):
                    out.append({
                        "id": str(r["id"]), "user_id": str(r["user_id"]), "admin_id": str(r["admin_id"]),
                        "type": r["type"], "message": r["message"], "status": r["status"],
                        "created_at": r["created_at"].isoformat() if hasattr(r["created_at"], "isoformat") else str(r["created_at"]),
                        "user_name": (r.get("user_name") or "").strip() or "User",
                    })
                else:
                    user_name = (row[7] if len(row) > 7 else "") or "User"
                    out.append({
                        "id": str(row[0]), "user_id": str(row[1]), "admin_id": str(row[2]),
                        "type": row[3], "message": row[4], "status": row[5],
                        "created_at": row[6].isoformat() if hasattr(row[6], "isoformat") else str(row[6]),
                        "user_name": str(user_name).strip() or "User",
                    })
            return out
        except Exception as e:
            print(f"CentralDB list_alerts: {e}")
            return []
        finally:
            cur.close()

    def list_user_plans_by_bot(self, bot_id, api_key):
        """List plans from users.health_hub_plans JSON array. None = unknown user, [] = empty or error."""
        info = self.get_user_and_admin_bot_by_user_bot((bot_id or "").strip(), (api_key or "").strip())
        if not info:
            return None
        uid = str(info["user_id"])
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT health_hub_plans FROM users WHERE id = %s::uuid LIMIT 1",
                (uid,),
            )
            row = cur.fetchone()
            if not row:
                return []
            raw = row["health_hub_plans"] if hasattr(row, "keys") else row[0]
            if raw is None:
                return []
            if isinstance(raw, (bytes, bytearray)):
                raw = raw.decode("utf-8", errors="replace")
            if isinstance(raw, str):
                arr = json.loads(raw) if raw.strip() else []
            elif isinstance(raw, list):
                arr = raw
            else:
                arr = json.loads(str(raw)) if str(raw).strip() else []
            if not isinstance(arr, list):
                return []
            out = []
            for item in arr:
                if not isinstance(item, dict):
                    continue
                pt = item.get("plan_time") or ""
                st = (item.get("status") or "pending").strip().lower()
                if st not in ("pending", "done"):
                    st = "pending"
                out.append({
                    "id": str(item.get("id") or ""),
                    "title": (item.get("title") or "").strip(),
                    "notes": (item.get("notes") or "").strip(),
                    "plan_date": (item.get("plan_date") or "").strip(),
                    "plan_time": str(pt).strip()[:16],
                    "activity_type": (item.get("activity_type") or "other").strip(),
                    "created_at": (item.get("created_at") or "").strip(),
                    "status": st,
                    "health_type": (item.get("health_type") or "general").strip()[:40] or "general",
                    "completed_at": (item.get("completed_at") or "").strip(),
                })
            out.sort(key=lambda p: (p.get("plan_date") or "", p.get("created_at") or ""), reverse=True)
            return out[:300]
        except Exception as e:
            print(f"CentralDB list_user_plans_by_bot: {e}")
            return []
        finally:
            cur.close()

    def create_user_plan_by_bot(self, bot_id, api_key, title, notes, plan_date, plan_time, activity_type, health_type="general"):
        """
        Append one plan object to users.health_hub_plans (JSON array).
        plan_date must be YYYY-MM-DD. plan_time optional (stored as string).
        Returns (plan_id_str, None) or (None, error_message).
        """
        info = self.get_user_and_admin_bot_by_user_bot((bot_id or "").strip(), (api_key or "").strip())
        if not info:
            return None, "user_not_found"
        uid = str(info["user_id"])
        title = (title or "").strip()[:255]
        notes_s = (notes or "").strip()
        plan_date = (plan_date or "").strip()
        if len(plan_date) != 10 or plan_date[4] != "-" or plan_date[7] != "-":
            return None, "invalid_plan_date"
        plan_time = (plan_time or "").strip()[:32]
        act = (activity_type or "other").strip()[:80] or "other"
        ht = (health_type or "general").strip()[:40] or "general"
        plan_id = str(uuid.uuid4())
        created_at = datetime.now(timezone.utc).isoformat()
        new_obj = {
            "id": plan_id,
            "title": title,
            "notes": notes_s,
            "plan_date": plan_date,
            "plan_time": plan_time,
            "activity_type": act,
            "created_at": created_at,
            "status": "pending",
            "health_type": ht,
            "completed_at": "",
        }
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT health_hub_plans FROM users WHERE id = %s::uuid FOR UPDATE",
                (uid,),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return None, "user_not_found"
            raw = row["health_hub_plans"] if hasattr(row, "keys") else row[0]
            if raw is None:
                arr = []
            elif isinstance(raw, str):
                arr = json.loads(raw) if raw.strip() else []
            elif isinstance(raw, list):
                arr = list(raw)
            else:
                arr = json.loads(str(raw)) if str(raw).strip() else []
            if not isinstance(arr, list):
                arr = []
            arr.insert(0, new_obj)
            arr = arr[:400]
            cur.execute(
                "UPDATE users SET health_hub_plans = %s::jsonb, updated_at = NOW() WHERE id = %s::uuid",
                (json.dumps(arr), uid),
            )
            conn.commit()
            return plan_id, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB create_user_plan_by_bot: {e}")
            return None, "db_error"
        finally:
            cur.close()

    def update_user_plan_by_bot(self, bot_id, api_key, plan_id, status=None):
        """Set plan status to pending|done. Returns (True, None) or (False, error)."""
        pid = (plan_id or "").strip()
        if not pid:
            return False, "plan_id_required"
        st = (status or "").strip().lower()
        if st not in ("pending", "done"):
            return False, "invalid_status"
        info = self.get_user_and_admin_bot_by_user_bot((bot_id or "").strip(), (api_key or "").strip())
        if not info:
            return False, "user_not_found"
        uid = str(info["user_id"])
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT health_hub_plans FROM users WHERE id = %s::uuid FOR UPDATE",
                (uid,),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return False, "user_not_found"
            raw = row["health_hub_plans"] if hasattr(row, "keys") else row[0]
            arr = self._decode_health_hub_plans_raw(raw)
            found = False
            for item in arr:
                if not isinstance(item, dict):
                    continue
                if str(item.get("id") or "").strip() == pid:
                    item["status"] = st
                    item["completed_at"] = (
                        datetime.now(timezone.utc).isoformat() if st == "done" else ""
                    )
                    found = True
                    break
            if not found:
                conn.rollback()
                return False, "plan_not_found"
            cur.execute(
                "UPDATE users SET health_hub_plans = %s::jsonb, updated_at = NOW() WHERE id = %s::uuid",
                (json.dumps(arr), uid),
            )
            conn.commit()
            return True, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB update_user_plan_by_bot: {e}")
            return False, "db_error"
        finally:
            cur.close()

    def delete_user_plan_by_bot(self, bot_id, api_key, plan_id):
        """Remove plan by id from health_hub_plans. Returns (True, None) or (False, error)."""
        pid = (plan_id or "").strip()
        if not pid:
            return False, "plan_id_required"
        info = self.get_user_and_admin_bot_by_user_bot((bot_id or "").strip(), (api_key or "").strip())
        if not info:
            return False, "user_not_found"
        uid = str(info["user_id"])
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT health_hub_plans FROM users WHERE id = %s::uuid FOR UPDATE",
                (uid,),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return False, "user_not_found"
            raw = row["health_hub_plans"] if hasattr(row, "keys") else row[0]
            arr = self._decode_health_hub_plans_raw(raw)
            new_arr = [x for x in arr if not (isinstance(x, dict) and str(x.get("id") or "").strip() == pid)]
            if len(new_arr) == len(arr):
                conn.rollback()
                return False, "plan_not_found"
            cur.execute(
                "UPDATE users SET health_hub_plans = %s::jsonb, updated_at = NOW() WHERE id = %s::uuid",
                (json.dumps(new_arr), uid),
            )
            conn.commit()
            return True, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB delete_user_plan_by_bot: {e}")
            return False, "db_error"
        finally:
            cur.close()

    @staticmethod
    def _decode_health_hub_plans_raw(raw):
        if raw is None:
            return []
        if isinstance(raw, (bytes, bytearray)):
            raw = raw.decode("utf-8", errors="replace")
        if isinstance(raw, str):
            arr = json.loads(raw) if raw.strip() else []
        elif isinstance(raw, list):
            arr = list(raw)
        else:
            arr = json.loads(str(raw)) if str(raw).strip() else []
        return arr if isinstance(arr, list) else []

    def get_sync(self, bot_id, api_key):
        user_id = self.get_user_id_by_bot(bot_id, api_key)
        if not user_id:
            return None
        medicines = self.list_medicines(user_id)
        settings = self.get_alert_settings(user_id)
        alerts = self.list_alerts(user_id=user_id, status="pending", limit=50)
        now = datetime.now(timezone.utc).isoformat()
        return {
            "medicines": medicines,
            "alert_settings": settings,
            "alerts": alerts,
            "server_time": now,
        }

    def get_all_users_by_admin_id(self, admin_id):
        """Return all real users linked to this admin (excludes the dashboard system user)."""
        if not admin_id:
            return []
        try:
            uuid.UUID(str(admin_id))
        except (ValueError, TypeError):
            return []
        try:
            self._ensure_users_profile_picture_column()
            self._ensure_users_display_mode_column()
            self._ensure_users_first_last_name_columns()
        except Exception:
            pass
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            rows = None
            variant = 0
            # 0=email+profile_picture+user_display_mode | 1=email no pp | 2=no email column
            for variant, sql in enumerate((
                "SELECT id, name, email, bot_id, api_key, created_at, profile_picture, COALESCE(user_display_mode, '') AS user_display_mode FROM users WHERE admin_id = %s::uuid AND bot_id != 'dashboard' ORDER BY created_at DESC",
                "SELECT id, name, email, bot_id, api_key, created_at FROM users WHERE admin_id = %s::uuid AND bot_id != 'dashboard' ORDER BY created_at DESC",
                "SELECT id, name, bot_id, api_key, created_at FROM users WHERE admin_id = %s::uuid AND bot_id != 'dashboard' ORDER BY created_at DESC",
            )):
                try:
                    cur.execute(sql, (admin_id,))
                    rows = cur.fetchall()
                    break
                except Exception:
                    continue
            if rows is None:
                return []
            result = []
            for row in rows:
                if hasattr(row, "keys"):
                    em = (row.get("email") or "").strip() if variant in (0, 1) else ""
                    pp = (row.get("profile_picture") or "").strip() if variant == 0 else ""
                    dm = (row.get("user_display_mode") or "").strip().lower() if variant == 0 else ""
                    result.append({
                        "id": str(row["id"]), "name": row["name"] or "", "email": em,
                        "bot_id": row["bot_id"] or "", "api_key": row["api_key"] or "",
                        "desktop_linked": False,
                        "profile_picture": pp,
                        "user_display_mode": dm if dm in ("standalone", "default") else "",
                    })
                else:
                    if variant == 0:
                        em = (row[2] or "").strip() if len(row) > 2 else ""
                        pp = (row[6] or "").strip() if len(row) > 6 else ""
                        raw_dm = (row[7] or "").strip().lower() if len(row) > 7 else ""
                        dm = raw_dm if raw_dm in ("standalone", "default") else ""
                        result.append({
                            "id": str(row[0]), "name": row[1] or "", "email": em,
                            "bot_id": row[3] or "", "api_key": row[4] or "",
                            "desktop_linked": False,
                            "profile_picture": pp,
                            "user_display_mode": dm,
                        })
                    elif variant == 1:
                        em = (row[2] or "").strip() if len(row) > 2 else ""
                        result.append({
                            "id": str(row[0]), "name": row[1] or "", "email": em,
                            "bot_id": row[3] or "", "api_key": row[4] or "",
                            "desktop_linked": False,
                            "profile_picture": "",
                            "user_display_mode": "",
                        })
                    else:
                        result.append({
                            "id": str(row[0]), "name": row[1] or "", "email": "",
                            "bot_id": row[2] or "", "api_key": row[3] or "",
                            "desktop_linked": False,
                            "profile_picture": "",
                            "user_display_mode": "",
                        })
            for item in result:
                uid = (item.get("id") or "").strip()
                if not uid:
                    continue
                disp = (self.get_user_full_display_name_for_user_id(uid) or "").strip()
                base = (item.get("name") or "").strip()
                em = (item.get("email") or "").strip()
                if disp:
                    item["name"] = disp
                elif base:
                    item["name"] = base
                elif em and "@" in em:
                    item["name"] = em.split("@", 1)[0].strip()
                else:
                    item["name"] = base
            return result
        except Exception as e:
            print(f"CentralDB get_all_users_by_admin_id: {e}")
            return []
        finally:
            cur.close()

    def user_has_desktop_linked(self, user_id):
        """Legacy gate removed (desktop_linked_at column dropped); always allow for real ids."""
        if not user_id:
            return False
        return True

    def user_belongs_to_admin(self, user_id, admin_id):
        """True if user_id is a non-dashboard user under this admin_id."""
        if not user_id or not admin_id:
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "SELECT 1 FROM users WHERE id = %s::uuid AND admin_id = %s::uuid AND bot_id != 'dashboard' LIMIT 1",
                (user_id, admin_id),
            )
            return cur.fetchone() is not None
        except Exception:
            return False
        finally:
            cur.close()

    def get_deleted_user_notification(self, bot_id, api_key):
        """If this (bot_id, api_key) was deleted by admin, return dict with user_name and message; else None."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT user_name FROM deleted_user_notifications WHERE bot_id = %s AND api_key = %s LIMIT 1",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if not row:
                return None
            name = row["user_name"] if hasattr(row, "keys") else (row[0] if row else None)
            return {"user_name": name or "User", "message": "The admin has removed you from their account."}
        except Exception as e:
            print(f"CentralDB get_deleted_user_notification: {e}")
            return None
        finally:
            cur.close()

    def delete_user_by_admin(self, admin_id, user_id):
        """Admin deletes a connected user. User cannot delete themselves; only admin can.
        Records (bot_id, api_key) in deleted_user_notifications so get-role returns 410 for that user.
        Returns (True, user_name) on success, (False, None) otherwise."""
        if not self.user_belongs_to_admin(user_id, admin_id):
            return False, None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT bot_id, api_key, name FROM users WHERE id = %s::uuid LIMIT 1",
                (user_id,),
            )
            row = cur.fetchone()
            if not row:
                return False, None
            bot_id = row["bot_id"] if hasattr(row, "keys") else row[0]
            api_key = row["api_key"] if hasattr(row, "keys") else row[1]
            user_name = (row["name"] or "User") if hasattr(row, "keys") else (row[2] if len(row) > 2 else "User")
            cur.execute(
                "INSERT INTO deleted_user_notifications (bot_id, api_key, user_name) VALUES (%s, %s, %s) ON CONFLICT (bot_id, api_key) DO UPDATE SET user_name = EXCLUDED.user_name, deleted_at = NOW()",
                (bot_id, api_key, user_name),
            )
            cur.execute("DELETE FROM users WHERE id = %s::uuid", (user_id,))
            conn.commit()
            return True, user_name
        except Exception as e:
            conn.rollback()
            print(f"CentralDB delete_user_by_admin: {e}")
            return False, None
        finally:
            cur.close()

    def get_user_data_for_admin(self, admin_id, user_id, last_sync_time=None):
        """Return same shape as get_admin_dashboard_data but for the given user_id. Use when admin 'acts as' a connected user."""
        if not self.user_belongs_to_admin(user_id, admin_id):
            return None
        now = datetime.now(timezone.utc).isoformat()
        incremental = bool(last_sync_time and (last_sync_time or "").strip())
        if incremental:
            medicines = self.list_medicines(user_id, since=last_sync_time)
            dose_logs = self.list_dose_logs(user_id, from_=last_sync_time, limit=500)
            alert_settings = self.get_alert_settings_if_updated_since(user_id, last_sync_time)
            alerts = self.list_alerts(admin_id=admin_id, user_id=user_id, since=last_sync_time, limit=200)
        else:
            medicines = self.list_medicines(user_id)
            dose_logs = self.list_dose_logs(user_id, limit=500)
            alert_settings = self.get_alert_settings(user_id)
            alerts = self.list_alerts(admin_id=admin_id, user_id=user_id, limit=200)
        medical_reminders = (alert_settings or {}).get("medical_reminders") if alert_settings else None
        if medical_reminders is None and not incremental:
            medical_reminders = {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []}
        out = {
            "medicines": medicines,
            "dose_logs": dose_logs,
            "alert_settings": alert_settings,
            "alerts": alerts,
            "medical_reminders": medical_reminders,
            "server_time": now,
            "incremental": incremental,
        }
        if incremental:
            all_meds = self.list_medicines(user_id)
            out["medicine_box_ids"] = [m.get("box_id") for m in all_meds if m.get("box_id")]
        try:
            dm = self.get_user_display_mode_for_user_id(user_id)
            out["user_display_mode"] = dm or ""
        except Exception:
            out["user_display_mode"] = ""
        try:
            greet = self.get_user_first_name_for_user_id(user_id)
            if greet:
                out["user_first_name"] = greet
        except Exception:
            pass
        try:
            full = self.get_user_full_display_name_for_user_id(user_id)
            if full:
                out["user_full_name"] = full
        except Exception:
            pass
        try:
            un = self.get_user_username_for_user_id(user_id)
            if un:
                out["user_username"] = un
        except Exception:
            pass
        try:
            pp = self.get_user_profile_picture_for_user_id(user_id)
            if pp:
                out["profile_picture"] = pp
        except Exception:
            pass
        return out

    def get_user_first_name_for_user_id(self, user_id):
        """users.first_name (preferred), else first token of username or name — title-cased for UI."""
        uid = str(user_id or "").strip()
        if not uid:
            return ""
        self._ensure_users_first_last_name_columns()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            try:
                cur.execute(
                    "SELECT TRIM(COALESCE(first_name, '')), TRIM(COALESCE(username, '')), TRIM(COALESCE(name, '')) FROM users WHERE id = %s::uuid LIMIT 1",
                    (uid,),
                )
            except Exception:
                cur.execute(
                    "SELECT TRIM(COALESCE(username, '')), TRIM(COALESCE(name, '')) FROM users WHERE id = %s::uuid LIMIT 1",
                    (uid,),
                )
                row = cur.fetchone()
                if not row:
                    return ""
                uname = (row[0] or "").strip() if row else ""
                name = (row[1] or "").strip() if row and len(row) > 1 else ""
                raw = uname if uname else name
                return self._first_name_greeting_token(raw)
            row = cur.fetchone()
            if not row:
                return ""
            stored_fn = (row[0] or "").strip() if row else ""
            uname = (row[1] or "").strip() if row and len(row) > 1 else ""
            name = (row[2] or "").strip() if row and len(row) > 2 else ""
            raw = stored_fn if stored_fn else (uname if uname else name)
            return self._first_name_greeting_token(raw)
        except Exception as e:
            print(f"CentralDB get_user_first_name_for_user_id: {e}")
            return ""
        finally:
            cur.close()

    def get_user_full_display_name_for_user_id(self, user_id):
        """Full name for UI: first_name + last_name when both set, else name, else username."""
        uid = str(user_id or "").strip()
        if not uid:
            return ""
        self._ensure_users_first_last_name_columns()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "SELECT TRIM(COALESCE(first_name, '')), TRIM(COALESCE(last_name, '')), "
                "TRIM(COALESCE(name, '')), TRIM(COALESCE(username, '')) FROM users WHERE id = %s::uuid LIMIT 1",
                (uid,),
            )
            row = cur.fetchone()
            if not row:
                return ""
            fn = (row[0] or "").strip() if len(row) > 0 else ""
            ln = (row[1] or "").strip() if len(row) > 1 else ""
            nm = (row[2] or "").strip() if len(row) > 2 else ""
            un = (row[3] or "").strip() if len(row) > 3 else ""
            # Prefer explicit first+last; then users.name (signup often stores full name here even when first/last are partial).
            if fn and ln:
                return f"{fn} {ln}".strip()
            if nm:
                return nm
            if fn:
                return fn
            if ln:
                return ln
            if un:
                return un
            return ""
        except Exception as e:
            print(f"CentralDB get_user_full_display_name_for_user_id: {e}")
            return ""
        finally:
            cur.close()

    def get_user_username_for_user_id(self, user_id):
        """users.username when set; else full display name (legacy rows often left username empty)."""
        uid = str(user_id or "").strip()
        if not uid:
            return ""
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "SELECT TRIM(COALESCE(username, '')) FROM users WHERE id = %s::uuid LIMIT 1",
                (uid,),
            )
            row = cur.fetchone()
            un = (row[0] or "").strip() if row else ""
            if un:
                return un
            return self.get_user_full_display_name_for_user_id(user_id) or ""
        except Exception as e:
            print(f"CentralDB get_user_username_for_user_id: {e}")
            return ""
        finally:
            cur.close()

    @staticmethod
    def _first_name_greeting_token(raw):
        s = (raw or "").strip()
        if not s:
            return ""
        token = s.split()[0].strip()
        if not token:
            return ""
        return token[:1].upper() + token[1:].lower() if len(token) > 1 else token.upper()

    def _ensure_users_first_last_name_columns(self):
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name VARCHAR(120) DEFAULT NULL")
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name VARCHAR(120) DEFAULT NULL")
            conn.commit()
        except Exception:
            conn.rollback()
        finally:
            cur.close()

    def set_user_first_last_name(self, user_id, first_name="", last_name=""):
        """Persist signup first/last on users (username unchanged). At least one non-empty value required; else no-op."""
        uid = str(user_id or "").strip()
        if not uid:
            return False
        try:
            uuid.UUID(uid)
        except (ValueError, TypeError):
            return False
        fn = (first_name or "").strip()[:120] or None
        ln = (last_name or "").strip()[:120] or None
        if fn is None and ln is None:
            return True
        self._ensure_users_first_last_name_columns()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "UPDATE users SET first_name = %s, last_name = %s, updated_at = NOW() WHERE id = %s::uuid",
                (fn, ln, uid),
            )
            conn.commit()
            return True
        except Exception as e:
            conn.rollback()
            print(f"CentralDB set_user_first_last_name: {e}")
            return False
        finally:
            cur.close()

    def _ensure_users_display_mode_column(self):
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS user_display_mode VARCHAR(32) DEFAULT NULL")
            conn.commit()
        except Exception:
            conn.rollback()
        finally:
            cur.close()

    def get_user_display_mode_for_user_id(self, user_id):
        if not user_id:
            return ""
        self._ensure_users_display_mode_column()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT user_display_mode FROM users WHERE id = %s::uuid LIMIT 1", (user_id,))
            row = cur.fetchone()
            if not row:
                return ""
            if hasattr(row, "keys"):
                v = row["user_display_mode"]
            else:
                v = row[0]
            return str(v or "").strip().lower()
        except Exception as e:
            print(f"CentralDB get_user_display_mode_for_user_id: {e}")
            return ""
        finally:
            cur.close()

    def set_user_display_mode_by_bot(self, bot_id, api_key, mode):
        m = (mode or "").strip().lower()
        if m not in ("standalone", "default"):
            return False
        bid = (bot_id or "").strip()
        key = (api_key or "").strip()
        if not bid or not key:
            return False
        self._ensure_users_display_mode_column()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "UPDATE users SET user_display_mode = %s, updated_at = NOW() WHERE bot_id = %s AND api_key = %s",
                (m, bid, key),
            )
            conn.commit()
            return cur.rowcount > 0
        except Exception as e:
            conn.rollback()
            print(f"CentralDB set_user_display_mode_by_bot: {e}")
            return False
        finally:
            cur.close()

    def set_user_display_mode_for_admin(self, admin_id, user_id, mode):
        """Admin overrides linked user's shell mode (standalone vs default). User app picks up via GET /user/data + databus."""
        m = (mode or "").strip().lower()
        if m not in ("standalone", "default"):
            return False
        uid = str(user_id or "").strip()
        aid = str(admin_id or "").strip()
        if not uid or not aid:
            return False
        if not self.user_belongs_to_admin(uid, aid):
            return False
        self._ensure_users_display_mode_column()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "UPDATE users SET user_display_mode = %s, updated_at = NOW() WHERE id = %s::uuid AND admin_id = %s::uuid AND bot_id != 'dashboard'",
                (m, uid, aid),
            )
            conn.commit()
            return cur.rowcount > 0
        except Exception as e:
            conn.rollback()
            print(f"CentralDB set_user_display_mode_for_admin: {e}")
            return False
        finally:
            cur.close()

    def _ensure_users_profile_picture_column(self):
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_picture TEXT")
            conn.commit()
        except Exception:
            conn.rollback()
        finally:
            cur.close()

    def get_user_profile_picture_for_user_id(self, user_id):
        """Return stored data URL / base64 image string for linked-user avatar, or empty."""
        uid = str(user_id or "").strip()
        if not uid:
            return ""
        self._ensure_users_profile_picture_column()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT profile_picture FROM users WHERE id = %s::uuid LIMIT 1", (uid,))
            row = cur.fetchone()
            if not row:
                return ""
            v = row["profile_picture"] if hasattr(row, "keys") else row[0]
            return str(v or "").strip()
        except Exception as e:
            print(f"CentralDB get_user_profile_picture_for_user_id: {e}")
            return ""
        finally:
            cur.close()

    def set_user_profile_picture_by_bot(self, bot_id, api_key, profile_picture):
        """Store user avatar (typically data:image/jpeg;base64,...). Empty string clears. Max ~400k chars."""
        bid = (bot_id or "").strip()
        key = (api_key or "").strip()
        if not bid or not key:
            return False, "missing_credentials"
        pic = (profile_picture or "").strip()
        if len(pic) > 400000:
            return False, "too_large"
        self._ensure_users_profile_picture_column()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "UPDATE users SET profile_picture = %s, updated_at = NOW() WHERE bot_id = %s AND api_key = %s AND bot_id != 'dashboard'",
                (pic if pic else None, bid, key),
            )
            conn.commit()
            if cur.rowcount <= 0:
                return False, "user_not_found"
            return True, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB set_user_profile_picture_by_bot: {e}")
            return False, str(e)
        finally:
            cur.close()

    def get_admin_by_id(self, admin_id):
        """Return one admin {id, name, bot_id, api_key} for the given admin_id, or None. Used for event-based alert checks."""
        if not admin_id:
            return None
        try:
            uuid.UUID(str(admin_id))
        except (ValueError, TypeError):
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute("SELECT id, name, bot_id, api_key FROM admins WHERE id = %s::uuid LIMIT 1", (admin_id,))
            row = cur.fetchone()
            if not row:
                return None
            if hasattr(row, "keys"):
                return {"id": str(row["id"]), "name": row["name"] or "", "bot_id": row["bot_id"] or "", "api_key": row["api_key"] or ""}
            return {"id": str(row[0]), "name": row[1] or "", "bot_id": row[2] or "", "api_key": row[3] or ""}
        except Exception as e:
            print(f"CentralDB get_admin_by_id: {e}")
            return None
        finally:
            cur.close()

    def get_all_active_admins(self):
        """Return list of {id, name, bot_id, api_key} for all admins."""
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute("SELECT id, name, bot_id, api_key FROM admins ORDER BY created_at")
            rows = cur.fetchall()
            result = []
            for row in rows:
                if hasattr(row, "keys"):
                    result.append({
                        "id": str(row["id"]),
                        "name": row["name"] or "",
                        "bot_id": row["bot_id"] or "",
                        "api_key": row["api_key"] or "",
                    })
                else:
                    result.append({"id": str(row[0]), "name": row[1] or "", "bot_id": row[2] or "", "api_key": row[3] or ""})
            return result
        except Exception as e:
            print(f"CentralDB get_all_active_admins: {e}")
            return []
        finally:
            cur.close()

    # ---- Signup flow (email → OTP → admin connection code) — new tables/routes only ----

    def _signup_otp_pepper(self):
        return (os.environ.get("SIGNUP_OTP_PEPPER") or "curax-signup-otp-pepper-change-me").strip()

    def _normalize_signup_email(self, email):
        return (email or "").strip().lower()

    def _hash_signup_password(self, password):
        salt = secrets.token_hex(16)
        iters = 120_000
        dk = hashlib.pbkdf2_hmac("sha256", (password or "").encode("utf-8"), salt.encode("ascii"), iters)
        return f"pbkdf2_sha256${iters}${salt}${base64.b64encode(dk).decode('ascii')}"

    def _verify_signup_password(self, password, stored):
        try:
            parts = (stored or "").split("$")
            if len(parts) != 4 or parts[0] != "pbkdf2_sha256":
                return False
            iters = int(parts[1])
            salt = parts[2]
            expected = base64.b64decode(parts[3])
            dk = hashlib.pbkdf2_hmac("sha256", (password or "").encode("utf-8"), salt.encode("ascii"), iters)
            return secrets.compare_digest(dk, expected)
        except Exception:
            return False

    def _hash_signup_otp(self, email_norm, otp):
        raw = f"{email_norm}:{(otp or '').strip()}:{self._signup_otp_pepper()}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _signup_email_already_registered(self, email_n: str) -> bool:
        """True if this email is already an admin or on any user row (completed signup).

        Any non-empty users.email match blocks a new /signup/start — same person must sign in,
        not start another signup row (even if they only change first/last name on the form).
        """
        if not email_n:
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                "SELECT 1 FROM admins WHERE LOWER(TRIM(COALESCE(email, ''))) = %s LIMIT 1",
                (email_n,),
            )
            if cur.fetchone():
                return True
            try:
                cur.execute(
                    """
                    SELECT 1 FROM users
                    WHERE NULLIF(TRIM(COALESCE(email, '')), '') IS NOT NULL
                      AND LOWER(TRIM(COALESCE(email, ''))) = %s
                    LIMIT 1
                    """,
                    (email_n,),
                )
                return cur.fetchone() is not None
            except Exception:
                return False
        except Exception:
            return False
        finally:
            cur.close()

    def _signup_start_session_conflict(self, email_n: str, password_plain: str):
        """Block /signup/start from hijacking another in-progress signup on the same email.

        - PENDING_ADMIN: email already verified; only sign-in + link-admin may continue — never
          reset to a fresh OTP from a \"new sign up\" with a different password.
        - PENDING_EMAIL: allow only if the password matches the existing session (same user
          resending OTP). Otherwise reject so a stranger cannot overwrite someone else's pending row.
        """
        if not email_n:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """
                SELECT account_status, password_hash
                FROM signup_sessions
                WHERE email_normalized = %s
                LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                return None
            st = str(row[0] or "").strip().upper()
            ph_row = row[1]
            if st == "PENDING_ADMIN":
                return {
                    "ok": False,
                    "error": "email_signup_in_progress",
                    "detail": (
                        "This email is already verified and waiting to connect to an admin. "
                        "Sign in to continue — you cannot start a new sign-up on this address."
                    ),
                }
            if st == "PENDING_EMAIL" and ph_row and not self._verify_signup_password(password_plain, ph_row):
                return {
                    "ok": False,
                    "error": "email_signup_in_progress",
                    "detail": (
                        "A sign-up is already in progress for this email with a different password. "
                        "Sign in with that password or use a different email."
                    ),
                }
            return None
        except Exception as e:
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err or "relation" in err:
                return None
            print(f"CentralDB _signup_start_session_conflict: {e}")
            return None
        finally:
            cur.close()

    def admin_mobile_sign_in_start(self, email, password):
        """Match admins.desktop_password_hash; create challenge row and email OTP (admin-specific copy)."""
        email_n = self._normalize_signup_email(email)
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        pw = (password or "").strip()
        if len(pw) < 6:
            return {"ok": False, "error": "password_too_short"}
        if not self.has_admin_mobile_login_challenges_table():
            return {"ok": False, "error": "admin_mobile_login_not_configured"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT name, email, desktop_password_hash
                FROM admins
                WHERE LOWER(TRIM(COALESCE(email, ''))) = %s
                LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                return {"ok": False, "error": "unknown_admin_email"}
            if hasattr(row, "keys"):
                admin_name = (row.get("name") or "").strip()
                mail_disp = (row.get("email") or "").strip() or email_n
                dph = (row.get("desktop_password_hash") or "").strip()
            else:
                admin_name = (row[0] or "").strip()
                mail_disp = (row[1] or "").strip() or email_n
                dph = (row[2] or "").strip()
            if not dph:
                return {
                    "ok": False,
                    "error": "password_not_synced",
                    "detail": (
                        "This admin record has no password set for mobile sign-in yet. "
                        "Use Sign up as admin with this email (if new), or sign in from another device that already saved "
                        "your password to the server. Otherwise contact support."
                    ),
                }
            if not self._verify_signup_password(pw, dph):
                return {"ok": False, "error": "invalid_credentials"}
            cur.execute("DELETE FROM admin_mobile_login_challenges WHERE email_normalized = %s", (email_n,))
            otp = str(secrets.randbelow(900_000) + 100_000)
            otp_h = self._hash_signup_otp(email_n, otp)
            challenge_token = secrets.token_urlsafe(32)
            cur.execute(
                """
                INSERT INTO admin_mobile_login_challenges (email_normalized, challenge_token, otp_hash, expires_at)
                VALUES (%s, %s, %s, NOW() + INTERVAL '15 minutes')
                """,
                (email_n, challenge_token, otp_h),
            )
            conn.commit()
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "admin_mobile_login_challenges" in err or "does not exist" in err or "relation" in err:
                return {"ok": False, "error": "admin_mobile_login_not_configured"}
            print(f"CentralDB admin_mobile_sign_in_start: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

        email_sent = False
        try:
            email_sent = bool(_send_admin_mobile_otp_email(mail_disp, otp, admin_name))
            if email_sent:
                print(f"  [admin mobile OTP] email sent to {mail_disp} (expires in 15m)")
            else:
                print(
                    f"  [admin mobile OTP] {mail_disp} -> {otp} (expires in 15m; no SMTP: set "
                    "SIGNUP_SMTP_USER + SIGNUP_SMTP_PASSWORD e.g. Gmail app password; "
                    "SIGNUP_DEV_RETURN_OTP=1 also returns dev_otp for admin routes when enabled)"
                )
        except Exception as e:
            print(f"  [admin mobile OTP] SMTP error for {mail_disp}: {e}; OTP logged for ops: {otp}")
            email_sent = False

        out = {"ok": True, "message": "otp_sent", "challenge_token": challenge_token, "email_sent": email_sent}
        if (os.environ.get("SIGNUP_DEV_RETURN_OTP") or "").strip().lower() in ("1", "true", "yes"):
            out["dev_otp"] = otp
        return out

    def admin_mobile_sign_in_verify(self, challenge_token, otp):
        """Exchange challenge_token + OTP for admin codes (mobile then POST /save-credentials with access_code)."""
        tok = (challenge_token or "").strip()
        otp_in = (otp or "").strip().replace(" ", "")
        if len(tok) < 16 or len(otp_in) < 6:
            return {"ok": False, "error": "invalid_input"}
        if not self.has_admin_mobile_login_challenges_table():
            return {"ok": False, "error": "admin_mobile_login_not_configured"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT email_normalized, otp_hash, expires_at
                FROM admin_mobile_login_challenges
                WHERE challenge_token = %s
                LIMIT 1
                """,
                (tok,),
            )
            row = cur.fetchone()
            if not row:
                return {"ok": False, "error": "challenge_not_found"}
            if hasattr(row, "keys"):
                email_n = (row.get("email_normalized") or "").strip()
                otp_hash_stored = (row.get("otp_hash") or "").strip()
                exp = row.get("expires_at")
            else:
                email_n = (row[0] or "").strip()
                otp_hash_stored = (row[1] or "").strip()
                exp = row[2]
            now = datetime.now(timezone.utc)
            if exp is not None:
                if getattr(exp, "tzinfo", None) is None:
                    exp = exp.replace(tzinfo=timezone.utc)
                else:
                    exp = exp.astimezone(timezone.utc)
                if now > exp:
                    cur.execute("DELETE FROM admin_mobile_login_challenges WHERE challenge_token = %s", (tok,))
                    conn.commit()
                    return {"ok": False, "error": "otp_expired"}
            want = self._hash_signup_otp(email_n, otp_in)
            if not otp_hash_stored or not secrets.compare_digest(want, otp_hash_stored):
                return {"ok": False, "error": "invalid_otp"}
            cur.execute("DELETE FROM admin_mobile_login_challenges WHERE challenge_token = %s", (tok,))
            cur.execute(
                """
                SELECT name, email, admin_access_code, connection_code
                FROM admins
                WHERE LOWER(TRIM(COALESCE(email, ''))) = %s
                LIMIT 1
                """,
                (email_n,),
            )
            adm = cur.fetchone()
            if not adm:
                conn.commit()
                return {"ok": False, "error": "admin_missing"}
            if hasattr(adm, "keys"):
                name = (adm.get("name") or "").strip()
                em = (adm.get("email") or "").strip()
                ac = (adm.get("admin_access_code") or "").strip()
                cc = (adm.get("connection_code") or "").strip()
            else:
                name = (adm[0] or "").strip()
                em = (adm[1] or "").strip()
                ac = (adm[2] or "").strip()
                cc = (adm[3] or "").strip()
            conn.commit()
            return {
                "ok": True,
                "message": "ok",
                "name": name,
                "email": em or email_n,
                "admin_access_code": ac,
                "connection_code": cc,
            }
        except Exception as e:
            conn.rollback()
            print(f"CentralDB admin_mobile_sign_in_verify: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def _issue_signin_verify_otp(self, email_n: str, user_id: str) -> dict:
        """Store OTP on users.password_reset_otp_* for post sign-in email verify (reuses existing columns)."""
        otp = str(secrets.randbelow(900_000) + 100_000)
        otp_h = self._hash_signup_otp(email_n, otp)
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """
                UPDATE users SET
                    password_reset_otp_hash = %s,
                    password_reset_otp_expires_at = NOW() + INTERVAL '15 minutes',
                    updated_at = NOW()
                WHERE id = %s::uuid
                """,
                (otp_h, user_id),
            )
            conn.commit()
            if cur.rowcount == 0:
                conn.rollback()
                return {"ok": False, "error": "database_error"}
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if (
                "password_reset_otp" in err
                or "undefinedcolumn" in err.replace(" ", "")
                or ("column" in err and "does not exist" in err)
            ):
                return {
                    "ok": False,
                    "error": "signin_verify_not_configured",
                    "detail": "Run central_schema.sql on Postgres (password_reset_otp columns on users).",
                }
            print(f"CentralDB _issue_signin_verify_otp: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

        email_sent = False
        try:
            email_sent = bool(_send_signup_otp_email(email_n, otp))
            if email_sent:
                print(f"  [sign-in verify OTP] email sent to {email_n} (expires in 15m)")
            else:
                print(f"  [sign-in verify OTP] {email_n} -> {otp} (expires in 15m; configure SIGNUP_SMTP_*)")
        except Exception as e:
            print(f"  [sign-in verify OTP] SMTP error for {email_n}: {e}; OTP: {otp}")
            email_sent = False
        out = {"ok": True, "message": "otp_sent", "email_sent": email_sent}
        if (os.environ.get("SIGNUP_DEV_RETURN_OTP") or "").strip().lower() in ("1", "true", "yes"):
            out["dev_otp"] = otp
        return out

    def _active_user_signin_payload(self, email_n: str) -> dict:
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT u.bot_id, u.api_key, u.admin_id::text AS admin_id,
                       COALESCE(a.name, '') AS admin_name,
                       COALESCE(a.admin_access_code, '') AS databus_access_code,
                       COALESCE(a.connection_code, '') AS connection_code,
                       COALESCE(u.email, '') AS email
                FROM users u
                INNER JOIN admins a ON a.id = u.admin_id
                WHERE LOWER(TRIM(COALESCE(u.email, ''))) = %s
                  AND COALESCE(u.bot_id, '') IS DISTINCT FROM 'dashboard'
                ORDER BY u.updated_at DESC NULLS LAST, u.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (email_n,),
            )
            urow = cur.fetchone()
        finally:
            cur.close()
        if not urow:
            return {"ok": False, "error": "user_missing"}
        if hasattr(urow, "keys"):
            return {
                "ok": True,
                "email": (urow.get("email") or "").strip() or email_n,
                "bot_id": str(urow.get("bot_id") or "").strip(),
                "api_key": str(urow.get("api_key") or "").strip(),
                "admin_id": str(urow.get("admin_id") or "").strip(),
                "admin_name": str(urow.get("admin_name") or "").strip(),
                "databus_access_code": str(urow.get("databus_access_code") or "").strip(),
                "connection_code": str(urow.get("connection_code") or "").strip(),
            }
        return {
            "ok": True,
            "email": (urow[6] or "").strip() or email_n,
            "bot_id": str(urow[0] or "").strip(),
            "api_key": str(urow[1] or "").strip(),
            "admin_id": str(urow[2] or "").strip(),
            "admin_name": str(urow[3] or "").strip(),
            "databus_access_code": str(urow[4] or "").strip(),
            "connection_code": str(urow[5] or "").strip(),
        }

    def signup_sign_in_verify_otp(self, email, otp):
        """Verify sign-in email OTP (users.password_reset_otp_*), return active session fields."""
        email_n = self._normalize_signup_email(email)
        otp_s = (otp or "").strip().replace(" ", "")
        if not email_n or len(otp_s) < 6:
            return {"ok": False, "error": "invalid_input"}
        want = self._hash_signup_otp(email_n, otp_s)
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        user_id = None
        try:
            cur.execute(
                """
                SELECT u.id::text AS id
                FROM users u
                WHERE LOWER(TRIM(COALESCE(u.email, ''))) = %s
                  AND COALESCE(u.bot_id, '') IS DISTINCT FROM 'dashboard'
                  AND u.password_reset_otp_hash = %s
                  AND u.password_reset_otp_expires_at IS NOT NULL
                  AND u.password_reset_otp_expires_at > NOW()
                ORDER BY u.updated_at DESC NULLS LAST, u.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (email_n, want),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return {"ok": False, "error": "invalid_or_expired"}
            user_id = str((row.get("id") if hasattr(row, "keys") else row[0]) or "").strip()
            if not user_id:
                conn.rollback()
                return {"ok": False, "error": "invalid_or_expired"}
            cur.execute(
                """
                UPDATE users SET
                    password_reset_otp_hash = NULL,
                    password_reset_otp_expires_at = NULL,
                    updated_at = NOW()
                WHERE id = %s::uuid
                """,
                (user_id,),
            )
            conn.commit()
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if (
                "password_reset_otp" in err
                or "undefinedcolumn" in err.replace(" ", "")
                or ("column" in err and "does not exist" in err)
            ):
                return {
                    "ok": False,
                    "error": "signin_verify_not_configured",
                    "detail": "Run central_schema.sql on Postgres (password_reset_otp columns on users).",
                }
            print(f"CentralDB signup_sign_in_verify_otp: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

        payload = self._active_user_signin_payload(email_n)
        if not payload.get("ok"):
            return payload
        payload["account_phase"] = "active"
        return payload

    def admin_email_signup_start(self, email, password):
        """Independent mobile admin registration: OTP email; verify creates admins row with desktop_password_hash."""
        email_n = self._normalize_signup_email(email)
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        pw = (password or "").strip()
        if len(pw) < 6:
            return {"ok": False, "error": "password_too_short"}
        if not self.has_admin_email_signup_sessions_table():
            return {"ok": False, "error": "admin_email_signup_not_configured"}
        if self._signup_email_already_registered(email_n):
            return {
                "ok": False,
                "error": "email_already_registered",
                "detail": "This email already has an account. Sign in instead.",
            }
        pw_h = self._hash_signup_password(pw)
        otp = str(secrets.randbelow(900_000) + 100_000)
        otp_h = self._hash_signup_otp(email_n, otp)
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """
                INSERT INTO admin_email_signup_sessions (
                    email_normalized, password_hash, otp_hash, otp_expires_at, updated_at
                ) VALUES (%s, %s, %s, NOW() + INTERVAL '15 minutes', NOW())
                ON CONFLICT (email_normalized) DO UPDATE SET
                    password_hash = EXCLUDED.password_hash,
                    otp_hash = EXCLUDED.otp_hash,
                    otp_expires_at = EXCLUDED.otp_expires_at,
                    updated_at = NOW()
                """,
                (email_n, pw_h, otp_h),
            )
            conn.commit()
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "admin_email_signup_sessions" in err or "does not exist" in err or "relation" in err:
                return {"ok": False, "error": "admin_email_signup_not_configured"}
            print(f"CentralDB admin_email_signup_start: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

        email_sent = False
        try:
            email_sent = bool(_send_admin_email_signup_otp_email(email_n, otp))
            if email_sent:
                print(f"  [admin email signup OTP] sent to {email_n} (expires in 15m)")
            else:
                print(
                    f"  [admin email signup OTP] {email_n} -> {otp} (expires in 15m; configure SMTP or "
                    "SIGNUP_DEV_RETURN_OTP=1)"
                )
        except Exception as e:
            print(f"  [admin email signup OTP] SMTP error for {email_n}: {e}; OTP logged: {otp}")
            email_sent = False

        out = {"ok": True, "message": "otp_sent", "email_sent": email_sent}
        if (os.environ.get("SIGNUP_DEV_RETURN_OTP") or "").strip().lower() in ("1", "true", "yes"):
            out["dev_otp"] = otp
        return out

    def admin_email_signup_verify(self, email, otp):
        """Verify OTP and insert new admins row (mobile-created administrator)."""
        email_n = self._normalize_signup_email(email)
        otp_in = (otp or "").strip().replace(" ", "")
        if not email_n or len(otp_in) < 6:
            return {"ok": False, "error": "invalid_input"}
        if not self.has_admin_email_signup_sessions_table():
            return {"ok": False, "error": "admin_email_signup_not_configured"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT password_hash, otp_hash, otp_expires_at
                FROM admin_email_signup_sessions
                WHERE email_normalized = %s
                LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                return {"ok": False, "error": "session_not_found"}
            if hasattr(row, "keys"):
                pw_hash = (row.get("password_hash") or "").strip()
                otp_hash_stored = (row.get("otp_hash") or "").strip()
                exp = row.get("otp_expires_at")
            else:
                pw_hash = (row[0] or "").strip()
                otp_hash_stored = (row[1] or "").strip()
                exp = row[2]
            now = datetime.now(timezone.utc)
            if exp is not None:
                if getattr(exp, "tzinfo", None) is None:
                    exp = exp.replace(tzinfo=timezone.utc)
                else:
                    exp = exp.astimezone(timezone.utc)
                if now > exp:
                    cur.execute("DELETE FROM admin_email_signup_sessions WHERE email_normalized = %s", (email_n,))
                    conn.commit()
                    return {"ok": False, "error": "otp_expired"}
            want = self._hash_signup_otp(email_n, otp_in)
            if not otp_hash_stored or not secrets.compare_digest(want, otp_hash_stored):
                return {"ok": False, "error": "invalid_otp"}
            cur.execute("DELETE FROM admin_email_signup_sessions WHERE email_normalized = %s", (email_n,))

            cur.execute(
                "SELECT 1 FROM admins WHERE LOWER(TRIM(COALESCE(email, ''))) = %s LIMIT 1",
                (email_n,),
            )
            if cur.fetchone():
                conn.commit()
                return {"ok": False, "error": "email_already_registered"}

            local = email_n.split("@")[0][:120] if "@" in email_n else "Admin"
            name_guess = (local[:1].upper() + local[1:]) if local else "Administrator"

            bot_id = secrets.token_hex(4)[:8]
            api_key = secrets.token_hex(8)
            access_code = self._generate_admin_access_code()
            connection_code = self._generate_connection_code()

            cur.execute(
                """
                INSERT INTO admins (
                    name, email, bot_id, api_key, admin_access_code, connection_code,
                    fcm_token, is_admin, updated_at, desktop_password_hash
                )
                VALUES (%s, %s, %s, %s, %s, %s, NULL, true, NOW(), %s)
                RETURNING id, admin_access_code, connection_code
                """,
                (name_guess, email_n, bot_id, api_key, access_code, connection_code, pw_hash),
            )
            ins = cur.fetchone()
            conn.commit()
            if not ins:
                return {"ok": False, "error": "database_error"}
            if hasattr(ins, "keys"):
                ac = (ins.get("admin_access_code") or "").strip()
                cc = (ins.get("connection_code") or "").strip()
            else:
                ac = (ins[1] or "").strip()
                cc = (ins[2] or "").strip()
            return {
                "ok": True,
                "message": "ok",
                "email": email_n,
                "admin_access_code": ac,
                "connection_code": cc,
                "name": name_guess,
            }
        except Exception as e:
            conn.rollback()
            print(f"CentralDB admin_email_signup_verify: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def signup_flow_start(self, email, password, first_name="", last_name=""):
        """Create/update signup_sessions row (NOT users — users row is created at /signup/link-admin).

        Until email + admin link complete, pending signups live only in signup_sessions
        (account_status PENDING_EMAIL, then PENDING_ADMIN). The users table stays empty for that email.

        Same email may call /signup/start again while PENDING_EMAIL with the *same* password (OTP
        refresh / resend). PENDING_ADMIN or PENDING_EMAIL with a different password returns
        email_signup_in_progress. If the email already exists on admins or users (finished
        registration), returns email_already_registered and no mail.

        Optional first_name / last_name are stored when signup_sessions has those columns
        (see signup_sessions.first_name / last_name in central_schema.sql).
        """
        email_n = self._normalize_signup_email(email)
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        pw = (password or "").strip()
        if len(pw) < 6:
            return {"ok": False, "error": "password_too_short"}
        if self._signup_email_already_registered(email_n):
            return {
                "ok": False,
                "error": "email_already_registered",
                "detail": "This email is already in use. Sign in with your existing account.",
            }
        conflict = self._signup_start_session_conflict(email_n, pw)
        if conflict:
            return conflict
        fn = (first_name or "").strip()[:120]
        ln = (last_name or "").strip()[:120]
        otp = str(secrets.randbelow(900_000) + 100_000)
        otp_h = self._hash_signup_otp(email_n, otp)
        pw_h = self._hash_signup_password(pw)
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            try:
                cur.execute(
                    """
                    INSERT INTO signup_sessions (
                        email_normalized, password_hash, account_status,
                        email_otp_hash, email_otp_expires_at, email_verified_at, updated_at,
                        first_name, last_name
                    ) VALUES (%s, %s, 'PENDING_EMAIL', %s, NOW() + INTERVAL '15 minutes', NULL, NOW(), %s, %s)
                    ON CONFLICT (email_normalized) DO UPDATE SET
                        password_hash = EXCLUDED.password_hash,
                        account_status = 'PENDING_EMAIL',
                        email_otp_hash = EXCLUDED.email_otp_hash,
                        email_otp_expires_at = EXCLUDED.email_otp_expires_at,
                        email_verified_at = NULL,
                        updated_at = NOW(),
                        first_name = CASE WHEN EXCLUDED.first_name <> '' THEN EXCLUDED.first_name ELSE signup_sessions.first_name END,
                        last_name = CASE WHEN EXCLUDED.last_name <> '' THEN EXCLUDED.last_name ELSE signup_sessions.last_name END
                    """,
                    (email_n, pw_h, otp_h, fn, ln),
                )
            except Exception as e0:
                e0s = str(e0).lower()
                if "first_name" in e0s and ("does not exist" in e0s or "undefinedcolumn" in e0s.replace(" ", "")):
                    cur.execute(
                        """
                        INSERT INTO signup_sessions (
                            email_normalized, password_hash, account_status,
                            email_otp_hash, email_otp_expires_at, email_verified_at, updated_at
                        ) VALUES (%s, %s, 'PENDING_EMAIL', %s, NOW() + INTERVAL '15 minutes', NULL, NOW())
                        ON CONFLICT (email_normalized) DO UPDATE SET
                            password_hash = EXCLUDED.password_hash,
                            account_status = 'PENDING_EMAIL',
                            email_otp_hash = EXCLUDED.email_otp_hash,
                            email_otp_expires_at = EXCLUDED.email_otp_expires_at,
                            email_verified_at = NULL,
                            updated_at = NOW()
                        """,
                        (email_n, pw_h, otp_h),
                    )
                else:
                    raise e0
            conn.commit()
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err or "relation" in err:
                return {"ok": False, "error": "signup_not_configured", "detail": "Run central_schema.sql on Postgres (signup_sessions table)."}
            print(f"CentralDB signup_flow_start: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()
        email_sent = False
        try:
            email_sent = bool(_send_signup_otp_email(email_n, otp))
            if email_sent:
                print(f"  [signup OTP] email sent to {email_n} (expires in 15m)")
            else:
                print(
                    f"  [signup OTP] {email_n} -> {otp} (expires in 15m; no SMTP: set "
                    "SIGNUP_SMTP_USER + SIGNUP_SMTP_PASSWORD e.g. Gmail app password; "
                    "SIGNUP_DEV_RETURN_OTP=1 returns code in JSON for dev)"
                )
        except Exception as e:
            print(f"  [signup OTP] SMTP error for {email_n}: {e}; OTP logged for ops: {otp}")
            email_sent = False
        out = {"ok": True, "message": "otp_sent", "email_sent": email_sent}
        if (os.environ.get("SIGNUP_DEV_RETURN_OTP") or "").strip() in ("1", "true", "yes"):
            out["dev_otp"] = otp
        return out

    def password_reset_start(self, email):
        """Send OTP to prove inbox ownership; then client sets password on users row. Generic OK if email unknown."""
        email_n = self._normalize_signup_email(email)
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        row = None
        try:
            cur.execute(
                """
                SELECT u.id::text AS id, u.password_hash,
                       COALESCE(u.account_status, 'ACTIVE') AS account_status
                FROM users u
                WHERE LOWER(TRIM(COALESCE(u.email, ''))) = %s
                  AND COALESCE(u.bot_id, '') IS DISTINCT FROM 'dashboard'
                ORDER BY u.updated_at DESC NULLS LAST, u.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
        except Exception as e:
            print(f"CentralDB password_reset_start lookup: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

        generic_ok = {"ok": True, "message": "if_registered", "email_sent": False}
        if not row:
            return generic_ok
        st = str(row.get("account_status") or "ACTIVE").strip().upper()
        # Same lifecycle values as users.account_status CHECK (central_schema.sql).
        if st not in ("ACTIVE", "PENDING_ADMIN", "PENDING", "PENDING_EMAIL"):
            return generic_ok
        user_id = str(row.get("id") or "").strip()
        if not user_id:
            return generic_ok

        otp = str(secrets.randbelow(900_000) + 100_000)
        otp_h = self._hash_signup_otp(email_n, otp)
        cur2 = conn.cursor()
        try:
            cur2.execute(
                """
                UPDATE users SET
                    password_reset_otp_hash = %s,
                    password_reset_otp_expires_at = NOW() + INTERVAL '15 minutes',
                    updated_at = NOW()
                WHERE id = %s::uuid
                """,
                (otp_h, user_id),
            )
            conn.commit()
            if cur2.rowcount == 0:
                conn.rollback()
                return generic_ok
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if (
                "password_reset_otp" in err
                or "undefinedcolumn" in err.replace(" ", "")
                or ("column" in err and "does not exist" in err)
            ):
                return {
                    "ok": False,
                    "error": "password_reset_not_configured",
                    "detail": "Run central_schema.sql on Postgres (password_reset_otp columns on users).",
                }
            print(f"CentralDB password_reset_start update: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur2.close()

        email_sent = False
        try:
            email_sent = bool(_send_signup_otp_email(email_n, otp))
            if email_sent:
                print(f"  [password reset OTP] email sent to {email_n} (expires in 15m)")
            else:
                print(
                    f"  [password reset OTP] {email_n} -> {otp} (expires in 15m; no SMTP or "
                    "SIGNUP_DEV_RETURN_OTP for dev)"
                )
        except Exception as e:
            print(f"  [password reset OTP] SMTP error for {email_n}: {e}; OTP: {otp}")
            email_sent = False
        out = {"ok": True, "message": "otp_sent", "email_sent": email_sent}
        if (os.environ.get("SIGNUP_DEV_RETURN_OTP") or "").strip() in ("1", "true", "yes"):
            out["dev_otp"] = otp
        return out

    def password_reset_complete(self, email, otp, new_password):
        """Verify OTP and set new password on users (+ signup_sessions if present)."""
        email_n = self._normalize_signup_email(email)
        otp_s = (otp or "").strip()
        pw = (new_password or "").strip()
        if not email_n or len(otp_s) < 6:
            return {"ok": False, "error": "invalid_input"}
        if len(pw) < 6:
            return {"ok": False, "error": "password_too_short"}
        want = self._hash_signup_otp(email_n, otp_s)
        pw_h = self._hash_signup_password(pw)
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                UPDATE users AS u SET
                    password_hash = %s,
                    password_reset_otp_hash = NULL,
                    password_reset_otp_expires_at = NULL,
                    updated_at = NOW()
                FROM (
                    SELECT u2.id
                    FROM users u2
                    WHERE LOWER(TRIM(COALESCE(u2.email, ''))) = %s
                      AND COALESCE(u2.bot_id, '') IS DISTINCT FROM 'dashboard'
                      AND u2.password_reset_otp_hash = %s
                      AND u2.password_reset_otp_expires_at IS NOT NULL
                      AND u2.password_reset_otp_expires_at > NOW()
                    ORDER BY u2.updated_at DESC NULLS LAST, u2.created_at DESC NULLS LAST
                    LIMIT 1
                ) AS sub
                WHERE u.id = sub.id
                RETURNING u.id::text AS id
                """,
                (pw_h, email_n, want),
            )
            updated = cur.fetchone()
            if not updated:
                conn.rollback()
                return {"ok": False, "error": "invalid_or_expired"}
            try:
                cur.execute(
                    """
                    UPDATE signup_sessions SET password_hash = %s, updated_at = NOW()
                    WHERE email_normalized = %s
                    """,
                    (pw_h, email_n),
                )
            except Exception:
                pass
            conn.commit()
            return {"ok": True, "message": "password_updated"}
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if (
                "password_reset_otp" in err
                or "undefinedcolumn" in err.replace(" ", "")
                or ("column" in err and "does not exist" in err)
            ):
                return {
                    "ok": False,
                    "error": "password_reset_not_configured",
                    "detail": "Run central_schema.sql on Postgres (password_reset_otp columns on users).",
                }
            print(f"CentralDB password_reset_complete: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def signup_sign_in(self, email, password):
        """POST email+password: pending signup session, or active user row (bot credentials).

        Returns dict with ok=True and account_phase in pending_email | pending_admin | active,
        or ok=False with error invalid_email | password_too_short | invalid_password |
        unknown_email | password_not_set | invalid_state.
        """
        email_n = self._normalize_signup_email(email)
        pw = (password or "").strip()
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        if len(pw) < 6:
            return {"ok": False, "error": "password_too_short"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        srow = None
        try:
            cur.execute(
                """
                SELECT password_hash, account_status
                FROM signup_sessions
                WHERE email_normalized = %s
                LIMIT 1
                """,
                (email_n,),
            )
            srow = cur.fetchone()
        except Exception as e:
            err = str(e).lower()
            # Only skip session lookup when the table/relation is missing — not permission errors etc.
            missing_signup_sessions = (
                "does not exist" in err
                and ("signup_sessions" in err or "relation" in err)
            )
            if not missing_signup_sessions:
                print(f"CentralDB signup_sign_in session: {e}")
                try:
                    cur.close()
                except Exception:
                    pass
                return {"ok": False, "error": "database_error"}
        finally:
            try:
                cur.close()
            except Exception:
                pass

        if srow:
            ph = srow["password_hash"] if hasattr(srow, "keys") else srow[0]
            st = (srow["account_status"] if hasattr(srow, "keys") else srow[1]) or ""
            st = str(st).strip().upper()
            if not self._verify_signup_password(pw, ph):
                return {"ok": False, "error": "invalid_password"}
            if st == "PENDING_EMAIL":
                return {"ok": True, "account_phase": "pending_email"}
            if st == "PENDING_ADMIN":
                out = {"ok": True, "account_phase": "pending_admin"}
                creds = self._signup_pending_directory_link_credentials(email_n)
                if creds:
                    out.update(creds)
                return out
            return {"ok": False, "error": "invalid_state", "account_status": st}

        cur2 = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        urow = None
        try:
            cur2.execute(
                """
                SELECT u.id::text AS id, u.bot_id, u.api_key, u.password_hash, u.account_status,
                       u.admin_id::text AS admin_id,
                       COALESCE(a.name, '') AS admin_name,
                       COALESCE(a.admin_access_code, '') AS databus_access_code,
                       COALESCE(a.connection_code, '') AS connection_code
                FROM users u
                INNER JOIN admins a ON a.id = u.admin_id
                WHERE LOWER(TRIM(COALESCE(u.email, ''))) = %s
                  AND COALESCE(u.bot_id, '') IS DISTINCT FROM 'dashboard'
                ORDER BY u.updated_at DESC NULLS LAST, u.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (email_n,),
            )
            urow = cur2.fetchone()
        except Exception as e:
            print(f"CentralDB signup_sign_in user lookup: {e}")
            urow = None
        finally:
            cur2.close()

        if not urow:
            return {
                "ok": False,
                "error": "unknown_email",
                "detail": (
                    "No matching account for this email and password. "
                    "If you still need to verify your email, start sign up again with the same email—"
                    "pending verification sessions are removed after 7 days when scheduled cleanup runs."
                ),
            }

        pw_hash = urow.get("password_hash") if hasattr(urow, "get") else None
        if not pw_hash or not str(pw_hash).strip():
            return {
                "ok": False,
                "error": "password_not_set",
                "detail": "This account has no password on file. Complete setup from your admin or use desktop linking.",
            }
        if not self._verify_signup_password(pw, pw_hash):
            return {"ok": False, "error": "invalid_password"}

        st = str(urow.get("account_status") or "ACTIVE").strip().upper()
        if st in ("PENDING_EMAIL", "PENDING_ADMIN", "PENDING"):
            return {"ok": False, "error": "account_incomplete", "account_status": st}

        user_id = str(urow.get("id") or "").strip()
        if not user_id:
            return {"ok": False, "error": "database_error"}
        otp_out = self._issue_signin_verify_otp(email_n, user_id)
        if not otp_out.get("ok"):
            return otp_out
        out = {
            "ok": True,
            "account_phase": "signin_verify",
            "email_sent": bool(otp_out.get("email_sent")),
        }
        if otp_out.get("dev_otp"):
            out["dev_otp"] = otp_out["dev_otp"]
        return out

    def signup_sign_in_oauth_email(self, verified_email: str):
        """Same outcomes as [signup_sign_in] but identity is already proved by Google/Facebook server-side."""
        email_n = self._normalize_signup_email(verified_email)
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        srow = None
        try:
            cur.execute(
                """
                SELECT account_status
                FROM signup_sessions
                WHERE email_normalized = %s
                LIMIT 1
                """,
                (email_n,),
            )
            srow = cur.fetchone()
        except Exception as e:
            err = str(e).lower()
            missing_signup_sessions = (
                "does not exist" in err
                and ("signup_sessions" in err or "relation" in err)
            )
            if not missing_signup_sessions:
                print(f"CentralDB signup_sign_in_oauth_email session: {e}")
                try:
                    cur.close()
                except Exception:
                    pass
                return {"ok": False, "error": "database_error"}
        finally:
            try:
                cur.close()
            except Exception:
                pass

        if srow:
            st = (srow["account_status"] if hasattr(srow, "keys") else srow[0]) or ""
            st = str(st).strip().upper()
            if st == "PENDING_EMAIL":
                return {"ok": True, "account_phase": "pending_email", "email": email_n}
            if st == "PENDING_ADMIN":
                out = {"ok": True, "account_phase": "pending_admin", "email": email_n}
                creds = self._signup_pending_directory_link_credentials(email_n)
                if creds:
                    out.update(creds)
                return out
            return {"ok": False, "error": "invalid_state", "account_status": st}

        cur2 = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        urow = None
        try:
            cur2.execute(
                """
                SELECT u.bot_id, u.api_key, u.password_hash, u.account_status, u.admin_id::text AS admin_id,
                       COALESCE(a.name, '') AS admin_name,
                       COALESCE(a.admin_access_code, '') AS databus_access_code,
                       COALESCE(a.connection_code, '') AS connection_code
                FROM users u
                INNER JOIN admins a ON a.id = u.admin_id
                WHERE LOWER(TRIM(COALESCE(u.email, ''))) = %s
                  AND COALESCE(u.bot_id, '') IS DISTINCT FROM 'dashboard'
                ORDER BY u.updated_at DESC NULLS LAST, u.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (email_n,),
            )
            urow = cur2.fetchone()
        except Exception as e:
            print(f"CentralDB signup_sign_in_oauth_email user lookup: {e}")
            urow = None
        finally:
            cur2.close()

        if not urow:
            return {
                "ok": False,
                "error": "unknown_email",
                "detail": (
                    "No matching account for this email. "
                    "If you still need to verify your email, start sign up again with the same email—"
                    "pending verification sessions are removed after 7 days when scheduled cleanup runs."
                ),
            }

        pw_hash = urow.get("password_hash") if hasattr(urow, "get") else None
        if not pw_hash or not str(pw_hash).strip():
            return {
                "ok": False,
                "error": "password_not_set",
                "detail": "This account has no password on file. Complete setup from your admin or use desktop linking.",
            }

        st = str(urow.get("account_status") or "ACTIVE").strip().upper()
        if st in ("PENDING_EMAIL", "PENDING_ADMIN", "PENDING"):
            return {"ok": False, "error": "account_incomplete", "account_status": st}

        return {
            "ok": True,
            "account_phase": "active",
            "email": email_n,
            "bot_id": str(urow.get("bot_id") or "").strip(),
            "api_key": str(urow.get("api_key") or "").strip(),
            "admin_id": str(urow.get("admin_id") or "").strip(),
            "admin_name": str(urow.get("admin_name") or "").strip(),
            "databus_access_code": str(urow.get("databus_access_code") or "").strip(),
            "connection_code": str(urow.get("connection_code") or "").strip(),
        }

    def signup_flow_resend_otp_pending_email_oauth(self, verified_email: str):
        """Refresh OTP for an existing PENDING_EMAIL session when the caller proved identity via OAuth (no password)."""
        email_n = self._normalize_signup_email(verified_email)
        if not email_n or "@" not in email_n or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email_n):
            return {"ok": False, "error": "invalid_email"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT account_status FROM signup_sessions WHERE email_normalized = %s LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return {"ok": False, "error": "session_not_found"}
            st = str(row["account_status"] if hasattr(row, "keys") else row[0] or "").strip().upper()
            if st != "PENDING_EMAIL":
                conn.rollback()
                return {"ok": False, "error": "wrong_state", "account_status": st}
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err or "relation" in err:
                return {"ok": False, "error": "signup_not_configured", "detail": "Run central_schema.sql on Postgres (signup_sessions table)."}
            print(f"CentralDB signup_flow_resend_otp_pending_email_oauth: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

        otp = str(secrets.randbelow(900_000) + 100_000)
        otp_h = self._hash_signup_otp(email_n, otp)
        cur2 = conn.cursor()
        try:
            cur2.execute(
                """
                UPDATE signup_sessions SET
                    email_otp_hash = %s,
                    email_otp_expires_at = NOW() + INTERVAL '15 minutes',
                    updated_at = NOW()
                WHERE email_normalized = %s AND account_status = 'PENDING_EMAIL'
                RETURNING email_normalized
                """,
                (otp_h, email_n),
            )
            if not cur2.fetchone():
                conn.rollback()
                return {"ok": False, "error": "wrong_state"}
            conn.commit()
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err or "relation" in err:
                return {"ok": False, "error": "signup_not_configured"}
            print(f"CentralDB signup_flow_resend_otp_pending_email_oauth update: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur2.close()

        email_sent = False
        try:
            email_sent = bool(_send_signup_otp_email(email_n, otp))
            if email_sent:
                print(f"  [signup OTP oauth resend] email sent to {email_n} (expires in 15m)")
            else:
                print(
                    f"  [signup OTP oauth resend] {email_n} -> {otp} (expires in 15m; no SMTP: set "
                    "SIGNUP_SMTP_USER + SIGNUP_SMTP_PASSWORD e.g. Gmail app password; "
                    "SIGNUP_DEV_RETURN_OTP=1 returns code in JSON for dev)"
                )
        except Exception as e:
            print(f"  [signup OTP oauth resend] SMTP error for {email_n}: {e}; OTP logged for ops: {otp}")
            email_sent = False
        out = {"ok": True, "message": "otp_sent", "email_sent": email_sent}
        if (os.environ.get("SIGNUP_DEV_RETURN_OTP") or "").strip() in ("1", "true", "yes"):
            out["dev_otp"] = otp
        return out

    def signup_flow_verify_email(self, email, otp):
        """PENDING_EMAIL → PENDING_ADMIN after valid OTP."""
        email_n = self._normalize_signup_email(email)
        otp = (otp or "").strip()
        if not email_n or len(otp) < 6:
            return {"ok": False, "error": "invalid_input"}
        want = self._hash_signup_otp(email_n, otp)
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT email_normalized, account_status, email_otp_hash, email_otp_expires_at
                FROM signup_sessions WHERE email_normalized = %s LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return {"ok": False, "error": "session_not_found"}
            st = row["account_status"] if hasattr(row, "keys") else None
            if st != "PENDING_EMAIL":
                conn.rollback()
                return {"ok": False, "error": "wrong_state", "account_status": st}
            got = row["email_otp_hash"] if hasattr(row, "keys") else None
            if not got or got != want:
                conn.rollback()
                return {"ok": False, "error": "invalid_otp"}
            cur.execute(
                """
                UPDATE signup_sessions SET
                    account_status = 'PENDING_ADMIN',
                    email_verified_at = NOW(),
                    email_otp_hash = NULL,
                    email_otp_expires_at = NULL,
                    updated_at = NOW()
                WHERE email_normalized = %s
                  AND account_status = 'PENDING_EMAIL'
                  AND email_otp_hash = %s
                  AND email_otp_expires_at > NOW()
                RETURNING id
                """,
                (email_n, want),
            )
            updated = cur.fetchone()
            if not updated:
                conn.rollback()
                return {"ok": False, "error": "invalid_or_expired_otp"}
            conn.commit()
            return {"ok": True, "message": "email_verified", "account_status": "PENDING_ADMIN"}
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err:
                return {"ok": False, "error": "signup_not_configured"}
            print(f"CentralDB signup_flow_verify_email: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def signup_flow_verify_email_google(self, email, verified_google_email):
        """PENDING_EMAIL → PENDING_ADMIN after Google ID token validated server-side (email must match token)."""
        email_n = self._normalize_signup_email(email)
        g_n = self._normalize_signup_email(verified_google_email)
        if not email_n or not g_n or email_n != g_n:
            return {"ok": False, "error": "google_email_mismatch"}
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT email_normalized, account_status
                FROM signup_sessions WHERE email_normalized = %s LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                conn.rollback()
                return {"ok": False, "error": "session_not_found"}
            st = row["account_status"] if hasattr(row, "keys") else None
            if st != "PENDING_EMAIL":
                conn.rollback()
                return {"ok": False, "error": "wrong_state", "account_status": st}
            cur.execute(
                """
                UPDATE signup_sessions SET
                    account_status = 'PENDING_ADMIN',
                    email_verified_at = NOW(),
                    email_otp_hash = NULL,
                    email_otp_expires_at = NULL,
                    updated_at = NOW()
                WHERE email_normalized = %s
                  AND account_status = 'PENDING_EMAIL'
                RETURNING id
                """,
                (email_n,),
            )
            updated = cur.fetchone()
            if not updated:
                conn.rollback()
                return {"ok": False, "error": "wrong_state"}
            conn.commit()
            return {"ok": True, "message": "email_verified", "account_status": "PENDING_ADMIN"}
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err:
                return {"ok": False, "error": "signup_not_configured"}
            print(f"CentralDB signup_flow_verify_email_google: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def _ensure_user_admin_link_requests_table(self):
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS user_admin_link_requests (
                    id UUID PRIMARY KEY,
                    email_normalized TEXT NOT NULL,
                    admin_id UUID NOT NULL,
                    user_bot_id TEXT NOT NULL,
                    user_api_key TEXT NOT NULL,
                    display_name TEXT,
                    fcm_token TEXT,
                    status TEXT NOT NULL DEFAULT 'pending',
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    updated_at TIMESTAMPTZ DEFAULT NOW()
                )
                """
            )
            cur.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_user_admin_link_req_pending
                ON user_admin_link_requests (email_normalized, user_bot_id, user_api_key)
                WHERE status = 'pending'
                """
            )
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB _ensure_user_admin_link_requests_table: {e}")
        finally:
            cur.close()

    def _signup_load_pending_admin_session(self, email_n):
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            try:
                cur.execute(
                    """
                    SELECT password_hash, account_status,
                           COALESCE(TRIM(first_name), '') AS fn,
                           COALESCE(TRIM(last_name), '') AS ln
                    FROM signup_sessions WHERE email_normalized = %s LIMIT 1
                    """,
                    (email_n,),
                )
            except Exception:
                conn.rollback()
                cur.execute(
                    "SELECT password_hash, account_status FROM signup_sessions WHERE email_normalized = %s LIMIT 1",
                    (email_n,),
                )
            srow = cur.fetchone()
            if not srow:
                conn.rollback()
                return {"ok": False, "error": "session_not_found"}
            st = srow["account_status"] if hasattr(srow, "keys") else None
            if st != "PENDING_ADMIN":
                conn.rollback()
                return {"ok": False, "error": "wrong_state", "account_status": st}
            pw_row = srow["password_hash"] if hasattr(srow, "keys") else None
            session_fn = ""
            session_ln = ""
            if hasattr(srow, "get"):
                session_fn = (srow.get("fn") or "").strip()
                session_ln = (srow.get("ln") or "").strip()
            conn.rollback()
            return {"ok": True, "password_hash": pw_row, "fn": session_fn, "ln": session_ln}
        except Exception as e:
            try:
                conn.rollback()
            except Exception:
                pass
            err = str(e).lower()
            if "signup_sessions" in err or "does not exist" in err:
                return {"ok": False, "error": "signup_not_configured"}
            print(f"CentralDB _signup_load_pending_admin_session: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def _signup_post_link_response_dict(self, user_id, admin):
        """Shared JSON shape for linked user (signup link-admin + directory accept)."""
        admin_id = admin.get("id")
        admin_name = admin.get("name") or ""
        databus_access_code = (admin.get("admin_access_code") or "").strip()
        connection_code = (admin.get("connection_code") or "").strip()
        uid_str = str(user_id)
        greet = ""
        full_name = ""
        username_display = ""
        display_mode = ""
        try:
            greet = self.get_user_first_name_for_user_id(uid_str) or ""
        except Exception:
            pass
        try:
            full_name = self.get_user_full_display_name_for_user_id(uid_str) or ""
        except Exception:
            pass
        try:
            username_display = self.get_user_username_for_user_id(uid_str) or ""
        except Exception:
            pass
        try:
            display_mode = self.get_user_display_mode_for_user_id(uid_str) or ""
        except Exception:
            pass
        return {
            "ok": True,
            "message": "ok",
            "admin_id": admin_id,
            "user_id": uid_str,
            "admin_name": admin_name,
            "databus_access_code": databus_access_code,
            "connection_code": connection_code,
            "account_status": "ACTIVE",
            "user_first_name": greet,
            "user_full_name": full_name,
            "user_username": username_display,
            "user_display_mode": display_mode,
        }

    def _signup_linked_payload_for_device(self, email_n, bot_id, api_key):
        """If this device is already linked for this signup email, return link-shaped dict (no ok/message keys)."""
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT u.id AS user_id, u.admin_id::text AS admin_id,
                       COALESCE(a.name, '') AS admin_name,
                       COALESCE(a.admin_access_code, '') AS databus_access_code,
                       COALESCE(a.connection_code, '') AS connection_code,
                       COALESCE(u.account_status, 'ACTIVE') AS account_status
                FROM users u
                INNER JOIN admins a ON a.id = u.admin_id
                WHERE u.bot_id = %s AND u.api_key = %s
                  AND LOWER(TRIM(COALESCE(u.email, ''))) = %s
                  AND COALESCE(u.bot_id, '') IS DISTINCT FROM 'dashboard'
                ORDER BY u.updated_at DESC NULLS LAST, u.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (bot_id, api_key, email_n),
            )
            row = cur.fetchone()
            if not row:
                return None
            st = str(row.get("account_status") or "ACTIVE").strip().upper()
            if st in ("PENDING_EMAIL", "PENDING_ADMIN", "PENDING"):
                return None
            user_id = row.get("user_id")
            admin_stub = {
                "id": row.get("admin_id"),
                "name": row.get("admin_name"),
                "admin_access_code": row.get("databus_access_code"),
                "connection_code": row.get("connection_code"),
            }
            d = self._signup_post_link_response_dict(user_id, admin_stub)
            d.pop("ok", None)
            d.pop("message", None)
            return d
        except Exception as e:
            print(f"CentralDB _signup_linked_payload_for_device: {e}")
            return None
        finally:
            cur.close()

    def _signup_finalize_link_to_admin(self, email_n, admin, bot_id, api_key, display_name, fcm_token, pw_row, session_fn, session_ln):
        admin_id = admin.get("id")
        combined = re.sub(r"\s+", " ", f"{session_fn} {session_ln}").strip()
        actual_display = combined if (session_fn or session_ln) else ((display_name or "").strip() or email_n)
        user_uname = combined if (session_fn or session_ln) else None
        user_id = self.upsert_user_from_bot(
            bot_id,
            api_key,
            admin_id,
            name=actual_display,
            email=email_n,
            fcm_token=fcm_token,
            username=user_uname,
        )
        if not user_id:
            return {"ok": False, "error": "link_failed"}
        if session_fn or session_ln:
            self.set_user_first_last_name(user_id, session_fn, session_ln)
        self._touch_user_password_and_active(user_id, password_hash_session=pw_row)
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("DELETE FROM signup_sessions WHERE email_normalized = %s", (email_n,))
            try:
                self._ensure_user_admin_link_requests_table()
                cur.execute(
                    "DELETE FROM user_admin_link_requests WHERE email_normalized = %s AND user_bot_id = %s AND user_api_key = %s",
                    (email_n, bot_id, api_key),
                )
            except Exception as e2:
                print(f"CentralDB _signup_finalize_link_to_admin cleanup requests: {e2}")
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB _signup_finalize_link_to_admin cleanup session: {e}")
        finally:
            cur.close()
        return self._signup_post_link_response_dict(user_id, admin)

    def signup_flow_link_admin(self, email, connection_code, bot_id, api_key, name=None, fcm_token=None):
        """After PENDING_ADMIN: connect via admin connection code + delete signup_sessions + set users ACTIVE."""
        email_n = self._normalize_signup_email(email)
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        connection_code = (connection_code or "").strip()
        if not email_n or not connection_code or not bot_id or not api_key:
            return {"ok": False, "error": "missing_fields"}
        sess = self._signup_load_pending_admin_session(email_n)
        if not sess.get("ok"):
            return sess
        admin = self.get_admin_by_connection_code(connection_code)
        if not admin:
            return {"ok": False, "error": "invalid_connection_code"}
        combined = re.sub(r"\s+", " ", f"{sess['fn']} {sess['ln']}").strip()
        display_name = combined if (sess["fn"] or sess["ln"]) else ((name or "").strip() or email_n)
        return self._signup_finalize_link_to_admin(
            email_n,
            admin,
            bot_id,
            api_key,
            display_name,
            fcm_token,
            sess["password_hash"],
            sess["fn"],
            sess["ln"],
        )

    def list_admins_for_signup_directory(self):
        """Public-safe list for signup UI: id + name only (capped)."""
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT id::text AS id, COALESCE(name, '') AS name
                FROM admins
                ORDER BY LOWER(COALESCE(NULLIF(TRIM(name), ''), NULLIF(TRIM(email), ''), id::text)) ASC
                LIMIT 100
                """
            )
            rows = cur.fetchall() or []
            out = []
            for row in rows:
                if hasattr(row, "get"):
                    out.append({"id": str(row.get("id") or ""), "name": str(row.get("name") or "").strip()})
                else:
                    out.append({"id": str(row[0]), "name": str(row[1] or "").strip()})
            return out
        except Exception as e:
            print(f"CentralDB list_admins_for_signup_directory: {e}")
            return []
        finally:
            cur.close()

    def get_admin_for_user_directory_link(self, admin_id_str):
        """Admin row for linking by UUID (choose-admin flow); same shape as get_admin_by_connection_code."""
        aid = (admin_id_str or "").strip()
        try:
            uuid.UUID(aid)
        except (ValueError, TypeError):
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id, name, connection_code, admin_access_code FROM admins WHERE id = %s::uuid LIMIT 1",
                (aid,),
            )
            row = cur.fetchone()
            if row and hasattr(row, "keys"):
                return {
                    "id": str(row["id"]),
                    "name": row["name"],
                    "connection_code": row["connection_code"],
                    "admin_access_code": (row.get("admin_access_code") or "").strip(),
                }
            if row:
                return {
                    "id": str(row[0]),
                    "name": row[1],
                    "connection_code": row[2],
                    "admin_access_code": (row[3] or "").strip() if len(row) > 3 else "",
                }
            return None
        except Exception as e:
            print(f"CentralDB get_admin_for_user_directory_link: {e}")
            return None
        finally:
            cur.close()

    def _signup_pending_directory_link_credentials(self, email_n):
        """If a directory link request is queued, return bot_id/api_key/admin label for sign-in resume."""
        self._ensure_user_admin_link_requests_table()
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT r.user_bot_id AS bot_id, r.user_api_key AS api_key,
                       COALESCE(NULLIF(TRIM(a.name), ''), '') AS pending_admin_name
                FROM user_admin_link_requests r
                LEFT JOIN admins a ON a.id = r.admin_id
                WHERE r.email_normalized = %s AND r.status = 'pending'
                ORDER BY r.created_at DESC NULLS LAST
                LIMIT 1
                """,
                (email_n,),
            )
            row = cur.fetchone()
            if not row:
                return None
            if hasattr(row, "get"):
                bid = (row.get("bot_id") or "").strip()
                key = (row.get("api_key") or "").strip()
                aname = (row.get("pending_admin_name") or "").strip()
            else:
                bid = (row[0] or "").strip()
                key = (row[1] or "").strip()
                aname = (row[2] or "").strip() if len(row) > 2 else ""
            if not bid or not key:
                return None
            return {"bot_id": bid, "api_key": key, "pending_admin_name": aname}
        except Exception as e:
            print(f"CentralDB _signup_pending_directory_link_credentials: {e}")
            return None
        finally:
            cur.close()

    def signup_submit_admin_link_request(self, email, admin_id, bot_id, api_key, name=None, fcm_token=None):
        """PENDING_ADMIN user submits a request for a chosen admin; admin accepts via dashboard later."""
        email_n = self._normalize_signup_email(email)
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        aid = (admin_id or "").strip()
        if not email_n or not bot_id or not api_key or not aid:
            return {"ok": False, "error": "missing_fields"}
        sess = self._signup_load_pending_admin_session(email_n)
        if not sess.get("ok"):
            return sess
        admin = self.get_admin_for_user_directory_link(aid)
        if not admin:
            return {"ok": False, "error": "invalid_admin"}
        self._ensure_user_admin_link_requests_table()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """
                DELETE FROM user_admin_link_requests
                WHERE email_normalized = %s AND user_bot_id = %s AND user_api_key = %s AND status = 'pending'
                """,
                (email_n, bot_id, api_key),
            )
            rid = str(uuid.uuid4())
            dn = (name or "").strip()[:200] or None
            ft = (fcm_token or "").strip() or None
            cur.execute(
                """
                INSERT INTO user_admin_link_requests (
                    id, email_normalized, admin_id, user_bot_id, user_api_key, display_name, fcm_token, status
                ) VALUES (%s::uuid, %s, %s::uuid, %s, %s, %s, %s, 'pending')
                """,
                (rid, email_n, aid, bot_id, api_key, dn, ft),
            )
            conn.commit()
            return {"ok": True, "message": "request_created", "request_id": rid, "admin_name": admin.get("name") or ""}
        except Exception as e:
            conn.rollback()
            err = str(e).lower()
            if "user_admin_link_requests" in err or "does not exist" in err:
                return {"ok": False, "error": "signup_not_configured"}
            print(f"CentralDB signup_submit_admin_link_request: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def signup_get_link_request_status(self, email, bot_id, api_key):
        """Poll: accepted (linked), pending (waiting on admin), or no_pending_request."""
        email_n = self._normalize_signup_email(email)
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not email_n or not bot_id or not api_key:
            return {"ok": False, "error": "missing_fields"}
        linked = self._signup_linked_payload_for_device(email_n, bot_id, api_key)
        if linked:
            out = {"ok": True, "status": "accepted"}
            out.update(linked)
            return out
        sess = self._signup_load_pending_admin_session(email_n)
        if not sess.get("ok"):
            err = sess.get("error") or "error"
            if err == "session_not_found":
                return {"ok": False, "error": "session_not_found"}
            if err == "wrong_state":
                return {"ok": False, "error": "wrong_state", "account_status": sess.get("account_status")}
            return {"ok": False, "error": err}
        self._ensure_user_admin_link_requests_table()
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT r.id::text AS request_id, r.admin_id::text AS admin_id, COALESCE(a.name, '') AS admin_name
                FROM user_admin_link_requests r
                INNER JOIN admins a ON a.id = r.admin_id
                WHERE r.email_normalized = %s AND r.user_bot_id = %s AND r.user_api_key = %s AND r.status = 'pending'
                LIMIT 1
                """,
                (email_n, bot_id, api_key),
            )
            row = cur.fetchone()
            if row:
                return {
                    "ok": True,
                    "status": "pending",
                    "request_id": row.get("request_id"),
                    "admin_id": row.get("admin_id"),
                    "admin_name": row.get("admin_name") or "",
                }
            return {"ok": True, "status": "no_pending_request"}
        except Exception as e:
            print(f"CentralDB signup_get_link_request_status: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def admin_list_pending_user_link_requests(self, access_code):
        """Admin dashboard: pending signup link requests for this access_code's admin."""
        admin = self.get_admin_by_access_code(access_code)
        if not admin:
            return {"ok": False, "error": "invalid_access_code", "requests": []}
        admin_id = admin.get("id")
        self._ensure_user_admin_link_requests_table()
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                """
                SELECT r.id::text AS request_id, r.email_normalized AS email,
                       COALESCE(r.display_name, '') AS display_name,
                       r.created_at
                FROM user_admin_link_requests r
                WHERE r.admin_id = %s::uuid AND r.status = 'pending'
                ORDER BY r.created_at ASC
                """,
                (admin_id,),
            )
            rows = cur.fetchall() or []
            req_list = []
            for row in rows:
                if hasattr(row, "get"):
                    ca = row.get("created_at")
                    req_list.append({
                        "request_id": row.get("request_id"),
                        "email": row.get("email"),
                        "display_name": row.get("display_name") or "",
                        "created_at": ca.isoformat() if ca else "",
                    })
            return {"ok": True, "requests": req_list}
        except Exception as e:
            print(f"CentralDB admin_list_pending_user_link_requests: {e}")
            return {"ok": False, "error": "database_error", "requests": []}
        finally:
            cur.close()

    def admin_accept_user_link_request(self, access_code, request_id):
        """Grant pending directory link request (same DB effect as signup link-admin)."""
        admin = self.get_admin_by_access_code(access_code)
        if not admin:
            return {"ok": False, "error": "invalid_access_code"}
        admin_id = admin.get("id")
        rid = (request_id or "").strip()
        try:
            uuid.UUID(rid)
        except (ValueError, TypeError):
            return {"ok": False, "error": "invalid_request_id"}
        self._ensure_user_admin_link_requests_table()
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        row = None
        try:
            cur.execute(
                """
                SELECT email_normalized, user_bot_id, user_api_key,
                       COALESCE(display_name, '') AS display_name, COALESCE(fcm_token, '') AS fcm_token
                FROM user_admin_link_requests
                WHERE id = %s::uuid AND admin_id = %s::uuid AND status = 'pending'
                LIMIT 1
                """,
                (rid, admin_id),
            )
            row = cur.fetchone()
        except Exception as e:
            print(f"CentralDB admin_accept_user_link_request fetch: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()
        if not row:
            return {"ok": False, "error": "request_not_found"}
        email_n = row["email_normalized"]
        bot_id = row["user_bot_id"]
        api_key = row["user_api_key"]
        disp = str((row.get("display_name") or "")).strip()
        fcm = str((row.get("fcm_token") or "")).strip()

        sess = self._signup_load_pending_admin_session(email_n)
        if not sess.get("ok"):
            out = {"ok": False, "error": sess.get("error") or "error"}
            if sess.get("account_status") is not None:
                out["account_status"] = sess.get("account_status")
            return out

        return self._signup_finalize_link_to_admin(
            email_n,
            admin,
            bot_id,
            api_key,
            disp or None,
            fcm or None,
            sess["password_hash"],
            sess["fn"],
            sess["ln"],
        )

    def admin_reject_user_link_request(self, access_code, request_id):
        """Mark a pending directory link request as rejected (admin declines)."""
        admin = self.get_admin_by_access_code(access_code)
        if not admin:
            return {"ok": False, "error": "invalid_access_code"}
        admin_id = admin.get("id")
        rid = (request_id or "").strip()
        try:
            uuid.UUID(rid)
        except (ValueError, TypeError):
            return {"ok": False, "error": "invalid_request_id"}
        self._ensure_user_admin_link_requests_table()
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute(
                """
                UPDATE user_admin_link_requests
                SET status = 'rejected', updated_at = NOW()
                WHERE id = %s::uuid AND admin_id = %s::uuid AND status = 'pending'
                """,
                (rid, admin_id),
            )
            if cur.rowcount == 0:
                conn.rollback()
                return {"ok": False, "error": "request_not_found"}
            conn.commit()
            return {"ok": True}
        except Exception as e:
            conn.rollback()
            print(f"CentralDB admin_reject_user_link_request: {e}")
            return {"ok": False, "error": "database_error"}
        finally:
            cur.close()

    def _touch_user_password_and_active(self, user_id, password_hash_session=None):
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            if password_hash_session:
                try:
                    cur.execute(
                        """
                        UPDATE users SET account_status = 'ACTIVE',
                            email_verified_at = COALESCE(email_verified_at, NOW()),
                            status_changed_at = NOW(),
                            password_hash = %s
                        WHERE id = %s::uuid
                        """,
                        (password_hash_session, user_id),
                    )
                except Exception:
                    cur.execute(
                        "UPDATE users SET updated_at = NOW() WHERE id = %s::uuid",
                        (user_id,),
                    )
            else:
                try:
                    cur.execute(
                        """
                        UPDATE users SET account_status = 'ACTIVE',
                            email_verified_at = COALESCE(email_verified_at, NOW()),
                            status_changed_at = NOW()
                        WHERE id = %s::uuid
                        """,
                        (user_id,),
                    )
                except Exception:
                    pass
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB _touch_user_password_and_active: {e}")
        finally:
            cur.close()

    def get_user_account_status_by_bot(self, bot_id, api_key):
        """Return account_status for this device user, or ACTIVE if column/table legacy."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT account_status FROM users WHERE bot_id = %s AND api_key = %s LIMIT 1",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if not row:
                return None
            return (row["account_status"] if hasattr(row, "keys") else row[0]) or "ACTIVE"
        except Exception:
            return "ACTIVE"
        finally:
            cur.close()

    def maintenance_cleanup_pending(self, hours_sessions=168, hours_users=24):
        """Delete stale signup_sessions and stale pending users (not dashboard). Returns counts.

        Default hours_sessions=168 (7×24h): signup_sessions rows older than 7 days are deleted when
        cleanup runs (e.g. daily Vercel Cron GET /api/maintenance_cleanup_pending with CRON_SECRET).
        """
        out = {"signup_sessions_deleted": 0, "users_deleted": 0}
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            try:
                cur.execute(
                    "DELETE FROM signup_sessions WHERE created_at < NOW() - (%s::int * INTERVAL '1 hour')",
                    (int(hours_sessions),),
                )
                out["signup_sessions_deleted"] = cur.rowcount
            except Exception as e:
                out["signup_sessions_error"] = str(e)
            try:
                cur.execute(
                    """
                    DELETE FROM users
                    WHERE bot_id IS DISTINCT FROM 'dashboard'
                      AND account_status IN ('PENDING_EMAIL', 'PENDING_ADMIN', 'PENDING')
                      AND created_at < NOW() - (%s::int * INTERVAL '1 hour')
                    """,
                    (int(hours_users),),
                )
                out["users_deleted"] = cur.rowcount
            except Exception as e:
                out["users_error"] = str(e)
            try:
                cur.execute("DELETE FROM desktop_link_codes WHERE expires_at <= NOW()")
                out["desktop_link_codes_deleted"] = cur.rowcount
            except Exception as e:
                out["desktop_link_codes_error"] = str(e)
            conn.commit()
        except Exception as e:
            conn.rollback()
            out["error"] = str(e)
        finally:
            cur.close()
        return out
