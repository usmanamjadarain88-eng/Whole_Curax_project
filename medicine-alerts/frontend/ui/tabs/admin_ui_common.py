"""Shared layout helpers for admin desktop tabs."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QScrollArea, QFrame, QLabel,
        QTableWidget, QTableWidgetItem, QHeaderView, QAbstractScrollArea, QSizePolicy,
    )
    from PyQt6.QtCore import Qt
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QScrollArea, QFrame, QLabel,
        QTableWidget, QTableWidgetItem, QHeaderView, QAbstractScrollArea, QSizePolicy,
    )
    from PyQt5.QtCore import Qt

PAGE_MARGIN = (24, 20, 24, 24)
BTN_PRIMARY = (
    "background: #0D9488; color: #ffffff; font-weight: 600; padding: 10px 18px;"
    "border-radius: 8px; border: none; min-height: 36px;"
)
BTN_OUTLINE = (
    "background: #ffffff; color: #0F766E; font-weight: 600; padding: 10px 18px;"
    "border-radius: 8px; border: 1.5px solid #0D9488; min-height: 36px;"
)
BTN_DANGER = (
    "background: #DC2626; color: #ffffff; font-weight: 600; padding: 10px 18px;"
    "border-radius: 8px; border: none; min-height: 36px;"
)

SHELL_STYLE = "AdminPageShell { background: #F1F5F9; }"
HEADER_STYLE = (
    "QFrame#adminPageHeader {"
    "  background: #ffffff; border-bottom: 1px solid #E2E8F0;"
    "}"
    "QLabel#adminPageTitle {"
    "  font-size: 22pt; font-weight: 800; color: #0F766E; background: transparent;"
    "}"
)


def _scrollbar_always_off():
    try:
        return Qt.ScrollBarPolicy.ScrollBarAlwaysOff
    except AttributeError:
        return Qt.ScrollBarAlwaysOff


def _expanding():
    try:
        return QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding
    except AttributeError:
        return QSizePolicy.Expanding, QSizePolicy.Expanding


def _minimum_expanding():
    try:
        return QSizePolicy.Policy.Expanding, QSizePolicy.Policy.MinimumExpanding
    except AttributeError:
        return QSizePolicy.Expanding, QSizePolicy.MinimumExpanding


class AdminPageShell(QWidget):
    """Full-page chrome: white header band + scrollable body on grey canvas."""

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.setObjectName("AdminPageShell")
        self.setStyleSheet(SHELL_STYLE)
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        header = QFrame()
        header.setObjectName("adminPageHeader")
        header.setStyleSheet(HEADER_STYLE)
        hl = QHBoxLayout(header)
        hl.setContentsMargins(24, 16, 24, 16)
        self._title_lbl = QLabel(title)
        self._title_lbl.setObjectName("adminPageTitle")
        self._title_lbl.setWordWrap(True)
        hl.addWidget(self._title_lbl, 1)
        self._header_actions = QHBoxLayout()
        hl.addLayout(self._header_actions)
        root.addWidget(header)

        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        try:
            self._scroll.setFrameShape(QFrame.Shape.NoFrame)
        except AttributeError:
            self._scroll.setFrameShape(QFrame.NoFrame)
        self._scroll.setHorizontalScrollBarPolicy(_scrollbar_always_off())
        self._body = QWidget()
        self._body.setStyleSheet("background: #F1F5F9;")
        pol = _minimum_expanding()
        self._body.setSizePolicy(pol[0], pol[1])
        self._body_layout = QVBoxLayout(self._body)
        self._body_layout.setContentsMargins(*PAGE_MARGIN)
        self._body_layout.setSpacing(16)
        self._scroll.setWidget(self._body)
        root.addWidget(self._scroll, 1)

    def set_title(self, text: str):
        self._title_lbl.setText(text)

    def add_header_widget(self, widget):
        self._header_actions.addWidget(widget)

    def body_layout(self) -> QVBoxLayout:
        return self._body_layout


def section_label(text: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setWordWrap(True)
    lbl.setStyleSheet(
        "font-size: 9pt; font-weight: 700; color: #64748B; letter-spacing: 0.08em; "
        "background: transparent; padding-top: 4px;"
    )
    return lbl


def card_frame() -> QFrame:
    f = QFrame()
    f.setStyleSheet(
        "QFrame { background: #ffffff; border: 1px solid #E2E8F0; border-radius: 12px; }"
    )
    return f


def table_item(text: str) -> QTableWidgetItem:
    it = QTableWidgetItem(str(text or ""))
    try:
        it.setTextAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
    except AttributeError:
        it.setTextAlignment(Qt.AlignLeft | Qt.AlignVCenter)
    return it


def prepare_table(table: QTableWidget, stretch_col: int = -1):
    table.setWordWrap(True)
    table.setAlternatingRowColors(True)
    try:
        table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
    except AttributeError:
        table.setEditTriggers(QTableWidget.NoEditTriggers)
        table.setSelectionBehavior(QTableWidget.SelectRows)
    table.horizontalHeader().setStretchLastSection(True)
    pol = _expanding()
    table.setSizePolicy(pol[0], pol[1])
    try:
        table.verticalHeader().setVisible(False)
        table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        if stretch_col >= 0:
            table.horizontalHeader().setSectionResizeMode(
                stretch_col, QHeaderView.ResizeMode.Stretch
            )
    except AttributeError:
        try:
            table.verticalHeader().setVisible(False)
            table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
            if stretch_col >= 0:
                table.horizontalHeader().setSectionResizeMode(stretch_col, QHeaderView.Stretch)
        except Exception:
            pass
    except Exception:
        pass


def resize_table_rows(table: QTableWidget):
    try:
        table.resizeRowsToContents()
    except Exception:
        pass


def admin_scroll_page(parent=None):
    """Legacy helper: returns scroll, body, layout (prefer AdminPageShell)."""
    shell = AdminPageShell("", parent)
    return shell._scroll, shell._body, shell._body_layout


def page_title(text: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setWordWrap(True)
    lbl.setObjectName("adminPageTitle")
    lbl.setStyleSheet(
        "font-size: 22pt; font-weight: 800; color: #0F766E; background: transparent;"
    )
    return lbl
