"""Admin Alerts — hub inbox."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLineEdit, QPushButton,
        QTableWidget, QTableWidgetItem, QComboBox, QMessageBox,
    )
    from PyQt6.QtCore import Qt
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLineEdit, QPushButton,
        QTableWidget, QTableWidgetItem, QComboBox, QMessageBox,
    )
    from PyQt5.QtCore import Qt

from ui.tabs.admin_ui_common import (
    AdminPageShell, table_item, resize_table_rows, apply_input_style,
    table_card, toolbar_card, BTN_PRIMARY, BTN_DANGER,
)


class AdminAlertsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self._selected_ids = set()
        shell = AdminPageShell("Alerts", self)
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        tool, tool_lo = toolbar_card()
        self._search = QLineEdit()
        self._search.setPlaceholderText("Search alerts…")
        apply_input_style(self._search)
        self._search.textChanged.connect(self.refresh)
        tool_lo.addWidget(self._search, 1)
        self._status = QComboBox()
        self._status.addItems(["All", "Taken", "Missed", "System"])
        apply_input_style(self._status)
        self._status.currentIndexChanged.connect(self.refresh)
        tool_lo.addWidget(self._status)
        self._sort = QComboBox()
        self._sort.addItems(["Newest", "Oldest"])
        apply_input_style(self._sort)
        self._sort.currentIndexChanged.connect(self.refresh)
        tool_lo.addWidget(self._sort)
        del_btn = QPushButton("Delete selected")
        del_btn.setStyleSheet(BTN_DANGER)
        del_btn.clicked.connect(self._delete_selected)
        tool_lo.addWidget(del_btn)
        ref = QPushButton("Refresh")
        ref.setStyleSheet(BTN_PRIMARY)
        ref.clicked.connect(self._reload)
        tool_lo.addWidget(ref)
        lo.addWidget(tool)

        self._table = QTableWidget(0, 5)
        self._table.setHorizontalHeaderLabels(["", "Type", "Time", "User", "Detail"])
        self._table.itemChanged.connect(self._on_item_changed)
        lo.addWidget(table_card(self._table, stretch_col=4), 1)

        if hasattr(controller, "medicine_updated"):
            controller.medicine_updated.connect(self.refresh)
        self.refresh()

    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()

    def _reload(self):
        fn = getattr(self.controller, "fetch_from_central_and_apply", None)
        if fn:
            fn()
        self.refresh()

    def _on_item_changed(self, item):
        if item.column() != 0:
            return
        aid = item.data(Qt.ItemDataRole.UserRole)
        if item.checkState() == Qt.CheckState.Checked:
            if aid:
                self._selected_ids.add(str(aid))
        elif aid in self._selected_ids:
            self._selected_ids.discard(str(aid))

    def refresh(self):
        self._table.blockSignals(True)
        self._table.setRowCount(0)
        alerts = getattr(self.controller, "admin_alerts", []) or []
        q = (self._search.text() or "").strip().lower()
        si = self._status.currentIndex()
        newest = self._sort.currentIndex() == 0

        def _ts(a):
            return (a.get("created_at") or a.get("timestamp") or "") if isinstance(a, dict) else ""

        rows = sorted([a for a in alerts if isinstance(a, dict)], key=_ts, reverse=newest)
        for a in rows:
            typ = (a.get("type") or "").lower()
            msg = (a.get("message") or "").lower()
            st = (a.get("status") or "").lower()
            if q and q not in typ and q not in msg:
                continue
            if si == 1 and "taken" not in typ and "taken" not in st:
                continue
            if si == 2 and "missed" not in typ and "missed" not in st:
                continue
            if si == 3 and any(x in typ for x in ("taken", "missed", "dose")):
                continue
            i = self._table.rowCount()
            self._table.insertRow(i)
            aid = str(a.get("id") or f"r{i}")
            chk = QTableWidgetItem()
            try:
                chk.setFlags(Qt.ItemFlag.ItemIsUserCheckable | Qt.ItemFlag.ItemIsEnabled)
                chk.setCheckState(
                    Qt.CheckState.Checked if aid in self._selected_ids else Qt.CheckState.Unchecked
                )
                chk.setData(Qt.ItemDataRole.UserRole, aid)
            except AttributeError:
                chk.setFlags(Qt.ItemIsUserCheckable | Qt.ItemIsEnabled)
                chk.setCheckState(Qt.Checked if aid in self._selected_ids else Qt.Unchecked)
                chk.setData(Qt.UserRole, aid)
            self._table.setItem(i, 0, chk)
            self._table.setItem(i, 1, table_item(a.get("type") or "Alert"))
            self._table.setItem(i, 2, table_item(_ts(a)[:19].replace("T", " ")))
            self._table.setItem(i, 3, table_item(a.get("user_name") or ""))
            self._table.setItem(i, 4, table_item(a.get("message") or ""))
        if self._table.rowCount() == 0:
            self._table.setRowCount(1)
            self._table.setItem(0, 4, table_item("No alerts."))
        self._table.blockSignals(False)
        resize_table_rows(self._table)

    def _delete_selected(self):
        if not self._selected_ids:
            return
        self.controller.admin_alerts = [
            a for a in (getattr(self.controller, "admin_alerts", []) or [])
            if isinstance(a, dict) and str(a.get("id") or "") not in self._selected_ids
        ]
        self._selected_ids.clear()
        if hasattr(self.controller, "save_data"):
            try:
                self.controller.save_data()
            except Exception:
                pass
        self.controller.medicine_updated.emit()
        self.refresh()
