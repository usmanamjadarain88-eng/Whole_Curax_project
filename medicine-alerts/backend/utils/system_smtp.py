"""
Send alert/notification email using server SIGNUP_SMTP_* (same Curax system mailbox as OTP).
Recipient is caller-defined (e.g. missed_dose_escalation.family_email).
"""
from __future__ import annotations

import datetime
import os
import re
import smtplib
import ssl
from email.message import EmailMessage
from email.policy import SMTP
from email.utils import formataddr

from utils.email_layout import (
    apply_urgent_notification_headers,
    curax_email_html,
    escape as curax_esc,
    plain_body_to_html_paragraphs,
)

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _smtp_settings():
    smtp_user = (os.environ.get("SIGNUP_SMTP_USER") or "").strip()
    smtp_password = (os.environ.get("SIGNUP_SMTP_PASSWORD") or "").strip()
    if not smtp_user or not smtp_password:
        return None
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
    reply_to = (os.environ.get("SIGNUP_EMAIL_REPLY_TO") or "").strip()
    return {
        "user": smtp_user,
        "password": smtp_password,
        "host": host,
        "port": port,
        "timeout": smtp_timeout,
        "from_addr": from_addr,
        "from_name": from_name,
        "reply_to": reply_to,
    }


def send_system_notification_email(to_addr: str, subject: str, body: str) -> bool:
    """Deliver one HTML notification to *to_addr* via SIGNUP_SMTP_* env vars."""
    cfg = _smtp_settings()
    if not cfg:
        return False
    to_addr = (to_addr or "").strip()
    if not _EMAIL_RE.match(to_addr):
        return False
    subject = (subject or "Curax alert").strip()
    body = (body or "").strip()

    body_html = plain_body_to_html_paragraphs(body)
    if not body_html.strip():
        body_html = (
            '<p style="margin:0;font-size:14px;color:#374151;line-height:1.55;">'
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
    html_body = curax_email_html(
        content_rows,
        footer_meta=[
            ("Time", curax_esc(sent_ts)),
            ("System", curax_esc("CuraX Intelligent Medicine System")),
            ("Recipients", f'<span style="color:#2563eb;">{curax_esc(to_addr)}</span>'),
        ],
    )
    text_body = f"{subject}\n\n{body}\n\n— {cfg['from_name']}\n"

    msg = EmailMessage(policy=SMTP)
    msg["Subject"] = subject
    msg["From"] = formataddr((cfg["from_name"], cfg["from_addr"]))
    msg["To"] = to_addr
    if cfg["reply_to"] and "@" in cfg["reply_to"]:
        msg["Reply-To"] = cfg["reply_to"]
    apply_urgent_notification_headers(msg)
    msg.set_content(text_body, subtype="plain", charset="utf-8")
    msg.add_alternative(html_body, subtype="html", charset="utf-8")

    try:
        host = cfg["host"]
        port = cfg["port"]
        timeout = cfg["timeout"]
        if port == 587:
            with smtplib.SMTP(host, port, timeout=timeout) as server:
                server.ehlo()
                server.starttls(context=ssl.create_default_context())
                server.ehlo()
                server.login(cfg["user"], cfg["password"])
                server.send_message(msg)
        else:
            with smtplib.SMTP_SSL(host, port, timeout=timeout) as server:
                server.login(cfg["user"], cfg["password"])
                server.send_message(msg)
        return True
    except Exception as e:
        print(f"[system_smtp] send failed: {e}")
        return False
