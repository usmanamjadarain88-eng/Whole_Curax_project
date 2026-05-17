"""Run blocking admin HTTP off the UI thread; apply results on the main thread."""
import threading

try:
    from PyQt6.QtCore import QTimer
except ImportError:
    from PyQt5.QtCore import QTimer


def _main_thread(fn):
    QTimer.singleShot(0, fn)


def run_bg(work, on_done):
    """work() -> result; on_done(result) runs on Qt main thread."""

    def _run():
        try:
            out = work()
        except Exception as e:
            out = e
        _main_thread(lambda: on_done(out))

    threading.Thread(target=_run, daemon=True).start()
