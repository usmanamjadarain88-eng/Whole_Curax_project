"""Admin Dashboard — Android AdminHubFragment."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QFrame,
        QTableWidget,
    )
    from PyQt6.QtCore import Qt, QTimer
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QFrame,
        QTableWidget,
    )
    from PyQt5.QtCore import Qt, QTimer

from datetime import datetime, timedelta
from ui.tabs.admin_api import linked_users, admin_access_code
from ui.tabs.admin_chart_widgets import HubChartPanel
from ui.tabs.admin_ui_common import (
    AdminPageShell, section_label, table_item, resize_table_rows,
    BTN_PRIMARY, card_frame, card_layout, table_card,
    ADMIN_BORDER, ADMIN_MUTED,
)


class _ClickableStatCard(QFrame):
    def __init__(self, on_click=None, parent=None):
        super().__init__(parent)
        self._on_click = on_click
        self.setObjectName("hubStatCard")
        self.setStyleSheet(
            f"#hubStatCard {{ background: #F8FAFC; border: 1px solid {ADMIN_BORDER}; border-radius: 12px; }}"
            "#hubStatCard:hover { border-color: #0D9488; }"
        )
        if on_click:
            try:
                self.setCursor(Qt.CursorShape.PointingHandCursor)
            except AttributeError:
                self.setCursor(Qt.PointingHandCursor)

    def mousePressEvent(self, event):
        if self._on_click:
            self._on_click()
        super().mousePressEvent(event)


def _stat_card(title: str, value: str, on_click=None):
    frame = _ClickableStatCard(on_click=on_click)
    lo = QVBoxLayout(frame)
    lo.setContentsMargins(10, 10, 10, 10)
    val = QLabel(value)
    val.setWordWrap(True)
    val.setAlignment(Qt.AlignmentFlag.AlignCenter)
    val.setStyleSheet("font-size: 18pt; font-weight: 700; color: #0F766E;")
    cap = QLabel(title)
    cap.setWordWrap(True)
    cap.setAlignment(Qt.AlignmentFlag.AlignCenter)
    cap.setStyleSheet(f"font-size: 9pt; color: {ADMIN_MUTED};")
    lo.addWidget(val)
    lo.addWidget(cap)
    return frame, val


class AdminDashboardTab(QWidget):
    TAB_USERS = 1
    TAB_ALERTS = 2

    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window

        shell = AdminPageShell("Dashboard", self)
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        ref = QPushButton("Refresh")
        ref.setStyleSheet(BTN_PRIMARY)
        ref.clicked.connect(self.refresh)
        shell.add_header_widget(ref)

        hero = QFrame()
        hero.setStyleSheet(
            "QFrame { background: qlineargradient(x1:0,y1:0,x2:1,y2:1,"
            "stop:0 #0D9488, stop:1 #14B8A6); border-radius: 14px; }"
        )
        hl = QVBoxLayout(hero)
        hl.setContentsMargins(20, 16, 20, 16)
        self._greeting = QLabel("Welcome")
        self._greeting.setWordWrap(True)
        self._greeting.setStyleSheet("font-size: 22pt; font-weight: 800; color: #fff;")
        self._tagline = QLabel("Manage linked users, alerts, and Care mode.")
        self._tagline.setWordWrap(True)
        self._tagline.setStyleSheet("font-size: 10pt; color: rgba(255,255,255,0.95);")
        hl.addWidget(self._greeting)
        hl.addWidget(self._tagline)
        lo.addWidget(hero)

        lo.addWidget(section_label("AT A GLANCE"))
        stats = QHBoxLayout()
        self._card_users, self._val_users = _stat_card(
            "Users", "0", on_click=lambda: self._go_tab(self.TAB_USERS)
        )
        self._card_relay, self._val_relay = _stat_card("Relay", "Off")
        self._card_alerts, self._val_alerts = _stat_card(
            "Alerts log", "0", on_click=lambda: self._go_tab(self.TAB_ALERTS)
        )
        stats.addWidget(self._card_users, 1)
        stats.addWidget(self._card_relay, 1)
        stats.addWidget(self._card_alerts, 1)
        lo.addLayout(stats)

        lo.addWidget(section_label("CONNECTION READINESS"))
        ready = card_frame()
        rl = QVBoxLayout(ready)
        rl.setContentsMargins(16, 14, 16, 14)
        self._readiness_pct = QLabel("—")
        self._readiness_pct.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._readiness_pct.setStyleSheet("font-size: 28pt; font-weight: 800; color: #0D9488;")
        self._legend = QLabel("Ready · 0  |  Pending · 0")
        self._legend.setWordWrap(True)
        self._legend.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._readiness_caption = QLabel("")
        self._readiness_caption.setWordWrap(True)
        self._readiness_caption.setStyleSheet("color: #64748B; font-size: 9pt;")
        rl.addWidget(self._readiness_pct)
        rl.addWidget(self._legend)
        rl.addWidget(self._readiness_caption)
        lo.addWidget(ready)

        lo.addWidget(section_label("PULSE"))
        pulse = QHBoxLayout()
        p1, self._val_pulse_alerts = _stat_card("Alerts · 7d", "0")
        p2, self._val_pulse_peak = _stat_card("Peak day", "0")
        p3, self._val_pulse_ready = _stat_card("Ready", "—")
        pulse.addWidget(p1, 1)
        pulse.addWidget(p2, 1)
        pulse.addWidget(p3, 1)
        lo.addLayout(pulse)

        lo.addWidget(section_label("INSIGHTS"))
        charts_card = card_frame()
        ch_lo = card_layout(charts_card)
        self._charts = HubChartPanel()
        self._charts.setMinimumHeight(160)
        ch_lo.addWidget(self._charts)
        lo.addWidget(charts_card)

        lo.addWidget(section_label("RECENT ACTIVITY"))
        self._alerts_table = QTableWidget(0, 3)
        self._alerts_table.setHorizontalHeaderLabels(["Type", "Time", "Detail"])
        lo.addWidget(table_card(self._alerts_table, stretch_col=2))

        lo.addWidget(section_label("DOSE HISTORY"))
        dose_card = card_frame()
        dl = card_layout(dose_card)
        self._dose_preview = QLabel("")
        self._dose_preview.setWordWrap(True)
        self._dose_preview.setStyleSheet(
            f"color: #334155; font-size: 10pt; background: transparent; border: none;"
        )
        dl.addWidget(self._dose_preview)
        lo.addWidget(dose_card)
        lo.addStretch(1)

        self._refresh_timer = QTimer(self)
        self._refresh_timer.setInterval(60_000)
        self._refresh_timer.timeout.connect(self.refresh)
        if hasattr(controller, "medicine_updated"):
            controller.medicine_updated.connect(self.refresh)
        if hasattr(controller, "admin_status_changed"):
            controller.admin_status_changed.connect(self.refresh)
        self.refresh()

    def showEvent(self, event):
        super().showEvent(event)
        self._refresh_timer.start()
        self.refresh()

    def hideEvent(self, event):
        self._refresh_timer.stop()
        super().hideEvent(event)

    def _go_tab(self, index: int):
        mw = self.main_window
        if mw and hasattr(mw, "tabs"):
            try:
                mw.tabs.setCurrentIndex(index)
            except Exception:
                pass

    def _admin_first_name(self) -> str:
        name = (getattr(self.controller, "logged_in_admin_name", None) or "").strip()
        if not name:
            db = getattr(self.controller, "_db", None)
            if db and hasattr(db, "get"):
                name = (db.get("admin_name") or "").strip()
        return name.split()[0] if name else ""

    def refresh(self):
        first = self._admin_first_name()
        self._greeting.setText(f"Hi, {first}" if first else "Welcome")
        alerts = getattr(self.controller, "admin_alerts", []) or []
        if not isinstance(alerts, list):
            alerts = []
        self._val_alerts.setText(str(len(alerts)))
        relay_on = bool(getattr(self.controller, "admin_logged_in", False)) and bool(
            admin_access_code(self.controller)
        )
        self._val_relay.setText("Live" if relay_on else "Off")
        self._fill_recent_alerts(alerts[:15])
        self._fill_pulse(alerts)
        self._fill_charts(alerts)
        if not admin_access_code(self.controller):
            self._val_users.setText("0")
            self._apply_readiness(0, 0)
            self._dose_preview.setText("Link desktop from admin app.")
            return
        users, err = linked_users(self.controller, dose_preview=False)
        if err:
            self._val_users.setText("—")
            self._dose_preview.setText(err)
            return
        users = users or []
        self._val_users.setText(str(len(users)))
        linked = pending = 0
        for u in users:
            if not isinstance(u, dict):
                continue
            if (u.get("bot_id") or "").strip():
                linked += 1
            else:
                pending += 1
        self._apply_readiness(linked, pending)
        self._val_pulse_ready.setText(f"{int(100 * linked / len(users))}%" if users else "—")
        users_dose, err2 = linked_users(self.controller, dose_preview=True)
        self._dose_preview.setText(err2 if err2 else "")
        if not err2:
            self._fill_dose_preview(users_dose or [])

    def _apply_readiness(self, linked, pending):
        total = linked + pending
        self._legend.setText(f"Ready · {linked}  |  Pending · {pending}")
        if total <= 0:
            self._readiness_pct.setText("—")
            self._readiness_caption.setText("Share connection code from Connections.")
        else:
            self._readiness_pct.setText(f"{int(100 * linked / total)}%")
            self._readiness_caption.setText(f"{total} accounts in roster.")

    def _fill_charts(self, alerts):
        today = datetime.now().date()
        buckets = [0] * 7
        for a in alerts:
            if not isinstance(a, dict):
                continue
            ts = (a.get("timestamp") or a.get("created_at") or "")[:19]
            try:
                d = datetime.fromisoformat(ts.replace("Z", "")).date()
            except Exception:
                continue
            for i in range(7):
                if d == today - timedelta(days=6 - i):
                    buckets[i] += 1
        self._charts.set_week_counts(buckets)

    def _fill_pulse(self, alerts):
        cutoff = datetime.now() - timedelta(days=7)
        counts = {}
        for a in alerts:
            if not isinstance(a, dict):
                continue
            ts = (a.get("timestamp") or a.get("created_at") or "")[:19]
            try:
                dt = datetime.fromisoformat(ts.replace("Z", ""))
            except Exception:
                continue
            if dt >= cutoff:
                counts[dt.strftime("%Y-%m-%d")] = counts.get(dt.strftime("%Y-%m-%d"), 0) + 1
        self._val_pulse_alerts.setText(str(sum(counts.values())))
        self._val_pulse_peak.setText(str(max(counts.values()) if counts else 0))

    def _fill_recent_alerts(self, rows):
        self._alerts_table.setRowCount(0)
        if not rows:
            self._alerts_table.setRowCount(1)
            self._alerts_table.setItem(0, 2, table_item("No alerts yet."))
            return
        for i, a in enumerate(rows):
            if not isinstance(a, dict):
                continue
            self._alerts_table.insertRow(i)
            typ = (a.get("type") or "Alert").strip()
            ts = (a.get("timestamp") or a.get("created_at") or "")[:19].replace("T", " ")
            detail = (a.get("message") or a.get("detail") or "").strip()
            self._alerts_table.setItem(i, 0, table_item(typ))
            self._alerts_table.setItem(i, 1, table_item(ts))
            self._alerts_table.setItem(i, 2, table_item(detail))
        resize_table_rows(self._alerts_table)

    def _fill_dose_preview(self, users):
        lines = []
        for u in users:
            if not isinstance(u, dict):
                continue
            name = (u.get("name") or "User").strip()
            logs = u.get("recent_doses") or []
            if isinstance(logs, list) and logs:
                lines.append(name)
                for e in logs[:4]:
                    if isinstance(e, dict):
                        ts = (e.get("timestamp") or "")[:16].replace("T", " ")
                        med = e.get("medicine") or e.get("medicine_name") or "—"
                        lines.append(f"  {ts}  {med}")
                lines.append("")
        self._dose_preview.setText("\n".join(lines).strip() or "No dose logs yet.")
