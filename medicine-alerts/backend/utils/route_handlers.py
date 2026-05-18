"""
Stateless route logic for Vercel serverless.
Returns (status_code, body); body is dict|list|str|None (204).
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import threading
import time
import uuid

from central_db import EmailAlreadyUsedError

from utils.databus import notify_databus
from utils.db import get_db
from utils.scheduler_shim import trigger_alert_checks_for_admin


def _verify_google_signin_id_token(id_token_jwt: str, expected_audience: str) -> dict:
    """
    Validate a Google Sign-In ID token via Google's tokeninfo endpoint.
    expected_audience must be the OAuth Web client ID (same as Android default_web_client_id).
    Returns {"ok": True, "email": str} or {"ok": False, "error": str}.
    """
    import urllib.parse
    import urllib.request

    if not (id_token_jwt or "").strip() or not (expected_audience or "").strip():
        return {"ok": False, "error": "invalid_input"}
    url = "https://oauth2.googleapis.com/tokeninfo?id_token=" + urllib.parse.quote(
        id_token_jwt.strip(), safe=""
    )
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "CuraxSignup/1"})
        with urllib.request.urlopen(req, timeout=12) as resp:
            raw = resp.read().decode()
        data = json.loads(raw)
    except Exception:
        return {"ok": False, "error": "invalid_google_token"}
    aud = (data.get("aud") or "").strip()
    if aud != expected_audience.strip():
        return {"ok": False, "error": "invalid_google_token"}
    try:
        exp = data.get("exp")
        if exp is not None and int(exp) < int(time.time()):
            return {"ok": False, "error": "invalid_google_token"}
    except (TypeError, ValueError):
        pass
    email = (data.get("email") or "").strip()
    if not email or "@" not in email:
        return {"ok": False, "error": "invalid_google_token"}
    ev = data.get("email_verified")
    if ev is False or ev == "false":
        return {"ok": False, "error": "invalid_google_token"}
    return {"ok": True, "email": email}


# Public OAuth client IDs from Firebase google-services (curax-bfacb). Env GOOGLE_SIGNIN_WEB_CLIENT_ID overrides / prepends.
_CURAX_GOOGLE_WEB_CLIENT_IDS_DEFAULT = (
    "428598576836-lgo255b9oqhejb3vf91tn8la5cor1lcl.apps.googleusercontent.com",
    "428598576836-mjv3b8l0vmskvmbvkv0n19n69oim31ol.apps.googleusercontent.com",
)


def _google_signin_audiences_for_verify():
    out = []
    seen = set()
    env = (os.environ.get("GOOGLE_SIGNIN_WEB_CLIENT_ID") or "").strip()
    if env:
        out.append(env)
        seen.add(env)
    for x in _CURAX_GOOGLE_WEB_CLIENT_IDS_DEFAULT:
        if x not in seen:
            out.append(x)
            seen.add(x)
    return out


def _verify_facebook_user_access_token(access_token: str) -> dict:
    """
    Validate a Facebook user access token for this app (FACEBOOK_APP_ID + FACEBOOK_APP_SECRET),
    then load email from /me. Returns {"ok": True, "email": str} or {"ok": False, "error": str}.
    """
    import urllib.parse
    import urllib.request

    token = (access_token or "").strip()
    if not token:
        return {"ok": False, "error": "invalid_input"}
    app_id = (os.environ.get("FACEBOOK_APP_ID") or "").strip()
    app_secret = (os.environ.get("FACEBOOK_APP_SECRET") or "").strip()
    if not app_id or not app_secret:
        return {"ok": False, "error": "facebook_oauth_not_configured"}
    app_access = f"{app_id}|{app_secret}"
    dbg_url = "https://graph.facebook.com/debug_token?" + urllib.parse.urlencode(
        {"input_token": token, "access_token": app_access}
    )
    try:
        req = urllib.request.Request(dbg_url, headers={"User-Agent": "CuraxSignup/1"})
        with urllib.request.urlopen(req, timeout=12) as resp:
            raw = resp.read().decode()
        dbg = json.loads(raw)
    except Exception:
        return {"ok": False, "error": "invalid_facebook_token"}
    data = dbg.get("data") or {}
    if not data.get("is_valid"):
        return {"ok": False, "error": "invalid_facebook_token"}
    if str(data.get("app_id") or "") != app_id:
        return {"ok": False, "error": "invalid_facebook_token"}
    me_url = "https://graph.facebook.com/me?" + urllib.parse.urlencode(
        {"fields": "email", "access_token": token}
    )
    try:
        req2 = urllib.request.Request(me_url, headers={"User-Agent": "CuraxSignup/1"})
        with urllib.request.urlopen(req2, timeout=12) as resp2:
            raw2 = resp2.read().decode()
        me = json.loads(raw2)
    except Exception:
        return {"ok": False, "error": "invalid_facebook_token"}
    email = (me.get("email") or "").strip()
    if not email or "@" not in email:
        return {"ok": False, "error": "facebook_email_required"}
    return {"ok": True, "email": email}


def root(body, query, headers):
    """So visiting the backend URL in a browser shows something instead of 404."""
    from utils import dev_router

    return (
        200,
        {
            "status": "ok",
            "message": "Medicine Alerts API",
            "routes": dev_router.public_route_index(),
            "docs": "Each route uses the listed HTTP method on that path. On Vercel, pretty URLs and /api/<function_stem> are both routed through main.py → dev_router.",
        },
    )
def health(body, query, headers):
    """Health check for Railway/monitoring."""
    db = get_db()
    db_ok = bool(db and db.is_available())
    signup_tbl = False
    if db_ok:
        try:
            signup_tbl = db.has_signup_sessions_table()
        except Exception:
            signup_tbl = False
    return (
        200,
        {
            "status": "ok",
            "database": "connected" if db_ok else "disconnected",
            "signup_sessions_table": signup_tbl,
        },
    )


# ---- Auth / credentials ----
def save_credentials(body, query, headers):
    """POST { bot_id, api_key, role?, access_code? (for admin), fcm_token? } ΓåÆ admins or users.
    If role=admin and access_code is sent, update that admin's bot_id/api_key (so app links to desktop-created admin).
    """
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    role = (data.get("role") or "user").strip().lower()
    access_code = (data.get("access_code") or "").strip()
    fcm_token = (data.get("fcm_token") or "").strip()
    name = (data.get("name") or "").strip()
    email = (data.get("email") or "").strip()
    desktop_password = (data.get("desktop_password") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    deleted_info = getattr(db, "get_deleted_user_notification", None) and db.get_deleted_user_notification(bot_id, api_key)
    if deleted_info:
        return (410, {
            "reason": "deleted_by_admin",
            "message": deleted_info.get("message", "The admin has removed you from their account."),
        })
    if role == "admin":
        try:
            if access_code:
                admin_id, admin_access_code, connection_code = db.update_admin_bot_by_access_code(
                    access_code,
                    bot_id,
                    api_key,
                    name=name,
                    email=email,
                    fcm_token=fcm_token or None,
                    desktop_password_plain=desktop_password or None,
                )
                if admin_id:
                    notify_databus(admin_access_code or access_code)
                    return (200, {"message": "ok", "admin_id": admin_id, "admin_access_code": admin_access_code or None, "connection_code": connection_code or None})
            admin_id, admin_access_code, connection_code = db.upsert_admin_from_bot(
                bot_id,
                api_key,
                name=name,
                email=email,
                fcm_token=fcm_token or None,
                desktop_password_plain=desktop_password or None,
            )
            if admin_access_code:
                notify_databus(admin_access_code)
            return (200, {"message": "ok", "admin_id": admin_id, "admin_access_code": admin_access_code or None, "connection_code": connection_code or None})
        except EmailAlreadyUsedError:
            return (409, {"message": "An admin with this email already exists. Delete the existing admin first."})
    admin_id = data.get("admin_id")
    if not admin_id:
        return (400, {"message": "admin_id required for role=user"})
    user_id = db.upsert_user_from_bot(bot_id, api_key, admin_id, name=name, fcm_token=fcm_token or None)
    return (200, {"message": "ok", "user_id": user_id})


def admin_mobile_sign_in_start(body, query, headers):
    """POST { email, password } → challenge_token + OTP emailed (admin mobile sign-in)."""
    data = body or {}
    email = (data.get("email") or "").strip()
    password = (data.get("password") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.admin_mobile_sign_in_start(email, password)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err in ("admin_mobile_login_not_configured", "database_error"):
            code = 503
        elif err == "unknown_admin_email":
            code = 404
        elif err == "invalid_credentials":
            code = 401
        out = {"message": err}
        if r.get("detail"):
            out["detail"] = r["detail"]
        return (code, out)
    return (200, {k: v for k, v in r.items() if k != "ok"})


def signup_sign_in_verify(body, query, headers):
    """POST { email, otp } → active user session fields after sign-in email verify."""
    data = body or {}
    email = (data.get("email") or "").strip()
    otp = (data.get("otp") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    try:
        r = db.signup_sign_in_verify_otp(email, otp)
    except Exception as e:
        print(f"signup_sign_in_verify: {e}")
        return (500, {"message": "database_error", "detail": str(e)[:300]})
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err in ("signin_verify_not_configured", "database_error"):
            code = 503
        elif err in ("invalid_or_expired", "invalid_input"):
            code = 401
        elif err == "user_missing":
            code = 404
        out = {"message": err}
        if r.get("detail"):
            out["detail"] = r["detail"]
        return (code, out)
    phase = r.get("account_phase") or "active"
    out = {"message": "ok", "account_phase": phase}
    if r.get("email"):
        out["email"] = r["email"]
    for k in (
        "bot_id",
        "api_key",
        "admin_id",
        "admin_name",
        "databus_access_code",
        "connection_code",
    ):
        out[k] = r.get(k) or ""
    return (200, out)


def admin_mobile_sign_in_verify(body, query, headers):
    """POST { challenge_token, otp } → admin_access_code, connection_code, name, email."""
    data = body or {}
    challenge_token = (data.get("challenge_token") or "").strip()
    otp = (data.get("otp") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.admin_mobile_sign_in_verify(challenge_token, otp)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err in ("admin_mobile_login_not_configured", "database_error"):
            code = 503
        elif err in ("challenge_not_found", "invalid_otp"):
            code = 401
        elif err == "otp_expired":
            code = 410
        elif err == "admin_missing":
            code = 404
        return (code, {"message": err})
    return (200, {k: v for k, v in r.items() if k != "ok"})


def admin_email_signup_start(body, query, headers):
    """POST { email, password } → OTP emailed; creates admin row after /admin/email-signup/verify."""
    data = body or {}
    email = (data.get("email") or "").strip()
    password = (data.get("password") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.admin_email_signup_start(email, password)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err in ("admin_email_signup_not_configured", "database_error"):
            code = 503
        elif err == "email_already_registered":
            code = 409
        out = {"message": err}
        if r.get("detail"):
            out["detail"] = r["detail"]
        return (code, out)
    return (200, {k: v for k, v in r.items() if k != "ok"})


def admin_email_signup_verify(body, query, headers):
    """POST { email, otp } → new administrator on central DB."""
    data = body or {}
    email = (data.get("email") or "").strip()
    otp = (data.get("otp") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.admin_email_signup_verify(email, otp)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err in ("admin_email_signup_not_configured", "database_error"):
            code = 503
        elif err in ("session_not_found", "invalid_otp"):
            code = 401
        elif err == "otp_expired":
            code = 410
        elif err == "email_already_registered":
            code = 409
        return (code, {"message": err})
    return (200, {k: v for k, v in r.items() if k != "ok"})


def connect_to_admin(body, query, headers):
    """POST { connection_code, bot_id, api_key, name?, email? } ΓåÆ link this app (user) to the admin with that connection_code.
    If email is sent, any previous user row for the same admin+email (e.g. old install) is removed so the same person has only one entry.
    """
    data = body
    connection_code = (data.get("connection_code") or "").strip()
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    name = (data.get("name") or "").strip()
    email = (data.get("email") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not connection_code or not bot_id or not api_key:
        return (400, {"message": "connection_code, bot_id and api_key required"})
    admin = db.get_admin_by_connection_code(connection_code)
    if not admin:
        return (404, {"message": "Invalid connection code"})
    admin_id = admin.get("id")
    admin_name = admin.get("name") or ""
    user_id = db.upsert_user_from_bot(bot_id, api_key, admin_id, name=name, email=email or None)
    if not user_id:
        return (500, {"message": "Failed to link user"})
    # Same room key as desktop/admin app WebSocket (NOT connection_code ΓÇö data bus uses admin_access_code).
    databus_access_code = (admin.get("admin_access_code") or "").strip()
    return (200, {
        "message": "ok",
        "admin_id": admin_id,
        "user_id": user_id,
        "admin_name": admin_name,
        "databus_access_code": databus_access_code,
    })


# ---- User signup flow (email -> OTP -> admin code); additive ----
def signup_start(body, query, headers):
    """POST { email, password, first_name?, last_name? } -> OTP issued (see server log); optional dev_otp if SIGNUP_DEV_RETURN_OTP=1."""
    data = body or {}
    email = (data.get("email") or "").strip()
    password = (data.get("password") or "").strip()
    first_name = (data.get("first_name") or "").strip()
    last_name = (data.get("last_name") or "").strip()
    google_id_token = (data.get("google_id_token") or "").strip()
    facebook_access_token = (data.get("facebook_access_token") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if google_id_token:
        vr = {"ok": False, "error": "invalid_google_token"}
        for aud in _google_signin_audiences_for_verify():
            vr = _verify_google_signin_id_token(google_id_token, aud)
            if vr.get("ok"):
                break
        if not vr.get("ok"):
            return (401, {"message": vr.get("error", "invalid_google_token")})
        g_email = (vr.get("email") or "").strip()
        if email and db._normalize_signup_email(email) != db._normalize_signup_email(g_email):
            return (400, {"message": "google_email_mismatch"})
        r = db.signup_flow_resend_otp_pending_email_oauth(g_email)
    elif facebook_access_token:
        fr = _verify_facebook_user_access_token(facebook_access_token)
        if fr.get("error") == "facebook_oauth_not_configured":
            return (503, {"message": "facebook_oauth_not_configured"})
        if not fr.get("ok"):
            return (401, {"message": fr.get("error", "invalid_facebook_token")})
        fb_email = (fr.get("email") or "").strip()
        if email and db._normalize_signup_email(email) != db._normalize_signup_email(fb_email):
            return (400, {"message": "facebook_email_mismatch"})
        r = db.signup_flow_resend_otp_pending_email_oauth(fb_email)
    else:
        r = db.signup_flow_start(email, password, first_name=first_name, last_name=last_name)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 503 if err == "signup_not_configured" else 400
        if err in ("email_already_registered", "email_signup_in_progress"):
            code = 409
        if err == "session_not_found":
            code = 404
        if err == "wrong_state":
            code = 409
        payload = {"message": err, "detail": r.get("detail")}
        if err == "signup_not_configured":
            payload["hint"] = (
                "Run central_schema.sql on Postgres (signup_sessions table). "
                "Until then, /signup/start cannot store pending signups."
            )
        if err == "email_already_registered" and r.get("detail"):
            payload["hint"] = r["detail"]
        if err == "email_signup_in_progress" and r.get("detail"):
            payload["hint"] = r["detail"]
        return (code, payload)
    out = {
        "message": r.get("message", "ok"),
        "pending_registration": True,
        "email_sent": bool(r.get("email_sent")),
    }
    if r.get("dev_otp"):
        out["dev_otp"] = r["dev_otp"]
    return (200, out)


def signup_sign_in(body, query, headers):
    """POST { email, password } -> pending_email | pending_admin | active (with bot_id, api_key, admin fields)."""
    data = body or {}
    google_id_token = (data.get("google_id_token") or "").strip()
    facebook_access_token = (data.get("facebook_access_token") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if google_id_token:
        vr = {"ok": False, "error": "invalid_google_token"}
        for aud in _google_signin_audiences_for_verify():
            vr = _verify_google_signin_id_token(google_id_token, aud)
            if vr.get("ok"):
                break
        if not vr.get("ok"):
            return (401, {"message": vr.get("error", "invalid_google_token")})
        r = db.signup_sign_in_oauth_email(vr.get("email") or "")
    elif facebook_access_token:
        fr = _verify_facebook_user_access_token(facebook_access_token)
        if fr.get("error") == "facebook_oauth_not_configured":
            return (503, {"message": "facebook_oauth_not_configured"})
        if not fr.get("ok"):
            return (401, {"message": fr.get("error", "invalid_facebook_token")})
        r = db.signup_sign_in_oauth_email(fr.get("email") or "")
    else:
        email = (data.get("email") or "").strip()
        password = (data.get("password") or "").strip()
        r = db.signup_sign_in(email, password)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err == "invalid_password":
            code = 401
        if err == "unknown_email":
            code = 404
        if err == "password_not_set":
            code = 403
        if err == "account_incomplete":
            code = 409
        msg = err
        if err == "unknown_email":
            msg = "No account found for this email and password."
        if err == "invalid_password":
            msg = "Incorrect password for this email."
        payload = {"message": msg, "detail": r.get("detail"), "account_status": r.get("account_status")}
        return (code, payload)
    phase = r.get("account_phase") or ""
    out = {"message": "ok", "account_phase": phase}
    if r.get("email"):
        out["email"] = r["email"]
    if phase == "signin_verify":
        out["email_sent"] = bool(r.get("email_sent"))
        if r.get("dev_otp"):
            out["dev_otp"] = r["dev_otp"]
    if phase == "active":
        for k in (
            "bot_id",
            "api_key",
            "admin_id",
            "admin_name",
            "databus_access_code",
            "connection_code",
        ):
            out[k] = r.get(k) or ""
    if phase == "pending_admin":
        for k in ("bot_id", "api_key", "pending_admin_name"):
            v = r.get(k)
            if v:
                out[k] = v
    return (200, out)


def signup_verify_email(body, query, headers):
    """POST { email, otp } or { email, google_id_token } -> session moves to PENDING_ADMIN."""
    data = body or {}
    email = (data.get("email") or "").strip()
    otp = (data.get("otp") or "").strip()
    google_id_token = (data.get("google_id_token") or "").strip()
    facebook_access_token = (data.get("facebook_access_token") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if google_id_token:
        vr = {"ok": False, "error": "invalid_google_token"}
        for aud in _google_signin_audiences_for_verify():
            vr = _verify_google_signin_id_token(google_id_token, aud)
            if vr.get("ok"):
                break
        if not vr.get("ok"):
            return (400, {"message": vr.get("error", "invalid_google_token")})
        r = db.signup_flow_verify_email_google(email, vr.get("email") or "")
    elif facebook_access_token:
        fr = _verify_facebook_user_access_token(facebook_access_token)
        if fr.get("error") == "facebook_oauth_not_configured":
            return (503, {"message": "facebook_oauth_not_configured"})
        if not fr.get("ok"):
            return (400, {"message": fr.get("error", "invalid_facebook_token")})
        r = db.signup_flow_verify_email_google(email, fr.get("email") or "")
    else:
        r = db.signup_flow_verify_email(email, otp)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 503 if err == "signup_not_configured" else 400
        if err in ("session_not_found",):
            code = 404
        if err in ("wrong_state",):
            code = 409
        if err == "google_email_mismatch":
            code = 400
        return (code, {"message": err, "account_status": r.get("account_status")})
    return (200, {"message": r.get("message", "ok"), "account_status": r.get("account_status")})


def password_reset_start(body, query, headers):
    """POST { email } -> OTP emailed for linked ACTIVE users (generic 200 if unknown)."""
    data = body or {}
    email = (data.get("email") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.password_reset_start(email)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 503 if err == "password_reset_not_configured" else 400
        payload = {"message": err}
        if r.get("detail"):
            payload["detail"] = r["detail"]
        return (code, payload)
    out = {"message": r.get("message", "ok")}
    if "email_sent" in r:
        out["email_sent"] = bool(r["email_sent"])
    if r.get("dev_otp"):
        out["dev_otp"] = r["dev_otp"]
    return (200, out)


def password_reset_complete(body, query, headers):
    """POST { email, otp, new_password } -> updates password after OTP check."""
    data = body or {}
    email = (data.get("email") or "").strip()
    otp = (data.get("otp") or "").strip()
    new_password = (data.get("new_password") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.password_reset_complete(email, otp, new_password)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 503 if err == "password_reset_not_configured" else 400
        if err in ("invalid_or_expired",):
            code = 401
        payload = {"message": err}
        if r.get("detail"):
            payload["detail"] = r["detail"]
        return (code, payload)
    return (200, {"message": r.get("message", "password_updated")})


def signup_link_admin(body, query, headers):
    """POST { email, connection_code, bot_id, api_key, name?, fcm_token? } -> same shape as /connect-to-admin after email steps."""
    data = body
    email = (data.get("email") or "").strip()
    connection_code = (data.get("connection_code") or "").strip()
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    name = (data.get("name") or "").strip()
    fcm_token = (data.get("fcm_token") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.signup_flow_link_admin(
        email, connection_code, bot_id, api_key, name=name or None, fcm_token=fcm_token or None
    )
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err == "invalid_connection_code":
            code = 404
        if err == "session_not_found":
            code = 404
        if err == "wrong_state":
            code = 409
        if err == "link_failed":
            code = 500
        return (code, {"message": err, "account_status": r.get("account_status")})
    ac = (r.get("databus_access_code") or "").strip()
    if ac:
        notify_databus(ac)
    return (200, {
        "message": "ok",
        "admin_id": r.get("admin_id"),
        "user_id": r.get("user_id"),
        "admin_name": r.get("admin_name"),
        "databus_access_code": r.get("databus_access_code"),
        "connection_code": r.get("connection_code") or "",
        "account_status": r.get("account_status", "ACTIVE"),
        "user_first_name": r.get("user_first_name") or "",
        "user_full_name": r.get("user_full_name") or "",
        "user_username": r.get("user_username") or "",
        "user_display_mode": r.get("user_display_mode") or "",
    })
def signup_list_admins_directory(body, query, headers):
    """GET — public id+name list for choose-your-admin during signup (capped on server)."""
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured", "admins": []})
    admins = db.list_admins_for_signup_directory()
    return (200, {"admins": admins})
def signup_request_admin_link(body, query, headers):
    """POST { email, admin_id, bot_id, api_key, name?, fcm_token? } — queue link request for that admin."""
    data = body
    email = (data.get("email") or "").strip()
    admin_id = (data.get("admin_id") or "").strip()
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    name = (data.get("name") or "").strip()
    fcm_token = (data.get("fcm_token") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.signup_submit_admin_link_request(
        email, admin_id, bot_id, api_key, name=name or None, fcm_token=fcm_token or None
    )
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err == "invalid_admin":
            code = 404
        if err == "session_not_found":
            code = 404
        if err == "wrong_state":
            code = 409
        if err == "signup_not_configured":
            code = 503
        payload = {"message": err}
        if r.get("account_status") is not None:
            payload["account_status"] = r.get("account_status")
        return (code, payload)
    return (200, {
        "message": r.get("message", "ok"),
        "request_id": r.get("request_id"),
        "admin_name": r.get("admin_name") or "",
    })
def signup_link_request_status(body, query, headers):
    """GET ?email=&bot_id=&api_key= — pending | accepted | no_pending_request."""
    email = (query.get("email") or "").strip()
    bot_id = (query.get("bot_id") or "").strip()
    api_key = (query.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.signup_get_link_request_status(email, bot_id, api_key)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err == "session_not_found":
            code = 404
        if err == "wrong_state":
            code = 409
        payload = {"message": err}
        if r.get("account_status") is not None:
            payload["account_status"] = r.get("account_status")
        return (code, payload)
    if r.get("status") == "accepted":
        ac = (r.get("databus_access_code") or "").strip()
        if ac:
            notify_databus(ac)
        return (200, {
            "status": "accepted",
            "admin_id": r.get("admin_id"),
            "user_id": r.get("user_id"),
            "admin_name": r.get("admin_name"),
            "databus_access_code": r.get("databus_access_code"),
            "connection_code": r.get("connection_code") or "",
            "account_status": r.get("account_status", "ACTIVE"),
            "user_first_name": r.get("user_first_name") or "",
            "user_full_name": r.get("user_full_name") or "",
            "user_username": r.get("user_username") or "",
            "user_display_mode": r.get("user_display_mode") or "",
        })
    out = {"status": r.get("status")}
    if r.get("request_id"):
        out["request_id"] = r.get("request_id")
    if r.get("admin_id"):
        out["admin_id"] = r.get("admin_id")
    out["admin_name"] = r.get("admin_name") or ""
    return (200, out)
def admin_pending_user_link_requests(body, query, headers):
    """GET ?access_code= — pending signup link requests for this admin (for dashboard UI)."""
    access_code = (query.get("access_code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured", "requests": []})
    if not access_code:
        return (400, {"message": "access_code required", "requests": []})
    r = db.admin_list_pending_user_link_requests(access_code)
    if not r.get("ok"):
        if r.get("error") == "invalid_access_code":
            return (404, {"message": "invalid_access_code", "requests": []})
        return (500, {"message": r.get("error"), "requests": []})
    return (200, {"requests": r.get("requests") or []})
def admin_accept_user_link_request(body, query, headers):
    """POST { access_code, request_id } — accept a pending directory link (same effect as user entering connection code)."""
    data = body
    access_code = (data.get("access_code") or "").strip()
    request_id = (data.get("request_id") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code or not request_id:
        return (400, {"message": "access_code and request_id required"})
    r = db.admin_accept_user_link_request(access_code, request_id)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err == "invalid_access_code":
            code = 404
        if err == "request_not_found":
            code = 404
        if err == "invalid_request_id":
            code = 400
        if err in ("session_not_found", "wrong_state"):
            code = 409
        if err == "link_failed":
            code = 500
        payload = {"message": err}
        if r.get("account_status") is not None:
            payload["account_status"] = r.get("account_status")
        return (code, payload)
    ac = (r.get("databus_access_code") or "").strip()
    if ac:
        notify_databus(ac)
    return (200, {
        "message": "ok",
        "admin_id": r.get("admin_id"),
        "user_id": r.get("user_id"),
        "admin_name": r.get("admin_name"),
        "databus_access_code": r.get("databus_access_code"),
        "connection_code": r.get("connection_code") or "",
        "account_status": r.get("account_status", "ACTIVE"),
        "user_first_name": r.get("user_first_name") or "",
        "user_full_name": r.get("user_full_name") or "",
        "user_username": r.get("user_username") or "",
        "user_display_mode": r.get("user_display_mode") or "",
    })
def admin_reject_user_link_request(body, query, headers):
    """POST { access_code, request_id } — decline a pending directory link request."""
    data = body
    access_code = (data.get("access_code") or "").strip()
    request_id = (data.get("request_id") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code or not request_id:
        return (400, {"message": "access_code and request_id required"})
    r = db.admin_reject_user_link_request(access_code, request_id)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 400
        if err == "invalid_access_code":
            code = 404
        if err == "request_not_found":
            code = 404
        if err == "invalid_request_id":
            code = 400
        return (code, {"message": err})
    return (200, {"message": "ok"})
def user_account_status(body, query, headers):
    """GET ?bot_id=&api_key= -> { account_status } for lifecycle gating on the client."""
    bot_id = (query.get("bot_id") or "").strip()
    api_key = (query.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    st = db.get_user_account_status_by_bot(bot_id, api_key)
    if st is None:
        return (404, {"message": "User not found"})
    return (200, {"account_status": st})
def maintenance_cleanup_pending_cron(body, query, headers):
    """GET — Vercel Cron only. Authorization: Bearer <CRON_SECRET> (or MAINTENANCE_API_KEY if CRON_SECRET unset).

    Deletes signup_sessions with created_at older than hours_sessions (default 168 = 7 days) and stale pending users.
    Query: hours_sessions, hours_users (optional ints).
    """
    auth = (headers.get("authorization") or "").strip()
    expected = (os.environ.get("CRON_SECRET") or os.environ.get("MAINTENANCE_API_KEY") or "").strip()
    if not expected:
        return (404, {"message": "Not found"})
    token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
    if token != expected:
        return (401, {"message": "Unauthorized"})
    try:
        hs = int((query.get("hours_sessions") or "").strip() or "168")
    except ValueError:
        hs = 168
    try:
        hu = int((query.get("hours_users") or "").strip() or "24")
    except ValueError:
        hu = 24
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    out = db.maintenance_cleanup_pending(hours_sessions=hs, hours_users=hu)
    return (200, {"message": "ok", "trigger": "cron", **out})


def maintenance_cleanup_pending(body, query, headers):
    """POST optional JSON { hours_sessions, hours_users } - requires X-Maintenance-Key matching MAINTENANCE_API_KEY."""
    key = (headers.get("x-maintenance-key") or "").strip()
    expected = (os.environ.get("MAINTENANCE_API_KEY") or "").strip()
    if not expected or key != expected:
        return (404, {"message": "Not found"})
    data = body
    hs = int(data.get("hours_sessions") or 168)
    hu = int(data.get("hours_users") or 24)
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    out = db.maintenance_cleanup_pending(hours_sessions=hs, hours_users=hu)
    return (200, {"message": "ok", **out})
def maintenance_run_alert_checks(body, query, headers):
    """POST — requires X-Maintenance-Key. Runs one alert sweep for all admins (Vercel Cron / serverless)."""
    key = (headers.get("x-maintenance-key") or "").strip()
    expected = (os.environ.get("MAINTENANCE_API_KEY") or "").strip()
    if not expected or key != expected:
        return (404, {"message": "Not found"})
    from alert_scheduler import BackendAlertScheduler
    s = BackendAlertScheduler(get_db)
    s.run_all_checks_once()
    return (200, {"message": "ok"})


def _wake_relay_if_needed(relay_url):
    """Hit relay HTTP /status to wake it (e.g. Render cold start). Ignore errors; WebSocket will retry."""
    try:
        import urllib.request as _urllib
        http_url = relay_url.replace("wss://", "https://", 1).replace("ws://", "http://", 1).rstrip("/")
        req = _urllib.Request(http_url + "/status", method="GET")
        _urllib.urlopen(req, timeout=45)
    except Exception:
        pass


def _send_alert_via_relay(bot_id, api_key, alert_type, message, fcm_token=None, user_name=None):
    """Send alert to relay so it reaches the user at any cost. FCM token from DB so push works even when phone is off.
    Wakes relay if cold (Render), then retries WebSocket with long timeouts until relay is up; relay sends via FCM."""
    import json
    import asyncio
    import time
    import urllib.request
    bot_id = (bot_id or "").strip()
    api_key = (api_key or "").strip()
    if not bot_id or not api_key:
        return False, "Missing bot_id or api_key"
    relay_url = os.environ.get("RELAY_URL", "wss://curax-relay.onrender.com").strip()
    payload = {
        "action": "alert",
        "bot_id": bot_id,
        "api_key": api_key,
        "type": alert_type or "alert",
        "message": message or "",
    }
    un = (user_name or "").strip()
    if un:
        payload["user_name"] = un
    fcm = (fcm_token or "").strip() or None
    if fcm:
        payload["fcm_token"] = fcm
    last_error = None
    # Wake relay first (GET /status) so cold start begins; then retry WebSocket until relay is up
    _wake_relay_if_needed(relay_url)
    time.sleep(3)
    # 5 attempts with backoff: 3s, +5s, +15s, +30s, +45s between attempts; 60s open_timeout each
    delays = (0, 5, 15, 30, 45)
    for attempt in range(5):
        if attempt > 0:
            time.sleep(delays[attempt])
        try:
            import websockets
            async def _ws_send():
                async with websockets.connect(relay_url, close_timeout=15, open_timeout=60) as ws:
                    await ws.send(json.dumps(payload))
            asyncio.run(_ws_send())
            return True, None
        except Exception as e:
            last_error = str(e).strip() or repr(e)
            print(f"[notify-event] relay attempt {attempt + 1}/5 ({bot_id[:8]}...): {e}")
    err_msg = (last_error or "Unknown error")[:200]
    return False, err_msg
def notify_event(body, query, headers):
    """POST { access_code, event_type, message, user_name? } → desktop tells backend of admin-only events.
    Backend finds admin bot_id+api_key by access_code, persists alert row (dashboard user), sends to relay
    → admin's app (FCM + popup), and notifies databus so desktop/mobile Alerts tabs refresh.
    Events: system_started, system_unlocked, admin_login, dose_taken, etc.
    """
    data = body
    access_code = (data.get("access_code") or "").strip()
    event_type = (data.get("event_type") or "alert").strip()
    message = (data.get("message") or "").strip()
    if not access_code:
        return (400, {"message": "access_code required"})
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    bot = db.get_admin_bot_by_access_code(access_code)
    if not bot:
        return (404, {"message": "Admin not found or credentials not yet registered (admin must sign up on app first)"})
    admin_row = db.get_admin_by_access_code(access_code) or {}
    admin_id = (admin_row.get("id") or "").strip()
    _system_types = frozenset({"system_started", "system_unlocked", "admin_login", "test_alert"})
    relay_user_name = None
    if (event_type or "").strip().lower() not in _system_types:
        relay_user_name = (data.get("user_name") or "").strip() or None
    bid, akey = bot.get("bot_id"), bot.get("api_key")
    fcm = (bot.get("fcm_token") or "").strip() or None
    if not fcm:
        fcm = db.get_fcm_token_for_bot(bid, akey) or None
    if admin_id:
        try:
            duid = db.get_dashboard_user_id(admin_id)
            if duid:
                db.create_alert(duid, admin_id, event_type, message)
        except Exception as e:
            print(f"[notify-event] create_alert: {e}")
    def _deliver():
        db2 = get_db()
        fcm_now = (fcm or (db2.get_fcm_token_for_bot(bid, akey) if db2 else None) or "").strip() or None
        _send_alert_via_relay(bid, akey, event_type, message, fcm_token=fcm_now, user_name=relay_user_name)
        try:
            notify_databus(access_code)
        except Exception as e:
            print(f"[notify-event] databus: {e}")
    threading.Thread(target=_deliver, daemon=True, name="NotifyRelay").start()
    return (200, {"message": "ok"})
def notify_event_by_user(body, query, headers):
    """POST { bot_id, api_key, event_type, message } ΓåÆ from a user's desktop/app.
    Backend finds that user's admin, sends alert to admin's app via relay, and creates an alert row so admin sees it in Alerts tab. Events: dose_taken, system_started, system_unlocked, etc."""
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    event_type = (data.get("event_type") or "alert").strip()
    message = (data.get("message") or "").strip()
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
    if not info:
        deleted_info = getattr(db, "get_deleted_user_notification", None) and db.get_deleted_user_notification(bot_id, api_key)
        if deleted_info:
            return (410, {
                "reason": "deleted_by_admin",
                "message": deleted_info.get("message", "The admin has removed you from their account."),
            })
        return (404, {"message": "User not found or not linked to an admin"})
    admin_bot_id = info.get("admin_bot_id") or ""
    admin_api_key = info.get("admin_api_key") or ""
    if not admin_bot_id or not admin_api_key:
        return (404, {"message": "Admin app not registered yet"})
    try:
        db.create_alert(info["user_id"], info["admin_id"], event_type, message)
    except Exception:
        pass
    admin_fcm = db.get_fcm_token_for_bot(admin_bot_id, admin_api_key)
    relay_user_name = (info.get("user_name") or "").strip()
    def _deliver():
        _send_alert_via_relay(
            admin_bot_id,
            admin_api_key,
            event_type,
            message,
            fcm_token=admin_fcm,
            user_name=relay_user_name or None,
        )
    threading.Thread(target=_deliver, daemon=True, name="NotifyRelay").start()
    return (200, {"message": "ok"})
def notify_event_to_user(body, query, headers):
    """POST { bot_id, api_key, event_type, message } ΓåÆ send alert to that user's app (e.g. desktop 'Test alert' in user view).
    Backend verifies the user exists, then sends to relay so the user's app receives the alert."""
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    event_type = (data.get("event_type") or "alert").strip()
    message = (data.get("message") or "").strip()
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    user_id = db.get_user_id_by_bot(bot_id, api_key)
    if not user_id:
        deleted_info = getattr(db, "get_deleted_user_notification", None) and db.get_deleted_user_notification(bot_id, api_key)
        if deleted_info:
            return (410, {
                "reason": "deleted_by_admin",
                "message": deleted_info.get("message", "The admin has removed you from their account."),
            })
        return (404, {"message": "User not found or not linked to an admin"})
    user_fcm = db.get_fcm_token_for_bot(bot_id, api_key)
    def _deliver():
        _send_alert_via_relay(bot_id, api_key, event_type, message, fcm_token=user_fcm)
    threading.Thread(target=_deliver, daemon=True, name="NotifyRelay").start()
    return (200, {"message": "ok"})
def get_linked_users(body, query, headers):
    """GET /admin/linked-users?access_code=... -> list of users linked to this admin (excluding dashboard user).
    Optional dose_preview=1 appends recent_doses (last 50) per user with medicine_name when known."""
    access_code = (query.get("access_code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"users": []})
    if not access_code:
        return (400, {"users": []})
    admin = db.get_admin_by_access_code(access_code)
    if not admin:
        return (404, {"users": []})
    admin_id = admin.get("id")
    users = db.get_all_users_by_admin_id(admin_id) or []
    want_doses = (query.get("dose_preview") or "").strip().lower() in ("1", "true", "yes")

    def _dose_qty_from_dosage(dosage_val):
        """Leading integer from dosage text (e.g. '2 tablets'); cap so '500mg' does not become 500 pills."""
        if dosage_val is None:
            return 1
        s = str(dosage_val).strip()
        if not s:
            return 1
        m = re.match(r"^(\d+)", s)
        if not m:
            return 1
        n = int(m.group(1))
        return n if 1 <= n <= 20 else 1

    def _norm_med_id(val):
        if val is None:
            return None
        s = str(val).strip()
        if not s:
            return None
        try:
            return str(uuid.UUID(s))
        except Exception:
            return s

    if want_doses:
        for u in users:
            uid = (u.get("id") or "").strip()
            try:
                doses = db.list_dose_logs(uid, limit=50) if uid else []
                meds = db.list_medicines(uid) if uid else []
                by_mid = {}
                for m in meds or []:
                    mid = m.get("id")
                    if mid:
                        ks = str(mid)
                        by_mid[ks] = m
                        try:
                            by_mid[str(uuid.UUID(ks))] = m
                        except Exception:
                            pass
                for row in doses:
                    mid_raw = row.get("medicine_id")
                    med_obj = None
                    if mid_raw:
                        med_obj = by_mid.get(_norm_med_id(mid_raw))
                        if med_obj is None:
                            med_obj = by_mid.get(str(mid_raw).strip())
                    if med_obj is None:
                        bid = str(row.get("box_id") or "").strip()
                        if bid:
                            for m in meds or []:
                                if str(m.get("box_id") or "").strip() == bid:
                                    med_obj = m
                                    break
                    nm = ((med_obj.get("name") or "").strip()) if med_obj else ""
                    row["medicine_name"] = nm
                    row["dose_quantity"] = _dose_qty_from_dosage(med_obj.get("dosage")) if med_obj else 1
                    if med_obj is not None:
                        try:
                            q = med_obj.get("quantity")
                            row["stock_quantity"] = int(q) if q is not None else None
                        except (TypeError, ValueError):
                            row["stock_quantity"] = None
                    else:
                        row["stock_quantity"] = None
                u["recent_doses"] = doses
            except Exception:
                u["recent_doses"] = []
    return (200, {"users": users})


def admin_set_user_display_mode(body, query, headers):
    """POST { access_code, user_id, display_mode: standalone|default } — admin sets linked user's app shell; databus notifies user."""
    data = body if isinstance(body, dict) else {}
    access_code = (data.get("access_code") or "").strip()
    user_id = (data.get("user_id") or "").strip()
    mode = (data.get("display_mode") or data.get("user_display_mode") or "").strip().lower()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code or not user_id:
        return (400, {"message": "access_code and user_id required"})
    if mode not in ("standalone", "default"):
        return (400, {"message": "display_mode must be standalone or default"})
    admin = db.get_admin_by_access_code(access_code)
    if not admin:
        return (404, {"message": "Admin not found"})
    admin_id = admin.get("id")
    ok = db.set_user_display_mode_for_admin(admin_id, user_id, mode)
    if not ok:
        return (404, {"message": "User not found or not linked to this admin"})
    notify_databus(access_code)
    return (200, {"ok": True, "user_display_mode": mode})


def put_admin_fcm_token(body, query, headers):
    """PUT { access_code, fcm_token } ΓåÆ update admin's FCM token. Called when app gets FCM token (e.g. after permission).
    So push alerts (and Test alert) work; relay uses FCM first when available."""
    data = body
    access_code = (data.get("access_code") or "").strip()
    fcm_token = (data.get("fcm_token") or "").strip()
    if not access_code:
        return (400, {"message": "access_code required"})
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    ok = db.update_admin_fcm_token_by_access_code(access_code, fcm_token if fcm_token else None)
    if not ok:
        return (404, {"message": "Admin not found for this access code"})
    return (200, {"message": "ok"})
def get_admin_connection(body, query, headers):
    """GET /admin/connection?access_code=... ΓåÆ { fcm_token_set, connected, linked_users } for Connection panel.
    connected = admin has bot_id+api_key (app has registered); fcm_token_set = FCM token stored for push."""
    access_code = (query.get("access_code") or "").strip()
    if not access_code:
        return (400, {"message": "access_code required", "fcm_token_set": False, "connected": False, "linked_users": []})
    db = get_db()
    if not db:
        return (503, {"fcm_token_set": False, "connected": False, "linked_users": []})
    status = db.get_admin_connection_status(access_code)
    if not status:
        return (404, {"fcm_token_set": False, "connected": False, "linked_users": []})
    admin = db.get_admin_by_access_code(access_code)
    admin_id = admin.get("id") if admin else None
    users = db.get_all_users_by_admin_id(admin_id) or [] if admin_id else []
    return (200, {
        "fcm_token_set": status.get("fcm_token_set", False),
        "connected": status.get("connected", False),
        "linked_users": [
            {
                "id": str(u.get("id", "")),
                "name": u.get("name"),
                "email": u.get("email") or "",
                "bot_id": u.get("bot_id"),
                "profile_picture": (u.get("profile_picture") or "").strip(),
                "user_display_mode": (u.get("user_display_mode") or "").strip(),
            }
            for u in users
        ],
    })
def delete_admin_user(user_id, body, query, headers):
    """DELETE /admin/users/<user_id> with JSON { "access_code": "..." }. Only admin can delete a connected user.
    User cannot delete themselves; deleted user is informed on next app/desktop request (410)."""
    data = body
    access_code = (data.get("access_code") or query.get("access_code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code:
        return (400, {"message": "access_code required"})
    result = db.get_role_by_access_code(access_code)
    if not result:
        return (404, {"message": "Invalid access code"})
    role, admin_id = result
    if role != "admin":
        return (403, {"message": "Access code is not for admin"})
    user_id = (user_id or "").strip()
    if not user_id:
        return (400, {"message": "user_id required"})
    ok, user_name = db.delete_user_by_admin(admin_id, user_id)
    if not ok:
        return (404, {"message": "User not found or not linked to this admin"})
    # Notify admin: create alert using dashboard user so it appears in admin alerts
    try:
        duid = db.get_dashboard_user_id(admin_id)
        if duid:
            db.create_alert(duid, admin_id, "user_removed", f"User {user_name or 'User'} was removed from your account.")
    except Exception:
        pass
    return (200, {"message": "User deleted", "user_name": user_name or "User"})
def verify_credentials(body, query, headers):
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"role": None, "message": "Central DB not configured"})
    result = db.get_role_by_bot(bot_id, api_key)
    if not result:
        deleted_info = getattr(db, "get_deleted_user_notification", None) and db.get_deleted_user_notification(bot_id, api_key)
        if deleted_info:
            return (410, {
                "role": None,
                "reason": "deleted_by_admin",
                "message": deleted_info.get("message", "The admin has removed you from their account."),
            })
        return (401, {"role": None, "message": "Invalid credentials"})
    role, id_ = result
    return (200, {"role": role, "message": "ok", "id": id_})
def get_role(body, query, headers):
    access_code = (query.get("access_code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"role": None, "message": "Central DB not configured"})
    if access_code:
        result = db.get_role_by_access_code(access_code)
        if not result:
            return (200, {"role": None})
        role, id_ = result
        if role == "admin":
            info = db.get_admin_by_access_code(access_code)
            name = (info or {}).get("name")
            conn_code = (info or {}).get("connection_code")
            return (200, {"role": "admin", "name": name, "admin_id": id_, "connection_code": conn_code})
    bot_id = (query.get("bot_id") or "").strip()
    api_key = (query.get("api_key") or "").strip()
    result = db.get_role_by_bot(bot_id, api_key)
    if not result:
        deleted_info = getattr(db, "get_deleted_user_notification", None) and db.get_deleted_user_notification(bot_id, api_key)
        if deleted_info:
            return (410, {
                "role": None,
                "reason": "deleted_by_admin",
                "message": deleted_info.get("message", "The admin has removed you from their account."),
            })
        return (200, {"role": None})
    role, id_ = result
    if role == "admin":
        info = db.get_admin_info_from_bot(bot_id, api_key)
        name = (info or {}).get("name")
        conn_code = (info or {}).get("connection_code")
        return (200, {"role": "admin", "name": name, "admin_id": id_, "connection_code": conn_code})
    return (200, {"role": "user", "name": None, "user_id": id_})
def admin_data(body, query, headers):
    """GET /admin/data?access_code=...&last_sync_time=...&act_as_user_id=... (optional).
    If act_as_user_id is set and belongs to this admin, returns that user's data (admin acting as that user)."""
    access_code = (query.get("access_code") or "").strip()
    last_sync_time = (query.get("last_sync_time") or "").strip() or None
    act_as_user_id = (query.get("act_as_user_id") or "").strip() or None
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code:
        return (400, {"message": "access_code required"})
    result = db.get_role_by_access_code(access_code)
    if not result:
        return (404, {"message": "Invalid access code"})
    role, admin_id = result
    if role != "admin":
        return (403, {"message": "Access code is not for admin"})
    if act_as_user_id and db.user_belongs_to_admin(act_as_user_id, admin_id):
        data = db.get_user_data_for_admin(admin_id, act_as_user_id, last_sync_time=last_sync_time)
    else:
        data = db.get_admin_dashboard_data(admin_id, last_sync_time=last_sync_time)
    if data is None:
        return (500, {"message": "Failed to load admin data"})
    out = _normalize_user_data_response(data)
    n_meds = len(out["medicines"])
    if n_meds == 0:
        print(f"  [admin/data] returning 0 medicines for this admin")
    else:
        print(f"  [admin/data] returning {n_meds} medicines")
    return (200, out)


def _normalize_user_data_response(data):
    """Same shape as admin_data: medicines, dose_logs, alert_settings, alerts, medical_reminders, server_time, incremental."""
    medicines = data.get("medicines") or []
    out = {
        "medicines": [dict(m) for m in medicines],
        "dose_logs": data.get("dose_logs") or [],
        "alert_settings": data.get("alert_settings") if data.get("alert_settings") is not None else {},
        "alerts": data.get("alerts") or [],
        "medical_reminders": data.get("medical_reminders") if data.get("medical_reminders") is not None else {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []},
        "server_time": data.get("server_time") or "",
        "incremental": bool(data.get("incremental")),
        "user_display_mode": str(data.get("user_display_mode") or "").strip().lower(),
        "user_first_name": str(data.get("user_first_name") or "").strip(),
        "user_full_name": str(data.get("user_full_name") or "").strip(),
        "user_username": str(data.get("user_username") or "").strip(),
    }
    pp = str(data.get("profile_picture") or "").strip()
    if pp:
        out["profile_picture"] = pp
    settings_obj = out["alert_settings"] if isinstance(out["alert_settings"], dict) else {}
    medicine_meta = settings_obj.get("medicine_meta") if isinstance(settings_obj.get("medicine_meta"), dict) else {}
    for m in out["medicines"]:
        if "times" not in m or m["times"] is None:
            m["times"] = []
        elif not isinstance(m["times"], list):
            m["times"] = list(m["times"]) if hasattr(m["times"], "__iter__") and not isinstance(m["times"], str) else []
        if "quantity" not in m or m["quantity"] is None:
            m["quantity"] = 0
        else:
            try:
                m["quantity"] = int(m["quantity"])
            except (TypeError, ValueError):
                m["quantity"] = 0
        box_id = (m.get("box_id") or "").strip().upper()
        meta = medicine_meta.get(box_id) if box_id else None
        if not isinstance(meta, dict):
            meta = {}
        exact_time = (meta.get("exact_time") or "").strip() or (m["times"][0] if m["times"] else "08:00")
        dose_per_day = _safe_int(meta.get("dose_per_day"), 0)
        if dose_per_day <= 0:
            dose_per_day = max(1, len(m["times"]))
        instructions = (meta.get("instructions") or m.get("dosage") or "").strip()
        expiry = (meta.get("expiry") or "").strip()
        m["exact_time"] = exact_time
        m["dose_per_day"] = dose_per_day
        m["instructions"] = instructions
        m["expiry"] = expiry
    if data.get("medicine_box_ids") is not None:
        out["medicine_box_ids"] = data["medicine_box_ids"]
    return out
def user_data(body, query, headers):
    """GET /user/data?bot_id=...&api_key=...&last_sync_time=... (optional).
    Returns this user's data in same shape as GET /admin/data (for desktop linked as user). Data saved by admin for this user is stored per user_id; when user links desktop they get their data here."""
    bot_id = (query.get("bot_id") or "").strip()
    api_key = (query.get("api_key") or "").strip()
    last_sync_time = (query.get("last_sync_time") or "").strip() or None
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
    if not info:
        deleted_info = getattr(db, "get_deleted_user_notification", None) and db.get_deleted_user_notification(bot_id, api_key)
        if deleted_info:
            return (410, {"message": deleted_info.get("message", "The admin has removed you from their account.")})
        return (404, {"message": "User not found"})
    user_id = info["user_id"]
    admin_id = info["admin_id"]
    data = db.get_user_data_for_admin(admin_id, user_id, last_sync_time=last_sync_time)
    if data is None:
        return (500, {"message": "Failed to load user data"})
    return (200, _normalize_user_data_response(data))


def user_post_display_mode(body, query, headers):
    """POST { bot_id, api_key, display_mode: standalone|default } — user app first-time / sync (admin can overwrite in DB)."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    mode = (data.get("display_mode") or data.get("user_display_mode") or "").strip().lower()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    if mode not in ("standalone", "default"):
        return (400, {"message": "display_mode must be standalone or default"})
    info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
    if not info:
        return (404, {"message": "User not found"})
    ok = db.set_user_display_mode_by_bot(bot_id, api_key, mode)
    if not ok:
        return (500, {"message": "Failed to save display mode"})
    return (200, {"ok": True, "user_display_mode": mode})


def user_post_profile_picture(body, query, headers):
    """POST { bot_id, api_key, profile_picture } — data URL or empty string to clear. Synced to admin linked-users list."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    raw = data.get("profile_picture")
    pic = (raw if isinstance(raw, str) else str(raw or "")).strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    ok, err = db.set_user_profile_picture_by_bot(bot_id, api_key, pic)
    if err == "missing_credentials":
        return (400, {"message": "bot_id and api_key required"})
    if err == "too_large":
        return (400, {"message": "profile_picture too large"})
    if err == "user_not_found":
        return (404, {"message": "User not found"})
    if not ok:
        return (500, {"message": "Failed to save profile picture", "detail": err or "unknown"})
    return (200, {"ok": True})


def user_standalone_sync(body, query, headers):
    """POST { bot_id, api_key, client_ms?, medicines?, dose_append?, medical_reminders?, system_settings? } — user app offline-first flush."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
    if not info:
        return (404, {"message": "User not found"})
    user_id = info["user_id"]
    admin_id = info["admin_id"]
    try:
        client_ms = int(data.get("client_ms") or 0)
    except (TypeError, ValueError):
        client_ms = 0
    medicines = data.get("medicines")
    dose_append = data.get("dose_append")
    if dose_append is None:
        dose_append = data.get("dose_log_append")
    medical_reminders = data.get("medical_reminders")
    system_settings = data.get("system_settings")
    if medicines is None:
        medicines = []
    if dose_append is None:
        dose_append = []
    ok = db.merge_user_standalone_sync_from_app(
        admin_id,
        user_id,
        medicines,
        dose_append,
        medical_reminders,
        client_ms,
        system_settings if isinstance(system_settings, dict) else None,
    )
    if not ok:
        return (500, {"message": "Failed to merge user data"})
    ac = (info.get("admin_access_code") or "").strip()
    if ac:
        notify_databus(ac)
    return (200, {"ok": True})


def user_missed_dose_escalate(body, query, headers):
    """POST { bot_id, api_key, phase: 15|30, box_id, medicine_name?, schedule_time?, dose_date? }
    Instant missed-dose escalation from user device (no cron delay)."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    phase = (data.get("phase") or "").strip()
    box_id = (data.get("box_id") or "").strip()
    medicine_name = (data.get("medicine_name") or "").strip()
    schedule_time = (data.get("schedule_time") or "").strip()
    dose_date = (data.get("dose_date") or "").strip()
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    if phase not in ("15", "30"):
        return (400, {"message": "phase must be 15 or 30"})
    if not box_id:
        return (400, {"message": "box_id required"})
    from alert_scheduler import BackendAlertScheduler
    scheduler = BackendAlertScheduler(get_db)
    ok, detail = scheduler.deliver_device_escalation(
        bot_id, api_key, phase, box_id, medicine_name, schedule_time, dose_date
    )
    if ok:
        return (200, {"ok": True})
    if detail == "user_not_found":
        return (404, {"message": "User not found"})
    if detail in ("skipped", "invalid_phase", "box_id_required"):
        return (200, {"ok": False, "skipped": True, "reason": detail})
    return (500, {"message": detail or "escalation failed"})


def user_plans_get(body, query, headers):
    """GET /user/plans?bot_id=&api_key= — list Health Hub planned items for this user."""
    bot_id = (query.get("bot_id") or "").strip()
    api_key = (query.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    plans = db.list_user_plans_by_bot(bot_id, api_key)
    if plans is None:
        return (404, {"message": "User not found"})
    return (200, {"plans": plans})


def user_plans_post(body, query, headers):
    """POST { bot_id, api_key, title, plan_date, notes?, plan_time?, activity_type?, health_type? } — create a plan."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    title = (data.get("title") or "").strip()
    notes = (data.get("notes") or "").strip()
    plan_date = (data.get("plan_date") or "").strip()
    plan_time = (data.get("plan_time") or "").strip()
    activity_type = (data.get("activity_type") or "other").strip()
    health_type = (data.get("health_type") or "general").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    if not title:
        return (400, {"message": "title required"})
    if not plan_date:
        return (400, {"message": "plan_date required (YYYY-MM-DD)"})
    pid, err = db.create_user_plan_by_bot(
        bot_id, api_key, title, notes, plan_date, plan_time, activity_type, health_type
    )
    if err == "user_not_found":
        return (404, {"message": "User not found"})
    if err == "invalid_plan_date":
        return (400, {"message": "plan_date must be YYYY-MM-DD"})
    if pid is None:
        return (500, {"message": "Failed to save plan", "detail": err or "unknown", "hint": "Run: ALTER TABLE users ADD COLUMN IF NOT EXISTS health_hub_plans JSONB NOT NULL DEFAULT '[]'::jsonb;"})
    return (200, {"ok": True, "id": pid})


def user_plans_patch(body, query, headers):
    """PATCH { bot_id, api_key, plan_id, status: pending|done }."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    plan_id = (data.get("plan_id") or "").strip()
    status = (data.get("status") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key or not plan_id:
        return (400, {"message": "bot_id, api_key, and plan_id required"})
    ok, err = db.update_user_plan_by_bot(bot_id, api_key, plan_id, status=status)
    if err == "user_not_found":
        return (404, {"message": "User not found"})
    if err == "plan_not_found":
        return (404, {"message": "Plan not found"})
    if err == "invalid_status":
        return (400, {"message": "status must be pending or done"})
    if not ok:
        return (500, {"message": "Failed to update plan", "detail": err or "unknown"})
    return (200, {"ok": True})


def user_plans_delete(body, query, headers):
    """DELETE body { bot_id, api_key, plan_id }."""
    data = body if isinstance(body, dict) else {}
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    plan_id = (data.get("plan_id") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key or not plan_id:
        return (400, {"message": "bot_id, api_key, and plan_id required"})
    ok, err = db.delete_user_plan_by_bot(bot_id, api_key, plan_id)
    if err == "user_not_found":
        return (404, {"message": "User not found"})
    if err == "plan_not_found":
        return (404, {"message": "Plan not found"})
    if not ok:
        return (500, {"message": "Failed to delete plan", "detail": err or "unknown"})
    return (200, {"ok": True})


def user_databus_room(body, query, headers):
    """GET ?bot_id=&api_key= ΓåÆ { databus_access_code } for WebSocket data bus (same room as admin/desktop)."""
    bot_id = (query.get("bot_id") or "").strip()
    api_key = (query.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    info = db.get_user_and_admin_bot_by_user_bot(bot_id, api_key)
    if not info:
        return (404, {"message": "User not found"})
    ac = db.get_admin_access_code_by_id(info["admin_id"])
    if not ac:
        return (404, {"message": "Admin not found"})
    return (200, {"databus_access_code": ac})
def admin_sync(body, query, headers):
    """POST { "access_code", "medicine_boxes", "dose_log", "alert_settings", "gmail_config", "medical_reminders", "sms_config" }.
    Write all admin data to Central DB (per admin). Then notify data bus so other clients get the update via WebSocket.
    """
    data = body
    access_code = (data.get("access_code") or "").strip()
    if not access_code:
        return (400, {"message": "access_code required"})
    db = get_db()
    if not db:
        return (503, {
            "message": "Central DB not configured. Set DATABASE_URL (e.g. Neon connection string) in server environment (e.g. Railway project variables)."
        })
    result = db.get_role_by_access_code(access_code)
    if not result:
        return (404, {"message": "Invalid access code"})
    role, admin_id = result
    if role != "admin":
        return (403, {"message": "Access code is not for admin"})
    medicine_boxes = data.get("medicine_boxes") if isinstance(data.get("medicine_boxes"), dict) else {}
    dose_log = data.get("dose_log") if isinstance(data.get("dose_log"), list) else []
    act_as_user_id = (data.get("act_as_user_id") or "").strip() or None
    if act_as_user_id and not db.user_belongs_to_admin(act_as_user_id, admin_id):
        act_as_user_id = None
    if act_as_user_id:
        ok = db.sync_admin_dashboard_data_for_user(admin_id, act_as_user_id, medicine_boxes, dose_log)
        if not ok:
            return (500, {"message": "Failed to write user data"})
        duid = act_as_user_id
    else:
        try:
            ok = db.sync_admin_dashboard_data(admin_id, medicine_boxes, dose_log)
            if not ok:
                print(f"  [admin/sync] sync_admin_dashboard_data returned False (dashboard user may be missing)")
                return (500, {"message": "Failed to write dashboard data (no dashboard user)"})
        except Exception as e:
            print(f"  [admin/sync] sync_admin_dashboard_data: {e}")
            return (500, {"message": "Failed to write dashboard data"})
        duid = db.get_dashboard_user_id(admin_id)
    if duid:
        settings = db.get_alert_settings(duid) or {}
        if isinstance(data.get("alert_settings"), dict):
            settings["alert_settings"] = data["alert_settings"]
        if isinstance(data.get("gmail_config"), dict):
            settings["gmail_config"] = data["gmail_config"]
        if isinstance(data.get("medical_reminders"), dict):
            settings["medical_reminders"] = data["medical_reminders"]
        if isinstance(data.get("sms_config"), dict):
            settings["sms_config"] = data["sms_config"]
        if isinstance(data.get("mobile_bot_config"), dict):
            settings["mobile_bot_config"] = data["mobile_bot_config"]
        if isinstance(data.get("admin_bot_config"), dict):
            settings["admin_bot_config"] = data["admin_bot_config"]

        incoming_meta = _extract_medicine_meta_from_boxes(medicine_boxes)
        existing_meta = settings.get("medicine_meta") if isinstance(settings.get("medicine_meta"), dict) else {}
        merged_meta = {}
        for box in [f"B{i}" for i in range(1, 7)]:
            if box in (medicine_boxes or {}):
                if isinstance((medicine_boxes or {}).get(box), dict) and box in incoming_meta:
                    merged_meta[box] = incoming_meta[box]
            elif box in existing_meta:
                merged_meta[box] = existing_meta[box]
        settings["medicine_meta"] = merged_meta

        if not db.upsert_alert_settings(duid, settings):
            return (500, {"message": "Failed to save alert settings"})
    notify_databus(access_code)
    trigger_alert_checks_for_admin(admin_id)
    return (200, {"message": "ok"})


def admin_clear_user_dose_logs(body, query, headers):
    """POST { access_code, user_id } — delete all dose_logs for that linked user (admin hub dose preview)."""
    data = body if isinstance(body, dict) else {}
    access_code = (data.get("access_code") or "").strip()
    user_id = (data.get("user_id") or "").strip()
    if not access_code or not user_id:
        return (400, {"message": "access_code and user_id required"})
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    admin = db.get_admin_by_access_code(access_code)
    if not admin:
        return (404, {"message": "Admin not found"})
    admin_id = admin.get("id")
    ok = db.clear_dose_logs_for_linked_user(admin_id, user_id)
    if not ok:
        return (404, {"message": "User not found or not linked to this admin"})
    notify_databus(access_code)
    trigger_alert_checks_for_admin(admin_id)
    return (200, {"ok": True})


def admin_notify(body, query, headers):
    """POST { "access_code": "..." } ΓåÆ tell data bus to push latest admin data to connected clients (WebSocket)."""
    data = body
    access_code = (data.get("access_code") or "").strip()
    if not access_code:
        return (400, {"message": "access_code required"})
    notify_databus(access_code)
    return (200, {"message": "ok"})
def delete_admin(body, query, headers):
    """DELETE /admin with JSON { "access_code": "..." } ΓåÆ delete that admin from Central DB.
    Frees access_code and connection_code so they can be allotted to new admins. Called by desktop when user deletes admin.
    """
    data = body
    access_code = (data.get("access_code") or query.get("access_code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code:
        return (400, {"message": "access_code required"})
    ok = db.delete_admin_by_access_code(access_code)
    if not ok:
        return (404, {"message": "Admin not found or already deleted"})
    return (200, {"message": "Admin deleted", "access_code": access_code})
def create_desktop_link_code(body, query, headers):
    """POST { "bot_id", "api_key" } from admin phone → one-time code for PC (5 min, single use). No user involved."""
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required (open the app signed in as admin)."})
    expires_seconds = 300
    code, admin_id, admin_name = db.create_desktop_link_code_for_bot(bot_id, api_key, expires_seconds=expires_seconds)
    if not code:
        return (503, {"message": "Could not create link code. Open the admin app signed in, then try again."})
    return (200, {"code": code, "expires_in": expires_seconds, "admin_name": admin_name})
def desktop_link_to_admin(body, query, headers):
    """POST { "code": "..." } → one-shot: redeem code + return full admin hub (same as GET /admin/data)."""
    data = body
    code = (data.get("code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not code:
        return (400, {"message": "code required"})
    info = db.redeem_desktop_link_code(code)
    if not info:
        return (404, {"message": "Invalid or expired code. Create a new code in the admin app."})
    admin_id = info["admin_id"]
    raw = db.get_admin_dashboard_data(admin_id, last_sync_time=None)
    if raw is None:
        return (500, {"message": "Could not load admin hub"})
    hub = _normalize_user_data_response(raw)
    sync_key = (info.get("sync_key") or "").strip()
    bot = db.get_admin_bot_by_access_code(sync_key) if sync_key else None
    out = {
        "admin_id": admin_id,
        "admin_name": info.get("admin_name") or "Admin",
        "connection_code": info.get("connection_code") or "",
        "hub": hub,
        "sync_key": sync_key,
    }
    if bot:
        out["bot_id"] = bot.get("bot_id") or ""
        out["api_key"] = bot.get("api_key") or ""
    return (200, out)
def put_admin_medical_reminders(body, query, headers):
    """PUT { "access_code": "...", "medical_reminders": { "appointments": [], "prescriptions": [], "lab_tests": [], "custom": [] } }.
    Updates the dashboard user's alert_settings.medical_reminders. Used by the app Reminders tab.
    """
    data = body
    access_code = (data.get("access_code") or "").strip()
    medical_reminders = data.get("medical_reminders")
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code:
        return (400, {"message": "access_code required"})
    result = db.get_role_by_access_code(access_code)
    if not result:
        return (404, {"message": "Invalid access code"})
    role, admin_id = result
    if role != "admin":
        return (403, {"message": "Access code is not for admin"})
    act_as = (data.get("act_as_user_id") or "").strip() or None
    duid, err = _target_user_for_admin(db, admin_id, act_as, check_desktop_linked=False)
    if err:
        return err
    # Normalize structure
    if medical_reminders is None:
        medical_reminders = {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []}
    if not isinstance(medical_reminders, dict):
        return (400, {"message": "medical_reminders must be an object"})
    for key in ("appointments", "prescriptions", "lab_tests", "custom"):
        if key not in medical_reminders:
            medical_reminders[key] = []
        elif not isinstance(medical_reminders[key], list):
            medical_reminders[key] = []
    settings = db.get_alert_settings(duid) or {}
    settings["medical_reminders"] = medical_reminders
    ok = db.upsert_alert_settings(duid, settings)
    if not ok:
        return (500, {"message": "Failed to save"})
    notify_databus(access_code)
    trigger_alert_checks_for_admin(admin_id)
    return (200, {"message": "ok", "medical_reminders": medical_reminders})


def _resolve_admin_from_access_code(db, access_code):
    """Validate access code and return (admin_id, error_response_or_None)."""
    code = (access_code or "").strip()
    if not code:
        return None, (400, {"message": "access_code required"})
    result = db.get_role_by_access_code(code)
    if not result:
        return None, (404, {"message": "Invalid access code"})
    role, admin_id = result
    if role != "admin":
        return None, (403, {"message": "Access code is not for admin"})
    return admin_id, None


def _target_user_for_admin(db, admin_id, act_as_user_id, check_desktop_linked=False):
    """Return user_id to use for admin operations: act_as_user_id if valid, else dashboard user.
    If check_desktop_linked and acting as a connected user, require that user to have linked desktop once.
    Returns (user_id, None) or (None, error_response)."""
    if act_as_user_id and (act_as_user_id or "").strip():
        uid = (act_as_user_id or "").strip()
        if db.user_belongs_to_admin(uid, admin_id):
            if check_desktop_linked and not getattr(db, "user_has_desktop_linked", lambda _: True)(uid):
                return None, (400, {"message": "User must login to desktop first. Changes sync to the user's desktop."})
            return uid, None
    duid = db.get_dashboard_user_id(admin_id)
    if not duid:
        return None, (500, {"message": "Dashboard user not found"})
    return duid, None


def _safe_int(value, default):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _extract_medicine_meta_from_medicines(medicines):
    """Build medicine metadata map keyed by box_id (B1..B6) from medicines payload."""
    meta = {}
    if not isinstance(medicines, list):
        return meta
    for m in medicines:
        if not isinstance(m, dict):
            continue
        box_id = (m.get("box_id") or "").strip().upper()
        if box_id not in {"B1", "B2", "B3", "B4", "B5", "B6"}:
            continue

        times = m.get("times")
        if isinstance(times, list):
            times = [str(t).strip() for t in times if str(t).strip()]
        elif isinstance(times, str) and times.strip():
            times = [times.strip()]
        else:
            times = []

        exact_time = (m.get("exact_time") or "").strip() or (times[0] if times else "08:00")
        dose_per_day = _safe_int(m.get("dose_per_day"), 0)
        if dose_per_day <= 0:
            dose_per_day = max(1, len(times))
        instructions = (m.get("instructions") or m.get("dosage") or "").strip()
        expiry = (m.get("expiry") or "").strip()

        meta[box_id] = {
            "dose_per_day": dose_per_day,
            "exact_time": exact_time,
            "instructions": instructions,
            "expiry": expiry,
        }
    return meta


def _extract_medicine_meta_from_boxes(medicine_boxes):
    """Build medicine metadata map keyed by box_id (B1..B6) from desktop medicine_boxes payload."""
    meta = {}
    if not isinstance(medicine_boxes, dict):
        return meta
    for box_id in [f"B{i}" for i in range(1, 7)]:
        med = medicine_boxes.get(box_id)
        if not isinstance(med, dict):
            continue
        exact_time = (med.get("exact_time") or "").strip() or "08:00"
        dose_per_day = _safe_int(med.get("dose_per_day"), 1)
        if dose_per_day <= 0:
            dose_per_day = 1
        instructions = (med.get("instructions") or "").strip()
        expiry = (med.get("expiry") or "").strip()
        meta[box_id] = {
            "dose_per_day": dose_per_day,
            "exact_time": exact_time,
            "instructions": instructions,
            "expiry": expiry,
        }
    return meta
def put_admin_medicines(body, query, headers):
    """PUT { access_code, medicines:[{name,box_id,quantity,low_stock,dosage,times,expiry}] }.
    Updates admin dashboard medicines (B1..B6) without touching dose_logs.
    """
    data = body
    access_code = (data.get("access_code") or "").strip()
    medicines = data.get("medicines")

    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})

    admin_id, err = _resolve_admin_from_access_code(db, access_code)
    if err:
        return err

    if not isinstance(medicines, list):
        return (400, {"message": "medicines must be a list"})

    act_as = (data.get("act_as_user_id") or "").strip() or None
    duid, err = _target_user_for_admin(db, admin_id, act_as, check_desktop_linked=False)
    if err:
        return err

    desired = {}
    meta_payload = _extract_medicine_meta_from_medicines(medicines)

    for m in medicines:
        if not isinstance(m, dict):
            continue
        box_id = (m.get("box_id") or "").strip().upper()
        if box_id not in {"B1", "B2", "B3", "B4", "B5", "B6"}:
            continue

        name = (m.get("name") or "").strip() or "Medicine"
        qty = max(0, _safe_int(m.get("quantity"), 0))
        low_stock = max(0, _safe_int(m.get("low_stock"), 5))

        meta = meta_payload.get(box_id) or {}
        instructions = (meta.get("instructions") or m.get("dosage") or "").strip()
        exact_time = (meta.get("exact_time") or "").strip() or "08:00"
        dose_per_day = _safe_int(meta.get("dose_per_day"), 1)
        if dose_per_day <= 0:
            dose_per_day = 1

        times = m.get("times")
        if isinstance(times, list):
            times = [str(t).strip() for t in times if str(t).strip()]
        elif isinstance(times, str) and times.strip():
            times = [times.strip()]
        else:
            times = []
        if not times:
            times = [exact_time] * dose_per_day

        desired[box_id] = {
            "name": name,
            "box_id": box_id,
            "quantity": qty,
            "low_stock": low_stock,
            "dosage": instructions,
            "times": times,
        }

    existing = db.list_medicines(duid)
    by_box = {(e.get("box_id") or "").strip().upper(): e for e in existing if isinstance(e, dict)}

    for box_id in ["B1", "B2", "B3", "B4", "B5", "B6"]:
        incoming = desired.get(box_id)
        current = by_box.get(box_id)

        if incoming:
            if current:
                ok = db.update_medicine(
                    current.get("id"),
                    name=incoming["name"],
                    box_id=box_id,
                    dosage=incoming["dosage"],
                    times=incoming["times"],
                    low_stock=incoming["low_stock"],
                    quantity=incoming["quantity"],
                )
                if not ok:
                    return (500, {"message": f"Failed to update {box_id}"})
            else:
                mid = db.create_medicine(
                    duid,
                    incoming["name"],
                    box_id=box_id,
                    dosage=incoming["dosage"],
                    times=incoming["times"],
                    low_stock=incoming["low_stock"],
                    quantity=incoming["quantity"],
                )
                if not mid:
                    return (500, {"message": f"Failed to create {box_id}"})
        elif current:
            ok = db.delete_medicine(current.get("id"))
            if not ok:
                return (500, {"message": f"Failed to delete {box_id}"})

    settings = db.get_alert_settings(duid) or {}
    if not isinstance(settings, dict):
        settings = {}
    merged_meta = {}
    for box in ["B1", "B2", "B3", "B4", "B5", "B6"]:
        if box in desired:
            merged_meta[box] = meta_payload.get(box, {})
    settings["medicine_meta"] = merged_meta
    if not db.upsert_alert_settings(duid, settings):
        return (500, {"message": "Failed to save medicine metadata"})

    notify_databus(access_code)
    trigger_alert_checks_for_admin(admin_id)
    return (200, {"message": "ok", "saved_boxes": len(desired)})
def put_admin_alert_settings(body, query, headers):
    """PUT { access_code, alert_settings?, gmail_config?, medical_reminders? }.
    Saves settings under dashboard user's alert_settings JSON and notifies databus.
    """
    data = body
    access_code = (data.get("access_code") or "").strip()

    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})

    admin_id, err = _resolve_admin_from_access_code(db, access_code)
    if err:
        return err

    act_as = (data.get("act_as_user_id") or "").strip() or None
    duid, err = _target_user_for_admin(db, admin_id, act_as, check_desktop_linked=False)
    if err:
        return err

    current = db.get_alert_settings(duid) or {}
    if not isinstance(current, dict):
        current = {}

    incoming_alert = data.get("alert_settings")
    incoming_gmail = data.get("gmail_config")
    incoming_reminders = data.get("medical_reminders")

    if not isinstance(incoming_alert, dict):
        direct_keys = {"medicine_alerts", "missed_dose_escalation", "stock_alerts", "expiry_alerts"}
        top = {k: data.get(k) for k in direct_keys if isinstance(data.get(k), dict)}
        if top:
            incoming_alert = top

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
    if isinstance(incoming_reminders, dict):
        current["medical_reminders"] = incoming_reminders

    ok = db.upsert_alert_settings(duid, current)
    if not ok:
        return (500, {"message": "Failed to save settings"})

    notify_databus(access_code)
    trigger_alert_checks_for_admin(admin_id)
    return (200, {"message": "ok", "settings": current})


# ---- Medicines ----
def list_medicines(body, query, headers):
    user_id = query.get("user_id")
    db = get_db()
    if not db:
        return (503, [])
    return (200, db.list_medicines(user_id))
def create_medicine(body, query, headers):
    data = body
    user_id = data.get("user_id")
    name = (data.get("name") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not user_id or not name:
        return (400, {"message": "user_id and name required"})
    mid = db.create_medicine(user_id, name, box_id=data.get("box_id"), dosage=data.get("dosage"), times=data.get("times"), low_stock=data.get("low_stock", 5))
    if not mid:
        return (500, {"message": "create failed"})
    admin_id = db.get_admin_id_by_user_id(user_id)
    trigger_alert_checks_for_admin(admin_id)
    # Keep desktop/app/user standalone live via databus when legacy /medicines endpoint is used.
    try:
        if admin_id:
            access_code = db.get_admin_access_code_by_id(admin_id)
            if access_code:
                notify_databus(access_code)
    except Exception:
        pass
    return (200, {"id": mid, "user_id": user_id, "name": name, "box_id": data.get("box_id"), "dosage": data.get("dosage"), "times": data.get("times") or [], "low_stock": data.get("low_stock", 5)})
def update_medicine(medicine_id, body, query, headers):
    data = body
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    user_id = db.get_user_id_by_medicine_id(medicine_id)
    ok = db.update_medicine(medicine_id, name=data.get("name"), box_id=data.get("box_id"), dosage=data.get("dosage"), times=data.get("times"), low_stock=data.get("low_stock"))
    if not ok:
        return (404, {"message": "update failed"})
    admin_id = db.get_admin_id_by_user_id(user_id) if user_id else None
    trigger_alert_checks_for_admin(admin_id)
    try:
        if admin_id:
            access_code = db.get_admin_access_code_by_id(admin_id)
            if access_code:
                notify_databus(access_code)
    except Exception:
        pass
    return (200, {"message": "ok"})
def delete_medicine(medicine_id, body, query, headers):
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    user_id = db.get_user_id_by_medicine_id(medicine_id)
    ok = db.delete_medicine(medicine_id)
    if not ok:
        return (404, {"message": "not found"})
    admin_id = db.get_admin_id_by_user_id(user_id) if user_id else None
    trigger_alert_checks_for_admin(admin_id)
    try:
        if admin_id:
            access_code = db.get_admin_access_code_by_id(admin_id)
            if access_code:
                notify_databus(access_code)
    except Exception:
        pass
    return (204, None)


# ---- Dose logs ----
def create_dose_log(body, query, headers):
    data = body
    user_id = data.get("user_id")
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not user_id:
        return (400, {"message": "user_id required"})
    lid = db.create_dose_log(user_id, medicine_id=data.get("medicine_id"), box_id=data.get("box_id"), taken_at=data.get("taken_at"), source=data.get("source", "desktop"))
    if not lid:
        return (500, {"message": "create failed"})
    trigger_alert_checks_for_admin(db.get_admin_id_by_user_id(user_id))
    return (200, {"id": lid})
def list_dose_logs(body, query, headers):
    user_id = query.get("user_id")
    from_ = query.get("from")
    to = query.get("to")
    db = get_db()
    if not db:
        return (503, [])
    return (200, db.list_dose_logs(user_id, from_=from_, to=to))


# ---- Alerts ----
def create_alert(body, query, headers):
    data = body
    user_id = data.get("user_id")
    admin_id = data.get("admin_id")
    type_ = data.get("type") or "info"
    message = data.get("message") or ""
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not user_id or not admin_id:
        return (400, {"message": "user_id and admin_id required"})
    aid = db.create_alert(user_id, admin_id, type_, message)
    if not aid:
        return (500, {"message": "create failed"})
    return (200, {"success": True, "id": aid})
def list_alerts(body, query, headers):
    user_id = query.get("user_id")
    status = query.get("status")
    db = get_db()
    if not db:
        return (503, [])
    return (200, db.list_alerts(user_id=user_id, status=status))


# ---- Alert settings ----
def get_alert_settings(body, query, headers):
    user_id = query.get("user_id")
    db = get_db()
    if not db:
        return (503, {})
    return (200, db.get_alert_settings(user_id))
def put_alert_settings(body, query, headers):
    data = body
    user_id = data.get("user_id")
    settings = data.get("settings")
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not user_id:
        return (400, {"message": "user_id required"})
    ok = db.upsert_alert_settings(user_id, settings or {})
    if ok:
        trigger_alert_checks_for_admin(db.get_admin_id_by_user_id(user_id))
    return (200, settings or {}) if ok else (500, {"message": "failed"})


# ---- Sync ----
def sync_get(body, query, headers):
    bot_id = query.get("bot_id", "").strip()
    api_key = query.get("api_key", "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    out = db.get_sync(bot_id, api_key)
    if out is None:
        return (404, {"message": "user not found for bot_id+api_key"})
    return (200, out)
def sync_post(body, query, headers):
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    out = db.get_sync(bot_id, api_key)
    if out is None:
        return (404, {"message": "user not found for bot_id+api_key"})
    return (200, out)


