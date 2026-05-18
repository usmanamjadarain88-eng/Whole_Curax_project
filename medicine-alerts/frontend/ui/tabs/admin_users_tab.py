"""Admin Users — linked roster + Care mode (same API/fields as Android AdminUsersFragment)."""
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

from ui.tabs.admin_api import linked_users, normalize_linked_user_row
from ui.tabs.admin_async import run_bg
from ui.tabs.admin_ui_common import (
    AdminPageShell, table_item, resize_table_rows,
    table_card, muted_label, BTN_PRIMARY, BTN_DANGER,
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
        users_card = table_card(self._table, stretch_col=1)
        ul = QVBoxLayout(users_card)
        foot = QWidget()
        fl = QHBoxLayout(foot)
        fl.setContentsMargins(16, 4, 16, 12)
        self._status = muted_label("")
        fl.addWidget(self._status)
        ul.addWidget(foot)
        lo.addWidget(users_card, 1)

        if hasattr(controller, "central_fetch_done"):
            controller.central_fetch_done.connect(self._on_data_synced)
        self._fetch_gen = 0
        self.refresh()

    def _on_data_synced(self, _payload=None):
        self.refresh()

    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()

    def refresh(self):
        self._fetch_gen += 1
        gen = self._fetch_gen
        self._status.setText("Loading…")

        def work():
            if hasattr(self.controller, "ensure_admin_access_code"):
                self.controller.ensure_admin_access_code()
            return linked_users(self.controller)

        def done(result):
            if gen != self._fetch_gen:
                return
            self._table.setRowCount(0)
            if isinstance(result, Exception):
                self._status.setText(str(result))
                return
            users, err = result
            if err:
                self._status.setText(err)
                return
            if not users:
                self._status.setText("No linked users.")
                return
            self._status.setText(f"{len(users)} linked.")
            for i, u in enumerate(users):
                self._apply_user_row(i, u)
            resize_table_rows(self._table)

        run_bg(work, done)

    def _apply_user_row(self, i, u):
        row = normalize_linked_user_row(u)
        if not row:
            return
        uid = row["user_id"]
        name = row["name"]
        email = row["email"]
        mode = row["display_mode"]
        relay = "Ready" if row["desktop_linked"] else "Pending"
        self._table.insertRow(i)
        self._table.setItem(i, 0, table_item(name))
        self._table.setItem(i, 1, table_item(email))
        self._table.setItem(i, 2, table_item(mode))
        self._table.setItem(i, 3, table_item(relay))
        cw, rw = QWidget(), QWidget()
        care = QPushButton("Care")
        care.setStyleSheet(BTN_PRIMARY)
        care.clicked.connect(
            lambda _=False, id=uid, n=name, m=mode: self._open_care(id, n, m)
        )
        rm = QPushButton("Remove")
        rm.setStyleSheet(BTN_DANGER)
        rm.clicked.connect(lambda _=False, id=uid, n=name: self._remove_user(id, n))
        for cell, btn in ((cw, care), (rw, rm)):
            h = QHBoxLayout(cell)
            h.setContentsMargins(4, 2, 4, 2)
            h.addWidget(btn)
        self._table.setCellWidget(i, 4, cw)
        self._table.setCellWidget(i, 5, rw)

    def _open_care(self, user_id, name, mode):
        if not user_id:
            QMessageBox.warning(self, "Users", "Missing user id from server.")
            return
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
