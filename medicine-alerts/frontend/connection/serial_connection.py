"""
connection/serial_connection.py
Handles wired (USB serial) and Bluetooth serial connections to the ESP32/Arduino.
All functions operate on the controller object directly so signals work correctly.
"""
import threading
import time

try:
    import serial
    import serial.tools.list_ports
    SERIAL_AVAILABLE = True
except ImportError:
    SERIAL_AVAILABLE = False


# --------------------------------------------------------------------------
# Port discovery
# --------------------------------------------------------------------------

def get_available_ports():
    """Return list of available serial port names."""
    if not SERIAL_AVAILABLE:
        return []
    try:
        ports = serial.tools.list_ports.comports()
        return [p.device for p in ports]
    except Exception:
        return []


def get_connected_port(controller):
    """Return the name of the currently connected port, or empty string."""
    try:
        ser = getattr(controller, "ser", None)
        if ser and getattr(ser, "is_open", False):
            return getattr(ser, "port", "") or ""
    except Exception:
        pass
    return ""


def get_bluetooth_ports():
    """Return list of port names that look like Bluetooth (name contains 'BT', 'bluetooth', or on Windows 'COM' on BT adapter)."""
    if not SERIAL_AVAILABLE:
        return []
    try:
        ports = serial.tools.list_ports.comports()
        bt_ports = []
        for p in ports:
            desc = (p.description or "").lower()
            name = (p.device or "").lower()
            if "bluetooth" in desc or "bt" in desc or "rfcomm" in name:
                bt_ports.append(p.device)
        return bt_ports
    except Exception:
        return []


# --------------------------------------------------------------------------
# Connect / Disconnect
# --------------------------------------------------------------------------

def connect_to_port(controller, baud=9600, timeout=5):
    """
    Connect to controller._connect_port_name (set by AppController.connect_to_port before calling this).
    Emits connected_changed(True/False) via controller signal.
    """
    if not SERIAL_AVAILABLE:
        controller._log("[Serial] pyserial not installed. Install with: pip install pyserial")
        return False

    port = getattr(controller, "_connect_port_name", "") or ""
    if not port:
        controller._log("[Serial] No port name specified.")
        return False

    # If already connected to the same port, do nothing
    ser = getattr(controller, "ser", None)
    if ser and getattr(ser, "is_open", False) and getattr(ser, "port", "") == port:
        return True

    # Disconnect existing connection first
    _do_disconnect(controller)

    try:
        controller._log(f"[Serial] Connecting to {port} at {baud} baud…")
        new_ser = serial.Serial(port, baud, timeout=timeout)
        time.sleep(0.5)  # give Arduino time to reset
        controller.ser = new_ser
        controller.connected = True
        try:
            controller.connected_changed.emit(True)
        except Exception:
            pass
        controller._log(f"[Serial] Connected to {port}")
        start_serial_thread(controller)
        # Save last connected port so auto-connect works next launch
        try:
            db = controller.get_db()
            if db and hasattr(db, "set"):
                db.set("last_connected_port", port)
        except Exception:
            pass
        return True
    except Exception as e:
        controller._log(f"[Serial] Failed to connect to {port}: {e}")
        controller.connected = False
        try:
            controller.connected_changed.emit(False)
        except Exception:
            pass
        return False


def connect_bluetooth(controller):
    """
    Auto-detect Bluetooth serial port and connect.
    Tries BT-looking ports first, then falls back to first available port.
    """
    if not SERIAL_AVAILABLE:
        controller._log("[Bluetooth] pyserial not installed.")
        return False

    bt_ports = get_bluetooth_ports()
    all_ports = get_available_ports()
    candidates = bt_ports + [p for p in all_ports if p not in bt_ports]

    last_bt = getattr(controller, "last_bt_port", None)
    if last_bt and last_bt in candidates:
        candidates = [last_bt] + [p for p in candidates if p != last_bt]

    for port in candidates:
        controller._log(f"[Bluetooth] Trying {port}…")
        controller._connect_port_name = port
        ok = connect_to_port(controller)
        if ok:
            controller.last_bt_port = port
            return True

    controller._log("[Bluetooth] Could not find Bluetooth device.")
    return False


def disconnect_esp32(controller):
    """Disconnect serial and emit connected_changed(False)."""
    _do_disconnect(controller)
    controller.connected = False
    controller.authenticated = False
    controller.active_led_box = None
    try:
        controller.connected_changed.emit(False)
    except Exception:
        pass
    try:
        controller.authenticated_changed.emit(False)
    except Exception:
        pass


def _do_disconnect(controller):
    """Close serial port quietly without touching signals."""
    try:
        ser = getattr(controller, "ser", None)
        if ser:
            try:
                ser.close()
            except Exception:
                pass
            controller.ser = None
    except Exception:
        pass


# --------------------------------------------------------------------------
# Background serial reader thread
# --------------------------------------------------------------------------

def start_serial_thread(controller):
    """Start background thread that reads serial data and dispatches it."""
    if getattr(controller, "serial_thread", None) and getattr(controller.serial_thread, "is_alive", lambda: False)():
        return  # already running

    def read_loop():
        while getattr(controller, "running", True):
            ser = getattr(controller, "ser", None)
            if not ser or not getattr(ser, "is_open", False):
                time.sleep(0.2)
                continue
            if getattr(controller, "serial_pause", False):
                time.sleep(0.05)
                continue
            try:
                if ser.in_waiting:
                    raw = ser.readline()
                    line = raw.decode("utf-8", errors="ignore").strip()
                    if line:
                        _handle_serial_line(controller, line)
            except Exception as e:
                err = str(e).lower()
                if "closed" in err or "disconnected" in err or "access denied" in err:
                    controller._log(f"[Serial] Device disconnected: {e}")
                    disconnect_esp32(controller)
                    break
                else:
                    controller._log(f"[Serial] Read error: {e}")
            time.sleep(0.02)

    t = threading.Thread(target=read_loop, daemon=True)
    t.start()
    controller.serial_thread = t


def _handle_serial_line(controller, line: str):
    """Route incoming serial data to correct handler."""
    up = line.upper()
    controller._log(f"[Serial RX] {line}")

    # Temperature updates
    if up.startswith("TEMP:") or up.startswith("TEMPERATURE:"):
        try:
            controller.temperature_update.emit(line)
        except Exception:
            pass
        return

    # Auth responses are handled synchronously in verify_pin_esp32, skip here
    if any(k in up for k in ("AUTH_OK", "AUTH_FAIL", "PIN_OK", "PIN_FAIL")):
        return

    # LED feedback
    if up.startswith("LED_ON:") or up.startswith("LED_OFF:"):
        return

    # Status / ping
    if "PONG" in up or up == "OK":
        return


# --------------------------------------------------------------------------
# LED control
# --------------------------------------------------------------------------

def send_led_on(controller, box_id: str):
    """Turn on LED for given box. Returns (True, msg) or (False, error)."""
    ser = getattr(controller, "ser", None)
    if not ser or not getattr(ser, "is_open", False):
        return False, "Not connected to ESP32"
    try:
        controller.serial_pause = True
        cmd = f"LED_ON:{box_id}\n"
        controller._log(f"[LED] Sending: {cmd.strip()}")
        ser.write(cmd.encode("utf-8"))
        ser.flush()
        controller.active_led_box = box_id
        return True, f"LED ON: Box {box_id}"
    except Exception as e:
        disconnect_esp32(controller)
        return False, str(e)
    finally:
        controller.serial_pause = False


def send_led_off(controller, box_id: str):
    """Turn off LED for given box. Returns (True, msg) or (False, error)."""
    ser = getattr(controller, "ser", None)
    if not ser or not getattr(ser, "is_open", False):
        return False, "Not connected to ESP32"
    try:
        controller.serial_pause = True
        cmd = f"LED_OFF:{box_id}\n"
        controller._log(f"[LED] Sending: {cmd.strip()}")
        ser.write(cmd.encode("utf-8"))
        ser.flush()
        if controller.active_led_box == box_id:
            controller.active_led_box = None
        return True, f"LED OFF: Box {box_id}"
    except Exception as e:
        disconnect_esp32(controller)
        return False, str(e)
    finally:
        controller.serial_pause = False


def send_led_all_off(controller):
    """Turn off all LEDs. Returns (True, msg) or (False, error)."""
    ser = getattr(controller, "ser", None)
    if not ser or not getattr(ser, "is_open", False):
        return False, "Not connected to ESP32"
    try:
        controller.serial_pause = True
        controller._log("[LED] Sending: LED_ALL_OFF")
        ser.write(b"LED_ALL_OFF\n")
        ser.flush()
        controller.active_led_box = None
        return True, "All LEDs OFF"
    except Exception as e:
        disconnect_esp32(controller)
        return False, str(e)
    finally:
        controller.serial_pause = False


# --------------------------------------------------------------------------
# Port test
# --------------------------------------------------------------------------

def quick_test_port(port_name: str, baud=9600, timeout=2) -> bool:
    """Try to open port briefly; return True if it opens successfully."""
    if not SERIAL_AVAILABLE:
        return False
    try:
        s = serial.Serial(port_name, baud, timeout=timeout)
        s.close()
        return True
    except Exception:
        return False
