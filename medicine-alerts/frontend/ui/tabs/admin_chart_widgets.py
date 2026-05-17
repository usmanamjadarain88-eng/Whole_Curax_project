"""Simple hub charts for admin dashboard (sparkline + bar chart)."""
try:
    from PyQt6.QtWidgets import QWidget, QVBoxLayout, QLabel
    from PyQt6.QtCore import Qt, QRectF
    from PyQt6.QtGui import QPainter, QPen, QColor, QBrush
except ImportError:
    from PyQt5.QtWidgets import QWidget, QVBoxLayout, QLabel
    from PyQt5.QtCore import Qt, QRectF
    from PyQt5.QtGui import QPainter, QPen, QColor, QBrush


class HubSparklineWidget(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(72)
        self.counts = [0] * 7
        self.placeholder = True

    def set_counts(self, counts, placeholder=False):
        self.counts = list(counts)[:7] if counts else [0] * 7
        while len(self.counts) < 7:
            self.counts.append(0)
        self.placeholder = placeholder
        self.update()

    def paintEvent(self, event):
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        p.fillRect(0, 0, w, h, QColor("#F8FAFC"))
        vals = self.counts
        mx = max(vals) if vals else 0
        if self.placeholder or mx <= 0:
            vals = [2, 4, 3, 6, 4, 5, 3]
            mx = max(vals)
            pen = QPen(QColor("#94A3B8"))
        else:
            pen = QPen(QColor("#0D9488"))
        pen.setWidth(2)
        p.setPen(pen)
        pad = 8
        step = (w - 2 * pad) / max(1, len(vals) - 1)
        pts = []
        for i, v in enumerate(vals):
            x = pad + i * step
            y = h - pad - (v / mx) * (h - 2 * pad) if mx else h - pad
            pts.append((x, y))
        for i in range(1, len(pts)):
            p.drawLine(int(pts[i - 1][0]), int(pts[i - 1][1]), int(pts[i][0]), int(pts[i][1]))
        p.end()


class HubBarChartWidget(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(88)
        self.counts = [0] * 7
        self.placeholder = True

    def set_counts(self, counts, placeholder=False):
        self.counts = list(counts)[:7] if counts else [0] * 7
        while len(self.counts) < 7:
            self.counts.append(0)
        self.placeholder = placeholder
        self.update()

    def paintEvent(self, event):
        p = QPainter(self)
        try:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        p.fillRect(0, 0, w, h, QColor("#FFFFFF"))
        vals = self.counts
        mx = max(vals) if vals else 0
        if self.placeholder or mx <= 0:
            vals = [1, 2, 1, 3, 2, 4, 2]
            mx = max(vals)
            color = QColor("#CBD5E1")
        else:
            color = QColor("#14B8A6")
        n = len(vals)
        pad = 10
        bar_w = max(4, (w - 2 * pad) / n - 4)
        for i, v in enumerate(vals):
            bh = (v / mx) * (h - 24) if mx else 0
            x = pad + i * ((w - 2 * pad) / n) + 2
            y = h - 12 - bh
            p.fillRect(QRectF(x, y, bar_w, bh), QBrush(color))
        p.end()


class HubChartPanel(QWidget):
    """Sparkline + bars with caption (admin hub insights)."""

    def __init__(self, parent=None):
        super().__init__(parent)
        lo = QVBoxLayout(self)
        lo.setContentsMargins(0, 0, 0, 0)
        self._caption = QLabel("Sample curve · real bars when alerts arrive.")
        self._caption.setStyleSheet("color: #64748B; font-size: 9pt;")
        self._caption.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._spark = HubSparklineWidget()
        self._bars = HubBarChartWidget()
        lo.addWidget(self._spark)
        lo.addWidget(self._bars)
        lo.addWidget(self._caption)

    def set_week_counts(self, counts):
        total = sum(counts) if counts else 0
        ph = total <= 0
        self._spark.set_counts(counts, placeholder=ph)
        self._bars.set_counts(counts, placeholder=ph)
        self._caption.setText(
            "Sample curve · real bars replace this when alerts arrive."
            if ph
            else "Live · scaled to this week's peak."
        )
