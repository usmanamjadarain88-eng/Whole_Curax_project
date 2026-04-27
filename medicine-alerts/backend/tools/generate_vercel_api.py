"""Generate api/*.py, utils/dev_router.py, and vercel rewrites snippet."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = ROOT / "api"
API.mkdir(exist_ok=True)

# (file_stem, {METHOD: function_name_in_route_handlers})  — upper METHOD
SIMPLE = [
    # Not named "index": on Vercel api/index maps to /api and can swallow /api/* sibling routes.
    ("welcome", {"GET": "root"}),
    ("health", {"GET": "health"}),
    ("save_credentials", {"POST": "save_credentials"}),
    ("connect_to_admin", {"POST": "connect_to_admin"}),
    ("signup_start", {"POST": "signup_start"}),
    ("signup_verify_email", {"POST": "signup_verify_email"}),
    ("signup_link_admin", {"POST": "signup_link_admin"}),
    ("signup_sign_in", {"POST": "signup_sign_in"}),
    ("user_account_status", {"GET": "user_account_status"}),
    ("maintenance_cleanup_pending", {"GET": "maintenance_cleanup_pending_cron", "POST": "maintenance_cleanup_pending"}),
    ("maintenance_run_alert_checks", {"POST": "maintenance_run_alert_checks"}),
    ("notify_event", {"POST": "notify_event"}),
    ("notify_event_by_user", {"POST": "notify_event_by_user"}),
    ("notify_event_to_user", {"POST": "notify_event_to_user"}),
    ("admin_linked_users", {"GET": "get_linked_users"}),
    ("admin_fcm_token", {"PUT": "put_admin_fcm_token"}),
    ("admin_connection", {"GET": "get_admin_connection"}),
    ("verify_credentials", {"POST": "verify_credentials"}),
    ("get_role", {"GET": "get_role"}),
    ("admin_data", {"GET": "admin_data"}),
    ("user_data", {"GET": "user_data"}),
    ("user_display_mode", {"POST": "user_post_display_mode"}),
    ("user_profile_picture", {"POST": "user_post_profile_picture"}),
    ("user_standalone_sync", {"POST": "user_standalone_sync"}),
    ("user_databus_room", {"GET": "user_databus_room"}),
    ("user_plans", {"GET": "user_plans_get", "POST": "user_plans_post", "PATCH": "user_plans_patch", "DELETE": "user_plans_delete"}),
    ("admin_sync", {"POST": "admin_sync"}),
    ("admin_notify", {"POST": "admin_notify"}),
    ("admin_delete", {"DELETE": "delete_admin"}),
    ("admin_create_desktop_link_code", {"POST": "create_desktop_link_code"}),
    ("desktop_link_to_admin", {"POST": "desktop_link_to_admin"}),
    ("user_create_desktop_link_code", {"POST": "user_create_desktop_link_code"}),
    ("user_desktop_by_code", {"POST": "user_desktop_by_code"}),
    ("admin_medical_reminders", {"PUT": "put_admin_medical_reminders"}),
    ("admin_medicines", {"PUT": "put_admin_medicines"}),
    ("admin_alert_settings", {"PUT": "put_admin_alert_settings"}),
    ("medicines", {"GET": "list_medicines", "POST": "create_medicine"}),
    ("dose_logs", {"GET": "list_dose_logs", "POST": "create_dose_log"}),
    ("alerts", {"GET": "list_alerts", "POST": "create_alert"}),
    ("alert_settings", {"GET": "get_alert_settings", "PUT": "put_alert_settings"}),
    ("sync", {"GET": "sync_get", "POST": "sync_post"}),
]

SPECIAL_ADMIN_USER = '''from utils.vercel_adapter import make_handler
from utils.route_handlers import delete_admin_user


def _delete(body, query, headers):
    uid = (query.get("user_id") or "").strip()
    if not uid:
        return 400, {"message": "user_id required"}
    return delete_admin_user(uid, body, query, headers)


class handler(make_handler(delete_fn=_delete)):
    pass
'''

SPECIAL_MEDICINE = '''from utils.vercel_adapter import make_handler
from utils.route_handlers import update_medicine, delete_medicine


def _patch(body, query, headers):
    mid = (query.get("medicine_id") or "").strip()
    if not mid:
        return 400, {"message": "medicine_id required"}
    return update_medicine(mid, body, query, headers)


def _delete(body, query, headers):
    mid = (query.get("medicine_id") or "").strip()
    if not mid:
        return 400, {"message": "medicine_id required"}
    return delete_medicine(mid, body, query, headers)


class handler(make_handler(patch_fn=_patch, delete_fn=_delete)):
    pass
'''


def make_simple_file(stem: str, methods: dict) -> str:
    imps = ", ".join(sorted(set(methods.values())))
    lines = [
        "from utils.vercel_adapter import make_handler",
        f"from utils.route_handlers import {imps}",
        "",
        "class handler(",
        "    make_handler(",
    ]
    for m, fn in sorted(methods.items(), key=lambda x: x[0]):
        key = f"{m.lower()}_fn"
        lines.append(f"        {key}={fn},")
    lines += [
        "    )",
        "):",
        "    pass",
    ]
    return "\n".join(lines)


def main() -> None:
    for stem, methods in SIMPLE:
        (API / f"{stem}.py").write_text(make_simple_file(stem, methods), encoding="utf-8")

    (API / "admin_user_delete.py").write_text(SPECIAL_ADMIN_USER, encoding="utf-8")
    (API / "medicine_by_id.py").write_text(SPECIAL_MEDICINE, encoding="utf-8")

    # dev_router.py — same paths as public API (used by stdlib api_server)
    lines = [
        '"""Dispatch (method, path) -> route_handlers call for local stdlib server."""',
        "from __future__ import annotations",
        "",
        "import re",
        "from typing import Any, Callable, List, Tuple",
        "",
        "from utils import route_handlers as rh",
        "",
        "Handler = Callable[..., Tuple[int, Any]]",
        "",
        "_STATIC: List[Tuple[str, str, str]] = [",
    ]
    public_paths: dict[str, str] = {
        "welcome": "/",
        "health": "/health",
        "save_credentials": "/save-credentials",
        "connect_to_admin": "/connect-to-admin",
        "signup_start": "/signup/start",
        "signup_verify_email": "/signup/verify-email",
        "signup_link_admin": "/signup/link-admin",
        "signup_sign_in": "/signup/sign-in",
        "user_account_status": "/user/account-status",
        "maintenance_cleanup_pending": "/maintenance/cleanup-pending",
        "maintenance_run_alert_checks": "/maintenance/run-alert-checks",
        "notify_event": "/notify-event",
        "notify_event_by_user": "/notify-event-by-user",
        "notify_event_to_user": "/notify-event-to-user",
        "admin_linked_users": "/admin/linked-users",
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
    for stem, methods in SIMPLE:
        path = public_paths[stem]
        for m, fn in methods.items():
            lines.append(f'    ("{m}", "{path}", "{fn}"),')
    lines += [
        '    ("PUT", "/admin/inventory", "put_admin_medicines"),',
        '    ("PUT", "/admin/settings", "put_admin_alert_settings"),',
    ]
    lines.append("]")
    lines += [
        "",
        "_DYN = [",
        '    (re.compile(r"^/admin/users/([^/]+)$"), "DELETE", "delete_admin_user"),',
        '    (re.compile(r"^/medicines/([^/]+)$"), "PATCH", "update_medicine"),',
        '    (re.compile(r"^/medicines/([^/]+)$"), "DELETE", "delete_medicine"),',
        "]",
        "",
        "_NAME_TO_FN = {",
    ]
    names = set()
    for _, methods in SIMPLE:
        names.update(methods.values())
    names.update(["delete_admin_user", "update_medicine", "delete_medicine"])
    for n in sorted(names):
        lines.append(f'    "{n}": rh.{n},')
    lines += [
        "}",
        "",
        "_STEM_TO_PUBLIC_PATH: dict[str, str] = {",
    ]
    for stem in public_paths:
        lines.append(f'    "{stem}": "{public_paths[stem]}",')
    lines += [
        "}",
        "",
        "def normalize_vercel_api_path(method: str, path_only: str, query: dict) -> str:",
        '    """Map Vercel URL (/api/stem or pretty paths) to the path shape used by dispatch()."""',
        "    p = (path_only or '/').rstrip('/') or '/'",
        '    if p in ("/", "/api", "/api/welcome"):',
        '        return "/"',
        '    if not p.startswith("/api/"):',
        "        return p",
        '    stem = (p[len("/api/"):]).split("/")[0]',
        '    if stem == "medicine_by_id":',
        '        mid = (query.get("medicine_id") or "").strip()',
        '        if mid:',
        '            return f"/medicines/{mid}"',
        '    if stem == "admin_user_delete":',
        '        uid = (query.get("user_id") or "").strip()',
        '        if uid:',
        '            return f"/admin/users/{uid}"',
        "    return _STEM_TO_PUBLIC_PATH.get(stem, p)",
        "",
        "def dispatch(method: str, path: str, body: dict, query: dict, headers: dict) -> Tuple[int, Any]:",
        '    path_only = path.split("?")[0]',
        '    path_only = (path_only or "/").rstrip("/") or "/"',
        "    for m, pfx, name in _STATIC:",
        "        if m == method and path_only == pfx:",
        "            fn = _NAME_TO_FN[name]",
        "            return fn(body, query, headers)",
        "    for rx, m, name in _DYN:",
        "        if m != method:",
        "            continue",
        "        mo = rx.match(path_only)",
        "        if not mo:",
        "            continue",
        "        fn = _NAME_TO_FN[name]",
        "        return fn(mo.group(1), body, query, headers)",
        '    return 404, {"message": "Not found", "path": path_only, "method": method}',
        "",
        "def public_route_index() -> list[dict[str, str]]:",
        '    """All static and dynamic URL shapes handled by dispatch (canonical paths)."""',
        '    rows: list[dict[str, str]] = [{"method": m, "path": p} for m, p, _ in _STATIC]',
        '    rows.append({"method": "DELETE", "path": "/admin/users/{user_id}"})',
        '    rows.append({"method": "PATCH", "path": "/medicines/{medicine_id}"})',
        '    rows.append({"method": "DELETE", "path": "/medicines/{medicine_id}"})',
        "    return rows",
        "",
    ]
    (ROOT / "utils" / "dev_router.py").write_text("\n".join(lines), encoding="utf-8")

    rewrites = [
        {
            "source": "/medicines/:medicine_id",
            "destination": "/api/medicine_by_id?medicine_id=:medicine_id",
        },
        {
            "source": "/admin/users/:user_id",
            "destination": "/api/admin_user_delete?user_id=:user_id",
        },
    ]

    def dest_url(stem: str) -> str:
        return f"/api/{stem}"

    for stem, path in public_paths.items():
        if stem == "welcome":
            rewrites.append({"source": "/", "destination": "/api/welcome"})
            continue
        rewrites.append({"source": path, "destination": dest_url(stem)})

    # Exact /api (no extra segment) → same handler as / (not api/index, which would own /api/*).
    rewrites.insert(
        2,
        {"source": "/api", "destination": "/api/welcome"},
    )

    rewrites.append(
        {"source": "/admin/inventory", "destination": "/api/admin_medicines"}
    )
    rewrites.append(
        {"source": "/admin/settings", "destination": "/api/admin_alert_settings"}
    )

    vj = {
        "$schema": "https://openapi.vercel.sh/vercel.json",
        "framework": "python",
        "rewrites": rewrites,
    }
    (ROOT / "vercel.json").write_text(json.dumps(vj, indent=2) + "\n", encoding="utf-8")
    print("Generated api/*.py, utils/dev_router.py, vercel.json")


if __name__ == "__main__":
    main()
