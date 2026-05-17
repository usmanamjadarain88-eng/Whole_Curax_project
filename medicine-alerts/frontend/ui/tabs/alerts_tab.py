import datetime
import math

try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QGroupBox,
        QCheckBox, QPushButton, QSpinBox, QLineEdit, QScrollArea,
        QMessageBox,
    )
    from PyQt6.QtCore import Qt, QTimer, QRectF, QPointF
    from PyQt6.QtGui import QPainter, QPen, QBrush, QColor
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QGroupBox,
        QCheckBox, QPushButton, QSpinBox, QLineEdit, QScrollArea,
        QMessageBox,
    )
    from PyQt5.QtCore import Qt, QTimer, QRectF, QPointF
    from PyQt5.QtGui import QPainter, QPen, QBrush, QColor

from ui.styles import (
    NEON_GREEN, TEXT_SECONDARY, TEXT_SECONDARY_LIGHT, TEXT_PRIMARY_LIGHT,
    ACCENT_LIGHT, SECONDARY_LIGHT_DARK, RADIUS, BORDER_LIGHT, BORDER,
)


class AnalogClockWidget(QWidget):
    """Real-time analog clock with hour/minute/second hands; sits to the right of digital time."""

    def __init__(self, parent=None, size=88):
        super().__init__(parent)
        self._size = size
        self.setFixedSize(size, size)
        self._dark = False
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.update)
        self._timer.start(1000)

    def set_dark(self, dark: bool):
        self._dark = bool(dark)
        self.update()

    def paintEvent(self, event):
        try:
            p = QPainter(self)
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
        except AttributeError:
            p = QPainter(self)
            p.setRenderHint(QPainter.Antialiasing)
            p.setRenderHint(QPainter.SmoothPixmapTransform)
        w, h = self.width(), self.height()
        cx, cy = w / 2.0, h / 2.0
        r = min(w, h) / 2.0 - 4

        now = datetime.datetime.now()
        hour = now.hour % 12 + now.minute / 60.0 + now.second / 3600.0
        minute = now.minute + now.second / 60.0
        second = now.second

        if self._dark:
            face_bg = QColor(14, 28, 46)
            border_c = QColor(45, 212, 191)
            tick_c = QColor(148, 163, 184)
            hour_c = QColor(226, 248, 246)
            min_c = QColor(203, 253, 245)
            sec_c = QColor(45, 212, 191)
        else:
            face_bg = QColor(248, 253, 252)
            border_c = QColor(13, 148, 136)
            tick_c = QColor(100, 116, 139)
            hour_c = QColor(15, 23, 42)
            min_c = QColor(30, 58, 58)
            sec_c = QColor(13, 148, 136)

        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(face_bg)
        p.drawEllipse(QRectF(cx - r, cy - r, 2 * r, 2 * r))

        p.setPen(QPen(border_c, 2.5))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawEllipse(QRectF(cx - r, cy - r, 2 * r, 2 * r))

        for i in range(12):
            angle = math.radians(i * 30 - 90)
            inner_r = r - 6
            outer_r = r
            x1 = cx + math.cos(angle) * inner_r
            y1 = cy + math.sin(angle) * inner_r
            x2 = cx + math.cos(angle) * outer_r
            y2 = cy + math.sin(angle) * outer_r
            p.setPen(QPen(tick_c, 1.8))
            p.drawLine(int(x1), int(y1), int(x2), int(y2))

        p.translate(cx, cy)

        def draw_hand(angle_deg, length_frac, width, color):
            a = math.radians(angle_deg - 90)
            x = math.cos(a) * r * length_frac
            y = math.sin(a) * r * length_frac
            p.setPen(QPen(color, width))
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawLine(0, 0, int(x), int(y))

        hour_angle = hour * 30
        minute_angle = minute * 6
        second_angle = second * 6

        draw_hand(hour_angle, 0.42, 3, hour_c)
        draw_hand(minute_angle, 0.62, 2.2, min_c)
        draw_hand(second_angle, 0.78, 1.2, sec_c)

        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(sec_c)
        p.drawEllipse(QRectF(-4, -4, 8, 8))

        p.end()


class AlertsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None, panel_mode="all"):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._panel_mode = (panel_mode or "all").lower()
        self._build_ui()
        self._apply_panel_mode()
        self._start_clock_timer()
        self._load_from_controller()
        self._refresh_activity_feed()
        try:
            self.controller.medicine_updated.connect(self._on_controller_data_updated)
        except Exception:
            pass
        try:
            self.controller.admin_status_changed.connect(self._refresh_activity_feed)
        except Exception:
            pass
        self.apply_theme(getattr(controller, "appearance_theme", "light"))

    def apply_theme(self, theme_name: str, content_scale: float = None):
        name = (theme_name or "light").lower()
        sc = content_scale if content_scale is not None else (getattr(self.window(), "_content_scale", 1.0) if self.window() else 1.0)
        s = max(0.5, min(1.0, float(sc)))
        title_color = ACCENT_LIGHT if name == "light" else NEON_GREEN
        secondary = SECONDARY_LIGHT_DARK if name == "light" else TEXT_SECONDARY
        t_pt = max(8, int(18 * s))
        try:
            self._title_label.setStyleSheet(
                f"font-size: {t_pt}pt; font-weight: bold; color: {title_color};"
            )
            self._subtitle_label.setStyleSheet(f"color: {secondary}; font-weight: bold; font-size: {max(8, int(10 * s))}pt;")
            self.next_label.setStyleSheet(f"color: {secondary}; font-size: {max(8, int(10 * s))}pt;")
            if name == "light":
                border = "#c8c8c8"
                bg = "#ffffff"
                accent_top = "#0D9488"
                radius = "10px"
            else:
                border = "#2d3748"
                bg = "#0d1117"
                accent_top = "#2DD4BF"
                radius = "10px"
            mt = max(10, int(18 * s))
            pd = max(8, int(24 * s))
            pd2 = max(6, int(14 * s))
            title_pt = max(11, int(17 * s))
            # Designed borders: 2px sides/bottom, accent top bar, rounded corners
            base = (
                f"QGroupBox {{ "
                f"border: 2px solid {border}; "
                f"border-top: 3px solid {accent_top}; "
                f"border-radius: {radius}; "
                f"margin-top: {mt}px; padding: {pd}px {pd2}px {pd2}px {pd2}px; "
                f"background-color: {bg}; "
                f"}} "
                f"QGroupBox::title {{ subcontrol-origin: margin; left: 14px; padding: 0 10px 12px 10px; "
                f"color: {title_color}; font-weight: 900; font-size: {title_pt}pt; }}"
            )
            for g in (self.clock_group, self.next_group, self.g_activity, self.g1, self.g_missed, self.g2, self.g3):
                g.setStyleSheet(base)
        except Exception:
            pass
        if hasattr(self, "_analog_clock"):
            self._analog_clock.set_dark(name == "dark")
            self._analog_clock.setFixedSize(max(44, int(88 * s)), max(44, int(88 * s)))
        if name == "light":
            self.clock_label.setStyleSheet(
                f"font-size: {max(14, int(22 * s))}pt; font-weight: bold; color: #111827;"
            )
            self.date_label.setStyleSheet(
                f"color: {SECONDARY_LIGHT_DARK}; font-size: {max(7, int(9 * s))}pt;"
            )
            try:
                self.refresh_btn.setStyleSheet(
                    "background-color: #16a34a; color: #ffffff; font-weight: bold; padding: 6px 12px; border-radius: 8px;"
                )
                self.refresh_btn.setFixedWidth(max(80, int(100 * s)))
                self.refresh_btn.setMinimumHeight(max(24, int(28 * s)))
            except Exception:
                pass
        else:
            self.clock_label.setStyleSheet(
                f"font-size: {max(14, int(22 * s))}pt; font-weight: bold; color: #e5f9ff;"
            )
            self.date_label.setStyleSheet(
                f"color: {TEXT_SECONDARY}; font-size: {max(7, int(9 * s))}pt;"
            )
            try:
                self.refresh_btn.setStyleSheet(
                    "background-color: #16a34a; color: #ffffff; font-weight: bold; padding: 6px 12px; border-radius: 8px;"
                )
                self.refresh_btn.setFixedWidth(max(80, int(100 * s)))
                self.refresh_btn.setMinimumHeight(max(24, int(28 * s)))
            except Exception:
                pass
        try:
            if hasattr(self, "_activity_placeholder"):
                self._activity_placeholder.setStyleSheet(f"color: {secondary}; font-size: {max(8, int(10 * s))}pt;")
            if hasattr(self, "_activity_list"):
                self._activity_list.setStyleSheet(f"color: {secondary}; font-family: monospace; font-size: {max(7, int(9 * s))}pt;")
            if hasattr(self, "family_test_btn"):
                self.family_test_btn.setMaximumWidth(max(120, int(160 * s)))
            if hasattr(self, "_alert_save_btn"):
                self._alert_save_btn.setStyleSheet(
                    "background-color: #0D9488; color: #ffffff; font-weight: 700; "
                    f"padding: 8px 20px; border-radius: 8px; border: none; font-size: {max(8, int(10 * s))}pt;"
                )
                self._alert_save_btn.setMaximumWidth(max(200, int(280 * s)))
        except Exception:
            pass

    def showEvent(self, event):
        """Re-apply section title styles when tab is shown; in user view disable edits and hide Save."""
        try:
            super().showEvent(event)
            self.apply_theme(getattr(self.controller, "appearance_theme", "light"))
            self._refresh_activity_feed()
            is_user = getattr(self.controller, "is_user_view", lambda: False)()
            if hasattr(self, "_alert_save_btn"):
                self._alert_save_btn.setVisible(not is_user)
            if hasattr(self, "_alert_edit_widgets"):
                for w in self._alert_edit_widgets:
                    try:
                        w.setEnabled(not is_user)
                    except Exception:
                        pass
        except Exception:
            super().showEvent(event)

    def _build_ui(self):
        root = QVBoxLayout(self)

        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        scroll.setStyleSheet("background: transparent;")
        scroll_w = QWidget(scroll)
        scroll_layout = QVBoxLayout(scroll_w)

        self._title_label = QLabel("Alerts & Reminders System")
        self._title_label.setStyleSheet(f"font-size: 18pt; font-weight: bold; color: {NEON_GREEN};")
        scroll_layout.addWidget(self._title_label)
        self._subtitle_label = QLabel("Configure medicine time, stock, and expiry alerts.")
        self._subtitle_label.setStyleSheet(f"color: {TEXT_SECONDARY};")
        scroll_layout.addWidget(self._subtitle_label)

        top_row = QHBoxLayout()

        self.clock_group = QGroupBox("🕒 Current Time")
        self.clock_group.setObjectName("alertsClockGroup")
        clock_layout = QHBoxLayout(self.clock_group)
        clock_left = QVBoxLayout()
        self.clock_label = QLabel("00:00:00")
        self.clock_label.setStyleSheet(
            "font-size: 22pt; font-weight: bold; color: #e5f9ff;"
        )
        clock_left.addWidget(self.clock_label)
        self.date_label = QLabel("")
        self.date_label.setStyleSheet(
            f"color: {TEXT_SECONDARY}; font-size: 9pt;"
        )
        clock_left.addWidget(self.date_label)
        clock_left.addStretch(1)
        clock_layout.addLayout(clock_left, 1)
        self._analog_clock = AnalogClockWidget(self.clock_group, size=88)
        try:
            clock_layout.addWidget(self._analog_clock, 0, Qt.AlignmentFlag.AlignVCenter)
        except AttributeError:
            clock_layout.addWidget(self._analog_clock, 0, Qt.AlignVCenter)
        top_row.addWidget(self.clock_group, 1)

        self.next_group = QGroupBox("🔔 Next Reminder")
        self.next_group.setObjectName("alertsNextGroup")
        next_layout = QVBoxLayout(self.next_group)
        self.next_label = QLabel(
            "No upcoming reminders yet.\n\n"
            "Add medicines or set exact times to see the next dose here."
        )
        self.next_label.setWordWrap(True)
        self.next_label.setStyleSheet(f"color: {TEXT_SECONDARY};")
        next_layout.addWidget(self.next_label)
        self.refresh_btn = QPushButton("🔄 Refresh")
        self.refresh_btn.clicked.connect(self._update_next_reminder)
        self.refresh_btn.setFixedWidth(100)
        self.refresh_btn.setMinimumHeight(28)
        refresh_row = QHBoxLayout()
        refresh_row.addWidget(self.refresh_btn)
        refresh_row.addStretch(1)
        next_layout.addLayout(refresh_row)
        top_row.addWidget(self.next_group, 1)

        scroll_layout.addLayout(top_row)

        self.g_activity = QGroupBox("📋 Recent activity (all users’ alerts)")
        self.g_activity.setObjectName("alertsActivityGroup")
        g_activity_layout = QVBoxLayout(self.g_activity)
        self._activity_placeholder = QLabel(
            "When you are logged in as Admin, events from all linked users appear here:\n"
            "medicine taken, missed dose, system unlocked, medicine alerts, etc."
        )
        self._activity_placeholder.setWordWrap(True)
        self._activity_placeholder.setStyleSheet(f"color: {TEXT_SECONDARY};")
        g_activity_layout.addWidget(self._activity_placeholder)
        self._activity_list = QLabel("")
        self._activity_list.setWordWrap(True)
        self._activity_list.setStyleSheet(f"color: {TEXT_SECONDARY}; font-family: monospace; font-size: 9pt;")
        g_activity_layout.addWidget(self._activity_list)
        scroll_layout.addWidget(self.g_activity)

        # Row 1: Medicine Time | Missed Dose Escalation (side by side)
        row1 = QHBoxLayout()
        self.g1 = QGroupBox("💊 Medicine Time Alerts")
        g1_layout = QVBoxLayout(self.g1)
        self.cb_30_before = QCheckBox("30 min before")
        self.cb_15_before = QCheckBox("15 min before")
        self.cb_exact = QCheckBox("Exact time")
        for cb in (self.cb_30_before, self.cb_15_before, self.cb_exact):
            cb.stateChanged.connect(self._sync_medicine_alerts_to_controller)
        g1_layout.addWidget(self.cb_30_before)
        g1_layout.addWidget(self.cb_15_before)
        g1_layout.addWidget(self.cb_exact)
        g1_layout.addWidget(QLabel("Snooze duration (minutes):"))
        self.spin_snooze = QSpinBox()
        self.spin_snooze.setRange(1, 60)
        self.spin_snooze.setValue(5)
        self.spin_snooze.valueChanged.connect(self._sync_medicine_alerts_to_controller)
        g1_layout.addWidget(self.spin_snooze)
        row1.addWidget(self.g1, 1)

        self.g_missed = QGroupBox("⚠️ Missed Dose Escalation")
        self.g_missed.setObjectName("alertsMissedGroup")
        g_missed_layout = QVBoxLayout(self.g_missed)
        self.cb_5_min = QCheckBox("After 5 min: reminder")
        self.cb_15_urgent = QCheckBox("After 15 min: urgent alert")
        self.cb_30_family = QCheckBox("After 30 min: notify family")
        self.cb_1hr_log = QCheckBox("After 1 hour: log as missed")
        g_missed_layout.addWidget(self.cb_5_min)
        g_missed_layout.addWidget(self.cb_15_urgent)
        g_missed_layout.addWidget(self.cb_30_family)
        g_missed_layout.addWidget(self.cb_1hr_log)
        g_missed_layout.addWidget(QLabel("Family / Admin email:"))
        self.family_email = QLineEdit()
        self.family_email.setPlaceholderText("admin@example.com")
        g_missed_layout.addWidget(self.family_email)
        self.family_test_btn = QPushButton("📤 Send Test Email")
        self.family_test_btn.clicked.connect(self._test_family_email)
        self.family_test_btn.setStyleSheet(
            "background-color: #16a34a; color: #ffffff; font-weight: bold; "
            "padding: 6px 12px; border-radius: 8px; border: none; font-size: 9pt;"
        )
        self.family_test_btn.setMaximumWidth(160)
        g_missed_layout.addWidget(self.family_test_btn)
        row1.addWidget(self.g_missed, 1)
        scroll_layout.addLayout(row1)

        # Row 2: Stock Alerts | Expiry Alerts (side by side)
        row2 = QHBoxLayout()
        self.g2 = QGroupBox("📦 Stock Alerts")
        g2_layout = QVBoxLayout(self.g2)
        self.cb_stock_enabled = QCheckBox("Enable stock alerts")
        g2_layout.addWidget(self.cb_stock_enabled)
        g2_layout.addWidget(QLabel("Low stock threshold:"))
        self.spin_threshold = QSpinBox()
        self.spin_threshold.setRange(1, 100)
        self.spin_threshold.setValue(5)
        g2_layout.addWidget(self.spin_threshold)
        self.cb_empty_alert = QCheckBox("Alert when empty")
        self.cb_critical_alert = QCheckBox("Critical when low")
        g2_layout.addWidget(self.cb_empty_alert)
        g2_layout.addWidget(self.cb_critical_alert)
        row2.addWidget(self.g2, 1)

        self.g3 = QGroupBox("📅 Expiry Alerts")
        self.g3.setObjectName("alertsExpiryGroup")
        g3_layout = QVBoxLayout(self.g3)
        self.cb_30_days = QCheckBox("30 days before")
        self.cb_15_days = QCheckBox("15 days before")
        self.cb_7_days = QCheckBox("7 days before")
        self.cb_1_day = QCheckBox("1 day before")
        g3_layout.addWidget(self.cb_30_days)
        g3_layout.addWidget(self.cb_15_days)
        g3_layout.addWidget(self.cb_7_days)
        g3_layout.addWidget(self.cb_1_day)
        row2.addWidget(self.g3, 1)
        scroll_layout.addLayout(row2)

        scroll.setWidget(scroll_w)
        root.addWidget(scroll)

        self._alert_save_btn = QPushButton("🔒 Save Alert Settings")
        self._alert_save_btn.setStyleSheet(
            "background-color: #0D9488; color: #ffffff; font-weight: 700; "
            "padding: 8px 20px; border-radius: 8px; border: none; font-size: 10pt;"
        )
        self._alert_save_btn.setMinimumHeight(36)
        self._alert_save_btn.setMaximumWidth(280)
        self._alert_save_btn.clicked.connect(self._save)
        root.addWidget(self._alert_save_btn)
        # Widgets to disable in user view (read-only)
        self._alert_edit_widgets = [
            self.cb_30_before, self.cb_15_before, self.cb_exact, self.spin_snooze,
            self.cb_5_min, self.cb_15_urgent, self.cb_30_family, self.cb_1hr_log, self.family_email,
            self.family_test_btn,
            self.cb_stock_enabled, self.spin_threshold, self.cb_empty_alert, self.cb_critical_alert,
            self.cb_30_days, self.cb_15_days, self.cb_7_days, self.cb_1_day,
        ]

    def _apply_panel_mode(self):
        """panel_mode: all | settings (admin Settings tab) | inbox (legacy; use AdminAlertsTab)."""
        mode = getattr(self, "_panel_mode", "all")
        if mode == "all":
            return
        hide = []
        if mode == "settings":
            hide = [
                getattr(self, "clock_group", None),
                getattr(self, "next_group", None),
                getattr(self, "g_activity", None),
            ]
            if hasattr(self, "_title_label"):
                self._title_label.setText("Alert settings")
            if hasattr(self, "_subtitle_label"):
                self._subtitle_label.setText(
                    "Medicine time, missed dose, stock, and expiry — same as admin app Settings."
                )
        elif mode == "inbox":
            hide = [
                getattr(self, "g1", None),
                getattr(self, "g_missed", None),
                getattr(self, "g2", None),
                getattr(self, "g3", None),
                getattr(self, "_alert_save_btn", None),
                getattr(self, "clock_group", None),
                getattr(self, "next_group", None),
            ]
        for w in hide:
            if w is not None:
                w.hide()

    def _on_controller_data_updated(self):
        try:
            self._load_from_controller()
            self._update_next_reminder()
            self._refresh_activity_feed()
        except Exception:
            pass

    def _sync_medicine_alerts_to_controller(self):
        """Keep controller.alert_settings in sync with current checkboxes so new medicines follow existing settings without saving."""
        ma = self.controller.alert_settings.setdefault("medicine_alerts", {})
        ma["30_min_before"] = self.cb_30_before.isChecked()
        ma["15_min_before"] = self.cb_15_before.isChecked()
        ma["exact_time"] = self.cb_exact.isChecked()
        ma["snooze_duration"] = self.spin_snooze.value()

    def _refresh_activity_feed(self):
        """Show activity only when admin account exists but admin is NOT currently signed in (remove when admin signed in)."""
        has_admin = getattr(self.controller, "_db", None) and getattr(self.controller._db, "has_admin_credentials", lambda: False)()
        is_user = getattr(self.controller, "is_user_view", lambda: False)()
        admin_signed_in = getattr(self.controller, "admin_logged_in", False)
        show = has_admin and not is_user and admin_signed_in
        if hasattr(self, "g_activity"):
            self.g_activity.setVisible(show)
        if not show or not hasattr(self, "_activity_list"):
            return
        alerts = getattr(self.controller, "admin_alerts", None) or []
        if not alerts:
            self._activity_list.setText("No recent events. Alerts from linked users will appear here.")
            return
        lines = []
        for a in alerts[:50]:
            if not isinstance(a, dict):
                continue
            ts = a.get("created_at") or ""
            if len(ts) > 19:
                ts = ts[:19].replace("T", " ")
            typ = (a.get("type") or "").strip() or "event"
            msg = (a.get("message") or "").strip()
            uid = a.get("user_id") or ""
            if uid:
                uid_short = str(uid).replace("-", "")[:8] if uid else ""
                lines.append(f"[{ts}] {typ} (user {uid_short}) {msg}")
            else:
                lines.append(f"[{ts}] {typ} {msg}")
        self._activity_list.setText("\n".join(lines) if lines else "No recent events.")

    def _load_from_controller(self):
        for cb in (self.cb_30_before, self.cb_15_before, self.cb_exact):
            try:
                cb.blockSignals(True)
            except Exception:
                pass
        try:
            self.spin_snooze.blockSignals(True)
        except Exception:
            pass
        ma = self.controller.alert_settings.get("medicine_alerts", {})
        self.cb_30_before.setChecked(ma.get("30_min_before", True))
        self.cb_15_before.setChecked(ma.get("15_min_before", True))
        self.cb_exact.setChecked(ma.get("exact_time", True))
        self.spin_snooze.setValue(ma.get("snooze_duration", 5))
        esc = self.controller.alert_settings.get("missed_dose_escalation", {})
        self.cb_5_min.setChecked(esc.get("5_min_reminder", True))
        self.cb_15_urgent.setChecked(esc.get("15_min_urgent", True))
        self.cb_30_family.setChecked(esc.get("30_min_family", True))
        self.cb_1hr_log.setChecked(esc.get("1_hour_log", True))
        self.family_email.setText(esc.get("family_email", "") or "")
        sa = self.controller.alert_settings.get("stock_alerts", {})
        self.cb_stock_enabled.setChecked(sa.get("enabled", True))
        self.spin_threshold.setValue(sa.get("low_stock_threshold", 5))
        self.cb_empty_alert.setChecked(sa.get("empty_alert", True))
        self.cb_critical_alert.setChecked(sa.get("critical_alert", True))
        ea = self.controller.alert_settings.get("expiry_alerts", {})
        self.cb_30_days.setChecked(ea.get("30_days_before", True))
        self.cb_15_days.setChecked(ea.get("15_days_before", True))
        self.cb_7_days.setChecked(ea.get("7_days_before", True))
        self.cb_1_day.setChecked(ea.get("1_day_before", True))
        for cb in (self.cb_30_before, self.cb_15_before, self.cb_exact):
            try:
                cb.blockSignals(False)
            except Exception:
                pass
        try:
            self.spin_snooze.blockSignals(False)
        except Exception:
            pass

    def _save(self):
        if self.main_window and not self.main_window.verify_admin_for_action("Save Medicine Alert Settings"):
            return
        self.controller.alert_settings.setdefault("medicine_alerts", {})["30_min_before"] = self.cb_30_before.isChecked()
        self.controller.alert_settings.setdefault("medicine_alerts", {})["15_min_before"] = self.cb_15_before.isChecked()
        self.controller.alert_settings.setdefault("medicine_alerts", {})["exact_time"] = self.cb_exact.isChecked()
        self.controller.alert_settings.setdefault("medicine_alerts", {})["snooze_duration"] = self.spin_snooze.value()
        self.controller.alert_settings.setdefault("missed_dose_escalation", {})["5_min_reminder"] = self.cb_5_min.isChecked()
        self.controller.alert_settings.setdefault("missed_dose_escalation", {})["15_min_urgent"] = self.cb_15_urgent.isChecked()
        self.controller.alert_settings.setdefault("missed_dose_escalation", {})["30_min_family"] = self.cb_30_family.isChecked()
        self.controller.alert_settings.setdefault("missed_dose_escalation", {})["1_hour_log"] = self.cb_1hr_log.isChecked()
        self.controller.alert_settings.setdefault("missed_dose_escalation", {})["family_email"] = self.family_email.text().strip()
        self.controller.alert_settings.setdefault("stock_alerts", {})["enabled"] = self.cb_stock_enabled.isChecked()
        self.controller.alert_settings.setdefault("stock_alerts", {})["low_stock_threshold"] = self.spin_threshold.value()
        self.controller.alert_settings.setdefault("stock_alerts", {})["empty_alert"] = self.cb_empty_alert.isChecked()
        self.controller.alert_settings.setdefault("stock_alerts", {})["critical_alert"] = self.cb_critical_alert.isChecked()
        self.controller.alert_settings.setdefault("expiry_alerts", {})["30_days_before"] = self.cb_30_days.isChecked()
        self.controller.alert_settings.setdefault("expiry_alerts", {})["15_days_before"] = self.cb_15_days.isChecked()
        self.controller.alert_settings.setdefault("expiry_alerts", {})["7_days_before"] = self.cb_7_days.isChecked()
        self.controller.alert_settings.setdefault("expiry_alerts", {})["1_day_before"] = self.cb_1_day.isChecked()
        self.controller.save_alert_settings()
        self.controller.reschedule_all_medicine_alerts()
        self.controller.status_message.emit("Alert settings saved.")
        QMessageBox.information(self, "Saved", "Alert settings saved.")


    def _start_clock_timer(self):
        """Start periodic updates for the clock and upcoming reminder."""
        try:
            from PyQt6.QtCore import QTimer
        except ImportError:
            from PyQt5.QtCore import QTimer
        self._clock_timer = QTimer(self)
        self._clock_timer.timeout.connect(self._update_clock_and_next)
        self._clock_timer.start(1000)  # every second
        self._update_clock_and_next()

    def _update_clock_and_next(self):
        try:
            now = datetime.datetime.now()
            if getattr(self, "clock_label", None) is not None:
                self.clock_label.setText(now.strftime("%I:%M:%S %p"))
            if getattr(self, "date_label", None) is not None:
                self.date_label.setText(now.strftime("%A, %B %d, %Y"))
            if getattr(self, "_last_next_update_min", None) != now.minute:
                self._last_next_update_min = now.minute
                self._update_next_reminder()
        except Exception:
            pass  # avoid timer callback crashing the app

    def _update_next_reminder(self):
        """Compute and display the next upcoming medicine dose in a 24h cycle."""
        now = datetime.datetime.now()
        boxes = getattr(self.controller, "medicine_boxes", {}) or {}
        upcoming = []

        for box_id, med in boxes.items():
            if not med:
                continue
            med_name = med.get("name", "Unknown")
            exact_time_str = med.get("exact_time") or med.get("time") or "00:00"
            try:
                hour, minute = map(int, exact_time_str.split(":"))
                med_time = datetime.time(hour, minute)
                candidate = datetime.datetime.combine(now.date(), med_time)
                if candidate <= now:
                    candidate = candidate + datetime.timedelta(days=1)
                diff_min = (candidate - now).total_seconds() / 60.0
                upcoming.append(
                    {
                        "name": med_name,
                        "box": box_id,
                        "time": exact_time_str,
                        "minutes": diff_min,
                        "is_tomorrow": candidate.date() > now.date(),
                    }
                )
            except Exception:
                continue

        if not upcoming:
            self.next_label.setText(
                "No medicines configured yet.\n\n"
                "Add medicines with exact times to see upcoming reminders here."
            )
            self.next_label.setStyleSheet(f"color: {TEXT_SECONDARY};")
            return

        upcoming.sort(key=lambda x: x["minutes"])
        nxt = upcoming[0]
        minutes = int(nxt["minutes"])
        if minutes < 60:
            time_text = f"{minutes}m"
        else:
            h = minutes // 60
            m = minutes % 60
            time_text = f"{h}h" if m == 0 else f"{h}h {m}m"

        name_short = nxt["name"][:15] + ("..." if len(nxt["name"]) > 15 else "")
        text = (
            f"💊 {name_short}\n"
            f"📦 Box {nxt['box']}\n"
            f"⏰ {nxt['time']}\n"
            f"⏳ In {time_text}"
        )

        if minutes <= 15:
            color = "#ef4444"  # urgent red
        elif minutes <= 60:
            color = "#facc15"  # soon yellow
        else:
            color = NEON_GREEN
        self.next_label.setText(text)
        self.next_label.setStyleSheet(f"color: {color}; font-weight: bold;")

    def _test_family_email(self):
        """Send a test email to the configured family email address for missed-dose escalation."""
        if self.main_window and not self.main_window.verify_admin_for_action("Test Family Email Alert"):
            return
        email = self.family_email.text().strip()
        if not email:
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(self, "Family Email", "Please enter a family email first.")
            return
        prev = dict(getattr(self.controller, "gmail_config", {}))
        gmail = dict(prev)
        sender = gmail.get("sender_email", "").strip()
        pwd = gmail.get("sender_password", "").strip()
        if not sender or not pwd:
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(self, "Family Email", "Configure Gmail alerts in System Settings first.")
            return
        gmail["recipients"] = email
        self.controller.gmail_config = gmail
        ok = False
        try:
            self.controller.send_gmail_alert(
                "CuraX Missed Dose Test",
                "This is a test email for missed-dose family notifications."
            )
            ok = True
        except Exception:
            ok = False
        self.controller.gmail_config = prev
        try:
            from PyQt6.QtWidgets import QMessageBox
        except ImportError:
            from PyQt5.QtWidgets import QMessageBox
        if ok:
            self.controller.status_message.emit("Test email sent.")
        else:
            QMessageBox.warning(self, "Family Email", "Failed to send test email. Check System Settings → Gmail Alerts.")
