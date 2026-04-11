# ─────────────────────────────────────────────────────────────────────────────
#  CuraX Light Theme QSS — exact match to curax-locked.html + curax_design_preview.html
# ─────────────────────────────────────────────────────────────────────────────
from theme.tokens import (
    PRIMARY, PRIMARY_HOVER, TEAL_MID, TEAL_VIVID, TEAL_DEEP, TEAL_PALE,
    BG_LIGHT, BG_CARD_LIGHT, BG_INPUT_LIGHT, BORDER_LIGHT,
    TEXT_PRIMARY_LIGHT, TEXT_SECONDARY_LIGHT, ACCENT_LIGHT,
    SIDEBAR_BG_LIGHT, SIDEBAR_BG_LIGHT2, SIDEBAR_BORDER_L,
    RADIUS, RADIUS_SM, RADIUS_LG, RADIUS_XL, LOW_STOCK_RED,
)

LIGHT_THEME = f"""
/* ── Base window ───────────────────────────────────────────────── */
QMainWindow {{
    background: transparent;
    color: {TEXT_PRIMARY_LIGHT};
    font-family: "Segoe UI", "DM Sans", system-ui, sans-serif;
    font-size: 10pt;
}}
QWidget {{
    color: {TEXT_PRIMARY_LIGHT};
    font-family: "Segoe UI", "DM Sans", system-ui, sans-serif;
    font-size: 10pt;
    background: transparent;
}}
QDialog {{
    background-color: {BG_CARD_LIGHT};
    color: {TEXT_PRIMARY_LIGHT};
    font-family: "Segoe UI", "DM Sans", system-ui, sans-serif;
    font-size: 10pt;
}}

/* ── Tab bar ───────────────────────────────────────────────────── */
QTabWidget::pane {{
    border: 2px solid #c8c8c8;
    border-radius: 0 0 12px 0;
    top: -2px;
    background: {BG_CARD_LIGHT};
}}
QTabBar {{
    background: {BG_LIGHT};
}}
QTabBar::tab {{
    background: #f3f4f6;
    color: #6b7280;
    border: 1.5px solid #c8c8c8;
    border-bottom: 1.5px solid #c8c8c8;
    padding: 7px 16px;
    border-radius: 8px 8px 0 0;
    font-weight: 600;
    font-size: 9pt;
    margin-right: 2px;
}}
QTabBar::tab:selected {{
    background: #ffffff;
    color: {PRIMARY};
    border: 1.5px solid #b0b0b0;
    border-bottom: 2.5px solid {PRIMARY};
    font-weight: 700;
}}
QTabBar::tab:hover:!selected {{
    background: #e9ecef;
    color: #374151;
    border-bottom: 1.5px solid #aaaaaa;
}}

/* ── Tab content area ──────────────────────────────────────────── */
QScrollArea {{
    background-color: {BG_CARD_LIGHT};
    border: none;
}}
QTabWidget QWidget, QTabWidget QScrollArea {{
    background-color: {BG_CARD_LIGHT};
}}

/* ── Labels ─────────────────────────────────────────────────────── */
QLabel {{
    color: {TEXT_PRIMARY_LIGHT};
    background: transparent;
}}

/* ── Buttons ────────────────────────────────────────────────────── */
QPushButton {{
    background-color: {PRIMARY};
    color: #ffffff;
    border: 1.5px solid {PRIMARY_HOVER};
    padding: 9px 24px;
    border-radius: {RADIUS_SM};
    font-weight: 700;
    font-size: 9pt;
}}
QPushButton:hover  {{
    background-color: {PRIMARY_HOVER};
    border-color: #085F58;
    color: #ffffff;
}}
QPushButton:pressed {{ background-color: #085F58; border-color: #064E49; color: #ffffff; }}
QPushButton:disabled {{
    background-color: #e5e7eb;
    color: #6b7280;
    border-color: #b0b0b0;
}}
QPushButton#danger {{
    background-color: #dc2626;
    color: #ffffff;
    border-color: #b91c1c;
}}
QPushButton#danger:hover {{ background-color: #b91c1c; color: #ffffff; }}
QPushButton:checked {{
    background-color: {PRIMARY};
    color: #ffffff;
    border-color: {PRIMARY_HOVER};
}}

/* ── Form inputs (matching .form-input from HTML) ──────────────── */
QLineEdit, QSpinBox, QDoubleSpinBox,
QComboBox, QPlainTextEdit, QTextEdit,
QDateEdit, QTimeEdit {{
    background-color: {BG_INPUT_LIGHT};
    color: {TEXT_PRIMARY_LIGHT};
    border: 1.5px solid #c8c8c8;
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
    border: 1.5px solid {PRIMARY};
    background-color: #FFFFFF;
}}
QComboBox::drop-down {{
    border: none; width: 24px; background: transparent;
}}
QComboBox QAbstractItemView {{
    background: {BG_CARD_LIGHT};
    color: {TEXT_PRIMARY_LIGHT};
    border: 1.5px solid #c8c8c8;
    border-radius: {RADIUS_SM};
    selection-background-color: {TEAL_PALE};
    selection-color: {TEAL_MID};
    outline: none;
    padding: 4px;
}}

/* ── Group boxes ─────────────────────────────────────────────────── */
QGroupBox {{
    color: #111827;
    border: 1.5px solid #c8c8c8;
    border-radius: {RADIUS_LG};
    margin-top: 18px;
    padding: 18px 16px 14px 16px;
    background-color: #ffffff;
    font-weight: 700;
    font-size: 10pt;
}}
QGroupBox::title {{
    subcontrol-origin: margin;
    left: 14px; padding: 0 8px;
    background: #ffffff;
    color: #0D9488;
    font-size: 10pt; font-weight: 800;
}}

/* ── Checkboxes & Radio ──────────────────────────────────────────── */
QCheckBox, QRadioButton {{
    color: {TEXT_PRIMARY_LIGHT};
    spacing: 8px; font-size: 9pt; background: transparent;
}}
QCheckBox::indicator {{
    width: 14px; height: 14px;
    border: 1.5px solid #c8c8c8;
    border-radius: 3px;
    background: {BG_INPUT_LIGHT};
}}
QCheckBox::indicator:checked {{
    background-color: {PRIMARY};
    border-color: {PRIMARY};
}}
QRadioButton::indicator {{
    width: 14px; height: 14px;
    border: 1.5px solid #c8c8c8;
    border-radius: 7px;
    background: {BG_INPUT_LIGHT};
}}
QRadioButton::indicator:checked {{
    background-color: {PRIMARY};
    border-color: {PRIMARY};
}}

/* ── Tables ──────────────────────────────────────────────────────── */
QTableWidget {{
    background: {BG_CARD_LIGHT};
    color: {TEXT_PRIMARY_LIGHT};
    border: 1.5px solid #c8c8c8;
    border-radius: {RADIUS};
    gridline-color: #E2F2EE;
    selection-background-color: {TEAL_PALE};
    selection-color: {TEAL_MID};
    alternate-background-color: #F6FDFB;
}}
QTableWidget::item {{ padding: 6px; color: {TEXT_PRIMARY_LIGHT}; }}
QTableWidget::item:selected {{
    background-color: {TEAL_PALE};
    color: {TEAL_MID};
}}
QHeaderView::section {{
    background: #f3f4f6;
    color: #111827;
    padding: 7px 12px;
    border: none; border-bottom: 2px solid #c8c8c8;
    font-weight: 700; font-size: 9pt;
}}

/* ── List widget ─────────────────────────────────────────────────── */
QListWidget {{
    background: {BG_CARD_LIGHT};
    color: {TEXT_PRIMARY_LIGHT};
    border: 1.5px solid #c8c8c8;
    border-radius: {RADIUS}; padding: 4px; outline: none;
}}
QListWidget::item {{
    padding: 7px 12px; border-radius: 7px;
    margin: 2px; color: {TEXT_PRIMARY_LIGHT};
}}
QListWidget::item:selected {{ background: {TEAL_PALE}; color: {TEAL_MID}; }}
QListWidget::item:hover {{ background: #E8F5F2; }}

/* ── Scroll bars ─────────────────────────────────────────────────── */
QScrollBar:vertical {{
    background: #E8F5F2; width: 8px; border-radius: 4px; margin: 0;
}}
QScrollBar::handle:vertical {{
    background: #99D9CF; border-radius: 4px; min-height: 24px;
}}
QScrollBar::handle:vertical:hover {{ background: {PRIMARY}; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
QScrollBar:horizontal {{
    background: #E8F5F2; height: 8px; border-radius: 4px;
}}
QScrollBar::handle:horizontal {{
    background: #99D9CF; border-radius: 4px; min-width: 24px;
}}
QScrollArea {{ border: none; background: transparent; }}

/* ── Topbar ──────────────────────────────────────────────────────── */
QFrame#topBar {{
    background-color: #ffffff;
    border-bottom: 2px solid #c8c8c8;
}}

/* ── Status bar (matching bottom bar from HTML) ─────────────────── */
QStatusBar {{
    background: #ffffff;
    color: {TEXT_SECONDARY_LIGHT};
    border-top: 2px solid #c8c8c8;
    padding: 0 16px; font-size: 8pt;
}}

/* ── Tooltip ─────────────────────────────────────────────────────── */
QToolTip {{
    background-color: {TEXT_PRIMARY_LIGHT};
    color: #E2E8F0;
    border: 1px solid {PRIMARY};
    border-radius: 6px; padding: 5px 10px; font-size: 8pt;
}}

/* ── Slider ──────────────────────────────────────────────────────── */
QSlider::groove:horizontal {{
    background: {BORDER_LIGHT}; height: 5px; border-radius: 3px;
}}
QSlider::handle:horizontal {{
    background: {PRIMARY}; border: 2px solid #fff;
    width: 16px; height: 16px; border-radius: 8px; margin: -6px 0;
}}
QSlider::sub-page:horizontal {{ background: {PRIMARY}; border-radius: 3px; }}

/* ── Sidebar ─────────────────────────────────────────────────────── */
QWidget#leftColumn {{
    background-color: #ffffff;
    border-right: 2px solid #c8c8c8;
}}
QFrame#sidebarFrame {{
    background-color: #ffffff;
    border-right: 2px solid #c8c8c8;
}}

/* ── Message dialog ──────────────────────────────────────────────── */
/* QTabWidget QWidget background rule is more specific than plain QPushButton and was
   painting QMessageBox OK/Cancel buttons white while text stayed white → invisible. */
QMessageBox {{ background-color: #F4FAF8; }}
QMessageBox QLabel {{ color: {TEXT_PRIMARY_LIGHT}; font-size: 10pt; }}
QMessageBox QPushButton,
QMessageBox QDialogButtonBox QPushButton {{
    background-color: {PRIMARY};
    color: #ffffff;
    border: 1.5px solid {PRIMARY_HOVER};
    padding: 6px 20px;
    border-radius: {RADIUS_SM};
    font-weight: 700;
    font-size: 9pt;
    min-width: 76px;
    min-height: 26px;
}}
QMessageBox QPushButton:hover,
QMessageBox QDialogButtonBox QPushButton:hover {{
    background-color: {PRIMARY_HOVER};
    border-color: #085F58;
    color: #ffffff;
}}
QMessageBox QPushButton:pressed,
QMessageBox QDialogButtonBox QPushButton:pressed {{
    background-color: #085F58;
    color: #ffffff;
}}

/* ── Calendar ────────────────────────────────────────────────────── */
QCalendarWidget {{ background-color: {BG_CARD_LIGHT}; color: {TEXT_PRIMARY_LIGHT}; }}
QCalendarWidget QWidget#qt_calendar_navigationbar {{
    background-color: {PRIMARY}; color: #fff; min-height: 38px; padding: 4px;
}}
QCalendarWidget QToolButton {{
    background-color: transparent; color: #fff;
    border: none; padding: 5px 10px; font-weight: 600;
}}
QCalendarWidget QToolButton:hover {{
    background-color: rgba(255,255,255,0.2); border-radius: 5px;
}}
QCalendarWidget QSpinBox {{
    background-color: transparent; color: #fff;
    border: none; font-weight: 600;
}}
QCalendarWidget QAbstractItemView {{
    background-color: {BG_CARD_LIGHT}; color: {TEXT_PRIMARY_LIGHT};
    selection-background-color: {PRIMARY}; selection-color: #fff;
    gridline-color: {BORDER_LIGHT}; font-size: 11px; outline: none;
}}
QCalendarWidget QAbstractItemView::item:hover {{
    background-color: #CCF0E8; border: 1px solid {PRIMARY}; border-radius: 4px;
}}
QCalendarWidget QAbstractItemView::item:selected {{
    background-color: {PRIMARY}; color: #fff; border-radius: 4px;
}}
"""
