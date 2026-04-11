# ui/styles.py — single import shim for all tabs and windows
from theme.tokens import (
    PRIMARY,
    ACCENT_DARK, ACCENT_DARK_DIM,
    NEON_GREEN, NEON_GREEN_DIM,
    BG_DARK, BG_CARD, BG_INPUT,
    BG_LIGHT, BG_CARD_LIGHT, BG_INPUT_LIGHT,
    TEXT_PRIMARY, TEXT_SECONDARY,
    TEXT_PRIMARY_LIGHT, TEXT_SECONDARY_LIGHT,
    BORDER, BORDER_LIGHT,
    LOW_STOCK_YELLOW, LOW_STOCK_RED,
    RADIUS, RADIUS_SM, RADIUS_LG, RADIUS_XL,
    ACCENT_LIGHT, SECONDARY_LIGHT_DARK,
)
from theme.light import LIGHT_THEME
from theme.dark import DARK_THEME

# Legacy card style strings (kept empty — styling is now done per-widget)
CARD_STYLE          = ""
CARD_STYLE_GLOW     = ""
CARD_STYLE_LOW      = ""
CARD_STYLE_CRITICAL = ""
