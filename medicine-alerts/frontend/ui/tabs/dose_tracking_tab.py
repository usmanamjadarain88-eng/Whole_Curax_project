import datetime
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QPushButton, QFrame, QTableWidget, QTableWidgetItem,
        QHeaderView, QAbstractItemView, QScrollArea
    )
    from PyQt6.QtCore import Qt
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QPushButton, QFrame, QTableWidget, QTableWidgetItem,
        QHeaderView, QAbstractItemView, QScrollArea
    )
    from PyQt5.QtCore import Qt

from ui.styles import NEON_GREEN, TEXT_SECONDARY, TEXT_SECONDARY_LIGHT, RADIUS, ACCENT_LIGHT, SECONDARY_LIGHT_DARK


class DoseTrackingTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._init_theme_styles(getattr(controller, "appearance_theme", "light"))
        self._build_ui()
        controller.medicine_updated.connect(self._refresh)

    def _init_theme_styles(self, theme_name: str):
        """Prepare card and button styles matching curax_design_preview.html exactly."""
        name = (theme_name or "light").lower()
        if name == "light":
            # Light: .box-card, .box-btn, .tab-title from HTML
            self._title_color = "#0D6B61"       # --deep
            self._secondary_text_color = "#475569"  # --muted
            card_bg = "#F0FAF8"                  # --bg
            border  = "#B2D8D4"                  # --border
            self._btn_style_inactive = (
                "background-color: #0D9488; color: #FFFFFF; "
                "border: none; border-radius: 6px; "
                "padding: 4px 10px; font-size: 8pt; font-weight: 600;"
            )
            self._btn_style_active = (
                "background-color: #2DD4BF; color: #0A1628; "
                "border: none; border-radius: 6px; "
                "padding: 4px 10px; font-size: 8pt; font-weight: 700;"
            )
        else:
            # Dark: .dark-app .box-card, .dark-app .box-id, etc.
            self._title_color = "#2DD4BF"        # --teal-glow
            self._secondary_text_color = "#94A3B8"
            card_bg = "#0A1628"                  # .dark-app .box-card
            border  = "#1E3A4A"                  # .dark-app borders
            self._btn_style_inactive = (
                "background-color: #0D9488; color: #FFFFFF; "
                "border: none; border-radius: 6px; "
                "padding: 4px 10px; font-size: 8pt; font-weight: 600;"
            )
            self._btn_style_active = (
                "background-color: #2DD4BF; color: #0A1628; "
                "border: none; border-radius: 6px; "
                "padding: 4px 10px; font-size: 8pt; font-weight: 700;"
            )
        self._card_style_normal = f"""
            background-color: {card_bg};
            border: 1px solid {border};
            border-radius: 12px;
            padding: 2px;
        """
        self._card_style_selected = f"""
            background-color: {card_bg};
            border: 2px solid {"#0D9488" if name == "light" else "#2DD4BF"};
            border-radius: 12px;
            padding: 2px;
        """

    def apply_theme(self, theme_name: str, content_scale: float = None):
        """Called from MainWindow when theme changes at runtime."""
        self._init_theme_styles(theme_name)
        sc = content_scale if content_scale is not None else (getattr(self.window(), "_content_scale", 1.0) if self.window() else 1.0)
        s = max(0.5, min(1.0, float(sc)))
        try:
            t_pt = max(8, int(14 * s))
            self._title_label.setStyleSheet(
                f"font-size: {t_pt}pt; font-weight: 800; color: {self._title_color}; "
                "font-family: 'Segoe UI'; letter-spacing: -0.3px; background: transparent;"
            )
            self._subtitle_label.setStyleSheet(
                f"font-size: {max(7, int(9 * s))}pt; color: {self._secondary_text_color}; background: transparent;"
            )
            if hasattr(self, "_title_history"):
                self._title_history.setStyleSheet(
                    f"font-size: {t_pt}pt; font-weight: 800; color: {self._title_color}; background: transparent;"
                )
        except Exception:
            pass
        for idx, (card, box_id) in enumerate(self.dose_cards):
            card.setStyleSheet(self._card_style_normal)
            btn, med_label, qty_label, _ = self.dose_buttons[idx]
            btn.setStyleSheet(self._btn_style_inactive)
            med_label.setStyleSheet(f"color: {self._secondary_text_color};")
            qty_label.setStyleSheet(f"color: {self._secondary_text_color};")
        self._update_card_highlight()

    def _build_ui(self):
        outer = QVBoxLayout(self)
        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        container = QWidget(scroll)
        layout = QVBoxLayout(container)
        self._title_label = QLabel("Dose Tracking & History")
        self._title_label.setStyleSheet(
            f"font-size: 14pt; font-weight: 800; color: {getattr(self, '_title_color', '#0D6B61')}; "
            "font-family: 'Segoe UI'; letter-spacing: -0.3px; background: transparent;"
        )
        layout.addWidget(self._title_label)
        self._subtitle_label = QLabel("Click a box to turn ON its LED and take medicine. Then Mark Dose Taken.")
        self._subtitle_label.setStyleSheet(
            f"color: {getattr(self, '_secondary_text_color', TEXT_SECONDARY)}; font-weight: bold;"
        )
        layout.addWidget(self._subtitle_label)

        grid = QGridLayout()
        self.dose_buttons = []
        self.dose_cards = []
        for i in range(1, 7):
            box_id = f"B{i}"
            card = QFrame(container)
            card.setObjectName("card")
            card.setStyleSheet(self._card_style_normal)
            card_layout = QVBoxLayout(card)
            btn = QPushButton(f"○ {box_id}")
            btn.setStyleSheet(self._btn_style_inactive)
            btn.clicked.connect(lambda checked, b=box_id: self._on_box_click(b))
            card_layout.addWidget(btn)
            med_label = QLabel("Empty")
            med_label.setStyleSheet(f"color: {self._secondary_text_color};")
            card_layout.addWidget(med_label)
            qty_label = QLabel("0 left")
            qty_label.setStyleSheet(f"color: {self._secondary_text_color};")
            card_layout.addWidget(qty_label)
            self.dose_buttons.append((btn, med_label, qty_label, box_id))
            self.dose_cards.append((card, box_id))
            grid.addWidget(card, (i - 1) // 3, (i - 1) % 3)
        layout.addLayout(grid)

        btn_row = QHBoxLayout()
        off_btn = QPushButton("🔴 Turn OFF All LEDs")
        off_btn.setObjectName("danger")
        off_btn.setStyleSheet("background-color:#dc2626;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #b91c1c;font-size:10pt;")
        off_btn.clicked.connect(self._turn_off_leds)
        mark_btn = QPushButton("✅ Mark Dose Taken")
        mark_btn.setStyleSheet("background-color:#16a34a;color:#ffffff;font-weight:700;padding:8px 20px;border-radius:8px;border:1.5px solid #15803d;font-size:10pt;")
        mark_btn.clicked.connect(self._mark_dose)
        btn_row.addWidget(off_btn)
        btn_row.addWidget(mark_btn)
        layout.addLayout(btn_row)

        self._title_history = QLabel("📜 Dose History")
        self._title_history.setStyleSheet(
            f"font-size: 14pt; font-weight: bold; color: {getattr(self, '_title_color', NEON_GREEN)};"
        )
        layout.addWidget(self._title_history)
        self.table = QTableWidget()
        self.table.setColumnCount(5)
        self.table.setHorizontalHeaderLabels(["Timestamp", "Box", "Medicine", "Dose Taken", "Remaining"])
        try:
            hh = self.table.horizontalHeader()
            hh.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)  # Timestamp
            hh.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)  # Box
            hh.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)           # Medicine — fills space
            hh.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)  # Dose Taken
            hh.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)  # Remaining
            self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
            self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        except AttributeError:
            self.table.horizontalHeader().setResizeMode(QHeaderView.Stretch)
            self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
            self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.verticalHeader().setDefaultSectionSize(36)
        self.table.verticalHeader().setVisible(False)   # hide row numbers on left
        self.table.setAlternatingRowColors(True)
        self.table.setShowGrid(True)
        self.table.setStyleSheet("""
            QTableWidget {
                border: 2px solid #c8d8d5;
                border-radius: 8px;
                background-color: #ffffff;
                gridline-color: #d1e8e4;
                font-size: 9pt;
            }
            QTableWidget::item {
                padding: 7px 12px;
                color: #1e293b;
                border-bottom: 1px solid #e2eeec;
            }
            QTableWidget::item:selected {
                background-color: #d0f0e9;
                color: #0D4A43;
            }
            QTableWidget::item:alternate {
                background-color: #f3fbf9;
            }
            QHeaderView::section {
                background-color: #1e3a3a;
                color: #ffffff;
                font-weight: 700;
                font-size: 9pt;
                padding: 9px 12px;
                border: none;
                border-right: 1px solid #2d5050;
                border-bottom: 2px solid #0D9488;
            }
            QHeaderView::section:last {
                border-right: none;
            }
            QHeaderView::section:first {
                border-top-left-radius: 6px;
            }
        """)
        self.table.setMinimumHeight(250)
        layout.addWidget(self.table)

        scroll.setWidget(container)
        outer.addWidget(scroll)

        self._refresh()

    def _on_box_click(self, box_id):
        if not self.controller.require_admin():
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.information(
                self,
                "Admin Setup Required",
                "Features are locked because no admin account exists.\n\n"
                "Go to Settings → Admin Panel to create an admin account first.",
            )
            return

        if self.controller.connected and self.controller.authenticated:
            self.controller.send_led_on(box_id)
        else:
            self.controller.active_led_box = box_id
        self._update_card_highlight()

    def _turn_off_leds(self):
        if self.controller.connected and self.controller.authenticated:
            self.controller.send_led_all_off()
        else:
            self.controller.active_led_box = None
        self._update_card_highlight()

    def _update_card_highlight(self):
        active = self.controller.active_led_box
        for idx, (card, box_id) in enumerate(self.dose_cards):
            card.setStyleSheet(self._card_style_selected if box_id == active else self._card_style_normal)
            btn, med_label, qty_label, b_id = self.dose_buttons[idx]
            if box_id == active:
                btn.setText(f"📍 {box_id}")
                btn.setStyleSheet(self._btn_style_active)
            else:
                btn.setText(f"○ {box_id}")
                btn.setStyleSheet(self._btn_style_inactive)

    def _mark_dose(self):
        if not self.controller.require_admin():
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.information(
                self,
                "Admin Setup Required",
                "Features are locked because no admin account exists.\n\n"
                "Go to Settings → Admin Panel to create an admin account first.",
            )
            return

        box_id = self.controller.active_led_box
        if not box_id:
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(self, "Select Box", "Click a box first to select it.")
            return
        med = self.controller.medicine_boxes.get(box_id)
        if not med:
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(self, "No Medicine", f"No medicine in {box_id}.")
            return

        current_qty = med.get("quantity", 0)
        dose_per_day = med.get("dose_per_day", 1) or 1

        if current_qty < dose_per_day:
            try:
                from PyQt6.QtWidgets import QMessageBox
            except ImportError:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(
                self,
                "Not Enough Medicine",
                f"Not enough {med.get('name', 'medicine')} available in {box_id} "
                f"to take {dose_per_day} dose(s).",
            )
            return

        qty = current_qty - dose_per_day
        med["quantity"] = qty
        med["last_dose_taken"] = datetime.datetime.now().isoformat()

        self.controller.dose_log.append(
            {
                "timestamp": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "box": box_id,
                "medicine": med.get("name", ""),
                "dose_taken": dose_per_day,
                "remaining": qty,
            }
        )
        self.controller.save_data()
        self.controller.medicine_updated.emit()
        # Admin-only alert: medicine taken (full message for admin)
        try:
            name = med.get("name", "Medicine")
            msg = f"Medicine \"{name}\" taken from box {box_id}. Remaining quantity is {qty}."
            self.controller.send_admin_alert("dose_taken", msg)
        except Exception:
            pass
        if self.controller.connected and self.controller.authenticated:
            self.controller.send_led_off(box_id)
        self.controller.active_led_box = None
        self._update_card_highlight()

    def _refresh(self):
        for btn, med_label, qty_label, box_id in self.dose_buttons:
            med = self.controller.medicine_boxes.get(box_id)
            if med:
                med_label.setText(med.get("name", "Unknown"))
                qty_label.setText(f"{med.get('quantity', 0)} left")
            else:
                med_label.setText("Empty")
                qty_label.setText("0 left")
        self._update_card_highlight()
        self.table.setRowCount(0)
        for row, log in enumerate(reversed(self.controller.dose_log)):
            self.table.insertRow(row)
            # Clean up timestamp — strip T and timezone for readability
            ts = log.get("timestamp", "") or ""
            try:
                ts = ts.replace("T", " ").split("+")[0].split(".")[0]
            except Exception:
                pass
            # Medicine name
            med_name = log.get("medicine", "") or ""
            # Remaining — show "—" if missing/None
            remaining = log.get("remaining", "")
            remaining_str = str(remaining) if (remaining is not None and remaining != "") else "—"
            # Dose taken
            dose_taken = log.get("dose_taken", 1)
            dose_str = str(dose_taken) if dose_taken else "1"

            self.table.setItem(row, 0, QTableWidgetItem(ts))
            self.table.setItem(row, 1, QTableWidgetItem(log.get("box", "")))
            self.table.setItem(row, 2, QTableWidgetItem(med_name))
            self.table.setItem(row, 3, QTableWidgetItem(dose_str))
            self.table.setItem(row, 4, QTableWidgetItem(remaining_str))
            # Center-align all cells
            for col in range(5):
                item = self.table.item(row, col)
                if item:
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
