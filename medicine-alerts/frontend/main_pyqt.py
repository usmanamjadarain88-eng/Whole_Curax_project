"""
main_pyqt.py — CuraX Desktop Entry Point
Run with:  python main_pyqt.py
"""
import os
import sys

# ── Path setup ────────────────────────────────────────────────────────────────
if getattr(sys, "frozen", False):
    PYQT_ROOT = os.path.dirname(sys.executable)
    _meipass = getattr(sys, "_MEIPASS", None)
    if _meipass and _meipass not in sys.path:
        sys.path.insert(0, _meipass)
else:
    PYQT_ROOT = os.path.dirname(os.path.abspath(__file__))

if PYQT_ROOT not in sys.path:
    sys.path.insert(0, PYQT_ROOT)

REPO_ROOT = os.path.dirname(PYQT_ROOT)
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

# ── Qt import ─────────────────────────────────────────────────────────────────
try:
    from PyQt6.QtWidgets import QApplication
    from PyQt6.QtGui import QPalette, QColor, QFont
    from PyQt6.QtCore import QTimer
except ImportError:
    try:
        from PyQt5.QtWidgets import QApplication
        from PyQt5.QtGui import QPalette, QColor, QFont
        from PyQt5.QtCore import QTimer
    except ImportError:
        print("CuraX requires PyQt6 (or PyQt5). Install with:\n  pip install PyQt6")
        sys.exit(1)

from core.controller import AppController
from ui.main_window import MainWindow


def resolve_storage_path() -> str:
    """Path to the single JSON file (curax_desktop.json). Matches db.AlertDB JSON storage."""
    if getattr(sys, "frozen", False):
        app_dir = os.path.join(os.path.expanduser("~"), "AppData", "Local", "CuraxAlerts")
        os.makedirs(app_dir, exist_ok=True)
        return os.path.join(app_dir, "curax_desktop.json")
    return os.path.join(PYQT_ROOT, "curax_desktop.json")


def _apply_consistent_look(app, theme_name="light"):
    try:
        app.setStyle("Fusion")
    except Exception:
        pass
    try:
        from theme.tokens import (
            BG_LIGHT, BG_INPUT_LIGHT, TEXT_PRIMARY_LIGHT, ACCENT_LIGHT,
            BG_DARK, BG_INPUT, TEXT_PRIMARY, ACCENT_DARK,
        )
        palette = QPalette()
        R = QPalette.ColorRole
        if theme_name == "dark":
            palette.setColor(R.Window,     QColor(BG_DARK))
            palette.setColor(R.WindowText, QColor(TEXT_PRIMARY))
            palette.setColor(R.Base,       QColor(BG_INPUT))
            palette.setColor(R.Text,       QColor(TEXT_PRIMARY))
            palette.setColor(R.Button,     QColor(ACCENT_DARK))
            palette.setColor(R.ButtonText, QColor("#ffffff"))
            palette.setColor(R.Highlight,  QColor(ACCENT_DARK))
        else:
            # BG_LIGHT is transparent — use a real color for palette
            palette.setColor(R.Window,     QColor("#e4f7f2"))
            palette.setColor(R.WindowText, QColor(TEXT_PRIMARY_LIGHT))
            palette.setColor(R.Base,       QColor(BG_INPUT_LIGHT))
            palette.setColor(R.Text,       QColor(TEXT_PRIMARY_LIGHT))
            palette.setColor(R.Button,     QColor(ACCENT_LIGHT))
            palette.setColor(R.ButtonText, QColor("#ffffff"))
            palette.setColor(R.Highlight,  QColor(ACCENT_LIGHT))
        app.setPalette(palette)
    except Exception:
        pass
    try:
        font = QFont("Segoe UI", 10)
        try:
            font.setStyleHint(QFont.StyleHint.SansSerif)
        except AttributeError:
            font.setStyleHint(QFont.SansSerif)
        app.setFont(font)
    except Exception:
        pass
    try:
        from theme import DARK_THEME, LIGHT_THEME
        app.setStyleSheet(DARK_THEME if theme_name == "dark" else LIGHT_THEME)
    except Exception:
        pass


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("CuraX - Intelligent Medicine System")
    app.setApplicationDisplayName("CuraX Medicine Alerts")

    storage_path = os.path.abspath(resolve_storage_path())
    controller = AppController(db_path=storage_path)

    theme = (getattr(controller, "appearance_theme", "light") or "light").strip().lower()
    if theme not in ("light", "dark"):
        theme = "light"
    _apply_consistent_look(app, theme)

    window = MainWindow(controller)
    # Defer show to first event loop so only one window appears (avoids 3-window bug on Windows)
    window.setVisible(False)
    QTimer.singleShot(0, window.show)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
