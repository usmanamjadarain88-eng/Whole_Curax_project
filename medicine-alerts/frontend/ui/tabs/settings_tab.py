try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTabWidget,
        QGroupBox, QLineEdit, QPushButton, QCheckBox, QMessageBox,
        QScrollArea, QFrame, QDialog, QFormLayout, QRadioButton, QSizePolicy
    )
    from PyQt6.QtCore import Qt, QThread, pyqtSignal
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTabWidget,
        QGroupBox, QLineEdit, QPushButton, QCheckBox, QMessageBox,
        QScrollArea, QFrame, QDialog, QFormLayout, QRadioButton, QSizePolicy
    )
    from PyQt5.QtCore import Qt, QThread, pyqtSignal

import uuid
from ui.styles import (
    NEON_GREEN, TEXT_SECONDARY, TEXT_SECONDARY_LIGHT,
    ACCENT_LIGHT, SECONDARY_LIGHT_DARK, RADIUS, BORDER_LIGHT, BORDER,
)


class _DeleteAdminWorker(QThread):
    """Runs delete_admin_from_backend in background so UI does not freeze."""
    done = pyqtSignal(object)  # "deleted" | "not_found" | False

    def __init__(self, controller, access_code):
        super().__init__()
        self._controller = controller
        self._access_code = access_code

    def run(self):
        result = False
        if getattr(self._controller, "delete_admin_from_backend", None):
            try:
                result = self._controller.delete_admin_from_backend(self._access_code)
            except Exception:
                pass
        self.done.emit(result)


class SettingsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None, admin_workstation=False):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._admin_workstation = bool(admin_workstation)
        self._build_ui()
        if self._admin_workstation:
            self._apply_admin_workstation_trim()
        self._load()
        try:
            self.controller.medicine_updated.connect(self._on_controller_data_updated)
        except Exception:
            pass
        theme = getattr(self.controller, "appearance_theme", "light")
        self.apply_theme(theme)

    def _title_color(self):
        theme = getattr(self.controller, "appearance_theme", "light")
        return ACCENT_LIGHT if (theme or "light").lower() == "light" else NEON_GREEN

    def _secondary_color(self):
        theme = getattr(self.controller, "appearance_theme", "light")
        return SECONDARY_LIGHT_DARK if (theme or "light").lower() == "light" else TEXT_SECONDARY

    def _build_ui(self):
        layout = QVBoxLayout(self)
        self._title_label = QLabel("Settings")
        self._title_label.setStyleSheet(
            f"font-size: 18pt; font-weight: bold; color: {self._title_color()};"
        )
        layout.addWidget(self._title_label)

        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        container = QWidget(scroll)
        self._settings_container = container
        c_layout = QVBoxLayout(container)
        c_layout.setContentsMargins(0, 0, 0, 0)

        tabs = QTabWidget(container)
        self._settings_tabs = tabs

        system = QWidget(tabs)
        sys_layout = QVBoxLayout(system)
        self._sys_layout = sys_layout
        sys_layout.setSpacing(20)
        sys_layout.setContentsMargins(16, 16, 16, 20)
        self._sys_desc = QLabel("System configuration and device info.")
        self._sys_desc.setStyleSheet(f"color: {self._secondary_color()}; font-size: 10pt;")
        sys_layout.addWidget(self._sys_desc)
        sys_layout.addSpacing(8)

        self.email_group = QGroupBox("📧 Email Alerts (Gmail)")
        email_layout = QVBoxLayout(self.email_group)
        email_layout.setSpacing(10)
        email_form = QFormLayout()
        email_form.setSpacing(8)
        self.gmail_sender = QLineEdit()
        self.gmail_sender.setPlaceholderText("you@gmail.com")
        email_form.addRow("Sender email:", self.gmail_sender)
        self.gmail_password = QLineEdit()
        try:
            self.gmail_password.setEchoMode(QLineEdit.EchoMode.Password)
        except AttributeError:
            self.gmail_password.setEchoMode(QLineEdit.Password)
        self.gmail_password.setPlaceholderText("Gmail app password")
        email_form.addRow("App password:", self.gmail_password)
        self.gmail_recipients = QLineEdit()
        self.gmail_recipients.setPlaceholderText("Optional; comma-separated")
        email_form.addRow("Recipients:", self.gmail_recipients)
        email_layout.addLayout(email_form)
        self._hint_email = QLabel("Uses smtp.gmail.com:465. Save to apply.")
        self._hint_email.setStyleSheet(f"color: {self._secondary_color()}; font-size: 9pt;")
        email_layout.addWidget(self._hint_email)
        email_btn_row = QHBoxLayout()
        self.gmail_test_btn = QPushButton("📤 Send Test Email")
        self.gmail_test_btn.setStyleSheet("background-color:#16a34a;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #15803d;font-size:9pt;")
        self.gmail_test_btn.clicked.connect(self._test_gmail)
        self.gmail_save_btn = QPushButton("💾 Save Gmail Settings")
        self.gmail_save_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self.gmail_save_btn.clicked.connect(self._save_gmail_settings)
        email_btn_row.addWidget(self.gmail_test_btn)
        email_btn_row.addWidget(self.gmail_save_btn)
        email_btn_row.addStretch()
        email_layout.addLayout(email_btn_row)
        sys_layout.addWidget(self.email_group)

        self.appearance_group = QGroupBox("🎨 Appearance / Theme")
        appearance_layout = QVBoxLayout(self.appearance_group)
        appearance_layout.setSpacing(10)
        self.appearance_hint = QLabel("Choose theme below; click Save to keep for next time. (Quick switch is in the top bar.)")
        self.appearance_hint.setWordWrap(True)
        self.appearance_hint.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 9pt;")
        appearance_layout.addWidget(self.appearance_hint)

        self.theme_label = QLabel("Interface Theme:")
        self.theme_label.setStyleSheet(f"font-size: 11pt; font-weight: bold; color: {NEON_GREEN};")
        appearance_layout.addWidget(self.theme_label)
        theme_column = QVBoxLayout()
        self.theme_dark_radio = QRadioButton("Dark Theme")
        self.theme_light_radio = QRadioButton("Light Theme (default)")
        self.theme_dark_radio.setStyleSheet("font-size: 10pt; font-weight: bold;")
        self.theme_light_radio.setStyleSheet("font-size: 10pt;")
        theme_column.addWidget(self.theme_dark_radio)
        theme_column.addSpacing(6)
        theme_column.addWidget(self.theme_light_radio)
        appearance_layout.addLayout(theme_column)

        def _on_theme_radio_changed():
            if not self.main_window:
                return
            theme = "light" if self.theme_light_radio.isChecked() else "dark"
            self.controller.appearance_theme = theme
            if hasattr(self.main_window, "apply_theme"):
                self.main_window.apply_theme(theme)

        self.theme_dark_radio.toggled.connect(_on_theme_radio_changed)
        self.theme_light_radio.toggled.connect(_on_theme_radio_changed)

        theme_save_row = QHBoxLayout()
        self.theme_save_btn = QPushButton("💾 Save Appearance")
        self.theme_save_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self.theme_save_btn.clicked.connect(self._save_appearance_settings)
        theme_save_row.addWidget(self.theme_save_btn)
        theme_save_row.addStretch()
        appearance_layout.addLayout(theme_save_row)

        sys_layout.addWidget(self.appearance_group)

        self.dnd_group = QGroupBox("⏰ Do Not Disturb")
        dnd_layout = QVBoxLayout(self.dnd_group)
        dnd_layout.setSpacing(10)
        self.dnd_enabled = QCheckBox("Enable do-not-disturb window")
        dnd_layout.addWidget(self.dnd_enabled)
        dnd_form = QFormLayout()
        dnd_form.setSpacing(8)
        self.dnd_start = QLineEdit()
        self.dnd_start.setPlaceholderText("22:00")
        dnd_form.addRow("Start (HH:MM):", self.dnd_start)
        self.dnd_end = QLineEdit()
        self.dnd_end.setPlaceholderText("07:00")
        dnd_form.addRow("End (HH:MM):", self.dnd_end)
        dnd_layout.addLayout(dnd_form)
        self._hint_dnd = QLabel("Non-critical alerts muted in this window.")
        self._hint_dnd.setStyleSheet(f"color: {self._secondary_color()}; font-size: 9pt;")
        dnd_layout.addWidget(self._hint_dnd)
        sys_layout.addWidget(self.dnd_group)

        self.save_sys_btn = QPushButton("💾 Save System Settings")
        self.save_sys_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        def _save_sys():
            if self.main_window and not self.main_window.verify_admin_for_action("Save System Preferences"):
                return
            self._save_system_settings()
        self.save_sys_btn.clicked.connect(_save_sys)
        sys_layout.addWidget(self.save_sys_btn)
        tabs.addTab(system, "System Settings")

        account = QWidget(tabs)
        acc_layout = QVBoxLayout(account)

        self.acc_title = QLabel("🔑 Password Management")
        self.acc_title.setStyleSheet(f"font-size: 14pt; font-weight: bold; color: {NEON_GREEN};")
        acc_layout.addWidget(self.acc_title)

        self.acc_rules = QLabel(
            "Two different secrets:\n\n"
            "• Desktop unlock PIN — opens this app on this computer only (not synced; not your phone PIN).\n"
            "• Admin account password — required for sensitive actions in Settings after you unlock.\n"
            "• Device password (below) — your medicine box (ESP32) PIN when you use USB/Bluetooth.\n\n"
            "Use strong digits and keep them confidential."
        )
        self.acc_rules.setWordWrap(True)
        self.acc_rules.setStyleSheet(f"color: {TEXT_SECONDARY};")
        acc_layout.addWidget(self.acc_rules)

        self.desktop_pin_group = QGroupBox("🖥️ Desktop unlock PIN (this PC only)")
        self.desktop_pin_group.setStyleSheet(f"QGroupBox {{ font-weight: bold; color: {self._title_color()}; }}")
        dpg = QVBoxLayout(self.desktop_pin_group)
        dpg.setSpacing(8)
        self._desktop_pin_hint = QLabel(
            "If you set a PIN here, you must enter it every time you open CuraX on this computer before the main window appears.\n"
            "It is stored only in your local desktop data file — not on your phone and not sent to the server."
        )
        self._desktop_pin_hint.setWordWrap(True)
        self._desktop_pin_hint.setStyleSheet(f"color: {self._secondary_color()}; font-size: 9pt;")
        dpg.addWidget(self._desktop_pin_hint)
        row_dp = QHBoxLayout()
        self.set_desktop_pin_btn = QPushButton("Set / change desktop PIN")
        self.set_desktop_pin_btn.setStyleSheet(
            "background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 14px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;"
        )
        self.set_desktop_pin_btn.clicked.connect(self._on_set_desktop_unlock_pin)
        row_dp.addWidget(self.set_desktop_pin_btn)
        self.clear_desktop_pin_btn = QPushButton("Remove desktop PIN")
        self.clear_desktop_pin_btn.setStyleSheet(
            "background-color:#64748b;color:#ffffff;font-weight:700;padding:8px 14px;border-radius:8px;border:1.5px solid #475569;font-size:9pt;"
        )
        self.clear_desktop_pin_btn.clicked.connect(self._on_clear_desktop_unlock_pin)
        row_dp.addWidget(self.clear_desktop_pin_btn)
        row_dp.addStretch()
        dpg.addLayout(row_dp)
        acc_layout.addWidget(self.desktop_pin_group)

        self.change_pwd_btn = QPushButton("🔐 Change Device Password")
        self.change_pwd_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self.change_pwd_btn.clicked.connect(self._change_password)
        pwd_btn_row = QHBoxLayout()
        pwd_btn_row.addWidget(self.change_pwd_btn)
        pwd_btn_row.addStretch()
        acc_layout.addLayout(pwd_btn_row)

        acc_layout.addStretch()
        tabs.addTab(account, "Account Settings")

        admin = QWidget(tabs)
        admin_layout = QVBoxLayout(admin)
        admin_layout.setContentsMargins(0, 0, 0, 0)
        admin_layout.setSpacing(8)
        try:
            admin_layout.setAlignment(Qt.AlignmentFlag.AlignTop)
        except AttributeError:
            admin_layout.setAlignment(Qt.AlignTop)

        admin_group = QWidget(admin)
        g_admin_layout = QVBoxLayout(admin_group)
        g_admin_layout.setContentsMargins(0, 0, 0, 0)
        g_admin_layout.setSpacing(8)

        self.admin_status_label = QLabel()
        self.admin_status_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 9pt;")
        g_admin_layout.addWidget(self.admin_status_label)

        admin_label_min_w = 200  # same width for all labels so inputs align
        admin_input_max_w = 440  # enough for long emails to be visible
        try:
            fixed_h = QSizePolicy.Policy.Fixed
        except AttributeError:
            fixed_h = QSizePolicy.Fixed

        def _admin_label(lbl):
            lbl.setMinimumWidth(admin_label_min_w)

        def _admin_input(w):
            w.setMinimumWidth(280)
            w.setMaximumWidth(admin_input_max_w)
            w.setSizePolicy(fixed_h, w.sizePolicy().verticalPolicy())

        name_row = QHBoxLayout()
        name_lbl = QLabel("Name:")
        _admin_label(name_lbl)
        name_row.addWidget(name_lbl)
        self.admin_name = QLineEdit()
        self.admin_name.setPlaceholderText("Admin name")
        _admin_input(self.admin_name)
        name_row.addWidget(self.admin_name)
        name_row.addStretch(1)
        g_admin_layout.addLayout(name_row)

        id_row = QHBoxLayout()
        id_lbl = QLabel("Admin ID (optional):")
        _admin_label(id_lbl)
        id_row.addWidget(id_lbl)
        self.admin_id = QLineEdit()
        self.admin_id.setPlaceholderText("CNIC / Staff ID")
        _admin_input(self.admin_id)
        id_row.addWidget(self.admin_id)
        id_row.addStretch(1)
        g_admin_layout.addLayout(id_row)

        email_row = QHBoxLayout()
        email_lbl = QLabel("Email:")
        _admin_label(email_lbl)
        email_row.addWidget(email_lbl)
        self.admin_email = QLineEdit()
        self.admin_email.setPlaceholderText("Email")
        _admin_input(self.admin_email)
        email_row.addWidget(self.admin_email)
        email_row.addStretch(1)
        g_admin_layout.addLayout(email_row)

        phone_row = QHBoxLayout()
        phone_lbl = QLabel("Phone:")
        _admin_label(phone_lbl)
        phone_row.addWidget(phone_lbl)
        self.admin_phone = QLineEdit()
        self.admin_phone.setPlaceholderText("Phone")
        _admin_input(self.admin_phone)
        phone_row.addWidget(self.admin_phone)
        phone_row.addStretch(1)
        g_admin_layout.addLayout(phone_row)

        pwd_row = QHBoxLayout()
        pwd_lbl = QLabel("Password:")
        _admin_label(pwd_lbl)
        pwd_row.addWidget(pwd_lbl)
        self.admin_password = QLineEdit()
        try:
            self.admin_password.setEchoMode(QLineEdit.EchoMode.Password)
        except AttributeError:
            self.admin_password.setEchoMode(QLineEdit.Password)
        self.admin_password.setPlaceholderText("Password")
        _admin_input(self.admin_password)
        pwd_row.addWidget(self.admin_password)
        pwd_row.addStretch(1)
        g_admin_layout.addLayout(pwd_row)

        self.setup_admin_btn = QPushButton("💾 Save Admin Credentials")
        self.setup_admin_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self.setup_admin_btn.clicked.connect(self._setup_admin)
        row_save = QHBoxLayout()
        row_save.addWidget(self.setup_admin_btn)
        row_save.addStretch()
        g_admin_layout.addLayout(row_save)

        self.delete_admin_btn = QPushButton("🗑️ Delete Admin Account (Admin)")
        self.delete_admin_btn.setStyleSheet("background-color:#dc2626;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #b91c1c;font-size:9pt;")
        self.delete_admin_btn.clicked.connect(self._delete_admin_account)
        row_delete = QHBoxLayout()
        row_delete.addWidget(self.delete_admin_btn)
        row_delete.addStretch()
        g_admin_layout.addLayout(row_delete)

        row_test_alert = QHBoxLayout()
        self.test_alert_btn = QPushButton("🔔 Test alert")
        self.test_alert_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self.test_alert_btn.setToolTip("Send a test alert to your Android app to verify backend and relay are working.")
        self.test_alert_btn.clicked.connect(self._on_test_alert_clicked)
        row_test_alert.addWidget(self.test_alert_btn)
        row_test_alert.addStretch()
        g_admin_layout.addLayout(row_test_alert)

        # Codes section: compact, same width as other admin fields (not full screen)
        self.admin_codes_container = QWidget(admin_group)
        self.admin_codes_container.setMaximumWidth(admin_input_max_w)
        codes_layout = QVBoxLayout(self.admin_codes_container)
        codes_layout.setContentsMargins(0, 12, 0, 0)
        codes_layout.setSpacing(6)
        codes_layout.addWidget(QLabel("Admin Access Code (enter in Android app to sign in as admin):"))
        self.admin_access_code_edit = QLineEdit()
        self.admin_access_code_edit.setReadOnly(True)
        self.admin_access_code_edit.setPlaceholderText("Shown when admin is created")
        _admin_input(self.admin_access_code_edit)
        try:
            self.admin_access_code_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except Exception:
            self.admin_access_code_edit.setEchoMode(QLineEdit.Password)
        codes_layout.addWidget(self.admin_access_code_edit)
        codes_layout.addWidget(QLabel("Connection Code (give to users so they can link to you):"))
        self.admin_connection_code_edit = QLineEdit()
        self.admin_connection_code_edit.setReadOnly(True)
        self.admin_connection_code_edit.setPlaceholderText("Shown when admin is created")
        _admin_input(self.admin_connection_code_edit)
        try:
            self.admin_connection_code_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except Exception:
            self.admin_connection_code_edit.setEchoMode(QLineEdit.Password)
        codes_layout.addWidget(self.admin_connection_code_edit)
        self.admin_codes_visible = False
        def _toggle_admin_codes():
            self.admin_codes_visible = not self.admin_codes_visible
            try:
                normal_mode = QLineEdit.EchoMode.Normal
                password_mode = QLineEdit.EchoMode.Password
            except Exception:
                normal_mode = QLineEdit.Normal
                password_mode = QLineEdit.Password
            if self.admin_codes_visible:
                self.admin_access_code_edit.setEchoMode(normal_mode)
                self.admin_connection_code_edit.setEchoMode(normal_mode)
                self.admin_show_codes_btn.setText("Hide codes")
            else:
                self.admin_access_code_edit.setEchoMode(password_mode)
                self.admin_connection_code_edit.setEchoMode(password_mode)
                self.admin_show_codes_btn.setText("Show codes")
        self.admin_show_codes_btn = QPushButton("Show codes")
        self.admin_show_codes_btn.setStyleSheet("background-color:#6b7280;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #4b5563;font-size:9pt;")
        self.admin_show_codes_btn.setMaximumWidth(140)
        self.admin_show_codes_btn.clicked.connect(_toggle_admin_codes)
        codes_btn_row = QHBoxLayout()
        codes_btn_row.addWidget(self.admin_show_codes_btn)
        codes_btn_row.addStretch()
        codes_layout.addLayout(codes_btn_row)
        self.admin_codes_container.setVisible(False)
        g_admin_layout.addWidget(self.admin_codes_container)

        admin_layout.addWidget(admin_group)

        self.admin_action_btn = QPushButton()
        self.admin_action_btn.setStyleSheet("background-color:#0D9488;color:#ffffff;font-weight:700;padding:8px 18px;border-radius:8px;border:1.5px solid #0F766E;font-size:9pt;")
        self.admin_action_btn.clicked.connect(self._handle_admin_login_logout)
        row_action = QHBoxLayout()
        row_action.addWidget(self.admin_action_btn)
        row_action.addStretch()
        admin_layout.addLayout(row_action)

        self._admin_tab_index = 2
        tabs.addTab(admin, "Admin Panel")
        self._settings_tabs = tabs
        # Admin Panel tab hidden until admin logs in (via sidebar Admin Login)
        try:
            tabs.setTabVisible(self._admin_tab_index, False)
        except Exception:
            pass

        c_layout.addWidget(tabs)
        scroll.setWidget(container)
        layout.addWidget(scroll)

        self.controller.admin_status_changed.connect(self._update_admin_tab_visibility)
        if hasattr(self.controller, "test_alert_done"):
            self.controller.test_alert_done.connect(self._on_test_alert_done)

    def _apply_admin_workstation_trim(self):
        """Desktop admin hub: no signup noise, no phone-only link flows, short copy."""
        if hasattr(self, "_sys_desc"):
            self._sys_desc.setText("Gmail, do-not-disturb, and appearance.")
        if hasattr(self, "_title_label"):
            self._title_label.setText("Settings")
        for name in (
            "link_to_admin_only_group",
            "linked_to_admin_group",
            "link_to_admin_btn",
            "unlink_desktop_btn",
            "_link_to_admin_desc",
        ):
            w = getattr(self, name, None)
            if w is not None:
                w.hide()
        if hasattr(self, "acc_rules"):
            self.acc_rules.setText(
                "Desktop unlock PIN (this PC) and optional device box PIN if you use hardware."
            )
        tabs = getattr(self, "_settings_tabs", None)
        if tabs is None:
            return
        for i in range(tabs.count()):
            t = (tabs.tabText(i) or "").lower()
            if "admin panel" in t:
                try:
                    tabs.setTabText(i, "Admin")
                except Exception:
                    pass
        self._update_admin_tab_visibility()

    def _refresh_admin_ui(self, db=None):
        """Admin workstation only — mobile app owns user/link flows; always show admin credentials UI."""
        if not hasattr(self, "admin_status_label") or not hasattr(self, "admin_action_btn"):
            return
        if db is None:
            try:
                db = self.controller.get_db()
            except Exception:
                return
        has_admin = db.has_admin_credentials() if hasattr(db, "has_admin_credentials") else False
        self.admin_status_label.setVisible(True)
        # When device has admin (admin-specific), no Login/Logout button
        self.admin_action_btn.setVisible(not has_admin)
        for w in (getattr(self, "admin_name", None), getattr(self, "admin_id", None), getattr(self, "admin_email", None),
                  getattr(self, "admin_phone", None), getattr(self, "admin_password", None),
                  getattr(self, "setup_admin_btn", None), getattr(self, "delete_admin_btn", None),
                  getattr(self, "test_alert_btn", None), getattr(self, "admin_codes_container", None)):
            if w is not None:
                w.setVisible(True)
        if hasattr(self, "test_alert_btn"):
            self.test_alert_btn.setVisible(has_admin)
        if hasattr(self, "admin_codes_container"):
            self.admin_codes_container.setVisible(has_admin)

        if has_admin:
            self.admin_status_label.setText("Admin account linked to this desktop.")
            self.admin_status_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 9pt;")
        if getattr(self, "_admin_workstation", False) and has_admin:
            for w in (
                getattr(self, "admin_name", None),
                getattr(self, "admin_id", None),
                getattr(self, "admin_email", None),
                getattr(self, "admin_phone", None),
                getattr(self, "admin_password", None),
                getattr(self, "setup_admin_btn", None),
                getattr(self, "admin_action_btn", None),
            ):
                if w is not None:
                    w.hide()
        elif getattr(self.controller, "admin_logged_in", False):
            self.admin_status_label.setText(
                f"✅ Logged in as {self.controller.logged_in_admin_name or 'Admin'} – all features unlocked."
            )
            self.admin_status_label.setStyleSheet(f"color: {NEON_GREEN}; font-size: 9pt;")
            self.admin_action_btn.setText("🔒 Logout")
        else:
            self.admin_status_label.setText("⚠️ Admin setup required (fill the form below and save credentials to create admin).")
            self.admin_status_label.setStyleSheet("color: #f97316; font-size: 9pt;")
            self.admin_action_btn.setText("Create Admin (fill form and Save)")

    def _update_admin_tab_visibility(self):
        """Show Admin Panel for admin workstation setup and credentials."""
        if not hasattr(self, "_settings_tabs") or not hasattr(self, "_admin_tab_index"):
            return
        try:
            db = self.controller.get_db()
            self._settings_tabs.setTabVisible(self._admin_tab_index, True)
            self._refresh_admin_ui(db)
        except Exception:
            pass

    def _load(self):
        db = self.controller.get_db()
        info = db.get_admin_info() if hasattr(db, "get_admin_info") else None
        if info:
            self.admin_name.setText(info.get("name", ""))
            self.admin_id.setText(info.get("admin_id", ""))
            self.admin_email.setText(info.get("email", ""))
            self.admin_phone.setText(info.get("phone", ""))

        # Access and connection codes: from local DB, or fetch from backend when admin is logged in and codes missing
        access_code = db.get("admin_access_code") if hasattr(db, "get") else None
        connection_code = db.get("admin_connection_code") if hasattr(db, "get") else None
        if (getattr(self.controller, "admin_logged_in", False) and (not (access_code or "").strip() or not (connection_code or "").strip())):
            ac, cc = self.controller.get_admin_codes_from_backend()
            if ac or cc:
                access_code = ac or access_code
                connection_code = cc or connection_code
                if getattr(db, "set", None):
                    if access_code:
                        db.set("admin_access_code", access_code)
                    if connection_code:
                        db.set("admin_connection_code", connection_code)
        if hasattr(self, "admin_access_code_edit"):
            self.admin_access_code_edit.setText((access_code or "").strip())
        if hasattr(self, "admin_connection_code_edit"):
            self.admin_connection_code_edit.setText((connection_code or "").strip())
        gmail = getattr(self.controller, "gmail_config", {})
        self.gmail_sender.setText(gmail.get("sender_email", ""))
        self.gmail_password.setText(gmail.get("sender_password", ""))
        self.gmail_recipients.setText(gmail.get("recipients", ""))

        theme = getattr(self.controller, "appearance_theme", "light") or "light"
        if str(theme).lower() == "light":
            self.theme_light_radio.setChecked(True)
        else:
            self.theme_dark_radio.setChecked(True)

        dnd = self.controller.alert_settings.get("do_not_disturb", {})
        self.dnd_enabled.setChecked(dnd.get("enabled", False))
        self.dnd_start.setText(dnd.get("start_time", "22:00"))
        self.dnd_end.setText(dnd.get("end_time", "07:00"))

        # User view: read-only settings (no Save buttons, checkboxes disabled — user can only view)
        is_user_view = self.controller.is_user_view()
        if hasattr(self, "gmail_save_btn"):
            self.gmail_save_btn.setVisible(not is_user_view)
        if hasattr(self, "gmail_test_btn"):
            self.gmail_test_btn.setVisible(not is_user_view)
        if hasattr(self, "save_sys_btn"):
            self.save_sys_btn.setVisible(not is_user_view)
        if hasattr(self, "dnd_enabled"):
            self.dnd_enabled.setEnabled(not is_user_view)
        if hasattr(self, "dnd_start"):
            self.dnd_start.setEnabled(not is_user_view)
        if hasattr(self, "dnd_end"):
            self.dnd_end.setEnabled(not is_user_view)
        if hasattr(self, "gmail_sender"):
            self.gmail_sender.setReadOnly(is_user_view)
        if hasattr(self, "gmail_password"):
            self.gmail_password.setReadOnly(is_user_view)
        if hasattr(self, "gmail_recipients"):
            self.gmail_recipients.setReadOnly(is_user_view)

        self._refresh_admin_ui(db)
        self._update_admin_tab_visibility()

    def _on_controller_data_updated(self):
        try:
            self._load()
        except Exception:
            pass

    def _save_gmail_settings(self):
        """Save Gmail/Email settings — admin approval required."""
        if self.main_window and not self.main_window.verify_admin_for_action("Save Gmail Settings"):
            return
        self._save_system_settings()

    def _save_appearance_settings(self):
        """Save only the selected appearance/theme (any user can save)."""
        theme = "light" if self.theme_light_radio.isChecked() else "dark"
        self.controller.save_appearance_theme(theme)
        QMessageBox.information(
            self,
            "Appearance",
            "Theme saved. It will be used next time you open CuraX.",
        )

    def apply_theme(self, theme_name: str, content_scale: float = None):
        """
        Adjust title, subtitles, and appearance-section text for dark vs light mode.
        Light mode: prominent title (ACCENT_LIGHT) and body (SECONDARY_LIGHT_DARK).
        """
        name = (theme_name or "light").lower()
        sc = content_scale if content_scale is not None else (getattr(self.window(), "_content_scale", 1.0) if self.window() else 1.0)
        s = max(0.5, min(1.0, float(sc)))
        title_color = ACCENT_LIGHT if name == "light" else NEON_GREEN
        secondary = SECONDARY_LIGHT_DARK if name == "light" else TEXT_SECONDARY
        if name == "light":
            main_color = "#0f172a"  # very dark text for radios
        else:
            main_color = "#e5e7eb"  # light text

        try:
            self._title_label.setStyleSheet(
                f"font-size: {max(8, int(18 * s))}pt; font-weight: bold; color: {title_color};"
            )
            self._sys_desc.setStyleSheet(f"color: {secondary}; font-size: {max(8, int(10 * s))}pt;")
            self._hint_email.setStyleSheet(f"color: {secondary}; font-size: {max(7, int(9 * s))}pt;")
            self._hint_dnd.setStyleSheet(f"color: {secondary}; font-size: {max(7, int(9 * s))}pt;")
        except Exception:
            pass
        try:
            self.theme_label.setStyleSheet(
                f"font-size: {max(9, int(11 * s))}pt; font-weight: bold; color: {title_color};"
            )
            self.appearance_hint.setStyleSheet(
                f"color: {secondary}; font-size: {max(7, int(9 * s))}pt;"
            )
            self.theme_dark_radio.setStyleSheet(
                f"font-size: {max(8, int(10 * s))}pt; font-weight: bold; color: {main_color};"
            )
            self.theme_light_radio.setStyleSheet(
                f"font-size: {max(8, int(10 * s))}pt; color: {main_color};"
            )
        except Exception:
            pass
        try:
            border = BORDER_LIGHT if name == "light" else BORDER
            bg = "#ffffff" if name == "light" else "#121827"
            title_pt = max(9, int(17 * s))
            mt = max(10, int(18 * s))
            pd = max(14, int(24 * s))
            pd2 = max(8, int(14 * s))
            inp_pt = max(7, int(10 * s))
            lbl_pt = max(7, int(9 * s))
            inp_h = max(22, int(28 * s))
            section_style = (
                f"QGroupBox {{ border: 1px solid {border}; border-radius: {RADIUS}; "
                f"margin-top: {mt}px; padding: {pd}px {pd2}px {pd2}px {pd2}px; background-color: {bg}; }} "
                f"QGroupBox::title {{ subcontrol-origin: margin; left: 14px; padding: 0 10px 12px 10px; "
                f"color: {title_color}; font-weight: 900; font-size: {title_pt}pt; }} "
                f"QGroupBox QLineEdit {{ font-size: {inp_pt}pt; min-height: {inp_h}px; max-height: {max(28, int(36 * s))}px; }} "
                f"QGroupBox QLabel {{ font-size: {lbl_pt}pt; }} "
                f"QGroupBox QCheckBox {{ font-size: {lbl_pt}pt; }} "
                f"QGroupBox QComboBox {{ font-size: {inp_pt}pt; min-height: {inp_h}px; }}"
            )
            for g in (
                getattr(self, "email_group", None),
                getattr(self, "appearance_group", None),
                getattr(self, "dnd_group", None),
                getattr(self, "link_to_admin_only_group", None),
                getattr(self, "linked_to_admin_group", None),
            ):
                if g is not None:
                    g.setStyleSheet(section_style)
        except Exception:
            pass
        try:
            from PyQt6.QtGui import QFont
        except Exception:
            from PyQt5.QtGui import QFont
        try:
            if hasattr(self, "_settings_container"):
                self._settings_container.setFont(QFont("Segoe UI", max(7, int(10 * s))))
            if hasattr(self, "_settings_tabs"):
                self._settings_tabs.setFont(QFont("Segoe UI", max(8, int(10 * s))))
                try:
                    tb = self._settings_tabs.tabBar()
                    if tb:
                        tb.setStyleSheet(f"font-size: {max(6, int(9 * s))}pt; font-weight: 600;")
                except Exception:
                    pass
        except Exception:
            pass
        try:
            if hasattr(self, "_sys_layout"):
                m = max(10, int(16 * s))
                self._sys_layout.setContentsMargins(m, m, m, max(12, int(20 * s)))
                self._sys_layout.setSpacing(max(12, int(20 * s)))
        except Exception:
            pass
        try:
            link_w_min = max(120, int(200 * s))
            link_w_max = max(200, int(320 * s))
            if hasattr(self, "link_to_admin_code_edit"):
                self.link_to_admin_code_edit.setMinimumWidth(link_w_min)
                self.link_to_admin_code_edit.setMaximumWidth(link_w_max)
        except Exception:
            pass
        try:
            btn_pt = max(7, int(9 * s))
            pad_v = max(6, int(8 * s))
            pad_h = max(12, int(18 * s))
            base_btn = f"font-weight:700;padding:{pad_v}px {pad_h}px;border-radius:8px;font-size:{btn_pt}pt;"
            if hasattr(self, "gmail_test_btn"):
                self.gmail_test_btn.setStyleSheet(f"background-color:#16a34a;color:#ffffff;border:1.5px solid #15803d;{base_btn}")
            if hasattr(self, "gmail_save_btn"):
                self.gmail_save_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
            if hasattr(self, "theme_save_btn"):
                self.theme_save_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
            if hasattr(self, "save_sys_btn"):
                self.save_sys_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
            if hasattr(self, "change_pwd_btn"):
                self.change_pwd_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
            if hasattr(self, "link_to_admin_btn"):
                self.link_to_admin_btn.setStyleSheet(f"background-color:#2563eb;color:#ffffff;border:1.5px solid #1d4ed8;{base_btn}")
            if hasattr(self, "unlink_desktop_btn"):
                self.unlink_desktop_btn.setStyleSheet(f"background-color:#dc2626;color:#ffffff;border:1.5px solid #b91c1c;{base_btn}")
            if hasattr(self, "setup_admin_btn"):
                self.setup_admin_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
            if hasattr(self, "delete_admin_btn"):
                self.delete_admin_btn.setStyleSheet(f"background-color:#dc2626;color:#ffffff;border:1.5px solid #b91c1c;{base_btn}")
            if hasattr(self, "test_alert_btn"):
                self.test_alert_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
            if hasattr(self, "admin_show_codes_btn"):
                self.admin_show_codes_btn.setStyleSheet(f"background-color:#6b7280;color:#ffffff;border:1.5px solid #4b5563;{base_btn}")
                self.admin_show_codes_btn.setMaximumWidth(max(100, int(140 * s)))
            if hasattr(self, "admin_action_btn"):
                self.admin_action_btn.setStyleSheet(f"background-color:#0D9488;color:#ffffff;border:1.5px solid #0F766E;{base_btn}")
        except Exception:
            pass
        try:
            admin_in_min = max(180, int(280 * s))
            admin_in_max = max(280, int(440 * s))
            for attr in ("admin_name", "admin_id", "admin_email", "admin_phone", "admin_password"):
                w = getattr(self, attr, None)
                if w is not None:
                    w.setMinimumWidth(admin_in_min)
                    w.setMaximumWidth(admin_in_max)
            if hasattr(self, "admin_codes_container"):
                self.admin_codes_container.setMaximumWidth(admin_in_max)
        except Exception:
            pass
        try:
            lbl_pt = max(7, int(9 * s))
            body_pt = max(8, int(10 * s))
            if hasattr(self, "_link_to_admin_desc"):
                self._link_to_admin_desc.setStyleSheet(f"color: {secondary}; font-size: {body_pt}pt;")
            if hasattr(self, "linked_to_admin_label"):
                self.linked_to_admin_label.setStyleSheet(f"color: {secondary}; font-size: {body_pt}pt;")
            if hasattr(self, "acc_title"):
                self.acc_title.setStyleSheet(f"font-size: {max(8, int(14 * s))}pt; font-weight: bold; color: {title_color};")
            if hasattr(self, "acc_rules"):
                self.acc_rules.setStyleSheet(f"color: {secondary}; font-size: {lbl_pt}pt;")
        except Exception:
            pass
        try:
            # Admin Panel tab: scale form labels and fields so they're not large on small screens
            if hasattr(self, "_settings_tabs") and self._settings_tabs.count() > 2:
                admin_tab = self._settings_tabs.widget(2)
                if admin_tab is not None:
                    ap_lbl = max(7, int(9 * s))
                    ap_inp = max(7, int(10 * s))
                    ap_label_min_w = max(100, int(200 * s))
                    admin_tab.setStyleSheet(
                        f"QLabel {{ font-size: {ap_lbl}pt; min-width: {ap_label_min_w}px; }} "
                        f"QLineEdit {{ font-size: {ap_inp}pt; min-height: {max(22, int(28 * s))}px; }} "
                        f"QCheckBox {{ font-size: {ap_lbl}pt; }} "
                        f"QComboBox {{ font-size: {ap_inp}pt; }}"
                    )
        except Exception:
            pass

    def showEvent(self, event):
        """Re-apply section title styles and refresh from controller when tab is shown (so Gmail/reminders loaded from Central are visible)."""
        try:
            super().showEvent(event)
            theme = getattr(self.controller, "appearance_theme", "light")
            self.apply_theme(theme)
            self._load()
        except Exception:
            super().showEvent(event)

    def _save_system_settings(self):
        """Persist Gmail / DND settings to controller and DB."""
        gmail = getattr(self.controller, "gmail_config", {})
        gmail["sender_email"] = self.gmail_sender.text().strip()
        gmail["sender_password"] = self.gmail_password.text().strip()
        gmail["recipients"] = self.gmail_recipients.text().strip()
        self.controller.gmail_config = gmail

        theme = "light" if self.theme_light_radio.isChecked() else "dark"
        self.controller.save_appearance_theme(theme)

        dnd = self.controller.alert_settings.setdefault("do_not_disturb", {})
        dnd["enabled"] = self.dnd_enabled.isChecked()
        start = self.dnd_start.text().strip() or "22:00"
        end = self.dnd_end.text().strip() or "07:00"
        dnd["start_time"] = start
        dnd["end_time"] = end

        try:
            self.controller.save_alert_settings()
        except Exception:
            pass
        self.controller.status_message.emit("System settings saved.")
        QMessageBox.information(self, "Saved", "System settings saved.")

    def _test_gmail(self):
        """Send a test email using current (unsaved) Gmail settings. Admin only."""
        if self.main_window and not self.main_window.verify_admin_for_action("Test Gmail Alerts"):
            return
        sender = self.gmail_sender.text().strip()
        pwd = self.gmail_password.text().strip()
        recips = self.gmail_recipients.text().strip()
        if not sender or not pwd:
            QMessageBox.warning(self, "Gmail Alerts", "Sender email and app password are required.")
            return
        prev = dict(getattr(self.controller, "gmail_config", {}))
        self.controller.gmail_config = {
            "sender_email": sender,
            "sender_password": pwd,
            "smtp_server": "smtp.gmail.com",
            "smtp_port": 465,
            "recipients": recips,
        }
        ok = False
        try:
            self.controller.send_gmail_alert(
                "CuraX Test Email",
                "This is a test alert from CuraX Settings."
            )
            ok = True
        except Exception:
            ok = False
        self.controller.gmail_config = prev
        if ok:
            self.controller.status_message.emit("Test email sent.")
        else:
            QMessageBox.warning(self, "Gmail Alerts", "Failed to send test email. Check Gmail settings and app password.")

    def _change_password(self):
        """
        Change device password: ask for current, then new + confirm.
        Anyone can change the password if they know the current one (not admin-only).
        """
        dlg = QDialog(self)
        dlg.setWindowTitle("Change Device Password")
        layout = QVBoxLayout(dlg)

        title = QLabel("Change Device Password")
        title.setStyleSheet(f"font-size: 14pt; font-weight: bold; color: {NEON_GREEN};")
        layout.addWidget(title)

        info = QLabel("Enter current password to verify, then set a new password.")
        info.setWordWrap(True)
        info.setStyleSheet(f"color: {TEXT_SECONDARY};")
        layout.addWidget(info)

        form = QFormLayout()
        current_edit = QLineEdit()
        new_edit = QLineEdit()
        confirm_edit = QLineEdit()
        try:
            current_edit.setEchoMode(QLineEdit.EchoMode.Password)
            new_edit.setEchoMode(QLineEdit.EchoMode.Password)
            confirm_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except AttributeError:
            current_edit.setEchoMode(QLineEdit.Password)
            new_edit.setEchoMode(QLineEdit.Password)
            confirm_edit.setEchoMode(QLineEdit.Password)

        new_edit.setEnabled(False)
        confirm_edit.setEnabled(False)

        current_row = QHBoxLayout()
        current_row.addWidget(current_edit)
        verify_btn = QPushButton("Verify Current")
        current_row.addWidget(verify_btn)
        form.addRow("Current password:", current_row)

        form.addRow("New password:", new_edit)
        form.addRow("Confirm password:", confirm_edit)
        layout.addLayout(form)

        status = QLabel("")
        status.setStyleSheet(f"color: {TEXT_SECONDARY};")
        layout.addWidget(status)

        btn_row = QHBoxLayout()
        ok_btn = QPushButton("Save New Password")
        ok_btn.setEnabled(False)  # enabled only after successful verify
        cancel_btn = QPushButton("Cancel")
        btn_row.addWidget(ok_btn)
        btn_row.addWidget(cancel_btn)
        layout.addLayout(btn_row)

        verified = {"ok": False}

        def on_verify():
            pwd = current_edit.text().strip()
            if not pwd:
                status.setText("❌ Enter current password.")
                status.setStyleSheet("color: #ef4444;")
                return
            if len(pwd) != 4 or not pwd.isdigit():
                status.setText("❌ Password must be 4 digits (numbers only).")
                status.setStyleSheet("color: #ef4444;")
                return

            status.setText("Verifying current password...")
            status.setStyleSheet("color: #facc15;")

            try:
                from PyQt6.QtWidgets import QApplication
            except ImportError:
                from PyQt5.QtWidgets import QApplication
            QApplication.processEvents()

            success, msg = self.controller.verify_pin_esp32(pwd)
            if success:
                verified["ok"] = True
                status.setText("✅ Current password verified.")
                status.setStyleSheet("color: #22c55e;")
                current_edit.setEnabled(False)
                verify_btn.setEnabled(False)
                verify_btn.setText("Verified")
                new_edit.setEnabled(True)
                confirm_edit.setEnabled(True)
                ok_btn.setEnabled(True)
                new_edit.setFocus()
            else:
                verified["ok"] = False
                if msg:
                    if "not responding" in msg.lower() or "not respond" in msg.lower():
                        msg = "ESP32 did not respond in time. Check cable and power, then try again."
                    status.setText(f"❌ {msg}")
                else:
                    status.setText("❌ Current password is incorrect or device did not respond.")
                status.setStyleSheet("color: #ef4444;")
                new_edit.clear()
                confirm_edit.clear()
                new_edit.setEnabled(False)
                confirm_edit.setEnabled(False)
                ok_btn.setEnabled(False)

        def on_ok():
            if not verified["ok"]:
                status.setText("❌ Verify current password first.")
                status.setStyleSheet("color: #ef4444;")
                return
            current = current_edit.text().strip()
            new = new_edit.text().strip()
            confirm = confirm_edit.text().strip()
            if not current or not new or not confirm:
                status.setText("❌ All fields are required.")
                status.setStyleSheet("color: #f97316;")
                return
            if new != confirm:
                status.setText("❌ New and confirm passwords do not match.")
                status.setStyleSheet("color: #ef4444;")
                return
            ok, msg = self.controller.change_device_password(current, new)
            if ok:
                self.controller.status_message.emit("Device password changed.")
                dlg.accept()
            else:
                status.setText(f"❌ {msg}")
                status.setStyleSheet("color: #ef4444;")

        verify_btn.clicked.connect(on_verify)
        ok_btn.clicked.connect(on_ok)
        cancel_btn.clicked.connect(dlg.reject)

        dlg.exec()

    def _on_set_desktop_unlock_pin(self):
        db = self.controller.get_db()
        if not db or not getattr(db, "has_admin_credentials", lambda: False)():
            QMessageBox.information(
                self,
                "Desktop PIN",
                "Create or link an admin account first (Admin tab or link with Access Code), then set a desktop PIN here.",
            )
            return
        if self.main_window and not self.main_window.verify_admin_for_action("Set desktop unlock PIN"):
            return
        has_pin = getattr(db, "has_desktop_app_unlock_pin", lambda: False)()

        dlg = QDialog(self)
        dlg.setWindowTitle("Desktop unlock PIN")
        dlg.setModal(True)
        lo = QVBoxLayout(dlg)
        info = QLabel(
            "Enter 4–8 digits. This PIN is stored only on this computer — it is not your Curax mobile app PIN and is not uploaded to the server."
        )
        info.setWordWrap(True)
        info.setStyleSheet(f"color: {TEXT_SECONDARY};")
        lo.addWidget(info)

        cur = QLineEdit()
        newp = QLineEdit()
        conf = QLineEdit()
        for e in (cur, newp, conf):
            try:
                e.setEchoMode(QLineEdit.EchoMode.Password)
            except Exception:
                e.setEchoMode(QLineEdit.Password)
        if has_pin:
            lo.addWidget(QLabel("Current desktop PIN:"))
            lo.addWidget(cur)
        lo.addWidget(QLabel("New desktop PIN:"))
        lo.addWidget(newp)
        lo.addWidget(QLabel("Confirm new PIN:"))
        lo.addWidget(conf)

        status = QLabel("")
        status.setStyleSheet("color: #ef4444;")
        lo.addWidget(status)

        row = QHBoxLayout()
        save_btn = QPushButton("Save")
        cancel_btn = QPushButton("Cancel")
        row.addWidget(save_btn)
        row.addWidget(cancel_btn)
        lo.addLayout(row)

        def do_save():
            n = newp.text().strip()
            c = conf.text().strip()
            if not n.isdigit() or not (4 <= len(n) <= 8):
                status.setText("New PIN must be 4–8 digits (numbers only).")
                return
            if n != c:
                status.setText("New PIN and confirmation do not match.")
                return
            if has_pin:
                if not db.verify_desktop_app_unlock_pin(cur.text().strip()):
                    status.setText("Current PIN is incorrect.")
                    return
            if db.set_desktop_app_unlock_pin(n):
                dlg.accept()
                QMessageBox.information(
                    self,
                    "Desktop PIN",
                    "Saved. The next time you start CuraX on this computer, you will need this PIN before the main window opens.",
                )
                if self.main_window and hasattr(self.main_window, "_apply_locked_state"):
                    self.main_window._apply_locked_state()
            else:
                status.setText("Could not save PIN.")

        save_btn.clicked.connect(do_save)
        cancel_btn.clicked.connect(dlg.reject)
        dlg.exec()

    def _on_clear_desktop_unlock_pin(self):
        db = self.controller.get_db()
        if not db or not getattr(db, "has_desktop_app_unlock_pin", lambda: False)():
            QMessageBox.information(self, "Desktop PIN", "No desktop unlock PIN is set.")
            return
        if self.main_window and not self.main_window.verify_admin_for_action("Remove desktop unlock PIN"):
            return
        if QMessageBox.question(
            self,
            "Remove desktop PIN",
            "Remove the desktop unlock PIN? Anyone with access to this Windows account could open CuraX without that PIN.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) != QMessageBox.StandardButton.Yes:
            return
        try:
            db.clear_desktop_app_unlock_pin()
        except Exception:
            pass
        QMessageBox.information(self, "Desktop PIN", "Desktop unlock PIN removed.")
        if self.main_window and hasattr(self.main_window, "_apply_locked_state"):
            self.main_window._apply_locked_state()

    def _setup_admin(self):
        """Create or update admin via backend API only, so access_code mapping stays consistent for /admin/data and /admin/sync."""
        name = self.admin_name.text().strip()
        admin_id_str = self.admin_id.text().strip() or name or "admin"
        email = self.admin_email.text().strip()
        phone = self.admin_phone.text().strip()
        password = self.admin_password.text().strip()
        if not name or not email or not password:
            QMessageBox.warning(self, "Validation", "Name, email, and password are required.")
            return
        db = self.controller.get_db()
        if db.has_admin_credentials():
            if self.main_window and not self.main_window.verify_admin_for_action("Update Admin Panel"):
                return
        is_new_admin = not db.has_admin_credentials()
        bot_id = str(uuid.uuid4())[:8]
        api_key = uuid.uuid4().hex[:16]
        aid, access_code, connection_code = None, None, None
        backend_save_failed = False

        # Same central API URL as all other activities (save/sync, delete admin, fetch data)
        base = getattr(self.controller, "get_central_api_base_url", lambda: None)()
        if base:
            try:
                import urllib.request
                import json as _json
                payload = _json.dumps({
                    "bot_id": bot_id,
                    "api_key": api_key,
                    "role": "admin",
                    "name": name,
                    "email": email,
                    "desktop_password": password,
                }).encode("utf-8")
                req = urllib.request.Request(
                    base.rstrip("/") + "/save-credentials",
                    data=payload,
                    method="POST",
                    headers={"Content-Type": "application/json"},
                )
                with urllib.request.urlopen(req, timeout=15) as resp:
                    if 200 <= getattr(resp, "status", 0) < 300:
                        body = resp.read().decode("utf-8", errors="replace")
                        data = _json.loads(body) if body.strip() else {}
                        access_code = (data.get("admin_access_code") or "").strip() or None
                        connection_code = (data.get("connection_code") or "").strip() or None
                        aid = data.get("admin_id")
            except Exception:
                backend_save_failed = True

        if not access_code and backend_save_failed:
            QMessageBox.warning(
                self,
                "Backend unavailable",
                "Could not save admin on backend. Start backend server and save admin again from Settings.",
            )
            return
        if not access_code:
            QMessageBox.warning(
                self,
                "Admin setup failed",
                "Backend did not return access/connection codes. Check backend URL and try again.",
            )
            return

        if not is_new_admin:
            info = db.get_admin_info() if hasattr(db, "get_admin_info") else None
            admin_id_str = (info.get("admin_id") or admin_id_str) if info else admin_id_str

        # Once backend confirms: save all returned data so admin can perform all activities
        if access_code:
            db.set("admin_access_code", access_code)
        if connection_code:
            db.set("admin_connection_code", connection_code)
        if db.set_admin_credentials(name, str(aid or admin_id_str), email, phone, password):
            try:
                self.controller.admin_status_changed.emit()
            except Exception:
                pass
            self._load()
            self.controller.load_data()
            if hasattr(self.controller, "restart_databus"):
                self.controller.restart_databus()
            if access_code:
                self.controller.status_message.emit("Admin created successfully. You can perform all admin activities.")
                QMessageBox.information(self, "Saved", "Admin created successfully. You can perform all admin activities.")
            else:
                self.controller.status_message.emit("Admin saved locally; set DATABASE_URL on server for load/save.")
                QMessageBox.information(self, "Saved", "Admin saved locally.")
            if self.main_window and hasattr(self.main_window, "switch_to_main_panel"):
                self.main_window.switch_to_main_panel()
        else:
            QMessageBox.warning(self, "Error", "Failed to save admin.")

    def _delete_admin_account(self):
        """Delete admin account completely (admin-protected, with confirmations)."""
        db = self.controller.get_db()
        if not db.has_admin_credentials():
            QMessageBox.information(self, "Admin", "No admin account exists to delete.")
            return
        if self.main_window and not self.main_window.verify_admin_for_action("Delete Admin Account"):
            return

        confirm = QMessageBox.question(
            self,
            "Delete Admin Account",
            "⚠ This will permanently delete the current admin account.\n\n"
            "All of this admin's data will be removed from the server database:\n"
            "the admin, all linked users, their medicines, dose logs, alerts, and settings.\n\n"
            "This desktop will also be cleared. You can create a new admin afterwards.\n\n"
            "Are you sure you want to continue?",
        )
        if confirm != QMessageBox.StandardButton.Yes:
            return

        final = QMessageBox.question(
            self,
            "Final Confirmation",
            "This action CANNOT be undone. The admin and all their data will be deleted from the database.\n\n"
            "Delete admin account now?",
        )
        if final != QMessageBox.StandardButton.Yes:
            return

        access_code = db.get("admin_access_code") if hasattr(db, "get") else None
        access_code = (access_code or "").strip() if isinstance(access_code, str) else ""
        if not access_code:
            QMessageBox.warning(
                self,
                "Admin",
                "No access code saved. Cannot delete admin on server.\nCreate/save admin first, then delete.",
            )
            return

        # Show "Deleting..." and run delete in background so UI never freezes or gets stuck
        self.controller.status_message.emit("Deleting admin on server...")
        if hasattr(self, "delete_admin_btn"):
            self.delete_admin_btn.setEnabled(False)
            self.delete_admin_btn.setText("Deleting...")

        worker = _DeleteAdminWorker(self.controller, access_code)
        worker.done.connect(lambda result: self._on_delete_admin_finished(result, db))
        self._delete_worker = worker  # keep reference so thread is not gc'd
        worker.start()

    def _on_test_alert_clicked(self):
        """Run test alert in background and show result when done."""
        if hasattr(self, "test_alert_btn"):
            self.test_alert_btn.setEnabled(False)
            self.test_alert_btn.setText("Sending...")
        import threading
        def work():
            ok, err = self.controller.send_admin_alert_test()
            if hasattr(self.controller, "test_alert_done"):
                try:
                    self.controller.test_alert_done.emit(ok, err or "")
                except Exception:
                    pass
        threading.Thread(target=work, daemon=True).start()

    def _on_test_alert_done(self, success, error_message):
        if hasattr(self, "test_alert_btn"):
            self.test_alert_btn.setEnabled(True)
            self.test_alert_btn.setText("🔔 Test alert")
        if success:
            if hasattr(self.controller, "status_message"):
                self.controller.status_message.emit("Test alert sent. Check your Android app.")
        else:
            if hasattr(self.controller, "status_message"):
                self.controller.status_message.emit("Test alert failed: " + (error_message or "Unknown error"))

    def _on_delete_admin_finished(self, backend_result, db):
        """Called when background delete finishes. Restore button and handle result."""
        if hasattr(self, "delete_admin_btn"):
            self.delete_admin_btn.setEnabled(True)
            self.delete_admin_btn.setText("🗑️ Delete Admin Account (Admin)")

        if backend_result is False:
            self.controller.status_message.emit("Delete failed.")
            QMessageBox.warning(
                self,
                "Admin",
                "Failed to delete admin on server. Admin was not removed.\nCheck your connection and try again.",
            )
            return

        # backend_result is "deleted" (200) or "not_found" (404 = already deleted on server): clear all local state — no cache
        if db.delete_admin_account():
            try:
                db.delete("admin_access_code")
            except Exception:
                pass
            try:
                db.delete("admin_connection_code")
            except Exception:
                pass
            try:
                if getattr(db, "clear_desktop_app_unlock_pin", None):
                    db.clear_desktop_app_unlock_pin()
            except Exception:
                pass
            try:
                self.controller.admin_logout()
            except Exception:
                self.controller.admin_logged_in = False
                self.controller.logged_in_admin_name = None
            try:
                if hasattr(self.controller, "restart_databus"):
                    self.controller.restart_databus()
            except Exception:
                pass
            if backend_result == "not_found":
                self.controller.status_message.emit("Admin was already deleted on server. Local account cleared.")
                QMessageBox.information(
                    self,
                    "Admin",
                    "Admin was already deleted on server.\nLocal account has been cleared. You can create a new admin.",
                )
            else:
                self.controller.status_message.emit("Admin deleted permanently. No cache left.")
            self._load()
        else:
            QMessageBox.warning(self, "Admin", "Server deleted the admin but local cleanup failed.")

    def _handle_admin_login_logout(self):
        """Mirror Tkinter admin panel's login/logout buttons using MainWindow helpers."""
        db = self.controller.get_db()
        has_admin = db.has_admin_credentials()
        if not has_admin:
            QMessageBox.information(
                self,
                "Admin",
                "No admin account configured yet.\nFill the Admin Setup / Update form and save.",
            )
            return

        if self.controller.admin_logged_in:
            if self.main_window:
                confirm = QMessageBox.question(
                    self,
                    "Logout Admin",
                    f"Logout {self.controller.logged_in_admin_name or 'Admin'}?\n\nAll admin-only features will be locked again.",
                )
                if confirm != QMessageBox.StandardButton.Yes:
                    return
            self.controller.admin_logout()
            self._load()
        else:
            if self.main_window:
                if self.main_window.open_admin_login_dialog():
                    self._load()
