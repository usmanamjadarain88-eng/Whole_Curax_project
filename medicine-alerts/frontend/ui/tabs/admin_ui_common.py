"""Shared layout helpers for admin desktop tabs."""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QScrollArea, QFrame, QLabel,
        QTableWidget, QTableWidgetItem, QHeaderView, QAbstractScrollArea, QSizePolicy,
        QLineEdit, QComboBox, QTextEdit,
    )
    from PyQt6.QtCore import Qt
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QScrollArea, QFrame, QLabel,
        QTableWidget, QTableWidgetItem, QHeaderView, QAbstractScrollArea, QSizePolicy,
        QLineEdit, QComboBox, QTextEdit,
    )
    from PyQt5.QtCore import Qt

# Admin hub design tokens (single source for borders / surfaces)
ADMIN_PAGE_BG = "#F1F5F9"
ADMIN_CARD_BG = "#FFFFFF"
ADMIN_BORDER = "#E2E8F0"
ADMIN_TEXT = "#0F172A"
ADMIN_MUTED = "#64748B"
ADMIN_ACCENT = "#0F766E"
ADMIN_ACCENT_BRIGHT = "#0D9488"
CARD_RADIUS = "12px"
CARD_PAD = (16, 14, 16, 14)
TOOLBAR_PAD = (16, 12, 16, 12)
PAGE_MARGIN = (24, 20, 24, 24)

BTN_PRIMARY = (
    f"background: {ADMIN_ACCENT_BRIGHT}; color: #ffffff; font-weight: 600; padding: 10px 18px;"
    "border-radius: 8px; border: none; min-height: 36px;"
)
BTN_OUTLINE = (
    f"background: {ADMIN_CARD_BG}; color: {ADMIN_ACCENT}; font-weight: 600; padding: 10px 18px;"
    f"border-radius: 8px; border: 1.5px solid {ADMIN_ACCENT_BRIGHT}; min-height: 36px;"
)
BTN_DANGER = (
    "background: #DC2626; color: #ffffff; font-weight: 600; padding: 10px 18px;"
    "border-radius: 8px; border: none; min-height: 36px;"
)

INPUT_STYLE = f"""
QLineEdit, QComboBox {{
    background: {ADMIN_CARD_BG};
    border: 1px solid {ADMIN_BORDER};
    border-radius: 8px;
    padding: 8px 12px;
    min-height: 34px;
    font-size: 10pt;
    color: {ADMIN_TEXT};
}}
QLineEdit:focus, QComboBox:focus, QLineEdit:hover, QComboBox:hover {{
    border-color: {ADMIN_ACCENT_BRIGHT};
}}
QComboBox::drop-down {{
    border: none;
    width: 28px;
}}
"""

TABLE_STYLE = f"""
QTableWidget {{
    background: {ADMIN_CARD_BG};
    border: none;
    gridline-color: {ADMIN_BORDER};
    alternate-background-color: #F8FAFC;
    font-size: 10pt;
    color: {ADMIN_TEXT};
}}
QTableWidget::item {{
    padding: 6px 10px;
    border: none;
}}
QTableWidget::item:selected {{
    background: #CCFBF1;
    color: {ADMIN_TEXT};
}}
QHeaderView::section {{
    background: #F8FAFC;
    color: #475569;
    font-weight: 600;
    font-size: 9pt;
    padding: 10px 8px;
    border: none;
    border-bottom: 1px solid {ADMIN_BORDER};
}}
"""

TEXT_BODY_STYLE = f"""
QTextEdit {{
    background: {ADMIN_CARD_BG};
    border: none;
    font-size: 10pt;
    color: {ADMIN_TEXT};
    padding: 4px;
}}
"""

SHELL_STYLE = f"AdminPageShell {{ background: {ADMIN_PAGE_BG}; }}"
HEADER_STYLE = (
    f"QFrame#adminPageHeader {{"
    f"  background: {ADMIN_CARD_BG}; border-bottom: 1px solid {ADMIN_BORDER};"
    f"}}"
    f"QLabel#adminPageTitle {{"
    f"  font-size: 18pt; font-weight: 800; color: {ADMIN_ACCENT}; background: transparent;"
    f"}}"
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
    """Full-page chrome: header band + scrollable body on grey canvas."""

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
        hl.setContentsMargins(24, 12, 24, 12)
        self._title_lbl = QLabel(title)
        self._title_lbl.setObjectName("adminPageTitle")
        self._title_lbl.setWordWrap(True)
        hl.addWidget(self._title_lbl, 1)
        self._header_actions = QHBoxLayout()
        self._header_actions.setSpacing(10)
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
        self._body.setStyleSheet(f"background: {ADMIN_PAGE_BG};")
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
        f"font-size: 9pt; font-weight: 700; color: {ADMIN_MUTED}; letter-spacing: 0.08em; "
        "background: transparent; padding-top: 2px; padding-bottom: 2px;"
    )
    return lbl


def muted_label(text: str = "") -> QLabel:
    lbl = QLabel(text)
    lbl.setWordWrap(True)
    lbl.setStyleSheet(
        f"color: {ADMIN_MUTED}; font-size: 10pt; background: transparent; padding: 4px 0;"
    )
    return lbl


def card_frame() -> QFrame:
    f = QFrame()
    f.setStyleSheet(
        f"QFrame {{ background: {ADMIN_CARD_BG}; border: 1px solid {ADMIN_BORDER}; "
        f"border-radius: {CARD_RADIUS}; }}"
    )
    return f


def card_layout(frame: QFrame) -> QVBoxLayout:
    lo = QVBoxLayout(frame)
    lo.setContentsMargins(*CARD_PAD)
    lo.setSpacing(12)
    return lo


def apply_input_style(widget):
    widget.setStyleSheet(INPUT_STYLE)


def table_card(table: QTableWidget, stretch_col: int = -1) -> QFrame:
    """White bordered block containing a data table."""
    prepare_table(table, stretch_col=stretch_col)
    table.setStyleSheet(TABLE_STYLE)
    card = card_frame()
    lo = QVBoxLayout(card)
    lo.setContentsMargins(0, 0, 0, 0)
    lo.setSpacing(0)
    lo.addWidget(table)
    return card


def toolbar_card() -> tuple:
    """Toolbar row inside a bordered card. Returns (card, layout)."""
    card = card_frame()
    lo = QHBoxLayout(card)
    lo.setContentsMargins(*TOOLBAR_PAD)
    lo.setSpacing(10)
    return card, lo


def text_card(editor: QTextEdit) -> QFrame:
    editor.setStyleSheet(TEXT_BODY_STYLE)
    card = card_frame()
    lo = QVBoxLayout(card)
    lo.setContentsMargins(12, 10, 12, 10)
    lo.addWidget(editor)
    return card


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
        table.setShowGrid(True)
    except AttributeError:
        table.setEditTriggers(QTableWidget.NoEditTriggers)
        table.setSelectionBehavior(QTableWidget.SelectRows)
        table.setShowGrid(True)
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
        f"font-size: 18pt; font-weight: 800; color: {ADMIN_ACCENT}; background: transparent;"
    )
    return lbl
