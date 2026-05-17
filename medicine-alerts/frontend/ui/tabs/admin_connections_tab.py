"""Admin Connections."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton,
        QTableWidget, QMessageBox, QApplication,
    )
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton,
        QTableWidget, QMessageBox, QApplication,
    )

from ui.tabs.admin_api import admin_access_code, api_base, pending_link_requests, post_json
from ui.tabs.admin_async import run_bg
from ui.tabs.admin_ui_common import (
    AdminPageShell, section_label, card_frame, card_layout, table_item,
    resize_table_rows, prepare_table, TABLE_STYLE, apply_input_style,
    muted_label, BTN_PRIMARY, BTN_OUTLINE,
)


class AdminConnectionsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        shell = AdminPageShell("Connections", self)
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        ref = QPushButton("Refresh")
        ref.setStyleSheet(BTN_PRIMARY)
        ref.clicked.connect(self.refresh)
        shell.add_header_widget(ref)

        lo.addWidget(section_label("PENDING REQUESTS"))
        self._pending = QTableWidget(0, 4)
        self._pending.setHorizontalHeaderLabels(["Name", "Email", "When", ""])
        prepare_table(self._pending, stretch_col=1)
        self._pending.setStyleSheet(TABLE_STYLE)
        pending_card = card_frame()
        pl = QVBoxLayout(pending_card)
        pl.setContentsMargins(0, 0, 0, 0)
        pl.addWidget(self._pending)
        foot = QWidget()
        fl = QHBoxLayout(foot)
        fl.setContentsMargins(16, 4, 16, 12)
        self._empty = muted_label("No pending requests.")
        fl.addWidget(self._empty)
        pl.addWidget(foot)
        lo.addWidget(pending_card)

        lo.addWidget(section_label("CONNECTION CODE"))
        code_card = card_frame()
        cl = card_layout(code_card)
        row = QHBoxLayout()
        self._code = QLineEdit()
        self._code.setReadOnly(True)
        apply_input_style(self._code)
        row.addWidget(self._code, 1)
        copy = QPushButton("Copy")
        copy.setStyleSheet(BTN_OUTLINE)
        copy.clicked.connect(self._copy_code)
        row.addWidget(copy)
        cl.addLayout(row)
        lo.addWidget(code_card)
        lo.addStretch(1)
        self._fetch_gen = 0
        self.refresh()

    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()

    def refresh(self):
        self._load_code()
        self._fetch_gen += 1
        gen = self._fetch_gen

        def work():
            return pending_link_requests(self.controller, resolve_code=True)

        def done(result):
            if gen != self._fetch_gen:
                return
            if isinstance(result, Exception):
                self._empty.setText(str(result))
                self._empty.show()
                self._pending.setRowCount(0)
                return
            self._apply_pending(result)

        run_bg(work, done)

    def _load_code(self):
        code = ""
        db = getattr(self.controller, "_db", None)
        if db and hasattr(db, "get"):
            code = (db.get("admin_connection_code") or "").strip()
        self._code.setText(code or "—")

    def _apply_pending(self, result):
        reqs, err = result
        self._pending.setRowCount(0)
        if err or not reqs:
            self._empty.setText(err or "No pending requests.")
            self._empty.show()
            return
        self._empty.hide()
        for i, r in enumerate(reqs):
            if not isinstance(r, dict):
                continue
            rid = (r.get("id") or r.get("request_id") or "").strip()
            self._pending.insertRow(i)
            self._pending.setItem(i, 0, table_item(r.get("name") or "User"))
            self._pending.setItem(i, 1, table_item(r.get("email") or ""))
            self._pending.setItem(i, 2, table_item((r.get("created_at") or "")[:19].replace("T", " ")))
            cell = QWidget()
            h = QHBoxLayout(cell)
            h.setContentsMargins(4, 2, 4, 2)
            a = QPushButton("Accept")
            a.clicked.connect(lambda _=False, x=rid: self._act(x, True))
            d = QPushButton("Decline")
            d.clicked.connect(lambda _=False, x=rid: self._act(x, False))
            h.addWidget(a)
            h.addWidget(d)
            self._pending.setCellWidget(i, 3, cell)
        resize_table_rows(self._pending)

    def _act(self, request_id, accept):
        code = admin_access_code(self.controller)
        base = api_base(self.controller)
        if not code or not base:
            return
        path = "/admin/accept-user-link-request" if accept else "/admin/reject-user-link-request"
        try:
            post_json(f"{base}{path}", {"access_code": code, "request_id": request_id})
            self.refresh()
        except Exception as e:
            QMessageBox.warning(self, "Connections", str(e))

    def _copy_code(self):
        t = self._code.text().strip()
        if t and t != "—":
            QApplication.clipboard().setText(t)
