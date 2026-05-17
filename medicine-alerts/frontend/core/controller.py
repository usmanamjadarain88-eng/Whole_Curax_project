import os
import sys
import json
import queue
import time
import threading
import datetime
import shutil
import smtplib
import urllib.parse
import urllib.error
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from db import AlertDB

try:
    from backend.central_db import CentralDB
except ImportError:
    CentralDB = None

try:
    from PyQt6.QtCore import QObject, pyqtSignal, QTimer
except ImportError:
    from PyQt5.QtCore import QObject, pyqtSignal, QTimer

import serial


def _emit_safe(signal, *args):
    """Emit from a background thread; no-op if the QObject has been deleted (e.g. app closed)."""
    try:
        signal.emit(*args)
    except RuntimeError:
        pass  # wrapped C/C++ object has been deleted (app closed during fetch)


class AppController(QObject):

    status_message = pyqtSignal(str)
    connected_changed = pyqtSignal(bool)
    authenticated_changed = pyqtSignal(bool)
    medicine_updated = pyqtSignal()
    temperature_update = pyqtSignal(str)
    admin_status_changed = pyqtSignal()
    care_mode_changed = pyqtSignal()  # admin entered/exited Care mode (act-as linked user)
    linked_user_changed = pyqtSignal()  # emitted when desktop is linked/unlinked to an app user
    linked_user_deleted_by_admin = pyqtSignal(str)  # message when server returns 410 (user removed by admin)
    central_fetch_done = pyqtSignal(object)  # payload dict from GET /admin/data, or None on failure
    save_done = pyqtSignal(bool)  # True = save to server succeeded, False = failed
    test_alert_done = pyqtSignal(bool, str)  # (success, error_message) after send_admin_alert_test

    def __init__(self, db_path=None):
        super().__init__()
        self._db = AlertDB(db_path)
        # If setup was never completed (user closed before reaching Main Panel), clear partial data so next launch starts fresh
        if getattr(self._db, "get_setup_complete", lambda: False)() is False:
            getattr(self._db, "clear_partial_setup", lambda: None)()
        self.connected = False
        self.authenticated = False
        self.ser = None
        self.running = True
        self.serial_thread = None
        self.serial_pause = False
        self.active_led_box = None
        self.last_bt_port = None
        self.wrong_count = 0
        self.admin_logged_in = False
        self.logged_in_admin_name = None
        self.admin_alerts = []
        self.act_as_user_id = ""
        self.act_as_user_name = ""
        self.act_as_user_display_mode = ""
        self._hub_snapshot_before_care = None

        self.temp_settings = {
            "peltier1": {"min": 15, "max": 20, "current": 18},
            "peltier2": {"min": 5, "max": 8, "current": 6},
        }
        self.medicine_boxes = {f"B{i}": None for i in range(1, 7)}
        self.dose_log = []

        self.alert_settings = {
            "email_alerts": {"enabled": True, "email": "", "send_copy_to": "", "alert_tone": "default", "volume": 80},
            "medicine_alerts": {"30_min_before": True, "15_min_before": True, "exact_time": True, "snooze_duration": 5},
            "stock_alerts": {"enabled": True, "low_stock_threshold": 5, "empty_alert": True, "critical_alert": True},
            "expiry_alerts": {"30_days_before": True, "15_days_before": True, "7_days_before": True, "1_day_before": True},
            "reminders": {"doctor_appointments": [], "prescription_renewals": [], "lab_tests": [], "custom_reminders": []},
            "do_not_disturb": {"enabled": False, "start_time": "22:00", "end_time": "07:00", "emergency_override": True},
            "missed_dose_escalation": {"5_min_reminder": True, "15_min_urgent": True, "30_min_family": True, "1_hour_log": True, "family_email": ""},
            "temperature_settings": {
                "peltier1": {"enabled": True, "min_temp": 15, "max_temp": 20, "current_temp": 18},
                "peltier2": {"enabled": True, "min_temp": 5, "max_temp": 8, "current_temp": 6},
            },
        }
        self.sms_config = {"enabled": False, "provider": "", "phone_number": "", "api_key": ""}
        self.mobile_bot = {"enabled": False, "bot_id": "", "api_key": "", "server_url": "https://curax-relay.onrender.com"}
        self.gmail_config = {
            "sender_email": "",
            "sender_password": "",
            "smtp_server": "smtp.gmail.com",
            "smtp_port": 465,
            "recipients": "",
        }
        self.medical_reminders = {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []}
        self.appearance_theme = "light"

        self._central_db = None
        # When device has admin, treat as permanently logged in (no login/logout)
        if getattr(self._db, "has_admin_credentials", None) and self._db.has_admin_credentials():
            self.admin_logged_in = True
            try:
                info = self._db.get_admin_info() if hasattr(self._db, "get_admin_info") else None
                self.logged_in_admin_name = (info.get("name") or "Admin").strip() if info else "Admin"
            except Exception:
                self.logged_in_admin_name = "Admin"

        self.load_alert_settings()
        # Mobile bot config removed - users sign up with connection code on app
        self.load_appearance_theme()

        self.central_fetch_done.connect(self._on_central_fetch_done)

        self._databus_queue = queue.Queue()
        self._databus_stop = threading.Event()
        self._databus_thread = None
        self._databus_timer = None

        self._central_refresh_timer = QTimer(self)
        self._central_refresh_timer.timeout.connect(self.fetch_from_central_and_apply)

        from core.alert_scheduler import AlertScheduler
        self.alert_scheduler = AlertScheduler(self)

        def _deferred_startup():
            self._init_central_db()
            QTimer.singleShot(0, self._finish_startup)

        threading.Thread(target=_deferred_startup, daemon=True).start()

    def _finish_startup(self):
        """Runs on main thread after background DB init. Starts databus, fetches data, schedules alerts."""
        self.load_data()
        self._start_databus_client()
        self._central_refresh_timer.start(30 * 1000)
        try:
            try:
                import schedule as _schedule
                _schedule.clear()
            except Exception:
                pass
            alert_count = 0
            boxes = getattr(self, "medicine_boxes", {}) or {}
            for box_id, medicine in boxes.items():
                if not medicine:
                    continue
                try:
                    self.alert_scheduler.schedule_medicine_alert(medicine, box_id)
                    alert_count += 1
                except Exception:
                    continue
            self.alert_scheduler.start()
            self._log(f"[AlertScheduler] Background scheduler started. {alert_count} medicine(s) scheduled.")
        except Exception as e:
            self._log(f"[AlertScheduler] Failed to start: {e}")

    def _log(self, message: str):
        try:
            print(message)
            sys.stdout.flush()
        except Exception:
            pass

    def get_db(self):
        return self._db

    def is_user_view(self):
        """Desktop is admin-only; end-user flows run on the mobile app (default mode)."""
        return False

    def _init_central_db(self):
        """Central DB: one URL for all installations. Set via DATABASE_URL (or CENTRAL_DB_URL) env or backend/database_url.txt."""
        url = (os.environ.get("DATABASE_URL") or os.environ.get("CENTRAL_DB_URL") or "").strip()
        if not url:
            # Load from file (same as api_server) so desktop works without setting env
            for _path in [
                os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "backend", "database_url.txt"),
                os.path.join(os.getcwd(), "backend", "database_url.txt"),
            ]:
                if os.path.isfile(_path):
                    try:
                        with open(_path, "r", encoding="utf-8") as _f:
                            for _line in _f:
                                _line = _line.strip()
                                if _line and not _line.startswith("#"):
                                    url = _line
                                    os.environ["DATABASE_URL"] = url
                                    break
                        if url:
                            break
                    except Exception:
                        pass
        self._central_db = None
        if url and CentralDB is not None:
            try:
                if "connect_timeout" not in url:
                    sep = "&" if "?" in url else "?"
                    url = url + sep + "connect_timeout=5"
                c = CentralDB(connection_string=url)
                if c.is_available():
                    self._central_db = c
                else:
                    c.close()
            except Exception:
                pass

    def get_central_db(self):
        """Return CentralDB client if configured and available. None otherwise."""
        if self._central_db is not None and hasattr(self._central_db, "is_available") and self._central_db.is_available():
            return self._central_db
        return None

    _DEFAULT_BACKEND_URL = "https://whole-curax-project.vercel.app"

    @classmethod
    def _sanitize_backend_url(cls, url: str) -> str:
        """Same host as the admin Android app (Vercel). Ignore dead Railway/Render URLs in old config files."""
        u = (url or "").strip().rstrip("/")
        if not u:
            return cls._DEFAULT_BACKEND_URL
        low = u.lower()
        if "railway.app" in low or "render.com" in low:
            return cls._DEFAULT_BACKEND_URL
        if not (low.startswith("https://") or low.startswith("http://")):
            return cls._DEFAULT_BACKEND_URL
        return u

    def get_backend_url(self):
        """Central API base URL — must match admin app (Prefs.centralApiUrl / Vercel)."""
        env_url = (os.environ.get("BACKEND_URL") or os.environ.get("CENTRAL_API_URL") or "").strip()
        if env_url:
            return self._sanitize_backend_url(env_url)
        for _path in [
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "backend", "backend_url.txt"),
            os.path.join(os.getcwd(), "backend", "backend_url.txt"),
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "backend", "api_base_url.txt"),
            os.path.join(os.getcwd(), "backend", "api_base_url.txt"),
        ]:
            if os.path.isfile(_path):
                try:
                    with open(_path, "r", encoding="utf-8") as _f:
                        for _line in _f:
                            _line = _line.strip()
                            if _line and not _line.startswith("#"):
                                return self._sanitize_backend_url(_line)
                except Exception:
                    pass
        return self._DEFAULT_BACKEND_URL

    def get_central_api_base_url(self):
        """Same as get_backend_url(): backend serves API (data) and we derive WebSocket from it."""
        return self.get_backend_url()

    def _recover_admin_access_code(self):
        """
        Recover admin_access_code from backend using saved admin bot credentials.
        Only runs when we already have an access_code to send (to refresh/link that admin).
        Does NOT call the backend when we have no access_code - that would create a new admin.
        Returns access code string on success, else None.
        """
        base = self.get_central_api_base_url()
        if not base:
            return None
        current_access_code = (self._db.get("admin_access_code") or "").strip() if getattr(self, "_db", None) else ""
        if not current_access_code:
            return None  # Do not call save-credentials without access_code: backend would create a new admin
        admin_bot = getattr(self, "admin_bot", {}) or {}
        bot_id = (admin_bot.get("bot_id") or "").strip()
        api_key = (admin_bot.get("api_key") or "").strip()
        if not bot_id or not api_key:
            return None
        admin_info = self._db.get_admin_info() if hasattr(self, "_db") and hasattr(self._db, "get_admin_info") else None
        name = ((admin_info or {}).get("name") or "").strip()
        email = ((admin_info or {}).get("email") or "").strip()
        payload = {
            "bot_id": bot_id,
            "api_key": api_key,
            "role": "admin",
            "name": name,
            "email": email,
            "access_code": current_access_code,
        }
        try:
            import urllib.request
            req = urllib.request.Request(
                base.rstrip("/") + "/save-credentials",
                data=json.dumps(payload).encode("utf-8"),
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                if not (200 <= getattr(resp, "status", 0) < 300):
                    return None
                body = resp.read().decode("utf-8", errors="replace")
                data = json.loads(body) if body.strip() else {}
                access_code = (data.get("admin_access_code") or "").strip()
                if not access_code:
                    return None
                if getattr(self, "_db", None) and hasattr(self._db, "set"):
                    self._db.set("admin_access_code", access_code)
                return access_code
        except Exception:
            return None

    def _get_access_code(self):
        """
        Return current admin access code for this desktop session.
        If local cached value is missing/stale, recover from backend using admin bot credentials.
        """
        # If there is no local admin configured at all, treat as "no access code".
        # This avoids hitting the server with a stale code and showing 404 when the admin was deleted/reset.
        if getattr(self, "_db", None) and hasattr(self._db, "has_admin_credentials"):
            try:
                if not self._db.has_admin_credentials():
                    return ""
            except Exception:
                pass
        code = (self._db.get("admin_access_code") or "").strip() if getattr(self, "_db", None) else ""
        if code:
            return code
        return (self._recover_admin_access_code() or "").strip()

    def get_admin_codes_from_backend(self):
        """Fetch admin_access_code and connection_code from GET /admin/codes for current admin. Returns (access_code, connection_code) or (None, None)."""
        base = self.get_central_api_base_url()
        if not base:
            return None, None
        code = (self._get_access_code() or "").strip()
        if not code:
            return None, None
        try:
            import urllib.request
            import urllib.error
            import json as _json
            url = f"{base}/admin/codes?access_code={urllib.parse.quote(code, safe='')}"
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status != 200:
                    return None, None
                body = resp.read().decode("utf-8", errors="replace")
                data = _json.loads(body) if body.strip() else {}
                ac = (data.get("admin_access_code") or "").strip() or None
                cc = (data.get("connection_code") or "").strip() or None
                return ac, cc
        except Exception:
            return None, None

    def recover_admin_by_access_code(self, access_code: str):
        """
        Recover full admin on this desktop: enter access code → fetch all data from backend,
        save admin credentials locally (so Features Locked goes away), apply medicines, settings,
        alerts, Gmail, reminders, and mark admin as logged in. Dashboard and everything recover for this admin.
        """
        code = (access_code or "").strip().upper()
        if not code:
            return False, "Access code is required."
        base = self.get_central_api_base_url()
        if not base:
            return False, "No backend URL configured."
        base = base.rstrip("/")
        try:
            import urllib.request
            import urllib.parse
            import json as _json

            # 1) Fetch full admin data (medicines, settings, alerts, reminders, gmail, dose_logs)
            url = f"{base}/admin/data?access_code={urllib.parse.quote(code, safe='')}"
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=15) as resp:
                if resp.status != 200:
                    return False, f"Server responded with {resp.status}."
                body = resp.read().decode("utf-8", errors="replace")
                payload = _json.loads(body) if body.strip() else {}
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", errors="replace")
                data = _json.loads(body) if body.strip() else {}
                msg = (data.get("message") or "").strip() or e.reason or "Request failed"
            except Exception:
                msg = e.reason or "Request failed"
            return False, f"Server {e.code}: {msg}"
        except Exception as e:
            return False, str(e)

        # 2) Get admin name and connection_code from get-role so we can save credentials locally
        admin_name = "Admin"
        admin_id_str = ""
        connection_code = ""
        try:
            role_url = f"{base}/get-role?access_code={urllib.parse.quote(code, safe='')}"
            role_req = urllib.request.Request(role_url, method="GET")
            with urllib.request.urlopen(role_req, timeout=10) as role_resp:
                if role_resp.status == 200:
                    role_body = role_resp.read().decode("utf-8", errors="replace")
                    role_data = _json.loads(role_body) if role_body.strip() else {}
                    if role_data.get("role") == "admin":
                        admin_name = (role_data.get("name") or "Admin").strip() or "Admin"
                        admin_id_str = str(role_data.get("admin_id") or "").strip()
                        connection_code = (role_data.get("connection_code") or "").strip() or ""
        except Exception:
            pass

        # 3) Save locally: access_code, connection_code, and admin_credentials so this device "has admin"
        db = getattr(self, "_db", None)
        if db and hasattr(db, "set"):
            db.set("admin_access_code", code)
            if connection_code:
                db.set("admin_connection_code", connection_code)
        if db and hasattr(db, "set_admin_credentials"):
            db.set_admin_credentials(admin_name, admin_id_str, "", "", "")

        # 4) Apply payload: medicines, alert_settings, gmail_config, medical_reminders, dose_logs, alerts
        self.apply_data_sync_from_central(payload)

        # 5) Mark admin as logged in so sidebar and dashboard show full access
        self.admin_logged_in = True
        self.logged_in_admin_name = admin_name

        try:
            self.reschedule_all_medicine_alerts()
        except Exception:
            pass
        if hasattr(self, "restart_databus"):
            try:
                self.restart_databus()
            except Exception:
                pass
        if hasattr(self, "admin_status_changed"):
            self.admin_status_changed.emit()
        return True, ""

    def _http_to_ws_url(self, url: str) -> str:
        u = (url or "").strip().rstrip("/")
        if "railway.app" in u.lower() or "render.com" in u.lower():
            return "wss://databus.vercel.app"
        if u.startswith("https://"):
            return u.replace("https://", "wss://", 1)
        if u.startswith("http://"):
            return u.replace("http://", "ws://", 1)
        return "wss://" + u if u else "wss://databus.vercel.app"

    def get_data_bus_url(self):
        """WebSocket for live updates — Vercel databus (same as admin app)."""
        url = (os.environ.get("DATA_BUS_URL") or "").strip().rstrip("/")
        if url:
            return self._http_to_ws_url(url)
        for _path in [
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "backend", "data_bus_url.txt"),
            os.path.join(os.getcwd(), "backend", "data_bus_url.txt"),
        ]:
            if os.path.isfile(_path):
                try:
                    with open(_path, "r", encoding="utf-8") as _f:
                        for _line in _f:
                            _line = _line.strip()
                            if _line and not _line.startswith("#"):
                                return self._http_to_ws_url(_line)
                except Exception:
                    pass
        return "wss://databus.vercel.app"

    def get_relay_alert_url(self):
        """URL for forwarding status alerts to relay (FCM). None = do not forward (avoids localhost:5000 errors when relay not running)."""
        url = (os.environ.get("RELAY_ALERT_URL") or "").strip().rstrip("/")
        if url:
            return url + "/relay/alert" if "/relay" not in url else url
        for _path in [
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "backend", "relay_url.txt"),
            os.path.join(os.getcwd(), "backend", "relay_url.txt"),
        ]:
            if os.path.isfile(_path):
                try:
                    with open(_path, "r", encoding="utf-8") as _f:
                        for _line in _f:
                            _line = _line.strip()
                            if _line and not _line.startswith("#"):
                                url = _line.rstrip("/")
                                return url + "/relay/alert" if "/relay" not in url else url
                except Exception:
                    pass
        return None

    def delete_admin_from_backend(self, access_code):
        """Call backend DELETE /admin. Returns 'deleted' (200), 'not_found' (404 = already deleted on server), or False (error)."""
        base = self.get_central_api_base_url()
        if not base or not (access_code or "").strip():
            return False
        try:
            import urllib.request
            import urllib.error
            import json as _json
            req = urllib.request.Request(
                f"{base}/admin",
                data=_json.dumps({"access_code": (access_code or "").strip()}).encode("utf-8"),
                method="DELETE",
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                status = getattr(resp, "status", 0)
                if 200 <= status < 300:
                    return "deleted"
                return False
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return "not_found"  # admin already deleted on server
            return False
        except Exception:
            return False

    def _start_databus_client(self):
        """Connect to data bus WebSocket so changes from app (or Central API) are pushed to desktop in real time."""
        try:
            from databus.databus_client import run_databus_client
        except Exception:
            return

        def _resolve_and_connect():
            access_code = self._get_access_code()
            ws_url = self.get_data_bus_url()
            if not access_code or not ws_url:
                return
            self._databus_stop.clear()
            run_databus_client(ws_url, access_code, self._databus_queue, self._databus_stop)

        self._databus_thread = threading.Thread(target=_resolve_and_connect, daemon=True)
        self._databus_thread.start()
        self._databus_timer = QTimer(self)
        self._databus_timer.timeout.connect(self._drain_databus_queue)
        self._databus_timer.start(500)

    def restart_databus(self):
        """Stop and restart the data bus client (e.g. after admin creation so new access_code is used)."""
        self._databus_stop.set()
        if self._databus_thread and self._databus_thread.is_alive():
            self._databus_thread.join(timeout=3)
        self._databus_thread = None
        self._databus_stop = threading.Event()
        self._start_databus_client()

    def _drain_databus_queue(self):
        """Apply data_sync payloads from data bus (backend pushes after any write to that admin)."""
        try:
            while True:
                payload = self._databus_queue.get_nowait()
                self.apply_data_sync_from_central(payload)
        except queue.Empty:
            pass

    def _on_central_fetch_done(self, payload):
        """Called on main thread when background fetch completes. Apply payload to state and refresh UI."""
        if payload and isinstance(payload, dict):
            self.apply_data_sync_from_central(payload)
            try:
                self.reschedule_all_medicine_alerts()
                boxes = getattr(self, "medicine_boxes", {}) or {}
                n = sum(1 for m in boxes.values() if m)
                last = getattr(self, "_last_logged_medicine_count", None)
                if last != n:
                    self._last_logged_medicine_count = n
                    self._log(f"[AlertScheduler] {n} medicine(s) now scheduled (after load).")
            except Exception:
                pass

    def apply_data_sync_from_central(self, payload):
        """Apply admin data payload (from data bus or GET /admin/data). All data lives in Central; no local DB for this."""
        if not payload or not isinstance(payload, dict):
            return
        medicines = payload.get("medicines") or []
        boxes = {f"B{i}": None for i in range(1, 7)}
        for m in medicines:
            box_id = (m.get("box_id") or "B1").strip().upper()
            if box_id not in boxes:
                continue
            times = m.get("times")
            if not isinstance(times, list):
                times = []
            exact = (times[0] if times else "08:00") or "08:00"
            qty = 0
            try:
                qty = int(m.get("quantity", 0))
            except (TypeError, ValueError):
                pass
            dose_per_day = 0
            try:
                dose_per_day = int(m.get("dose_per_day", 0) or 0)
            except (TypeError, ValueError):
                dose_per_day = 0
            if dose_per_day <= 0:
                dose_per_day = len(times) or 1
            boxes[box_id] = {
                "name": (m.get("name") or "").strip() or "Medicine",
                "quantity": qty,
                "stock": qty,
                "dose_per_day": dose_per_day,
                "exact_time": (m.get("exact_time") or exact),
                "times": times,
                "instructions": (m.get("instructions") or m.get("dosage") or "").strip(),
                "expiry": (m.get("expiry") or "").strip(),
            }
        self.medicine_boxes = boxes
        dose_logs = payload.get("dose_logs") or []
        _dose_log_entries = []
        for e in dose_logs:
            if not isinstance(e, dict) or not e.get("taken_at"):
                continue
            box = (e.get("box_id") or "").strip().upper()
            # Try to get medicine name: from log entry itself, then from boxes
            med_name = (
                e.get("medicine_name") or
                e.get("medicine") or
                e.get("name") or
                ""
            ).strip()
            if not med_name and box in boxes and boxes[box]:
                med_name = boxes[box].get("name", "")
            # Try to get dose_taken and remaining from log entry
            try:
                dose_taken = int(e.get("dose_taken") or e.get("quantity_taken") or 1)
            except (TypeError, ValueError):
                dose_taken = 1
            remaining = e.get("remaining") or e.get("quantity_remaining")
            if remaining is None and box in boxes and boxes[box]:
                remaining = boxes[box].get("quantity", "")
            try:
                remaining = int(remaining) if remaining is not None and remaining != "" else ""
            except (TypeError, ValueError):
                remaining = ""
            _dose_log_entries.append({
                "timestamp": e.get("taken_at"),
                "box": box,
                "medicine": med_name,
                "dose_taken": dose_taken,
                "remaining": remaining,
            })
        self.dose_log = _dose_log_entries
        def _normalize_reminders(rem_obj):
            if not isinstance(rem_obj, dict):
                return {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []}
            out = {}
            for k in ("appointments", "prescriptions", "lab_tests", "custom"):
                v = rem_obj.get(k)
                out[k] = v if isinstance(v, list) else []
            return out

        reminders = payload.get("medical_reminders")

        alert_top = payload.get("alert_settings")
        nested_alert = None
        nested_gmail = None
        nested_reminders = None

        if isinstance(alert_top, dict):
            if isinstance(alert_top.get("alert_settings"), dict):
                nested_alert = alert_top.get("alert_settings")
            else:
                direct_keys = {"medicine_alerts", "missed_dose_escalation", "stock_alerts", "expiry_alerts", "do_not_disturb", "email_alerts", "temperature_settings"}
                direct = {k: v for k, v in alert_top.items() if k in direct_keys and isinstance(v, dict)}
                if direct:
                    nested_alert = direct

            if isinstance(alert_top.get("gmail_config"), dict):
                nested_gmail = alert_top.get("gmail_config")

            if isinstance(alert_top.get("medical_reminders"), dict):
                nested_reminders = alert_top.get("medical_reminders")

        if isinstance(payload.get("gmail_config"), dict):
            nested_gmail = payload.get("gmail_config")

        if isinstance(reminders, dict):
            self.medical_reminders = _normalize_reminders(reminders)
        elif isinstance(nested_reminders, dict):
            self.medical_reminders = _normalize_reminders(nested_reminders)

        if isinstance(nested_alert, dict):
            for k, v in nested_alert.items():
                if k in self.alert_settings and isinstance(self.alert_settings[k], dict) and isinstance(v, dict):
                    self.alert_settings[k].update(v)
                else:
                    self.alert_settings[k] = v

        if isinstance(nested_gmail, dict):
            self.gmail_config.update(nested_gmail)

        self.admin_alerts = payload.get("alerts") if isinstance(payload.get("alerts"), list) else []
        self.medicine_updated.emit()

    def fetch_from_central_and_apply(self):
        """Load data from Central API: GET /admin/data when admin, or GET /user/data when linked as user."""
        base = self.get_central_api_base_url()
        if not base:
            return
        base = base.rstrip("/")
        access_code = self._get_access_code()
        linked = self._db.get_linked_user() if hasattr(self._db, "get_linked_user") else None
        bot_id = (linked or {}).get("linked_user_bot_id") if linked else None
        api_key = (linked or {}).get("linked_user_api_key") if linked else None
        if access_code:
            self._fetch_admin_data(base, access_code)
            return
        if bot_id and api_key:
            self._fetch_user_data(base, bot_id, api_key)
            return

    def is_care_mode(self) -> bool:
        return bool((self.act_as_user_id or "").strip())

    def enter_care_mode(self, user_id: str, user_name: str = "", display_mode: str = "default"):
        """Load linked user's hub data (same as admin app Care mode)."""
        user_id = (user_id or "").strip()
        if not user_id:
            return False, "Invalid user."
        if not self._hub_snapshot_before_care:
            self._hub_snapshot_before_care = {
                "medicine_boxes": dict(self.medicine_boxes),
                "dose_log": list(self.dose_log),
                "medical_reminders": dict(self.medical_reminders) if isinstance(self.medical_reminders, dict) else {},
                "alert_settings": json.loads(json.dumps(self.alert_settings)),
                "admin_alerts": list(self.admin_alerts),
            }
        self.act_as_user_id = user_id
        self.act_as_user_name = (user_name or "User").strip() or "User"
        self.act_as_user_display_mode = (display_mode or "default").strip().lower()
        _emit_safe(self.care_mode_changed)
        self.fetch_from_central_and_apply()
        return True, ""

    def exit_care_mode(self):
        """Return to admin hub view."""
        self.act_as_user_id = ""
        self.act_as_user_name = ""
        self.act_as_user_display_mode = ""
        snap = self._hub_snapshot_before_care
        self._hub_snapshot_before_care = None
        if snap:
            self.medicine_boxes = snap.get("medicine_boxes") or {f"B{i}": None for i in range(1, 7)}
            self.dose_log = snap.get("dose_log") or []
            self.medical_reminders = snap.get("medical_reminders") or {
                "appointments": [], "prescriptions": [], "lab_tests": [], "custom": []
            }
            self.alert_settings = snap.get("alert_settings") or self.alert_settings
            self.admin_alerts = snap.get("admin_alerts") or []
            _emit_safe(self.medicine_updated)
        _emit_safe(self.care_mode_changed)
        self.fetch_from_central_and_apply()

    def delete_linked_user(self, user_id: str):
        """DELETE /admin/users/<id> — remove user from hub (admin app parity)."""
        user_id = (user_id or "").strip()
        code = self._get_access_code()
        base = self.get_central_api_base_url()
        if not code or not base or not user_id:
            return False, "Not linked or missing user id."
        import urllib.request
        import urllib.error
        try:
            req = urllib.request.Request(
                f"{base.rstrip('/')}/admin/users/{urllib.parse.quote(user_id, safe='')}",
                data=json.dumps({"access_code": code}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="DELETE",
            )
            with urllib.request.urlopen(req, timeout=25) as resp:
                json.loads(resp.read().decode("utf-8") or "{}")
            if self.act_as_user_id == user_id:
                self.exit_care_mode()
            self.fetch_from_central_and_apply()
            return True, "User removed."
        except urllib.error.HTTPError as e:
            try:
                body = json.loads(e.read().decode("utf-8") or "{}")
                msg = (body.get("message") or "").strip()
            except Exception:
                msg = ""
            return False, msg or f"Remove failed ({e.code})."
        except Exception as e:
            return False, str(e) or "Remove failed."

    def _fetch_admin_data(self, base, access_code):
        """Fetch GET /admin/data and apply (existing behaviour)."""
        import urllib.parse
        act_as = (self.act_as_user_id or "").strip()
        def do_fetch():
            payload = None
            try:
                import urllib.request
                q = f"access_code={urllib.parse.quote(access_code, safe='')}"
                if act_as:
                    q += f"&act_as_user_id={urllib.parse.quote(act_as, safe='')}"
                req = urllib.request.Request(
                    f"{base}/admin/data?{q}",
                    method="GET",
                )
                resp = urllib.request.urlopen(req, timeout=15)
                if resp.status == 200:
                    payload = json.loads(resp.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                msg = f"Server returned {e.code}: "
                try:
                    body = e.read().decode("utf-8", errors="replace")
                    data = json.loads(body) if body.strip() else {}
                    msg += (data.get("message") or "").strip() or e.reason or "Request failed"
                except Exception:
                    msg += e.reason or "Request failed"
                if e.code == 404:
                    recovered = self._recover_admin_access_code()
                    if recovered:
                        try:
                            q2 = f"access_code={urllib.parse.quote(recovered, safe='')}"
                            if act_as:
                                q2 += f"&act_as_user_id={urllib.parse.quote(act_as, safe='')}"
                            req2 = urllib.request.Request(
                                f"{base}/admin/data?{q2}",
                                method="GET",
                            )
                            resp2 = urllib.request.urlopen(req2, timeout=15)
                            if resp2.status == 200:
                                payload = json.loads(resp2.read().decode("utf-8"))
                                _emit_safe(self.status_message, "Recovered access code and reloaded data from server.")
                                _emit_safe(self.central_fetch_done, payload)
                                return
                        except Exception:
                            pass
                    msg += ". Go to Settings -> Admin Panel and Save Admin Credentials again to get a new access code."
                _emit_safe(self.status_message, msg)
            except Exception as e:
                _emit_safe(self.status_message, f"Failed to load data: {e}")
            _emit_safe(self.central_fetch_done, payload)
        t = threading.Thread(target=do_fetch, daemon=True)
        t.start()

    def _fetch_user_data(self, base, bot_id, api_key):
        """Fetch GET /user/data for linked user so desktop shows same data as admin dashboard."""
        import urllib.parse
        def do_fetch():
            payload = None
            try:
                import urllib.request
                params = urllib.parse.urlencode({"bot_id": bot_id, "api_key": api_key})
                req = urllib.request.Request(f"{base}/user/data?{params}", method="GET")
                resp = urllib.request.urlopen(req, timeout=15)
                if resp.status == 200:
                    payload = json.loads(resp.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                if e.code == 410:
                    try:
                        body = e.read().decode("utf-8", errors="replace")
                        data = json.loads(body) if body.strip() else {}
                        msg = (data.get("message") or "").strip() or "The admin has removed you from their account."
                    except Exception:
                        msg = "The admin has removed you from their account."
                    if getattr(self._db, "clear_linked_user", None):
                        self._db.clear_linked_user()
                    _emit_safe(self.linked_user_changed)
                    _emit_safe(self.linked_user_deleted_by_admin, msg)
                else:
                    _emit_safe(self.status_message, f"Failed to load user data: {e.code}")
            except Exception as e:
                _emit_safe(self.status_message, f"Failed to load user data: {e}")
            _emit_safe(self.central_fetch_done, payload)
        t = threading.Thread(target=do_fetch, daemon=True)
        t.start()

    def set_central_db_url(self, url):
        """No-op: central DB URL is set once via DATABASE_URL env for all installations, not per-app."""
        pass

    def _save_all_to_central_api(self):
        """POST /admin/sync to write current state to Central DB. Backend then notifies data bus so other clients get the update."""
        base = self.get_central_api_base_url()
        access_code = self._get_access_code()
        if not base or not access_code:
            return False
        try:
            payload = {
                "access_code": access_code,
                "medicine_boxes": getattr(self, "medicine_boxes", {}) or {},
                "dose_log": getattr(self, "dose_log", []) or [],
                "alert_settings": getattr(self, "alert_settings", {}),
                "gmail_config": getattr(self, "gmail_config", {}),
                "medical_reminders": getattr(self, "medical_reminders", {}),
                "mobile_bot_config": getattr(self, "mobile_bot", {}),
            }
            import urllib.request
            req = urllib.request.Request(
                base + "/admin/sync",
                data=json.dumps(payload).encode("utf-8"),
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                return 200 <= getattr(resp, "status", 0) < 300
        except urllib.error.HTTPError as e:
            if e.code == 404:
                recovered = self._recover_admin_access_code()
                if recovered:
                    try:
                        payload["access_code"] = recovered
                        import urllib.request
                        req = urllib.request.Request(
                            base + "/admin/sync",
                            data=json.dumps(payload).encode("utf-8"),
                            method="POST",
                            headers={"Content-Type": "application/json"},
                        )
                        with urllib.request.urlopen(req, timeout=15) as resp:
                            if 200 <= getattr(resp, "status", 0) < 300:
                                _emit_safe(self.status_message, "Recovered access code and saved to server.")
                                return True
                    except Exception:
                        pass
            return False
        except Exception:
            return False

    def load_data(self):
        """Load medicines, dose_log, alert_settings, gmail_config, medical_reminders from Central API only. No local DB for this data."""
        try:
            self.fetch_from_central_and_apply()
        except Exception as e:
            self.status_message.emit(f"Failed to load data: {e}")

    def _save_in_progress(self):
        """True if a save_data() request is still in progress (so we can wait on close)."""
        return getattr(self, "_save_in_progress_flag", False)

    def wait_for_pending_save(self, timeout_seconds=8):
        """Block until any in-progress save completes or timeout. Call on app close so data is not lost."""
        if not self._save_in_progress():
            return
        ev = getattr(self, "_save_done_event", None)
        if ev:
            ev.wait(timeout=timeout_seconds)

    def save_data(self):
        """Save medicines and dose_log to Central API in a background thread; emit save_done(success) when finished so UI does not freeze."""
        base = self.get_central_api_base_url()
        access_code = self._get_access_code()
        if not base or not access_code:
            self.status_message.emit("Cannot save: no server or access code.")
            self.save_done.emit(False)
            return
        payload = {
            "access_code": access_code,
            "medicine_boxes": getattr(self, "medicine_boxes", {}) or {},
            "dose_log": getattr(self, "dose_log", []) or [],
            "alert_settings": getattr(self, "alert_settings", {}),
            "gmail_config": getattr(self, "gmail_config", {}),
            "medical_reminders": getattr(self, "medical_reminders", {}),
        }
        if not hasattr(self, "_save_done_event"):
            self._save_done_event = threading.Event()
        self._save_done_event.clear()
        self._save_in_progress_flag = True

        def do_save():
            ok = False
            err_msg = None
            try:
                import urllib.request
                req = urllib.request.Request(
                    base + "/admin/sync",
                    data=json.dumps(payload).encode("utf-8"),
                    method="POST",
                    headers={"Content-Type": "application/json"},
                )
                resp = urllib.request.urlopen(req, timeout=15)
                ok = 200 <= getattr(resp, "status", 0) < 300
            except urllib.error.HTTPError as e:
                err_msg = None
                try:
                    body = e.read().decode("utf-8", errors="replace")
                    data = json.loads(body) if body.strip() else {}
                    err_msg = (data.get("message") or "").strip() or f"Server {e.code}: {e.reason}"
                except Exception:
                    err_msg = f"Server {e.code}: {e.reason}"
                if e.code == 404:
                    recovered = self._recover_admin_access_code()
                    if recovered:
                        try:
                            payload["access_code"] = recovered
                            req2 = urllib.request.Request(
                                base + "/admin/sync",
                                data=json.dumps(payload).encode("utf-8"),
                                method="POST",
                                headers={"Content-Type": "application/json"},
                            )
                            resp2 = urllib.request.urlopen(req2, timeout=15)
                            ok = 200 <= getattr(resp2, "status", 0) < 300
                            if ok:
                                err_msg = None
                                _emit_safe(self.status_message, "Recovered access code and saved to server.")
                        except Exception:
                            pass
                    if not ok:
                        err_msg = (err_msg or "Invalid access code") + " - Go to Settings -> Admin Panel and Save Admin Credentials again."
                setattr(self, "_last_save_error", err_msg)
                _emit_safe(self.status_message, err_msg)
            except Exception as e:
                err_msg = str(e)
                setattr(self, "_last_save_error", err_msg)
                _emit_safe(self.status_message, err_msg)
            finally:
                self._save_in_progress_flag = False
                self._save_done_event.set()
            if not ok and not err_msg:
                fallback = "Failed to save to server (check connection and access code)."
                _emit_safe(self.status_message, fallback)
                setattr(self, "_last_save_error", fallback)
            _emit_safe(self.save_done, ok)
            if ok:
                setattr(self, "_last_save_error", "")
                _emit_safe(self.medicine_updated)

        threading.Thread(target=do_save, daemon=True).start()

    def load_alert_settings(self):
        """Load only device-local settings from local DB (e.g. sms_config). Alert settings and Gmail come from Central in load_data()."""
        try:
            sc = self._db.get("sms_config")
            if sc and isinstance(sc, dict):
                for key, value in sc.items():
                    if key in self.sms_config:
                        self.sms_config[key] = value
            # Clean up legacy defaults so the user doesn't see fake providers/keys.
            if (self.sms_config.get("provider") or "").lower() == "callmebot" and not self.sms_config.get("enabled", False):
                if not (self.sms_config.get("phone_number") or "").strip() and (self.sms_config.get("api_key") in ("0", "", None)):
                    self.sms_config["provider"] = ""
                    self.sms_config["api_key"] = ""
                    try:
                        self._db.set("sms_config", self.sms_config)
                    except Exception:
                        pass
        except Exception:
            pass

    def load_appearance_theme(self):
        try:
            t = self._db.get("appearance_theme")
            if t is None:
                self.appearance_theme = "light"
                return
            if isinstance(t, str):
                name = t.strip().lower()
                if name in ("light", "dark"):
                    self.appearance_theme = name
                    return
                if name == "default":
                    self.appearance_theme = "light"
                    return
        except Exception:
            pass
        self.appearance_theme = "light"

    def save_appearance_theme(self, theme_name: str):
        try:
            name = (theme_name or "").strip().lower()
            if name not in ("dark", "light"):
                return
            self._db.set("appearance_theme", name)
            self.appearance_theme = name
        except Exception:
            pass

    def save_alert_settings(self):
        """Save alert settings and Gmail to Central API. Persist only device-local sms_config to local DB."""
        try:
            if getattr(self, "_db", None) and hasattr(self._db, "set"):
                self._db.set("sms_config", getattr(self, "sms_config", {}))
            if not self._save_all_to_central_api():
                self.status_message.emit("Failed to save alert settings to server.")
        except Exception as e:
            self.status_message.emit(str(e))

    def reschedule_all_medicine_alerts(self):
        try:
            for box_id in list(getattr(self, "medicine_boxes", {}).keys()):
                self.alert_scheduler.cancel_medicine_alerts_for_box(box_id)
            boxes = getattr(self, "medicine_boxes", {}) or {}
            for box_id, medicine in boxes.items():
                if not medicine:
                    continue
                try:
                    self.alert_scheduler.schedule_medicine_alert(medicine, box_id)
                except Exception:
                    continue
        except Exception:
            pass

    def send_gmail_alert(self, subject, body):
        """Send email via Gmail using gmail_config (recipients, sender_email, sender_password). Used by Alerts/Settings test email and family escalation."""
        try:
            gmail = getattr(self, "gmail_config", {}) or {}
            sender = (gmail.get("sender_email") or "").strip()
            pwd = (gmail.get("sender_password") or "").strip()
            if not sender or not pwd:
                raise ValueError("Gmail not configured: set sender email and app password in Settings.")
            recipient_text = (gmail.get("recipients") or "").strip()
            if recipient_text:
                recipient_list = [e.strip() for e in recipient_text.split(",") if e.strip()]
            else:
                recipient_list = [sender]
            msg = MIMEMultipart()
            msg["From"] = sender
            msg["To"] = ", ".join(recipient_list)
            msg["Subject"] = subject
            html = (
                "<html><body style='font-family: Arial, sans-serif; margin: 0; padding: 20px;'>"
                "<div style='background: #4a6fa5; color: white; padding: 20px; border-radius: 10px 10px 0 0;'>"
                "<h2 style='margin: 0;'>CuraX Medicine Alert</h2></div>"
                "<div style='background-color: white; padding: 30px; border-radius: 0 0 10px 10px; border: 1px solid #ddd;'>"
                f"<h3 style='color: #333; margin-top: 0;'>{subject}</h3>"
                f"<p style='color: #666; font-size: 16px; line-height: 1.6;'>{body}</p>"
                f"<p style='color: #999; font-size: 12px;'>Automated alert from CuraX | {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>"
                "</div></body></html>"
            )
            msg.attach(MIMEText(html, "html"))
            smtp_server = gmail.get("smtp_server", "smtp.gmail.com")
            smtp_port = int(gmail.get("smtp_port", 465))
            with smtplib.SMTP_SSL(smtp_server, smtp_port) as server:
                server.login(sender, pwd)
                server.sendmail(sender, recipient_list, msg.as_string())
        except Exception as e:
            self._log(f"[send_gmail_alert] {e}")
            raise

    def load_medical_reminders(self):
        """Medical reminders are loaded from Central API in load_data(); no local DB."""
        pass

    def save_medical_reminders(self):
        """Save medical reminders to Central API on a background thread (avoid UI freeze)."""
        def _do_save():
            ok = False
            try:
                ok = self._save_all_to_central_api()
                if not ok:
                    self.status_message.emit("Failed to save reminders to server.")
            except Exception as e:
                self.status_message.emit(str(e))
            finally:
                try:
                    self.save_done.emit(ok)
                    if ok:
                        self.medicine_updated.emit()
                except Exception:
                    pass

        threading.Thread(target=_do_save, daemon=True).start()

    def load_mobile_bot_config(self):
        try:
            data = self._db.get("mobile_bot_config")
            if data:
                self.mobile_bot.update(data)
            srv = (self.mobile_bot.get("server_url") or "").strip()
            if "curax-alerts.herokuapp.com" in srv:
                self.mobile_bot["server_url"] = "https://curax-relay.onrender.com"
                try:
                    self._db.set("mobile_bot_config", self.mobile_bot)
                except Exception:
                    pass
        except Exception:
            pass

    def load_admin_bot_config(self):
        pass

    def save_admin_bot_config(self):
        pass

    def require_admin(self):
        if not self._db.has_admin_credentials():
            self.status_message.emit("Admin Required: No admin configured. Go to Settings Ã¢â€ â€™ Admin Panel.")
            return False
        return True

    def verify_admin_login(self, password):
        if not self._db.has_admin_credentials():
            return False
        if not self._db.verify_admin_password(password):
            return False
        info = self._db.get_admin_info()
        name = (info or {}).get("name") or "Admin"
        self.admin_logged_in = True
        self.logged_in_admin_name = name
        # Do not log every Admin Login - it runs on every unlock and fills approval_logs
        self.admin_status_changed.emit()
        try:
            self.send_admin_alert("admin_login", f"Admin panel logged in by {name}")
        except Exception:
            pass
        return True

    def verify_admin_password_for_action(self, action_label: str, password: str) -> bool:
        if not self._db.has_admin_credentials():
            return False
        info = self._db.get_admin_info()
        name = (info or {}).get("name") or "Admin"
        if not self._db.verify_admin_password(password):
            try:
                self._db.log_approval(action_label, "Unknown", "Denied")
            except Exception:
                pass
            return False
        try:
            self._db.log_approval(action_label, name, "Approved")
        except Exception:
            pass
        return True

    def admin_logout(self):
        self.admin_logged_in = False
        self.logged_in_admin_name = None
        # Clear in-memory admin data so after delete (or logout) the desktop shows a clean state
        self.medicine_boxes = {f"B{i}": None for i in range(1, 7)}
        self.dose_log = []
        self.medical_reminders = {"appointments": [], "prescriptions": [], "lab_tests": [], "custom": []}
        self.admin_alerts = []
        self.admin_status_changed.emit()

    def apply_admin_hub_link_session(self, data: dict):
        """Apply POST /desktop/link-to-admin response: hub payload + local session (no access-code UI)."""
        if not data or not isinstance(data, dict):
            return False, "Invalid server response."
        hub = data.get("hub")
        if not hub or not isinstance(hub, dict):
            return False, "Could not load admin hub. Create a new code in the app."
        admin_name = (data.get("admin_name") or "Admin").strip() or "Admin"
        admin_id_str = str(data.get("admin_id") or "").strip()
        connection_code = (data.get("connection_code") or "").strip()
        sync_key = (data.get("sync_key") or "").strip().upper()
        db = getattr(self, "_db", None)
        if db and hasattr(db, "set") and sync_key:
            db.set("admin_access_code", sync_key)
            if connection_code:
                db.set("admin_connection_code", connection_code)
        if db and hasattr(db, "clear_legacy_user_desktop_modes"):
            db.clear_legacy_user_desktop_modes()
        if db and hasattr(db, "set_admin_identity_from_link"):
            db.set_admin_identity_from_link(admin_name, admin_id_str)
        elif db and hasattr(db, "set_admin_credentials"):
            db.set_admin_credentials(admin_name, admin_id_str, "", "", "")
        self.apply_data_sync_from_central(hub)
        self.admin_logged_in = True
        self.logged_in_admin_name = admin_name
        try:
            self.reschedule_all_medicine_alerts()
        except Exception:
            pass
        if hasattr(self, "restart_databus"):
            try:
                self.restart_databus()
            except Exception:
                pass
        if hasattr(self, "admin_status_changed"):
            self.admin_status_changed.emit()
        return True, ""

    def _parse_link_error_body(self, body: str, http_code: int):
        """Return (message, try_fallback_path)."""
        text = (body or "").strip()
        try:
            err = json.loads(text) if text else {}
            if isinstance(err, dict):
                msg = (err.get("message") or err.get("error") or "").strip()
                if isinstance(msg, dict):
                    msg = (msg.get("message") or "").strip()
                if msg and "application not found" in msg.lower():
                    return (
                        "Desktop was pointing at an old server. Restart the app — it now uses Vercel "
                        f"({self._DEFAULT_BACKEND_URL}).",
                        False,
                    )
                if err.get("path") and str(err.get("message", "")).lower() == "not found":
                    return "Link API not found on server — redeploy backend.", True
                if msg:
                    return msg, False
        except Exception:
            pass
        if text.lower().startswith("<!"):
            return f"Bad API host. Use {self._DEFAULT_BACKEND_URL}", False
        if http_code == 404:
            return "Invalid or expired code. Create a new code in the app.", False
        return text[:200] if text else f"Link failed ({http_code}).", False

    def link_admin_desktop_by_link_code(self, code: str):
        """Redeem code from admin app → load full hub on this PC (POST /desktop/link-to-admin)."""
        code = (code or "").strip().upper()
        if not code:
            return False, "Enter the code from your phone."
        base = self.get_central_api_base_url()
        if not base:
            return False, "Backend URL not configured."
        import urllib.request
        import urllib.error

        paths = ("/desktop/link-to-admin", "/api/desktop_link_to_admin")
        last_msg = "Could not link desktop."
        for i, path in enumerate(paths):
            try:
                req = urllib.request.Request(
                    base + path,
                    data=json.dumps({"code": code}).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=30) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                return self.apply_admin_hub_link_session(payload)
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8", errors="replace") if e.fp else ""
                msg, try_next = self._parse_link_error_body(body, e.code)
                last_msg = msg
                if try_next and i + 1 < len(paths):
                    continue
                return False, last_msg
            except Exception as e:
                last_msg = str(e) or last_msg
                if i + 1 < len(paths):
                    continue
                return False, last_msg
        return False, last_msg

    def connect_to_port(self, port_name):
        from connection import serial_connection
        self._connect_port_name = port_name
        return serial_connection.connect_to_port(self)

    def disconnect_esp32(self):
        from connection import serial_connection
        serial_connection.disconnect_esp32(self)

    def start_serial_thread(self):
        from connection import serial_connection
        serial_connection.start_serial_thread(self)

    def get_available_ports(self):
        from connection import serial_connection
        return serial_connection.get_available_ports()

    def get_connected_port(self):
        from connection import serial_connection
        return serial_connection.get_connected_port(self)

    def quick_test_port(self, port_name):
        from connection import serial_connection
        return serial_connection.quick_test_port(port_name)

    def get_bluetooth_ports(self):
        from connection import serial_connection
        return serial_connection.get_bluetooth_ports()

    def connect_bluetooth(self):
        from connection import serial_connection
        return serial_connection.connect_bluetooth(self)

    def verify_pin_esp32(self, pin):
        from auth.verify_esp32 import verify_pin_esp32
        return verify_pin_esp32(self, pin)

    def send_led_on(self, box_id):
        from connection import serial_connection
        return serial_connection.send_led_on(self, box_id)

    def send_led_off(self, box_id):
        from connection import serial_connection
        return serial_connection.send_led_off(self, box_id)

    def send_led_all_off(self):
        from connection import serial_connection
        return serial_connection.send_led_all_off(self)

    def apply_peltier_settings(self, peltier_id: str, enabled: bool, min_temp: float, max_temp: float):
        if not self.authenticated or not self.ser or not self.ser.is_open:
            return False, "Please connect and authenticate ESP32 first."
        try:
            min_t = float(min_temp)
            max_t = float(max_temp)
        except (TypeError, ValueError):
            return False, "Invalid temperature values."
        if min_t >= max_t:
            return False, "Minimum temperature must be less than maximum."

        try:
            self.serial_pause = True
            try:
                self.ser.reset_input_buffer()
            except Exception:
                pass
            cmd = f"TEMP_SET:{peltier_id},{enabled},{min_t},{max_t}\n"
            self._log(f"[TEMP] Sending: {cmd.strip()}")
            self.ser.write(cmd.encode("utf-8"))
            self.ser.flush()
            zone = self.temp_settings.setdefault(peltier_id, {})
            zone["min"] = min_t
            zone["max"] = max_t
            zone["enabled"] = bool(enabled)
            return True, f"{peltier_id.upper()} applied: {min_t}Ã‚Â°C Ã¢â€ â€™ {max_t}Ã‚Â°C"
        except Exception as e:
            return False, f"Failed to apply settings: {str(e)[:80]}"
        finally:
            self.serial_pause = False

    def query_temperatures(self):
        if not self.authenticated or not self.ser or not self.ser.is_open:
            return False, "Please connect and authenticate ESP32 first."
        try:
            self.serial_pause = True
            try:
                self.ser.reset_input_buffer()
            except Exception:
                pass
            self._log("[TEMP] Sending TEMP_QUERY")
            self.ser.write(b"TEMP_QUERY\n")
            self.ser.flush()
            return True, "Temperature query sent."
        except Exception as e:
            return False, f"Failed to query temperature: {str(e)[:80]}"
        finally:
            self.serial_pause = False

    def _send_bot_alert(self, bot_config, alert_type, message):
        """Send alert to a bot via relay WebSocket. Returns True on success."""
        bot_id = (bot_config.get("bot_id") or "").strip()
        api_key = (bot_config.get("api_key") or "").strip()
        if not bot_id or not api_key:
            return False
        server_url = (bot_config.get("server_url") or "localhost:5050").strip()
        if not server_url:
            return False
        try:
            if server_url.startswith("https://") or server_url.startswith("http://"):
                wss = server_url.replace("https://", "wss://", 1).replace("http://", "ws://", 1).rstrip("/")
                try:
                    import asyncio
                    import websockets
                except ImportError:
                    return False

                async def _send_via_ws():
                    async with websockets.connect(wss, close_timeout=2, open_timeout=15) as ws:
                        await ws.send(json.dumps({
                            "action": "alert",
                            "bot_id": bot_id,
                            "api_key": api_key,
                            "type": alert_type,
                            "message": message,
                        }))

                asyncio.run(_send_via_ws())
                return True
            import socket
            srv = server_url.split("://")[-1] if "://" in server_url else server_url
            host, port = (srv.rsplit(":", 1) + ["5050"])[:2]
            port = int(port)
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            client.settimeout(3)
            client.connect((host, port))
            client.sendall(json.dumps({"type": alert_type, "message": message}).encode("utf-8"))
            client.close()
            return True
        except Exception:
            return False

    def send_mobile_alert(self, alert_type, message, priority="NORMAL"):
        return self._send_bot_alert(self.mobile_bot, alert_type, message)

    def send_admin_alert(self, alert_type, message, priority="NORMAL"):
        """Tell backend of admin-only events (system_started, system_unlocked, admin_login, dose_taken).
        Uses access_code if this desktop has admin credentials; else uses linked user's bot_id/api_key (notify-event-by-user) so admin receives alerts from each user's desktop."""
        base = self.get_central_api_base_url()
        if not base:
            return False
        base = base.rstrip("/")
        try:
            import urllib.request
            import json as _json
            payload = {"event_type": str(alert_type), "message": str(message or "")}
            access_code = self._get_access_code()
            if access_code:
                payload["access_code"] = access_code
                url = base + "/notify-event"
            else:
                linked = self._db.get_linked_user() if hasattr(self._db, "get_linked_user") else None
                bot_id = (linked or {}).get("linked_user_bot_id") if linked else None
                api_key = (linked or {}).get("linked_user_api_key") if linked else None
                if bot_id and api_key:
                    payload["bot_id"] = bot_id
                    payload["api_key"] = api_key
                    url = base + "/notify-event-by-user"
                else:
                    return False
            data = _json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
            def _send():
                try:
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        return resp.status in (200, 201)
                except urllib.error.HTTPError as e:
                    if e.code == 410:
                        try:
                            body = e.read().decode("utf-8") if e.fp else ""
                            data_ = _json.loads(body) if body else {}
                            msg = data_.get("message", "The admin has removed you from their account.")
                        except Exception:
                            msg = "The admin has removed you from their account."
                        if getattr(self._db, "clear_linked_user", None):
                            self._db.clear_linked_user()
                        _emit_safe(self.linked_user_changed)
                        _emit_safe(self.linked_user_deleted_by_admin, msg)
                    elif getattr(self, "_log", None):
                        self._log(f"[AdminAlert] notify failed: {e}")
                    return False
                except Exception as e:
                    if getattr(self, "_log", None):
                        self._log(f"[AdminAlert] notify failed: {e}")
                    return False
            t = threading.Thread(target=_send, daemon=True)
            t.start()
            return True
        except Exception:
            return False

    def send_admin_alert_test(self, alert_type="test_alert", message=None):
        """Send a test alert synchronously so the UI can show success/failure.
        Admin desktop: uses access_code → /notify-event (alert to admin's app).
        User desktop: uses linked user bot_id/api_key → /notify-event-to-user (alert to user's app).
        Returns (True, None) if the alert was sent to the backend and relay accepted it;
        returns (False, error_message) otherwise. Use for the 'Test alert' button."""
        if message is None:
            message = "Desktop test – alert setup is complete. If you see this on your app, alerts are working."
        base = self.get_central_api_base_url()
        if not base:
            return False, "No server URL configured"
        base = base.rstrip("/")
        access_code = self._get_access_code()
        url = None
        payload = {"event_type": str(alert_type), "message": str(message)}
        if access_code:
            payload["access_code"] = access_code
            url = base + "/notify-event"
        else:
            linked = self._db.get_linked_user() if hasattr(self._db, "get_linked_user") else None
            bot_id = (linked or {}).get("linked_user_bot_id") if linked else None
            api_key = (linked or {}).get("linked_user_api_key") if linked else None
            if bot_id and api_key:
                payload["bot_id"] = bot_id
                payload["api_key"] = api_key
                url = base + "/notify-event-to-user"
            else:
                return False, "No admin access code or linked user. Save Admin Credentials and sign in on the app, or link this desktop to a user."
        if not url:
            return False, "No admin access code. Save Admin Credentials first, then sign in on the Android app."
        try:
            import urllib.request
            import urllib.error
            import json as _json
            data = _json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                if resp.status in (200, 201):
                    return True, None
                body = resp.read().decode("utf-8", errors="replace") if resp else ""
                try:
                    j = _json.loads(body) if body else {}
                    return False, (j.get("message") or body or "Unknown error").strip()
                except Exception:
                    return False, body or "Unknown error"
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", errors="replace") if e.fp else ""
                j = _json.loads(body) if body else {}
                # Prefer relay_error so user sees real reason (e.g. "connection timed out", "Connection refused")
                msg = (j.get("relay_error") or j.get("message") or "").strip() or e.reason or f"HTTP {e.code}"
            except Exception:
                msg = e.reason or f"HTTP {e.code}"
            if e.code == 404:
                return False, msg or "Admin not found. Sign in on the Android app with your Access Code first."
            return False, msg
        except Exception as e:
            return False, str(e) if str(e) else "Connection or server error"

    def change_device_password(self, current_password: str, new_password: str):
        if not self.ser or not self.ser.is_open:
            return False, "Not connected to device"
        if not current_password or not new_password:
            return False, "Both current and new passwords are required"
        if len(new_password) < 4 or not new_password.isdigit():
            return False, "New password must be at least 4 digits (numbers only)"
        try:
            self.serial_pause = True
            command = f"SET_PASSWORD:{current_password},{new_password}\n"
            self.ser.reset_input_buffer()
            time.sleep(0.1)
            self.ser.write(command.encode("utf-8"))
            self.ser.flush()

            response = ""
            start_time = time.time()
            timeout = 3.0
            while time.time() - start_time < timeout:
                if self.ser.in_waiting:
                    try:
                        raw = self.ser.readline()
                        response = raw.decode("utf-8", errors="ignore").strip()
                        break
                    except Exception:
                        continue
                time.sleep(0.05)

            up = (response or "").upper()
            if any(tok in up for tok in ["PASSWORD_OK", "PWD_OK", "SUCCESS"]):
                db = self.get_db()
                if getattr(db, "set_user_device_password", None):
                    db.set_user_device_password(new_password)
                return True, "Device password updated. Use the new PIN next time."
            if "PASSWORD_FAIL_OLD" in up:
                return False, "Current password is incorrect"
            if "PASSWORD_FAIL_FORMAT" in up or "FAIL" in up:
                return False, "Invalid password format"
            if not response:
                return False, "No response from device"
            return False, response
        except Exception as e:
            err_msg = str(e)[:80]
            try:
                import serial
                if isinstance(e, (serial.SerialException, serial.SerialTimeoutException)):
                    self.disconnect_esp32()
            except Exception:
                if "timeout" in err_msg.lower() or "write" in err_msg.lower():
                    self.disconnect_esp32()
            return False, err_msg
        finally:
            self.serial_pause = False

    def set_initial_device_pin(self, new_pin: str):
        """Set PIN on device when it has no PIN yet (first-time). Uses default '0000' as current. Returns (True, msg) or (False, error)."""
        return self.change_device_password("0000", new_pin)
