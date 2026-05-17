"""Care mode Overview — compact medicine boxes + inventory (admin app)."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout, QLabel, QPushButton,
        QFrame, QTableWidget, QDialog,
    )
    from PyQt6.QtCore import Qt
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout, QLabel, QPushButton,
        QFrame, QTableWidget, QDialog,
    )
    from PyQt5.QtCore import Qt

from ui.tabs.admin_ui_common import (
    AdminPageShell, section_label, card_frame, table_item,
    prepare_table, resize_table_rows, BTN_PRIMARY, BTN_OUTLINE,
)
from ui.tabs.add_medicine_tab import AddMedicineTab
from ui.tabs.main_panel_tab import BoxDetailDialog


class _CompactBoxCard(QFrame):
    def __init__(self, box_id: str, on_click, parent=None):
        super().__init__(parent)
        self.box_id = box_id
        self._on_click = on_click
        self.setFixedHeight(76)
        try:
            self.setCursor(Qt.CursorShape.PointingHandCursor)
        except AttributeError:
            self.setCursor(Qt.PointingHandCursor)
        self.setStyleSheet(
            "QFrame { background: #ffffff; border: 1px solid #CBD5E1; border-radius: 10px; }"
            "QFrame:hover { border-color: #0D9488; }"
        )
        lo = QVBoxLayout(self)
        lo.setContentsMargins(10, 8, 10, 8)
        lo.setSpacing(2)
        self._lbl_id = QLabel(box_id)
        self._lbl_id.setStyleSheet("font-weight: 700; color: #0D9488; font-size: 9pt;")
        self._lbl_name = QLabel("Empty")
        self._lbl_name.setWordWrap(True)
        self._lbl_name.setStyleSheet("font-weight: 600; color: #0F172A; font-size: 9pt;")
        self._lbl_qty = QLabel("")
        self._lbl_qty.setStyleSheet("color: #64748B; font-size: 8pt;")
        lo.addWidget(self._lbl_id)
        lo.addWidget(self._lbl_name)
        lo.addWidget(self._lbl_qty)

    def mousePressEvent(self, event):
        if self._on_click:
            self._on_click(self.box_id)
        super().mousePressEvent(event)

    def set_medicine(self, med):
        if not med:
            self._lbl_name.setText("Empty")
            self._lbl_qty.setText("Tap to assign")
            return
        self._lbl_name.setText((med.get("name") or "—")[:28])
        q = med.get("quantity", "—")
        t = med.get("exact_time", med.get("time", ""))
        self._lbl_qty.setText(f"Qty {q} · {t}")


class AdminCareOverviewTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._box_cards = {}

        shell = AdminPageShell("Overview", self)
        self._shell = shell
        lo = shell.body_layout()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(shell)

        self._mode_badge = QLabel("")
        self._mode_badge.setWordWrap(True)
        self._mode_badge.setStyleSheet(
            "background: #E0F2F1; color: #0F766E; padding: 8px 12px;"
            "border-radius: 8px; font-weight: 600;"
        )
        lo.addWidget(self._mode_badge)

        add_row = QHBoxLayout()
        add_row.addStretch(1)
        btn_add = QPushButton("+ Add medicine")
        btn_add.setStyleSheet(BTN_PRIMARY)
        btn_add.clicked.connect(self._add_medicine)
        add_row.addWidget(btn_add)
        lo.addLayout(add_row)

        lo.addWidget(section_label("MEDICINE BOXES"))
        box_card = card_frame()
        box_lo = QVBoxLayout(box_card)
        box_lo.setContentsMargins(12, 12, 12, 12)
        grid = QGridLayout()
        grid.setSpacing(8)
        for i in range(1, 7):
            bid = f"B{i}"
            c = _CompactBoxCard(bid, self._open_box)
            self._box_cards[bid] = c
            r, col = divmod(i - 1, 3)
            grid.addWidget(c, r, col)
        box_lo.addLayout(grid)
        lo.addWidget(box_card)

        lo.addWidget(section_label("INVENTORY"))
        self._inv_table = QTableWidget(0, 7)
        self._inv_table.setHorizontalHeaderLabels(
            ["Medicine", "Box", "Qty", "Dose/day", "Expiry", "Time", ""]
        )
        prepare_table(self._inv_table, stretch_col=0)
        self._inv_table.setMinimumHeight(180)
        lo.addWidget(self._inv_table, 1)

        if hasattr(controller, "medicine_updated"):
            controller.medicine_updated.connect(self.refresh)
        if hasattr(controller, "care_mode_changed"):
            controller.care_mode_changed.connect(self._update_mode)
        self._update_mode()
        self.refresh()

    def _update_mode(self):
        mode = (getattr(self.controller, "act_as_user_display_mode", "") or "default").strip()
        name = (getattr(self.controller, "act_as_user_name", "") or "User").strip()
        label = "Standalone" if mode == "standalone" else "Default"
        self._mode_badge.setText(f"Managing {name} · {label} app")
        self._shell.set_title(f"Overview · {name}")

    def refresh(self):
        self._update_mode()
        boxes = getattr(self.controller, "medicine_boxes", {}) or {}
        for bid, card in self._box_cards.items():
            card.set_medicine(boxes.get(bid))
        self._inv_table.setRowCount(0)
        row = 0
        for bid in sorted(boxes.keys()):
            med = boxes.get(bid)
            if not med:
                continue
            self._inv_table.insertRow(row)
            self._inv_table.setItem(row, 0, table_item(med.get("name", "")))
            self._inv_table.setItem(row, 1, table_item(bid))
            self._inv_table.setItem(row, 2, table_item(str(med.get("quantity", ""))))
            self._inv_table.setItem(row, 3, table_item(str(med.get("dose_per_day", ""))))
            self._inv_table.setItem(row, 4, table_item(str(med.get("expiry", ""))))
            self._inv_table.setItem(row, 5, table_item(str(med.get("exact_time", med.get("time", "")))))
            cell = QWidget()
            h = QHBoxLayout(cell)
            h.setContentsMargins(2, 2, 2, 2)
            ob = QPushButton("Open")
            ob.setStyleSheet(BTN_OUTLINE)
            ob.clicked.connect(lambda _=False, b=bid: self._open_box(b))
            h.addWidget(ob)
            self._inv_table.setCellWidget(row, 6, cell)
            row += 1
        if row == 0:
            self._inv_table.setRowCount(1)
            self._inv_table.setItem(0, 0, table_item("No medicines. Use Add medicine."))
        resize_table_rows(self._inv_table)

    def _open_box(self, box_id):
        boxes = getattr(self.controller, "medicine_boxes", {}) or {}
        med = boxes.get(box_id)
        if not med:
            self._add_medicine(box_id)
            return
        dlg = BoxDetailDialog(box_id, med, self.controller, self)
        dlg.exec()
        self.refresh()

    def _add_medicine(self, preselect_box=None):
        dlg = QDialog(self)
        dlg.setWindowTitle("Add medicine")
        dlg.setMinimumSize(540, 580)
        lay = QVBoxLayout(dlg)
        form = AddMedicineTab(self.controller, self.main_window, dlg)
        lay.addWidget(form)
        if preselect_box and hasattr(form, "med_box"):
            ix = form.med_box.findText(preselect_box)
            if ix >= 0:
                form.med_box.setCurrentIndex(ix)
        close = QPushButton("Done")
        close.setStyleSheet(BTN_PRIMARY)
        close.clicked.connect(dlg.accept)
        lay.addWidget(close)
        dlg.exec()
        self.refresh()
