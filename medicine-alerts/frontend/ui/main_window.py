import os
import sys
import math

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    from PyQt6.QtWidgets import (
        QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QTabWidget, QLabel, QLineEdit, QPushButton, QFrame, QStackedWidget,
        QDialog, QDialogButtonBox, QComboBox, QGroupBox, QMessageBox,
        QStatusBar, QGraphicsDropShadowEffect, QSizePolicy, QScrollArea, QRadioButton,
    )
    from PyQt6.QtCore import Qt, QThread, QSize, QPoint, pyqtSignal, QTimer, QRect, QRectF, QEvent, QByteArray
    from PyQt6.QtGui import QFont, QFontMetrics, QPainter, QPainterPath, QLinearGradient, QRadialGradient, QConicalGradient, QBrush, QColor, QPixmap, QMovie, QPen, QImage, QRegion
except ImportError:
    from PyQt5.QtWidgets import (
        QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QTabWidget, QLabel, QLineEdit, QPushButton, QFrame, QStackedWidget,
        QDialog, QDialogButtonBox, QComboBox, QGroupBox, QMessageBox,
        QStatusBar, QGraphicsDropShadowEffect, QSizePolicy, QScrollArea, QRadioButton,
    )
    from PyQt5.QtCore import Qt, QThread, QSize, QPoint, pyqtSignal, QTimer, QRect, QRectF, QEvent, QByteArray
    from PyQt5.QtGui import QFont, QFontMetrics, QPainter, QPainterPath, QLinearGradient, QRadialGradient, QConicalGradient, QBrush, QColor, QPixmap, QMovie, QPen, QImage, QRegion

try:
    from PyQt6.QtSvg import QSvgRenderer
except ImportError:
    try:
        from PyQt5.QtSvg import QSvgRenderer
    except ImportError:
        QSvgRenderer = None

from ui.styles import (
    DARK_THEME, LIGHT_THEME, NEON_GREEN, PRIMARY, TEXT_SECONDARY,
    ACCENT_LIGHT, SECONDARY_LIGHT_DARK,
)
try:
    from ui.styles import ACCENT_DARK
except ImportError:
    ACCENT_DARK = ACCENT_LIGHT
from ui.widgets.theme_toggle import ThemeToggle
from ui.tabs.admin_dashboard_tab import AdminDashboardTab
from ui.tabs.admin_alerts_tab import AdminAlertsTab
from ui.tabs.admin_hub_settings_tab import AdminHubSettingsTab
from ui.tabs.admin_care_overview_tab import AdminCareOverviewTab
from ui.tabs.medical_reminders_tab import MedicalRemindersTab
from ui.tabs.admin_users_tab import AdminUsersTab
from ui.tabs.admin_reports_tab import AdminReportsTab
from ui.tabs.admin_connections_tab import AdminConnectionsTab
from auth.pin_dialog import PinDialog

# ── SVG lock icon ─────────────────────────────────────────────────────────────
_LOCK_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    '<path fill="{color}" d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10'
    'c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 '
    '2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/>'
    '</svg>'
)
# Teal → mint → warm gold (brand-friendly lock, not flat grey)
_LOCK_SVG_GRADIENT = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    '<defs><linearGradient id="curaxLockGrad" x1="0%" y1="0%" x2="100%" y2="100%">'
    '<stop offset="0%" stop-color="#0D9488"/><stop offset="45%" stop-color="#2DD4BF"/>'
    '<stop offset="85%" stop-color="#14B8A6"/><stop offset="100%" stop-color="#F59E0B"/>'
    '</linearGradient></defs>'
    '<path fill="url(#curaxLockGrad)" d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10'
    'c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 '
    '2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/>'
    "</svg>"
)
# Same brand colours, cycled on the lock for fast “event lighting” blink
_LOCK_PARTY_COLORS = ("#0D9488", "#2DD4BF", "#14B8A6", "#F59E0B")
_LOCK_PATH_D = (
    "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10"
    "c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 "
    "2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"
)


def _lock_svg_party_frame(frame_index: int) -> str:
    """Rotate gradient stops through brand palette (frame_index cycles 0..n-1)."""
    cols = _LOCK_PARTY_COLORS
    n = len(cols)
    k = frame_index % n
    ordered = [cols[(k + i) % n] for i in range(n)]
    grad_id = f"curaxLockG{k}"
    offs = ("0%", "32%", "64%", "100%")
    flash = (frame_index // n) % 2
    flash_op = "1" if flash else "0.82"
    parts = []
    for i in range(n):
        op = f' stop-opacity="{flash_op}"' if i == n - 1 else ""
        parts.append(f'<stop offset="{offs[i]}" stop-color="{ordered[i]}"{op}/>')
    stops = "".join(parts)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
        f'<defs><linearGradient id="{grad_id}" x1="0%" y1="0%" x2="100%" y2="100%">'
        f"{stops}</linearGradient></defs>"
        f'<path fill="url(#{grad_id})" d="{_LOCK_PATH_D}"/>'
        "</svg>"
    )


def _lock_icon_pixmap_party(size_px: int, frame_index: int):
    """Lock icon with party-style colour chase (same hues as brand gradient)."""
    if QSvgRenderer is None:
        return None
    svg = _lock_svg_party_frame(frame_index)
    r = QSvgRenderer(QByteArray(svg.encode("utf-8")))
    if not r.isValid():
        return None
    fmt = getattr(QImage.Format, "Format_ARGB32", getattr(QImage, "Format_ARGB32", 4))
    img = QImage(size_px * 2, size_px * 2, fmt)
    img.fill(0)
    painter = QPainter(img)
    try:
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
    except AttributeError:
        painter.setRenderHint(QPainter.Antialiasing, True)
    r.render(painter)
    painter.end()
    pix = QPixmap.fromImage(img)
    if hasattr(Qt, "AspectRatioMode"):
        pix = pix.scaled(size_px, size_px, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
    else:
        pix = pix.scaled(size_px, size_px)
    return pix
LOCK_ICON_SIZE_PX = 48
# Must match QSS border-radius on QFrame#lockedCard (mask clips children to this curve)
LOCKED_CARD_CORNER_RADIUS_PX = 20


def _lock_icon_pixmap_branded(size_px: int = 72):
    """Padlock with teal–mint–gold gradient for the locked screen."""
    if QSvgRenderer is None:
        return None
    r = QSvgRenderer(QByteArray(_LOCK_SVG_GRADIENT.encode("utf-8")))
    if not r.isValid():
        return None
    fmt = getattr(QImage.Format, "Format_ARGB32", getattr(QImage, "Format_ARGB32", 4))
    img = QImage(size_px * 2, size_px * 2, fmt)
    img.fill(0)
    painter = QPainter(img)
    try:
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
    except AttributeError:
        painter.setRenderHint(QPainter.Antialiasing, True)
    r.render(painter)
    painter.end()
    pix = QPixmap.fromImage(img)
    if hasattr(Qt, "AspectRatioMode"):
        pix = pix.scaled(size_px, size_px, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
    else:
        pix = pix.scaled(size_px, size_px)
    return pix


def _lock_icon_pixmap(color_hex: str, size_px: int = LOCK_ICON_SIZE_PX):
    if QSvgRenderer is None:
        return None
    svg = _LOCK_SVG.format(color=color_hex)
    r = QSvgRenderer(QByteArray(svg.encode("utf-8")))
    if not r.isValid():
        return None
    fmt = getattr(QImage.Format, "Format_ARGB32", getattr(QImage, "Format_ARGB32", 4))
    img = QImage(size_px * 2, size_px * 2, fmt)
    img.fill(0)
    painter = QPainter(img)
    r.render(painter)
    painter.end()
    pix = QPixmap.fromImage(img)
    if hasattr(Qt, "AspectRatioMode"):
        pix = pix.scaled(size_px, size_px, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
    else:
        pix = pix.scaled(size_px, size_px)
    return pix


def _make_hamburger_pixmap(color_hex, size=22):
    try:
        render_hint = QPainter.RenderHint.Antialiasing
    except AttributeError:
        render_hint = QPainter.Antialiasing
    pix = QPixmap(size, size)
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.setRenderHint(render_hint)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(color_hex))
    w, h = size * 3 // 4, max(2, size // 10)
    x0 = (size - w) // 2
    gap = size // 5
    y0 = (size - (2 * gap + 3 * h)) // 2
    for i in range(3):
        p.drawRoundedRect(x0, y0 + i * (h + gap), w, h, h // 2, h // 2)
    p.end()
    return pix


def _make_close_pixmap(color_hex, size=22):
    try:
        render_hint = QPainter.RenderHint.Antialiasing
    except AttributeError:
        render_hint = QPainter.Antialiasing
    pix = QPixmap(size, size)
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.setRenderHint(render_hint)
    pen = QPen(QColor(color_hex))
    pen.setWidth(max(2, size // 8))
    pen.setCapStyle(Qt.PenCapStyle.RoundCap)
    p.setPen(pen)
    m = size // 4
    p.drawLine(m, m, size - m, size - m)
    p.drawLine(size - m, m, m, size - m)
    p.end()
    return pix


class BluetoothConnectThread(QThread):
    def __init__(self, controller):
        super().__init__()
        self.controller = controller

    def run(self):
        self.controller.connect_bluetooth()


class MenuIconLabel(QLabel):
    clicked = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setStyleSheet("background: transparent; border: none; padding: 0; margin: 0;")

    def mousePressEvent(self, event):
        try:
            btn = Qt.MouseButton.LeftButton
        except AttributeError:
            btn = Qt.LeftButton
        if event.button() == btn:
            self.clicked.emit()
        super().mousePressEvent(event)


class _TopBarMenuHitZone(QWidget):
    """Transparent layer over the hamburger column so the whole topbar strip (including gaps above icon) toggles menu."""

    def __init__(self, main_window, parent=None):
        super().__init__(parent)
        self._mw = main_window
        try:
            self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
        except AttributeError:
            self.setAttribute(Qt.WA_TranslucentBackground, True)
        try:
            self.setCursor(Qt.CursorShape.PointingHandCursor)
        except AttributeError:
            self.setCursor(Qt.PointingHandCursor)
        self.setStyleSheet("background: transparent;")

    def mousePressEvent(self, event):
        try:
            btn = Qt.MouseButton.LeftButton
        except AttributeError:
            btn = Qt.LeftButton
        if event.button() == btn and self._mw is not None:
            self._mw._toggle_sidebar()
        super().mousePressEvent(event)


class PowerButton(QLabel):
    """Power icon button with Sleep/Shutdown popup menu. Visible only when unlocked."""
    sleep_requested = pyqtSignal()
    shutdown_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(28, 28)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setToolTip("Power options")
        self.setStyleSheet("background: transparent; border: none;")
        self._dark = False
        self._draw_icon()

    def _draw_icon(self):
        import math as _math
        color = "#2DD4BF" if self._dark else "#0D9488"
        size = 22
        pm = QPixmap(size, size)
        pm.fill(QColor(0, 0, 0, 0))
        p = QPainter(pm)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)

        cx, cy = size / 2, size / 2
        r_outer = size / 2 - 1.0
        r_inner = r_outer - 3.2          # stroke width ~3px
        stem_w  = 3.2

        col = QColor(color)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(col))

        # ── Arc: 300° ring (60° gap at top-centre) drawn as filled annulus sector
        gap_deg  = 62.0
        start_deg = 90.0 + gap_deg / 2.0   # Qt angles: 0°=right, 90°=top
        sweep_deg = 360.0 - gap_deg

        path = QPainterPath()
        # outer arc
        path.arcMoveTo(cx - r_outer, cy - r_outer, r_outer * 2, r_outer * 2, start_deg)
        path.arcTo(cx - r_outer, cy - r_outer, r_outer * 2, r_outer * 2, start_deg, sweep_deg)
        # line to inner
        end_rad = _math.radians(start_deg + sweep_deg)
        ix = cx + r_inner * _math.cos(-end_rad + _math.pi / 2)
        iy = cy - r_inner * _math.sin(-end_rad + _math.pi / 2)
        path.lineTo(ix, iy)
        # inner arc (reverse)
        path.arcTo(cx - r_inner, cy - r_inner, r_inner * 2, r_inner * 2, start_deg + sweep_deg, -sweep_deg)
        path.closeSubpath()
        p.drawPath(path)

        # ── Stem: rounded rectangle at top centre
        stem_h = r_outer - 1.5
        stem_x = cx - stem_w / 2
        stem_y = cy - stem_h - 0.5
        stem_rect = QRectF(stem_x, stem_y, stem_w, stem_h + 2)
        p.drawRoundedRect(stem_rect, stem_w / 2, stem_w / 2)

        p.end()
        self.setPixmap(pm)

    def set_dark(self, dark: bool):
        self._dark = dark
        self._draw_icon()
        # No border, no background — just the icon
        self.setStyleSheet("background: transparent; border: none;")

    def mousePressEvent(self, event):
        try:
            btn = Qt.MouseButton.LeftButton
        except AttributeError:
            btn = Qt.LeftButton
        if event.button() == btn:
            self._show_menu()
        super().mousePressEvent(event)

    def _show_menu(self):
        try:
            from PyQt6.QtWidgets import QMenu
            from PyQt6.QtGui import QAction
        except ImportError:
            from PyQt5.QtWidgets import QMenu, QAction

        dark = self._dark
        menu = QMenu(self)
        menu.setStyleSheet(f"""
            QMenu {{
                background-color: {"#0d1a2e" if dark else "#ffffff"};
                border: 1.5px solid {"#2DD4BF" if dark else "#0D9488"};
                border-radius: 10px;
                padding: 4px 0px;
            }}
            QMenu::item {{
                padding: 9px 20px 9px 14px;
                font-size: 9pt;
                font-weight: 500;
                color: {"#e2f8f4" if dark else "#0D6B61"};
                border-radius: 6px;
                margin: 2px 4px;
            }}
            QMenu::item:selected {{
                background-color: {"rgba(45,212,191,0.15)" if dark else "rgba(13,148,136,0.10)"};
                color: {"#2DD4BF" if dark else "#0D9488"};
            }}
        """)

        sleep_act = QAction("  🌙  Sleep  (lock screen)", self)
        sleep_act.triggered.connect(self.sleep_requested.emit)

        shut_act  = QAction("  ⏻  Shut Down  (close app)", self)
        shut_act.triggered.connect(self.shutdown_requested.emit)

        menu.addAction(sleep_act)
        menu.addSeparator()
        menu.addAction(shut_act)

        sh = menu.sizeHint()
        mw, mh = max(sh.width(), 1), max(sh.height(), 1)
        win = self.window()
        try:
            win_rect = win.frameGeometry()
        except Exception:
            win_rect = QRect(self.mapToGlobal(QPoint(0, 0)), win.size())
        margin = 8
        # Default: below button, left-aligned with button (menu grows to the right)
        pos = self.mapToGlobal(self.rect().bottomLeft())
        x, y = pos.x(), pos.y()
        if x + mw > win_rect.right() - margin:
            x = win_rect.right() - mw - margin
        if x < win_rect.left() + margin:
            x = win_rect.left() + margin
        if y + mh > win_rect.bottom() - margin:
            y = self.mapToGlobal(self.rect().topLeft()).y() - mh - margin
        if y < win_rect.top() + margin:
            y = win_rect.top() + margin
        pos_clamped = QPoint(int(x), int(y))
        _run = getattr(menu, "exec", None) or getattr(menu, "exec_", None)
        if _run:
            _run(pos_clamped)



# ── Lock icon (professional padlock, above card) ─────────────────────────────────
class _YellowLockIconWidget(QWidget):
    """Padlock in teal with animating inner circular sunburst."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(64, 64)

    def _phase(self):
        p = self.parent()
        while p:
            if hasattr(p, "_card_border_phase"):
                return getattr(p, "_card_border_phase", 0.0)
            p = p.parent() if hasattr(p, "parent") and callable(p.parent) else None
        return 0.0

    def paintEvent(self, event):
        """Teal padlock with inner rotating needles (sunburst)."""
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
            p.setRenderHint(QPainter.SmoothPixmapTransform)
        w, h = float(self.width()), float(self.height())
        cx, cy = w / 2.0, h / 2.0
        parent = self.parent()
        dark = getattr(parent, '_dark_bg', False) if parent else False
        fill_color = QColor("#5EEAD4")
        outline_color = QColor("#0D9488") if not dark else QColor("#2DD4BF")
        size = min(w, h) * 0.76
        left, top = cx - size / 2, cy - size / 2
        body_top = top + size * 0.38
        body_h = size * 0.58
        body_left = left + size * 0.12
        body_w, body_r = size * 0.76, size * 0.12
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(fill_color)
        p.drawRoundedRect(int(body_left), int(body_top), int(body_w), int(body_h), int(body_r), int(body_r))
        p.setPen(QPen(outline_color, max(1.4, size * 0.035)))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRoundedRect(int(body_left), int(body_top), int(body_w), int(body_h), int(body_r), int(body_r))
        shackle_w, shackle_h = size * 0.52, size * 0.40
        sh_left, sh_top = cx - shackle_w / 2, top + size * 0.04
        stroke = max(2.4, size * 0.12)
        try:
            fill_pen = QPen(fill_color, stroke)
            fill_pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        except AttributeError:
            fill_pen = QPen(fill_color, stroke)
            fill_pen.setCapStyle(Qt.RoundCap)
        p.setPen(fill_pen)
        p.drawArc(int(sh_left), int(sh_top), int(shackle_w), int(shackle_h * 1.08), 0 * 16, 180 * 16)
        stem_y = sh_top + shackle_h * 0.52
        p.drawLine(int(sh_left + stroke / 2), int(sh_top + shackle_h * 0.5), int(sh_left + stroke / 2), int(stem_y))
        p.drawLine(int(sh_left + shackle_w - stroke / 2), int(sh_top + shackle_h * 0.5), int(sh_left + shackle_w - stroke / 2), int(stem_y))
        try:
            outline_pen = QPen(outline_color, max(1.2, size * 0.03))
            outline_pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        except AttributeError:
            outline_pen = QPen(outline_color, max(1.2, size * 0.03))
            outline_pen.setCapStyle(Qt.RoundCap)
        p.setPen(outline_pen)
        p.drawArc(int(sh_left), int(sh_top), int(shackle_w), int(shackle_h * 1.08), 0 * 16, 180 * 16)
        p.drawLine(int(sh_left + stroke / 2), int(sh_top + shackle_h * 0.5), int(sh_left + stroke / 2), int(stem_y))
        p.drawLine(int(sh_left + shackle_w - stroke / 2), int(sh_top + shackle_h * 0.5), int(sh_left + shackle_w - stroke / 2), int(stem_y))
        phase = self._phase()
        inner_cx, inner_cy = cx, body_top + body_h * 0.42
        inner_r, num_rays = size * 0.18, 12
        ray_inner, ray_outer = size * 0.04, inner_r
        p.save()
        p.translate(inner_cx, inner_cy)
        p.rotate(-phase * 180.0 / math.pi)
        p.translate(-inner_cx, -inner_cy)
        p.setPen(QPen(outline_color, max(1.2, size * 0.028)))
        p.setBrush(Qt.BrushStyle.NoBrush)
        for i in range(num_rays):
            angle = (2.0 * math.pi * i) / num_rays
            x1 = inner_cx + ray_inner * math.cos(angle)
            y1 = inner_cy - ray_inner * math.sin(angle)
            x2 = inner_cx + ray_outer * math.cos(angle)
            y2 = inner_cy - ray_outer * math.sin(angle)
            p.drawLine(int(x1), int(y1), int(x2), int(y2))
        p.restore()
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(outline_color)
        p.drawEllipse(int(inner_cx - ray_inner), int(inner_cy - ray_inner), int(ray_inner * 2), int(ray_inner * 2))
        p.end()


# ── Redesigned Locked Screen ──────────────────────────────────────────────────
class _CardBorderOverlay(QWidget):
    """Draws a thin animating border and subtle inner glow on top of the locked card."""

    def __init__(self, parent=None):
        super().__init__(parent)
        try:
            self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
            self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, True)
        except AttributeError:
            self.setAttribute(Qt.WA_TranslucentBackground, True)
            self.setAttribute(Qt.WA_TransparentForMouseEvents, True)

    def _locked_screen(self):
        p = self.parent()
        while p:
            if isinstance(p, LockedScreen):
                return p
            p = p.parent() if hasattr(p, "parent") and callable(p.parent) else None
        return None

    def paintEvent(self, event):
        ls = self._locked_screen()
        phase = getattr(ls, "_card_border_phase", 0.0) if ls else 0.0
        dark = getattr(ls, "_dark_bg", False) if ls else False
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
            p.setRenderHint(QPainter.SmoothPixmapTransform)
        w, h = self.width(), self.height()
        if w < 10 or h < 10:
            p.end()
            return
        r = 16
        stroke = 1.5
        rect = QRectF(stroke / 2, stroke / 2, w - stroke, h - stroke)
        # Base border: subtle full outline
        base_pen = QPen(QColor(13, 148, 136, 40) if not dark else QColor(45, 212, 191, 50), stroke)
        try:
            base_pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
        except AttributeError:
            try:
                base_pen.setJoinStyle(Qt.RoundJoin)
            except Exception:
                pass
        p.setPen(base_pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRoundedRect(int(rect.x()), int(rect.y()), int(rect.width()), int(rect.height()), r, r)
        # Revolving dash + shimmer + breathing glow
        dash_len=60; gap_len=2400
        offset=(phase/(2.0*math.pi))*(dash_len+gap_len)
        wc=QColor(45,212,191,255) if dark else QColor(13,148,136,230)
        walk_pen=QPen(wc,2.5); walk_pen.setDashPattern([dash_len,gap_len]); walk_pen.setDashOffset(-offset)
        try: walk_pen.setCapStyle(Qt.PenCapStyle.RoundCap); walk_pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
        except AttributeError:
            try: walk_pen.setCapStyle(Qt.RoundCap); walk_pen.setJoinStyle(Qt.RoundJoin)
            except Exception: pass
        p.setPen(walk_pen); p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRoundedRect(int(rect.x()),int(rect.y()),int(rect.width()),int(rect.height()),r,r)
        bar_cx=(phase/(2.0*math.pi))*(w+120)-60; bar_w=max(60,w*0.25); bar_alp=int(180+60*math.sin(phase*3))
        bg2=QLinearGradient(bar_cx-bar_w,0,bar_cx+bar_w,0)
        bg2.setColorAt(0.0,QColor(45,212,191,0)); bg2.setColorAt(0.5,QColor(45,212,191,bar_alp if dark else int(bar_alp*0.65))); bg2.setColorAt(1.0,QColor(45,212,191,0))
        p.setPen(Qt.PenStyle.NoPen); p.setBrush(QBrush(bg2)); p.drawRoundedRect(0,0,w,4,2,2)
        ga2=int(14+10*math.sin(phase*2))
        gc=QColor(45,212,191,ga2) if dark else QColor(13,148,136,ga2)
        glow2=QRadialGradient(w/2.0,h/2.0,max(w,h)*0.55)
        glow2.setColorAt(0.0,QColor(gc.red(),gc.green(),gc.blue(),0)); glow2.setColorAt(0.75,QColor(gc.red(),gc.green(),gc.blue(),0)); glow2.setColorAt(1.0,gc)
        p.setBrush(QBrush(glow2)); p.drawRoundedRect(int(rect.x()),int(rect.y()),int(rect.width()),int(rect.height()),r,r)
        p.end()



class _CardWrapper(QWidget):
    """Wraps the locked card so we can overlay the animating border."""

    def __init__(self, card, parent=None):
        super().__init__(parent)
        self._card = card
        self.setStyleSheet("background: transparent;")
        lo = QVBoxLayout(self)
        lo.setContentsMargins(0, 0, 0, 0)
        lo.setSpacing(0)
        lo.addWidget(card)
        self._overlay = _CardBorderOverlay(self)
        self._overlay.raise_()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._overlay.setGeometry(0, 0, self.width(), self.height())
        self._overlay.raise_()


# ── Redesigned Locked Screen ──────────────────────────────────────────────────

class _LockRingWidget(QWidget):
    """Custom widget: spinning dashed ring + float animation + lock icon."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._phase = 0.0
        self._float_offset = 0
        self._pulse = False
        self._color = "#2DD4BF"
        self._dark = False
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)

    def set_phase(self, p: float):
        self._phase = p
        self.update()

    def set_float_offset(self, offset):
        self._float_offset = float(offset)
        self.update()

    def set_phase_and_float(self, phase: float, offset: float):
        """Combined update — one repaint instead of two per frame."""
        self._phase = phase
        self._float_offset = offset
        self.update()

    def set_pulse(self, on: bool):
        self._pulse = on
        self.update()

    def set_color(self, c: str):
        self._color = "#2DD4BF"
        self.update()

    def set_dark(self, d: bool):
        self._dark = False
        self.update()

    def paintEvent(self, event):
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        cx = w / 2.0
        # Float offset shifts everything vertically
        cy = h / 2.0 + self._float_offset
        base = QColor(self._color)
        light_fill = QColor(240, 253, 250)
        r_outer = min(w, h) / 2.0 - 4
        r_inner = r_outer - 14

        # Single thin circle (minimal, professional)
        ring_pen = QPen(QColor(148, 163, 184), 1.5)
        p.setPen(ring_pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawEllipse(int(cx - r_outer), int(cy - r_outer), int(r_outer * 2), int(r_outer * 2))

        # Inner filled circle + lock
        pen_circle = QPen(base, 2.0)
        p.setPen(pen_circle)
        p.setBrush(light_fill)
        p.drawEllipse(int(cx - r_inner), int(cy - r_inner), int(r_inner * 2), int(r_inner * 2))

        # ── Lock icon (teal outline + light fill — professional) ─────────────────────────────────────────────────
        icon_w = r_inner * 0.70
        ix = cx - icon_w / 2
        iy = cy - icon_w / 2

        # Shackle arc (teal outline)
        sh_x = ix + icon_w * 0.22
        sh_y = iy - icon_w * 0.04
        sh_w = icon_w * 0.56
        sh_h = icon_w * 0.52
        shackle_pen = QPen(base, max(2.5, icon_w * 0.13))
        shackle_pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        p.setPen(shackle_pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        try:
            p.drawArc(int(sh_x), int(sh_y), int(sh_w), int(sh_h), 0 * 16, 180 * 16)
        except Exception:
            pass

        # Body rectangle: light fill + teal border
        bx = ix + icon_w * 0.06
        by = iy + icon_w * 0.42
        bw = icon_w * 0.88
        bh = icon_w * 0.54
        p.setPen(QPen(base, 1.5))
        p.setBrush(light_fill)
        p.drawRoundedRect(int(bx), int(by), int(bw), int(bh), 5, 5)

        # Keyhole
        p.setBrush(light_fill)
        kx = cx - icon_w * 0.11
        ky = by + bh * 0.18
        kw = icon_w * 0.22
        p.drawEllipse(int(kx), int(ky), int(kw), int(kw))
        p.drawRect(int(kx + kw * 0.22), int(ky + kw * 0.48), int(kw * 0.56), int(bh * 0.38))
        p.end()


# ── CuraX App Icon (base64-embedded PNG — same as Android app icon) ──────────
_CURAX_ICON_B64 = (
    "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/BZjfosRX39NNCh64xEQwi014hANOyoKx0Q52fbLQDVtUWPtuWXdCFav36w1KEOSZWhtMMbcid3JjG7bleoktc6r4AUJIQTvvXfOuTo2V/eEICrLkhccIQAMSmmttWmwRpu4ZA4CAV/VdQjO++BOYkzdrzTopa6izvlK5rXJXJeYBEdjnDfPkWp2b9UrxrWGOiq6a2iqCBrv/crPvjmJ2jy3rbrbTTaN+EsS3DpD3vGG0Bnzjtebb2XMX/Syxt/9wpj65vtvtnO5PinY/Ail1sZqM5vaaNP0Z/fN6h2Uttde0zzYBIuv13avaY29jsYSE/9uVOk2V+XfKjFOBZTElqXB+5jk5n3jk9/YZxU2/t5IGlw9vrGFzTEIAYyJ9527bU0djbOw8T2t0Ms10ZvbVuetM/7bZKPfkL1tbjtb3vGm0BnzjtebbzTm+iUvCzeMi94Y8q93L3vO6K++B7RJVtnerfEJjYXWCrRWqCCrRLb28TxPmRfV+qNusSzaqFW2+nXDtt7m//ivODTH6xZZ2RsTA1nVjZtVF7PbNM6jryHmFTSSMbRNRI1KUEo1TU2b7mwisUD8ZTznKXm+Pl/dcnsbm1OzzqB3vAl0xrzj9eZbr8xvutnjrX5urbj57HqluDLmt8Xobwqq6LaXeBQ3WXUAVZBo3bTx3Pg+BVbFwjgV1o1I2oS365/Nqna+TaKPAjS3H4ZvCpvLiyygsKrpbj/nL6l+a93lGg3aQgh4omKdEDuFN41SGzd4u/rnpdb1Zp7bX8pt0fbOmHe8CXTGvOMfhNuNubrFmK/t2d9eTOTWxX2bZc7zdvV15fp+6BfNF5oX3zjO/4k7/hfmS3Z0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dLwS/P8B14U2/EAtdwEAAAAASUVORK5CYII="
)

def _get_curax_icon_pixmap(size: int = 38):
    """Load the CuraX app icon at given size — raw, no clipping, transparent bg."""
    try:
        import base64 as _b64
        raw = _b64.b64decode(_CURAX_ICON_B64)
        img = QImage()
        img.loadFromData(raw)
        try:
            pix = QPixmap.fromImage(img).scaled(
                size, size,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
        except AttributeError:
            pix = QPixmap.fromImage(img).scaled(
                size, size,
                Qt.KeepAspectRatio,
                Qt.SmoothTransformation,
            )
        return pix
    except Exception:
        return None

# ── Gradient title label ──────────────────────────────────────────────────────
class LockedScreen(QWidget):
    """Professional lock screen: yellow lock above card + secured card + stats strip."""

    def __init__(self, parent=None):
        super().__init__(parent)
        # Never register as its own taskbar window
        try:
            self.setWindowFlags(Qt.WindowType.Widget)
        except AttributeError:
            self.setWindowFlags(Qt.Widget)
        self._theme = "light"
        self._dark_bg = False
        self._pulse_phase = 0
        self._pulse_timer = None
        self._ring_phase = 0.0
        self._card_border_phase = 0.0
        self.setMinimumHeight(200)
        try:
            self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, False)
        except AttributeError:
            pass
        try:
            self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        except AttributeError:
            self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)

        # Single 16ms (60fps) timer drives all animation — eliminates jerks
        self._ring_phase = 0.0
        self._float_phase = 0.0
        # Extra animation phases for rich background
        self._scan_phase = 0.0       # scan line: 0..1 top->bottom
        self._pulse_bg_phase = 0.0   # pulse circles expansion
        self._blob_phase = 0.0       # blob drift sine
        self._grid_phase = 0.0       # grid opacity fade
        self._particle_phase = 0.0   # particle float
        # Pre-generate stable particle & pulse positions (seeded)
        import random as _rnd
        _r = _rnd.Random(42)
        self._bg_particles = [
            {
                "x": _r.uniform(0.0, 0.25) if _r.random() > 0.5 else _r.uniform(0.75, 1.0),
                "y_base": _r.uniform(0.1, 0.9),
                "size": _r.uniform(1.5, 4.0),
                "dur": _r.uniform(5.0, 12.0),
                "delay": _r.uniform(0.0, 12.0),
            }
            for _ in range(24)
        ]
        self._anim_timer = QTimer(self)
        self._anim_timer.timeout.connect(self._on_anim_tick)
        self._anim_timer.start(16)
        # Lock icon: fast brand-colour chase (party / event lighting)
        self._lock_party_frame = 0
        self._lock_party_tick = 0

        # Allow our paintEvent to draw background
        try:
            self.setAttribute(Qt.WidgetAttribute.WA_OpaquePaintEvent, True)
        except AttributeError:
            self.setAttribute(Qt.WA_OpaquePaintEvent, True)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(0)
        try:
            layout.setAlignment(Qt.AlignmentFlag.AlignCenter)
        except AttributeError:
            layout.setAlignment(Qt.AlignCenter)

        # ── Icon ring (hidden; kept for compatibility) ─────────────────────────
        self._icon_ring = _LockRingWidget(self)
        self._icon_ring.setFixedSize(136, 136)
        self._icon_ring.setVisible(False)

        # ── Lock icon above the card (simple SVG for new clean design) ──
        self._yellow_lock = _YellowLockIconWidget(self)
        self._yellow_lock.setVisible(False)
        self._lock_svg_label = QLabel(self)
        self._lock_svg_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._lock_svg_label.setStyleSheet("background: transparent; border: none;")
        self._lock_icon_base = 72
        self._lock_svg_label.setFixedSize(self._lock_icon_base, self._lock_icon_base)
        _pm = _lock_icon_pixmap_party(self._lock_icon_base, 0)
        if _pm:
            self._lock_svg_label.setPixmap(_pm)
        self._lock_svg_label.setVisible(True)

        # ── Card (scales with window in resizeEvent) ──────────────────────────
        self._card = QFrame(self)
        self._card.setObjectName("lockedCard")
        self._card.setMinimumWidth(360)
        self._card.setMinimumHeight(160)
        try:
            self._card.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred)
        except AttributeError:
            self._card.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Preferred)
        self._card_layout = QVBoxLayout(self._card)
        self._card_layout.setSpacing(0)
        self._card_layout.setContentsMargins(0, 0, 0, 0)

        # Top brand accent strip (teal → mint → gold)
        self._card_accent = QFrame(self._card)
        self._card_accent.setObjectName("lockedCardAccent")
        self._card_accent.setFixedHeight(5)
        self._card_layout.addWidget(self._card_accent)

        # ── Inner content ──────────────────────
        _inner = QWidget(self._card)
        _inner.setStyleSheet("background: transparent;")
        self._inner_lo = QVBoxLayout(_inner)
        self._inner_lo.setContentsMargins(22, 18, 22, 18)
        self._inner_lo.setSpacing(12)

        self._lock_icon_label = QLabel("LOCKED")
        self._lock_icon_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._lock_icon_label.setObjectName("lockedBadge")
        self._lock_icon_label.setVisible(False)
        self._inner_lo.addWidget(self._lock_icon_label)

        self._eyebrow = QLabel("ACCESS RESTRICTED")
        self._eyebrow.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._eyebrow.setObjectName("lockedEyebrow")
        self._inner_lo.addWidget(self._eyebrow)

        self.center_lock = QLabel("System Locked")
        self.center_lock.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.center_lock.setObjectName("lockedTitle")
        self._inner_lo.addWidget(self.center_lock)

        self._divider = QFrame(self._card)
        self._divider.setFixedSize(96, 4)
        self._divider.setObjectName("lockedDivider")
        self._inner_lo.addWidget(self._divider, alignment=Qt.AlignmentFlag.AlignHCenter)
        self._inner_lo.addSpacing(8)
        _brow = QWidget(self._card)
        _brow.setStyleSheet("background:transparent;")
        _bro_lo = QHBoxLayout(_brow)
        _bro_lo.setContentsMargins(0, 0, 0, 0)
        _bro_lo.setSpacing(10)
        for _bt in ["🔒  HIPAA", "🛡  AES-256", "📡  IoT"]:
            _b = QLabel(_bt)
            _b.setObjectName("secBadge")
            _b.setAlignment(Qt.AlignmentFlag.AlignCenter)
            _bro_lo.addWidget(_b)
        self._badges_row = _brow
        self._inner_lo.addWidget(_brow)
        self._inner_lo.addSpacing(6)

        self._subtitle = QLabel("")
        self._subtitle.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._subtitle.setWordWrap(True)
        self._subtitle.setObjectName("lockedSubtitle")
        self._subtitle.setVisible(False)
        self._inner_lo.addWidget(self._subtitle)

        self._inner_lo.addSpacing(10)

        # Primary CTA
        self._instr_pill = QLabel("  ☰  Open menu  →  Unlock  ")
        self._instr_pill.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._instr_pill.setWordWrap(True)
        self._instr_pill.setObjectName("lockedPill")
        try:
            self._instr_pill.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        except AttributeError:
            self._instr_pill.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Minimum)
        self._inner_lo.addWidget(self._instr_pill)

        self._inner_lo.addSpacing(12)

        self._card_layout.addWidget(_inner)

        # ── Stats strip ────────────────────────────────────────────────────────
        self._stats_frame = QFrame(self._card)
        self._stats_frame.setObjectName("statsStrip")
        try:
            self._stats_frame.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        except AttributeError:
            self._stats_frame.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        stats_row = QHBoxLayout(self._stats_frame)
        stats_row.setContentsMargins(0, 0, 0, 0)
        stats_row.setSpacing(0)
        self._stats_cells = []
        for i, (val, lbl) in enumerate([("IoT", "CONN."), ("AES", "ENCR."), ("24/7", "MON.")]):
            cell = QWidget(self._stats_frame)
            try:
                cell.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
            except AttributeError:
                cell.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
            cell_lo = QVBoxLayout(cell)
            cell_lo.setContentsMargins(8, 8, 8, 8)
            cell_lo.setSpacing(2)
            self._stats_cells.append(cell)
            v = QLabel(val)
            v.setAlignment(Qt.AlignmentFlag.AlignCenter)
            v.setObjectName("statValue")
            l = QLabel(lbl)
            l.setAlignment(Qt.AlignmentFlag.AlignCenter)
            l.setObjectName("statLabel")
            cell_lo.addWidget(v)
            cell_lo.addWidget(l)
            stats_row.addWidget(cell, 1)
            if i < 2:
                sep = QFrame(self._stats_frame)
                try:
                    sep.setFrameShape(QFrame.Shape.VLine)
                except AttributeError:
                    sep.setFrameShape(QFrame.VLine)
                sep.setObjectName("statSep")
                stats_row.addWidget(sep)
        self._card_layout.addWidget(self._stats_frame)

        # Status label
        self._status_label = QLabel("")
        self._status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._status_label.setObjectName("lockedStatus")
        self._status_label.setVisible(False)
        self._status_label.setContentsMargins(12, 6, 12, 8)
        self._card_layout.addWidget(self._status_label)

        # Stack icon+card vertically, perfectly centered (wrap in a container so the
        # icon+card group is treated as one unit and sits at the true vertical midpoint)
        _center_container = QWidget(self)
        _center_container.setStyleSheet("background: transparent;")
        try:
            _center_container.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred)
        except AttributeError:
            _center_container.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Preferred)
        _center_lo = QVBoxLayout(_center_container)
        _center_lo.setContentsMargins(0, 0, 0, 0)
        _center_lo.setSpacing(0)
        try:
            _center_lo.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        except AttributeError:
            _center_lo.setAlignment(Qt.AlignHCenter)
        self._icon_ring.setVisible(False)
        _center_lo.addWidget(self._icon_ring, 0, Qt.AlignmentFlag.AlignHCenter)
        _center_lo.addWidget(self._yellow_lock, 0, Qt.AlignmentFlag.AlignHCenter)
        _center_lo.addWidget(self._lock_svg_label, 0, Qt.AlignmentFlag.AlignHCenter)
        self._lock_card_spacer = QWidget(_center_container)
        self._lock_card_spacer.setFixedHeight(20)
        self._lock_card_spacer.setStyleSheet("background: transparent;")
        _center_lo.addWidget(self._lock_card_spacer, 0, Qt.AlignmentFlag.AlignHCenter)
        self._card_wrapper = _CardWrapper(self._card)
        self._card_wrapper._overlay.setVisible(False)  # New design: no animating border
        _center_lo.addWidget(self._card_wrapper, 0, Qt.AlignmentFlag.AlignHCenter)
        self._center_container = _center_container

        layout.addStretch(1)
        layout.addWidget(_center_container, 0, Qt.AlignmentFlag.AlignHCenter)
        layout.addStretch(1)

        self._scale = 1.0
        self._card.installEventFilter(self)
        self.set_theme("light")

    def _sync_locked_card_rounded_mask(self):
        """Clip card contents (including top gradient strip) to the same radius as the card corners."""
        card = getattr(self, "_card", None)
        if card is None:
            return
        w, h = card.width(), card.height()
        if w < 2 or h < 2:
            card.clearMask()
            return
        r = float(LOCKED_CARD_CORNER_RADIUS_PX)
        path = QPainterPath()
        path.addRoundedRect(QRectF(0.0, 0.0, float(w), float(h)), r, r)
        poly = path.toFillPolygon()
        try:
            region = QRegion(poly)
        except TypeError:
            region = QRegion(poly.toPolygon())
        card.setMask(region)

    def eventFilter(self, obj, event):
        if obj is getattr(self, "_card", None):
            try:
                is_resize = event.type() == QEvent.Type.Resize
            except AttributeError:
                is_resize = event.type() == QEvent.Resize
            if is_resize:
                self._sync_locked_card_rounded_mask()
        return super().eventFilter(obj, event)

    def showEvent(self, event):
        super().showEvent(event)
        self._sync_locked_card_rounded_mask()

    def _on_anim_tick(self):
        """Single 60fps tick drives ring spin + float + card border + background."""
        # Ring: 0.008 rad/frame = ~1 full rotation per 13 seconds (slow, elegant)
        self._ring_phase = (self._ring_phase + 0.008) % (2.0 * math.pi)
        # Card border: slow rotation for animating border
        self._card_border_phase = (self._card_border_phase + 0.015) % (2.0 * math.pi)
        if hasattr(self, "_card_wrapper") and hasattr(self._card_wrapper, "_overlay"):
            self._card_wrapper._overlay.update()
        if hasattr(self, "_yellow_lock"):
            self._yellow_lock.update()
        # Float: 0.012 rad/frame = ~8 second gentle up/down cycle
        self._float_phase = (self._float_phase + 0.012) % (2.0 * math.pi)
        if hasattr(self, "_icon_ring"):
            offset = 4.0 * math.sin(self._float_phase)
            self._icon_ring.set_phase_and_float(self._ring_phase, offset)
        # Background animations
        # Scan line: full cycle in ~12s = 1/720 per frame
        self._scan_phase = (self._scan_phase + 1.0 / 720.0) % 1.0
        # Pulse circles: full cycle in ~5s = 1/300 per frame
        self._pulse_bg_phase = (self._pulse_bg_phase + 1.0 / 300.0) % 1.0
        # Blob drift sine
        self._blob_phase = (self._blob_phase + 0.004) % (2.0 * math.pi)
        # Grid opacity: 8s cycle
        self._grid_phase = (self._grid_phase + 0.005) % (2.0 * math.pi)
        # Particle float
        self._particle_phase = (self._particle_phase + 1.0 / 420.0) % 1.0
        self.update()  # trigger paintEvent for animated background
        # ~3 updates per frame skip → ~20 Hz colour steps (fast blink, same palette)
        self._lock_party_tick += 1
        if self._lock_party_tick >= 3:
            self._lock_party_tick = 0
            self._lock_party_frame = (self._lock_party_frame + 1) % 8
            self._refresh_lock_party_pixmap()

    def _refresh_lock_party_pixmap(self):
        if not hasattr(self, "_lock_svg_label"):
            return
        sc = getattr(self, "_scale", 1.0)
        _sz = max(34, int(getattr(self, "_lock_icon_base", 72) * sc))
        _pm = _lock_icon_pixmap_party(_sz, self._lock_party_frame)
        if _pm:
            self._lock_svg_label.setPixmap(_pm)

    def resizeEvent(self, event):
        """Scale card and text with window size: small screen = smaller card, no scroll, no cutout."""
        super().resizeEvent(event)
        h = self.height()
        w = self.width()
        # Reset center container fixed width so the layout can recompute freely
        if hasattr(self, "_center_container"):
            self._center_container.setMinimumWidth(0)
            self._center_container.setMaximumWidth(16777215)
        # Scale from both width and height so compact/minimum windows shrink content
        # (full-size windows stay at 1.0). Floor ~0.36 keeps copy readable with padded CTA.
        ref_h, ref_w = 440, 520
        usable_h = max(130, h - 96)
        usable_w = max(168, w - 36)
        scale = min(1.0, usable_h / ref_h, usable_w / ref_w)
        scale = max(0.36, scale)
        self._scale = scale
        self._apply_scale()

    def _apply_scale(self):
        """Apply current _scale to icon size, card size/padding, stats padding, then refresh theme (fonts)."""
        s = getattr(self, "_scale", 1.0)
        if hasattr(self, "_icon_ring"):
            size = max(52, min(136, int(136 * s)))
            self._icon_ring.setFixedSize(size, size)
        if hasattr(self, "_card"):
            w_avail = max(240, self.width() - 40)
            if s >= 0.88:
                card_min_w = max(200, min(int(340 * s), int(w_avail * 0.92)))
                card_min_h = max(100, min(int(140 * s), int(128 + 10 * s)))
                card_min_w = max(card_min_w, min(460, int(w_avail * 0.58)))
                self._card.setMaximumWidth(min(460, int(w_avail * 0.60)))
                try:
                    self._card.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
                except AttributeError:
                    self._card.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Minimum)
            else:
                # Compact: slightly narrower card; height follows content (avoid squashed CTA)
                card_min_w = max(168, min(int(292 * s), int(w_avail * 0.96)))
                card_min_h = 0
                self._card.setMaximumWidth(int(w_avail))
                try:
                    self._card.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Minimum)
                except AttributeError:
                    self._card.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Minimum)
            self._card.setMinimumWidth(card_min_w)
            self._card.setMinimumHeight(card_min_h)
        if hasattr(self, "_lock_card_spacer"):
            self._lock_card_spacer.setFixedHeight(max(6, int(20 * s)))
        # Inner card padding (accent strip stays flush to card edges)
        if hasattr(self, "_inner_lo"):
            m = max(10, int(20 * s))
            m_vert = max(10, int(16 * s))
            if s >= 0.88:
                m = max(14, int(22 * s))
                m_vert = max(14, int(18 * s))
            sp = max(5, int(10 * s)) if s < 0.88 else max(8, int(12 * s))
            self._inner_lo.setContentsMargins(m, m_vert, m, m_vert)
            self._inner_lo.setSpacing(sp)
        if hasattr(self, "_card_layout"):
            self._card_layout.setContentsMargins(0, 0, 0, 0)
            self._card_layout.setSpacing(0)
        if hasattr(self, "_stats_cells"):
            for cell in self._stats_cells:
                lo = cell.layout()
                if lo:
                    t = max(4, int(10 * s))
                    lo.setContentsMargins(max(2, int(4 * s)), t, max(2, int(4 * s)), t)
                    lo.setSpacing(max(1, int(2 * s)))
        self.set_theme(getattr(self, "_theme", "light"))

    def set_status(self, text: str, color_hex: str):
        self._status_label.setText(text or "")
        if color_hex:
            self._status_label.setStyleSheet(f"font-size: 13px; color: {color_hex}; background: transparent;")
        self._status_label.setVisible(bool(text))

    def set_subtitle(self, text: str):
        self._subtitle.setText(text or "")
        self._subtitle.setVisible(bool((text or "").strip()))

    def apply_locked_card_layout(self, *, show_title: bool = True):
        """Center card: title + HIPAA/AES/IoT badges. Header pill is the CTA (no extra text/button)."""
        if hasattr(self, "_card_wrapper"):
            self._card_wrapper.setVisible(True)
        if hasattr(self, "_lock_card_spacer"):
            self._lock_card_spacer.setVisible(True)
        self.set_title_visible(show_title)
        if hasattr(self, "_divider"):
            self._divider.setVisible(show_title)
        if hasattr(self, "_badges_row"):
            self._badges_row.setVisible(True)
        for w in (
            getattr(self, "_instr_pill", None),
            getattr(self, "_stats_frame", None),
            getattr(self, "_subtitle", None),
        ):
            if w is not None:
                w.setVisible(False)

    def set_title_visible(self, visible: bool):
        self.center_lock.setVisible(visible)
        self._eyebrow.setVisible(visible)

    def set_first_time_pulse(self, on: bool):
        try:
            from PyQt6.QtCore import QTimer
        except ImportError:
            from PyQt5.QtCore import QTimer
        if on and getattr(self, "_pulse_timer", None) is None:
            self._pulse_timer = QTimer(self)
            self._pulse_timer.timeout.connect(self._on_pulse_tick)
            self._pulse_timer.start(700)
        elif not on and getattr(self, "_pulse_timer", None) is not None:
            self._pulse_timer.stop()
            self._pulse_timer = None
            self._pulse_phase = 0
            self.set_theme(self._theme)

    def _on_pulse_tick(self):
        self._pulse_phase = (self._pulse_phase + 1) % 4
        if hasattr(self, "_icon_ring"):
            self._icon_ring.set_pulse(self._pulse_phase % 2 == 0)

    def set_theme(self, theme: str):
        self._theme = (theme or "light").lower()
        dark = self._theme == "dark"

        if dark:
            self._dark_bg = True
            # Dark: elevated card, mint/teal accents
            card_bg        = "#2C2C2F"
            card_border    = "rgba(45, 212, 191, 0.35)"
            card_border2   = "#3F3F46"
            title_color    = "#FAFAFA"
            subtitle_color = "#A1A1AA"
            eyebrow_color  = "#5EEAD4"
            pill_bg        = ""
            pill_border    = "rgba(255, 255, 255, 0.12)"
            pill_color     = "#FAFAFA"
            stat_val_color = "#D4D4D8"
            stat_lbl_color = "#71717A"
            stat_bg        = "#27272A"
            stat_sep_color = "#3F3F46"
            ring_color     = "#71717A"
        else:
            self._dark_bg = False
            # Light: clean card, teal accent ring
            card_bg        = "#FFFFFF"
            card_border    = "rgba(13, 148, 136, 0.28)"
            card_border2   = "#E4E4E7"
            title_color    = "#18181B"
            subtitle_color = "#71717A"
            eyebrow_color  = "#0F766E"
            pill_bg        = ""  # unused when pill uses gradient
            pill_border    = "rgba(255, 255, 255, 0.35)"
            pill_color     = "#FAFAFA"
            stat_val_color = "#3F3F46"
            stat_lbl_color = "#71717A"
            stat_bg        = "#FAFAF9"
            stat_sep_color = "#E4E4E7"
            ring_color     = "#71717A"

        self.setStyleSheet("")
        self.update()  # trigger paintEvent for rich background

        _cr = LOCKED_CARD_CORNER_RADIUS_PX
        self._card.setStyleSheet(f"""
            QFrame#lockedCard {{
                background-color: {card_bg};
                border-radius: {_cr}px;
                border: 1px solid {card_border};
            }}
        """)
        if hasattr(self, "_card_accent"):
            self._card_accent.setStyleSheet(f"""
                QFrame#lockedCardAccent {{
                    background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                        stop:0 #0D9488, stop:0.42 #2DD4BF, stop:0.78 #14B8A6, stop:1 #F59E0B);
                    border: none;
                    border-top-left-radius: {_cr}px;
                    border-top-right-radius: {_cr}px;
                    min-height: 5px;
                    max-height: 5px;
                }}
            """)
        shadow = QGraphicsDropShadowEffect(self._card)
        shadow.setBlurRadius(40)
        shadow.setOffset(0, 8)
        shadow.setColor(QColor(0, 0, 0, 18 if not dark else 80))
        self._card.setGraphicsEffect(shadow)

        sc = getattr(self, "_scale", 1.0)
        self._eyebrow.setStyleSheet(f"""
            font-size: {max(8, int(10 * sc))}pt; font-weight: 600; letter-spacing: 3px;
            color: {eyebrow_color}; background: transparent; text-transform: uppercase;
        """)
        if hasattr(self, "_lock_icon_label"):
            badge_fg = "#64748b" if not dark else "#94a3b8"
            badge_bg = "#f1f5f9" if not dark else "rgba(45,212,191,0.15)"
            self._lock_icon_label.setStyleSheet(
                f"font-size: {max(9, int(11 * sc))}pt; font-weight: 700; letter-spacing: 1.5px; "
                f"color: {badge_fg}; background-color: {badge_bg}; border-radius: 6px; "
                f"padding: 6px 14px;"
            )
        self.center_lock.setStyleSheet(f"""
            font-size: {max(20, int(30 * sc))}px; font-weight: 700; letter-spacing: -0.5px;
            color: {title_color}; background: transparent; margin: 2px 0 2px 0;
        """)
        self._subtitle.setStyleSheet(f"""
            font-size: {max(11, int(14 * sc))}pt; color: {subtitle_color}; background: transparent;
            line-height: 1.5; margin: 0 8px;
        """)
        if dark:
            pill_grad = (
                "qlineargradient(x1:0, y1:0, x2:1, y2:1, "
                "stop:0 #0F766E, stop:0.5 #0D9488, stop:1 #115E59)"
            )
        else:
            pill_grad = (
                "qlineargradient(x1:0, y1:0, x2:1, y2:1, "
                "stop:0 #0F766E, stop:0.55 #0D9488, stop:1 #0D9488)"
            )
        pill_pt = max(9, int(12 * sc))
        pad_v = max(8, int(14 * sc))
        pad_h = max(14, int(26 * sc))
        pill_rad = max(10, int(14 * sc))
        self._instr_pill.setStyleSheet(f"""
            QLabel#lockedPill {{
                font-size: {pill_pt}pt; font-weight: 700; color: {pill_color};
                background: {pill_grad};
                border: 1px solid {pill_border};
                border-radius: {pill_rad}px;
                padding: {pad_v}px {pad_h}px;
            }}
        """)
        _pf = QFont(self._instr_pill.font())
        try:
            _pf.setPointSize(pill_pt)
        except Exception:
            pass
        _fm = QFontMetrics(_pf)
        _tw = max(120, self._card.width() - 24) if self._card.width() > 40 else max(120, int(260 * sc))
        try:
            _wflags = int(Qt.TextFlag.TextWordWrap)
        except AttributeError:
            _wflags = int(Qt.TextWordWrap)
        _tb = _fm.boundingRect(QRect(0, 0, _tw, 9999), _wflags, self._instr_pill.text())
        _pill_h = max(_fm.lineSpacing(), _tb.height()) + 2 * pad_v + 6
        self._instr_pill.setMinimumHeight(int(_pill_h))

        # Stats strip
        self.findChild(QFrame, "statsStrip")
        for frame in self._card.findChildren(QFrame):
            if frame.objectName() == "statsStrip":
                frame.setStyleSheet(f"""
                    QFrame#statsStrip {{
                        background-color: {stat_bg};
                        border-radius: 0px 0px 18px 18px;
                        border-top: 1px solid {stat_sep_color};
                    }}
                """)
        statvf = max(8, int(10 * sc))
        statlf = max(7, int(9 * sc))
        for lbl in self._card.findChildren(QLabel):
            if lbl.objectName() == "statValue":
                lbl.setStyleSheet(f"font-size: {statvf}pt; font-weight: 700; color: {stat_val_color}; background: transparent;")
            elif lbl.objectName() == "statLabel":
                lbl.setStyleSheet(f"font-size: {statlf}pt; font-weight: 500; color: {stat_lbl_color}; background: transparent; letter-spacing: 0.5px; text-transform: uppercase;")
        for frame in self._card.findChildren(QFrame):
            if frame.objectName() == "statSep":
                frame.setStyleSheet(f"border: none; border-left: 1px solid {stat_sep_color}; max-width: 1px;")

        self._status_label.setStyleSheet(f"font-size: {max(9, int(13 * sc))}px; background: transparent;")
        if hasattr(self, "_divider"):
            self._divider.setFixedSize(max(64, int(96 * sc)), max(3, int(4 * sc)))
            self._divider.setStyleSheet("""
                QFrame#lockedDivider {
                    background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                        stop:0 #0D9488, stop:0.5 #2DD4BF, stop:1 #F59E0B);
                    border: none;
                    border-radius: 2px;
                }
            """)
        if hasattr(self, "_badges_row"):
            if dark:
                _bfg, _bbg, _bbdr = "#CCFBF1", "rgba(45, 212, 191, 0.12)", "rgba(45, 212, 191, 0.35)"
            else:
                _bfg, _bbg, _bbdr = "#115E59", "#F0FDFA", "rgba(13, 148, 136, 0.28)"
            _bpv = max(4, int(5 * sc))
            _bph = max(5, int(11 * sc))
            for _b in self._badges_row.findChildren(QLabel):
                if _b.objectName() == "secBadge":
                    _b.setStyleSheet(
                        f"font-size:{max(7, int(9 * sc))}pt;font-weight:600;color:{_bfg};"
                        f"background:{_bbg};border:1px solid {_bbdr};border-radius:999px;"
                        f"padding:{_bpv}px {_bph}px;letter-spacing:0.35px;"
                    )

        if hasattr(self, "_icon_ring"):
            self._icon_ring.set_color("#0d9488" if not dark else "#2DD4BF")
            self._icon_ring.set_dark(dark)
        if hasattr(self, "_lock_svg_label"):
            _lock_sz = max(34, int(getattr(self, "_lock_icon_base", 72) * sc))
            self._lock_svg_label.setFixedSize(_lock_sz, _lock_sz)
            _pm = _lock_icon_pixmap_party(_lock_sz, getattr(self, "_lock_party_frame", 0))
            if _pm:
                self._lock_svg_label.setPixmap(_pm)


    def paintEvent(self, event):
        """Simple, unique background: single calm gradient, no patterns."""
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
        w, h = float(self.width()), float(self.height())
        iw, ih = int(w), int(h)

        if self._dark_bg:
            # Dark: warm charcoal gradient (simple, no grid/orbs)
            base = QLinearGradient(0, 0, w, h)
            base.setColorAt(0.0, QColor("#252528"))
            base.setColorAt(0.5, QColor("#1E1E21"))
            base.setColorAt(1.0, QColor("#18181B"))
            p.fillRect(0, 0, iw, ih, QBrush(base))
        else:
            # Light: warm off-white to soft sage (calm, minimal)
            base = QLinearGradient(0, 0, w * 0.4, h * 1.2)
            base.setColorAt(0.0, QColor("#F8F7F5"))
            base.setColorAt(0.5, QColor("#F2F1ED"))
            base.setColorAt(1.0, QColor("#E8E6E1"))
            p.fillRect(0, 0, iw, ih, QBrush(base))

        p.end()


class GradientTitleLabel(QLabel):
    def __init__(self, text="", parent=None):
        super().__init__(text, parent)
        self._theme = "dark"
        self._anim_phase = 0.0

    def set_theme(self, theme: str):
        self._theme = (theme or "light").lower()
        self.update()

    def set_anim_phase(self, phase: float):
        self._anim_phase = float(phase)
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        try:
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            painter.setRenderHint(QPainter.Antialiasing)
        font = self.font()
        font.setWeight(QFont.Weight.Bold)
        font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 0.5)
        painter.setFont(font)
        text = self.text()
        if not text:
            return
        path = QPainterPath()
        fm = painter.fontMetrics()
        try:
            text_width = fm.horizontalAdvance(text)
        except AttributeError:
            text_width = fm.width(text)
        x = max(0, (self.width() - text_width) / 2)
        y = (self.height() + fm.ascent() - fm.descent()) / 2
        path.addText(x, y, font, text)
        grad = QLinearGradient(0, 0, self.width(), 0)
        shift = 0.10 * math.sin(self._anim_phase * 0.9)
        c0 = max(0.0, min(1.0, 0.0 + shift))
        c1 = max(0.0, min(1.0, 0.5 + shift))
        c2 = max(0.0, min(1.0, 1.0 + shift))
        if self._theme == "light":
            grad.setColorAt(c0, QColor("#054733"))
            grad.setColorAt(c1, QColor("#0A6C50"))
            grad.setColorAt(c2, QColor("#066044"))
        else:
            grad.setColorAt(c0, QColor("#22D3EE"))
            grad.setColorAt(c1, QColor("#2DD4BF"))
            grad.setColorAt(c2, QColor("#14B8A6"))
        painter.fillPath(path, grad)


# ── Sidebar ambient widget ────────────────────────────────────────────────────
class SidebarGridAnimation(QWidget):
    """Subtle breathing glow — matches the teal gradient of the main UI."""
    def __init__(self, parent=None):
        super().__init__(parent)
        try:
            self.setWindowFlags(Qt.WindowType.Widget)
        except AttributeError:
            self.setWindowFlags(Qt.Widget)
        self._theme = "light"
        self._phase = 0.0
        self.setMinimumHeight(40)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(80)

    def set_theme(self, theme: str):
        self._theme = (theme or "light").lower()
        self.update()

    def _tick(self):
        self._phase = (self._phase + 0.03) % (2.0 * math.pi)
        self.update()

    def paintEvent(self, event):
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
        w, h = float(self.width()), float(self.height())
        if w < 4 or h < 4:
            p.end()
            return
        pulse = int(10 + 7 * math.sin(self._phase))
        if self._theme == "light":
            orb = QRadialGradient(w * 0.5, h * 0.5, min(w, h) * 0.9)
            orb.setColorAt(0.0, QColor(13, 148, 136, pulse))
            orb.setColorAt(1.0, QColor(13, 148, 136, 0))
        else:
            orb = QRadialGradient(w * 0.5, h * 0.5, min(w, h) * 0.9)
            orb.setColorAt(0.0, QColor(45, 212, 191, pulse))
            orb.setColorAt(1.0, QColor(45, 212, 191, 0))
        p.fillRect(0, 0, int(w), int(h), QBrush(orb))
        p.end()


# ── Main Window ───────────────────────────────────────────────────────────────
class MainWindow(QMainWindow):
    """CuraX main window — redesigned."""

    def resizeEvent(self, event):
        """Keep overlay sidebar correctly positioned on resize; scale topbar (status pill + toggle) on small screens."""
        super().resizeEvent(event)
        if hasattr(self, "sidebar") and self.sidebar.parent() and self.sidebar_visible:
            self.sidebar.setGeometry(self._sidebar_geo())
        self._update_topbar_scale()

    def _update_topbar_scale(self):
        """Recompute topbar scale from window width and apply (so header/toggle/status scale on small screens)."""
        if not hasattr(self, "_topbar_scale"):
            return
        w = self.width()
        # Scale below 1 when width < 900 so typical "small" windows (600–850px) get smaller header/toggle/status
        ts = min(1.0, (w - 60) / 900.0)
        ts = max(0.52, ts)
        self._topbar_scale = ts
        self._apply_topbar_scale()

    def _apply_topbar_scale(self):
        """Scale topbar height, status pill, status label, and theme toggle for small screens."""
        ts = getattr(self, "_topbar_scale", 1.0)
        topbar_h = max(38, int(64 * ts))
        if hasattr(self, "_topbar_frame"):
            self._topbar_frame.setFixedHeight(topbar_h)
        # Brand strip must equal topbar height so clicks above the icon (centering gap) still hit the bar
        if hasattr(self, "_brand_frame"):
            self._brand_frame.setFixedHeight(topbar_h)
        # Hamburger: fill topbar height so full strip is clickable (avoids mis-hit on small screens)
        if hasattr(self, "menu_btn"):
            btn_w = max(28, int(34 * ts))
            self.menu_btn.setFixedSize(btn_w, topbar_h)
            self.menu_btn.raise_()
        if getattr(self, "_menu_hit_zone", None) is not None and hasattr(self, "_topbar_frame") and hasattr(self, "menu_btn"):
            tb = self._topbar_frame
            mb = self.menu_btn
            th = tb.height() if tb.height() > 4 else topbar_h
            tl = mb.mapTo(tb, QPoint(0, 0))
            pad_r = 12
            hit_w = max(52, tl.x() + mb.width() + pad_r)
            self._menu_hit_zone.setGeometry(0, 0, hit_w, th)
            self._menu_hit_zone.raise_()
        if hasattr(self, "_center_title"):
            try:
                self._center_title.setCursor(Qt.CursorShape.ArrowCursor)
            except Exception:
                self._center_title.setCursor(Qt.ArrowCursor)
        if hasattr(self, "_brand_name"):
            try:
                self._brand_name.setCursor(Qt.CursorShape.ArrowCursor)
            except Exception:
                self._brand_name.setCursor(Qt.ArrowCursor)
        if hasattr(self, "theme_toggle_global"):
            tw = max(28, min(56, int(56 * ts)))
            th = max(14, min(28, int(28 * ts)))
            self.theme_toggle_global.setFixedSize(tw, th)
        if hasattr(self, "_status_dot"):
            d = max(4, min(8, int(8 * ts)))
            self._status_dot.setFixedSize(d, d)
        if hasattr(self, "_status_pill"):
            lo = self._status_pill.layout()
            if lo:
                mh = max(3, int(5 * ts))
                mw = max(6, int(10 * ts))
                lo.setContentsMargins(mw, mh, mw, mh)
                lo.setSpacing(max(4, int(6 * ts)))
            r = max(8, int(14 * ts))
            theme = getattr(self, "_current_theme", "light")
            border = "#4a4a4a" if theme == "dark" else "#d1d5db"
            bg = "rgba(14,28,46,0.8)" if theme == "dark" else "#ffffff"
            self._status_pill.setStyleSheet(
                f"QFrame#statusPill {{ background-color: {bg}; border: 1.5px solid {border}; "
                f"border-radius: {r}px; padding: 2px 6px; }}"
            )
        if hasattr(self, "locked_status"):
            fs = max(5, min(9, int(8 * ts)))
            theme = getattr(self, "_current_theme", "light")
            clr = "#2DD4BF" if theme == "dark" else "#0d6b61"
            self.locked_status.setStyleSheet(
                f"font-size: {fs}pt; font-weight: 500; color: {clr}; "
                "background: transparent; border: none;"
            )
            self.locked_status.setMaximumWidth(max(80, int(160 * ts)))
        if hasattr(self, "_right_frame"):
            self._right_frame.setMinimumWidth(max(100, int(160 * ts)))
        if hasattr(self, "_center_title"):
            theme = getattr(self, "_current_theme", "light")
            title_clr = "#e2f8f4" if theme == "dark" else "#0d6b61"
            self._center_title.setStyleSheet(
                f"background: transparent; border: none; font-size: {max(7, int(11 * ts))}pt; "
                f"font-weight: 700; color: {title_clr}; letter-spacing: 0.5px;"
            )
        if hasattr(self, "_brand_name"):
            bpt = max(10, int(15 * ts))
            theme = getattr(self, "_current_theme", "light")
            if theme == "dark":
                self._brand_name.setText(
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#a8f0e8;">Cura</span>'
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#2DD4BF;">X</span>'
                )
            else:
                self._brand_name.setText(
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#0a3d38;">Cura</span>'
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#0d9488;">X</span>'
                )
        if hasattr(self, "_brand_icon"):
            sz = max(28, int(44 * ts))
            self._brand_icon.setFixedSize(sz, sz)
        if hasattr(self, "_apply_sidebar_scale"):
            self._apply_sidebar_scale()
        if hasattr(self, "_footer_bar"):
            ft = getattr(self, "_current_theme", "light")
            fpt = max(6, int(8 * ts))
            if ft == "dark":
                self._footer_bar.setStyleSheet(
                    "QFrame#footerBar { background-color: rgba(10,15,30,0.6); "
                    "border-top: 2px solid #3a3a3a; }"
                    f"QLabel#footerLabel {{ font-size: {fpt}pt; color: #2DD4BF; background: transparent; }}"
                )
            else:
                self._footer_bar.setStyleSheet(
                    "QFrame#footerBar { background-color: rgba(255,255,255,0.38); "
                    "border-top: 1px solid #E2E8F0; }"
                    f"QLabel#footerLabel {{ font-size: {fpt}pt; color: #6a9e99; background: transparent; }}"
                )
        self._content_scale = ts
        if hasattr(self, "tabs"):
            self.tabs.setFont(QFont("Segoe UI", max(6, int(10 * ts))))
            try:
                tb = self.tabs.tabBar()
                if tb:
                    tb.setStyleSheet(f"font-size: {max(6, int(9 * ts))}pt; font-weight: 600;")
            except Exception:
                pass
            for i in range(self.tabs.count()):
                w = self.tabs.widget(i)
                if w and hasattr(w, "apply_theme"):
                    try:
                        w.apply_theme(self._current_theme, content_scale=ts)
                    except TypeError:
                        w.apply_theme(self._current_theme)

    def paintEvent(self, event):
        """Paint rich teal gradient + grid background across the entire window."""
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
        w, h = float(self.width()), float(self.height())
        if self._mw_dark:
            base = QLinearGradient(0, 0, w, h)
            base.setColorAt(0.0, QColor("#070C14"))
            base.setColorAt(0.45, QColor("#0B1524"))
            base.setColorAt(1.0, QColor("#050910"))
            p.fillRect(0, 0, int(w), int(h), QBrush(base))
            orb1 = QRadialGradient(w * 0.10, h * 0.20, min(w, h) * 0.55)
            orb1.setColorAt(0.0, QColor(18, 168, 150, 38)); orb1.setColorAt(1.0, QColor(18, 168, 150, 0))
            p.fillRect(0, 0, int(w), int(h), QBrush(orb1))
            orb2 = QRadialGradient(w * 0.85, h * 0.75, min(w, h) * 0.42)
            orb2.setColorAt(0.0, QColor(31, 212, 191, 32)); orb2.setColorAt(1.0, QColor(31, 212, 191, 0))
            p.fillRect(0, 0, int(w), int(h), QBrush(orb2))
            grid_color = QColor(45, 212, 191, 22)
        else:
            # Pure white — no gradient, no tint
            p.fillRect(0, 0, int(w), int(h), QColor("#ffffff"))
        p.end()

    def apply_theme(self, theme_name: str):
        name = (theme_name or "light").lower()
        self._current_theme = "light" if name == "light" else "dark"
        self._mw_dark = (self._current_theme == "dark")
        self.update()  # repaint background
        if self._current_theme == "light":
            self.setStyleSheet(LIGHT_THEME)
        else:
            self.setStyleSheet(DARK_THEME)
        if hasattr(self, "sidebar"):
            self._apply_sidebar_theme()
        if hasattr(self, "locked_title"):
            self._apply_title_theme()
        if hasattr(self, "locked_screen_widget"):
            self.locked_screen_widget.set_theme(self._current_theme)
        if hasattr(self, "theme_toggle_global"):
            self.theme_toggle_global.set_theme(self._current_theme)
        if hasattr(self, "_power_btn"):
            self._power_btn.set_dark(self._mw_dark)
        # Update footer bar theme
        if hasattr(self, "_footer_bar"):
            if self._current_theme == "dark":
                self._footer_bar.setStyleSheet(
                    "QFrame#footerBar { background-color: rgba(8,12,22,0.78); "
                    "border-top: 2px solid rgba(45,212,191,0.22); }"
                    "QLabel#footerLabel { font-size: 8pt; color: #5EEAD4; background: transparent; }"
                )
            else:
                self._footer_bar.setStyleSheet(
                    "QFrame#footerBar { background-color: rgba(255,255,255,0.38); "
                    "border-top: 1px solid #E2E8F0; }"
                    "QLabel#footerLabel { font-size: 8pt; color: #6a9e99; background: transparent; }"
                )
        if hasattr(self, "tabs"):
            try:
                sc = getattr(self, "_content_scale", getattr(self, "_topbar_scale", 1.0))
                for i in range(self.tabs.count()):
                    w = self.tabs.widget(i)
                    if hasattr(w, "apply_theme"):
                        try:
                            w.apply_theme(self._current_theme, content_scale=sc)
                        except TypeError:
                            w.apply_theme(self._current_theme)
            except Exception:
                pass

    def _apply_sidebar_scale(self):
        """Scale sidebar section titles, status pill, and base font for small screens."""
        ts = getattr(self, "_topbar_scale", 1.0)
        if hasattr(self, "sidebar"):
            self.sidebar.setFont(QFont("Segoe UI", max(8, int(9 * ts))))
        if hasattr(self, "tools_section_title"):
            px = max(6, int(8 * ts))
            self.tools_section_title.setStyleSheet(
                f"font-size: {px}px; letter-spacing: 1.5px; color: rgba(13,148,136,0.70);"
                "font-weight: 700; padding: 4px 2px 2px 2px; background: transparent;"
            )
        if hasattr(self, "admin_section_title"):
            px = max(6, int(8 * ts))
            self.admin_section_title.setStyleSheet(
                f"font-size: {px}px; letter-spacing: 1.5px; color: rgba(13,148,136,0.70);"
                "font-weight: 700; padding: 4px 2px 2px 2px; background: transparent;"
            )
        if hasattr(self, "admin_status"):
            h = max(22, int(28 * ts))
            self.admin_status.setStyleSheet(
                f"background: #475569; color: #F8FAFC; padding: 6px 10px; min-height: {h}px; "
                f"border: none; border-radius: 10px; font-weight: 700; font-size: {max(7, int(9 * ts))}pt;"
            )
        if hasattr(self, "port_combo"):
            self.port_combo.setMinimumWidth(max(80, int(120 * ts)))
            self.port_combo.setMinimumHeight(max(22, int(26 * ts)))
        if hasattr(self, "connect_btn"):
            self.connect_btn.setMinimumWidth(max(80, int(120 * ts)))
            self.connect_btn.setMinimumHeight(max(20, int(24 * ts)))
        if hasattr(self, "auth_btn"):
            self.auth_btn.setMinimumWidth(max(80, int(120 * ts)))
            self.auth_btn.setMinimumHeight(max(20, int(24 * ts)))
        if hasattr(self, "unlock_without_device_btn"):
            self.unlock_without_device_btn.setMinimumWidth(max(80, int(120 * ts)))
            self.unlock_without_device_btn.setMinimumHeight(max(20, int(24 * ts)))
        if hasattr(self, "unlock_with_password_btn"):
            self.unlock_with_password_btn.setMinimumHeight(max(22, int(26 * ts)))
            self.unlock_with_password_btn.setStyleSheet(
                f"QPushButton {{ padding: {max(6, int(8 * ts))}px {max(10, int(12 * ts))}px; "
                f"font-size: {max(7, int(9 * ts))}pt; font-weight: 700; border: none; border-radius: 10px; }}"
            )
        if hasattr(self, "sidebar_test_alert_btn"):
            self.sidebar_test_alert_btn.setMinimumHeight(max(18, int(22 * ts)))
        if hasattr(self, "admin_quick_btn"):
            self.admin_quick_btn.setStyleSheet(
                f"background-color: #0D9488; color: #fff; font-weight: 600; font-size: {max(8, int(10 * ts))}pt; "
                f"padding: {max(5, int(6 * ts))}px {max(8, int(10 * ts))}px; border-radius: 8px; text-align: center;"
            )
        if hasattr(self, "sidebar_my_codes_btn"):
            self.sidebar_my_codes_btn.setMinimumHeight(max(22, int(26 * ts)))
        if hasattr(self, "linked_to_admin_status"):
            self.linked_to_admin_status.setStyleSheet(
                f"background: #1E293B; color: #94A3B8; padding: 6px 10px; min-height: {max(20, int(26 * ts))}px; "
                f"border: none; border-radius: 8px; font-weight: 600; font-size: {max(7, int(9 * ts))}pt;"
            )
        if hasattr(self, "_unlock_admin_hint"):
            self._unlock_admin_hint.setStyleSheet(
                f"color: #94A3B8; font-size: {max(7, int(9 * ts))}pt; background: transparent;"
            )
        if hasattr(self, "com_status"):
            self.com_status.setStyleSheet(f"color: #EF4444; font-size: {max(6, int(8 * ts))}pt;")
        if hasattr(self, "port_connected_label"):
            self.port_connected_label.setStyleSheet(
                f"color: #0D9488; font-size: {max(7, int(9 * ts))}pt; font-weight: 700;"
            )
        if hasattr(self, "sidebar") and hasattr(self, "_sidebar_expanded_width"):
            self.sidebar.setFixedWidth(max(140, int(self._sidebar_expanded_width * ts)))
        if hasattr(self, "sidebar") and self.sidebar.layout():
            lo = self.sidebar.layout()
            m = max(6, int(8 * ts))
            lo.setContentsMargins(m, max(8, int(10 * ts)), m, max(8, int(10 * ts)))
            lo.setSpacing(max(3, int(4 * ts)))

    def _apply_sidebar_theme(self):
        theme = getattr(self, "_current_theme", "light")
        ts = getattr(self, "_topbar_scale", 1.0)
        sb_fs = max(7, int(9 * ts))
        sb_mh = max(20, int(26 * ts))
        sb_pv = max(5, int(6 * ts))
        sb_ph = max(8, int(10 * ts))
        row_h = max(22, int((getattr(self, "_sidebar_row_height", 28) - 2) * ts))
        status_text = (self.admin_status.text() or "").lower()
        if "admin:" in status_text:
            status_kind = "admin"
        elif "linked to:" in status_text or "user view" in status_text:
            status_kind = "user"
        else:
            status_kind = "locked"

        if theme == "light":
            self.left_column.setStyleSheet(
                "QWidget#leftColumn {"
                "background-color: #ffffff;"
                "border-right: 1px solid #E2E8F0;"
                "}"
            )
            self.sidebar.setStyleSheet(
                "QFrame#sidebarFrame {"
                "background-color: #ffffff;"
                "border-top: none;"
                "border-left: none;"
                "border-right: 1px solid #E2E8F0;"
                "border-bottom: 1px solid #E2E8F0;"
                "border-bottom-right-radius: 12px;"
                "}"
                "QFrame#sidebarFrame QLabel { background: transparent; color: #1f2937; }"
                "QFrame#sidebarFrame QGroupBox { background: transparent; border: none; }"
                "QFrame#sidebarFrame QPushButton {"
                "background-color: #f3f4f6;"
                "color: #111827; border: 1.5px solid #d1d5db;"
                f"border-radius: 8px; font-size: {sb_fs}pt; font-weight: 600;"
                f"padding: {sb_pv}px {sb_ph}px; text-align: left; min-height: {sb_mh}px;"
                "}"
                "QFrame#sidebarFrame QPushButton:hover {"
                "background-color: #e8f5f2; border-color: #0d9488; color: #0d9488;"
                "}"
                "QFrame#sidebarFrame QPushButton:pressed {"
                "background-color: #d1faf5;"
                "}"
            )
            if status_kind == "admin":
                status_bg, status_border, status_fg = "#0D6B61", "#0A5650", "#ECFFFA"
            elif status_kind == "user":
                status_bg, status_border, status_fg = "#0E5F79", "#0B4F65", "#E9F8FF"
            else:
                status_bg, status_border, status_fg = "#64748B", "#4B5563", "#F1F5F9"
            nav_style = (
                "QPushButton {"
                "background: qlineargradient(x1:0,y1:0,x2:0,y2:1,"
                "stop:0 rgba(255,255,255,0.72), stop:1 rgba(220,244,238,0.72));"
                "color: #0a3d38; text-align: left;"
                f"padding: {max(5, int(7*ts))}px {max(8, int(12*ts))}px; min-height: {row_h}px;"
                f"border-radius: {getattr(self,'_sidebar_radius',8)}px;"
                f"border: 1px solid rgba(13,148,136,0.28); font-size: {sb_fs}pt; font-weight: 600;"
                "}"
                "QPushButton:hover { background: rgba(255,255,255,0.90);"
                "border-color: rgba(13,148,136,0.55); color: #042f2e; }"
            )
            card_bg, card_border = "#FFFFFF", "#C8DDD8"
            input_bg, input_border, input_fg = "#F9FCFB", "#9FBCAF", "#12382B"
            tool_text, tool_hover = "#1B2632", "#E6EDF2"
            com_bg, com_border, com_text, com_hover = "#D1F0E0", "#9EBCAF", "#12362B", "#E5F2EC"
            admin_btn_bg, admin_btn_hover = "#075D43", "#0B6F50"
        else:
            self.left_column.setStyleSheet(
                "QWidget#leftColumn {"
                "background: qlineargradient(x1:0,y1:0,x2:0,y2:1,"
                "stop:0 #0d1626, stop:1 #070c14);"
                "border-right: 2px solid rgba(45,212,191,0.14);"
                "}"
            )
            self.sidebar.setStyleSheet(
                "QFrame#sidebarFrame {"
                "background: qlineargradient(x1:0,y1:0,x2:0,y2:1,"
                "stop:0 #121e30, stop:0.5 #0f1a2a, stop:1 #0a121e);"
                "border-top: none;"
                "border-left: none;"
                "border-right: 2px solid rgba(45,212,191,0.14);"
                "border-bottom: 2px solid rgba(44,71,96,0.55);"
                "border-bottom-right-radius: 12px;"
                "}"
                "QFrame#sidebarFrame QLabel { background: transparent; color: #a8f0e8; }"
                "QFrame#sidebarFrame QGroupBox { background: transparent; border: none; }"
                "QFrame#sidebarFrame QPushButton {"
                "background-color: rgba(30,58,74,0.85);"
                "color: #a8f0e8; border: 1.5px solid rgba(45,212,191,0.25);"
                f"border-radius: 8px; font-size: {sb_fs}pt; font-weight: 600;"
                f"padding: {sb_pv}px {sb_ph}px; text-align: left; min-height: {sb_mh}px;"
                "}"
                "QFrame#sidebarFrame QPushButton:hover {"
                "background-color: rgba(45,80,100,0.90);"
                "border-color: rgba(45,212,191,0.55); color: #e2fdf8;"
                "}"
                "QFrame#sidebarFrame QPushButton:pressed {"
                "background: rgba(45,212,191,0.15); border-color: #2dd4bf;"
                "}"
            )
            if status_kind == "admin":
                status_bg, status_border, status_fg = "#0D9488", "#0F766E", "#F0FDFA"
            elif status_kind == "user":
                status_bg, status_border, status_fg = "#0E7490", "#0C5A6E", "#ECFEFF"
            else:
                status_bg, status_border, status_fg = "#334155", "#1E293B", "#94A3B8"
            nav_style = (
                "QPushButton {"
                "background: qlineargradient(x1:0,y1:0,x2:0,y2:1,"
                "stop:0 rgba(30,58,74,0.80), stop:1 rgba(14,28,46,0.80));"
                "color: #a8f0e8; text-align: left;"
                f"padding: {max(5, int(7 * ts))}px {max(8, int(12 * ts))}px; min-height: {row_h}px;"
                f"border-radius: {getattr(self,'_sidebar_radius',8)}px;"
                f"border: 1px solid rgba(45,212,191,0.20); font-size: {sb_fs}pt; font-weight: 600;"
                "}"
                "QPushButton:hover { background: rgba(52,92,118,0.92);"
                "border-color: rgba(45,212,191,0.50); color: #f0fdf9; }"
            )
            card_bg, card_border = "#121E30", "#2C4760"
            input_bg, input_border, input_fg = "#0A1524", "#2C4760", "#EEF2F7"
            tool_text, tool_hover = "#D1DAE6", "#1A3048"
            com_bg, com_border, com_text, com_hover = "#121E30", "#2C4760", "#D1DAE6", "#1A3048"
            admin_btn_bg, admin_btn_hover = "#0D9488", "#14B8A6"

        r = getattr(self, "_sidebar_radius", 8)
        h = getattr(self, "_sidebar_row_height", 28)

        self.admin_status.setStyleSheet(
            f"background: {status_bg}; color: {status_fg}; padding: 8px 12px; min-height: {h}px; "
            f"border: none; border-radius: 10px; font-weight: 700; font-size: 9pt;"
        )
        try:
            self.user_view_btn.setStyleSheet(nav_style)
        except Exception:
            pass

        com_btn_style = (
            "QPushButton {"
            f"background-color: {com_bg}; color: {com_text}; text-align: left;"
            "padding: 5px 8px; min-height: 24px;"
            f"border-radius: {r}px; font-size: 9pt; font-weight: 600;"
            f"border: 1px solid {com_border};"
            "}"
            f"QPushButton:hover {{ background-color: {com_hover}; }}"
        )
        self._esp32_btn_style = com_btn_style
        self.connect_btn.setStyleSheet(com_btn_style)
        self.auth_btn.setStyleSheet(com_btn_style)

        self.com_frame.setStyleSheet(f"""
            QGroupBox {{
                background-color: {card_bg}; border-radius: {r}px;
                border: 1px solid {card_border}; margin-top: 2px; padding: 6px;
            }}
            QGroupBox::title {{ height: 0px; padding: 0; }}
            QGroupBox QComboBox#portCombo {{
                border: 1px solid {input_border}; border-radius: {r}px;
                padding: 2px 6px; min-height: 24px; font-size: 9pt;
                background-color: {input_bg}; color: {input_fg};
            }}
        """)
        self.tool_group.setStyleSheet(f"""
            QGroupBox {{
                background-color: {card_bg}; border-radius: {r}px;
                border: 1px solid {card_border}; margin-top: 2px; padding: 4px;
            }}
            QGroupBox::title {{ height: 0px; padding: 0; }}
            QGroupBox QPushButton {{
                background-color: transparent; color: {tool_text};
                text-align: left; padding: 4px 8px; border-radius: {r}px; font-size: 9pt; font-weight: 600;
            }}
            QGroupBox QPushButton:hover {{ background-color: {tool_hover}; }}
        """)
        self.admin_quick_btn.setStyleSheet(
            f"QPushButton {{ background-color: {admin_btn_bg}; color: #F6FBFF; font-weight: 700; "
            f"font-size: 9pt; padding: 6px 10px; border-radius: 8px; text-align: center; border: none; }}"
            f"QPushButton:hover {{ background-color: {admin_btn_hover}; }}"
        )

        # ESP32 toggle button
        if theme == "light":
            self.esp32_btn.setStyleSheet(f"""
                QPushButton#esp32ToggleBtn {{
                    background-color: #EAF5EF; color: #123327;
                    text-align: left; padding: 8px 12px;
                    min-height: {h-4}px; border-radius: {r}px;
                    border: 1px solid #CBE0D5; border-left: 3px solid #0D9488;
                    font-size: 10pt; font-weight: 700;
                }}
                QPushButton#esp32ToggleBtn:hover {{
                    background-color: #DFF0E8; border-left: 3px solid #0D9488;
                }}
            """)
        else:
            self.esp32_btn.setStyleSheet(f"""
                QPushButton#esp32ToggleBtn {{
                    background: qlineargradient(x1:0,y1:0,x2:0,y2:1,
                        stop:0 #162536, stop:1 #101c2c);
                    color: #EEF2F7;
                    text-align: left; padding: 8px 12px;
                    min-height: {h-4}px; border-radius: {r}px;
                    border: 1px solid rgba(45,212,191,0.22);
                    border-left: 3px solid #2DD4BF;
                    font-size: 10pt; font-weight: 700;
                }}
                QPushButton#esp32ToggleBtn:hover {{
                    background: #1A3048;
                    border: 1px solid rgba(45,212,191,0.35);
                    border-left: 3px solid #5EEAD4;
                }}
            """)

        section_color = "#6B7280" if theme == "light" else "#475569"
        section_style = f"font-size: 9px; letter-spacing: 1px; color: {section_color}; font-weight: 700; padding: 2px 0; text-transform: uppercase;"
        self.tools_section_title.setStyleSheet(section_style)
        self.admin_section_title.setStyleSheet(section_style)

        icon_color = "#111827" if theme == "light" else "#E2E8F0"
        self._menu_hamburger_pixmap = _make_hamburger_pixmap(icon_color)
        self._menu_close_pixmap = _make_close_pixmap(icon_color)
        self.menu_btn.setStyleSheet("background: transparent; border: none;")
        self.menu_btn.setPixmap(
            self._menu_close_pixmap if getattr(self, "sidebar_visible", False) else self._menu_hamburger_pixmap
        )
        self.menu_btn.update()
        self._apply_sidebar_scale()

    def _status_pill_style(self, for_ready: bool = False):
        if not for_ready:
            return "font-size: 9pt; font-weight: 500; padding: 3px 0; background: transparent; border: none;"
        theme = getattr(self, "_current_theme", "light")
        if theme == "light":
            return (
                "font-size: 9pt; font-weight: 600; padding: 6px 16px; "
                "border-radius: 14px; border: 1px solid #B2D8D4; background-color: #F4FAF8;"
            )
        else:
            return (
                "font-size: 9pt; font-weight: 600; padding: 6px 16px; "
                "border-radius: 14px; border: 1px solid #1E3A4A; background-color: #0E1C2E;"
            )

    def _apply_title_theme(self):
        theme = getattr(self, "_current_theme", "light")
        is_dark = (theme == "dark")

        # Update topbar background
        if hasattr(self, "_topbar_frame"):
            if is_dark:
                self._topbar_frame.setStyleSheet("""
                    QFrame#topBar {
                        background-color: rgba(14,22,40,0.97);
                        border-bottom: 2px solid #3a3a3a;
                    }
                """)
            else:
                self._topbar_frame.setStyleSheet("""
                    QFrame#topBar {
                        background-color: #ffffff;
                        border-bottom: 1px solid #E2E8F0;
                    }
                """)

        # Update status pill
        if hasattr(self, "_status_pill"):
            if is_dark:
                self._status_pill.setStyleSheet("""
                    QFrame#statusPill {
                        background-color: rgba(14,28,46,0.8);
                        border: 1.5px solid #4a4a4a;
                        border-radius: 14px; padding: 2px 6px;
                    }
                """)
            else:
                self._status_pill.setStyleSheet("""
                    QFrame#statusPill {
                        background-color: #ffffff;
                        border: 1.5px solid #d1d5db;
                        border-radius: 14px; padding: 2px 6px;
                    }
                """)

        # Status label color (font size comes from _apply_topbar_scale for responsiveness)
        if hasattr(self, "locked_status"):
            clr = "#2DD4BF" if is_dark else "#0d6b61"
            ts = getattr(self, "_topbar_scale", 1.0)
            fs = max(5, min(9, int(8 * ts)))
            self.locked_status.setStyleSheet(
                f"font-size: {fs}pt; font-weight: 500; color: {clr}; "
                "background: transparent; border: none;"
            )

        # Center title and brand name (font size applied in _apply_topbar_scale for scaling)
        if hasattr(self, "_center_title"):
            title_clr = "#e2f8f4" if is_dark else "#0d6b61"
            ts = getattr(self, "_topbar_scale", 1.0)
            self._center_title.setStyleSheet(
                f"background: transparent; border: none; font-size: {max(7, int(11 * ts))}pt; "
                f"font-weight: 700; color: {title_clr}; letter-spacing: 0.5px;"
            )

        # Brand name colors and size (scaled in _apply_topbar_scale)
        if hasattr(self, "_brand_name"):
            ts = getattr(self, "_topbar_scale", 1.0)
            bpt = max(10, int(15 * ts))
            if is_dark:
                self._brand_name.setText(
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#a8f0e8;">Cura</span>'
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#2DD4BF;">X</span>'
                )
            else:
                self._brand_name.setText(
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#0a3d38;">Cura</span>'
                    f'<span style="font-size:{bpt}pt;font-weight:800;color:#0d9488;">X</span>'
                )

        self._update_status_text()
        self._apply_topbar_scale()

    def _animate_header_overlay(self):
        self._header_overlay_phase = (getattr(self, "_header_overlay_phase", 0.0) + 0.06) % (2.0 * math.pi)
        try:
            self.locked_title.set_anim_phase(self._header_overlay_phase)
        except Exception:
            pass
        self._apply_title_theme()

    def __init__(self, controller):
        super().__init__()
        self.controller = controller
        self.sidebar_visible = False
        self._sidebar_locked_mode = True
        self._mw_dark = False  # for paintEvent background
        self.setWindowTitle("CuraX - Intelligent Medicine System")
        # Single top-level window on Windows: set flags so we don't get extra taskbar/empty frames
        try:
            self.setWindowFlags(
                Qt.WindowType.Window
                | Qt.WindowType.WindowCloseButtonHint
                | Qt.WindowType.WindowMinimizeButtonHint
                | Qt.WindowType.WindowMaximizeButtonHint
            )
        except AttributeError:
            self.setWindowFlags(
                Qt.Window | Qt.WindowCloseButtonHint
                | Qt.WindowMinimizeButtonHint | Qt.WindowMaximizeButtonHint
            )
        # Set a placeholder central widget immediately so Windows doesn't create extra native windows
        self.setCentralWidget(QWidget(self))
        # Use a single hidden menu bar from the start so Qt doesn't create extra native windows on Windows
        try:
            from PyQt6.QtWidgets import QMenuBar
        except ImportError:
            from PyQt5.QtWidgets import QMenuBar
        _mb = QMenuBar(self)
        _mb.setVisible(False)
        if hasattr(_mb, "setNativeMenuBar"):
            _mb.setNativeMenuBar(False)
        self.setMenuBar(_mb)
        _mb.setMinimumHeight(0)
        _mb.setMaximumHeight(0)
        # Set the real app icon in the taskbar/title bar
        try:
            _win_icon_pix = _get_curax_icon_pixmap(64)
            if _win_icon_pix and not _win_icon_pix.isNull():
                try:
                    from PyQt6.QtGui import QIcon
                except ImportError:
                    from PyQt5.QtGui import QIcon
                self.setWindowIcon(QIcon(_win_icon_pix))
        except Exception:
            pass
        self.setMinimumSize(640, 480)
        self._initial_theme = getattr(self.controller, "appearance_theme", "light") or "light"
        # Allow paintEvent to render background (do NOT block with stylesheet bg)
        try:
            self.setAttribute(Qt.WidgetAttribute.WA_OpaquePaintEvent, True)
        except AttributeError:
            self.setAttribute(Qt.WA_OpaquePaintEvent, True)

        central = QWidget(self)
        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        # ── Left column: just the hamburger strip ───────────────
        self.left_column = QWidget(central)
        self.left_column.setObjectName("leftColumn")
        self._sidebar_expanded_width = 192
        self._sidebar_collapsed_width = 0   # no strip — hamburger is in topbar
        self.left_column.setFixedWidth(0)
        self.left_column.setVisible(False)  # fully hidden
        left_layout = QVBoxLayout(self.left_column)
        left_layout.setContentsMargins(4, 4, 4, 4)
        left_layout.setSpacing(0)
        left_layout.setAlignment(Qt.AlignmentFlag.AlignTop)

        # Menu button
        self.menu_btn = MenuIconLabel()
        self.menu_btn.setObjectName("menuButton")
        # Height must match topbar from frame 1 so brand_frame fills the bar (no dead strip above)
        self.menu_btn.setFixedSize(34, 64)
        self.menu_btn.clicked.connect(self._toggle_sidebar)
        # menu_btn goes into topbar brand_lo — NOT left_layout

        # ── Sidebar: overlay drawer (parented to central, not in layout) ──
        self.sidebar = QFrame(central)
        self.sidebar.setObjectName("sidebarFrame")
        self.sidebar.setFixedWidth(self._sidebar_expanded_width)
        sidebar_layout = QVBoxLayout(self.sidebar)
        sidebar_layout.setContentsMargins(8, 10, 8, 10)
        sidebar_layout.setSpacing(4)

        SIDEBAR_RADIUS = 8
        SIDEBAR_ROW_HEIGHT = 28
        self._sidebar_radius = SIDEBAR_RADIUS
        self._sidebar_row_height = SIDEBAR_ROW_HEIGHT

        def _section_title(text):
            lbl = QLabel(text.upper())
            lbl.setStyleSheet(
                "font-size: 8px; letter-spacing: 1.5px; color: rgba(13,148,136,0.70);"
                "font-weight: 700; padding: 4px 2px 2px 2px; background: transparent;"
            )
            return lbl

        # Admin status badge
        self.admin_status = QPushButton("Features Locked")
        self.admin_status.setObjectName("sidebarStatusPill")
        self.admin_status.setCursor(Qt.CursorShape.PointingHandCursor)
        self.admin_status.setStyleSheet(
            f"background: #475569; color: #F8FAFC; padding: 6px 10px; min-height: {SIDEBAR_ROW_HEIGHT}px; "
            "border: none; border-radius: 10px; font-weight: 700; font-size: 9pt;"
        )
        self.admin_status.clicked.connect(self._on_admin_status_clicked)
        sidebar_layout.addWidget(self.admin_status)

        sidebar_nav_style = (
            "QPushButton { background-color: #F3F8F5; color: #10372B; text-align: left;"
            f"padding: 6px 10px; min-height: {SIDEBAR_ROW_HEIGHT-2}px;"
            f"border-radius: {SIDEBAR_RADIUS}px; border: 1px solid #C8DDD8; font-size: 9pt; font-weight: 600; }}"
            "QPushButton:hover { background-color: #E5F2EC; border: 1px solid #A8C9C0; }"
        )

        self.esp32_btn = QPushButton("ESP32")
        self.esp32_btn.setVisible(False)
        self.esp32_btn.setObjectName("esp32ToggleBtn")
        self.esp32_btn.clicked.connect(self._toggle_esp32_section)
        sidebar_layout.addWidget(self.esp32_btn)

        # COM frame
        self.com_frame = QGroupBox("")
        com_layout = QVBoxLayout(self.com_frame)
        com_layout.setSpacing(4)
        self.port_combo = QComboBox()
        self.port_combo.setObjectName("portCombo")
        self.port_combo.setMinimumWidth(120)
        self.port_combo.setMinimumHeight(26)
        try:
            self.port_combo.setPlaceholderText("Select Port")
        except Exception:
            pass
        self.port_combo.addItems(self.controller.get_available_ports())
        com_layout.addWidget(self.port_combo)

        self.port_connected_label = QLabel("")
        self.port_connected_label.setStyleSheet(f"color: #0D9488; font-size: 9pt; font-weight: 700;")
        self.port_connected_label.setVisible(False)
        com_layout.addWidget(self.port_connected_label)

        self.connect_btn = QPushButton("Connect ESP32")
        self.connect_btn.setMinimumWidth(120)
        self.connect_btn.setMinimumHeight(24)
        self.connect_btn.clicked.connect(self._connect_esp32)
        com_layout.addWidget(self.connect_btn)

        self.auth_btn = QPushButton("Authenticate")
        self.auth_btn.setMinimumWidth(120)
        self.auth_btn.setMinimumHeight(24)
        self.auth_btn.clicked.connect(self._show_pin_dialog)
        self.auth_btn.setEnabled(False)
        com_layout.addWidget(self.auth_btn)

        self.unlock_without_device_btn = QPushButton("Unlock without device")
        self.unlock_without_device_btn.setMinimumWidth(120)
        self.unlock_without_device_btn.setMinimumHeight(24)
        self.unlock_without_device_btn.clicked.connect(self._unlock_without_device)
        self.unlock_without_device_btn.setVisible(False)
        com_layout.addWidget(self.unlock_without_device_btn)

        self.com_status = QLabel("Disconnected")
        self.com_status.setStyleSheet("color: #EF4444; font-size: 8pt;")
        self.com_status.setMinimumHeight(14)
        com_layout.addWidget(self.com_status)

        self.com_frame.setVisible(False)
        sidebar_layout.addWidget(self.com_frame)

        # Admin unlock frame
        self.unlock_admin_frame = QFrame(self.sidebar)
        self.unlock_admin_frame.setObjectName("unlockAdminFrame")
        unlock_admin_layout = QVBoxLayout(self.unlock_admin_frame)
        unlock_admin_layout.setContentsMargins(0, 0, 0, 0)
        unlock_admin_layout.setSpacing(0)
        self._unlock_admin_hint = QLabel("No medicine box needed.")
        self._unlock_admin_hint.setStyleSheet("color: #94A3B8; font-size: 9pt; background: transparent;")
        self._unlock_admin_hint.setWordWrap(True)
        unlock_admin_layout.addWidget(self._unlock_admin_hint)
        self.unlock_with_password_btn = QPushButton("Unlock with password")
        self.unlock_with_password_btn.setMinimumHeight(26)
        self.unlock_with_password_btn.setStyleSheet(
            "QPushButton { padding: 8px 12px; font-size: 9pt; font-weight: 700; "
            "border: none; border-radius: 10px; }"
        )
        self.unlock_with_password_btn.clicked.connect(self._unlock_without_device)
        unlock_admin_layout.addWidget(self.unlock_with_password_btn)
        self.unlock_admin_frame.setVisible(False)
        self.unlock_admin_frame.setStyleSheet(
            "QFrame#unlockAdminFrame { background-color: transparent; "
            "border: none; margin-top: 2px; padding: 0px; }"
        )
        sidebar_layout.addWidget(self.unlock_admin_frame)

        self.link_admin_access_btn = QPushButton("🔗 Link with code from app")
        self.link_admin_access_btn.setMinimumHeight(28)
        self.link_admin_access_btn.setStyleSheet(
            "QPushButton { background-color: #1E40AF; color: #fff; border: none; border-radius: 8px; "
            "padding: 8px 12px; font-size: 9pt; font-weight: 700; } "
            "QPushButton:hover { background-color: #1D4ED8; } "
            "QPushButton:pressed { background-color: #1E3A8A; }"
        )
        self.link_admin_access_btn.clicked.connect(self._link_admin_desktop_code_dialog)
        self.link_admin_access_btn.setVisible(False)
        self.link_admin_access_btn.setToolTip(
            "Enter the one-time code from the admin app (Settings → Desktop linking code)."
        )
        sidebar_layout.addWidget(self.link_admin_access_btn)

        # Tools section
        self.tools_section_title = _section_title("Tools")
        self.tools_section_title.setVisible(False)
        sidebar_layout.addWidget(self.tools_section_title)
        self.tool_group = QGroupBox("")
        self.tool_group.setVisible(False)
        tool_layout = QVBoxLayout(self.tool_group)
        for label, handler in [
            ("Check Medicine Data", self._check_medicine_data),
            ("Alert System Status", self._check_alert_status),
        ]:
            btn = QPushButton(label)
            btn.clicked.connect(handler)
            tool_layout.addWidget(btn)
        self.sidebar_test_alert_btn = QPushButton("Test Alert")
        self.sidebar_test_alert_btn.setMinimumHeight(22)
        self.sidebar_test_alert_btn.clicked.connect(self._on_sidebar_test_alert_clicked)
        tool_layout.addWidget(self.sidebar_test_alert_btn)
        sidebar_layout.addWidget(self.tool_group)

        self.admin_section_title = _section_title("Admin")
        self.admin_section_title.setVisible(False)
        sidebar_layout.addWidget(self.admin_section_title)

        self.admin_quick_btn = QPushButton("Admin Login / Logout")
        self.admin_quick_btn.clicked.connect(self._admin_quick_action)
        self.admin_quick_btn.setStyleSheet(
            "background-color: #0D9488; color: #fff; font-weight: 600; font-size: 10pt; "
            "padding: 6px 10px; border-radius: 8px; text-align: center;"
        )
        self.admin_quick_btn.setVisible(False)
        sidebar_layout.addSpacing(4)
        sidebar_layout.addWidget(self.admin_quick_btn)

        self.sidebar_my_codes_btn = QPushButton("My codes")
        self.sidebar_my_codes_btn.setMinimumHeight(26)
        self.sidebar_my_codes_btn.clicked.connect(self._on_sidebar_my_codes_clicked)
        self.sidebar_my_codes_btn.setVisible(False)
        sidebar_layout.addWidget(self.sidebar_my_codes_btn)

        # Lines animation fills all remaining empty space at the bottom
        # Collapsed filler — just enough space to look clean, no stretch
        sidebar_layout.addStretch(1)
        self._sidebar_grid = SidebarGridAnimation(self.sidebar)
        self._sidebar_grid.setFixedHeight(32)
        sidebar_layout.addWidget(self._sidebar_grid)

        # Sidebar is NOT in any layout — overlays content area as floating drawer
        # left_column no longer in layout (hamburger moved to topbar)

        # ── Right content area ────────────────────────────────────
        content = QWidget(central)
        content.setMinimumWidth(400)
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(20, 8, 20, 12)
        content_layout.setSpacing(6)

        # ── Top bar — exact match to curax-locked.html .topbar ──────────────────
        topbar = QFrame(content)
        topbar.setObjectName("topBar")
        topbar.setFixedHeight(64)
        topbar.setStyleSheet("""
            QFrame#topBar {
                background-color: #ffffff;
                border-bottom: 1px solid #E2E8F0;
            }
        """)
        self._topbar_frame = topbar
        topbar.setStyleSheet("""
            QFrame#topBar {
                background-color: #ffffff;
                border-bottom: 1px solid #E2E8F0;
            }
        """)
        topbar_layout = QHBoxLayout(topbar)
        topbar_layout.setContentsMargins(0, 0, 16, 0)
        topbar_layout.setSpacing(8)

        # ── Left: brand — fixed minimum width so center stays truly centred ──
        brand_frame = QFrame(topbar)
        self._brand_frame = brand_frame
        brand_frame.setStyleSheet("background: transparent; border: none;")
        brand_frame.setMinimumWidth(160)
        try:
            brand_frame.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        except AttributeError:
            brand_frame.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Fixed)
        brand_lo = QHBoxLayout(brand_frame)
        brand_lo.setContentsMargins(10, 0, 0, 0)
        brand_lo.setSpacing(10)
        try:
            brand_lo.setAlignment(Qt.AlignmentFlag.AlignVCenter)
        except AttributeError:
            brand_lo.setAlignment(Qt.AlignVCenter)

        brand_icon = QLabel()
        self._brand_icon = brand_icon
        brand_icon.setFixedSize(44, 44)
        brand_icon.setAlignment(Qt.AlignmentFlag.AlignCenter)
        brand_icon.setStyleSheet("background: transparent; border: none; padding: 0px;")
        # Load the real CuraX Android app icon
        _icon_pix = _get_curax_icon_pixmap(42)
        if _icon_pix and not _icon_pix.isNull():
            brand_icon.setPixmap(_icon_pix)
        else:
            # Fallback: teal-orange gradient with cross
            brand_icon.setStyleSheet("""
                background: qlineargradient(x1:0,y1:0,x2:1,y2:1,
                    stop:0 #0d9488, stop:0.5 #f97316, stop:1 #0d9488);
                border-radius: 11px; font-size: 15pt;
                font-weight: 800; color: white;
            """)
            brand_icon.setText("✚")
        try:
            _glow = QGraphicsDropShadowEffect()
            _glow.setBlurRadius(18)
            _glow.setColor(QColor(13, 148, 136, 120))
            _glow.setOffset(0, 2)
            brand_icon.setGraphicsEffect(_glow)
        except Exception:
            pass

        self._brand_name = QLabel()
        brand_name = self._brand_name
        brand_name.setStyleSheet("background: transparent; border: none;")
        brand_name.setText(
            '<span style="font-size:15pt;font-weight:800;color:#0a3d38;">Cura</span>'
            '<span style="font-size:15pt;font-weight:800;color:#0d9488;">X</span>'
        )
        brand_name.setTextFormat(Qt.TextFormat.RichText)

        brand_lo.addWidget(self.menu_btn)   # hamburger first in topbar
        brand_lo.addSpacing(6)
        brand_lo.addWidget(brand_icon)
        brand_lo.addWidget(brand_name)
        brand_lo.addStretch()

        # ── Center: title as plain bold label, no border, absolutely centred ─
        # Use a stretch-1 spacer on each side with the label in between (stretch 2)
        # so the label is always the midpoint regardless of left/right widths
        topbar_layout.addWidget(brand_frame, 1)   # stretch 1

        self._center_title = QLabel("Intelligent Medicine Management System")
        self._center_title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._center_title.setWordWrap(False)
        self._center_title.setStyleSheet("""
            background: transparent;
            border: none;
            font-size: 11pt;
            font-weight: 700;
            color: #0d6b61;
            letter-spacing: 0.5px;
        """)
        try:
            self._center_title.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        except AttributeError:
            self._center_title.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        topbar_layout.addWidget(self._center_title, 2, Qt.AlignmentFlag.AlignCenter)  # stretch 2

        # ── Right: status pill + theme toggle — same stretch as left ─────────
        right_frame = QFrame(topbar)
        self._right_frame = right_frame
        right_frame.setStyleSheet("background: transparent; border: none;")
        right_frame.setMinimumWidth(160)
        try:
            right_frame.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        except AttributeError:
            right_frame.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Fixed)
        right_lo = QHBoxLayout(right_frame)
        right_lo.setContentsMargins(0, 0, 0, 0)
        right_lo.setSpacing(8)
        right_lo.addStretch()   # push everything to the right

        self._status_pill = QFrame(right_frame)
        self._status_pill.setObjectName("statusPill")
        self._status_pill.setStyleSheet("""
            QFrame#statusPill {
                background-color: #ffffff;
                border: 1px solid #E2E8F0;
                border-radius: 14px;
            }
        """)
        try:
            self._status_pill.setSizePolicy(QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Fixed)
        except AttributeError:
            self._status_pill.setSizePolicy(QSizePolicy.Maximum, QSizePolicy.Fixed)
        pill_lo = QHBoxLayout(self._status_pill)
        pill_lo.setContentsMargins(10, 5, 10, 5)
        pill_lo.setSpacing(6)

        self._status_dot = QLabel()
        self._status_dot.setFixedSize(8, 8)
        self._status_dot.setStyleSheet(
            "background: #ef4444; border-radius: 4px; border: none;"
        )
        self.locked_status = QLabel("System Locked")
        self.locked_status.setStyleSheet(
            "font-size: 8pt; font-weight: 500; color: #0d6b61; "
            "background: transparent; border: none;"
        )
        try:
            self.locked_status.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        except AttributeError:
            self.locked_status.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Fixed)
        pill_lo.addWidget(self._status_dot)
        pill_lo.addWidget(self.locked_status)

        self.theme_toggle_global = ThemeToggle(self)
        self.theme_toggle_global.setFixedSize(56, 28)
        self.theme_toggle_global.setToolTip("Switch theme (light/dark)")

        self._power_btn = PowerButton()
        self._power_btn.set_dark(False)
        self._power_btn.setVisible(False)   # shown only when unlocked
        self._power_btn.sleep_requested.connect(self._power_sleep)
        self._power_btn.shutdown_requested.connect(self._power_shutdown)

        right_lo.addWidget(self._status_pill)
        right_lo.addWidget(self.theme_toggle_global)
        right_lo.addSpacing(4)
        right_lo.addWidget(self._power_btn)

        topbar_layout.addWidget(right_frame, 1)   # stretch 1 — mirrors left

        self._menu_hit_zone = _TopBarMenuHitZone(self, topbar)

        self._topbar_scale = 1.0
        self._content_scale = 1.0

        # Keep these for compatibility with rest of codebase (must have parent=content so they are not top-level windows)
        self.locked_title = GradientTitleLabel("", content)
        self.locked_title.setVisible(False)
        self.locked_subtitle = QLabel("", content)
        self.locked_subtitle.setVisible(False)
        self.theme_save_icon_btn = QPushButton(content)
        self.theme_save_icon_btn.setVisible(False)
        self.theme_save_icon_btn.clicked.connect(self._save_theme_for_next_time)

        content_layout.setContentsMargins(0, 0, 0, 0)
        content_layout.setSpacing(0)
        content_layout.addWidget(topbar)

        # Stacked widget: locked | main tabs
        self.stacked = QStackedWidget()
        self.locked_screen_widget = LockedScreen(self.stacked)  # parent=stacked prevents taskbar entry
        self.stacked.addWidget(self.locked_screen_widget)
        main_content = QWidget(self.stacked)
        main_inner = QVBoxLayout(main_content)
        main_inner.setContentsMargins(0, 2, 0, 0)
        main_inner.setSpacing(0)
        self.tabs = QTabWidget()
        self.tabs.setDocumentMode(True)
        self.tabs.setStyleSheet(
            "QTabWidget::pane { border: none; border-top: 3px solid #000000; background: #F1F5F9; margin-top: 0; }"
            "QTabBar { background: #ffffff; border: none; border-bottom: 3px solid #000000; }"
            "QTabBar::tab {"
            "  min-width: 100px; padding: 12px 22px; margin: 0;"
            "  font-size: 10pt; font-weight: 600; color: #64748B;"
            "  background: transparent; border: none;"
            "  border-bottom: 3px solid transparent;"
            "}"
            "QTabBar::tab:selected { color: #0F766E; border-bottom: 3px solid #0D9488; }"
            "QTabBar::tab:hover { color: #0D9488; background: #F0FDFA; }"
        )
        self.tabs.currentChanged.connect(self._on_tab_change_guard)
        # Pass parent=self explicitly so tabs are never parentless top-level windows
        # Same tab order/names as admin Android app: Dashboard · Users · Alerts · Reports · Connections · Settings
        self._tab_dashboard = AdminDashboardTab(controller, self, parent=self.tabs)
        self.tabs.addTab(self._tab_dashboard, "Dashboard")
        self.tabs.addTab(AdminUsersTab(controller, self, parent=self.tabs), "Users")
        self.tabs.addTab(AdminAlertsTab(controller, self, parent=self.tabs), "Alerts")
        self.tabs.addTab(AdminReportsTab(controller, self, parent=self.tabs), "Reports")
        self.tabs.addTab(AdminConnectionsTab(controller, self, parent=self.tabs), "Connections")
        self._tab_settings = AdminHubSettingsTab(controller, self, parent=self.tabs)
        self.tabs.addTab(self._tab_settings, "Settings")
        self._overview_tab_index = 0
        self._users_tab_index = 1
        self._alerts_tab_index = 2
        self._reports_tab_index = 3
        self._connections_tab_index = 4
        self._settings_tab_index = 5
        self._care_tabs = QTabWidget()
        self._care_tabs.setDocumentMode(True)
        self._care_tabs.setStyleSheet(self.tabs.styleSheet())
        self._care_tabs.currentChanged.connect(self._on_tab_change_guard)
        self._care_tabs_built = False
        self._tab_mode_stack = QStackedWidget()
        self._tab_mode_stack.addWidget(self.tabs)
        self._tab_mode_stack.addWidget(self._care_tabs)
        self._care_banner = QFrame()
        self._care_banner.setObjectName("careBanner")
        self._care_banner.setStyleSheet(
            "QFrame#careBanner { background: #0F766E; border-radius: 0; }"
            "QLabel { color: white; font-weight: 600; background: transparent; }"
        )
        self._care_banner.hide()
        cb_lo = QHBoxLayout(self._care_banner)
        cb_lo.setContentsMargins(16, 8, 16, 8)
        self._care_banner_label = QLabel("")
        self._care_banner_label.setWordWrap(True)
        cb_lo.addWidget(self._care_banner_label, 1)
        self._care_exit_btn = QPushButton("Return to Admin")
        self._care_exit_btn.setStyleSheet(
            "background: white; color: #0F766E; font-weight: 700; padding: 6px 14px; border-radius: 8px;"
        )
        self._care_exit_btn.clicked.connect(self.show_hub_mode_ui)
        cb_lo.addWidget(self._care_exit_btn)
        main_inner.addWidget(self._care_banner)
        main_inner.addWidget(self._tab_mode_stack, 1)
        if hasattr(controller, "care_mode_changed"):
            controller.care_mode_changed.connect(self._on_care_mode_changed)
        self.stacked.addWidget(main_content)
        content_layout.addWidget(self.stacked, 1)
        main_layout.addWidget(content, 1)

        # ── Footer bar — exact match to curax-locked.html .bottombar ───────────
        self._footer_bar = QFrame(self)
        self._footer_bar.setObjectName("footerBar")
        self._footer_bar.setFixedHeight(36)
        footer_layout = QHBoxLayout(self._footer_bar)
        footer_layout.setContentsMargins(32, 0, 32, 0)
        footer_layout.setSpacing(0)

        self._footer_left = QLabel("© 2026 CuraX Medical Systems")
        self._footer_left.setObjectName("footerLabel")
        self._footer_left.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        try:
            self._footer_left.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        except AttributeError:
            self._footer_left.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Fixed)

        self._footer_sync = QLabel("🕐 Last sync: just now")
        self._footer_sync.setObjectName("footerLabel")
        self._footer_sync.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self._footer_right = QLabel("All rights reserved")
        self._footer_right.setObjectName("footerLabel")
        self._footer_right.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)

        # Equal stretch(1) between every single item — perfectly even spacing, no separators
        footer_layout.addWidget(self._footer_left,    0, Qt.AlignmentFlag.AlignVCenter)
        footer_layout.addStretch(1)
        footer_layout.addWidget(self._footer_sync,    0, Qt.AlignmentFlag.AlignVCenter)
        footer_layout.addStretch(1)
        footer_layout.addWidget(self._footer_right,   0, Qt.AlignmentFlag.AlignVCenter)

        self._footer_bar.setStyleSheet(
            "QFrame#footerBar { background-color: rgba(255,255,255,0.80); "
            "border-top: 1px solid rgba(160,170,180,0.22); }"
            "QLabel#footerLabel { font-size: 8pt; color: #6b7280; "
            "background: transparent; font-weight: 500; }"
        )

        # Live clock — updates every second so footer feels alive
        self._footer_clock_timer = QTimer(self)
        self._footer_clock_timer.timeout.connect(self._update_footer_clock)
        self._footer_clock_timer.start(1000)
        self._update_footer_clock()  # set immediately

        # Wrap central widget + footer in a container
        _footer_wrapper = QWidget(self)
        _fw_layout = QVBoxLayout(_footer_wrapper)
        _fw_layout.setContentsMargins(0, 0, 0, 0)
        _fw_layout.setSpacing(0)
        _fw_layout.addWidget(central, 1)
        _fw_layout.addWidget(self._footer_bar, 0)
        self.setCentralWidget(_footer_wrapper)

        # ── Sidebar overlay: float over entire footer_wrapper, positioned below topbar ──
        self.sidebar.setParent(_footer_wrapper)
        self.sidebar.raise_()
        self.sidebar.setVisible(False)
        self.sidebar_visible = False

        # Sidebar animation — slides in/out horizontally
        try:
            from PyQt6.QtCore import QPropertyAnimation, QRect, QEasingCurve
        except ImportError:
            from PyQt5.QtCore import QPropertyAnimation, QRect, QEasingCurve
        self._sidebar_anim = QPropertyAnimation(self.sidebar, b"geometry")
        self._sidebar_anim.setDuration(220)
        try:
            self._sidebar_anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        except AttributeError:
            self._sidebar_anim.setEasingCurve(QEasingCurve.OutCubic)

        pass  # overlay sidebar — left_column already set

        self._connect_signals()
        self._update_status_text()
        self._apply_locked_state()
        self._refresh_port_combo()
        self._update_admin_status_label()
        self._add_menu()
        self.apply_theme(self._initial_theme)
        self._header_overlay_phase = 0.0
        self._header_anim_timer = QTimer(self)
        self._header_anim_timer.timeout.connect(self._animate_header_overlay)
        self._header_anim_timer.start(70)
        self._system_startup_alert_sent = False
        # system_started: on app open (not on unlock)
        QTimer.singleShot(2500, self._send_startup_alert_to_bot)
        QTimer.singleShot(8000, self._send_startup_alert_to_bot)
        if hasattr(self.controller, "central_fetch_done"):
            self.controller.central_fetch_done.connect(self._on_central_data_for_startup_alert)
        # Apply topbar scale after first layout so small window gets smaller toggle/status even before resize
        QTimer.singleShot(100, self._update_topbar_scale)

    # ── All original methods preserved below (no logic changes) ──────────────

    def _desktop_alert_user_label(self):
        try:
            from ui.tabs.admin_api import admin_display_name
            return admin_display_name(self.controller)
        except Exception:
            return "Admin"

    def _desktop_has_admin_link(self) -> bool:
        try:
            has_admin, _ = self._get_desktop_role()
            if not has_admin:
                return False
            db = getattr(self.controller, "_db", None)
            if db and hasattr(db, "get") and (db.get("admin_access_code") or "").strip():
                return True
            if getattr(self.controller, "admin_bot", None):
                ab = self.controller.admin_bot or {}
                if (ab.get("bot_id") or "").strip() and (ab.get("api_key") or "").strip():
                    return True
            return False
        except Exception:
            return False

    def _mark_startup_alert_sent(self):
        self._system_startup_alert_sent = True

    def _on_central_data_for_startup_alert(self, _payload=None):
        try:
            db = self.controller.get_db()
            if db and hasattr(db, "get_admin_info"):
                info = db.get_admin_info() or {}
                n = (info.get("name") or "").strip()
                if n:
                    self.controller.logged_in_admin_name = n
        except Exception:
            pass
        self._update_admin_status_label()
        if not getattr(self, "_system_startup_alert_sent", False):
            self._send_startup_alert_to_bot()

    def _send_startup_alert_to_bot(self):
        """Once per app session when desktop opens — does not require unlock."""
        try:
            if getattr(self, "_system_startup_alert_sent", False):
                return
            if not self._desktop_has_admin_link():
                return
            label = self._desktop_alert_user_label()
            if self.controller.send_admin_alert(
                "system_started",
                f"{label}'s desktop: CuraX started",
                on_success=self._mark_startup_alert_sent,
            ):
                pass
        except Exception:
            pass

    def _send_unlocked_alert_to_bot(self):
        """Once per unlock click — only when user unlocks the desktop."""
        try:
            if not getattr(self.controller, "authenticated", False):
                return
            if not self._desktop_has_admin_link():
                return
            label = self._desktop_alert_user_label()
            self.controller.send_admin_alert(
                "system_unlocked",
                f"{label}'s desktop: system unlocked",
            )
        except Exception:
            pass

    def _add_menu(self):
        # Menu bar already set in __init__ as hidden; ensure it stays hidden and uses no vertical strip
        mb = self.menuBar()
        if mb is not None:
            mb.setVisible(False)
            mb.setMinimumHeight(0)
            mb.setMaximumHeight(0)

    def _sidebar_geo(self):
        """Sidebar rect: x=0, y=topbar bottom, height=content only (no topbar, no footer)."""
        try:
            from PyQt6.QtCore import QRect
        except ImportError:
            from PyQt5.QtCore import QRect
        w = getattr(self, "_sidebar_expanded_width", 192)
        parent = self.sidebar.parent()
        parent_h = parent.height() if parent else self.height()
        try:
            topbar_h = self._topbar_frame.height() if getattr(self, "_topbar_frame", None) else 64
        except Exception:
            topbar_h = 64
        topbar_h = max(36, topbar_h)
        footer_h = 32   # fixed footer height
        sidebar_h = max(60, parent_h - topbar_h - footer_h)
        return QRect(0, topbar_h, w, sidebar_h)

    def _toggle_sidebar(self):
        self.sidebar_visible = not self.sidebar_visible
        w = getattr(self, "_sidebar_expanded_width", 218)
        end_geo = self._sidebar_geo()
        try:
            from PyQt6.QtCore import QRect
        except ImportError:
            from PyQt5.QtCore import QRect
        start_geo = QRect(-w, end_geo.y(), w, end_geo.height())

        if self.sidebar_visible:
            self.sidebar.setVisible(True)
            self.sidebar.raise_()
            self._sidebar_anim.stop()
            self._sidebar_anim.setStartValue(start_geo)
            self._sidebar_anim.setEndValue(end_geo)
            self._sidebar_anim.start()
            if not getattr(self.controller, "authenticated", False) and not getattr(self.controller, "connected", False):
                _, desktop_linked = self._get_desktop_role()
                if hasattr(self, "unlock_without_device_btn") and not desktop_linked:
                    self.unlock_without_device_btn.setVisible(True)
        else:
            self._sidebar_anim.stop()
            self._sidebar_anim.setStartValue(end_geo)
            self._sidebar_anim.setEndValue(start_geo)
            self._sidebar_anim.finished.connect(
                lambda: self.sidebar.setVisible(False) if not self.sidebar_visible else None
            )
            self._sidebar_anim.start()

        # Update hamburger icon
        if hasattr(self, "_menu_close_pixmap") and hasattr(self, "_menu_hamburger_pixmap"):
            self.menu_btn.setPixmap(
                self._menu_close_pixmap if self.sidebar_visible else self._menu_hamburger_pixmap
            )

    def _toggle_esp32_section(self):
        self.com_frame.setVisible(not self.com_frame.isVisible())

    def _on_global_theme_toggle(self, theme: str):
        self.controller.appearance_theme = theme
        self.apply_theme(theme)

    def _save_theme_for_next_time(self):
        theme = getattr(self.controller, "appearance_theme", "light") or "light"
        self.controller.save_appearance_theme(theme)
        if hasattr(self, "_footer_center"): pass  # theme saved silently

    def _connect_signals(self):
        self.controller.connected_changed.connect(self._on_connected_changed)
        self.controller.authenticated_changed.connect(self._on_authenticated_changed)
        self.controller.admin_status_changed.connect(self._update_admin_status_label)
        self.controller.linked_user_changed.connect(self._update_admin_status_label)
        if hasattr(self.controller, "linked_user_deleted_by_admin"):
            self.controller.linked_user_deleted_by_admin.connect(self._on_linked_user_deleted_by_admin)
        self.controller.status_message.connect(self._on_status_message)
        if hasattr(self, "theme_toggle_global"):
            self.theme_toggle_global.theme_changed.connect(self._on_global_theme_toggle)
        if hasattr(self.controller, "test_alert_done"):
            self.controller.test_alert_done.connect(self._on_sidebar_test_alert_done)

    def _on_status_message(self, msg: str):
        if not msg:
            return
        relay_url = getattr(self.controller, "get_relay_alert_url", lambda: None)()
        if relay_url:
            try:
                import requests
                requests.post(relay_url, json={"alert": msg}, timeout=2)
            except Exception:
                if not getattr(self, "_relay_fail_logged", False):
                    self._relay_fail_logged = True
        # status message logged (no status bar)

    def _on_connected_changed(self, connected):
        self._update_status_text()
        self.auth_btn.setEnabled(connected)
        if connected:
            port = (self.controller.get_connected_port() or self.port_combo.currentText() or "").strip() or "ESP32"
            self.com_status.setText(f"Connected to {port}")
            self.com_status.setStyleSheet("color: #0D9488; font-size: 8pt; font-weight: 600;")
            self.port_connected_label.setText(f"Port: {port}")
            self.port_connected_label.setVisible(True)
            self.connect_btn.setText("Disconnect ESP32")
            self._refresh_port_combo()
            if port and self.port_combo.findText(port) < 0:
                self.port_combo.insertItem(0, port)
            if port:
                self.port_combo.setCurrentText(port)
        else:
            self.com_status.setText("Disconnected")
            self.com_status.setStyleSheet("color: #EF4444; font-size: 8pt;")
            self.port_connected_label.setText("")
            self.port_connected_label.setVisible(False)
            self.connect_btn.setText("Connect ESP32")
            self._refresh_port_combo()
            _, desktop_linked = self._get_desktop_role()
            if hasattr(self, "unlock_without_device_btn") and not desktop_linked:
                self.unlock_without_device_btn.setVisible(True)
        if hasattr(self, "unlock_without_device_btn") and connected:
            self.unlock_without_device_btn.setVisible(False)

    def _on_authenticated_changed(self, authenticated):
        self._update_status_text()
        self._apply_locked_state()
        if authenticated:
            self._send_unlocked_alert_to_bot()
            self._update_admin_status_label()
        else:
            fn = getattr(self.controller, "clear_desktop_unlock_alert_dedupe", None)
            if fn:
                fn()
        if authenticated and hasattr(self, "com_frame") and self.com_frame.isVisible():
            self.com_frame.setVisible(False)
        self.tools_section_title.setVisible(authenticated)
        self.tool_group.setVisible(authenticated)
        has_admin = False
        try:
            db = self.controller.get_db()
            has_admin = db.has_admin_credentials() if db else False
        except Exception:
            pass
        admin_visible = authenticated and (has_admin or getattr(self.controller, "admin_logged_in", False))
        if hasattr(self, "admin_section_title"):
            self.admin_section_title.setVisible(admin_visible)
        if hasattr(self, "admin_quick_btn"):
            self.admin_quick_btn.setVisible(False)

    def _on_sidebar_test_alert_clicked(self):
        if not self._require_unlocked(): return
        if hasattr(self, "sidebar_test_alert_btn"):
            self.sidebar_test_alert_btn.setEnabled(False)
            self.sidebar_test_alert_btn.setText("Sending...")
        import threading
        def work():
            ok, err = self.controller.send_admin_alert_test()
            if hasattr(self.controller, "test_alert_done"):
                try:
                    self.controller.test_alert_done.emit(ok, err or "")
                except Exception:
                    pass
        threading.Thread(target=work, daemon=True).start()

    def _on_sidebar_test_alert_done(self, success, error_message):
        if hasattr(self, "sidebar_test_alert_btn"):
            self.sidebar_test_alert_btn.setEnabled(True)
            self.sidebar_test_alert_btn.setText("Test Alert")
        if success:
            if hasattr(self.controller, "status_message"):
                self.controller.status_message.emit("Test alert sent. Check your Android app.")
        else:
            if hasattr(self.controller, "status_message"):
                self.controller.status_message.emit("Test alert failed: " + (error_message or "Unknown error"))

    def _on_sidebar_my_codes_clicked(self):
        if not self._require_unlocked(): return
        try:
            db = self.controller.get_db()
            access_code = (db.get("admin_access_code") if hasattr(db, "get") else None) or ""
            connection_code = (db.get("admin_connection_code") if hasattr(db, "get") else None) or ""
            if getattr(self.controller, "admin_logged_in", False) and (not (access_code or "").strip() or not (connection_code or "").strip()):
                ac, cc = self.controller.get_admin_codes_from_backend()
                if ac or cc:
                    access_code = (ac or "").strip() or access_code
                    connection_code = (cc or "").strip() or connection_code
                    if hasattr(db, "set"):
                        if access_code:
                            db.set("admin_access_code", access_code)
                        if connection_code:
                            db.set("admin_connection_code", connection_code)
        except Exception:
            access_code = ""
            connection_code = ""

        dlg = QDialog(self)
        dlg.setWindowTitle("My codes")
        layout = QVBoxLayout(dlg)
        layout.addWidget(QLabel("Admin Access Code:"))
        access_edit = QLineEdit()
        access_edit.setReadOnly(True)
        access_edit.setText((access_code or "").strip())
        try:
            access_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except Exception:
            access_edit.setEchoMode(QLineEdit.Password)
        layout.addWidget(access_edit)
        layout.addWidget(QLabel("Connection Code:"))
        conn_edit = QLineEdit()
        conn_edit.setReadOnly(True)
        conn_edit.setText((connection_code or "").strip())
        try:
            conn_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except Exception:
            conn_edit.setEchoMode(QLineEdit.Password)
        layout.addWidget(conn_edit)
        codes_visible = [False]
        def toggle():
            try:
                nm = QLineEdit.EchoMode.Normal
                pm = QLineEdit.EchoMode.Password
            except Exception:
                nm = QLineEdit.Normal
                pm = QLineEdit.Password
            codes_visible[0] = not codes_visible[0]
            mode = nm if codes_visible[0] else pm
            access_edit.setEchoMode(mode)
            conn_edit.setEchoMode(mode)
            show_btn.setText("Hide codes" if codes_visible[0] else "Show codes")
        show_btn = QPushButton("Show codes")
        show_btn.clicked.connect(toggle)
        layout.addWidget(show_btn)
        dlg.exec()

    def _get_desktop_role(self):
        """Admin workstation only — no user / desktop_linked modes."""
        try:
            db = self.controller.get_db()
            has_admin = bool(
                db and (
                    db.has_admin_credentials()
                    or getattr(self.controller, "admin_logged_in", False)
                )
            )
            return (has_admin, False)
        except Exception:
            return (False, False)

    def _apply_locked_state(self):
        is_unlocked = bool(self.controller.authenticated)
        self.stacked.setCurrentIndex(1 if is_unlocked else 0)
        self.locked_title.setVisible(True)
        self.locked_subtitle.setVisible(True)
        self.locked_status.setVisible(True)
        self._sidebar_locked_mode = not is_unlocked
        has_admin, _ = self._get_desktop_role()

        if hasattr(self, "link_admin_access_btn"):
            self.link_admin_access_btn.setVisible(self._sidebar_locked_mode and not has_admin)

        if self._sidebar_locked_mode:
            self.sidebar_visible = False
            self.sidebar.setVisible(False)
            if hasattr(self, "_power_btn"):
                self._power_btn.setVisible(False)
            pass  # overlay sidebar — left_column already set

            if hasattr(self, "locked_screen_widget"):
                if hasattr(self.locked_screen_widget, "set_subtitle"):
                    self.locked_screen_widget.set_subtitle("")
                if hasattr(self.locked_screen_widget, "apply_locked_card_layout"):
                    # Card stays visible (lock + title); header pill carries link/unlock CTA.
                    self.locked_screen_widget.apply_locked_card_layout(show_title=True)

            if hasattr(self, "esp32_btn"):
                self.esp32_btn.setVisible(False)
            if hasattr(self, "com_frame"):
                self.com_frame.setVisible(False)
            if hasattr(self, "connect_btn"):
                self.connect_btn.setVisible(True)
            if hasattr(self, "port_combo"):
                self.port_combo.setVisible(True)

            if has_admin:
                if hasattr(self, "locked_screen_widget"):
                    self.locked_screen_widget.set_first_time_pulse(True)
                if hasattr(self, "unlock_admin_frame"):
                    self.unlock_admin_frame.setVisible(True)
                if hasattr(self, "_unlock_admin_hint"):
                    self._unlock_admin_hint.setVisible(False)
                if hasattr(self, "unlock_without_device_btn"):
                    self.unlock_without_device_btn.setVisible(False)
                try:
                    dbu = self.controller.get_db()
                    if hasattr(self, "unlock_with_password_btn") and dbu and getattr(dbu, "has_desktop_app_unlock_pin", lambda: False)():
                        self.unlock_with_password_btn.setText("Unlock with desktop PIN")
                        self.unlock_with_password_btn.setToolTip(
                            "PIN for this computer only. Not the same as your Curax mobile app PIN."
                        )
                    elif hasattr(self, "unlock_with_password_btn"):
                        self.unlock_with_password_btn.setText("Unlock with password")
                        self.unlock_with_password_btn.setToolTip("")
                except Exception:
                    pass
            else:
                if hasattr(self, "locked_screen_widget"):
                    self.locked_screen_widget.set_first_time_pulse(True)
                if hasattr(self, "unlock_admin_frame"):
                    self.unlock_admin_frame.setVisible(False)
                if hasattr(self, "_unlock_admin_hint"):
                    self._unlock_admin_hint.setVisible(False)
                if hasattr(self, "unlock_without_device_btn"):
                    self.unlock_without_device_btn.setVisible(False)
        else:
            # Always start closed when unlocking — user opens manually
            self.sidebar_visible = False
            self.sidebar.setVisible(False)
            if hasattr(self, "_power_btn"):
                self._power_btn.setVisible(True)
            if hasattr(self, "menu_btn") and hasattr(self, "_menu_hamburger_pixmap"):
                self.menu_btn.setPixmap(self._menu_hamburger_pixmap)
            if hasattr(self, "locked_screen_widget"):
                self.locked_screen_widget.set_first_time_pulse(False)
            if hasattr(self, "link_admin_access_btn"):
                self.link_admin_access_btn.setVisible(False)
            if hasattr(self, "esp32_btn"):
                self.esp32_btn.setVisible(False)
            if hasattr(self, "unlock_admin_frame"):
                self.unlock_admin_frame.setVisible(False)

        if hasattr(self, "menu_btn") and hasattr(self, "_menu_close_pixmap") and hasattr(self, "_menu_hamburger_pixmap"):
            self.menu_btn.setPixmap(
                self._menu_close_pixmap if getattr(self, "sidebar_visible", False) else self._menu_hamburger_pixmap
            )
            self.menu_btn.update()

    def _update_footer_clock(self):
        """Update the live clock and sync label in footer every second."""
        try:
            import datetime
            now = datetime.datetime.now()
            time_str = now.strftime("%H:%M:%S")
            if hasattr(self, "_footer_sync"):
                self._footer_sync.setText(f"🕒 {time_str}")
        except Exception:
            pass

    def _update_status_text(self):
        theme = getattr(self, "_current_theme", "light")
        is_ready = bool(self.controller.authenticated)
        base_style = self._status_pill_style(for_ready=is_ready)
        accent = ACCENT_LIGHT if theme == "light" else NEON_GREEN
        secondary = "#475569" if theme == "light" else "#94A3B8"
        has_admin, _ = self._get_desktop_role()
        if self.controller.authenticated:
            msg = "System Ready"
            color = accent
        else:
            msg = "Open menu (☰) → Link with code from app" if not has_admin else "Open menu (☰) → Unlock"
            color = secondary
        # Update the status pill text (font size from _topbar_scale for small screens)
        self.locked_status.setText(msg)
        self.locked_status.setVisible(True)
        clr_pill = "#2DD4BF" if theme == "dark" else "#0d6b61"
        ts = getattr(self, "_topbar_scale", 1.0)
        fs = max(5, min(9, int(8 * ts)))
        self.locked_status.setStyleSheet(
            f"font-size: {fs}pt; font-weight: 500; color: {clr_pill}; background: transparent; border: none;"
        )
        # Update dot: green when ready, red when locked
        if hasattr(self, "_status_dot"):
            dot_color = "#22c55e" if self.controller.authenticated else "#ef4444"
            self._status_dot.setStyleSheet(
                f"background: {dot_color}; border-radius: 4px; border: none;"
            )
        if hasattr(self, "locked_screen_widget") and hasattr(self.locked_screen_widget, "set_status"):
            self.locked_screen_widget.set_status("", color)

    def _update_admin_status_label(self):
        try:
            db = self.controller.get_db()
            has_admin = db.has_admin_credentials()
        except Exception:
            has_admin = False
            db = None

        if getattr(self.controller, "admin_logged_in", False):
            name = getattr(self.controller, "logged_in_admin_name", None) or "Admin"
            self.admin_status.setText(f"Admin: {name}")
        elif has_admin:
            try:
                info = db.get_admin_info() if db and hasattr(db, "get_admin_info") else None
                name = (info.get("name") or "Admin").strip() if info else "Admin"
            except Exception:
                name = "Admin"
            self.admin_status.setText(f"Admin: {name}")
        else:
            self.admin_status.setText("Link admin app")

        self._update_alerts_tab_visibility()
        if hasattr(self, "admin_section_title"):
            try:
                self.admin_section_title.setVisible(
                    bool(self.controller.authenticated) and (has_admin or getattr(self.controller, "admin_logged_in", False))
                )
            except Exception:
                pass
        if hasattr(self, "admin_quick_btn"):
            self.admin_quick_btn.setVisible(False)
        if hasattr(self, "sidebar_my_codes_btn"):
            self.sidebar_my_codes_btn.setVisible(False)
        try:
            self._apply_sidebar_theme()
        except Exception:
            pass

    def _update_alerts_tab_visibility(self, linked=None):
        """Admin workstation: all admin tabs visible."""
        if not hasattr(self, "tabs"):
            return
        try:
            for idx in range(self.tabs.count()):
                try:
                    self.tabs.setTabVisible(idx, True)
                except Exception:
                    pass
        except Exception:
            pass

    def _on_linked_user_deleted_by_admin(self, message: str):
        if message:
            QMessageBox.information(self, "Account removed", message + "\n\nThis desktop is no longer linked to that user.")
        self._update_admin_status_label()

    def _try_auto_connect_user(self):
        if self.controller.connected:
            return
        try:
            db = self.controller.get_db()
            last_port = (db.get("last_connected_port") or "").strip() if db else ""
        except Exception:
            last_port = ""
        def work():
            if last_port:
                self.controller.connect_to_port(last_port)
            else:
                self.controller.connect_bluetooth()
        import threading
        QTimer.singleShot(800, lambda: threading.Thread(target=work, daemon=True).start())

    def _refresh_port_combo(self):
        ports = self.controller.get_available_ports()
        self.port_combo.clear()
        self.port_combo.addItems(ports)

    def _connect_esp32(self):
        if self.controller.connected:
            self.controller.disconnect_esp32()
            QMessageBox.information(self, "ESP32", "ESP32 disconnected.")
            return
        self._refresh_port_combo()
        mode = QMessageBox.question(
            self, "Connection Mode",
            "YES → WIRED (USB)\nNO → BLUETOOTH\n\nESP32 must be ON.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No | QMessageBox.StandardButton.Cancel,
            QMessageBox.StandardButton.Yes,
        )
        if mode == QMessageBox.StandardButton.Cancel:
            return
        if mode == QMessageBox.StandardButton.Yes:
            self._connect_wired()
        else:
            self._connect_bluetooth()

    def _connect_wired(self):
        self._refresh_port_combo()
        ports = self.controller.get_available_ports()
        if not ports:
            reply = QMessageBox.question(self, "Connect", "No serial ports found. Plug in Arduino then click OK to rescan in 2s.",
                QMessageBox.StandardButton.Ok | QMessageBox.StandardButton.Cancel, QMessageBox.StandardButton.Ok)
            if reply == QMessageBox.StandardButton.Ok:
                QTimer.singleShot(2000, self._connect_wired_rescan)
            return
        port = (self.port_combo.currentText() or "").strip() or ports[0]
        if port not in ports:
            port = ports[0]
        self.connect_btn.setEnabled(False)
        self.connect_btn.setText("Connecting...")
        _timeout_sec = 20
        timeout_timer = [None]

        def on_done():
            if timeout_timer[0]:
                try:
                    timeout_timer[0].stop()
                except Exception:
                    pass
            self.connect_btn.setEnabled(True)
            self.connect_btn.setText("Disconnect ESP32" if self.controller.connected else "Connect ESP32")

        def on_timeout():
            timeout_timer[0] = None
            on_done()
            QMessageBox.warning(self, "ESP32", "Connection timed out. Turn on Arduino and try again.")

        import threading
        timeout_timer[0] = QTimer(self)
        timeout_timer[0].setSingleShot(True)
        timeout_timer[0].timeout.connect(on_timeout)
        timeout_timer[0].start(_timeout_sec * 1000)
        threading.Thread(target=lambda: (self.controller.connect_to_port(port), QTimer.singleShot(0, on_done)), daemon=True).start()

    def _connect_wired_rescan(self):
        self._refresh_port_combo()
        ports = self.controller.get_available_ports()
        if not ports:
            QMessageBox.warning(self, "Connect", "No serial ports found. Ensure Arduino is connected via USB.")
            return
        self._connect_wired()

    def _connect_bluetooth(self):
        self.connect_btn.setEnabled(False)
        self.connect_btn.setText("Connecting...")
        self._bt_thread = BluetoothConnectThread(self.controller)
        _timeout_sec = 20
        timeout_timer = [None]

        def on_finished():
            if timeout_timer[0]:
                try:
                    timeout_timer[0].stop()
                except Exception:
                    pass
            self.connect_btn.setEnabled(True)
            self.connect_btn.setText("Disconnect ESP32" if self.controller.connected else "Connect ESP32")
            if not self.controller.connected:
                QMessageBox.warning(self, "ESP32", "Could not connect to Bluetooth device.")

        def on_timeout():
            timeout_timer[0] = None
            self.connect_btn.setEnabled(True)
            self.connect_btn.setText("Connect ESP32")
            QMessageBox.warning(self, "ESP32", "Connection timed out.")

        timeout_timer[0] = QTimer(self)
        timeout_timer[0].setSingleShot(True)
        timeout_timer[0].timeout.connect(on_timeout)
        timeout_timer[0].start(_timeout_sec * 1000)
        self._bt_thread.finished.connect(on_finished)
        self._bt_thread.start()

    def switch_to_main_panel(self):
        try:
            db = self.controller.get_db()
            if db and getattr(db, "set_setup_complete", None):
                db.set_setup_complete(True)
        except Exception:
            pass
        if hasattr(self, "tabs"):
            self.tabs.setCurrentIndex(0)

    def _position_unlock_dialog_near_sidebar(self, dlg):
        dw = dlg.frameGeometry().width()
        dh = dlg.frameGeometry().height()
        btn = getattr(self, "unlock_with_password_btn", None)
        if btn and btn.isVisible():
            try:
                br = btn.rect()
                pt = btn.mapToGlobal(br.bottomLeft())
                dlg.move(pt.x(), pt.y() + 8)
                return
            except Exception:
                pass
        gr = self.geometry()
        tl = self.mapToGlobal(gr.topLeft())
        dlg.move(tl.x() + 24, tl.y() + (gr.height() - dh) // 4)

    def _unlock_without_device(self):
        try:
            from PyQt6.QtWidgets import (
                QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
                QPushButton, QMessageBox, QFrame, QSizePolicy
            )
            from PyQt6.QtCore import Qt
            from PyQt6.QtGui import QFont
        except ImportError:
            from PyQt5.QtWidgets import (
                QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
                QPushButton, QMessageBox, QFrame, QSizePolicy
            )
            from PyQt5.QtCore import Qt
            from PyQt5.QtGui import QFont

        db = self.controller.get_db()
        has_admin = db and getattr(db, "has_admin_credentials", lambda: False)()
        has_user_pwd = db and getattr(db, "has_user_device_password", lambda: False)()
        desktop_linked = db and getattr(db, "get_desktop_linked_admin", lambda: None)() if db else None

        theme = getattr(self, "_current_theme", "light") or "light"
        is_dark = theme == "dark"

        # Theme colours matching CuraX design tokens
        if is_dark:
            dlg_bg      = "#0B1220"
            hdr_bg1     = "#0D1828"
            hdr_bg2     = "#091525"
            hdr_border  = "#1A3040"
            body_bg     = "#0A0F1E"
            accent      = "#2DD4BF"
            title_c     = "#2DD4BF"
            sub_c       = "#64748B"
            input_bg    = "#0D1828"
            input_border= "#1A3040"
            input_focus = "#2DD4BF"
            input_c     = "#E2E8F0"
            btn_bg      = "#0D9488"
            btn_hover   = "#14B8A6"
            cancel_bg   = "#1E293B"
            cancel_c    = "#94A3B8"
            cancel_bdr  = "#334155"
            label_c     = "#94A3B8"
            err_c       = "#EF4444"
        else:
            dlg_bg      = "#F0FAF8"
            hdr_bg1     = "#FFFFFF"
            hdr_bg2     = "#EDF8F5"
            hdr_border  = "#C0DDD8"
            body_bg     = "#F0FAF8"
            accent      = "#0D9488"
            title_c     = "#0D6B61"
            sub_c       = "#64748B"
            input_bg    = "#FFFFFF"
            input_border= "#B2D8D4"
            input_focus = "#0D9488"
            input_c     = "#0F172A"
            btn_bg      = "#0D9488"
            btn_hover   = "#0F766E"
            cancel_bg   = "#F1F5F9"
            cancel_c    = "#475569"
            cancel_bdr  = "#CBD5E1"
            label_c     = "#475569"
            err_c       = "#EF4444"

        dialog_scale = max(0.52, min(1.0, float(getattr(self, "_content_scale", getattr(self, "_topbar_scale", 1.0)))))

        def _big_unlock_dlg(role_label, subtitle, verifier, pwd_placeholder="Enter your password…"):
            s = dialog_scale
            dlg = QDialog(self)
            dlg.setWindowTitle("CuraX — Unlock")
            dlg.setModal(True)
            dlg.setFixedWidth(max(220, int(300 * s)))
            dlg.setStyleSheet(f"QDialog {{ background-color: {dlg_bg}; }}")

            root = QVBoxLayout(dlg)
            root.setSpacing(0)
            root.setContentsMargins(0, 0, 0, 0)

            # ── Header: compact icon + title + subtitle in one row ──
            header = QFrame()
            header.setObjectName("unlockHeader")
            header.setStyleSheet(f"""
                QFrame#unlockHeader {{
                    background: qlineargradient(x1:0,y1:0,x2:1,y2:1,
                        stop:0 {hdr_bg1}, stop:1 {hdr_bg2});
                    border-bottom: 1px solid {hdr_border};
                }}
            """)
            hm = (max(12, int(20 * s)), max(10, int(14 * s)), max(12, int(20 * s)), max(8, int(12 * s)))
            h_lo = QVBoxLayout(header)
            h_lo.setContentsMargins(*hm)
            h_lo.setSpacing(2)
            h_lo.setAlignment(Qt.AlignmentFlag.AlignCenter)

            icon_lbl = QLabel("🔐")
            icon_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            icon_lbl.setStyleSheet(f"font-size: {max(12, int(18 * s))}pt; background: transparent;")
            h_lo.addWidget(icon_lbl)

            title_lbl = QLabel("Unlock CuraX")
            title_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            title_lbl.setStyleSheet(
                f"font-size: {max(9, int(12 * s))}pt; font-weight: 800; color: {title_c}; "
                "background: transparent;"
            )
            h_lo.addWidget(title_lbl)

            sub_lbl = QLabel(f"{role_label} — {subtitle}")
            sub_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            sub_lbl.setStyleSheet(f"font-size: {max(7, int(8 * s))}pt; color: {sub_c}; background: transparent;")
            h_lo.addWidget(sub_lbl)

            root.addWidget(header)

            # ── Body ────────────────────────────────────────────────
            body = QFrame()
            body.setObjectName("unlockBody")
            body.setStyleSheet(f"QFrame#unlockBody {{ background-color: {body_bg}; }}")
            bm = (max(10, int(16 * s)), max(10, int(14 * s)), max(10, int(16 * s)), max(10, int(14 * s)))
            b_lo = QVBoxLayout(body)
            b_lo.setContentsMargins(*bm)
            b_lo.setSpacing(max(6, int(8 * s)))

            # Password input (no separate label — placeholder is enough)
            pwd_edit = QLineEdit()
            pwd_edit.setPlaceholderText(pwd_placeholder)
            pwd_edit.setFixedHeight(max(28, int(36 * s)))
            try:
                pwd_edit.setEchoMode(QLineEdit.EchoMode.Password)
            except Exception:
                pwd_edit.setEchoMode(QLineEdit.Password)
            pwd_pt = max(8, int(10 * s))
            pwd_edit.setStyleSheet(f"""
                QLineEdit {{
                    background-color: {input_bg};
                    color: {input_c};
                    border: 1px solid {input_border};
                    border-radius: 8px;
                    padding: 6px 12px;
                    font-size: {pwd_pt}pt;
                    font-weight: 500;
                    letter-spacing: 2px;
                }}
                QLineEdit:focus {{
                    border: 2px solid {input_focus};
                    background-color: {input_bg};
                }}
            """)
            b_lo.addWidget(pwd_edit)

            # Error/status label (hidden initially)
            err_lbl = QLabel("")
            err_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            err_lbl.setStyleSheet(
                f"font-size: {max(7, int(8 * s))}pt; color: {err_c}; background: transparent; font-weight: 600;"
            )
            err_lbl.setVisible(False)
            b_lo.addWidget(err_lbl)

            # Unlock + Cancel side by side — compact height
            btn_row = QHBoxLayout()
            btn_row.setSpacing(max(6, int(8 * s)))

            btn_h = max(28, int(34 * s))
            btn_pt = max(8, int(9 * s))
            unlock_btn = QPushButton("🔓  Unlock")
            unlock_btn.setFixedHeight(btn_h)
            unlock_btn.setStyleSheet(f"""
                QPushButton {{
                    background-color: {btn_bg};
                    color: #FFFFFF;
                    border: none;
                    border-radius: 8px;
                    font-size: {btn_pt}pt;
                    font-weight: 700;
                    padding: 4px 12px;
                }}
                QPushButton:hover {{ background-color: {btn_hover}; }}
                QPushButton:pressed {{ background-color: #0F766E; }}
            """)

            cancel_btn = QPushButton("Cancel")
            cancel_btn.setFixedHeight(btn_h)
            cancel_btn.setStyleSheet(f"""
                QPushButton {{
                    background-color: {cancel_bg};
                    color: {cancel_c};
                    border: 1px solid {cancel_bdr};
                    border-radius: 8px;
                    font-size: {btn_pt}pt;
                    font-weight: 600;
                    padding: 4px 12px;
                }}
                QPushButton:hover {{ background-color: {hdr_border}; color: {input_c}; }}
            """)

            btn_row.addWidget(unlock_btn)
            btn_row.addWidget(cancel_btn)
            b_lo.addLayout(btn_row)

            root.addWidget(body)

            def do_unlock():
                pwd = pwd_edit.text().strip()
                if not pwd:
                    err_lbl.setText("⚠  Please enter your PIN or password.")
                    err_lbl.setVisible(True)
                    pwd_edit.setFocus()
                    return
                if verifier(pwd):
                    self.controller.authenticated = True
                    self.controller.authenticated_changed.emit(True)
                    dlg.accept()
                else:
                    err_lbl.setText("✗  Wrong PIN or password. Please try again.")
                    err_lbl.setVisible(True)
                    pwd_edit.clear()
                    pwd_edit.setFocus()

            unlock_btn.clicked.connect(do_unlock)
            pwd_edit.returnPressed.connect(do_unlock)
            cancel_btn.clicked.connect(dlg.reject)

            # Position near the sidebar (just to the right of the unlock button)
            try:
                btn_ref = getattr(self, "unlock_with_password_btn", None)
                dlg.adjustSize()
                dw = dlg.sizeHint().width()
                dh = dlg.sizeHint().height()
                if btn_ref and btn_ref.isVisible():
                    br = btn_ref.rect()
                    pt = btn_ref.mapToGlobal(br.topRight())
                    dlg.move(pt.x() + 8, pt.y())
                else:
                    pg = self.geometry()
                    pt2 = self.mapToGlobal(pg.topLeft())
                    dlg.move(pt2.x() + 270, pt2.y() + (pg.height() - dh) // 4)
            except Exception:
                pass

            pwd_edit.setFocus()
            dlg.exec()

        if has_admin:
            needs_unlock = bool(
                db and (
                    getattr(db, "has_desktop_app_unlock_pin", lambda: False)()
                    or getattr(db, "has_admin_unlock_password", lambda: False)()
                )
            )
            if not needs_unlock:
                self.controller.authenticated = True
                self.controller.authenticated_changed.emit(True)
                return
            use_desktop_pin = bool(
                db and getattr(db, "has_desktop_app_unlock_pin", lambda: False)()
            )
            if use_desktop_pin:
                _big_unlock_dlg(
                    "Admin · this computer",
                    "Enter your desktop unlock PIN (digits only). Stored only on this PC — not your phone app PIN.",
                    lambda pwd: getattr(db, "verify_desktop_app_unlock_pin", lambda p: False)(pwd),
                    pwd_placeholder="Enter desktop PIN…",
                )
            else:
                _big_unlock_dlg(
                    "Admin",
                    "Enter your admin account password.",
                    lambda pwd: getattr(db, "verify_admin_password", lambda p: False)(pwd),
                )
        else:
            self.controller.authenticated = True
            self.controller.authenticated_changed.emit(True)

    def _show_pin_dialog(self):
        if not self.controller.connected:
            QMessageBox.warning(self, "ESP32", "Please connect ESP32 first.")
            return
        d = PinDialog(self.controller, self)
        d.apply_theme(getattr(self, "_current_theme", "light"))
        d.exec()

    def _show_admin_verify_dialog(self, action_label: str):
        try:
            from PyQt6.QtWidgets import QDialog, QVBoxLayout, QHBoxLayout, QFrame, QLineEdit, QPushButton
        except ImportError:
            from PyQt5.QtWidgets import QDialog, QVBoxLayout, QHBoxLayout, QFrame, QLineEdit, QPushButton
        dlg = QDialog(self)
        dlg.setWindowTitle("Admin Verification Required")
        dlg.setModal(True)
        layout = QVBoxLayout(dlg)
        layout.setContentsMargins(0, 0, 0, 0)
        header = QFrame()
        header.setStyleSheet("background-color: #B91C1C;")
        h_layout = QVBoxLayout(header)
        title = QLabel("Admin Authorization Required")
        title.setStyleSheet("font-size: 14pt; font-weight: bold; color: white;")
        h_layout.addWidget(title)
        layout.addWidget(header)
        body = QVBoxLayout()
        body.setContentsMargins(16, 16, 16, 16)
        body.addWidget(QLabel(f"Required for: {action_label}"))
        body.addWidget(QLabel("Enter Admin Password:"))
        pwd_edit = QLineEdit()
        try:
            pwd_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except AttributeError:
            pwd_edit.setEchoMode(QLineEdit.Password)
        body.addWidget(pwd_edit)
        status = QLabel("")
        body.addWidget(status)
        btn_row = QHBoxLayout()
        verify_btn = QPushButton("Verify")
        verify_btn.setStyleSheet("background-color: #16A34A; color: white; font-weight: bold; padding: 6px 18px;")
        cancel_btn = QPushButton("Cancel")
        cancel_btn.setStyleSheet("background-color: #B91C1C; color: white; font-weight: bold; padding: 6px 18px;")
        btn_row.addWidget(verify_btn)
        btn_row.addWidget(cancel_btn)
        body.addLayout(btn_row)
        layout.addLayout(body)
        result = {"password": None}
        def on_verify():
            pwd = pwd_edit.text().strip()
            if not pwd:
                status.setText("Please enter password.")
                return
            result["password"] = pwd
            dlg.accept()
        verify_btn.clicked.connect(on_verify)
        cancel_btn.clicked.connect(dlg.reject)
        dlg.exec()
        return result["password"]

    def _show_admin_login_dialog(self) -> bool:
        db = self.controller.get_db()
        if not db.has_admin_credentials():
            QMessageBox.warning(self, "Admin", "No admin account configured. Set up admin in Settings → Admin Panel.")
            return False
        info = db.get_admin_info() or {}
        name = info.get("name", "Admin")
        try:
            from PyQt6.QtWidgets import QDialog, QVBoxLayout, QHBoxLayout, QFrame, QLineEdit, QPushButton
        except ImportError:
            from PyQt5.QtWidgets import QDialog, QVBoxLayout, QHBoxLayout, QFrame, QLineEdit, QPushButton
        dlg = QDialog(self)
        dlg.setWindowTitle("Admin Login")
        dlg.setModal(True)
        layout = QVBoxLayout(dlg)
        layout.setContentsMargins(0, 0, 0, 0)
        header = QFrame()
        header.setStyleSheet("background-color: #15803D;")
        h_layout = QVBoxLayout(header)
        title = QLabel("Admin Login")
        title.setStyleSheet("font-size: 16pt; font-weight: bold; color: white;")
        h_layout.addWidget(title)
        layout.addWidget(header)
        body = QVBoxLayout()
        body.setContentsMargins(16, 16, 16, 16)
        body.addWidget(QLabel("Enter password to unlock all features."))
        body.addWidget(QLabel("Password:"))
        pwd_edit = QLineEdit()
        try:
            pwd_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except AttributeError:
            pwd_edit.setEchoMode(QLineEdit.Password)
        body.addWidget(pwd_edit)
        status = QLabel("")
        body.addWidget(status)
        btn_row = QHBoxLayout()
        login_btn = QPushButton("Login")
        login_btn.setStyleSheet("background-color: #16A34A; color: white; font-weight: bold; padding: 6px 24px;")
        cancel_btn = QPushButton("Cancel")
        cancel_btn.setStyleSheet("background-color: #4B5563; color: white; font-weight: bold; padding: 6px 24px;")
        btn_row.addWidget(login_btn)
        btn_row.addWidget(cancel_btn)
        body.addLayout(btn_row)
        layout.addLayout(body)
        def attempt_login():
            pwd = pwd_edit.text().strip()
            if not pwd:
                status.setText("Please enter password.")
                return
            if self.controller.verify_admin_login(pwd):
                self.controller.load_data()
                QMessageBox.information(self, "Admin", f"Welcome {name}! All features unlocked.")
                dlg.accept()
            else:
                status.setText("Incorrect password")
                status.setStyleSheet("color: #EF4444;")
        login_btn.clicked.connect(attempt_login)
        cancel_btn.clicked.connect(dlg.reject)
        dlg.exec()
        return self.controller.admin_logged_in

    def open_admin_login_dialog(self):
        return self._show_admin_login_dialog()

    def verify_admin_for_action(self, action_label: str) -> bool:
        db = self.controller.get_db()
        if not db.has_admin_credentials():
            QMessageBox.information(self, "Admin Required",
                "No admin account configured.\n\nPlease open Settings → Admin Panel and set up an admin first.")
            self._open_settings_tab()
            return False
        return True

    def _backup_database(self):
        try:
            if self.controller.save_backup_snapshot():
                backup_path = self.controller._backup_file_path()
                QMessageBox.information(self, "Backup Database",
                    f"Backup saved:\n1. Inside the database\n2. Separate file:\n{backup_path}")
            else:
                QMessageBox.warning(self, "Backup Database", "Backup failed.")
        except Exception as e:
            QMessageBox.critical(self, "Backup Database", f"Backup failed: {e}")

    def _build_care_tabs_if_needed(self):
        if getattr(self, "_care_tabs_built", False):
            return
        c = self.controller
        from ui.tabs.alerts_tab import AlertsTab
        self._care_tabs.addTab(AdminCareOverviewTab(c, self, parent=self._care_tabs), "Overview")
        self._care_tabs.addTab(MedicalRemindersTab(c, self, parent=self._care_tabs), "Reminders")
        self._care_tabs.addTab(AdminAlertsTab(c, self, parent=self._care_tabs), "Logs")
        self._care_tabs.addTab(AlertsTab(c, self, parent=self._care_tabs, panel_mode="settings"), "Settings")
        self._care_tabs_built = True

    def show_care_mode_ui(self):
        self._build_care_tabs_if_needed()
        name = (getattr(self.controller, "act_as_user_name", None) or "User").strip()
        self._care_banner_label.setText(f"Care mode: {name}")
        self._care_banner.show()
        self._tab_mode_stack.setCurrentIndex(1)
        self._care_tabs.setCurrentIndex(0)
        for w in (self._tab_dashboard,):
            if w and hasattr(w, "refresh"):
                try:
                    w.refresh()
                except Exception:
                    pass

    def show_hub_mode_ui(self):
        if getattr(self.controller, "is_care_mode", lambda: False)():
            self.controller.exit_care_mode()
        self._care_banner.hide()
        self._tab_mode_stack.setCurrentIndex(0)
        self.tabs.setCurrentIndex(getattr(self, "_overview_tab_index", 0))

    def _on_care_mode_changed(self):
        if getattr(self.controller, "is_care_mode", lambda: False)():
            self.show_care_mode_ui()
        else:
            if hasattr(self, "_tab_mode_stack"):
                self._care_banner.hide()
                self._tab_mode_stack.setCurrentIndex(0)

    def _on_tab_change_guard(self, index: int):
        """Block tab navigation when system is locked — silently, no popup."""
        # Only block if user clicked a non-zero tab while locked
        # index==0 is always allowed (default tab); never show popup here
        if index == 0:
            return
        if not getattr(self.controller, "authenticated", False):
            # Silently revert — lock screen is covering tabs anyway
            tw = self._care_tabs if getattr(self, "_tab_mode_stack", None) and self._tab_mode_stack.currentIndex() == 1 else self.tabs
            tw.blockSignals(True)
            tw.setCurrentIndex(0)
            tw.blockSignals(False)

    def _require_unlocked(self) -> bool:
        """Returns True if unlocked. If locked, shows a friendly toast and returns False."""
        # Always pass if authenticated
        if getattr(self.controller, "authenticated", False):
            return True
        # Safety: don't show popup during app init (stacked not built yet)
        if not hasattr(self, "stacked"):
            return False
        try:
            from PyQt6.QtWidgets import QMessageBox
        except ImportError:
            from PyQt5.QtWidgets import QMessageBox
        msg = QMessageBox(self)
        msg.setWindowTitle("Locked")
        msg.setText("🔒  Please unlock the system first.")
        msg.setInformativeText(
            "Open the menu (☰) and authenticate to access this feature."
        )
        msg.setIcon(QMessageBox.Icon.Information)
        msg.setStandardButtons(QMessageBox.StandardButton.Ok)
        msg.setStyleSheet("""
            QMessageBox {
                background-color: #ffffff;
                font-size: 10pt;
            }
            QMessageBox QLabel {
                color: #0D6B61;
                font-size: 10pt;
            }
            QPushButton {
                background-color: #0D9488;
                color: #ffffff;
                border-radius: 8px;
                padding: 6px 20px;
                font-weight: 600;
                font-size: 9pt;
            }
            QPushButton:hover { background-color: #0f766e; }
        """)
        msg.exec()
        return False

    def _check_medicine_data(self):
        if not self._require_unlocked(): return
        try:
            from PyQt6.QtWidgets import QDialog, QVBoxLayout, QTextEdit, QPushButton
        except ImportError:
            from PyQt5.QtWidgets import QDialog, QVBoxLayout, QTextEdit, QPushButton
        dlg = QDialog(self)
        dlg.setWindowTitle("Medicine Data Check")
        layout = QVBoxLayout(dlg)
        text = QTextEdit()
        text.setReadOnly(True)
        lines = []
        empty = 0
        filled = 0
        low_stock = []
        for box_id, med in (self.controller.medicine_boxes or {}).items():
            if med:
                filled += 1
                qty = med.get("quantity", 0)
                if qty <= 5:
                    low_stock.append(f"{med.get('name', 'Unknown')} (Box {box_id})")
                lines.append(f"Box {box_id}: {med.get('name', 'Unknown')} - qty {qty}")
            else:
                empty += 1
                lines.append(f"Box {box_id}: EMPTY")
        summary = [f"Filled: {filled}/6", f"Empty: {empty}/6", ""]
        summary.extend(lines)
        if low_stock:
            summary += ["", "Low stock:", *[f"  - {m}" for m in low_stock]]
        text.setPlainText("\n".join(summary))
        layout.addWidget(text)
        close_btn = QPushButton("Close")
        close_btn.clicked.connect(dlg.accept)
        layout.addWidget(close_btn)
        dlg.exec()

    def _restore_database(self):
        try:
            restored = self.controller.restore_from_backup_snapshot()
            if not restored:
                restored = self.controller.restore_from_backup_file()
            if restored:
                try:
                    self.controller.load_data()
                    self.controller.medicine_updated.emit()
                    self.apply_theme(getattr(self.controller, "appearance_theme", "light"))
                except Exception:
                    pass
                QMessageBox.information(self, "Restore Database", "Data restored from backup.")
            else:
                QMessageBox.warning(self, "Restore Database", "No backup found.")
        except Exception as e:
            QMessageBox.critical(self, "Restore Database", f"Restore failed: {e}")

    def _check_alert_status(self):
        if not self._require_unlocked(): return
        try:
            from PyQt6.QtWidgets import QDialog, QVBoxLayout, QTextEdit, QPushButton
        except ImportError:
            from PyQt5.QtWidgets import QDialog, QVBoxLayout, QTextEdit, QPushButton
        dlg = QDialog(self)
        dlg.setWindowTitle("Alert System Status")
        layout = QVBoxLayout(dlg)
        text = QTextEdit()
        text.setReadOnly(True)
        lines = ["SCHEDULER STATUS", "-" * 20]
        sched = getattr(self.controller, "alert_scheduler", None)
        if sched:
            lines.append("Alert Scheduler: RUNNING")
            if hasattr(sched, "scheduled_alerts"):
                lines.append(f"  Scheduled jobs: {len(getattr(sched, 'scheduled_alerts', []))}")
        else:
            lines.append("Alert Scheduler: NOT RUNNING")
        lines += ["", "GMAIL", "-" * 20]
        gc = getattr(self.controller, "gmail_config", {})
        sender = (gc.get("sender_email") or "").strip()
        lines.append(f"Sender: {sender or 'Not configured'}")
        text.setPlainText("\n".join(lines))
        layout.addWidget(text)
        close_btn = QPushButton("Close")
        close_btn.clicked.connect(dlg.accept)
        layout.addWidget(close_btn)
        dlg.exec()

    def _link_admin_desktop_code_dialog(self):
        """Compact dialog: enter one-time code from admin app → link this PC to that admin hub."""
        try:
            from PyQt6.QtWidgets import (
                QDialog, QVBoxLayout, QHBoxLayout, QLineEdit, QLabel,
                QPushButton, QApplication,
            )
            from PyQt6.QtCore import Qt, QTimer
        except ImportError:
            from PyQt5.QtWidgets import (
                QDialog, QVBoxLayout, QHBoxLayout, QLineEdit, QLabel,
                QPushButton, QApplication,
            )
            from PyQt5.QtCore import Qt, QTimer

        theme = getattr(self, "_current_theme", "light") or "light"
        is_dark = theme == "dark"
        if is_dark:
            dlg_bg = "#0B1220"
            sub_c = "#94A3B8"
            input_bg = "#0D1828"
            input_border = "#1A3040"
            input_focus = "#2DD4BF"
            input_c = "#E2E8F0"
            btn_bg = "#0D9488"
            btn_hover = "#14B8A6"
            err_c = "#EF4444"
            ok_c = "#2DD4BF"
            cancel_bg = "#1E293B"
            cancel_c = "#94A3B8"
            cancel_bdr = "#334155"
        else:
            dlg_bg = "#F0FAF8"
            sub_c = "#64748B"
            input_bg = "#FFFFFF"
            input_border = "#B2D8D4"
            input_focus = "#0D9488"
            input_c = "#0F172A"
            btn_bg = "#0D9488"
            btn_hover = "#0F766E"
            err_c = "#EF4444"
            ok_c = "#0D9488"
            cancel_bg = "#F1F5F9"
            cancel_c = "#475569"
            cancel_bdr = "#CBD5E1"

        s = max(0.45, min(1.0, float(getattr(self, "_content_scale", getattr(self, "_topbar_scale", 1.0)))))
        w = max(240, int(300 * s))
        pad = max(12, int(16 * s))
        pt = max(8, int(10 * s))

        dlg = QDialog(self)
        dlg.setWindowTitle("Link admin")
        dlg.setModal(True)
        dlg.setFixedWidth(w)
        try:
            dlg.setWindowFlag(Qt.WindowType.MSWindowsFixedSizeDialogHint, True)
        except AttributeError:
            pass
        dlg.setStyleSheet(f"QDialog {{ background-color: {dlg_bg}; }}")

        lo = QVBoxLayout(dlg)
        lo.setContentsMargins(pad, pad, pad, pad)
        lo.setSpacing(max(8, int(10 * s)))

        hint = QLabel("Code from admin app (Settings → Desktop linking code)")
        hint.setWordWrap(True)
        hint.setStyleSheet(f"color: {sub_c}; font-size: {pt}pt; background: transparent;")
        lo.addWidget(hint)

        code_edit = QLineEdit()
        code_edit.setPlaceholderText("Enter code")
        code_edit.setMaxLength(16)
        code_edit.setMinimumHeight(max(32, int(38 * s)))
        code_edit.setStyleSheet(f"""
            QLineEdit {{
                background-color: {input_bg}; color: {input_c};
                border: 1px solid {input_border}; border-radius: 8px;
                padding: 6px 10px; font-size: {max(9, int(11 * s))}pt; font-weight: 700;
                letter-spacing: 2px;
            }}
            QLineEdit:focus {{ border: 2px solid {input_focus}; }}
        """)
        lo.addWidget(code_edit)

        status_lbl = QLabel("")
        status_lbl.setWordWrap(True)
        status_lbl.setVisible(False)
        status_lbl.setStyleSheet(f"color: {err_c}; font-size: {max(8, int(9 * s))}pt; background: transparent;")
        lo.addWidget(status_lbl)

        btn_row = QHBoxLayout()
        btn_row.setSpacing(8)
        link_btn = QPushButton("Link")
        link_btn.setMinimumHeight(max(32, int(36 * s)))
        link_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: {btn_bg}; color: #fff; border: none;
                border-radius: 8px; font-weight: 700; padding: 6px 14px;
            }}
            QPushButton:hover {{ background-color: {btn_hover}; }}
        """)
        cancel_btn = QPushButton("Cancel")
        cancel_btn.setMinimumHeight(max(32, int(36 * s)))
        cancel_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: {cancel_bg}; color: {cancel_c};
                border: 1px solid {cancel_bdr}; border-radius: 8px; padding: 6px 12px;
            }}
        """)
        btn_row.addWidget(link_btn)
        btn_row.addWidget(cancel_btn)
        lo.addLayout(btn_row)

        def do_link():
            code = code_edit.text().strip().upper()
            if not code:
                status_lbl.setText("Enter the code from your phone.")
                status_lbl.setStyleSheet(f"color: {err_c}; font-size: {max(8, int(9 * s))}pt;")
                status_lbl.setVisible(True)
                return
            link_btn.setEnabled(False)
            link_btn.setText("…")
            try:
                QApplication.processEvents()
            except Exception:
                pass
            fn = getattr(self.controller, "link_admin_desktop_by_link_code", None)
            ok, msg = fn(code) if fn else (False, "Linking not available.")
            if ok:
                try:
                    self.controller.medicine_updated.emit()
                except Exception:
                    pass
                self._update_admin_status_label()

                def _done():
                    dlg.accept()
                    self.controller.authenticated = True
                    self.controller.authenticated_changed.emit(True)
                    self._apply_locked_state()
                    self._update_status_text()
                    if hasattr(self, "tabs"):
                        self.tabs.setCurrentIndex(getattr(self, "_overview_tab_index", 0))

                QTimer.singleShot(150, _done)
            else:
                link_btn.setEnabled(True)
                link_btn.setText("Link")
                status_lbl.setText(msg or "Invalid or expired code.")
                status_lbl.setVisible(True)

        link_btn.clicked.connect(do_link)
        code_edit.returnPressed.connect(do_link)
        cancel_btn.clicked.connect(dlg.reject)
        code_edit.setFocus()
        dlg.adjustSize()
        try:
            pg = self.geometry()
            g = self.mapToGlobal(pg.topLeft())
            dlg.move(g.x() + 200, g.y() + max(40, pg.height() // 5))
        except Exception:
            pass
        dlg.exec()

    def _recover_admin_from_code(self):
        """Legacy alias — same compact link dialog."""
        self._link_admin_desktop_code_dialog()

    def _show_set_password_after_recovery(self):
        """Show a full-screen dialog for admin to set their password after account recovery."""
        try:
            from PyQt6.QtWidgets import (
                QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
                QPushButton, QFrame
            )
            from PyQt6.QtCore import Qt
        except ImportError:
            from PyQt5.QtWidgets import (
                QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
                QPushButton, QFrame
            )
            from PyQt5.QtCore import Qt

        theme = getattr(self, "_current_theme", "light") or "light"
        is_dark = theme == "dark"

        if is_dark:
            dlg_bg = "#0B1220"; hdr_bg1 = "#0D1828"; hdr_bg2 = "#091525"
            hdr_border = "#1A3040"; body_bg = "#0A0F1E"; title_c = "#2DD4BF"
            sub_c = "#64748B"; input_bg = "#0D1828"; input_border = "#1A3040"
            input_focus = "#2DD4BF"; input_c = "#E2E8F0"; label_c = "#94A3B8"
            btn_bg = "#0D9488"; btn_hover = "#14B8A6"; err_c = "#EF4444"
            skip_bg = "#1E293B"; skip_c = "#94A3B8"; skip_bdr = "#334155"
        else:
            dlg_bg = "#F0FAF8"; hdr_bg1 = "#FFFFFF"; hdr_bg2 = "#EDF8F5"
            hdr_border = "#C0DDD8"; body_bg = "#F0FAF8"; title_c = "#0D6B61"
            sub_c = "#64748B"; input_bg = "#FFFFFF"; input_border = "#B2D8D4"
            input_focus = "#0D9488"; input_c = "#0F172A"; label_c = "#475569"
            btn_bg = "#0D9488"; btn_hover = "#0F766E"; err_c = "#EF4444"
            skip_bg = "#F1F5F9"; skip_c = "#475569"; skip_bdr = "#CBD5E1"

        s = max(0.52, min(1.0, float(getattr(self, "_content_scale", getattr(self, "_topbar_scale", 1.0)))))
        inp_pt = max(9, int(11 * s))
        input_style = f"""
            QLineEdit {{
                background-color: {input_bg}; color: {input_c};
                border: 1px solid {input_border}; border-radius: 10px;
                padding: 10px 14px; font-size: {inp_pt}pt; font-weight: 600;
            }}
            QLineEdit:focus {{ border: 2px solid {input_focus}; }}
        """

        dlg = QDialog(self)
        dlg.setWindowTitle("CuraX — Set Password")
        dlg.setModal(True)
        dlg.setMinimumWidth(max(280, int(420 * s)))
        dlg.setMaximumWidth(max(320, int(500 * s)))
        try:
            dlg.setWindowFlag(Qt.WindowType.MSWindowsFixedSizeDialogHint, True)
        except AttributeError:
            pass
        dlg.setStyleSheet(f"QDialog {{ background-color: {dlg_bg}; }}")

        root = QVBoxLayout(dlg)
        root.setSpacing(0)
        root.setContentsMargins(0, 0, 0, 0)

        # Header
        header = QFrame()
        header.setObjectName("setPwdHeader")
        header.setStyleSheet(f"""
            QFrame#setPwdHeader {{
                background: qlineargradient(x1:0,y1:0,x2:1,y2:1,
                    stop:0 {hdr_bg1}, stop:1 {hdr_bg2});
                border-bottom: 1px solid {hdr_border};
            }}
        """)
        hm = (max(20, int(32 * s)), max(18, int(28 * s)), max(20, int(32 * s)), max(14, int(24 * s)))
        h_lo = QVBoxLayout(header)
        h_lo.setContentsMargins(*hm)
        h_lo.setSpacing(max(4, int(6 * s)))
        h_lo.setAlignment(Qt.AlignmentFlag.AlignCenter)

        icon_lbl = QLabel("🔑")
        icon_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        icon_lbl.setStyleSheet(f"font-size: {max(20, int(32 * s))}pt; background: transparent;")
        h_lo.addWidget(icon_lbl)

        title_lbl = QLabel("Set Your Password")
        title_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        title_lbl.setStyleSheet(
            f"font-size: {max(11, int(16 * s))}pt; font-weight: 800; color: {title_c}; "
            "background: transparent; letter-spacing: -0.3px;"
        )
        h_lo.addWidget(title_lbl)

        sub_lbl = QLabel("Admin account recovered — please set a new unlock password")
        sub_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        sub_lbl.setWordWrap(True)
        lbl_pt = max(7, int(9 * s))
        sub_lbl.setStyleSheet(f"font-size: {lbl_pt}pt; color: {sub_c}; background: transparent;")
        h_lo.addWidget(sub_lbl)

        root.addWidget(header)

        # Body
        body = QFrame()
        body.setObjectName("setPwdBody")
        body.setStyleSheet(f"QFrame#setPwdBody {{ background-color: {body_bg}; }}")
        bm = (max(20, int(32 * s)), max(18, int(28 * s)), max(20, int(32 * s)), max(20, int(32 * s)))
        b_lo = QVBoxLayout(body)
        b_lo.setContentsMargins(*bm)
        b_lo.setSpacing(max(10, int(14 * s)))

        pwd_label = QLabel("New Password")
        pwd_label.setStyleSheet(f"font-size: {lbl_pt}pt; font-weight: 700; color: {label_c}; background: transparent;")
        b_lo.addWidget(pwd_label)

        inp_h = max(36, int(46 * s))
        pwd_edit = QLineEdit()
        pwd_edit.setPlaceholderText("Enter new password…")
        pwd_edit.setMinimumHeight(inp_h)
        try:
            pwd_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except Exception:
            pwd_edit.setEchoMode(QLineEdit.Password)
        pwd_edit.setStyleSheet(input_style)
        b_lo.addWidget(pwd_edit)

        confirm_label = QLabel("Confirm Password")
        confirm_label.setStyleSheet(f"font-size: {lbl_pt}pt; font-weight: 700; color: {label_c}; background: transparent;")
        b_lo.addWidget(confirm_label)

        confirm_edit = QLineEdit()
        confirm_edit.setPlaceholderText("Re-enter new password…")
        confirm_edit.setMinimumHeight(inp_h)
        try:
            confirm_edit.setEchoMode(QLineEdit.EchoMode.Password)
        except Exception:
            confirm_edit.setEchoMode(QLineEdit.Password)
        confirm_edit.setStyleSheet(input_style)
        b_lo.addWidget(confirm_edit)

        err_lbl = QLabel("")
        err_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        err_lbl.setWordWrap(True)
        err_lbl.setStyleSheet(f"font-size: {lbl_pt}pt; color: {err_c}; background: transparent; font-weight: 600;")
        err_lbl.setVisible(False)
        b_lo.addWidget(err_lbl)

        b_lo.addSpacing(max(4, int(4 * s)))

        btn_row = QHBoxLayout()
        btn_row.setSpacing(max(8, int(10 * s)))

        btn_h = max(36, int(46 * s))
        save_btn = QPushButton("✔  Save Password")
        save_btn.setMinimumHeight(btn_h)
        save_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: {btn_bg}; color: #FFFFFF; border: none;
                border-radius: 10px; font-size: {inp_pt}pt; font-weight: 700; padding: 10px 16px;
            }}
            QPushButton:hover {{ background-color: {btn_hover}; }}
            QPushButton:pressed {{ background-color: #0F766E; }}
        """)

        skip_btn = QPushButton("Skip")
        skip_btn.setMinimumHeight(btn_h)
        skip_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: {skip_bg}; color: {skip_c};
                border: 1px solid {skip_bdr}; border-radius: 10px;
                font-size: {max(8, int(10 * s))}pt; font-weight: 600; padding: 10px 16px;
            }}
            QPushButton:hover {{ background-color: {hdr_border}; }}
        """)

        btn_row.addWidget(save_btn)
        btn_row.addWidget(skip_btn)
        b_lo.addLayout(btn_row)

        root.addWidget(body)

        def do_save():
            pwd = pwd_edit.text()
            conf = confirm_edit.text()
            if not pwd:
                err_lbl.setText("⚠  Please enter a password.")
                err_lbl.setVisible(True)
                pwd_edit.setFocus()
                return
            if pwd != conf:
                err_lbl.setText("✗  Passwords do not match.")
                err_lbl.setVisible(True)
                confirm_edit.clear()
                confirm_edit.setFocus()
                return
            db = self.controller.get_db()
            if db and hasattr(db, "update_admin_password"):
                db.update_admin_password(pwd)
            err_lbl.setVisible(False)
            dlg.accept()

        save_btn.clicked.connect(do_save)
        confirm_edit.returnPressed.connect(do_save)
        skip_btn.clicked.connect(dlg.reject)

        # Centre on parent
        try:
            pg = self.geometry()
            dlg.adjustSize()
            dw = dlg.sizeHint().width()
            dh = dlg.sizeHint().height()
            dlg.move(
                pg.x() + (pg.width() - dw) // 2,
                pg.y() + (pg.height() - dh) // 2,
            )
        except Exception:
            pass

        pwd_edit.setFocus()
        dlg.exec()

    def _admin_quick_action(self):
        if self.controller.admin_logged_in:
            confirm = QMessageBox.question(self, "Logout Admin", f"Logout {self.controller.logged_in_admin_name or 'Admin'}?")
            if confirm == QMessageBox.StandardButton.Yes:
                self.controller.admin_logout()
                QMessageBox.information(self, "Admin", "Admin logged out.")
            return
        self._show_admin_login_dialog()

    def _open_settings_tab(self):
        for i in range(self.tabs.count()):
            if "Settings" in self.tabs.tabText(i):
                self.tabs.setCurrentIndex(i)
                break

    def _on_admin_status_clicked(self):
        if (self.admin_status.text() or "").strip() != "Features Locked":
            return
        for i in range(self.tabs.count()):
            if "Settings" in self.tabs.tabText(i):
                self.tabs.setCurrentIndex(i)
                settings_w = self.tabs.widget(i)
                if hasattr(settings_w, "_settings_tabs") and hasattr(settings_w, "_admin_tab_index"):
                    settings_w._settings_tabs.setTabVisible(settings_w._admin_tab_index, True)
                    settings_w._settings_tabs.setCurrentIndex(settings_w._admin_tab_index)
                break

    def changeEvent(self, event):
        super().changeEvent(event)
        try:
            act = getattr(QEvent, "Type", QEvent)
            if event.type() == getattr(act, "ActivationChange", None) and self.isActiveWindow():
                if not hasattr(self, "_focus_fetch_timer"):
                    self._focus_fetch_timer = QTimer(self)
                    self._focus_fetch_timer.setSingleShot(True)
                    self._focus_fetch_timer.timeout.connect(
                        self.controller.fetch_from_central_and_apply
                    )
                self._focus_fetch_timer.start(1200)
        except Exception:
            pass

    def _power_sleep(self):
        """Sleep: reset auth, go back to locked state. App keeps running."""
        # Reset authentication for both admin and user sessions
        self.controller.authenticated = False
        if hasattr(self.controller, "current_user"):
            self.controller.current_user = None
        if hasattr(self.controller, "admin_authenticated"):
            self.controller.admin_authenticated = False
        if hasattr(self.controller, "user_authenticated"):
            self.controller.user_authenticated = False
        # Return to lock screen
        if hasattr(self, "stacked") and hasattr(self, "locked_screen_widget"):
            self.stacked.setCurrentIndex(0)
        self._apply_locked_state()

    def _power_shutdown(self):
        """Shutdown: close the application."""
        try:
            from PyQt6.QtWidgets import QMessageBox
        except ImportError:
            from PyQt5.QtWidgets import QMessageBox
        reply = QMessageBox.question(
            self, "Shut Down CuraX",
            "Are you sure you want to close CuraX?\n\nThe application will exit.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel
        )
        try:
            yes = QMessageBox.StandardButton.Yes
        except AttributeError:
            yes = QMessageBox.Yes
        if reply == yes:
            self.close()

    def closeEvent(self, event):
        if hasattr(self.controller, "_save_in_progress") and self.controller._save_in_progress():
            QMessageBox.information(self, "Saving…", "Finishing save to server… Please wait.")
        if hasattr(self.controller, "wait_for_pending_save"):
            self.controller.wait_for_pending_save(10)
        self.controller.disconnect_esp32()
        event.accept()
