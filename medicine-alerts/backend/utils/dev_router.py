"""Dispatch (method, path) -> route_handlers call for local stdlib server."""
from __future__ import annotations

import re
from typing import Any, Callable, List, Tuple

from utils import route_handlers as rh

Handler = Callable[..., Tuple[int, Any]]

_STATIC: List[Tuple[str, str, str]] = [
    ("GET", "/", "root"),
    ("GET", "/health", "health"),
    ("POST", "/save-credentials", "save_credentials"),
    ("POST", "/admin/mobile-sign-in-start", "admin_mobile_sign_in_start"),
    ("POST", "/admin/mobile-sign-in-verify", "admin_mobile_sign_in_verify"),
    ("POST", "/admin/email-signup/start", "admin_email_signup_start"),
    ("POST", "/admin/email-signup/verify", "admin_email_signup_verify"),
    ("POST", "/connect-to-admin", "connect_to_admin"),
    ("POST", "/signup/start", "signup_start"),
    ("POST", "/signup/verify-email", "signup_verify_email"),
    ("POST", "/signup/link-admin", "signup_link_admin"),
    ("GET", "/signup/admins-directory", "signup_list_admins_directory"),
    ("POST", "/signup/request-admin-link", "signup_request_admin_link"),
    ("GET", "/signup/link-request-status", "signup_link_request_status"),
    ("POST", "/signup/sign-in", "signup_sign_in"),
    ("POST", "/password-reset/start", "password_reset_start"),
    ("POST", "/password-reset/complete", "password_reset_complete"),
    ("GET", "/user/account-status", "user_account_status"),
    ("GET", "/maintenance/cleanup-pending", "maintenance_cleanup_pending_cron"),
    ("POST", "/maintenance/cleanup-pending", "maintenance_cleanup_pending"),
    ("POST", "/maintenance/run-alert-checks", "maintenance_run_alert_checks"),
    ("POST", "/notify-event", "notify_event"),
    ("POST", "/notify-event-by-user", "notify_event_by_user"),
    ("POST", "/notify-event-to-user", "notify_event_to_user"),
    ("GET", "/admin/linked-users", "get_linked_users"),
    ("GET", "/admin/pending-user-link-requests", "admin_pending_user_link_requests"),
    ("POST", "/admin/accept-user-link-request", "admin_accept_user_link_request"),
    ("POST", "/admin/reject-user-link-request", "admin_reject_user_link_request"),
    ("POST", "/admin/set-user-display-mode", "admin_set_user_display_mode"),
    ("PUT", "/admin/fcm-token", "put_admin_fcm_token"),
    ("GET", "/admin/connection", "get_admin_connection"),
    ("POST", "/verify-credentials", "verify_credentials"),
    ("GET", "/get-role", "get_role"),
    ("GET", "/admin/data", "admin_data"),
    ("GET", "/user/data", "user_data"),
    ("POST", "/user/display-mode", "user_post_display_mode"),
    ("POST", "/user/profile-picture", "user_post_profile_picture"),
    ("POST", "/user/standalone-sync", "user_standalone_sync"),
    ("GET", "/user/databus-room", "user_databus_room"),
    ("GET", "/user/plans", "user_plans_get"),
    ("POST", "/user/plans", "user_plans_post"),
    ("PATCH", "/user/plans", "user_plans_patch"),
    ("DELETE", "/user/plans", "user_plans_delete"),
    ("POST", "/admin/sync", "admin_sync"),
    ("POST", "/admin/clear-user-dose-logs", "admin_clear_user_dose_logs"),
    ("POST", "/admin/notify", "admin_notify"),
    ("DELETE", "/admin", "delete_admin"),
    ("POST", "/admin/create-desktop-link-code", "create_desktop_link_code"),
    ("POST", "/desktop/link-to-admin", "desktop_link_to_admin"),
    ("POST", "/user/create-desktop-link-code", "user_create_desktop_link_code"),
    ("POST", "/user/desktop-by-code", "user_desktop_by_code"),
    ("PUT", "/admin/medical_reminders", "put_admin_medical_reminders"),
    ("PUT", "/admin/medicines", "put_admin_medicines"),
    ("PUT", "/admin/alert_settings", "put_admin_alert_settings"),
    ("GET", "/medicines", "list_medicines"),
    ("POST", "/medicines", "create_medicine"),
    ("GET", "/dose_logs", "list_dose_logs"),
    ("POST", "/dose_logs", "create_dose_log"),
    ("GET", "/alerts", "list_alerts"),
    ("POST", "/alerts", "create_alert"),
    ("GET", "/alert_settings", "get_alert_settings"),
    ("PUT", "/alert_settings", "put_alert_settings"),
    ("GET", "/sync", "sync_get"),
    ("POST", "/sync", "sync_post"),
    ("PUT", "/admin/inventory", "put_admin_medicines"),
    ("PUT", "/admin/settings", "put_admin_alert_settings"),
]

_DYN = [
    (re.compile(r"^/admin/users/([^/]+)$"), "DELETE", "delete_admin_user"),
    (re.compile(r"^/medicines/([^/]+)$"), "PATCH", "update_medicine"),
    (re.compile(r"^/medicines/([^/]+)$"), "DELETE", "delete_medicine"),
]

_NAME_TO_FN = {
    "admin_accept_user_link_request": rh.admin_accept_user_link_request,
    "admin_clear_user_dose_logs": rh.admin_clear_user_dose_logs,
    "admin_data": rh.admin_data,
    "admin_email_signup_start": rh.admin_email_signup_start,
    "admin_email_signup_verify": rh.admin_email_signup_verify,
    "admin_mobile_sign_in_start": rh.admin_mobile_sign_in_start,
    "admin_mobile_sign_in_verify": rh.admin_mobile_sign_in_verify,
    "admin_notify": rh.admin_notify,
    "admin_pending_user_link_requests": rh.admin_pending_user_link_requests,
    "admin_set_user_display_mode": rh.admin_set_user_display_mode,
    "admin_sync": rh.admin_sync,
    "connect_to_admin": rh.connect_to_admin,
    "create_alert": rh.create_alert,
    "create_desktop_link_code": rh.create_desktop_link_code,
    "create_dose_log": rh.create_dose_log,
    "create_medicine": rh.create_medicine,
    "delete_admin": rh.delete_admin,
    "delete_admin_user": rh.delete_admin_user,
    "delete_medicine": rh.delete_medicine,
    "desktop_link_to_admin": rh.desktop_link_to_admin,
    "get_admin_connection": rh.get_admin_connection,
    "get_alert_settings": rh.get_alert_settings,
    "get_linked_users": rh.get_linked_users,
    "get_role": rh.get_role,
    "health": rh.health,
    "list_alerts": rh.list_alerts,
    "list_dose_logs": rh.list_dose_logs,
    "list_medicines": rh.list_medicines,
    "maintenance_cleanup_pending": rh.maintenance_cleanup_pending,
    "maintenance_cleanup_pending_cron": rh.maintenance_cleanup_pending_cron,
    "maintenance_run_alert_checks": rh.maintenance_run_alert_checks,
    "notify_event": rh.notify_event,
    "notify_event_by_user": rh.notify_event_by_user,
    "notify_event_to_user": rh.notify_event_to_user,
    "password_reset_complete": rh.password_reset_complete,
    "password_reset_start": rh.password_reset_start,
    "put_admin_alert_settings": rh.put_admin_alert_settings,
    "put_admin_fcm_token": rh.put_admin_fcm_token,
    "put_admin_medical_reminders": rh.put_admin_medical_reminders,
    "put_admin_medicines": rh.put_admin_medicines,
    "put_alert_settings": rh.put_alert_settings,
    "root": rh.root,
    "save_credentials": rh.save_credentials,
    "signup_link_admin": rh.signup_link_admin,
    "signup_link_request_status": rh.signup_link_request_status,
    "signup_list_admins_directory": rh.signup_list_admins_directory,
    "signup_request_admin_link": rh.signup_request_admin_link,
    "signup_sign_in": rh.signup_sign_in,
    "signup_start": rh.signup_start,
    "signup_verify_email": rh.signup_verify_email,
    "sync_get": rh.sync_get,
    "sync_post": rh.sync_post,
    "update_medicine": rh.update_medicine,
    "user_account_status": rh.user_account_status,
    "user_create_desktop_link_code": rh.user_create_desktop_link_code,
    "user_data": rh.user_data,
    "user_databus_room": rh.user_databus_room,
    "user_desktop_by_code": rh.user_desktop_by_code,
    "user_plans_delete": rh.user_plans_delete,
    "user_plans_get": rh.user_plans_get,
    "user_plans_patch": rh.user_plans_patch,
    "user_plans_post": rh.user_plans_post,
    "user_post_display_mode": rh.user_post_display_mode,
    "user_post_profile_picture": rh.user_post_profile_picture,
    "user_standalone_sync": rh.user_standalone_sync,
    "verify_credentials": rh.verify_credentials,
}

_STEM_TO_PUBLIC_PATH: dict[str, str] = {
    "welcome": "/",
    "health": "/health",
    "save_credentials": "/save-credentials",
    "admin_mobile_sign_in_start": "/admin/mobile-sign-in-start",
    "admin_mobile_sign_in_verify": "/admin/mobile-sign-in-verify",
    "admin_email_signup_start": "/admin/email-signup/start",
    "admin_email_signup_verify": "/admin/email-signup/verify",
    "connect_to_admin": "/connect-to-admin",
    "signup_start": "/signup/start",
    "signup_verify_email": "/signup/verify-email",
    "signup_link_admin": "/signup/link-admin",
    "signup_admins_directory": "/signup/admins-directory",
    "signup_request_admin_link": "/signup/request-admin-link",
    "signup_link_request_status": "/signup/link-request-status",
    "signup_sign_in": "/signup/sign-in",
    "password_reset_start": "/password-reset/start",
    "password_reset_complete": "/password-reset/complete",
    "user_account_status": "/user/account-status",
    "maintenance_cleanup_pending": "/maintenance/cleanup-pending",
    "maintenance_run_alert_checks": "/maintenance/run-alert-checks",
    "notify_event": "/notify-event",
    "notify_event_by_user": "/notify-event-by-user",
    "notify_event_to_user": "/notify-event-to-user",
    "admin_linked_users": "/admin/linked-users",
    "admin_pending_user_link_requests": "/admin/pending-user-link-requests",
    "admin_accept_user_link_request": "/admin/accept-user-link-request",
    "admin_reject_user_link_request": "/admin/reject-user-link-request",
    "admin_set_user_display_mode": "/admin/set-user-display-mode",
    "admin_fcm_token": "/admin/fcm-token",
    "admin_connection": "/admin/connection",
    "verify_credentials": "/verify-credentials",
    "get_role": "/get-role",
    "admin_data": "/admin/data",
    "user_data": "/user/data",
    "user_display_mode": "/user/display-mode",
    "user_profile_picture": "/user/profile-picture",
    "user_standalone_sync": "/user/standalone-sync",
    "user_databus_room": "/user/databus-room",
    "user_plans": "/user/plans",
    "admin_sync": "/admin/sync",
    "admin_notify": "/admin/notify",
    "admin_delete": "/admin",
    "admin_create_desktop_link_code": "/admin/create-desktop-link-code",
    "desktop_link_to_admin": "/desktop/link-to-admin",
    "user_create_desktop_link_code": "/user/create-desktop-link-code",
    "user_desktop_by_code": "/user/desktop-by-code",
    "admin_medical_reminders": "/admin/medical_reminders",
    "admin_medicines": "/admin/medicines",
    "admin_alert_settings": "/admin/alert_settings",
    "medicines": "/medicines",
    "dose_logs": "/dose_logs",
    "alerts": "/alerts",
    "alert_settings": "/alert_settings",
    "sync": "/sync",
}

def normalize_vercel_api_path(method: str, path_only: str, query: dict) -> str:
    """Map Vercel URL (/api/stem or pretty paths) to the path shape used by dispatch()."""
    p = (path_only or '/').rstrip('/') or '/'
    if p in ("/", "/api", "/api/welcome"):
        return "/"
    if not p.startswith("/api/"):
        return p
    stem = (p[len("/api/"):]).split("/")[0]
    if stem == "medicine_by_id":
        mid = (query.get("medicine_id") or "").strip()
        if mid:
            return f"/medicines/{mid}"
    if stem == "admin_user_delete":
        uid = (query.get("user_id") or "").strip()
        if uid:
            return f"/admin/users/{uid}"
    return _STEM_TO_PUBLIC_PATH.get(stem, p)

def dispatch(method: str, path: str, body: dict, query: dict, headers: dict) -> Tuple[int, Any]:
    path_only = path.split("?")[0]
    path_only = (path_only or "/").rstrip("/") or "/"
    for m, pfx, name in _STATIC:
        if m == method and path_only == pfx:
            fn = _NAME_TO_FN[name]
            return fn(body, query, headers)
    for rx, m, name in _DYN:
        if m != method:
            continue
        mo = rx.match(path_only)
        if not mo:
            continue
        fn = _NAME_TO_FN[name]
        return fn(mo.group(1), body, query, headers)
    return 404, {"message": "Not found", "path": path_only, "method": method}

def public_route_index() -> list[dict[str, str]]:
    """All static and dynamic URL shapes handled by dispatch (canonical paths)."""
    rows: list[dict[str, str]] = [{"method": m, "path": p} for m, p, _ in _STATIC]
    rows.append({"method": "DELETE", "path": "/admin/users/{user_id}"})
    rows.append({"method": "PATCH", "path": "/medicines/{medicine_id}"})
    rows.append({"method": "DELETE", "path": "/medicines/{medicine_id}"})
    return rows
