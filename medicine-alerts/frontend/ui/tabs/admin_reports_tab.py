"""Admin Reports."""
import csv
import os
from datetime import datetime

try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTextEdit, QComboBox,
        QPushButton, QFileDialog, QMessageBox,
    )
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTextEdit, QComboBox,
        QPushButton, QFileDialog, QMessageBox,
    )

from ui.tabs.admin_api import linked_users, admin_access_code
from ui.tabs.admin_async import run_bg
from ui.tabs.admin_ui_common import (
    AdminPageShell, toolbar_card, text_card, apply_input_style,
    BTN_PRIMARY, BTN_OUTLINE, ADMIN_MUTED,
)


class AdminReportsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self._linked = []
        shell = AdminPageShell("Reports", self)
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        tool, tool_lo = toolbar_card()
        lbl = QLabel("Report for")
        lbl.setStyleSheet(f"color: {ADMIN_MUTED}; font-weight: 600; background: transparent;")
        tool_lo.addWidget(lbl)
        self._user_combo = QComboBox()
        self._user_combo.addItem("All hub", "")
        apply_input_style(self._user_combo)
        self._user_combo.currentIndexChanged.connect(self.refresh)
        tool_lo.addWidget(self._user_combo, 1)
        ref = QPushButton("Refresh")
        ref.setStyleSheet(BTN_PRIMARY)
        ref.clicked.connect(self._reload_users)
        tool_lo.addWidget(ref)
        ex = QPushButton("Export CSV")
        ex.setStyleSheet(BTN_OUTLINE)
        ex.clicked.connect(self._export_csv)
        tool_lo.addWidget(ex)
        lo.addWidget(tool)

        self._body = QTextEdit()
        self._body.setReadOnly(True)
        try:
            self._body.setLineWrapMode(QTextEdit.LineWrapMode.WidgetWidth)
        except AttributeError:
            self._body.setLineWrapMode(QTextEdit.WidgetWidth)
        lo.addWidget(text_card(self._body), 1)
        if hasattr(controller, "medicine_updated"):
            controller.medicine_updated.connect(self.refresh)
        self._fetch_gen = 0
        self._reload_users()

    def showEvent(self, event):
        super().showEvent(event)
        self._reload_users()

    def _reload_users(self):
        self._fetch_gen += 1
        gen = self._fetch_gen

        def work():
            return linked_users(self.controller)

        def done(result):
            if gen != self._fetch_gen:
                return
            self._user_combo.blockSignals(True)
            self._user_combo.clear()
            self._user_combo.addItem("All hub", "")
            if isinstance(result, Exception):
                self._linked = []
            else:
                users, _ = result
                self._linked = users or []
                for u in self._linked:
                    if isinstance(u, dict):
                        uid = (u.get("user_id") or u.get("id") or "").strip()
                        if uid:
                            self._user_combo.addItem((u.get("name") or "User").strip(), uid)
            self._user_combo.blockSignals(False)
            self.refresh()

        run_bg(work, done)

    def refresh(self):
        if not admin_access_code(self.controller):
            self._body.setPlainText("Link desktop first.")
            return
        uid = self._user_combo.currentData()
        boxes = getattr(self.controller, "medicine_boxes", {}) or {}
        dose_log = getattr(self.controller, "dose_log", []) or []
        alerts = getattr(self.controller, "admin_alerts", []) or []
        if uid:
            user = next((u for u in self._linked if isinstance(u, dict) and (u.get("user_id") or u.get("id")) == uid), None)
            name = (user.get("name") or "User") if user else "User"
            logs = (user.get("recent_doses") or []) if user else []
            lines = [f"Report: {name}", "", "Recent doses:"]
            for e in (logs if isinstance(logs, list) else [])[-25:]:
                if isinstance(e, dict):
                    lines.append(f"  {(e.get('timestamp') or '')[:19]}  {e.get('medicine') or '—'}")
            self._body.setPlainText("\n".join(lines))
            return
        lines = [
            "Hub report",
            f"Medicines: {sum(1 for b in boxes.values() if b)}",
            f"Doses: {len(dose_log)}",
            f"Alerts: {len(alerts) if isinstance(alerts, list) else 0}",
            f"Users: {len(self._linked)}",
            "",
            "Recent doses:",
        ]
        for e in dose_log[-25:]:
            if isinstance(e, dict):
                lines.append(f"  {(e.get('timestamp') or '')[:19]}  {e.get('medicine') or '—'}")
        self._body.setPlainText("\n".join(lines))

    def _export_csv(self):
        path, _ = QFileDialog.getSaveFileName(
            self, "Export CSV",
            os.path.join(os.path.expanduser("~"), f"curax_{datetime.now():%Y%m%d}.csv"),
            "CSV (*.csv)",
        )
        if path:
            with open(path, "w", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                for line in self._body.toPlainText().splitlines():
                    w.writerow([line])
