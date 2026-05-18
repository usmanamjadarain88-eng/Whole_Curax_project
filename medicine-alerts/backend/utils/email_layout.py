"""
Shared HTML shell for outbound CuraX emails (transactional OTP + scheduler alerts).
Table-based layout for Gmail / mobile clients; gradient header with pill + brand title.
"""
from __future__ import annotations

import html as html_mod
from typing import List, Optional, Tuple


def escape(s: str) -> str:
    return html_mod.escape((s or "").strip(), quote=True)


def apply_urgent_notification_headers(msg) -> None:
    """
    Ask mail apps to treat the message as important so new-mail notifications are more
    likely to pop up (heads-up). Still respects the user's OS / Gmail notification toggles.

    Uses Microsoft-style capital Importance and legacy X-Priority spellings many mobile
    clients (Outlook, Gmail heuristics, Samsung Mail) still read.

    Avoids Precedence: bulk and Auto-Submitted, which often suppress buzz / badge behavior.
    Works with email.message.EmailMessage and email.mime.multipart.MIMEMultipart.
    """
    # RFC-style + Exchange / Outlook de-facto (capital "High" matters for some parsers).
    msg["Importance"] = "High"
    msg["Priority"] = "urgent"
    msg["X-Priority"] = "1 (Highest)"
    msg["X-MSMail-Priority"] = "High"
    msg["MSMail-Priority"] = "High"
    # Exchange classification — helps some clients surface as personal / notify.
    msg["Sensitivity"] = "Personal"


def curax_email_html(
    content_rows_html: str,
    *,
    footer_meta: Optional[List[Tuple[str, str]]] = None,
) -> str:
    """
    Wrap inner card rows with outer gray background + gradient header.

    content_rows_html: one or more <tr>...</tr> fragments (cells inside the white card, below header).
    footer_meta: optional [(label, value_html), ...] — value_html may include safe markup (e.g. escaped emails).
    """
    header_tr = (
        '<tr><td style="padding:0;background-color:#2563eb;'
        'background-image:linear-gradient(90deg,#2563eb 0%,#22c55e 100%);">'
        '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">'
        "<tr>"
        '<td style="padding:18px 22px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
        '<span style="font-size:22px;line-height:1;vertical-align:middle;">💊</span>'
        '<span style="display:inline-block;font-size:17px;font-weight:700;color:#ffffff;'
        'margin-left:12px;line-height:22px;vertical-align:middle;">'
        "CuraX Medicine System"
        "</span>"
        "</td>"
        "</tr>"
        "</table>"
        "</td></tr>"
    )

    footer_html = ""
    if footer_meta:
        lines = []
        for label, value_html in footer_meta:
            lab = escape(label)
            lines.append(
                f'<div style="font-size:12px;color:#374151;margin-top:8px;line-height:1.45;">'
                f'<span style="color:#9ca3af;">{lab}:</span> {value_html}</div>'
            )
        footer_html = (
            '<tr><td style="padding:0 28px 24px 28px;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;">'
            '<hr style="border:none;border-top:1px solid #e5e7eb;margin:8px 0 14px 0;">'
            + "".join(lines)
            + "</td></tr>"
        )

    return (
        '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" '
        'style="margin:0;padding:24px 12px;background:#f4f5f7;">'
        '<tr><td align="center">'
        '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" '
        'style="max-width:520px;background:#ffffff;border-radius:12px;overflow:hidden;'
        'border:1px solid #e6e7eb;">'
        + header_tr
        + content_rows_html
        + footer_html
        + "</table>"
        "</td></tr></table>"
    )


def plain_body_to_html_paragraphs(text: str) -> str:
    """Turn plain text into stacked paragraphs (escaped)."""
    t = (text or "").strip()
    if not t:
        return ""
    parts = []
    for para in t.split("\n\n"):
        line = para.strip()
        if not line:
            continue
        inner = escape(line).replace("\n", "<br>")
        parts.append(
            f'<p style="margin:0 0 10px 0;font-size:14px;color:#374151;line-height:1.55;">{inner}</p>'
        )
    return "".join(parts)
