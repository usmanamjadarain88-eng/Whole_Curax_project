"""Admin Users — linked roster + Care mode."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTableWidget, QPushButton,
        QMessageBox,
    )
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTableWidget, QPushButton,
        QMessageBox,
    )

from ui.tabs.admin_api import linked_users
from ui.tabs.admin_ui_common import (
    AdminPageShell, table_item, resize_table_rows, prepare_table,
    TABLE_STYLE, card_frame, muted_label, BTN_PRIMARY, BTN_DANGER,
)


class AdminUsersTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        shell = AdminPageShell("Users", self)
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        ref = QPushButton("Refresh")
        ref.setStyleSheet(BTN_PRIMARY)
        ref.clicked.connect(self.refresh)
        shell.add_header_widget(ref)

        self._table = QTableWidget(0, 6)
        self._table.setHorizontalHeaderLabels(
            ["Name", "Email", "Mode", "Relay", "Care", "Remove"]
        )
        prepare_table(self._table, stretch_col=1)
        self._table.setStyleSheet(TABLE_STYLE)
        users_card = card_frame()
        ul = QVBoxLayout(users_card)
        ul.setContentsMargins(0, 0, 0, 0)
        ul.setSpacing(0)
        ul.addWidget(self._table, 1)
        foot = QWidget()
        fl = QHBoxLayout(foot)
        fl.setContentsMargins(16, 4, 16, 12)
        self._status = muted_label("")
        fl.addWidget(self._status)
        ul.addWidget(foot)
        lo.addWidget(users_card, 1)

        if hasattr(controller, "medicine_updated"):
            controller.medicine_updated.connect(self.refresh)
        self.refresh()

    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()

    def refresh(self):
        self._table.setRowCount(0)
        users, err = linked_users(self.controller)
        if err:
            self._status.setText(err)
            return
        if not users:
            self._status.setText("No linked users.")
            return
        self._status.setText(f"{len(users)} linked.")
        for i, u in enumerate(users):
            if not isinstance(u, dict):
                continue
            uid = (u.get("user_id") or u.get("id") or "").strip()
            name = (u.get("name") or u.get("full_name") or "User").strip()
            self._table.insertRow(i)
            self._table.setItem(i, 0, table_item(name))
            self._table.setItem(i, 1, table_item(u.get("email") or ""))
            self._table.setItem(i, 2, table_item(u.get("app_mode") or u.get("mode") or "default"))
            self._table.setItem(i, 3, table_item("Ready" if (u.get("bot_id") or "").strip() else "Pending"))
            cw, rw = QWidget(), QWidget()
            care = QPushButton("Care")
            care.setStyleSheet(BTN_PRIMARY)
            care.clicked.connect(lambda _=False, id=uid, n=name, m=u.get("app_mode") or "default": self._open_care(id, n, m))
            rm = QPushButton("Remove")
            rm.setStyleSheet(BTN_DANGER)
            rm.clicked.connect(lambda _=False, id=uid, n=name: self._remove_user(id, n))
            for cell, btn in ((cw, care), (rw, rm)):
                h = QHBoxLayout(cell)
                h.setContentsMargins(4, 2, 4, 2)
                h.addWidget(btn)
            self._table.setCellWidget(i, 4, cw)
            self._table.setCellWidget(i, 5, rw)
        resize_table_rows(self._table)

    def _open_care(self, user_id, name, mode):
        fn = getattr(self.controller, "enter_care_mode", None)
        if fn and fn(user_id, name, mode)[0]:
            if self.main_window and hasattr(self.main_window, "show_care_mode_ui"):
                self.main_window.show_care_mode_ui()

    def _remove_user(self, user_id, name):
        if QMessageBox.question(self, "Remove", f"Remove {name}?") != QMessageBox.StandardButton.Yes:
            return
        fn = getattr(self.controller, "delete_linked_user", None)
        if fn:
            ok, msg = fn(user_id)
            if ok:
                self.refresh()
            else:
                QMessageBox.warning(self, "Users", msg or "Failed.")
