"""Shared HTTP helpers for admin desktop tabs (same APIs as Android admin app)."""
import json
import urllib.parse
import urllib.error
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
            return code.upper()
    return ""


def admin_access_code_resolved(controller) -> str:
    """Same as Android Prefs.adminAccessCode — recover via bot if needed."""
    if hasattr(controller, "ensure_admin_access_code"):
        try:
            return (controller.ensure_admin_access_code() or "").strip()
        except Exception:
            pass
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


def normalize_linked_user_row(u):
    """Match Android AdminUsersFragment field names."""
    if not isinstance(u, dict):
        return None
    uid = (u.get("id") or u.get("user_id") or "").strip()
    name_raw = (u.get("name") or u.get("full_name") or "").strip()
    email = (u.get("email") or "").strip()
    if not name_raw or name_raw.lower() == "null":
        if "@" in email:
            name_raw = email.split("@")[0].strip()
        else:
            name_raw = "User"
    raw_dm = (u.get("user_display_mode") or u.get("app_mode") or u.get("mode") or "").strip().lower()
    display_mode = raw_dm if raw_dm in ("standalone", "default") else "default"
    return {
        "user_id": uid,
        "name": name_raw,
        "email": email,
        "display_mode": display_mode,
        "desktop_linked": bool((u.get("bot_id") or "").strip()),
    }


def linked_users(controller, dose_preview: bool = False, resolve_code: bool = True):
    code = admin_access_code_resolved(controller) if resolve_code else admin_access_code(controller)
    base = api_base(controller)
    if not base:
        return None, "Backend URL not configured (check backend_url.txt)."
    if not code:
        return None, "No admin access code. In the admin app: Link this PC (one-time code), or Settings → save admin."
    enc = urllib.parse.quote(code, safe="")
    suffix = "&dose_preview=1" if dose_preview else ""
    url = f"{base}/admin/linked-users?access_code={enc}{suffix}"
    try:
        data = get_json(url)
        users = data.get("users") if isinstance(data, dict) else data
        if not isinstance(users, list):
            users = []
        return users, None
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", errors="replace")
            data = json.loads(body) if body.strip() else {}
            msg = (data.get("message") or body or str(e)).strip()
        except Exception:
            msg = str(e)
        return None, f"Server {e.code}: {msg}"
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
