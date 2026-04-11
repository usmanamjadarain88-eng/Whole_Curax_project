import datetime
import schedule
import time
import threading


class AlertScheduler:
    """Desktop-local alert scheduler. Shows status bar notifications only.
    Mobile/admin/email alerts are now handled by the backend scheduler.
    """

    def __init__(self, controller):
        self.app = controller
        self.running = True
        self.scheduled_alerts = {}
        self._sent_reminder_alerts = set()
        self._sent_medicine_alerts = set()
        self._sent_missed_escalation = set()
        self._sent_expiry_alerts = set()
        self._sent_stock_alerts = set()
        self._condition_alert_state = {}

    def cancel_medicine_alerts_for_box(self, box_id):
        today = datetime.datetime.now().strftime("%Y-%m-%d")
        to_discard = [(b, d, t) for (b, d, t) in self._sent_medicine_alerts if b == box_id and d == today]
        for x in to_discard:
            self._sent_medicine_alerts.discard(x)
        to_discard_esc = [(b, d, t) for (b, d, t) in self._sent_missed_escalation if b == box_id and d == today]
        for x in to_discard_esc:
            self._sent_missed_escalation.discard(x)
        for x in list(self._sent_stock_alerts):
            if x[0] == box_id:
                self._sent_stock_alerts.discard(x)
        for x in list(self._sent_expiry_alerts):
            if x[0] == box_id:
                self._sent_expiry_alerts.discard(x)
        for k in list(self._condition_alert_state.keys()):
            if k.startswith(f"stock:{box_id}:") or k.startswith(f"expiry:{box_id}:"):
                self._clear_condition_alert(k)
        prefix = f"{box_id}_"
        for key in list(self.scheduled_alerts.keys()):
            if key.startswith(prefix):
                job = self.scheduled_alerts.pop(key, None)
                if job is not None:
                    try:
                        schedule.cancel_job(job)
                    except Exception:
                        pass

    def schedule_medicine_alert(self, medicine, box_id):
        if not medicine:
            return
        medicine_time = medicine.get("exact_time", medicine.get("time", "08:00"))
        try:
            hour, minute = map(int, str(medicine_time).strip().split(":"))
        except Exception:
            hour, minute = 8, 0
        medicine_alerts = self.app.alert_settings.get("medicine_alerts", {})
        _ = medicine_alerts.get("30_min_before", True)
        _ = medicine_alerts.get("15_min_before", True)
        _ = medicine_alerts.get("exact_time", True)

    def send_alert(self, alert_type, medicine, box_id):
        name = medicine.get("name", "Medicine")
        if alert_type == "pre30":
            body = f"Medicine {name} from box {box_id} is due in 30 minutes."
        elif alert_type == "pre":
            body = f"Medicine {name} from box {box_id} is due in 15 minutes. Time to take soon."
        elif alert_type == "time":
            body = f"Medicine {name} from box {box_id} \u2013 time to take now."
        elif alert_type == "missed":
            body = f"Medicine {name} from box {box_id} may have been missed. Please take if you haven't."
        else:
            body = f"Medicine {name} from box {box_id} \u2013 alert."
        try:
            self.app.status_message.emit(body)
        except Exception:
            pass

    def _check_medicine_alerts(self):
        now = datetime.datetime.now()
        today_str = now.strftime("%Y-%m-%d")
        current_hm = (now.hour, now.minute)
        self._sent_medicine_alerts = {(b, d, t) for (b, d, t) in self._sent_medicine_alerts if d >= today_str}
        self._sent_missed_escalation = {(b, d, t) for (b, d, t) in self._sent_missed_escalation if d >= today_str}
        boxes = getattr(self.app, "medicine_boxes", {}) or {}
        medicine_alerts = self.app.alert_settings.get("medicine_alerts", {})
        esc = self.app.alert_settings.get("missed_dose_escalation", {})

        for box_id, medicine in boxes.items():
            if not medicine:
                continue
            medicine_time = medicine.get("exact_time", medicine.get("time", "08:00"))
            try:
                h, m = map(int, str(medicine_time).strip().split(":"))
            except Exception:
                h, m = 8, 0
            try:
                dose_dt = now.replace(hour=h, minute=m, second=0, microsecond=0)
            except ValueError:
                continue

            # 30 min before
            if medicine_alerts.get("30_min_before", True):
                total_mins = h * 60 + m - 30
                if total_mins < 0:
                    total_mins += 24 * 60
                h_30 = (total_mins // 60) % 24
                m_30 = total_mins % 60
                if (h_30, m_30) == current_hm and (box_id, today_str, "pre30") not in self._sent_medicine_alerts:
                    self._sent_medicine_alerts.add((box_id, today_str, "pre30"))
                    self.send_alert("pre30", medicine, box_id)
            # 15 min before
            if medicine_alerts.get("15_min_before", True):
                if m >= 15:
                    h_before, m_before = h, m - 15
                else:
                    m_before = m + 45
                    h_before = 23 if h == 0 else h - 1
                if (h_before, m_before) == current_hm and (box_id, today_str, "pre") not in self._sent_medicine_alerts:
                    self._sent_medicine_alerts.add((box_id, today_str, "pre"))
                    self.send_alert("pre", medicine, box_id)
            # Exact time
            if medicine_alerts.get("exact_time", True):
                if (h, m) == current_hm and (box_id, today_str, "time") not in self._sent_medicine_alerts:
                    self._sent_medicine_alerts.add((box_id, today_str, "time"))
                    self.send_alert("time", medicine, box_id)

            delta = now - dose_dt
            minutes_late = delta.total_seconds() / 60.0
            if minutes_late < 5 or minutes_late > 180:
                continue

            # 5 min after: missed reminder
            if esc.get("5_min_reminder", True) and (box_id, today_str, "5min") not in self._sent_missed_escalation:
                if 5 <= minutes_late < 15:
                    self._sent_missed_escalation.add((box_id, today_str, "5min"))
                    name = medicine.get("name", "Medicine")
                    body = f"Missed reminder: {name} from box {box_id} is 5 minutes late. Please take now if not taken."
                    try:
                        self.app.status_message.emit(body)
                    except Exception:
                        pass

            # 15 min after: urgent
            if esc.get("15_min_urgent", True) and (box_id, today_str, "15min") not in self._sent_missed_escalation:
                if 15 <= minutes_late < 30:
                    self._sent_missed_escalation.add((box_id, today_str, "15min"))
                    name = medicine.get("name", "Medicine")
                    body = f"URGENT: {name} from box {box_id} is 15 minutes overdue. Immediate action required."
                    try:
                        self.app.status_message.emit(body)
                    except Exception:
                        pass

            # 30 min after: family escalation
            if esc.get("30_min_family", True) and (box_id, today_str, "30min") not in self._sent_missed_escalation:
                if 30 <= minutes_late < 60:
                    self._sent_missed_escalation.add((box_id, today_str, "30min"))
                    name = medicine.get("name", "Medicine")
                    body = f"Family escalation: {name} from box {box_id} is 30 minutes overdue. Please check patient immediately."
                    try:
                        self.app.status_message.emit(body)
                    except Exception:
                        pass

            # 60+ min after: log as missed in local dose_log
            if esc.get("1_hour_log", True) and (box_id, today_str, "1h") not in self._sent_missed_escalation:
                if 60 <= minutes_late <= 180:
                    self._sent_missed_escalation.add((box_id, today_str, "1h"))
                    dose_log = getattr(self.app, "dose_log", [])
                    if not isinstance(dose_log, list):
                        dose_log = []
                    dose_log.append({
                        "date": today_str,
                        "time": f"{h:02d}:{m:02d}",
                        "box_id": box_id,
                        "medicine": medicine.get("name", "Medicine"),
                        "status": "missed",
                    })
                    self.app.dose_log = dose_log
                    try:
                        self.app.save_data()
                    except Exception:
                        pass
                    try:
                        self.app.medicine_updated.emit()
                    except Exception:
                        pass

    def _allow_condition_alert(self, key):
        now_ts = time.time()
        now_date = datetime.datetime.now().strftime("%Y-%m-%d")
        state = self._condition_alert_state.get(key)

        if str(key).startswith("expiry:"):
            if not state:
                self._condition_alert_state[key] = {
                    "count": 1,
                    "next_ts": now_ts + 8 * 3600,
                    "day": now_date,
                }
                return True
            day = state.get("day")
            if day != now_date:
                return False
            if int(state.get("count", 1)) >= 3:
                return False
            if now_ts >= float(state.get("next_ts", 0)):
                count = int(state.get("count", 1)) + 1
                self._condition_alert_state[key] = {
                    "count": count,
                    "next_ts": now_ts + 8 * 3600,
                    "day": now_date,
                }
                return True
            return False

        if not state:
            self._condition_alert_state[key] = {"count": 1, "next_ts": now_ts + 2 * 3600}
            return True
        if now_ts >= float(state.get("next_ts", 0)):
            count = int(state.get("count", 1)) + 1
            gap = 24 * 3600 if count >= 2 else 2 * 3600
            self._condition_alert_state[key] = {"count": count, "next_ts": now_ts + gap}
            return True
        return False

    def _clear_condition_alert(self, key):
        try:
            self._condition_alert_state.pop(key, None)
        except Exception:
            pass

    def _check_medical_reminder_alerts(self):
        now = datetime.datetime.now()
        reminders_data = getattr(self.app, "medical_reminders", {}) or {}
        for key in ["appointments", "prescriptions", "lab_tests", "custom"]:
            lst = reminders_data.get(key, [])
            for idx, r in enumerate(lst):
                date_str = r.get("date", "")
                time_str = r.get("time", "09:00")
                if not date_str:
                    continue
                try:
                    dt = datetime.datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M")
                except Exception:
                    try:
                        dt = datetime.datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M:%S")
                    except Exception:
                        continue
                reminders = r.get("reminders", {})
                title = r.get("title", r.get("doctor", r.get("medicine", r.get("test_name", "Reminder"))))
                if reminders.get("24h"):
                    t24 = dt - datetime.timedelta(hours=24)
                    if abs((t24 - now).total_seconds()) < 90 and (key, idx, "24h") not in self._sent_reminder_alerts:
                        self._sent_reminder_alerts.add((key, idx, "24h"))
                        msg = f"Reminder: {title} in 24 hours (at {dt.strftime('%Y-%m-%d %H:%M')})"
                        try:
                            self.app.status_message.emit(msg)
                        except Exception:
                            pass
                if reminders.get("2h"):
                    t2 = dt - datetime.timedelta(hours=2)
                    if abs((t2 - now).total_seconds()) < 90 and (key, idx, "2h") not in self._sent_reminder_alerts:
                        self._sent_reminder_alerts.add((key, idx, "2h"))
                        msg = f"Reminder: {title} in 2 hours (at {dt.strftime('%Y-%m-%d %H:%M')})"
                        try:
                            self.app.status_message.emit(msg)
                        except Exception:
                            pass
                if now > dt + datetime.timedelta(minutes=5):
                    self._sent_reminder_alerts.discard((key, idx, "24h"))
                    self._sent_reminder_alerts.discard((key, idx, "2h"))

    def _check_expiry_alerts(self):
        now = datetime.datetime.now()
        today = now.date()
        expiry_settings = self.app.alert_settings.get("expiry_alerts", {})
        boxes = getattr(self.app, "medicine_boxes", {}) or {}

        for box_id, medicine in boxes.items():
            if not medicine:
                continue
            expiry_str = medicine.get("expiry", "")
            if not expiry_str:
                continue
            try:
                expiry_date = datetime.datetime.strptime(expiry_str.strip()[:10], "%Y-%m-%d").date()
            except Exception:
                continue

            delta = (expiry_date - today).days
            if delta < 0:
                for k in list(self._condition_alert_state.keys()):
                    if k.startswith(f"expiry:{box_id}:{expiry_str}:"):
                        self._clear_condition_alert(k)
                continue

            name = medicine.get("name", "Medicine")
            matched_any = False
            for key, days in [("30_days_before", 30), ("15_days_before", 15), ("7_days_before", 7), ("1_day_before", 1)]:
                cond_key = f"expiry:{box_id}:{expiry_str}:{days}"
                if not expiry_settings.get(key, True) or delta != days:
                    self._clear_condition_alert(cond_key)
                    continue

                matched_any = True
                if not self._allow_condition_alert(cond_key):
                    continue

                body = f"{name} in Box {box_id} expires on {expiry_str} ({days} day(s) from now)."
                try:
                    self.app._log(f"[AlertScheduler] Expiry: {body}")
                except Exception:
                    pass
                try:
                    self.app.status_message.emit(body)
                except Exception:
                    pass
                try:
                    if hasattr(self.app, "send_admin_alert"):
                        self.app.send_admin_alert("expiry", body)
                except Exception:
                    pass

            if not matched_any:
                for k in list(self._condition_alert_state.keys()):
                    if k.startswith(f"expiry:{box_id}:{expiry_str}:"):
                        self._clear_condition_alert(k)

    def _check_stock_alerts(self):
        stock_settings = self.app.alert_settings.get("stock_alerts", {})
        if not stock_settings.get("enabled", True):
            return

        threshold = int(stock_settings.get("low_stock_threshold", 5))
        empty_alert = stock_settings.get("empty_alert", True)
        critical_alert = stock_settings.get("critical_alert", True)
        boxes = getattr(self.app, "medicine_boxes", {}) or {}

        for box_id, medicine in boxes.items():
            if not medicine:
                continue
            try:
                qty = int(medicine.get("quantity", 0))
            except (TypeError, ValueError):
                qty = 0

            name = medicine.get("name", "Medicine")
            empty_key = f"stock:{box_id}:empty"
            low_key = f"stock:{box_id}:low"

            if qty <= 0 and empty_alert:
                self._clear_condition_alert(low_key)
                if self._allow_condition_alert(empty_key):
                    body = f"{name} in Box {box_id} has no stock. Please refill."
                    try:
                        self.app._log(f"[AlertScheduler] Stock: {body}")
                    except Exception:
                        pass
                    try:
                        self.app.status_message.emit(body)
                    except Exception:
                        pass
                    try:
                        if hasattr(self.app, "send_admin_alert"):
                            self.app.send_admin_alert("stock", body)
                    except Exception:
                        pass
            elif qty <= threshold and critical_alert:
                self._clear_condition_alert(empty_key)
                if self._allow_condition_alert(low_key):
                    body = f"{name} in Box {box_id} has low stock ({qty} left, threshold {threshold})."
                    try:
                        self.app._log(f"[AlertScheduler] Stock: {body}")
                    except Exception:
                        pass
                    try:
                        self.app.status_message.emit(body)
                    except Exception:
                        pass
                    try:
                        if hasattr(self.app, "send_admin_alert"):
                            self.app.send_admin_alert("stock", body)
                    except Exception:
                        pass
            else:
                self._clear_condition_alert(empty_key)
                self._clear_condition_alert(low_key)

    def start(self):
        try:
            schedule.every().minute.do(self._check_medical_reminder_alerts)
            self._check_medical_reminder_alerts()
            schedule.every().minute.do(self._check_medicine_alerts)
            self._check_medicine_alerts()
            schedule.every().minute.do(self._check_expiry_alerts)
            self._check_expiry_alerts()
            schedule.every().minute.do(self._check_stock_alerts)
            self._check_stock_alerts()
        except Exception:
            pass
        def run():
            while self.running:
                try:
                    schedule.run_pending()
                except Exception:
                    pass
                time.sleep(0.2)
        threading.Thread(target=run, daemon=True).start()

    def stop(self):
        self.running = False
        schedule.clear()
