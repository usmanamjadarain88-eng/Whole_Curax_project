# ─────────────────────────────────────────────────────────────────────────────
#  CuraX Dark Theme QSS — exact match to .dark-app in curax_design_preview.html
# ─────────────────────────────────────────────────────────────────────────────
from theme.tokens import (
    PRIMARY, BG_DARK, BG_CARD, BG_INPUT, BORDER,
    TEXT_PRIMARY, TEXT_SECONDARY, ACCENT_DARK, ACCENT_DARK_DIM,
    SIDEBAR_BG_DARK, SIDEBAR_BG_DARK2, SIDEBAR_BORDER_D, TAB_DARK_ACTIVE_BG,
    RADIUS, RADIUS_SM, RADIUS_LG, RADIUS_XL, LOW_STOCK_RED,
)

DARK_THEME = f"""
/* ── Base window ───────────────────────────────────────────────── */
QMainWindow {{
    background: transparent;
    color: {TEXT_PRIMARY};
    font-family: "Segoe UI", "DM Sans", system-ui, sans-serif;
    font-size: 10pt;
}}
QWidget {{
    color: {TEXT_PRIMARY};
    font-family: "Segoe UI", "DM Sans", system-ui, sans-serif;
    font-size: 10pt;
    background: transparent;
}}
QDialog {{
    background-color: {BG_CARD};
    color: {TEXT_PRIMARY};
    font-family: "Segoe UI", "DM Sans", system-ui, sans-serif;
    font-size: 10pt;
}}

/* ── Tab bar — layered bar + clearer hover / selection ─────────── */
QTabWidget::pane {{
    border: 1px solid rgba(45, 212, 191, 0.14);
    border-radius: 0 0 16px 0;
    top: -2px;
    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
        stop:0 {BG_INPUT}, stop:1 #060D16);
}}
QTabBar {{
    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
        stop:0 #050A10, stop:1 #0B1522);
    border-bottom: 1px solid rgba(44, 71, 96, 0.55);
}}
QTabBar::tab {{
    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
        stop:0 #152536, stop:1 #101C2C);
    color: {TEXT_SECONDARY};
    border: 1px solid rgba(44, 71, 96, 0.85);
    border-bottom: none;
    padding: 8px 18px;
    border-radius: 9px 9px 0 0;
    font-weight: 600;
    font-size: 9pt;
    margin-right: 3px;
}}
QTabBar::tab:selected {{
    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
        stop:0 {TAB_DARK_ACTIVE_BG}, stop:1 #122030);
    color: {ACCENT_DARK};
    border: 1px solid rgba(45, 212, 191, 0.35);
    border-bottom: 2px solid {ACCENT_DARK};
    font-weight: 700;
}}
QTabBar::tab:hover:!selected {{
    background: #1A3048;
    color: {TEXT_PRIMARY};
    border: 1px solid rgba(45, 212, 191, 0.22);
}}

/* ── Labels ─────────────────────────────────────────────────────── */
QLabel {{ color: {TEXT_PRIMARY}; background: transparent; }}

/* ── Buttons — border + hover “lift” for feedback ───────────────── */
QPushButton {{
    background-color: {PRIMARY};
    color: #ffffff;
    border: 1px solid #0F766E;
    padding: 9px 24px;
    border-radius: {RADIUS_SM};
    font-weight: 700; font-size: 9pt;
}}
QPushButton:hover  {{
    background-color: #14B8A6;
    border: 1px solid #5EEAD4;
}}
QPushButton:pressed {{
    background-color: #0F766E;
    border: 1px solid #0D9488;
}}
QPushButton:disabled {{
    background-color: #162030; color: #3D5565; border: 1px solid #1E3040;
}}
QPushButton#danger {{
    background-color: #7F1D1D; color: #FEE2E2;
    border: 1px solid #991B1B;
}}
QPushButton#danger:hover {{
    background-color: {LOW_STOCK_RED}; color: #fff;
    border: 1px solid #FCA5A5;
}}

/* ── Form inputs (matching .dark-app .form-input) ──────────────── */
QLineEdit, QSpinBox, QDoubleSpinBox,
QComboBox, QPlainTextEdit, QTextEdit,
QDateEdit, QTimeEdit {{
    background-color: {BG_INPUT};
    color: {TEXT_PRIMARY};
    border: 1px solid {BORDER};
    border-radius: {RADIUS_SM};
    padding: 7px 10px;
    font-size: 9pt;
    selection-background-color: {PRIMARY};
    selection-color: #ffffff;
    min-height: 28px;
}}
QLineEdit:focus, QSpinBox:focus, QComboBox:focus,
QPlainTextEdit:focus, QTextEdit:focus,
QDateEdit:focus, QTimeEdit:focus {{
    border: 2px solid {ACCENT_DARK};
    background-color: #0C1828;
}}
QComboBox::drop-down {{
    border: none; width: 24px; background: transparent;
}}
QComboBox QAbstractItemView {{
    background: {BG_CARD};
    color: {TEXT_PRIMARY};
    border: 1px solid {BORDER};
    border-radius: {RADIUS_SM};
    selection-background-color: #0D4F49;
    selection-color: {TEXT_PRIMARY};
    outline: none; padding: 4px;
}}

/* ── Group boxes ─────────────────────────────────────────────────── */
QGroupBox {{
    color: {ACCENT_DARK};
    border: 1px solid rgba(45, 212, 191, 0.22);
    border-radius: {RADIUS_LG};
    margin-top: 18px;
    padding: 18px 16px 14px 16px;
    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
        stop:0 {BG_CARD}, stop:1 #0E1928);
    font-weight: 700; font-size: 10pt;
}}
QGroupBox::title {{
    subcontrol-origin: margin;
    left: 14px; padding: 0 8px;
    background: {BG_CARD};
    color: {ACCENT_DARK};
    font-size: 10pt; font-weight: 800;
}}

/* ── Checkboxes & Radio ──────────────────────────────────────────── */
QCheckBox, QRadioButton {{
    color: {TEXT_PRIMARY};
    spacing: 8px; font-size: 9pt; background: transparent;
}}
QCheckBox::indicator {{
    width: 14px; height: 14px;
    border: 1px solid rgba(45, 212, 191, 0.35);
    border-radius: 3px; background: {BG_INPUT};
}}
QCheckBox::indicator:checked {{
    background-color: {PRIMARY}; border-color: #5EEAD4;
}}
QRadioButton::indicator {{
    width: 14px; height: 14px;
    border: 1px solid rgba(45, 212, 191, 0.35);
    border-radius: 7px; background: {BG_INPUT};
}}
QRadioButton::indicator:checked {{
    background-color: {PRIMARY}; border-color: #5EEAD4;
}}

/* ── Tables ──────────────────────────────────────────────────────── */
QTableWidget {{
    background: {BG_INPUT};
    color: #CBD5E1;
    border: 1px solid rgba(44, 71, 96, 0.9);
    border-radius: {RADIUS};
    gridline-color: rgba(44, 71, 96, 0.65);
    selection-background-color: #0D4F49;
    selection-color: {TEXT_PRIMARY};
    alternate-background-color: #0C1826;
}}
QTableWidget::item {{ padding: 6px; color: #CBD5E1; }}
QTableWidget::item:selected {{ background: #0D4F49; color: {TEXT_PRIMARY}; }}
QHeaderView::section {{
    background: {BG_CARD};
    color: {ACCENT_DARK};
    padding: 7px 12px;
    border: none; border-bottom: 1px solid {BORDER};
    font-weight: 700; font-size: 9pt;
}}

/* ── List widget ─────────────────────────────────────────────────── */
QListWidget {{
    background: {BG_INPUT};
    color: #CBD5E1;
    border: 1px solid {BORDER};
    border-radius: {RADIUS}; padding: 4px; outline: none;
}}
QListWidget::item {{
    padding: 7px 12px; border-radius: 7px;
    margin: 2px; color: #CBD5E1;
}}
QListWidget::item:selected {{ background: #0D4F49; color: {ACCENT_DARK}; }}
QListWidget::item:hover {{ background: #142030; }}

/* ── Scroll bars ─────────────────────────────────────────────────── */
QScrollBar:vertical {{
    background: {BG_CARD}; width: 8px; border-radius: 4px; margin: 0;
}}
QScrollBar::handle:vertical {{
    background: #2A4A62; border-radius: 4px; min-height: 24px;
}}
QScrollBar::handle:vertical:hover {{ background: {ACCENT_DARK_DIM}; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
QScrollBar:horizontal {{
    background: {BG_CARD}; height: 8px; border-radius: 4px;
}}
QScrollBar::handle:horizontal {{
    background: #1E3A4A; border-radius: 4px; min-width: 24px;
}}
QScrollArea {{ border: none; background: transparent; }}

/* ── Status bar ──────────────────────────────────────────────────── */
QStatusBar {{
    background: rgba(8, 14, 24, 0.92);
    color: {ACCENT_DARK};
    border-top: 1px solid rgba(45, 212, 191, 0.2);
    padding: 0 16px; font-size: 8pt;
}}

/* ── Tooltip ─────────────────────────────────────────────────────── */
QToolTip {{
    background-color: {BG_CARD};
    color: {TEXT_PRIMARY};
    border: 1px solid {ACCENT_DARK};
    border-radius: 6px; padding: 5px 10px; font-size: 8pt;
}}

/* ── Slider ──────────────────────────────────────────────────────── */
QSlider::groove:horizontal {{
    background: {BORDER}; height: 5px; border-radius: 3px;
}}
QSlider::handle:horizontal {{
    background: {ACCENT_DARK}; border: 2px solid {BG_DARK};
    width: 16px; height: 16px; border-radius: 8px; margin: -6px 0;
}}
QSlider::sub-page:horizontal {{ background: {PRIMARY}; border-radius: 3px; }}

/* ── Sidebar (matching .dark-app .sidebar) ───────────────────────── */
QFrame#sidebarFrame, QWidget#leftColumn {{
    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
        stop:0 {SIDEBAR_BG_DARK}, stop:1 {SIDEBAR_BG_DARK2});
    border-right: 1px solid {SIDEBAR_BORDER_D};
}}

/* ── Message dialog ──────────────────────────────────────────────── */
/* Same as light: tab-scope QWidget background must not wipe dialog button contrast. */
QMessageBox {{ background-color: {BG_CARD}; }}
QMessageBox QLabel {{ color: {TEXT_PRIMARY}; font-size: 10pt; }}
QMessageBox QPushButton,
QMessageBox QDialogButtonBox QPushButton {{
    background-color: {PRIMARY};
    color: #ffffff;
    border: 1px solid #14B8A6;
    padding: 6px 20px;
    border-radius: {RADIUS_SM};
    font-weight: 700;
    font-size: 9pt;
    min-width: 76px;
    min-height: 26px;
}}
QMessageBox QPushButton:hover,
QMessageBox QDialogButtonBox QPushButton:hover {{
    background-color: #14B8A6;
    color: #ffffff;
}}
QMessageBox QPushButton:pressed,
QMessageBox QDialogButtonBox QPushButton:pressed {{
    background-color: #0F766E;
    color: #ffffff;
}}

/* ── Calendar ────────────────────────────────────────────────────── */
QCalendarWidget {{ background-color: {BG_CARD}; color: {TEXT_PRIMARY}; }}
QCalendarWidget QWidget#qt_calendar_navigationbar {{
    background-color: #0D4F49; color: {ACCENT_DARK}; min-height: 38px; padding: 4px;
}}
QCalendarWidget QToolButton {{
    background-color: transparent; color: {ACCENT_DARK};
    border: none; padding: 5px 10px; font-weight: 600;
}}
QCalendarWidget QToolButton:hover {{
    background-color: rgba(45,212,191,0.15); border-radius: 5px;
}}
QCalendarWidget QSpinBox {{
    background-color: transparent; color: {ACCENT_DARK};
    border: none; font-weight: 600;
}}
QCalendarWidget QAbstractItemView {{
    background-color: {BG_CARD}; color: {TEXT_PRIMARY};
    selection-background-color: #0D4F49; selection-color: {ACCENT_DARK};
    gridline-color: {BORDER}; font-size: 11px; outline: none;
}}
QCalendarWidget QAbstractItemView::item:hover {{
    background-color: #0D4F49; border: 1px solid {ACCENT_DARK}; border-radius: 4px;
}}
QCalendarWidget QAbstractItemView::item:selected {{
    background-color: {PRIMARY}; color: #fff; border-radius: 4px;
}}
"""
