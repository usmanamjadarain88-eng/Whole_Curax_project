# ─────────────────────────────────────────────────────────────────────────────
#  CuraX Design Tokens — v4.0  (exact match to curax-locked.html & curax_design_preview.html)
# ─────────────────────────────────────────────────────────────────────────────
# Light palette (from HTML :root variables)
TEAL_DEEP    = "#0A3D38"   # --teal-deep  (darkest teal, headings on light)
TEAL_MID     = "#0D6B61"   # --teal-mid / --deep  (labels, sidebar headings)
TEAL_VIVID   = "#12A896"   # --teal-vivid (accents, tab active borders)
TEAL_GLOW    = "#1FD4BF"   # --teal-glow  (shimmer, orbs)
TEAL_LIGHT_HL= "#A8F0E8"   # --teal-light (shimmer ends)
CREAM        = "#F0FAF8"   # --cream / --bg  (main window bg)
BG_PALE      = "#E8F5F2"   # body bg in design preview

# Primary teal used in design_preview
PRIMARY      = "#0D9488"   # --teal
PRIMARY_HOVER= "#0F766E"   # --teal-dark
PRIMARY_LIGHT= "#14B8A6"   # --teal-light in preview
TEAL_GLOW2   = "#2DD4BF"   # --teal-glow in preview (dark mode accent)
TEAL_PALE    = "#E2F2EE"   # --teal-pale

# ── Light mode background/card tokens ────────────────────────────────────────
BG_LIGHT        = "transparent"  # main window bg painted in paintEvent
BG_CARD_LIGHT   = "#FFFFFF"
BG_INPUT_LIGHT  = "#FFFFFF"
BORDER_LIGHT    = "#B2D8D4"   # --border
ACCENT_LIGHT    = "#0D6B61"   # --deep
TEXT_PRIMARY_LIGHT  = "#0F172A"   # --text
TEXT_SECONDARY_LIGHT = "#475569"  # --muted
SECONDARY_LIGHT_DARK = "#334155"

# Sidebar light
SIDEBAR_BG_LIGHT  = "#F4FAF8"   # linear-gradient start
SIDEBAR_BG_LIGHT2 = "#ECF7F3"   # linear-gradient end
SIDEBAR_BORDER_L  = "#C6E0DA"

# ── Dark mode tokens (layered surfaces + teal-tinted chrome for depth) ─────
BG_DARK      = "#070C14"   # deepest layer (window base)
BG_CARD      = "#121E30"   # raised cards / dialogs
BG_INPUT     = "#0A1524"   # inset fields & tab content
BORDER       = "#2C4760"   # visible structure without harsh contrast
TEXT_PRIMARY     = "#EEF2F7"
TEXT_SECONDARY   = "#9BB0C4"
ACCENT_DARK      = "#2DD4BF"
ACCENT_DARK_DIM  = "#14B8A6"

# Sidebar dark (subtle vertical depth)
SIDEBAR_BG_DARK   = "#101B2C"
SIDEBAR_BG_DARK2  = "#080F18"
SIDEBAR_BORDER_D  = "#243E52"

# Tab dark active (reads as “lit” strip)
TAB_DARK_ACTIVE_BG = "#162A3D"

# ── Legacy aliases (for theme_toggle.py and older imports) ────────────────────
NEON_GREEN      = TEAL_MID
NEON_GREEN_GLOW = "rgba(13, 107, 97, 0.25)"
NEON_GREEN_DIM  = PRIMARY

# ── Semantic ──────────────────────────────────────────────────────────────────
LOW_STOCK_YELLOW = "#F59E0B"
LOW_STOCK_RED    = "#EF4444"
SUCCESS  = "#10B981"
WARNING  = "#F59E0B"
ERROR    = "#EF4444"
INFO     = "#3B82F6"

# ── Spacing ───────────────────────────────────────────────────────────────────
SPACE_4  = "4px";  SPACE_8  = "8px";  SPACE_12 = "12px"
SPACE_16 = "16px"; SPACE_20 = "20px"; SPACE_24 = "24px"
SPACE_32 = "32px"; SPACE_48 = "48px"

# ── Radius ────────────────────────────────────────────────────────────────────
RADIUS      = "12px"
RADIUS_SM   = "8px"
RADIUS_LG   = "16px"
RADIUS_XL   = "20px"
RADIUS_FULL = "9999px"

# ── Typography ────────────────────────────────────────────────────────────────
FONT_XS  = "10px"; FONT_SM   = "12px"; FONT_BASE = "14px"
FONT_MD  = "16px"; FONT_LG   = "18px"; FONT_XL   = "24px"
FONT_2XL = "32px"

# ── Charts ────────────────────────────────────────────────────────────────────
CHART_COLORS = ["#0D9488","#3B82F6","#8B5CF6","#F59E0B","#EC4899","#14B8A6"]

# ── Sidebar dimensions ────────────────────────────────────────────────────────
SIDEBAR_WIDTH     = "260px"
SIDEBAR_COLLAPSED = "72px"
SIDEBAR_ACTIVE_LIGHT = "#D0F0EA"
SIDEBAR_ACTIVE_DARK  = "#0D4F49"
