"""Shared HTTP helpers for admin desktop tabs (same APIs as Android admin app)."""
import json
import urllib.parse
import urllib.request


def admin_display_name(controller) -> str:
    """Admin name for labels/alerts — from session, then local credentials, never empty."""
    name = (getattr(controller, "logged_in_admin_name", None) or "").strip()
    if name:
        return name
    db = getattr(controller, "_db", None)
    if db and hasattr(db, "get_admin_info"):
        try:
            info = db.get_admin_info() or {}
            name = (info.get("name") or "").strip()
            if name:
                return name
        except Exception:
            pass
    return "Admin"


def admin_access_code(controller) -> str:
    db = getattr(controller, "_db", None)
    if db and hasattr(db, "get"):
        code = (db.get("admin_access_code") or "").strip()
        if code:
            return code
    return ""


def admin_access_code_resolved(controller) -> str:
    """Resolve access code including backend recovery — background threads only."""
    code = admin_access_code(controller)
    if code:
        return code
    if hasattr(controller, "_get_access_code"):
        try:
            return (controller._get_access_code() or "").strip()
        except Exception:
            pass
    return ""


def api_base(controller) -> str:
    fn = getattr(controller, "get_central_api_base_url", None)
    return (fn() or "").rstrip("/") if fn else ""


def get_json(url: str, timeout: int = 20):
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_json(url: str, payload: dict, timeout: int = 25):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw.strip() else {}


def linked_users(controller, dose_preview: bool = False, resolve_code: bool = True):
    code = admin_access_code_resolved(controller) if resolve_code else admin_access_code(controller)
    base = api_base(controller)
    if not code or not base:
        return None, "Link from the admin app first (lock screen → Link with code)."
    enc = urllib.parse.quote(code, safe="")
    suffix = "&dose_preview=1" if dose_preview else ""
    url = f"{base}/admin/linked-users?access_code={enc}{suffix}"
    try:
        data = get_json(url)
        users = data.get("users") if isinstance(data, dict) else data
        if not isinstance(users, list):
            users = []
        return users, None
    except Exception as e:
        return None, str(e)


def pending_link_requests(controller, resolve_code: bool = True):
    code = admin_access_code_resolved(controller) if resolve_code else admin_access_code(controller)
    base = api_base(controller)
    if not code or not base:
        return None, "Link from the admin app first."
    enc = urllib.parse.quote(code, safe="")
    url = f"{base}/admin/pending-user-link-requests?access_code={enc}"
    try:
        data = get_json(url)
        reqs = data.get("requests") if isinstance(data, dict) else data
        if not isinstance(reqs, list):
            reqs = []
        return reqs, None
    except Exception as e:
        return None, str(e)
