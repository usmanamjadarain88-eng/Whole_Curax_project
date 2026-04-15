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

    Optional: SIGNUP_SMTP_HOST (default smtp.gmail.com), SIGNUP_SMTP_PORT (default 465),
    SIGNUP_EMAIL_FROM (defaults to SIGNUP_SMTP_USER), SIGNUP_EMAIL_FROM_NAME,
    SIGNUP_OTP_EMAIL_SUBJECT, SIGNUP_EMAIL_REPLY_TO.
    """
    import smtplib
    import html as html_mod
    from email.mime.multipart import MIMEMultipart
    from email.mime.text import MIMEText
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
    from_addr = (os.environ.get("SIGNUP_EMAIL_FROM") or smtp_user).strip()
    from_name = (os.environ.get("SIGNUP_EMAIL_FROM_NAME") or "Curax system").strip()
    subject = (os.environ.get("SIGNUP_OTP_EMAIL_SUBJECT") or "Curax — your verification code").strip()
    reply_to = (os.environ.get("SIGNUP_EMAIL_REPLY_TO") or "").strip()
    to_addr = (to_addr or "").strip()
    if "@" not in to_addr:
        return False

    otp_esc = html_mod.escape((otp_plain or "").strip())
    name_esc = html_mod.escape(from_name)
    sign_off_plain = f"\n— {from_name}\n"
    sign_off_html = (
        "<p style=\"margin-top:20px;color:#555;font-size:13px;\">"
        f"This message was sent by <strong>{name_esc}</strong> for account sign-up.</p>"
    )
    text_body = (
        f"{from_name}\n\n"
        f"Your verification code is: {otp_plain}\n\n"
        "This code expires in 15 minutes. If you did not request this, you can ignore this email."
        f"{sign_off_plain}"
    )
    html_body = (
        f"<p style=\"color:#333;font-size:14px;\"><strong>{name_esc}</strong></p>"
        "<p>Your verification code is:</p>"
        f"<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px;\">{otp_esc}</p>"
        "<p>This code expires in 15 minutes.</p>"
        "<p>If you did not request this, you can ignore this email.</p>"
        f"{sign_off_html}"
    )

    msg = MIMEMultipart("alternative")
    msg["Subject"] = subject
    msg["From"] = formataddr((from_name, from_addr))
    msg["To"] = to_addr
    if reply_to and "@" in reply_to:
        msg["Reply-To"] = reply_to
    msg.attach(MIMEText(text_body, "plain", "utf-8"))
    msg.attach(MIMEText(html_body, "html", "utf-8"))

    with smtplib.SMTP_SSL(host, port) as server:
        server.login(smtp_user, smtp_password)
        server.sendmail(from_addr, [to_addr], msg.as_string())
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
        """True if public.signup_sessions exists (migration_signup_sessions.sql was applied)."""
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

    def create_desktop_link_code(self, access_code, expires_seconds=300):
        """Admin creates a one-time code for a user to link desktop to this admin (user view). Code valid 5 min; one-time use. Returns (code, admin_id, admin_name) or (None, None, None)."""
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

    def get_admin_by_desktop_link_code(self, code):
        """Validate desktop link code, return admin info and consume the code. Returns { admin_id, admin_name } or None."""
        code = (code or "").strip().upper()
        if not code:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT d.admin_id, a.name FROM desktop_link_codes d JOIN admins a ON a.id = d.admin_id WHERE d.code = %s AND d.expires_at > NOW() LIMIT 1",
                (code,),
            )
            row = cur.fetchone()
            if not row:
                return None
            admin_id = row["admin_id"] if hasattr(row, "keys") else row[0]
            admin_name = (row["name"] if hasattr(row, "keys") else row[1]) or "Admin"
            cur.execute("DELETE FROM desktop_link_codes WHERE code = %s", (code,))
            conn.commit()
            return {"admin_id": str(admin_id), "admin_name": admin_name}
        except Exception as e:
            conn.rollback()
            print(f"CentralDB get_admin_by_desktop_link_code: {e}")
            return None
        finally:
            cur.close()

    def create_user_desktop_link_code(self, bot_id, api_key, expires_seconds=300):
        """User (app) creates a one-time code for desktop to link to this user. Code valid 5 min; one-time use. Returns (code, user_id, user_name) or (None, None, None)."""
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        if not bot_id or not api_key:
            return None, None, None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            cur.execute(
                "SELECT id, name FROM users WHERE bot_id = %s AND api_key = %s LIMIT 1",
                (bot_id, api_key),
            )
            row = cur.fetchone()
            if not row:
                return None, None, None
            user_id = row["id"] if hasattr(row, "keys") else row[0]
            user_name = (row["name"] if hasattr(row, "keys") else row[1]) or "User"
            from datetime import timedelta
            expires_at = datetime.now(timezone.utc) + timedelta(seconds=expires_seconds)
            for _ in range(20):
                link_code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
                try:
                    cur.execute(
                        "INSERT INTO user_desktop_link_codes (code, user_id, expires_at) VALUES (%s, %s, %s)",
                        (link_code, user_id, expires_at),
                    )
                    if cur.rowcount:
                        conn.commit()
                        return link_code, str(user_id), user_name
                except Exception:
                    conn.rollback()
                    continue
            return None, None, None
        except Exception as e:
            conn.rollback()
            print(f"CentralDB create_user_desktop_link_code: {e}")
            return None, None, None
        finally:
            cur.close()

    def get_user_by_desktop_link_code(self, code):
        """Validate user desktop link code, return user info and admin info; consume the code.
        Returns { user_id, user_name, bot_id, api_key, admin_id, admin_name } or None.
        Sets users.desktop_linked_at when code is used so admin can only save for users who linked desktop."""
        code = (code or "").strip().upper()
        if not code:
            return None
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            try:
                cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS desktop_linked_at TIMESTAMPTZ DEFAULT NULL")
                conn.commit()
            except Exception:
                conn.rollback()
            cur.execute(
                """SELECT d.user_id, u.name AS user_name, u.bot_id, u.api_key, u.admin_id, a.name AS admin_name
                   FROM user_desktop_link_codes d
                   JOIN users u ON u.id = d.user_id
                   JOIN admins a ON a.id = u.admin_id
                   WHERE d.code = %s AND d.expires_at > NOW() LIMIT 1""",
                (code,),
            )
            row = cur.fetchone()
            if not row:
                return None
            user_id = row["user_id"] if hasattr(row, "keys") else row[0]
            user_name = (row["user_name"] if hasattr(row, "keys") else row[1]) or "User"
            bot_id = (row["bot_id"] if hasattr(row, "keys") else row[2]) or ""
            api_key = (row["api_key"] if hasattr(row, "keys") else row[3]) or ""
            admin_id = row["admin_id"] if hasattr(row, "keys") else row[4]
            admin_name = (row["admin_name"] if hasattr(row, "keys") else row[5]) or "Admin"
            try:
                cur.execute("UPDATE users SET desktop_linked_at = COALESCE(desktop_linked_at, NOW()) WHERE id = %s", (user_id,))
            except Exception:
                pass
            cur.execute("DELETE FROM user_desktop_link_codes WHERE code = %s", (code,))
            conn.commit()
            return {
                "user_id": str(user_id),
                "user_name": user_name,
                "bot_id": bot_id,
                "api_key": api_key,
                "admin_id": str(admin_id) if admin_id else "",
                "admin_name": admin_name,
            }
        except Exception as e:
            conn.rollback()
            print(f"CentralDB get_user_by_desktop_link_code: {e}")
            return None
        finally:
            cur.close()

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
    def upsert_admin_from_bot(self, bot_id, api_key, name=None, email=None, fcm_token=None):
        """Insert or update admin by (bot_id, api_key). Returns (admin_id, admin_access_code, connection_code) or (None, None, None).
        New admins get unique admin_access_code and connection_code; existing admins keep their codes.
        fcm_token: when provided, stored so backend can send push alerts to this admin.
        """
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        email_val = (email or "").strip()
        fcm = (fcm_token or "").strip() or None
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
                INSERT INTO admins (name, email, bot_id, api_key, admin_access_code, connection_code, fcm_token, is_admin, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, true, NOW())
                ON CONFLICT (bot_id, api_key)
                DO UPDATE SET name = COALESCE(EXCLUDED.name, admins.name),
                              email = COALESCE(EXCLUDED.email, admins.email),
                              admin_access_code = COALESCE(admins.admin_access_code, EXCLUDED.admin_access_code),
                              connection_code = COALESCE(admins.connection_code, EXCLUDED.connection_code),
                              fcm_token = COALESCE(NULLIF(TRIM(EXCLUDED.fcm_token), ''), admins.fcm_token),
                              is_admin = true,
                              updated_at = NOW()
                RETURNING id, admin_access_code, connection_code
                """,
                (name or "", email_val or "", bot_id, api_key, access_code, connection_code, fcm),
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

    def update_admin_bot_by_access_code(self, access_code, bot_id, api_key, name=None, email=None, fcm_token=None):
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
                email = COALESCE(NULLIF(%s, ''), email), fcm_token = COALESCE(NULLIF(%s, ''), fcm_token), is_admin = true, updated_at = NOW()
                WHERE admin_access_code = %s
                RETURNING id, admin_access_code, connection_code
                """,
                (bot_id, api_key, name or "", email_val or "", fcm or "", code),
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
                """SELECT u.id AS user_id, u.admin_id, u.name AS user_name, a.bot_id AS admin_bot_id, a.api_key AS admin_api_key
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
                }
            return {
                "user_id": str(row[0]),
                "admin_id": str(row[1]),
                "user_name": (row[2] or "").strip() or "User",
                "admin_bot_id": (row[3] or "").strip(),
                "admin_api_key": (row[4] or "").strip(),
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
        conn = self._ensure_conn()
        cur = conn.cursor(cursor_factory=RealDictCursor) if RealDictCursor else conn.cursor()
        try:
            rows = None
            variant = 0  # 0=id,name,email,bot,api,created,desktop | 1=no email | 2=no desktop_linked_at
            for variant, sql in enumerate((
                "SELECT id, name, email, bot_id, api_key, created_at, desktop_linked_at FROM users WHERE admin_id = %s::uuid AND bot_id != 'dashboard' ORDER BY created_at DESC",
                "SELECT id, name, bot_id, api_key, created_at, desktop_linked_at FROM users WHERE admin_id = %s::uuid AND bot_id != 'dashboard' ORDER BY created_at DESC",
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
                    dlinked = row.get("desktop_linked_at")
                    em = (row.get("email") or "").strip() if variant == 0 else ""
                    result.append({
                        "id": str(row["id"]), "name": row["name"] or "", "email": em,
                        "bot_id": row["bot_id"] or "", "api_key": row["api_key"] or "",
                        "desktop_linked": dlinked is not None,
                    })
                else:
                    if variant == 0:
                        em = (row[2] or "").strip() if len(row) > 2 else ""
                        dlinked = row[6] if len(row) > 6 else None
                        result.append({
                            "id": str(row[0]), "name": row[1] or "", "email": em,
                            "bot_id": row[3] or "", "api_key": row[4] or "",
                            "desktop_linked": dlinked is not None,
                        })
                    elif variant == 1:
                        dlinked = row[5] if len(row) > 5 else None
                        result.append({
                            "id": str(row[0]), "name": row[1] or "", "email": "",
                            "bot_id": row[2] or "", "api_key": row[3] or "",
                            "desktop_linked": dlinked is not None,
                        })
                    else:
                        result.append({
                            "id": str(row[0]), "name": row[1] or "", "email": "",
                            "bot_id": str(row[2] or ""), "api_key": str(row[3] or ""),
                            "desktop_linked": False,
                        })
            return result
        except Exception as e:
            print(f"CentralDB get_all_users_by_admin_id: {e}")
            return []
        finally:
            cur.close()

    def user_has_desktop_linked(self, user_id):
        """True if this user has linked a desktop at least once (desktop_linked_at set)."""
        if not user_id:
            return False
        conn = self._ensure_conn()
        cur = conn.cursor()
        try:
            cur.execute("SELECT 1 FROM users WHERE id = %s::uuid AND desktop_linked_at IS NOT NULL LIMIT 1", (user_id,))
            return cur.fetchone() is not None
        except Exception:
            return False
        finally:
            cur.close()

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
        return out

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

    def signup_flow_start(self, email, password, first_name="", last_name=""):
        """Create/update signup_sessions row (NOT users — users row is created at /signup/link-admin).

        Until email + admin link complete, pending signups live only in signup_sessions
        (account_status PENDING_EMAIL, then PENDING_ADMIN). The users table stays empty for that email.

        Same email may call /signup/start again while PENDING_EMAIL with the *same* password (OTP
        refresh / resend). PENDING_ADMIN or PENDING_EMAIL with a different password returns
        email_signup_in_progress. If the email already exists on admins or users (finished
        registration), returns email_already_registered and no mail.

        Optional first_name / last_name are stored when signup_sessions has those columns
        (see migration_signup_sessions_names.sql).
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
                return {"ok": False, "error": "signup_not_configured", "detail": "Run migration_signup_sessions.sql"}
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
                return {"ok": True, "account_phase": "pending_admin"}
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

        return {
            "ok": True,
            "account_phase": "active",
            "bot_id": str(urow.get("bot_id") or "").strip(),
            "api_key": str(urow.get("api_key") or "").strip(),
            "admin_id": str(urow.get("admin_id") or "").strip(),
            "admin_name": str(urow.get("admin_name") or "").strip(),
            "databus_access_code": str(urow.get("databus_access_code") or "").strip(),
            "connection_code": str(urow.get("connection_code") or "").strip(),
        }

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

    def signup_flow_link_admin(self, email, connection_code, bot_id, api_key, name=None, fcm_token=None):
        """After PENDING_ADMIN: same as connect-to-admin + delete signup_sessions + set users ACTIVE/password if columns exist."""
        email_n = self._normalize_signup_email(email)
        bot_id = (bot_id or "").strip()
        api_key = (api_key or "").strip()
        connection_code = (connection_code or "").strip()
        if not email_n or not connection_code or not bot_id or not api_key:
            return {"ok": False, "error": "missing_fields"}
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
        finally:
            cur.close()

        admin = self.get_admin_by_connection_code(connection_code)
        if not admin:
            return {"ok": False, "error": "invalid_connection_code"}
        admin_id = admin.get("id")
        admin_name = admin.get("name") or ""

        combined = re.sub(r"\s+", " ", f"{session_fn} {session_ln}").strip()
        display_name = combined if (session_fn or session_ln) else ((name or "").strip() or email_n)
        user_uname = combined if (session_fn or session_ln) else None

        user_id = self.upsert_user_from_bot(
            bot_id,
            api_key,
            admin_id,
            name=display_name,
            email=email_n,
            fcm_token=fcm_token,
            username=user_uname,
        )
        if not user_id:
            return {"ok": False, "error": "link_failed"}

        self._touch_user_password_and_active(user_id, password_hash_session=pw_row)

        cur = conn.cursor()
        try:
            cur.execute("DELETE FROM signup_sessions WHERE email_normalized = %s", (email_n,))
            conn.commit()
        except Exception as e:
            conn.rollback()
            print(f"CentralDB signup_flow_link_admin cleanup session: {e}")
        finally:
            cur.close()

        databus_access_code = (admin.get("admin_access_code") or "").strip()
        return {
            "ok": True,
            "message": "ok",
            "admin_id": admin_id,
            "user_id": user_id,
            "admin_name": admin_name,
            "databus_access_code": databus_access_code,
            "account_status": "ACTIVE",
        }

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
            conn.commit()
        except Exception as e:
            conn.rollback()
            out["error"] = str(e)
        finally:
            cur.close()
        return out
