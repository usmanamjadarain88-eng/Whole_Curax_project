try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QLineEdit, QSpinBox, QComboBox, QPushButton,
        QDateEdit, QTextEdit, QScrollArea, QFrame, QSizePolicy
    )
    from PyQt6.QtCore import Qt, QDate
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QLineEdit, QSpinBox, QComboBox, QPushButton,
        QDateEdit, QTextEdit, QScrollArea, QFrame, QSizePolicy
    )
    from PyQt5.QtCore import Qt, QDate

from ui.styles import (
    NEON_GREEN,
    NEON_GREEN_DIM,
    BG_CARD,
    BG_CARD_LIGHT,
    BG_INPUT,
    BG_INPUT_LIGHT,
    BORDER,
    TEXT_PRIMARY,
    TEXT_PRIMARY_LIGHT,
    ACCENT_LIGHT,
    SECONDARY_LIGHT_DARK,
)


class AddMedicineTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._input_fields = []
        self._save_pending = False
        self._pending_save_box_id = None
        self._pending_save_medicine = None
        self._build_ui()
        if hasattr(controller, "save_done"):
            controller.save_done.connect(self._on_save_done)
        self.apply_theme(getattr(controller, "appearance_theme", "light"))

    def apply_theme(self, theme_name: str, content_scale: float = None):
        name = (theme_name or "light").lower()
        sc = content_scale if content_scale is not None else (getattr(self.window(), "_content_scale", 1.0) if self.window() else 1.0)
        s = max(0.5, min(1.0, float(sc)))
        dark = name == "dark"
        # ── Match curax_design_preview.html exactly ──
        if dark:
            # Dark theme: .dark-app colors
            title_c   = "#2DD4BF"          # .dark-app .tab-title
            sub_c     = "#64748B"           # .dark-app .tab-sub
            bg_c      = "#0A1628"           # .dark-app .tab-content
            input_bg  = "#0A1628"           # .dark-app .form-input background
            input_fg  = "#E2E8F0"
            input_bdr = "#1E3A4A"
            popup_bg  = "#0E1C2E"
            popup_fg  = "#E2E8F0"
            popup_sel = "#0D4F49"
            label_c   = "#64748B"           # .dark-app .form-label
            save_bg   = "#0D9488"
        else:
            # Light theme: .tab-content / .form-input colors
            title_c   = "#0D6B61"           # --deep
            sub_c     = "#475569"           # --muted
            bg_c      = "#FFFFFF"           # .tab-content background: white
            input_bg  = "#FFFFFF"           # .form-input background
            input_fg  = "#0F172A"           # --text
            input_bdr = "#B2D8D4"           # --border
            popup_bg  = "#FFFFFF"
            popup_fg  = "#0F172A"
            popup_sel = "#E2F2EE"
            label_c   = "#475569"           # --muted
            save_bg   = "#0D9488"           # --teal

        try:
            self._title_label.setStyleSheet(
                f"font-size: {max(8, int(14 * s))}pt; font-weight: 800; color: {title_c}; "
                "font-family: 'Segoe UI'; letter-spacing: -0.3px; background: transparent;"
            )
        except Exception:
            pass

        # Update all form labels
        try:
            for lbl in self.findChildren(__import__('PyQt6.QtWidgets', fromlist=['QLabel']).QLabel if True else None.__class__):
                pass
        except Exception:
            pass

        try:
            from PyQt6.QtWidgets import QComboBox, QLabel
        except Exception:
            from PyQt5.QtWidgets import QComboBox, QLabel

        # Style all labels that are form labels
        lbl_pt = max(7, int(8 * s))
        for lbl in self.findChildren(QLabel):
            txt = lbl.text()
            if any(c in txt for c in ["💊","📦","📅","🕒","📥","⏰","📝"]):
                lbl.setStyleSheet(
                    f"font-size: {lbl_pt}pt; font-weight: 600; color: {label_c}; "
                    "background: transparent; margin-bottom: 2px;"
                )

        inp_pt = max(7, int(9 * s))
        input_style = (
            f"border: 1.5px solid {input_bdr}; border-radius: 8px; "
            f"background-color: {input_bg}; color: {input_fg}; "
            f"padding: 7px 10px; font-size: {inp_pt}pt;"
        )

        sel_color = "#0D6B61" if not dark else "#E2E8F0"
        for w in self._input_fields:
            try:
                if isinstance(w, QComboBox):
                    w.setStyleSheet(
                        f"QComboBox {{"
                        f"  border: 1.5px solid {input_bdr}; border-radius: 8px;"
                        f"  background-color: {input_bg}; color: {input_fg};"
                        f"  padding: 7px 10px; font-size: {inp_pt}pt; min-height: 28px;"
                        f"}}"
                        f"QComboBox QAbstractItemView {{"
                        f"  background-color: {popup_bg}; color: {popup_fg};"
                        f"  border: 1px solid {input_bdr};"
                        f"  selection-background-color: {popup_sel};"
                        f"  selection-color: {sel_color};"
                        f"  border-radius: 8px; padding: 4px; outline: none;"
                        f"}}"
                    )
                else:
                    w.setStyleSheet(input_style)
            except Exception:
                continue

        try:
            self.save_btn.setStyleSheet(
                f"QPushButton {{"
                f"  background-color: {save_bg}; color: #FFFFFF; font-weight: 700;"
                f"  font-size: {inp_pt}pt; padding: 9px 24px; border-radius: 8px; border: none;"
                f"}}"
                f"QPushButton:hover {{ background-color: #0F766E; }}"
            )
        except Exception:
            pass

    def _build_ui(self):
        outer = QVBoxLayout(self)

        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        scroll.setStyleSheet("QScrollArea { background: transparent; border: none; }")
        scroll.viewport().setStyleSheet("background: transparent;")
        try:
            scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        except AttributeError:
            scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
            scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)

        container = QWidget(scroll)
        try:
            container.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Minimum)
        except AttributeError:
            container.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Minimum)
        layout = QVBoxLayout(container)
        layout.setSpacing(12)

        self._title_label = QLabel("Add New Medicine")
        self._title_label.setStyleSheet(
            "font-size: 14pt; font-weight: 800; color: #0D6B61; "
            "font-family: 'Segoe UI'; letter-spacing: -0.3px; background: transparent;"
        )
        layout.addWidget(self._title_label)

        self._user_view_label = QLabel("You are in user view. Adding medicine is only available on the admin device.")
        self._user_view_label.setWordWrap(True)
        self._user_view_label.setStyleSheet("font-size: 12pt; color: #64748b;")
        self._user_view_label.setVisible(False)
        layout.addWidget(self._user_view_label)

        form_wrapper = QWidget(container)
        self._form_wrapper = form_wrapper
        form_wrapper.setMaximumWidth(560)
        form_layout = QVBoxLayout(form_wrapper)
        form_layout.setSpacing(12)
        form_layout.setContentsMargins(0, 0, 0, 0)

        def _row_two_col(left_label, left_widget, right_label, right_widget):
            row = QHBoxLayout()
            row.setSpacing(20)
            left_cell = QWidget(form_wrapper)
            left_lo = QVBoxLayout(left_cell)
            left_lo.setContentsMargins(0, 0, 0, 0)
            left_lo.setSpacing(4)
            left_lo.addWidget(left_label)
            left_lo.addWidget(left_widget)
            right_cell = QWidget(form_wrapper)
            right_lo = QVBoxLayout(right_cell)
            right_lo.setContentsMargins(0, 0, 0, 0)
            right_lo.setSpacing(4)
            right_lo.addWidget(right_label)
            right_lo.addWidget(right_widget)
            row.addWidget(left_cell, 1)
            row.addWidget(right_cell, 1)
            return row

        lbl_style = "font-size: 11pt;"

        lbl_name = QLabel("💊 Medicine Name:")
        lbl_name.setStyleSheet(lbl_style)
        self.med_name = QLineEdit()
        self.med_name.setPlaceholderText("e.g. Paracetamol")
        self._input_fields.append(self.med_name)
        lbl_qty = QLabel("📦 Quantity:")
        lbl_qty.setStyleSheet(lbl_style)
        self.med_qty = QSpinBox()
        self.med_qty.setRange(1, 1000)
        self.med_qty.setValue(30)
        self._input_fields.append(self.med_qty)
        form_layout.addLayout(_row_two_col(lbl_name, self.med_name, lbl_qty, self.med_qty))

        lbl_dose = QLabel("💊 Dose per Day:")
        lbl_dose.setStyleSheet(lbl_style)
        self.med_dose = QSpinBox()
        self.med_dose.setRange(1, 10)
        self.med_dose.setValue(1)
        self._input_fields.append(self.med_dose)
        lbl_expiry = QLabel("📅 Expiry Date:")
        lbl_expiry.setStyleSheet(lbl_style)
        self.med_expiry = QDateEdit()
        self.med_expiry.setDate(QDate.currentDate().addYears(1))
        self.med_expiry.setCalendarPopup(True)
        cal = self.med_expiry.calendarWidget()
        if cal is not None:
            cal.setMinimumSize(380, 320)
            cal.setGridVisible(True)
            cal.setVerticalHeaderFormat(cal.VerticalHeaderFormat.NoVerticalHeader)
        self._input_fields.append(self.med_expiry)
        form_layout.addLayout(_row_two_col(lbl_dose, self.med_dose, lbl_expiry, self.med_expiry))

        lbl_period = QLabel("🕒 Intake Period:")
        lbl_period.setStyleSheet(lbl_style)
        self.med_period = QComboBox()
        self.med_period.addItems(["Morning (6 AM - 10 AM)", "Afternoon (12 PM - 4 PM)", "Night (8 PM - 10 PM)"])
        self.med_period.setMinimumHeight(32)
        self._input_fields.append(self.med_period)
        lbl_box = QLabel("📥 Assign to Box:")
        lbl_box.setStyleSheet(lbl_style)
        self.med_box = QComboBox()
        self.med_box.addItems(["B1", "B2", "B3", "B4", "B5", "B6"])
        self._input_fields.append(self.med_box)
        form_layout.addLayout(_row_two_col(lbl_period, self.med_period, lbl_box, self.med_box))

        lbl_time = QLabel("⏰ Exact Time (Hour:Minute):")
        lbl_time.setStyleSheet(lbl_style)
        form_layout.addWidget(lbl_time)
        time_widget = QWidget(container)
        time_widget.setMaximumWidth(250)
        time_row = QHBoxLayout(time_widget)
        time_row.setContentsMargins(0, 0, 0, 0)
        time_row.setSpacing(10)
        self.med_hour = QComboBox()
        self.med_hour.addItems([f"{i:02d}" for i in range(0, 24)])
        self.med_hour.setCurrentText("08")
        self.med_hour.setMinimumWidth(64)
        self.med_hour.setMinimumHeight(32)
        self.med_minute = QComboBox()
        self.med_minute.addItems([f"{i:02d}" for i in range(0, 60, 5)])
        self.med_minute.setCurrentText("00")
        self.med_minute.setMinimumWidth(64)
        self.med_minute.setMinimumHeight(32)
        time_colon = QLabel(" : ")
        time_colon.setStyleSheet("font-size: 14pt; font-weight: bold;")
        time_row.addWidget(self.med_hour)
        time_row.addWidget(time_colon)
        time_row.addWidget(self.med_minute)
        time_row.addStretch()
        self._input_fields.extend([self.med_hour, self.med_minute])
        form_layout.addWidget(time_widget)

        lbl_instr = QLabel("📝 Instructions:")
        lbl_instr.setStyleSheet(lbl_style)
        form_layout.addWidget(lbl_instr)
        self.med_instructions = QTextEdit()
        self.med_instructions.setMaximumHeight(64)
        self.med_instructions.setPlaceholderText("Optional instructions")
        form_layout.addWidget(self.med_instructions)
        self._input_fields.append(self.med_instructions)

        self.save_btn = QPushButton("💾  Save Medicine")
        self.save_btn.clicked.connect(self._save_medicine)
        self.save_btn.setMinimumHeight(42)
        self.save_btn.setMaximumWidth(280)
        self.save_btn.setStyleSheet(
            "background-color: #0D9488; color: #FFFFFF; font-weight: 700; "
            "font-size: 11pt; padding: 9px 24px; border-radius: 10px; border: none;"
        )
        form_layout.addWidget(self.save_btn)
        form_layout.addSpacing(16)  # space below button so it’s not cut off when scrolled to bottom

        layout.addWidget(form_wrapper)

        scroll.setWidget(container)
        outer.addWidget(scroll)

    def showEvent(self, event):
        super().showEvent(event)
        is_user = getattr(self.controller, "is_user_view", lambda: False)()
        if hasattr(self, "_form_wrapper"):
            self._form_wrapper.setVisible(not is_user)
        if hasattr(self, "_user_view_label"):
            self._user_view_label.setVisible(is_user)

    def _on_save_done(self, success):
        """Called when background save completes; show result and clear form if this tab triggered the save."""
        if not getattr(self, "_save_pending", False):
            return
        self._save_pending = False
        try:
            self.save_btn.setEnabled(True)
            self.save_btn.setText("🔒 Save Medicine")
        except Exception:
            pass
        try:
            from PyQt6.QtWidgets import QMessageBox
        except ImportError:
            from PyQt5.QtWidgets import QMessageBox
        if success and self._pending_save_box_id and self._pending_save_medicine:
            self.controller.alert_scheduler.cancel_medicine_alerts_for_box(self._pending_save_box_id)
            self.controller.alert_scheduler.schedule_medicine_alert(self._pending_save_medicine, self._pending_save_box_id)
            self.med_name.clear()
            self.med_qty.setValue(30)
            self.med_dose.setValue(1)
            self.med_instructions.clear()
            self.controller._log("Medicine added.")
            self.controller.status_message.emit("Medicine added.")
            QMessageBox.information(self, "Saved", "Medicine added.")
        elif not success:
            msg = getattr(self.controller, "_last_save_error", "") or "Could not save to server. Check connection and try again."
            QMessageBox.warning(self, "Save failed", msg)
        self._pending_save_box_id = None
        self._pending_save_medicine = None

    def _save_medicine(self):
        if not self.controller.require_admin():
            return
        name = self.med_name.text().strip()
        if not name:
            try:
                from PyQt6.QtWidgets import QMessageBox
            except Exception:
                from PyQt5.QtWidgets import QMessageBox
            QMessageBox.warning(self, "Validation", "Medicine name is required.")
            return
        box_id = self.med_box.currentText()
        exact_time = f"{self.med_hour.currentText()}:{self.med_minute.currentText()}"
        period_map = {
            "Morning (6 AM - 10 AM)": "Morning",
            "Afternoon (12 PM - 4 PM)": "Afternoon",
            "Night (8 PM - 10 PM)": "Night",
        }
        medicine = {
            "name": name,
            "quantity": self.med_qty.value(),
            "dose_per_day": self.med_dose.value(),
            "expiry": self.med_expiry.date().toString("yyyy-MM-dd"),
            "exact_time": exact_time,
            "period": period_map.get(self.med_period.currentText(), "Morning"),
            "instructions": self.med_instructions.toPlainText().strip(),
        }
        self.controller.medicine_boxes[box_id] = medicine
        self.controller.medicine_updated.emit()
        self._save_pending = True
        self._pending_save_box_id = box_id
        self._pending_save_medicine = medicine
        try:
            self.save_btn.setEnabled(False)
            self.save_btn.setText("Saving…")
        except Exception:
            pass
        self.controller.save_data()
