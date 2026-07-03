package com.curax.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * BLE UART bridge to ESP32 using Nordic UART Service (common ESP32 Arduino BLE UART UUIDs).
 * Line protocol: SERVO_OPEN/CLOSE, TEMP_SET, TEMP_QUERY.
 * Box unlock is hardware keypad only — the app does not send PIN_UNLOCK.
 */
object CuraxEsp32BleLink {

    private const val TAG = "CuraxEsp32Ble"

    const val ACTION_CONNECTION_STATE = "com.curax.app.ESP32_BLE_CONNECTION_STATE"
    const val ACTION_UART_LINE = "com.curax.app.ESP32_BLE_UART_LINE"
    const val ACTION_TELEMETRY = "com.curax.app.ESP32_BLE_TELEMETRY"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_LINE = "line"
    const val TEMP_HYSTERESIS_C = 1.0f

    private val UART_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UART_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UART_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var writesReady = false

    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    private val uartRxBuffer = StringBuilder()

    @Volatile
    var tempZone1C: Float? = null
        private set

    @Volatile
    var tempZone2C: Float? = null
        private set

    @Volatile
    var humidityPct: Float? = null
        private set

    @Volatile
    private var lastTelemetryMs: Long = 0L

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun isConnected(): Boolean = writesReady && gatt != null && rxCharacteristic != null

    fun connectedDeviceName(): String {
        val ctx = appContext ?: return ""
        return Prefs(ctx).esp32BleDeviceName.trim()
    }

    fun lastTelemetryMs(): Long = lastTelemetryMs

    private fun clearTelemetry() {
        tempZone1C = null
        tempZone2C = null
        humidityPct = null
        lastTelemetryMs = 0L
        broadcastTelemetry()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        clearTelemetry()
        synchronized(uartRxBuffer) { uartRxBuffer.clear() }
        writesReady = false
        rxCharacteristic = null
        txCharacteristic = null
        pendingWrites.clear()
        writeInFlight = false
        val g = gatt
        gatt = null
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
        broadcastState(false, "")
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, address: String, displayName: String = "") {
        init(context)
        val ctx = appContext ?: return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            broadcastState(false, "")
            return
        }
        val dev = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            broadcastState(false, "")
            return
        }
        disconnect()
        writesReady = false
        rxCharacteristic = null
        txCharacteristic = null
        val prefs = Prefs(ctx)
        prefs.esp32BleDeviceAddress = address
        if (displayName.isNotBlank()) {
            prefs.esp32BleDeviceName = displayName
        }
        val friendly = displayName.ifBlank { prefs.esp32BleDeviceName.ifBlank { address } }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(ctx, false, gattCallback(friendly), BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(ctx, false, gattCallback(friendly))
        }
    }

    fun connectSavedDevice(context: Context) {
        val addr = Prefs(context.applicationContext).esp32BleDeviceAddress.trim()
        if (addr.isEmpty()) return
        connect(context, addr)
    }

    fun sendServoOpen(boxId: String) {
        val id = boxId.trim().uppercase(Locale.US)
        if (!id.matches(Regex("B[1-6]"))) return
        enqueueLine("SERVO_OPEN:$id")
    }

    fun sendServoClose(boxId: String) {
        val id = boxId.trim().uppercase(Locale.US)
        if (!id.matches(Regex("B[1-6]"))) return
        enqueueLine("SERVO_CLOSE:$id")
    }

    fun sendServoAllClose() {
        enqueueLine("SERVO_ALL_CLOSE")
    }

    /** Bare-board test: ESP32 replies with OK:HELLO (or READY on connect). */
    fun sendPing(): Boolean {
        if (!isConnected()) return false
        enqueueLine("PING")
        return true
    }

    fun sendTempSet(peltierId: String, enabled: Boolean, targetC: Float) {
        val pid = peltierId.trim().lowercase()
        if (pid != "peltier1" && pid != "peltier2") return
        val target = String.format(Locale.US, "%.1f", targetC)
        enqueueLine("TEMP_SET:$pid,$enabled,$target")
    }

    /** Push saved T-adjustment thresholds to ESP32 (fans Z1, cooler Z2 auto control). */
    fun syncPeltierThresholds(context: Context) {
        if (!isConnected()) return
        val prefs = Prefs(context.applicationContext)
        sendTempSet("peltier1", prefs.peltier1Enabled, prefs.peltier1TargetC)
        sendTempSet("peltier2", prefs.peltier2Enabled, prefs.peltier2TargetC)
    }

    fun sendTempQuery() {
        if (!isConnected()) return
        enqueueLine("TEMP_QUERY")
    }

    private fun appendUartRx(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val chunk = String(bytes, Charset.forName("UTF-8"))
        synchronized(uartRxBuffer) {
            uartRxBuffer.append(chunk)
            while (true) {
                val s = uartRxBuffer.toString()
                val n = s.indexOf('\n')
                if (n < 0) break
                val line = s.substring(0, n).trim()
                uartRxBuffer.delete(0, n + 1)
                if (line.isNotEmpty()) {
                    parseTelemetryFromLine(line)
                }
            }
        }
    }

    private fun parseTelemetryFromLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        fun parseAfter(vararg prefixes: String): Float? {
            for (prefix in prefixes) {
                val idx = trimmed.indexOf(prefix, ignoreCase = true)
                if (idx < 0) continue
                var rest = trimmed.substring(idx + prefix.length).trim()
                if (rest.startsWith(":")) rest = rest.drop(1).trim()
                val token = rest.split(",", " ", ";", "\t").firstOrNull()?.trim().orEmpty()
                token.toFloatOrNull()?.let { return it }
            }
            return null
        }

        var changed = false
        parseAfter("TEMP1", "TEMPERATURE1")?.let {
            tempZone1C = it
            changed = true
        }
        parseAfter("TEMP2", "TEMPERATURE2")?.let {
            tempZone2C = it
            changed = true
        }
        parseAfter("HUM", "HUMIDITY", "RH")?.let {
            humidityPct = it
            changed = true
        }
        if (changed) {
            lastTelemetryMs = System.currentTimeMillis()
            broadcastTelemetry()
        }
    }

    private fun broadcastTelemetry() {
        val ctx = appContext ?: return
        ctx.sendBroadcast(Intent(ACTION_TELEMETRY))
    }

    private fun enqueueLine(line: String) {
        val bytes = (line.filter { it != '\n' && it != '\r' } + "\n").toByteArray(Charset.forName("UTF-8"))
        synchronized(pendingWrites) {
            pendingWrites.addLast(bytes)
        }
        flushWritesFromMain()
    }

    private fun flushWritesFromMain() {
        mainHandler.post { drainWriteQueue() }
    }

    private fun drainWriteQueue() {
        synchronized(this) {
            if (!writesReady || writeInFlight) return
            val g = gatt ?: return
            val rx = rxCharacteristic ?: return
            val chunk = synchronized(pendingWrites) {
                pendingWrites.pollFirst()
            } ?: return
            writeInFlight = true
            try {
                val props = rx.properties
                val writeType =
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                rx.writeType = writeType
                val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(rx, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = chunk
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(rx)
                }
                if (!writeOk) {
                    writeInFlight = false
                    synchronized(pendingWrites) {
                        pendingWrites.addFirst(chunk)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
                writeInFlight = false
                synchronized(pendingWrites) {
                    pendingWrites.addFirst(chunk)
                }
            }
        }
    }

    private fun broadcastState(connected: Boolean, name: String) {
        val ctx = appContext ?: return
        if (connected) {
            Prefs(ctx).esp32BleEverConnected = true
        }
        ctx.sendBroadcast(
            Intent(ACTION_CONNECTION_STATE).apply {
                putExtra(EXTRA_CONNECTED, connected)
                putExtra(EXTRA_DEVICE_NAME, name)
            },
        )
    }

    private fun gattCallback(friendlyName: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                    return
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clearTelemetry()
                    synchronized(uartRxBuffer) { uartRxBuffer.clear() }
                    writesReady = false
                    rxCharacteristic = null
                    txCharacteristic = null
                    writeInFlight = false
                    broadcastState(false, "")
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (CuraxEsp32BleLink.gatt == gatt) {
                        CuraxEsp32BleLink.gatt = null
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnect()
                    return
                }
                val svc = gatt.getService(UART_SERVICE) ?: run {
                    Log.w(TAG, "UART service not found")
                    disconnect()
                    return
                }
                val rx = svc.getCharacteristic(UART_RX) ?: run {
                    disconnect()
                    return
                }
                rxCharacteristic = rx
                val tx = svc.getCharacteristic(UART_TX)
                if (tx == null) {
                    txCharacteristic = null
                    writesReady = true
                    broadcastState(true, friendlyName)
                    flushWritesFromMain()
                    return
                }
                txCharacteristic = tx
                synchronized(uartRxBuffer) { uartRxBuffer.clear() }
                gatt.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD) ?: run {
                    writesReady = true
                    broadcastState(true, friendlyName)
                    flushWritesFromMain()
                    return
                }
                val descOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                if (!descOk) {
                    writesReady = true
                    broadcastState(true, friendlyName)
                    flushWritesFromMain()
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                    writesReady = true
                    broadcastState(true, friendlyName)
                    flushWritesFromMain()
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                writeInFlight = false
                flushWritesFromMain()
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                deliverTxNotification(characteristic)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    deliverTxNotification(characteristic, value)
                }
            }
        }
    }

    private fun deliverTxNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray? = null,
    ) {
        val tx = txCharacteristic ?: return
        if (characteristic.uuid != tx.uuid) return
        val bytes = value
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return
            } else {
                @Suppress("DEPRECATION")
                characteristic.value ?: return
            }
        appendUartRx(bytes)
    }
}
