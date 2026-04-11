"""
Medicine Box Panel — Modern dashboard tile grid.

Each box = a painted tile with:
  • Soft gradient mesh background (status-tinted)
  • Large qty number on the left
  • Slim donut arc on the right  
  • Medicine name + box label
  • Subtle bottom accent line
  • Responsive: 3-col → 2-col → 1-col based on width
"""
try:
    from PyQt6.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QFrame, QScrollArea, QDialog, QPushButton,
        QMessageBox, QSizePolicy, QApplication,
    )
    from PyQt6.QtCore import Qt, QEvent, QObject, QSize, QRectF, QRect, QPointF, QTimer, QEasingCurve
    from PyQt6.QtGui import (
        QColor, QPainter, QLinearGradient, QRadialGradient,
        QPainterPath, QPen, QFont, QBrush, QFontMetrics, QConicalGradient,
    )
except ImportError:
    from PyQt5.QtWidgets import (
        QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QLabel, QFrame, QScrollArea, QDialog, QPushButton,
        QMessageBox, QSizePolicy, QApplication,
    )
    from PyQt5.QtCore import Qt, QEvent, QObject, QSize, QRectF, QRect, QPointF, QTimer, QEasingCurve
    from PyQt5.QtGui import (
        QColor, QPainter, QLinearGradient, QRadialGradient,
        QPainterPath, QPen, QFont, QBrush, QFontMetrics, QConicalGradient,
    )

from ui.styles import LOW_STOCK_YELLOW, LOW_STOCK_RED

METER_FULL_SCALE = 80

try:
    from PyQt6.QtCore import pyqtSignal as _sig
except ImportError:
    from PyQt5.QtCore import pyqtSignal as _sig


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


# ── Status colours ─────────────────────────────────────────────────────────────
_STATUS = {
    # name,     accent,    bg_tint_light,  bg_tint_dark
    "ok":   ("#0D9488", "#E8F7F4", "#0A2830"),
    "low":  ("#D97706", "#FEF3E2", "#1E1400"),
    "crit": ("#DC2626", "#FEE2E2", "#1E0808"),
    "none": ("#64748B", "#F1F5F9", "#0D1928"),
}

_L = dict(
    page_bg="#DFE8EC",        # clearly distinct from white cards
    title="#0A3D38",
    sub="#4A7A74",
    card_bg="#FFFFFF",
    name="#0F172A",
    empty="#64748B",           # darker empty text, visible
    ring_track="#B8D4CE",      # visible track on white bg
    ring_text="#0A3D38",
    qty_empty="#94A3B8",
    sep="#B0C4CC",             # clearly visible card border
)
_D = dict(
    page_bg="#080E18",
    title="#EEF2F7",
    sub="#9BB0C4",
    card_bg="#121E30",
    name="#D8E2ED",
    empty="#6B8296",
    ring_track="#2A4A62",
    ring_text="#EEF2F7",
    qty_empty="#4A6074",
    sep="#2C4760",
)


# ─────────────────────────────────────────────────────────────────────────────
# MedicineTile — one fully-painted dashboard tile
# ─────────────────────────────────────────────────────────────────────────────
class MedicineTile(QWidget):
    """
    Painted tile. Layout (all proportional to W×H):

    ┌─────────────────────────────────────────┐
    │  B1  (top-left badge)     [arc  ring]   │  ← tinted gradient bg
    │                                         │
    │  29                       ◖████◗        │
    │  tablets left              72%          │
    │                                         │
    │  Paracetamol                            │
    │──────────────────── accent line ────────│
    └─────────────────────────────────────────┘
    """
    clicked = _sig(str)

    _R = 16   # corner radius

    # animation timing  — all deliberately slow & cinematic
    _ARC_STEPS   = 80    # arc sweep: 80 × 20ms = 1600ms
    _ARC_MS      = 20
    _CNT_STEPS   = 70    # qty count-up: 70 × 25ms = 1750ms
    _CNT_MS      = 25
    _HOV_STEPS   = 12    # hover transition: 12 × 14ms = 168ms
    _HOV_MS      = 14
    _PULSE_MS    = 35    # crit pulse frame interval (slow breathe)
    _FADE_STEPS  = 35    # entry fade: 35 × 22ms = 770ms
    _FADE_MS     = 22

    def __init__(self, box_id: str, theme_name: str = "light", parent=None):
        super().__init__(parent)
        self.box_id    = box_id
        self._theme    = theme_name
        self._tok      = _L if theme_name != "dark" else _D
        self._med_name = ""
        self._qty      = 0
        self._has_med  = False
        self._status   = "none"
        self._hov      = False

        # ── animation state ───────────────────────────────────────────
        # arc ring sweep: 0→target (no text animation inside arc)
        self._arc_pct     = 0.0
        self._arc_target  = 0.0
        self._arc_step    = 0
        self._arc_timer   = QTimer(self)
        self._arc_timer.timeout.connect(self._tick_arc)

        # quantity count-up: 0 → real qty (slow, cinematic)
        self._disp_qty    = 0
        self._qty_target  = 0
        self._qty_step    = 0
        self._qty_timer   = QTimer(self)
        self._qty_timer.timeout.connect(self._tick_qty)

        # hover lift: _hov_t goes 0.0→1.0 (in) or 1.0→0.0 (out)
        self._hov_t       = 0.0
        self._hov_timer   = QTimer(self)
        self._hov_timer.timeout.connect(self._tick_hover)

        # critical pulse: alpha oscillates on accent bar & badge
        self._pulse_t     = 0.0
        self._pulse_dir   = 1
        self._pulse_timer = QTimer(self)
        self._pulse_timer.timeout.connect(self._tick_pulse)

        # entry fade-in: 0.0→1.0
        self._fade        = 0.0
        self._fade_step   = 0
        self._fade_timer  = QTimer(self)
        self._fade_timer.timeout.connect(self._tick_fade)

        try:
            self.setCursor(Qt.CursorShape.PointingHandCursor)
            self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
            self.setAttribute(Qt.WidgetAttribute.WA_Hover)
        except AttributeError:
            self.setCursor(Qt.PointingHandCursor)
            self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
            self.setAttribute(Qt.WA_Hover)
        self.setMinimumSize(140, 130)

    # ── animation tickers ─────────────────────────────────────────────
    def _ease_out_cubic(self, t: float) -> float:
        return 1.0 - (1.0 - t) ** 3

    def _ease_out_quart(self, t: float) -> float:
        """Even gentler deceleration for the slow slide."""
        return 1.0 - (1.0 - t) ** 4

    def _tick_arc(self):
        self._arc_step += 1
        t = self._ease_out_cubic(self._arc_step / self._ARC_STEPS)
        self._arc_pct = self._arc_target * t
        if self._arc_step >= self._ARC_STEPS:
            self._arc_pct = self._arc_target
            self._arc_timer.stop()
        self.update()

    def _tick_qty(self):
        self._qty_step += 1
        t = self._ease_out_quart(self._qty_step / self._CNT_STEPS)
        self._disp_qty = int(round(self._qty_target * t))
        if self._qty_step >= self._CNT_STEPS:
            self._disp_qty = self._qty_target
            self._qty_timer.stop()
        self.update()

    def _tick_hover(self):
        step = 1.0 / self._HOV_STEPS
        if self._hov:
            self._hov_t = min(1.0, self._hov_t + step)
        else:
            self._hov_t = max(0.0, self._hov_t - step)
        if self._hov_t in (0.0, 1.0):
            self._hov_timer.stop()
        self.update()

    def _tick_pulse(self):
        self._pulse_t += self._pulse_dir * 0.025   # slower breathe
        if self._pulse_t >= 1.0:
            self._pulse_t = 1.0
            self._pulse_dir = -1
        elif self._pulse_t <= 0.2:
            self._pulse_t = 0.2
            self._pulse_dir = 1
        self.update()

    def _tick_fade(self):
        self._fade_step += 1
        self._fade = self._ease_out_cubic(self._fade_step / self._FADE_STEPS)
        if self._fade_step >= self._FADE_STEPS:
            self._fade = 1.0
            self._fade_timer.stop()
        self.update()

    def _start_data_anims(self):
        """Apply arc + quantity immediately (no sweep / count-up on main panel)."""
        self._arc_timer.stop()
        self._qty_timer.stop()

        self._arc_target = _clamp(self._qty / METER_FULL_SCALE, 0.0, 1.0) if self._has_med else 0.0
        self._arc_pct = self._arc_target
        self._arc_step = self._ARC_STEPS

        self._qty_target = self._qty if self._has_med else 0
        self._disp_qty = self._qty_target
        self._qty_step = self._CNT_STEPS

        # critical pulse
        if self._status == "crit" and self._has_med:
            if not self._pulse_timer.isActive():
                self._pulse_t   = 0.5
                self._pulse_dir = 1
                self._pulse_timer.start(self._PULSE_MS)
        else:
            self._pulse_timer.stop()
            self._pulse_t = 1.0

    # ── data ──────────────────────────────────────────────────────────
    def set_data(self, med, low_threshold: int = 5):
        if med:
            self._has_med  = True
            self._med_name = (med.get("name") or "").strip() or "Unknown"
            qty            = med.get("quantity", 0)
            self._qty      = int(qty) if isinstance(qty, (int, float)) else 0
            if self._qty == 0:
                self._status = "crit"
            elif self._qty <= low_threshold:
                self._status = "low"
            else:
                self._status = "ok"
        else:
            self._has_med  = False
            self._med_name = ""
            self._qty      = 0
            self._status   = "none"
        self._start_data_anims()
        self.update()

    def update_theme(self, theme_name: str):
        self._theme = theme_name
        self._tok   = _L if theme_name != "dark" else _D
        self.update()

    # ── paint ─────────────────────────────────────────────────────────
    def paintEvent(self, event):
        qp = QPainter(self)
        try:
            qp.setRenderHint(QPainter.RenderHint.Antialiasing)
            qp.setRenderHint(QPainter.RenderHint.TextAntialiasing)
        except AttributeError:
            qp.setRenderHint(QPainter.Antialiasing)
            qp.setRenderHint(QPainter.TextAntialiasing)

        W, H = self.width(), self.height()
        tok   = self._tok
        accent, _, _ = _STATUS[self._status]
        R = self._R

        try:
            no_pen   = Qt.PenStyle.NoPen
            no_brush = Qt.BrushStyle.NoBrush
            rc       = Qt.PenCapStyle.RoundCap
            al_c     = Qt.AlignmentFlag.AlignCenter
            al_lv    = Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter
        except AttributeError:
            no_pen   = Qt.NoPen
            no_brush = Qt.NoBrush
            rc       = Qt.RoundCap
            al_c     = Qt.AlignCenter
            al_lv    = Qt.AlignLeft | Qt.AlignVCenter

        # ── global fade-in opacity ────────────────────────────────────
        qp.setOpacity(max(0.0, min(1.0, self._fade)))

        # ── hover smooth values ───────────────────────────────────────
        hov_ease = self._hov_t * self._hov_t * (3 - 2 * self._hov_t)
        glow_a   = int(hov_ease * 80)

        # ── glow halo on hover ────────────────────────────────────────
        if glow_a > 0:
            glow_col = QColor(accent)
            glow_col.setAlpha(glow_a)
            qp.setPen(QPen(glow_col, 6))
            qp.setBrush(no_brush)
            qp.drawRoundedRect(QRectF(-3, -3, W + 6, H + 6), R + 2, R + 2)

        # ── card background ───────────────────────────────────────────
        card_path = QPainterPath()
        card_rect = QRectF(hov_ease, hov_ease, W - hov_ease * 2, H - hov_ease * 2)
        card_path.addRoundedRect(card_rect, R, R)
        bg = QColor(tok["card_bg"])
        if self._hov:
            bg = bg.darker(103) if self._theme != "dark" else bg.lighter(115)
        qp.setPen(no_pen)
        qp.fillPath(card_path, bg)

        # ── card border — interpolate to accent on hover ──────────────
        sep_c = QColor(tok["sep"])
        acc_c = QColor(accent)
        t_bdr = hov_ease
        bdr_c = QColor(
            int(sep_c.red()   * (1 - t_bdr) + acc_c.red()   * t_bdr),
            int(sep_c.green() * (1 - t_bdr) + acc_c.green() * t_bdr),
            int(sep_c.blue()  * (1 - t_bdr) + acc_c.blue()  * t_bdr),
        )
        bdr_w = 1.5 + hov_ease * 0.5
        qp.setPen(QPen(bdr_c, bdr_w))
        qp.setBrush(no_brush)
        qp.drawRoundedRect(QRectF(bdr_w / 2, bdr_w / 2,
                                  W - bdr_w, H - bdr_w), R, R)

        # ── left accent bar — 6px gradient, crit-pulse alpha ─────────
        bar_w     = 6
        bar_alpha = int(_clamp(self._pulse_t, 0.2, 1.0) * 255)
        bar_path  = QPainterPath()
        bar_path.addRoundedRect(QRectF(bdr_w, bdr_w, bar_w, H - bdr_w * 2), 3, 3)
        clip_p = QPainterPath()
        clip_p.addRoundedRect(QRectF(0, 0, W, H), R, R)
        bar_path = bar_path.intersected(clip_p)
        qp.setPen(no_pen)
        bar_grad = QLinearGradient(0, 0, 0, H)
        c0 = QColor(accent).lighter(125); c0.setAlpha(bar_alpha)
        c1 = QColor(accent);              c1.setAlpha(bar_alpha)
        c2 = QColor(accent).darker(110);  c2.setAlpha(bar_alpha)
        bar_grad.setColorAt(0.0, c0)
        bar_grad.setColorAt(0.5, c1)
        bar_grad.setColorAt(1.0, c2)
        qp.fillPath(bar_path, bar_grad)

        # layout metrics
        left_pad  = bar_w + int(W * 0.07)
        right_pad = int(W * 0.06)
        top_pad   = int(H * 0.11)

        # ── arc ring (right side) — always shows final static value ──
        ring_sz  = int(_clamp(min(W, H) * 0.42, 44, 88))
        ring_x   = W - right_pad - ring_sz
        ring_y   = top_pad
        ring_rw  = max(6, ring_sz // 8)
        margin   = ring_rw // 2 + 2
        arc_rect = QRectF(ring_x + margin, ring_y + margin,
                          ring_sz - 2 * margin, ring_sz - 2 * margin)

        # track circle
        tp = QPen(QColor(tok["ring_track"]), ring_rw)
        tp.setCapStyle(rc)
        qp.setPen(tp)
        qp.setBrush(no_brush)
        qp.drawEllipse(arc_rect)

        # Progress arc — static solid stroke, flat caps (no conical “glow” pill at 12 o’clock)
        pct_anim = self._arc_pct
        if pct_anim > 0:
            try:
                flat = Qt.PenCapStyle.FlatCap
            except AttributeError:
                flat = Qt.FlatCap
            ap = QPen(QColor(accent), ring_rw)
            ap.setCapStyle(flat)
            qp.setPen(ap)
            qp.setBrush(no_brush)
            qp.drawArc(arc_rect, int(90 * 16), int(-pct_anim * 360 * 16))

        # static % label — always shows real final value, no counting
        real_pct = _clamp(self._qty / METER_FULL_SCALE, 0.0, 1.0) if self._has_med else 0.0
        try:
            pf = QFont("Segoe UI", _clamp(ring_sz // 5, 6, 13), QFont.Weight.Bold)
        except AttributeError:
            pf = QFont("Segoe UI", _clamp(ring_sz // 5, 6, 13), QFont.Bold)
        qp.setFont(pf)
        qp.setPen(QColor(accent if self._has_med else tok["ring_text"]))
        pct_str = f"{int(real_pct * 100)}%" if self._has_med else "—"
        qp.drawText(QRect(ring_x, ring_y, ring_sz, ring_sz), al_c, pct_str)

        # ── badge B1..B6 — pop in with fade tied to entry fade ───────
        badge_h = _clamp(int(H * 0.22), 22, 34)
        badge_w = _clamp(int(W * 0.24), 32, 52)
        badge_x = left_pad
        badge_y = top_pad
        bp = QPainterPath()
        bp.addRoundedRect(QRectF(badge_x, badge_y, badge_w, badge_h), badge_h / 2, badge_h / 2)
        badge_bg = QColor(accent)
        badge_bg.setAlpha(int(150 + bar_alpha * 0.33))
        qp.setPen(no_pen)
        qp.fillPath(bp, badge_bg)
        try:
            bf = QFont("Segoe UI", _clamp(int(badge_h * 0.48), 7, 12), QFont.Weight.Black)
        except AttributeError:
            bf = QFont("Segoe UI", _clamp(int(badge_h * 0.48), 7, 12), QFont.Black)
        qp.setFont(bf)
        qp.setPen(QColor("#FFFFFF"))
        qp.drawText(QRect(badge_x, badge_y, badge_w, badge_h), al_c, self.box_id)

        # ── text area — fixed positions, no slide ─────────────────────
        qty_y  = badge_y + badge_h + int(H * 0.05)
        txt_w  = ring_x - left_pad - int(W * 0.04)
        name_h = int(H * 0.20)
        name_y = H - int(H * 0.06) - name_h

        if self._has_med:
            # big quantity number (count-up only, no slide)
            try:
                qf = QFont("Segoe UI", _clamp(int(H * 0.28), 14, 38), QFont.Weight.Black)
            except AttributeError:
                qf = QFont("Segoe UI", _clamp(int(H * 0.28), 14, 38), QFont.Black)
            qp.setFont(qf)
            qp.setPen(QColor(accent))
            qp.drawText(QRect(left_pad, qty_y, txt_w, int(H * 0.36)),
                        al_lv, str(self._disp_qty))

            # "tablets left"
            try:
                sf = QFont("Segoe UI", _clamp(int(H * 0.10), 6, 10))
            except AttributeError:
                sf = QFont("Segoe UI", _clamp(int(H * 0.10), 6, 10))
            qp.setFont(sf)
            qp.setPen(QColor("#64748B"))
            sub_y = qty_y + int(H * 0.31)
            qp.drawText(QRect(left_pad, sub_y, txt_w, int(H * 0.18)),
                        al_lv, "tablets left")

            # medicine name at bottom
            try:
                nf = QFont("Segoe UI", _clamp(int(H * 0.12), 7, 11), QFont.Weight.DemiBold)
            except AttributeError:
                nf = QFont("Segoe UI", _clamp(int(H * 0.12), 7, 11), QFont.DemiBold)
            qp.setFont(nf)
            qp.setPen(QColor(tok["name"]))
            fm = QFontMetrics(nf)
            try:
                elide = Qt.TextElideMode.ElideRight
            except AttributeError:
                elide = Qt.ElideRight
            name_disp = fm.elidedText(self._med_name, elide, txt_w)
            qp.drawText(QRect(left_pad, name_y, txt_w, name_h), al_lv, name_disp)

        else:
            # empty slot
            try:
                ef = QFont("Segoe UI", _clamp(int(H * 0.12), 7, 11))
            except AttributeError:
                ef = QFont("Segoe UI", _clamp(int(H * 0.12), 7, 11))
            qp.setFont(ef)
            qp.setPen(QColor(tok["empty"]))
            qp.drawText(QRect(left_pad, qty_y, txt_w, int(H * 0.55)),
                        al_lv, "No medicine\nassigned")

            # "Tap to assign" hint
            try:
                hf = QFont("Segoe UI", _clamp(int(H * 0.10), 6, 9))
            except AttributeError:
                hf = QFont("Segoe UI", _clamp(int(H * 0.10), 6, 9))
            qp.setFont(hf)
            hint_c = QColor(accent)
            hint_c.setAlpha(180)
            qp.setPen(hint_c)
            qp.drawText(QRect(left_pad, name_y, txt_w, name_h), al_lv, "Tap to assign →")

        qp.end()

    # ── events ─────────────────────────────────────────────────────────
    def mousePressEvent(self, event):
        try:
            left = Qt.MouseButton.LeftButton
        except AttributeError:
            left = Qt.LeftButton
        if event.button() == left:
            self.clicked.emit(self.box_id)
        super().mousePressEvent(event)

    def enterEvent(self, event):
        self._hov = True
        self._hov_timer.start(self._HOV_MS)
        super().enterEvent(event)

    def leaveEvent(self, event):
        self._hov = False
        self._hov_timer.start(self._HOV_MS)
        super().leaveEvent(event)

    def showEvent(self, event):
        super().showEvent(event)
        if self._fade < 1.0:
            self._fade_step = 0
            self._fade_timer.start(self._FADE_MS)

    def sizeHint(self):
        return QSize(200, 170)

    def minimumSizeHint(self):
        return QSize(140, 130)


# ─────────────────────────────────────────────────────────────────────────────
# BoxDetailDialog
# ─────────────────────────────────────────────────────────────────────────────
class BoxDetailDialog(QDialog):
    def __init__(self, controller, box_id, main_window, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.box_id = box_id
        self.setWindowTitle(f"Box {box_id} — Details")
        self.setMinimumWidth(380)
        theme = (getattr(controller, "appearance_theme", "light") or "light").lower()
        tok = _D if theme == "dark" else _L
        # Keys: keep slate in light; muted but readable in dark. Values: dark body text vs light on dark.
        key_c = "#64748B" if theme != "dark" else "#94A3B8"
        val_c = tok["name"] if theme != "dark" else "#E2E8F0"
        self.setStyleSheet(
            f"QDialog {{ background-color: {tok['card_bg']}; }}"
        )
        root = QVBoxLayout(self)
        root.setSpacing(12)
        root.setContentsMargins(22, 22, 22, 22)
        med = controller.medicine_boxes.get(box_id)

        if not med:
            empty = QLabel("No medicine assigned to this box.")
            empty.setStyleSheet(
                f"font-size:9pt;color:{val_c};background:transparent;"
            )
            root.addWidget(empty)
        else:
            exact = med.get("exact_time", med.get("time", "—"))
            for lbl, val in [
                ("Medicine",     med.get("name", "—")),
                ("Quantity",     f"{med.get('quantity', '—')} tablets"),
                ("Dose / Day",   str(med.get("dose_per_day", "—"))),
                ("Expiry",       med.get("expiry", "—")),
                ("Exact Time",   exact),
                ("Instructions", med.get("instructions", "None")),
                ("Last Dose",    med.get("last_dose_taken", "Not taken yet")),
            ]:
                row = QHBoxLayout()
                k = QLabel(lbl + ":")
                k.setStyleSheet(
                    f"font-weight:700;color:{key_c};font-size:9pt;min-width:90px;"
                    "background:transparent;"
                )
                v = QLabel(str(val))
                v.setWordWrap(True)
                v.setStyleSheet(
                    f"font-size:9pt;color:{val_c};background:transparent;"
                )
                row.addWidget(k)
                row.addWidget(v, 1)
                root.addLayout(row)

        btn_row = QHBoxLayout()
        close_btn = QPushButton("Close")
        close_btn.setStyleSheet(
            "background:#6B7280;color:#fff;font-weight:700;padding:8px 22px;"
            "border-radius:8px;border:none;font-size:9pt;"
        )
        close_btn.clicked.connect(self.accept)
        btn_row.addWidget(close_btn)
        if med and not getattr(controller, "is_user_view", lambda: False)():
            rm = QPushButton("Remove Medicine")
            rm.setStyleSheet(
                "background:#DC2626;color:#fff;font-weight:700;padding:8px 22px;"
                "border-radius:8px;border:none;font-size:9pt;"
            )
            rm.clicked.connect(self._remove)
            btn_row.addWidget(rm)
        root.addLayout(btn_row)

    def _remove(self):
        if not self.controller.require_admin():
            return
        try:
            self.controller.alert_scheduler.cancel_medicine_alerts_for_box(self.box_id)
        except Exception:
            pass
        self.controller.medicine_boxes[self.box_id] = None
        self.controller.save_data()
        self.controller.medicine_updated.emit()
        self.accept()
        self.controller.status_message.emit("Medicine removed.")
        QMessageBox.information(self, "Saved", "Medicine removed.")


# ─────────────────────────────────────────────────────────────────────────────
# MainPanelTab
# ─────────────────────────────────────────────────────────────────────────────
class MainPanelTab(QWidget):
    def __init__(self, controller, main_window=None, parent=None):
        super().__init__(parent)
        self.controller = controller
        self.main_window = main_window
        self._tiles = []
        self._theme = (getattr(controller, "appearance_theme", "light") or "light").lower()
        self._build_ui()
        controller.medicine_updated.connect(self.refresh)

    def _tok(self):
        return _L if self._theme != "dark" else _D

    # ── build ──────────────────────────────────────────────────────────
    def _build_ui(self):
        tok = self._tok()
        self.setStyleSheet(f"MainPanelTab{{background:{tok['page_bg']};border:none;}}")
        root = QVBoxLayout(self)
        root.setContentsMargins(20, 16, 20, 16)
        root.setSpacing(14)

        # Header
        hdr = QWidget()
        hdr.setStyleSheet("background:transparent;")
        hl = QVBoxLayout(hdr)
        hl.setContentsMargins(0, 0, 0, 0)
        hl.setSpacing(2)
        self._title = QLabel("Medicine Boxes")
        self._title.setStyleSheet(
            f"font-size:18pt;font-weight:800;color:{tok['title']};"
            "letter-spacing:-0.4px;background:transparent;"
        )
        hl.addWidget(self._title)
        self._sub = QLabel("Select a box to view details or mark a dose.")
        self._sub.setStyleSheet(f"color:{tok['sub']};font-size:9pt;background:transparent;")
        hl.addWidget(self._sub)
        root.addWidget(hdr)

        # Scroll area
        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        try:
            self._scroll.setFrameShape(QFrame.Shape.NoFrame)
        except AttributeError:
            self._scroll.setFrameShape(QFrame.NoFrame)
        self._scroll.setStyleSheet("QScrollArea{background:transparent;border:none;}")

        self._grid_widget = QWidget()
        self._grid_widget.setStyleSheet("background:transparent;")
        self._grid = QGridLayout(self._grid_widget)
        self._grid.setSpacing(14)
        self._grid.setContentsMargins(0, 0, 0, 0)

        for i in range(1, 7):
            box_id = f"B{i}"
            tile = MedicineTile(box_id, self._theme)
            tile.clicked.connect(self._on_click)
            self._tiles.append(tile)

        self._place_tiles(cols=3)
        self._scroll.setWidget(self._grid_widget)

        # wheel forward
        try:
            wt = QEvent.Type.Wheel
        except AttributeError:
            wt = QEvent.Wheel

        class _WF(QObject):
            def __init__(self, area, p=None):
                super().__init__(p)
                self._a = area
            def eventFilter(self, obj, ev):
                if ev.type() == wt and self._a.verticalScrollBar().isVisible():
                    sb = self._a.verticalScrollBar()
                    d = ev.angleDelta().y() if hasattr(ev, "angleDelta") else ev.delta()
                    sb.setValue(sb.value() - d)
                    return True
                return False

        self._wf = _WF(self._scroll, self)
        self.installEventFilter(self._wf)
        root.addWidget(self._scroll)
        self.refresh()

    def _place_tiles(self, cols: int):
        # Remove all from grid
        for t in self._tiles:
            self._grid.removeWidget(t)
        for i, tile in enumerate(self._tiles):
            r, c = divmod(i, cols)
            self._grid.addWidget(tile, r, c)
        for c in range(cols):
            self._grid.setColumnStretch(c, 1)

    # ── resize → reflow columns ────────────────────────────────────────
    def resizeEvent(self, event):
        super().resizeEvent(event)
        W = self.width()
        cols = 3 if W >= 660 else (2 if W >= 420 else 1)
        self._place_tiles(cols)
        # tile height = proportional to column width
        tile_w = (W - 40 - 14 * (cols - 1)) / cols
        tile_h = _clamp(int(tile_w * 0.76), 130, 210)
        for tile in self._tiles:
            tile.setFixedHeight(tile_h)

    # ── theme ──────────────────────────────────────────────────────────
    def apply_theme(self, theme_name: str, content_scale: float = None):
        self._theme = (theme_name or "light").lower()
        tok = self._tok()
        self.setStyleSheet(f"MainPanelTab{{background:{tok['page_bg']};border:none;}}")
        self._title.setStyleSheet(
            f"font-size:18pt;font-weight:800;color:{tok['title']};"
            "letter-spacing:-0.4px;background:transparent;"
        )
        self._sub.setStyleSheet(f"color:{tok['sub']};font-size:9pt;background:transparent;")
        for tile in self._tiles:
            tile.update_theme(self._theme)
        self.refresh()

    # ── events ─────────────────────────────────────────────────────────
    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()

    def _on_click(self, box_id: str):
        BoxDetailDialog(self.controller, box_id, self.main_window, self).exec()

    # ── refresh ────────────────────────────────────────────────────────
    def refresh(self):
        sa = self.controller.alert_settings.get("stock_alerts") or {}
        thr = _clamp(int(sa.get("low_stock_threshold", 5) or 5), 1, 100)
        for tile in self._tiles:
            med = self.controller.medicine_boxes.get(tile.box_id)
            tile.set_data(med, thr)

    # legacy compat
    def _init_theme_styles(self, t):
        pass
    @property
    def box_widgets(self):
        return []
