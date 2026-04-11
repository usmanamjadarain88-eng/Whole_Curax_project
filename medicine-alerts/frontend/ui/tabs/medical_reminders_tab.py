try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
        QListWidget, QListWidgetItem, QGroupBox, QLineEdit, QTimeEdit,
        QComboBox, QTabWidget, QMessageBox, QDialog, QDialogButtonBox,
        QFormLayout, QScrollArea, QFrame, QDateEdit,
        QTableWidget, QTableWidgetItem, QCheckBox
    )
    from PyQt6.QtCore import Qt, QTime, QDate
    from PyQt6.QtGui import QFont
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
        QListWidget, QListWidgetItem, QGroupBox, QLineEdit, QTimeEdit,
        QComboBox, QTabWidget, QMessageBox, QDialog, QDialogButtonBox,
        QFormLayout, QScrollArea, QFrame, QDateEdit,
        QTableWidget, QTableWidgetItem, QCheckBox
    )
    from PyQt5.QtCore import Qt, QTime, QDate

from ui.styles import NEON_GREEN, TEXT_SECONDARY, CARD_STYLE, ACCENT_LIGHT, SECONDARY_LIGHT_DARK


class AddReminderDialog(QDialog):
    def __init__(self, reminder_type, parent=None):
        super().__init__(parent)
        mw = parent.window() if parent else None
        s = max(0.52, min(1.0, float(getattr(mw, "_content_scale", getattr(mw, "_topbar_scale", 1.0)) if mw else 1.0)))
        self.setWindowTitle("Add Reminder")
        self.reminder_type = reminder_type
        self._scale = s
        self.setMinimumWidth(max(280, int(380 * s)))
        layout = QFormLayout(self)

        # Title/first field: Doctor (appointments), Medicine (prescriptions), Test name (lab_tests), Title (custom)
        self.title_edit = QLineEdit()
        if reminder_type == "appointments":
            self.title_edit.setPlaceholderText("Doctor name")
            layout.addRow("Doctor name:", self.title_edit)
        elif reminder_type == "prescriptions":
            self.title_edit.setPlaceholderText("Medicine name")
            layout.addRow("Medicine:", self.title_edit)
        elif reminder_type == "lab_tests":
            self.title_edit.setPlaceholderText("Test name")
            layout.addRow("Test name:", self.title_edit)
        else:
            self.title_edit.setPlaceholderText("Title / Name")
            layout.addRow("Title:", self.title_edit)

        # Location / Pharmacy / Priority (custom)
        self.location_edit = QLineEdit()
        if reminder_type == "prescriptions":
            self.location_edit.setPlaceholderText("Pharmacy (optional)")
            layout.addRow("Pharmacy:", self.location_edit)
        elif reminder_type == "custom":
            self.location_edit.setPlaceholderText("e.g. Low, Medium, High")
            layout.addRow("Priority:", self.location_edit)
        else:
            self.location_edit.setPlaceholderText("Location / Clinic (optional)")
            layout.addRow("Location:", self.location_edit)

        self.date_edit = QDateEdit()
        try:
            self.date_edit.setDate(QDate.currentDate())
        except AttributeError:
            self.date_edit.setDate(QDate.currentDate())
        self.date_edit.setCalendarPopup(True)
        cal = self.date_edit.calendarWidget()
        if cal is not None:
            cal.setMinimumSize(380, 320)
            cal.setGridVisible(True)
            cal.setVerticalHeaderFormat(cal.VerticalHeaderFormat.NoVerticalHeader)
        layout.addRow("Date:", self.date_edit)

        # Time (hidden for prescriptions; they get Expiry instead)
        self.time_edit = QTimeEdit()
        self.time_edit.setTime(QTime.currentTime())
        self.expiry_date_edit = None
        if reminder_type == "prescriptions":
            self.expiry_date_edit = QDateEdit()
            try:
                self.expiry_date_edit.setDate(QDate.currentDate().addMonths(1))
            except Exception:
                self.expiry_date_edit.setDate(QDate.currentDate())
            self.expiry_date_edit.setCalendarPopup(True)
            exp_cal = self.expiry_date_edit.calendarWidget()
            if exp_cal is not None:
                exp_cal.setMinimumSize(max(260, int(380 * s)), max(220, int(320 * s)))
                exp_cal.setGridVisible(True)
                exp_cal.setVerticalHeaderFormat(exp_cal.VerticalHeaderFormat.NoVerticalHeader)
            layout.addRow("Expiry:", self.expiry_date_edit)
        else:
            layout.addRow("Time:", self.time_edit)

        # Description: Specialty (appointments), Doctor (prescriptions), Description (lab_tests, custom)
        self.desc_edit = QLineEdit()
        if reminder_type == "appointments":
            self.desc_edit.setPlaceholderText("Specialty / Notes (optional)")
            layout.addRow("Specialty / Notes:", self.desc_edit)
        elif reminder_type == "prescriptions":
            self.desc_edit.setPlaceholderText("Prescribing doctor (optional)")
            layout.addRow("Doctor:", self.desc_edit)
        else:
            self.desc_edit.setPlaceholderText("Description (optional)")
            layout.addRow("Description:", self.desc_edit)

        self.cb_24h = QCheckBox("24 hours before")
        self.cb_24h.setChecked(True)
        self.cb_2h = QCheckBox("2 hours before")
        self.cb_2h.setChecked(True)
        self.cb_alert = QCheckBox("Send mobile/email alert")
        self.cb_alert.setChecked(True)
        layout.addRow("", self.cb_24h)
        layout.addRow("", self.cb_2h)
        layout.addRow("", self.cb_alert)
        try:
            bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        except TypeError:
            bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        layout.addRow(bb)
        self.setFont(QFont("Segoe UI", max(8, int(10 * s))))

    def get_reminder(self):
        base = {
            "reminders": {
                "24h": self.cb_24h.isChecked(),
                "2h": self.cb_2h.isChecked(),
                "alert": self.cb_alert.isChecked(),
            },
        }
        if self.reminder_type == "appointments":
            base["doctor"] = self.title_edit.text().strip()
            base["title"] = base["doctor"]
            base["location"] = self.location_edit.text().strip()
            base["date"] = self.date_edit.date().toString("yyyy-MM-dd")
            base["time"] = self.time_edit.time().toString("HH:mm")
            base["specialty"] = self.desc_edit.text().strip()
            base["description"] = base["specialty"]
        elif self.reminder_type == "prescriptions":
            base["medicine"] = self.title_edit.text().strip()
            base["title"] = base["medicine"]
            base["pharmacy"] = self.location_edit.text().strip()
            base["location"] = base["pharmacy"]
            base["date"] = self.date_edit.date().toString("yyyy-MM-dd")
            base["expiry_date"] = self.expiry_date_edit.date().toString("yyyy-MM-dd") if self.expiry_date_edit else base["date"]
            base["time"] = "00:00"
            base["doctor"] = self.desc_edit.text().strip()
            base["description"] = base["doctor"]
        elif self.reminder_type == "lab_tests":
            base["test_name"] = self.title_edit.text().strip()
            base["title"] = base["test_name"]
            base["location"] = self.location_edit.text().strip()
            base["date"] = self.date_edit.date().toString("yyyy-MM-dd")
            base["time"] = self.time_edit.time().toString("HH:mm")
            base["description"] = self.desc_edit.text().strip()
        else:
            base["title"] = self.title_edit.text().strip()
            base["priority"] = self.location_edit.text().strip()
            base["location"] = base["priority"]
            base["date"] = self.date_edit.date().toString("yyyy-MM-dd")
            base["time"] = self.time_edit.time().toString("HH:mm")
            base["description"] = self.desc_edit.text().strip()
        return base


class MedicalRemindersTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._build_ui()
        self._refresh_all()
        try:
            self.controller.medicine_updated.connect(self._refresh_all)
        except Exception:
            pass
        self.apply_theme(getattr(controller, "appearance_theme", "light"))

    def showEvent(self, event):
        """Refresh reminder tables; in user view disable appointments/prescriptions/lab_tests add, keep custom add; hide delete/save/export."""
        try:
            super().showEvent(event)
            self._refresh_all()
            is_user = getattr(self.controller, "is_user_view", lambda: False)()
            if hasattr(self, "_add_reminder_btns"):
                for k in ("appointments", "prescriptions", "lab_tests"):
                    if k in self._add_reminder_btns:
                        self._add_reminder_btns[k].setEnabled(not is_user)
                if "custom" in self._add_reminder_btns:
                    self._add_reminder_btns["custom"].setEnabled(True)
            if hasattr(self, "all_delete_btn"):
                self.all_delete_btn.setVisible(not is_user)
            if hasattr(self, "all_save_btn"):
                self.all_save_btn.setVisible(not is_user)
            if hasattr(self, "_export_btn"):
                self._export_btn.setVisible(not is_user)
            if hasattr(self, "_import_btn"):
                self._import_btn.setVisible(not is_user)
            if hasattr(self, "_backup_btn"):
                self._backup_btn.setVisible(not is_user)
        except Exception:
            super().showEvent(event)

    def apply_theme(self, theme_name: str, content_scale: float = None):
        """Title and subtitle: light mode uses ACCENT_LIGHT and SECONDARY_LIGHT_DARK."""
        name = (theme_name or "light").lower()
        sc = content_scale if content_scale is not None else (getattr(self.window(), "_content_scale", 1.0) if self.window() else 1.0)
        s = max(0.5, min(1.0, float(sc)))
        title_color = ACCENT_LIGHT if name == "light" else NEON_GREEN
        secondary = SECONDARY_LIGHT_DARK if name == "light" else TEXT_SECONDARY
        try:
            self._title_label.setStyleSheet(
                f"font-size: {max(8, int(18 * s))}pt; font-weight: bold; color: {title_color};"
            )
            self._subtitle_label.setStyleSheet(f"color: {secondary}; font-size: {max(8, int(10 * s))}pt;")
        except Exception:
            pass
        try:
            btn_pt = max(7, int(9 * s))
            pad_v = max(6, int(8 * s))
            pad_h = max(10, int(14 * s))
            add_style = (
                f"background-color:#0D9488;color:#ffffff;font-weight:700;"
                f"padding:{pad_v}px {pad_h}px;border-radius:8px;border:1.5px solid #0F766E;font-size:{btn_pt}pt;"
            )
            for btn in getattr(self, "_add_reminder_btns", {}).values():
                btn.setStyleSheet(add_style)
        except Exception:
            pass
        try:
            from PyQt6.QtGui import QFont
        except Exception:
            from PyQt5.QtGui import QFont
        try:
            if hasattr(self, "tabs"):
                self.tabs.setFont(QFont("Segoe UI", max(6, int(10 * s))))
                try:
                    tb = self.tabs.tabBar()
                    if tb:
                        tb.setStyleSheet(f"font-size: {max(6, int(9 * s))}pt; font-weight: 600;")
                except Exception:
                    pass
        except Exception:
            pass
        try:
            btn_pt = max(7, int(9 * s))
            pad_v = max(6, int(8 * s))
            pad_h = max(12, int(16 * s))
            base_btn = f"font-weight:700;padding:{pad_v}px {pad_h}px;border-radius:8px;font-size:{btn_pt}pt;"
            if hasattr(self, "all_delete_btn"):
                self.all_delete_btn.setStyleSheet(
                    f"background-color:#dc2626;color:#ffffff;border:none;{base_btn}"
                )
            if hasattr(self, "all_save_btn"):
                self.all_save_btn.setStyleSheet(
                    f"background-color:#0D9488;color:#ffffff;border:none;{base_btn}"
                )
            if hasattr(self, "_export_btn"):
                self._export_btn.setStyleSheet(
                    f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}"
                )
            if hasattr(self, "_import_btn"):
                self._import_btn.setStyleSheet(
                    f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}"
                )
            if hasattr(self, "_backup_btn"):
                self._backup_btn.setStyleSheet(
                    f"background-color:#6b7280;color:#ffffff;border:1.5px solid #4b5563;{base_btn}"
                )
        except Exception:
            pass

    def _safe_reminder_item(self, raw):
        if isinstance(raw, dict):
            return raw
        if isinstance(raw, str):
            txt = raw.strip()
            if not txt:
                return {}
            try:
                obj = json.loads(txt)
                if isinstance(obj, dict):
                    return obj
            except Exception:
                pass
            return {"title": txt, "description": txt}
        return {}

    def _safe_reminders_flags(self, raw):
        if isinstance(raw, dict):
            return raw
        if isinstance(raw, str):
            txt = raw.strip()
            if not txt:
                return {}
            try:
                obj = json.loads(txt)
                if isinstance(obj, dict):
                    return obj
            except Exception:
                pass
            low = txt.lower()
            return {"24h": "24" in low, "2h": "2h" in low or "2 hour" in low}
        return {}

    def _build_ui(self):
        outer = QVBoxLayout(self)
        self._title_label = QLabel("Medical Reminders & Appointments")
        self._title_label.setStyleSheet(f"font-size: 18pt; font-weight: bold; color: {NEON_GREEN};")
        outer.addWidget(self._title_label)
        self._subtitle_label = QLabel("Manage appointments, prescriptions, lab tests, and custom reminders.")
        self._subtitle_label.setStyleSheet(f"color: {TEXT_SECONDARY};")
        outer.addWidget(self._subtitle_label)

        quick_container = QWidget(self)
        quick_row = QHBoxLayout(quick_container)
        self._add_reminder_btns = {}
        for key, label in [
            ("appointments", "📅 Add Appointment"),
            ("prescriptions", "💊 Add Prescription"),
            ("lab_tests", "🧪 Add Lab Test"),
            ("custom", "🔔 Custom Reminder"),
        ]:
            btn = QPushButton(label)
            btn.setObjectName(f"add_reminder_{key}")
            btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 14px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
            btn.clicked.connect(lambda checked, k=key: self._add_reminder(k))
            quick_row.addWidget(btn)
            self._add_reminder_btns[key] = btn
        self._add_buttons_container = quick_container
        outer.addWidget(quick_container)

        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        container = QWidget(scroll)
        layout = QVBoxLayout(container)

        self.tabs = QTabWidget(container)

        all_page = QWidget(self.tabs)
        all_layout = QVBoxLayout(all_page)
        self.all_table = QTableWidget()
        self.all_table.setColumnCount(7)
        self.all_table.setHorizontalHeaderLabels(
            ["Type", "Title / Doctor", "Date", "Time", "Location / Details", "Reminders", "Status"]
        )
        # Set column widths so Location/Details isn't cut off
        try:
            from PyQt6.QtWidgets import QHeaderView as HV
            self.all_table.horizontalHeader().setSectionResizeMode(0, HV.ResizeMode.ResizeToContents)
            self.all_table.horizontalHeader().setSectionResizeMode(1, HV.ResizeMode.Stretch)
            self.all_table.horizontalHeader().setSectionResizeMode(2, HV.ResizeMode.ResizeToContents)
            self.all_table.horizontalHeader().setSectionResizeMode(3, HV.ResizeMode.ResizeToContents)
            self.all_table.horizontalHeader().setSectionResizeMode(4, HV.ResizeMode.Stretch)
            self.all_table.horizontalHeader().setSectionResizeMode(5, HV.ResizeMode.ResizeToContents)
            self.all_table.horizontalHeader().setSectionResizeMode(6, HV.ResizeMode.ResizeToContents)
        except Exception:
            try:
                from PyQt5.QtWidgets import QHeaderView as HV
                self.all_table.horizontalHeader().setSectionResizeMode(1, HV.Stretch)
                self.all_table.horizontalHeader().setSectionResizeMode(4, HV.Stretch)
            except Exception:
                pass
        try:
            from PyQt6.QtWidgets import QAbstractItemView, QHeaderView
            self.all_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
            self.all_table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
            self.all_table.horizontalHeader().setStretchLastSection(True)
        except Exception:
            pass
        all_layout.addWidget(self.all_table)
        all_btn_row = QHBoxLayout()
        self.all_delete_btn = QPushButton("🗑️ Delete Selected Reminder (Admin)")
        self.all_delete_btn.setStyleSheet(
            "background-color: #dc2626; color: #ffffff; font-weight: 700; "
            "padding: 8px 16px; border-radius: 8px; border: none;"
        )
        self.all_delete_btn.clicked.connect(self._delete_from_all)
        self.all_save_btn = QPushButton("💾 Save All Reminders")
        self.all_save_btn.setStyleSheet(
            "background-color: #0D9488; color: #ffffff; font-weight: 700; "
            "padding: 8px 16px; border-radius: 8px; border: none;"
        )
        self.all_save_btn.clicked.connect(self._save)
        all_btn_row.addWidget(self.all_delete_btn)
        all_btn_row.addWidget(self.all_save_btn)
        all_layout.addLayout(all_btn_row)
        self.tabs.addTab(all_page, "📋 All Reminders")

        self._type_tables = {}
        for key, label in [
            ("appointments", "📅 Appointments"),
            ("prescriptions", "💊 Prescriptions"),
            ("lab_tests", "🔬 Lab Tests"),
            ("custom", "📌 Custom"),
        ]:
            page = QWidget(self.tabs)
            page_layout = QVBoxLayout(page)

            table = QTableWidget()
            if key == "appointments":
                headers = ["Doctor", "Specialty", "Date", "Time", "Location", "24h / 2h", "Status"]
            elif key == "prescriptions":
                headers = ["Medicine", "Doctor", "Expiry", "Pharmacy", "7d / 3d / 1d", "Status"]
            elif key == "lab_tests":
                headers = ["Test Name", "Date", "Time", "Location", "Status"]
            else:  # custom
                headers = ["Title", "Date", "Time", "Priority", "Description", "Status"]
            table.setColumnCount(len(headers))
            table.setHorizontalHeaderLabels(headers)
            try:
                from PyQt6.QtWidgets import QAbstractItemView, QHeaderView
                table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
                table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
                table.horizontalHeader().setStretchLastSection(True)
            except Exception:
                pass
            page_layout.addWidget(table)

            self._type_tables[key] = table
            self.tabs.addTab(page, label)

        export_page = QWidget(self.tabs)
        export_layout = QVBoxLayout(export_page)
        info = QLabel(
            "Export, import, and backup your medical reminders and related settings.\n\n"
            "• Export: save reminders to JSON.\n"
            "• Import: load reminders from a JSON file.\n"
            "• Backup All: save reminders + alert settings + email/SMS + medicine data."
        )
        info.setWordWrap(True)
        info.setStyleSheet(f"color: {TEXT_SECONDARY};")
        export_layout.addWidget(info)

        btn_row_exp = QHBoxLayout()
        self._export_btn = QPushButton("⬇️ Export Reminders")
        self._export_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self._export_btn.clicked.connect(self._export)
        self._import_btn = QPushButton("⬆️ Import Reminders")
        self._import_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self._import_btn.clicked.connect(self._import)
        self._backup_btn = QPushButton("🗄️ Backup All Data")
        self._backup_btn.setStyleSheet("background-color:#6b7280;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #4b5563;font-size:9pt;")
        self._backup_btn.clicked.connect(self._backup_all)
        btn_row_exp.addWidget(self._export_btn)
        btn_row_exp.addWidget(self._import_btn)
        btn_row_exp.addWidget(self._backup_btn)
        export_layout.addLayout(btn_row_exp)

        self._status_label = QLabel("")
        self._status_label.setStyleSheet(f"color: {TEXT_SECONDARY};")
        export_layout.addWidget(self._status_label)

        self.tabs.addTab(export_page, "⬇️ Export / Import")
        layout.addWidget(self.tabs)

        scroll.setWidget(container)
        outer.addWidget(scroll)

    def _add_reminder(self, key):
        is_user = getattr(self.controller, "is_user_view", lambda: False)()
        if is_user and key != "custom":
            return  # User view: only custom reminder add allowed
        if key != "custom" and self.main_window:
            if not self.main_window.verify_admin_for_action("Add Medical Reminder"):
                return

        d = AddReminderDialog(key, self)
        try:
            Accepted = QDialog.DialogCode.Accepted
        except AttributeError:
            Accepted = QDialog.Accepted
        if d.exec() == Accepted:
            r = d.get_reminder()
            if not r.get("title"):
                req = {"appointments": "Doctor name", "prescriptions": "Medicine", "lab_tests": "Test name"}.get(key, "Title")
                QMessageBox.warning(self, "Validation", f"{req} is required.")
                return
            self.controller.medical_reminders[key].append(r)
            self.controller.save_medical_reminders()
            self._refresh_all()
            rem = r.get("reminders", {})
            parts = []
            if rem.get("24h"):
                parts.append("24h before")
            if rem.get("2h"):
                parts.append("2h before")
            if parts:
                try:
                    self.controller.status_message.emit("Reminder saved.")
                except Exception:
                    pass
            try:
                self.controller.status_message.emit("Reminder added.")
            except Exception:
                pass
            QMessageBox.information(self, "Saved", "Reminder added.")

    def _delete_selected(self, key):
        return

    def _delete_from_all(self):
        """Delete from All Reminders view (admin-protected)."""
        if self.main_window and not self.main_window.verify_admin_for_action("Delete Medical Reminder"):
            return
        row = self.all_table.currentRow()
        if row < 0:
            QMessageBox.warning(self, "Delete", "Select a reminder row first.")
            return
        index = 0
        for key in ["appointments", "prescriptions", "lab_tests", "custom"]:
            lst = self.controller.medical_reminders.get(key, [])
            if not isinstance(lst, list):
                lst = []
                self.controller.medical_reminders[key] = lst
            if row < index + len(lst):
                inner_index = row - index
                if 0 <= inner_index < len(lst):
                    lst.pop(inner_index)
                self.controller.save_medical_reminders()
                self._refresh_all()
                self.controller.status_message.emit("Reminder removed.")
                return
            index += len(lst)

    def _export(self):
        if self.main_window and not self.main_window.verify_admin_for_action("Export Medical Reminders"):
            return
        import json
        import os
        try:
            from PyQt6.QtWidgets import QFileDialog
        except ImportError:
            from PyQt5.QtWidgets import QFileDialog
        path, _ = QFileDialog.getSaveFileName(self, "Export Reminders", "", "JSON (*.json)")
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(self.controller.medical_reminders, f, indent=2)
            msg = f"Exported to {os.path.basename(path)}"
            if self._status_label:
                self._status_label.setText(f"✅ {msg}")
            QMessageBox.information(self, "Export", msg)
        except Exception as e:
            if self._status_label:
                self._status_label.setText(f"❌ Export failed: {e}")
            QMessageBox.warning(self, "Export Failed", str(e))

    def _import(self):
        if self.main_window and not self.main_window.verify_admin_for_action("Import Medical Reminders"):
            return
        import json
        import os
        try:
            from PyQt6.QtWidgets import QFileDialog
        except ImportError:
            from PyQt5.QtWidgets import QFileDialog
        path, _ = QFileDialog.getOpenFileName(self, "Import Reminders", "", "JSON (*.json)")
        if not path:
            return
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict):
                raise ValueError("Invalid file format")
            self.controller.medical_reminders = data
            self._refresh_all()
            if self._status_label:
                self._status_label.setText(f"✅ Reminders imported from {os.path.basename(path)}")
            QMessageBox.information(self, "Import", f"Imported from {os.path.basename(path)}")
        except Exception as e:
            if self._status_label:
                self._status_label.setText(f"❌ Import failed: {e}")
            QMessageBox.warning(self, "Import Failed", str(e))

    def _backup_all(self):
        if self.main_window and not self.main_window.verify_admin_for_action("Backup All Data"):
            return
        import json
        import os
        try:
            from PyQt6.QtWidgets import QFileDialog
        except ImportError:
            from PyQt5.QtWidgets import QFileDialog
        path, _ = QFileDialog.getSaveFileName(self, "Backup All Data", "", "JSON (*.json)")
        if not path:
            return
        try:
            backup = {
                "medical_reminders": self.controller.medical_reminders,
                "alert_settings": self.controller.alert_settings,
                "gmail_config": getattr(self.controller, "gmail_config", {}),
                "sms_config": getattr(self.controller, "sms_config", {}),
                "medicine_boxes": self.controller.medicine_boxes,
                "dose_log": self.controller.dose_log,
            }
            with open(path, "w", encoding="utf-8") as f:
                json.dump(backup, f, indent=2)
            msg = f"Backup saved to {os.path.basename(path)}"
            if self._status_label:
                self._status_label.setText(f"✅ {msg}")
            QMessageBox.information(self, "Backup", msg)
        except Exception as e:
            if self._status_label:
                self._status_label.setText(f"❌ Backup failed: {e}")
            QMessageBox.warning(self, "Backup Failed", str(e))

    def _refresh_all(self):
        """Refresh per-type tables and combined 'All Reminders' table."""
        for key, table in self._type_tables.items():
            data = self.controller.medical_reminders.get(key, [])
            if not isinstance(data, list):
                data = []
            table.setRowCount(len(data))
            for row, raw in enumerate(data):
                r = self._safe_reminder_item(raw)
                if key == "appointments":
                    reminders = self._safe_reminders_flags(r.get("reminders", {}))
                    rem_txt = ", ".join(
                        part for part, flag in [("24h", reminders.get("24h")), ("2h", reminders.get("2h"))]
                        if flag
                    ) or "-"
                    values = [
                        r.get("doctor") or r.get("title", ""),
                        r.get("specialty") or r.get("description", ""),
                        r.get("date", ""),
                        r.get("time", ""),
                        r.get("location", ""),
                        rem_txt,
                        r.get("status", "scheduled"),
                    ]
                elif key == "prescriptions":
                    reminders = self._safe_reminders_flags(r.get("reminders", {}))
                    rem_txt = ", ".join(
                        part for part, flag in [("7d", reminders.get("7d")), ("3d", reminders.get("3d")), ("1d", reminders.get("1d"))]
                        if flag
                    ) or ", ".join(
                        part for part, flag in [("24h", reminders.get("24h")), ("2h", reminders.get("2h"))]
                        if flag
                    ) or "-"
                    values = [
                        r.get("medicine") or r.get("title", ""),
                        r.get("doctor") or r.get("description", ""),
                        r.get("expiry_date") or r.get("date", ""),
                        r.get("pharmacy") or r.get("location", ""),
                        rem_txt,
                        r.get("status", "active"),
                    ]
                elif key == "lab_tests":
                    values = [
                        r.get("test_name") or r.get("title", ""),
                        r.get("date", ""),
                        r.get("time", ""),
                        r.get("location", ""),
                        r.get("status", "scheduled"),
                    ]
                else:  # custom
                    reminders = self._safe_reminders_flags(r.get("reminders", {}))
                    rem_txt = ", ".join(
                        part for part, flag in [("24h", reminders.get("24h")), ("2h", reminders.get("2h"))]
                        if flag
                    ) or "-"
                    values = [
                        r.get("title", ""),
                        r.get("date", ""),
                        r.get("time", ""),
                        r.get("priority") or r.get("location", ""),
                        r.get("description", ""),
                        r.get("status", "active"),
                    ]
                for col, val in enumerate(values):
                    table.setItem(row, col, QTableWidgetItem(str(val)))

        all_rows = []
        for key in ["appointments", "prescriptions", "lab_tests", "custom"]:
            data = self.controller.medical_reminders.get(key, [])
            if not isinstance(data, list):
                continue
            for raw in data:
                r = self._safe_reminder_item(raw)
                if key == "appointments":
                    reminders = self._safe_reminders_flags(r.get("reminders", {}))
                    rem_txt = ", ".join(
                        part for part, flag in [("24h", reminders.get("24h")), ("2h", reminders.get("2h"))]
                        if flag
                    ) or "-"
                    title_or_doc = r.get("doctor") or r.get("title", "-")
                    row = [
                        "Appointment",
                        title_or_doc,
                        r.get("date", ""),
                        r.get("time", ""),
                        r.get("location", ""),
                        rem_txt,
                        r.get("status", "scheduled"),
                    ]
                elif key == "prescriptions":
                    reminders = self._safe_reminders_flags(r.get("reminders", {}))
                    rem_txt = ", ".join(
                        part for part, flag in [("7d", reminders.get("7d")), ("3d", reminders.get("3d")), ("1d", reminders.get("1d"))]
                        if flag
                    ) or ", ".join(
                        part for part, flag in [("24h", reminders.get("24h")), ("2h", reminders.get("2h"))]
                        if flag
                    ) or "-"
                    med = r.get("medicine") or r.get("title", "")
                    doc = r.get("doctor") or r.get("description", "")
                    pharm = r.get("pharmacy") or r.get("location", "")
                    loc_detail = f"By: {doc} @ {pharm}" if (doc or pharm) else (r.get("location") or r.get("description") or "-")
                    row = [
                        "Prescription",
                        med,
                        r.get("expiry_date") or r.get("date", ""),
                        r.get("time", ""),
                        loc_detail,
                        rem_txt,
                        r.get("status", "active"),
                    ]
                elif key == "lab_tests":
                    row = [
                        "Lab Test",
                        r.get("test_name") or r.get("title", ""),
                        r.get("date", ""),
                        r.get("time", ""),
                        r.get("location", ""),
                        "-",
                        r.get("status", "scheduled"),
                    ]
                else:
                    reminders = self._safe_reminders_flags(r.get("reminders", {}))
                    rem_txt = ", ".join(
                        part for part, flag in [("24h", reminders.get("24h")), ("2h", reminders.get("2h"))]
                        if flag
                    ) or "-"
                    pri = r.get("priority") or r.get("location", "")
                    row = [
                        "Custom",
                        r.get("title", ""),
                        r.get("date", ""),
                        r.get("time", ""),
                        f"Priority: {pri}" if pri else (r.get("description", "")[:60] or "-"),
                        rem_txt,
                        r.get("status", "active"),
                    ]
                all_rows.append(row)

        self.all_table.setRowCount(len(all_rows))
        for row_idx, row_vals in enumerate(all_rows):
            for col, val in enumerate(row_vals):
                self.all_table.setItem(row_idx, col, QTableWidgetItem(str(val)))

    def _save(self):
        if self.main_window and not self.main_window.verify_admin_for_action("Save Medical Reminders"):
            return
        self.controller.save_medical_reminders()
        self.controller.status_message.emit("Reminders saved.")
        QMessageBox.information(self, "Saved", "Reminders saved.")
