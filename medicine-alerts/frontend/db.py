"""
Desktop storage: single JSON file (no SQLite, no separate DB folder).
All data for this desktop lives in one file, e.g. curax_desktop.json.
"""
import os
import json
import hashlib
import threading


def _default_file_path():
    """Single file in current working directory; no separate folder."""
    return os.path.join(os.getcwd(), "curax_desktop.json")


class AlertDB:
    """
    File-based store: one JSON file holds settings, admin_credentials, approval_logs.
    Same interface as before so controller and UI need no changes.
    """

    def __init__(self, db_path=None):
        """db_path = path to curax_desktop.json (or None for default). No .db; no migration."""
        if db_path and isinstance(db_path, str) and db_path.strip():
            self.file_path = os.path.abspath(db_path.strip())
        else:
            self.file_path = os.path.abspath(_default_file_path())
        self._lock = threading.Lock()
        self._ensure_file()
        print("Desktop data file:", self.file_path)

    def _ensure_file(self):
        """Create empty JSON file if missing. No migration from SQLite; storage is JSON only."""
        if not os.path.exists(self.file_path):
            self._write_data({"settings": {}, "admin_credentials": None, "approval_logs": []})

    def _read_data(self):
        with self._lock:
            try:
                with open(self.file_path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                return {"settings": {}, "admin_credentials": None, "approval_logs": []}

    def _write_data(self, data):
        with self._lock:
            dir_path = os.path.dirname(self.file_path)
            if dir_path:
                os.makedirs(dir_path, exist_ok=True)
            # Atomic write: write to a temp file then replace, so concurrent saves
            # never leave a half-written JSON on disk.
            tmp_path = self.file_path + ".tmp"
            with open(tmp_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, self.file_path)

    @property
    def db_path(self):
        return self.file_path

    def get(self, key):
        data = self._read_data()
        return data.get("settings", {}).get(key)

    def set(self, key, value):
        data = self._read_data()
        if "settings" not in data:
            data["settings"] = {}
        data["settings"][key] = value
        self._write_data(data)

    def delete(self, key):
        data = self._read_data()
        if "settings" in data and key in data["settings"]:
            del data["settings"][key]
            self._write_data(data)

    def close(self):
        pass

    def _hash_password(self, password):
        return hashlib.sha256(password.encode()).hexdigest()

    _KEEP_HASH = object()  # sentinel: keep existing hash if password is empty

    def set_admin_credentials(self, name, admin_id, email, phone, password, preserve_password_hash=_KEEP_HASH):
        try:
            data = self._read_data()
            existing = data.get("admin_credentials") or {}
            existing_hash = (existing.get("password_hash") or "").strip()
            if preserve_password_hash is None:
                # Caller explicitly passed None → clear hash (admin must set new password after recovery)
                password_hash = self._hash_password("")
            elif preserve_password_hash is not AlertDB._KEEP_HASH and preserve_password_hash and str(preserve_password_hash).strip():
                # Caller passed an explicit hash string → use it directly
                password_hash = str(preserve_password_hash).strip()
            elif (password is None or (isinstance(password, str) and not password.strip())) and existing_hash:
                # No password given and hash already exists → keep it
                password_hash = existing_hash
            else:
                password_hash = self._hash_password(password or "")
            data["admin_credentials"] = {
                "name": name or "",
                "admin_id": admin_id or "",
                "email": email or "",
                "phone": phone or "",
                "password_hash": password_hash,
            }
            self._write_data(data)
            return True
        except Exception as e:
            print(f"Error setting admin credentials: {e}")
            return False

    def verify_admin_password(self, password):
        try:
            password_hash = self._hash_password(password)
            data = self._read_data()
            ac = data.get("admin_credentials")
            return ac is not None and ac.get("password_hash") == password_hash
        except Exception as e:
            print(f"Error verifying admin password: {e}")
            return False

    def verify_admin_credentials(self, email, password):
        try:
            password_hash = self._hash_password(password)
            data = self._read_data()
            ac = data.get("admin_credentials")
            if not ac:
                return False
            return (ac.get("email") == email and ac.get("password_hash") == password_hash)
        except Exception as e:
            print(f"Error verifying admin credentials: {e}")
            return False

    def get_admin_info(self):
        try:
            data = self._read_data()
            ac = data.get("admin_credentials")
            if not ac:
                return None
            return {
                "name": ac.get("name", ""),
                "admin_id": ac.get("admin_id", ""),
                "email": ac.get("email", ""),
                "phone": ac.get("phone", ""),
            }
        except Exception as e:
            print(f"Error getting admin info: {e}")
            return None

    def update_admin_password(self, new_password):
        try:
            data = self._read_data()
            ac = data.get("admin_credentials")
            if not ac:
                return False
            ac["password_hash"] = self._hash_password(new_password)
            self._write_data(data)
            return True
        except Exception as e:
            print(f"Error updating admin password: {e}")
            return False

    def get_admin_email(self):
        try:
            info = self.get_admin_info()
            return (info or {}).get("email")
        except Exception as e:
            print(f"Error getting admin email: {e}")
            return None

    def has_admin_credentials(self):
        try:
            data = self._read_data()
            return data.get("admin_credentials") is not None
        except Exception:
            return False

    def set_user_device_password(self, password):
        """Set the device password for this desktop when used as a user (for unlock without ESP32)."""
        try:
            if not password or not str(password).strip():
                return False
            data = self._read_data()
            if "settings" not in data:
                data["settings"] = {}
            data["settings"]["user_device_password_hash"] = self._hash_password(str(password).strip())
            self._write_data(data)
            return True
        except Exception as e:
            print(f"Error setting user device password: {e}")
            return False

    def verify_user_device_password(self, password):
        try:
            data = self._read_data()
            stored = (data.get("settings") or {}).get("user_device_password_hash")
            if not stored:
                return False
            return self._hash_password(password) == stored
        except Exception:
            return False

    def has_user_device_password(self):
        try:
            data = self._read_data()
            return bool((data.get("settings") or {}).get("user_device_password_hash"))
        except Exception:
            return False

    def get_setup_complete(self):
        """True only after User Setup or Admin Setup is fully done and app reached Main Panel."""
        try:
            data = self._read_data()
            return bool((data.get("settings") or {}).get("setup_complete"))
        except Exception:
            return False

    def set_setup_complete(self, value=True):
        """Mark setup as complete (call only when user has reached Main Panel after full setup)."""
        try:
            self.set("setup_complete", value)
            return True
        except Exception:
            return False

    def clear_partial_setup(self):
        """Clear half-done setup data so next launch starts fresh. Call when setup_complete is False at startup."""
        try:
            for key in (
                "user_device_password_hash",
                "linked_user_id", "linked_user_name", "linked_user_bot_id", "linked_user_api_key",
                "desktop_linked_admin_id", "desktop_linked_admin_name",
            ):
                self.delete(key)
            return True
        except Exception:
            return False

    def get_linked_user(self):
        """Return dict with linked_user_id, linked_user_name and optionally linked_user_bot_id, linked_user_api_key if this desktop is linked to an app user; else None."""
        try:
            uid = self.get("linked_user_id")
            name = self.get("linked_user_name")
            if uid and str(uid).strip():
                out = {"linked_user_id": str(uid).strip(), "linked_user_name": (name or "").strip() or "Linked user"}
                bid = self.get("linked_user_bot_id")
                akey = self.get("linked_user_api_key")
                if bid and akey:
                    out["linked_user_bot_id"] = str(bid).strip()
                    out["linked_user_api_key"] = str(akey).strip()
                return out
            return None
        except Exception:
            return None

    def set_linked_user(self, user_id, user_name="", bot_id=None, api_key=None):
        """Mark this desktop as linked to the given app user (after one-time code). Optionally store bot_id/api_key for sending events to admin."""
        try:
            self.set("linked_user_id", user_id or "")
            self.set("linked_user_name", user_name or "")
            self.set("linked_user_bot_id", (bot_id or "").strip() or "")
            self.set("linked_user_api_key", (api_key or "").strip() or "")
            return True
        except Exception as e:
            print(f"Error setting linked user: {e}")
            return False

    def clear_linked_user(self):
        """Remove linked user so this desktop is no longer bound to that user."""
        try:
            self.delete("linked_user_id")
            self.delete("linked_user_name")
            self.delete("linked_user_bot_id")
            self.delete("linked_user_api_key")
            return True
        except Exception as e:
            print(f"Error clearing linked user: {e}")
            return False

    def get_desktop_linked_admin(self):
        """Return dict with desktop_linked_admin_id, desktop_linked_admin_name if desktop linked via 'Use existing admin' code; else None."""
        try:
            aid = self.get("desktop_linked_admin_id")
            name = self.get("desktop_linked_admin_name")
            if aid and str(aid).strip():
                return {"desktop_linked_admin_id": str(aid).strip(), "desktop_linked_admin_name": (name or "").strip() or "Admin"}
            return None
        except Exception:
            return None

    def set_desktop_linked_admin(self, admin_id, admin_name=""):
        """Set this desktop as linked to an admin (user view only) after 'Use existing admin' code."""
        try:
            self.set("desktop_linked_admin_id", admin_id or "")
            self.set("desktop_linked_admin_name", admin_name or "")
            return True
        except Exception as e:
            print(f"Error setting desktop linked admin: {e}")
            return False

    def clear_desktop_linked_admin(self):
        """Remove desktop linked admin (unlink from user view)."""
        try:
            self.delete("desktop_linked_admin_id")
            self.delete("desktop_linked_admin_name")
            return True
        except Exception as e:
            print(f"Error clearing desktop linked admin: {e}")
            return False

    def delete_admin_account(self):
        """Clear admin from this desktop's JSON file: admin_credentials set to None.
        Caller should also delete admin_access_code and admin_connection_code from settings.
        After this, the JSON file has no admin account data (other settings may remain)."""
        try:
            data = self._read_data()
            data["admin_credentials"] = None
            self._write_data(data)
            print("Admin account deleted successfully")
            return True
        except Exception as e:
            print(f"Error deleting admin account: {e}")
            return False

    def log_approval(self, action, admin_name, status, details=""):
        try:
            data = self._read_data()
            logs = data.get("approval_logs") or []
            next_id = max([x.get("id", 0) for x in logs], default=0) + 1
            from datetime import datetime
            logs.insert(0, {
                "id": next_id,
                "action": action,
                "admin_name": admin_name,
                "status": status,
                "details": details,
                "timestamp": datetime.now().isoformat(),
            })
            data["approval_logs"] = logs[:50]
            self._write_data(data)
            return True
        except Exception as e:
            print(f"Error logging approval: {e}")
            return False

    def get_approval_logs(self, limit=100):
        try:
            data = self._read_data()
            logs = data.get("approval_logs") or []
            out = []
            for row in logs[:limit]:
                out.append({
                    "id": row.get("id", 0),
                    "action": row.get("action", ""),
                    "admin_name": row.get("admin_name"),
                    "status": row.get("status"),
                    "details": row.get("details", ""),
                    "timestamp": row.get("timestamp", ""),
                })
            return out
        except Exception as e:
            print(f"Error getting approval logs: {e}")
            return []
