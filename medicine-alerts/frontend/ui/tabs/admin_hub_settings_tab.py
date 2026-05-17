"""Hub Settings — same items as admin app menu (⋮ → Settings). No desktop link code."""
import json
import os
from datetime import datetime

try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QLabel, QComboBox,
        QMessageBox, QApplication, QDialog, QFormLayout, QLineEdit,
    )
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QLabel, QComboBox,
        QMessageBox, QApplication, QDialog, QFormLayout, QLineEdit,
    )

from ui.tabs.admin_ui_common import AdminPageShell, section_label, card_frame, BTN_PRIMARY, BTN_OUTLINE
from ui.tabs.settings_tab import SettingsTab


class AdminHubSettingsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._pin_helper = SettingsTab(controller, main_window)
        self._pin_helper.hide()

        shell = AdminPageShell("Settings", self)
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        lo.addWidget(section_label("SECURITY"))
        sec_card = card_frame()
        sec_lo = QVBoxLayout(sec_card)
        sec_lo.setContentsMargins(16, 14, 16, 14)
        sec_lo.setSpacing(10)
        self._btn_pin = QPushButton("Set PIN")
        self._btn_pin.setStyleSheet(BTN_PRIMARY)
        self._btn_pin.clicked.connect(self._on_pin)
        sec_lo.addWidget(self._btn_pin)
        self._auto_lock = QComboBox()
        self._auto_lock.addItems(["Auto-lock: 5 min", "Auto-lock: 10 min", "Auto-lock: 30 min", "Auto-lock: off"])
        self._auto_lock.currentIndexChanged.connect(self._save_auto_lock)
        sec_lo.addWidget(self._auto_lock)
        lo.addWidget(sec_card)

        lo.addWidget(section_label("ADMIN"))
        adm = card_frame()
        adm_lo = QVBoxLayout(adm)
        adm_lo.setContentsMargins(16, 14, 16, 14)
        adm_lo.setSpacing(10)
        for text, slot in (
            ("My connection code", self._connection_code),
            ("My app info", self._app_info),
            ("Export alert history", self._export_history),
            ("Help", self._help),
        ):
            b = QPushButton(text)
            b.setStyleSheet(BTN_OUTLINE)
            b.clicked.connect(slot)
            adm_lo.addWidget(b)
        lo.addWidget(adm)
        lo.addStretch(1)

        self._load_auto_lock()
        self._refresh_pin_label()

    def _refresh_pin_label(self):
        db = self.controller.get_db() if hasattr(self.controller, "get_db") else None
        has = db and getattr(db, "has_desktop_app_unlock_pin", lambda: False)()
        self._btn_pin.setText("Change PIN" if has else "Set PIN")

    def _on_pin(self):
        self._refresh_pin_label()
        self._pin_helper._on_set_desktop_unlock_pin()
        self._refresh_pin_label()

    def _load_auto_lock(self):
        db = getattr(self.controller, "_db", None)
        mins = 5
        if db and hasattr(db, "get"):
            try:
                mins = int(db.get("auto_lock_minutes") or 5)
            except Exception:
                mins = 5
        mapping = {5: 0, 10: 1, 30: 2, 0: 3}
        self._auto_lock.blockSignals(True)
        self._auto_lock.setCurrentIndex(mapping.get(mins, 0))
        self._auto_lock.blockSignals(False)

    def _save_auto_lock(self):
        db = getattr(self.controller, "_db", None)
        if not db or not hasattr(db, "set"):
            return
        idx = self._auto_lock.currentIndex()
        mins = [5, 10, 30, 0][idx]
        db.set("auto_lock_minutes", mins)

    def _connection_code(self):
        code = ""
        db = getattr(self.controller, "_db", None)
        if db and hasattr(db, "get"):
            code = (db.get("admin_connection_code") or "").strip()
        if not code and hasattr(self.controller, "get_admin_codes_from_backend"):
            _, cc = self.controller.get_admin_codes_from_backend()
            code = (cc or "").strip()
        if not code:
            QMessageBox.information(self, "Connection code", "Link admin account first.")
            return
        dlg = QDialog(self)
        dlg.setWindowTitle("Connection code")
        fl = QFormLayout(dlg)
        edit = QLineEdit(code)
        edit.setReadOnly(True)
        fl.addRow("Code:", edit)
        copy = QPushButton("Copy")
        copy.clicked.connect(lambda: QApplication.clipboard().setText(code))
        fl.addRow(copy)
        dlg.exec()

    def _app_info(self):
        db = getattr(self.controller, "_db", None)
        name = id_ = ""
        if db and hasattr(db, "get"):
            name = (db.get("admin_name") or "").strip()
            id_ = (db.get("admin_id") or "").strip()
        lines = [
            f"Admin name: {name or '—'}",
            f"Admin id: {id_ or '—'}",
            f"Desktop: {getattr(self.controller, 'logged_in_admin_name', '') or '—'}",
        ]
        QMessageBox.information(self, "App info", "\n".join(lines))

    def _export_history(self):
        alerts = getattr(self.controller, "admin_alerts", []) or []
        path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            f"alert_history_{datetime.now():%Y%m%d_%H%M%S}.json",
        )
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(alerts, f, indent=2, default=str)
            QMessageBox.information(self, "Export", f"Saved:\n{path}")
        except Exception as e:
            QMessageBox.warning(self, "Export", str(e))

    def _help(self):
        QMessageBox.information(
            self,
            "Help",
            "Dashboard — overview and stats.\n"
            "Users — open Care mode to manage medicines.\n"
            "Alerts — hub alert log.\n"
            "Reports — summaries and CSV export.\n"
            "Connections — pending links and invite code.\n"
            "Settings — this screen (same as ⋮ on phone).",
        )
