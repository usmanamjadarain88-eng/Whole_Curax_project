"""Admin Settings — hub admin only (Gmail, DND, theme, codes, PIN). No desktop link code."""
try:
    from PyQt6.QtWidgets import QWidget, QVBoxLayout
except ImportError:
    from PyQt5.QtWidgets import QWidget, QVBoxLayout

from ui.tabs.settings_tab import SettingsTab


class AdminSettingsTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        lo = QVBoxLayout(self)
        lo.setContentsMargins(0, 0, 0, 0)
        lo.setSpacing(0)
        self._inner = SettingsTab(
            controller, main_window, self, admin_workstation=True
        )
        lo.addWidget(self._inner)

    def apply_theme(self, theme, content_scale=None):
        if hasattr(self._inner, "apply_theme"):
            try:
                self._inner.apply_theme(theme, content_scale=content_scale)
            except TypeError:
                self._inner.apply_theme(theme)
