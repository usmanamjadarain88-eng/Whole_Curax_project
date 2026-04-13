"""
Stateless route logic for Vercel serverless.
Returns (status_code, body); body is dict|list|str|None (204).
"""
from __future__ import annotations

import asyncio
import json
import os
import threading
import time

from central_db import EmailAlreadyUsedError

from utils.databus import notify_databus
from utils.db import get_db
from utils.scheduler_shim import trigger_alert_checks_for_admin

def root(body, query, headers):
    """So visiting the backend URL in a browser shows something instead of 404."""
    return (200, {"status": "ok", "message": "Medicine Alerts API", "docs": "Use POST/GET /save-credentials, /admin/data, /medicines, etc."})
def health(body, query, headers):
    """Health check for Railway/monitoring."""
    db = get_db()
    return (200, {"status": "ok", "database": "connected" if (db and db.is_available()) else "disconnected"})


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
                    access_code, bot_id, api_key, name=name, email=email, fcm_token=fcm_token or None
                )
                if admin_id:
                    notify_databus(admin_access_code or access_code)
                    return (200, {"message": "ok", "admin_id": admin_id, "admin_access_code": admin_access_code or None, "connection_code": connection_code or None})
            admin_id, admin_access_code, connection_code = db.upsert_admin_from_bot(bot_id, api_key, name=name, email=email, fcm_token=fcm_token or None)
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
    """POST { email, password } -> OTP issued (see server log); optional dev_otp if SIGNUP_DEV_RETURN_OTP=1."""
    data = body
    email = (data.get("email") or "").strip()
    password = (data.get("password") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.signup_flow_start(email, password)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 503 if err == "signup_not_configured" else 400
        return (code, {"message": err, "detail": r.get("detail")})
    out = {"message": r.get("message", "ok")}
    if r.get("dev_otp"):
        out["dev_otp"] = r["dev_otp"]
    return (200, out)
def signup_verify_email(body, query, headers):
    """POST { email, otp } -> session moves to PENDING_ADMIN."""
    data = body
    email = (data.get("email") or "").strip()
    otp = (data.get("otp") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    r = db.signup_flow_verify_email(email, otp)
    if not r.get("ok"):
        err = r.get("error") or "error"
        code = 503 if err == "signup_not_configured" else 400
        if err in ("session_not_found",):
            code = 404
        if err in ("wrong_state",):
            code = 409
        return (code, {"message": err, "account_status": r.get("account_status")})
    return (200, {"message": r.get("message", "ok"), "account_status": r.get("account_status")})
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
        "account_status": r.get("account_status", "ACTIVE"),
    })
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
def maintenance_cleanup_pending(body, query, headers):
    """POST optional JSON { hours_sessions, hours_users } - requires X-Maintenance-Key matching MAINTENANCE_API_KEY."""
    key = (headers.get("x-maintenance-key") or "").strip()
    expected = (os.environ.get("MAINTENANCE_API_KEY") or "").strip()
    if not expected or key != expected:
        return (404, {"message": "Not found"})
    data = body
    hs = int(data.get("hours_sessions") or 24)
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


def _send_alert_via_relay(bot_id, api_key, alert_type, message, fcm_token=None):
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
    """POST { access_code, event_type, message } ΓåÆ desktop tells backend of admin-only events.
    Backend finds admin bot_id+api_key by access_code, sends alert to relay ΓåÆ admin's app (FCM preferred).
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
    bid, akey = bot.get("bot_id"), bot.get("api_key")
    fcm = (bot.get("fcm_token") or "").strip() or None
    if not fcm:
        fcm = db.get_fcm_token_for_bot(bid, akey) or None
    def _deliver():
        db2 = get_db()
        fcm_now = (fcm or (db2.get_fcm_token_for_bot(bid, akey) if db2 else None) or "").strip() or None
        _send_alert_via_relay(bid, akey, event_type, message, fcm_token=fcm_now)
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
    def _deliver():
        _send_alert_via_relay(admin_bot_id, admin_api_key, event_type, message, fcm_token=admin_fcm)
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
    """GET /admin/linked-users?access_code=... -> list of users linked to this admin (excluding dashboard user)."""
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
    return (200, {"users": users})
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
        "linked_users": [{"id": str(u.get("id", "")), "name": u.get("name"), "email": u.get("email") or "", "bot_id": u.get("bot_id")} for u in users],
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
    }
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
    """POST { "access_code": "..." } ΓåÆ admin creates a one-time code for a user to link desktop (user view).
    Returns { "code": "ABC12XYZ", "expires_in": 600 }. Only the admin (with access_code) can create this."""
    data = body
    access_code = (data.get("access_code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not access_code:
        return (400, {"message": "access_code required"})
    code, admin_id, admin_name = db.create_desktop_link_code(access_code, expires_seconds=300)
    if not code:
        return (404, {"message": "Invalid access code or could not create code"})
    return (200, {"code": code, "expires_in": 300, "admin_name": admin_name})
def desktop_link_to_admin(body, query, headers):
    """POST { "code": "..." } ΓåÆ desktop enters the code from admin; links to that admin (user view only).
    Returns { "admin_id": "...", "admin_name": "..." }. Code is consumed (one-time use)."""
    data = body
    code = (data.get("code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not code:
        return (400, {"message": "code required"})
    info = db.get_admin_by_desktop_link_code(code)
    if not info:
        return (404, {"message": "Invalid or expired code"})
    return (200, {"admin_id": info["admin_id"], "admin_name": info["admin_name"]})
def user_create_desktop_link_code(body, query, headers):
    """POST { "bot_id": "...", "api_key": "..." } ΓåÆ user (app) creates a one-time code for desktop to link to this user.
    Returns { "code": "...", "expires_in": 300, "user_name": "..." }. Code valid 5 min; one-time use."""
    data = body
    bot_id = (data.get("bot_id") or "").strip()
    api_key = (data.get("api_key") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not bot_id or not api_key:
        return (400, {"message": "bot_id and api_key required"})
    code, user_id, user_name = db.create_user_desktop_link_code(bot_id, api_key, expires_seconds=300)
    if not code:
        return (404, {"message": "User not found or could not create code"})
    return (200, {"code": code, "expires_in": 300, "user_name": user_name})
def user_desktop_by_code(body, query, headers):
    """POST { "code": "..." } ΓåÆ desktop enters the code from the user app; links to that user.
    Returns { "user_id": "...", "user_name": "..." }. Code is consumed (one-time use)."""
    data = body
    code = (data.get("code") or "").strip()
    db = get_db()
    if not db:
        return (503, {"message": "Central DB not configured"})
    if not code:
        return (400, {"message": "code required"})
    info = db.get_user_by_desktop_link_code(code)
    if not info:
        return (404, {"message": "Invalid or expired code"})
    return (200, {
        "user_id": info["user_id"],
        "user_name": info["user_name"],
        "bot_id": info.get("bot_id", ""),
        "api_key": info.get("api_key", ""),
        "admin_id": info.get("admin_id", ""),
        "admin_name": info.get("admin_name", "Admin"),
    })
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
        current["gmail_config"] = incoming_gmail
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


