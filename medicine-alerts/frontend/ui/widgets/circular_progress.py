try:
    from PyQt6.QtWidgets import QWidget
    from PyQt6.QtCore import Qt, QRectF
    from PyQt6.QtGui import QPainter, QPen, QColor, QConicalGradient, QLinearGradient, QFont
except ImportError:
    from PyQt5.QtWidgets import QWidget
    from PyQt5.QtCore import Qt, QRectF
    from PyQt5.QtGui import QPainter, QPen, QColor, QConicalGradient, QLinearGradient, QFont


class CircularProgressWidget(QWidget):
    """
    Beautiful donut-ring progress.
    - Thin track ring
    - Thick coloured arc drawn with conical gradient for a glowing feel
    - Percentage text + small unit label drawn in centre
    """

    def __init__(self, parent=None, value=0, full_scale=80,
                 track_color="#1E3A4A", fill_color="#2DD4BF", bg_color="transparent"):
        super().__init__(parent)
        self._value = 0
        self._full_scale = max(1, full_scale)
        self._track_color = track_color
        self._track_pen_width = 8
        self._fill_color = fill_color
        self._fill_color2 = fill_color      # second gradient stop (set via setColors)
        self._bg_color = bg_color
        self._text_color = "#E2E8F0"
        self.setValue(value)
        self.setMinimumSize(88, 88)
        try:
            self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        except AttributeError:
            self.setAttribute(Qt.WA_TranslucentBackground)

    def value(self):
        return self._value

    def setValue(self, value):
        self._value = max(0, min(100, value))
        self.update()

    def setFullScale(self, scale):
        self._full_scale = max(1, scale)
        self.update()

    def setColors(self, track=None, fill=None, bg=None, track_pen_width=None,
                  fill2=None, text_color=None):
        if track is not None:
            self._track_color = track
        if fill is not None:
            self._fill_color = fill
            if self._fill_color2 == self._fill_color or fill2 is None:
                self._fill_color2 = fill
        if fill2 is not None:
            self._fill_color2 = fill2
        if bg is not None:
            self._bg_color = bg
        if track_pen_width is not None:
            self._track_pen_width = max(4, int(track_pen_width))
        if text_color is not None:
            self._text_color = text_color
        self.update()

    def paintEvent(self, event):
        super().paintEvent(event)
        qp = QPainter(self)
        try:
            qp.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            qp.setRenderHint(QPainter.Antialiasing)

        w, h = self.width(), self.height()
        side = min(w, h)
        x0 = (w - side) // 2
        y0 = (h - side) // 2
        ring_w = max(7, side // 9)          # ring thickness scales with widget size
        margin = ring_w // 2 + 2
        r = side - 2 * margin
        rect = QRectF(x0 + margin, y0 + margin, r, r)
        cx = x0 + side / 2
        cy = y0 + side / 2

        try:
            round_cap = Qt.PenCapStyle.RoundCap
            no_brush  = Qt.BrushStyle.NoBrush
        except AttributeError:
            round_cap = Qt.RoundCap
            no_brush  = Qt.NoBrush

        # ── Track ring ──────────────────────────────────────────────────────
        track_pen = QPen(QColor(self._track_color), ring_w)
        track_pen.setCapStyle(round_cap)
        qp.setPen(track_pen)
        qp.setBrush(no_brush)
        qp.drawEllipse(rect)

        # ── Filled arc ──────────────────────────────────────────────────────
        if self._value > 0:
            span_deg = (self._value / 100.0) * 360.0

            # Conical gradient for shimmer effect
            grad = QConicalGradient(cx, cy, 90)
            grad.setColorAt(0.0,  QColor(self._fill_color))
            grad.setColorAt(0.5,  QColor(self._fill_color2))
            grad.setColorAt(1.0,  QColor(self._fill_color))

            arc_pen = QPen(grad, ring_w)
            arc_pen.setCapStyle(round_cap)
            qp.setPen(arc_pen)
            qp.setBrush(no_brush)
            # Qt uses 1/16th degrees; start at top (90°), counter-clockwise = negative span
            qp.drawArc(rect,
                       int(90 * 16),
                       int(-span_deg * 16))

        # ── Centre text ─────────────────────────────────────────────────────
        try:
            font = QFont("Segoe UI", max(7, side // 7), QFont.Weight.Bold)
        except AttributeError:
            font = QFont("Segoe UI", max(7, side // 7), QFont.Bold)
        qp.setFont(font)
        qp.setPen(QColor(self._text_color))
        pct_text = f"{int(self._value)}%"
        try:
            qp.drawText(rect.toRect(), Qt.AlignmentFlag.AlignCenter, pct_text)
        except AttributeError:
            qp.drawText(rect.toRect(), Qt.AlignCenter, pct_text)

        qp.end()
