"""
auth/pin_dialog.py — CuraX PIN authentication dialog.
Professional design: gradient header, numeric keypad, live dot display.
Works in both light and dark themes.
"""
import time

try:
    from PyQt6.QtWidgets import (
        QDialog, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QPushButton, QFrame, QApplication, QWidget,
    )
    from PyQt6.QtCore import Qt, QTimer
    from PyQt6.QtGui import QFont
except ImportError:
    from PyQt5.QtWidgets import (
        QDialog, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QPushButton, QFrame, QApplication, QWidget,
    )
    from PyQt5.QtCore import Qt, QTimer
    from PyQt5.QtGui import QFont

from auth.verify_esp32 import verify_pin_esp32


class PinDialog(QDialog):
    """4-digit PIN dialog with keypad, live feedback, and full theme support."""

    def __init__(self, controller, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.pin = ""
        self._theme = (
            getattr(parent, "_current_theme", None)
            or getattr(controller, "appearance_theme", "light")
            or "light"
        ).lower()
        if self._theme not in ("dark", "light"):
            self._theme = "light"

        self.setWindowTitle("CuraX — Authentication")
        self.setMinimumWidth(330)
        self.setMaximumWidth(390)
        try:
            self.setWindowFlag(Qt.WindowType.MSWindowsFixedSizeDialogHint, True)
        except AttributeError:
            pass

        self._build_ui()
        self.apply_theme(self._theme)

    # ── Build UI ──────────────────────────────────────────────────────────────
    def _build_ui(self):
        root = QVBoxLayout(self)
        root.setSpacing(0)
        root.setContentsMargins(0, 0, 0, 0)

        # ── Header ────────────────────────────────────────────
        self._header = QFrame()
        self._header.setObjectName("pinHeader")
        hl = QVBoxLayout(self._header)
        hl.setContentsMargins(28, 24, 28, 20)
        hl.setSpacing(5)
        hl.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self._icon_lbl = QLabel("🔒")
        self._icon_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hl.addWidget(self._icon_lbl)

        self._title_lbl = QLabel("System Authentication")
        self._title_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hl.addWidget(self._title_lbl)

        self._sub_lbl = QLabel("Enter your 4-digit PIN")
        self._sub_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hl.addWidget(self._sub_lbl)

        root.addWidget(self._header)

        # ── Body ──────────────────────────────────────────────
        self._body = QFrame()
        self._body.setObjectName("pinBody")
        bl = QVBoxLayout(self._body)
        bl.setContentsMargins(24, 20, 24, 24)
        bl.setSpacing(14)

        # Dot display
        self._dot_frame = QFrame()
        self._dot_frame.setObjectName("pinDotFrame")
        df_lo = QVBoxLayout(self._dot_frame)
        df_lo.setContentsMargins(0, 8, 0, 8)
        self._dot_lbl = QLabel("_ _ _ _")
        self._dot_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        df_lo.addWidget(self._dot_lbl)
        bl.addWidget(self._dot_frame)

        # Status line
        self._status_lbl = QLabel("Enter PIN to authenticate")
        self._status_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._status_lbl.setWordWrap(True)
        bl.addWidget(self._status_lbl)

        # Keypad
        try:
            kfont = QFont("Segoe UI", 13, QFont.Weight.Bold)
        except AttributeError:
            kfont = QFont("Segoe UI", 13, QFont.Bold)

        self._keypad_btns = []
        grid = QGridLayout()
        grid.setSpacing(8)
        layout_map = [
            ("1","2","3"),
            ("4","5","6"),
            ("7","8","9"),
            ("", "0","⌫"),
        ]
        for row, row_keys in enumerate(layout_map):
            for col, key in enumerate(row_keys):
                if key == "":
                    grid.addWidget(QWidget(), row, col)
                    continue
                btn = QPushButton(key)
                btn.setFont(kfont)
                btn.setMinimumSize(66, 50)
                if key == "⌫":
                    btn.setObjectName("pinBksp")
                    btn.clicked.connect(self._backspace)
                else:
                    btn.setObjectName("pinKey")
                    btn.clicked.connect(lambda _, d=key: self._add_digit(d))
                self._keypad_btns.append(btn)
                grid.addWidget(btn, row, col)
        bl.addLayout(grid)

        # Bottom row
        brow = QHBoxLayout()
        brow.setSpacing(10)
        self._cancel_btn = QPushButton("Cancel")
        self._cancel_btn.setObjectName("pinCancel")
        self._cancel_btn.setMinimumHeight(38)
        self._cancel_btn.clicked.connect(self.reject)
        self._clear_btn = QPushButton("Clear")
        self._clear_btn.setObjectName("pinClear")
        self._clear_btn.setMinimumHeight(38)
        self._clear_btn.clicked.connect(self._clear)
        brow.addWidget(self._cancel_btn)
        brow.addWidget(self._clear_btn)
        bl.addLayout(brow)

        root.addWidget(self._body)

    # ── Theme ─────────────────────────────────────────────────────────────────
    def apply_theme(self, theme: str):
        self._theme = (theme or "light").lower()
        dark = self._theme == "dark"

        if dark:
            dlg_bg     = "#0A0F1E"
            hdr_bg1    = "#0D1828"
            hdr_bg2    = "#091525"
            hdr_border = "#1A3040"
            body_bg    = "#0A0F1E"
            accent     = "#2DD4BF"
            title_c    = "#2DD4BF"
            sub_c      = "#64748B"
            dot_bg     = "#0D1828"
            dot_border = "#1A3040"
            dot_c_norm = "#E2E8F0"
            dot_c_ok   = "#2DD4BF"
            dot_c_err  = "#EF4444"
            stat_norm  = "#2DD4BF"
            stat_err   = "#EF4444"
            stat_ok    = "#2DD4BF"
            stat_wait  = "#F59E0B"
            key_bg     = "#0D1828"
            key_c      = "#E2E8F0"
            key_bdr    = "#1A3040"
            key_hov    = "#132535"
            key_hov_c  = "#2DD4BF"
            key_hov_b  = "#2DD4BF"
            bksp_bg    = "#131828"
            bksp_c     = "#94A3B8"
            cancel_bg  = "#7F1D1D"
            cancel_c   = "#FEE2E2"
            cancel_hov = "#EF4444"
            clear_bg   = "#0D1828"
            clear_c    = "#94A3B8"
            clear_bdr  = "#1A3040"
            clear_hov  = "#162030"
            clear_hov_c= "#E2E8F0"
        else:
            dlg_bg     = "#F0FAF8"
            hdr_bg1    = "#FFFFFF"
            hdr_bg2    = "#EDF8F5"
            hdr_border = "#C0DDD8"
            body_bg    = "#F0FAF8"
            accent     = "#0D9488"
            title_c    = "#0D6B61"
            sub_c      = "#64748B"
            dot_bg     = "#FFFFFF"
            dot_border = "#0D9488"
            dot_c_norm = "#0F172A"
            dot_c_ok   = "#0D9488"
            dot_c_err  = "#EF4444"
            stat_norm  = "#0D9488"
            stat_err   = "#EF4444"
            stat_ok    = "#0D9488"
            stat_wait  = "#D97706"
            key_bg     = "#FFFFFF"
            key_c      = "#0F172A"
            key_bdr    = "#C0DDD8"
            key_hov    = "#EAF5F2"
            key_hov_c  = "#0D6B61"
            key_hov_b  = "#0D9488"
            bksp_bg    = "#F1F5F9"
            bksp_c     = "#64748B"
            cancel_bg  = "#EF4444"
            cancel_c   = "#FFFFFF"
            cancel_hov = "#DC2626"
            clear_bg   = "#FFFFFF"
            clear_c    = "#475569"
            clear_bdr  = "#C0DDD8"
            clear_hov  = "#F1F5F9"
            clear_hov_c= "#0F172A"

        # Store display colour sets for runtime use
        self._dc = {
            "norm": dot_c_norm, "ok": dot_c_ok, "err": dot_c_err,
            "stat_norm": stat_norm, "stat_err": stat_err,
            "stat_ok": stat_ok, "stat_wait": stat_wait,
        }

        self.setStyleSheet(f"QDialog {{ background-color: {dlg_bg}; }}")

        self._header.setStyleSheet(f"""
            QFrame#pinHeader {{
                background: qlineargradient(x1:0,y1:0,x2:1,y2:1,
                    stop:0 {hdr_bg1}, stop:1 {hdr_bg2});
                border-bottom: 1px solid {hdr_border};
                border-radius: 0px;
            }}
        """)
        self._icon_lbl.setStyleSheet(
            "font-size: 26pt; background: transparent;"
        )
        self._title_lbl.setStyleSheet(
            f"font-size: 14pt; font-weight: 800; color: {title_c}; "
            "background: transparent; letter-spacing: -0.3px;"
        )
        self._sub_lbl.setStyleSheet(
            f"font-size: 9pt; color: {sub_c}; background: transparent;"
        )

        self._body.setStyleSheet(
            f"QFrame#pinBody {{ background-color: {body_bg}; }}"
        )

        self._dot_frame.setStyleSheet(f"""
            QFrame#pinDotFrame {{
                background-color: {dot_bg};
                border: 1px solid {dot_border};
                border-radius: 10px;
            }}
        """)
        self._dot_lbl.setStyleSheet(
            f"font-size: 22pt; font-weight: 800; color: {dot_c_norm}; "
            "letter-spacing: 10px; background: transparent;"
        )

        self._status_lbl.setStyleSheet(
            f"font-size: 9pt; color: {stat_norm}; background: transparent;"
        )

        self.setStyleSheet(self.styleSheet() + f"""
            QPushButton#pinKey {{
                background-color: {key_bg};
                color: {key_c};
                border: 1px solid {key_bdr};
                border-radius: 9px;
            }}
            QPushButton#pinKey:hover {{
                background-color: {key_hov};
                border-color: {key_hov_b};
                color: {key_hov_c};
            }}
            QPushButton#pinKey:pressed {{
                background-color: {accent};
                color: #FFFFFF;
                border-color: {accent};
            }}
            QPushButton#pinBksp {{
                background-color: {bksp_bg};
                color: {bksp_c};
                border: 1px solid {key_bdr};
                border-radius: 9px;
                font-size: 14pt;
            }}
            QPushButton#pinBksp:hover {{
                background-color: {key_hov};
                color: {key_c};
            }}
            QPushButton#pinCancel {{
                background-color: {cancel_bg};
                color: {cancel_c};
                border: none;
                border-radius: 9px;
                font-size: 10pt;
                font-weight: 600;
                padding: 8px 16px;
            }}
            QPushButton#pinCancel:hover {{ background-color: {cancel_hov}; color: #fff; }}
            QPushButton#pinClear {{
                background-color: {clear_bg};
                color: {clear_c};
                border: 1px solid {clear_bdr};
                border-radius: 9px;
                font-size: 10pt;
                font-weight: 600;
                padding: 8px 16px;
            }}
            QPushButton#pinClear:hover {{
                background-color: {clear_hov};
                color: {clear_hov_c};
            }}
        """)

    # ── Helpers ───────────────────────────────────────────────────────────────
    def _update_dots(self):
        filled = "●" * len(self.pin)
        empty  = "  _" * (4 - len(self.pin))
        self._dot_lbl.setText(filled + empty.lstrip() if self.pin else "_ _ _ _")

    def _set_dot_color(self, mode: str):
        c = self._dc.get(mode, self._dc["norm"])
        self._dot_lbl.setStyleSheet(
            f"font-size: 22pt; font-weight: 800; color: {c}; "
            "letter-spacing: 10px; background: transparent;"
        )

    def _set_status(self, text: str, mode: str = "norm"):
        c = self._dc.get(f"stat_{mode}", self._dc["stat_norm"])
        self._status_lbl.setText(text)
        self._status_lbl.setStyleSheet(
            f"font-size: 9pt; color: {c}; background: transparent;"
        )

    # ── Interaction ───────────────────────────────────────────────────────────
    def _add_digit(self, digit: str):
        if len(self.pin) >= 4:
            return
        self.pin += digit
        self._update_dots()
        self._set_dot_color("norm")
        self._set_status("●" * len(self.pin) + "○" * (4 - len(self.pin)), "norm")
        if len(self.pin) == 4:
            QTimer.singleShot(250, self._verify)

    def _backspace(self):
        if self.pin:
            self.pin = self.pin[:-1]
            self._update_dots()
            self._set_dot_color("norm")
            if not self.pin:
                self._set_status("Enter PIN to authenticate", "norm")
            else:
                self._set_status("●" * len(self.pin) + "○" * (4 - len(self.pin)), "norm")

    def _clear(self):
        self.pin = ""
        self._dot_lbl.setText("_ _ _ _")
        self._set_dot_color("norm")
        self._set_status("Enter PIN to authenticate", "norm")

    def _verify(self):
        if len(self.pin) != 4:
            self._set_status("✗ PIN must be 4 digits", "err")
            return
        self._set_status("Verifying…", "wait")
        try:
            QApplication.processEvents()
        except Exception:
            pass
        success, msg = verify_pin_esp32(self.controller, self.pin)
        if success:
            self.controller.authenticated = True
            self.controller.authenticated_changed.emit(True)
            self._dot_lbl.setText("✓ ✓ ✓ ✓")
            self._set_dot_color("ok")
            self._set_status("✓ Authenticated!", "ok")
            QTimer.singleShot(700, self.accept)
        else:
            if "not responding" in (msg or "").lower():
                msg = "ESP32 not responding — check cable & power."
            self._dot_lbl.setText("✗ ✗ ✗ ✗")
            self._set_dot_color("err")
            self._set_status(f"✗ {msg or 'Wrong PIN'}", "err")
            QTimer.singleShot(1500, self._clear)

    # ── Keyboard support ──────────────────────────────────────────────────────
    def keyPressEvent(self, event):
        try:
            text = event.text()
            if text and text.isdigit():
                self._add_digit(text)
                return
            key = event.key()
            try:
                BS  = Qt.Key.Key_Backspace
                ENT = Qt.Key.Key_Return
                ESC = Qt.Key.Key_Escape
            except AttributeError:
                BS  = Qt.Key_Backspace
                ENT = Qt.Key_Return
                ESC = Qt.Key_Escape
            if key == BS:
                self._backspace()
            elif key == ENT:
                self._verify()
            elif key == ESC:
                self.reject()
            else:
                super().keyPressEvent(event)
        except Exception:
            super().keyPressEvent(event)
