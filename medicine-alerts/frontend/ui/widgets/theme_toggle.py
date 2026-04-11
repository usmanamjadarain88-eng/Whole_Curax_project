# CuraX ThemeToggle — teal-branded, smooth QPropertyAnimation knob
import math
try:
    from PyQt6.QtWidgets import QWidget
    from PyQt6.QtCore import Qt, QPropertyAnimation, QEasingCurve, pyqtSignal, QPointF, QRectF, pyqtProperty
    from PyQt6.QtGui import QPainter, QPen, QBrush, QColor, QPainterPath, QLinearGradient
except ImportError:
    from PyQt5.QtWidgets import QWidget
    from PyQt5.QtCore import Qt, QPropertyAnimation, QEasingCurve, pyqtSignal, QPointF, QRectF, pyqtProperty
    from PyQt5.QtGui import QPainter, QPen, QBrush, QColor, QPainterPath, QLinearGradient


class ThemeToggle(QWidget):
    """Teal pill toggle: sun=light, moon=dark. Smooth animated knob, CuraX branded."""

    theme_changed = pyqtSignal(str)  # "light" or "dark"

    def __init__(self, parent=None):
        super().__init__(parent)
        self._slider_pos = 0.0   # 0.0 = light, 1.0 = dark
        self._app_theme = "light"
        self._anim = QPropertyAnimation(self, b"sliderPosition")
        self._anim.setDuration(260)
        try:
            self._anim.setEasingCurve(QEasingCurve.Type.InOutCubic)
        except AttributeError:
            self._anim.setEasingCurve(QEasingCurve.InOutCubic)
        self.setFixedSize(56, 28)
        try:
            self.setCursor(Qt.CursorShape.PointingHandCursor)
        except AttributeError:
            self.setCursor(Qt.PointingHandCursor)
        try:
            self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        except AttributeError:
            self.setFocusPolicy(Qt.StrongFocus)

    def get_slider_position(self):
        return self._slider_pos

    def set_slider_position(self, value):
        self._slider_pos = max(0.0, min(1.0, float(value)))
        self.update()

    sliderPosition = pyqtProperty(float, get_slider_position, set_slider_position)

    def is_dark(self):
        return self._slider_pos >= 0.5

    def set_theme(self, theme: str):
        name = (theme or "light").lower()
        self._app_theme = "dark" if name == "dark" else "light"
        target = 1.0 if name == "dark" else 0.0
        if abs(self._slider_pos - target) < 0.01:
            self.update()
            return
        self._anim.stop()
        self._anim.setStartValue(self._slider_pos)
        self._anim.setEndValue(target)
        self._anim.start()

    def _toggle(self):
        target = 0.0 if self.is_dark() else 1.0
        self._anim.stop()
        self._anim.setStartValue(self._slider_pos)
        self._anim.setEndValue(target)
        self._anim.start()
        self.theme_changed.emit("dark" if target >= 0.5 else "light")

    def mousePressEvent(self, event):
        try:
            btn = Qt.MouseButton.LeftButton
        except AttributeError:
            btn = Qt.LeftButton
        if event.button() == btn and self.rect().contains(event.pos()):
            self._toggle()
            event.accept()
            return
        super().mousePressEvent(event)

    def paintEvent(self, event):
        qp = QPainter(self)
        try:
            qp.setRenderHint(QPainter.RenderHint.Antialiasing)
        except AttributeError:
            qp.setRenderHint(QPainter.Antialiasing)

        w, h = self.width(), self.height()
        t = self._slider_pos          # 0 = light, 1 = dark
        on_dark_app = self._app_theme == "dark"

        # ── Track gradient: teal (light mode) ↔ deep navy (dark mode) ────────
        track_grad = QLinearGradient(0, 0, w, 0)
        if on_dark_app:
            # dark app: dark navy track, cyan accent on active side
            r1 = QColor(14, 28, 46)
            r2 = QColor(45, 212, 191, 180)
        else:
            # light app: soft mint → vivid teal
            r1 = QColor(204, 251, 241, 200)
            r2 = QColor(13, 148, 136)
        # blend based on slider
        track_grad.setColorAt(0.0, r1)
        track_grad.setColorAt(1.0, r2)

        # track pill
        rx = 14.0
        path = QPainterPath()
        path.addRoundedRect(QRectF(0, 0, w, h), rx, rx)
        qp.setPen(Qt.PenStyle.NoPen)
        qp.fillPath(path, QBrush(track_grad))

        # track border
        if on_dark_app:
            border = QColor(45, 212, 191, 100)
        else:
            border = QColor(13, 148, 136, 160)
        try:
            qp.setPen(QPen(border, 1.2))
        except TypeError:
            qp.setPen(QPen(border))
        try:
            qp.setBrush(Qt.BrushStyle.NoBrush)
        except AttributeError:
            qp.setBrush(Qt.NoBrush)
        qp.drawPath(path)

        # ── Knob ─────────────────────────────────────────────────────────────
        pad = 3
        knob_d = h - pad * 2
        # light → knob left; dark → knob right
        knob_x = pad + t * (w - pad * 2 - knob_d)
        knob_y = pad

        # shadow
        qp.setPen(Qt.PenStyle.NoPen)
        qp.setBrush(QColor(0, 0, 0, 30))
        qp.drawEllipse(QRectF(knob_x + 1, knob_y + 1, knob_d, knob_d))

        # knob body: white in light mode, dark teal in dark mode
        if on_dark_app:
            knob_color = QColor(14, 212, 191)
        else:
            knob_color = QColor(255, 255, 255)
        qp.setBrush(QColor(knob_color))
        qp.setPen(Qt.PenStyle.NoPen)
        qp.drawEllipse(QRectF(knob_x, knob_y, knob_d, knob_d))

        # ── Icon inside knob ─────────────────────────────────────────────────
        cx = knob_x + knob_d / 2
        cy = knob_y + knob_d / 2
        icon_sz = knob_d * 0.30

        if t < 0.5:
            # Sun icon
            if on_dark_app:
                icon_c = QColor(14, 28, 46)
            else:
                icon_c = QColor(13, 148, 136)
            qp.setBrush(QBrush(icon_c))
            qp.setPen(Qt.PenStyle.NoPen)
            qp.drawEllipse(QPointF(cx, cy), icon_sz, icon_sz)
            for i in range(8):
                ang = math.radians(i * 45)
                x1 = cx + math.cos(ang) * (icon_sz + 1.5)
                y1 = cy + math.sin(ang) * (icon_sz + 1.5)
                x2 = cx + math.cos(ang) * (icon_sz + 3.0)
                y2 = cy + math.sin(ang) * (icon_sz + 3.0)
                try:
                    qp.setPen(QPen(icon_c, 1.2))
                except TypeError:
                    qp.setPen(QPen(icon_c))
                qp.drawLine(int(x1), int(y1), int(x2), int(y2))
                qp.setPen(Qt.PenStyle.NoPen)
        else:
            # Moon crescent
            if on_dark_app:
                icon_c = QColor(14, 28, 46)
            else:
                icon_c = QColor(13, 148, 136)
            moon_path = QPainterPath()
            moon_path.addEllipse(QPointF(cx, cy), icon_sz + 0.5, icon_sz + 0.5)
            cut = QPainterPath()
            cut.addEllipse(QPointF(cx + icon_sz * 0.5, cy - icon_sz * 0.2),
                           icon_sz * 0.78, icon_sz * 0.78)
            crescent = moon_path.subtracted(cut)
            qp.setBrush(QBrush(icon_c))
            qp.setPen(Qt.PenStyle.NoPen)
            qp.drawPath(crescent)

        qp.end()
