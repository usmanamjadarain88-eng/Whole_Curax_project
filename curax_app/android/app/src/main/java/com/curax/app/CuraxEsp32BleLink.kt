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
 * Line protocol matches desktop USB serial: LED_ON:B1, LED_OFF:B1, LED_ALL_OFF, TEMP_SET:...
 */
object CuraxEsp32BleLink {

    private const val TAG = "CuraxEsp32Ble"

    const val ACTION_CONNECTION_STATE = "com.curax.app.ESP32_BLE_CONNECTION_STATE"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_DEVICE_NAME = "device_name"

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
    private var passwordResultCallback: ((Boolean, String) -> Unit)? = null
    private var passwordTimeoutRunnable: Runnable? = null

    @Volatile
    private var awaitingPasswordResponse = false

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

    @SuppressLint("MissingPermission")
    fun disconnect() {
        clearPasswordPending("Disconnected", notify = true)
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

    fun sendLedOn(boxId: String) {
        val id = boxId.trim().uppercase()
        if (!id.matches(Regex("B[1-6]"))) return
        enqueueLine("LED_ON:$id")
    }

    fun sendLedOff(boxId: String) {
        val id = boxId.trim().uppercase()
        if (!id.matches(Regex("B[1-6]"))) return
        enqueueLine("LED_OFF:$id")
    }

    fun sendLedAllOff() {
        enqueueLine("LED_ALL_OFF")
    }

    fun sendTempSet(peltierId: String, enabled: Boolean, minC: Float, maxC: Float) {
        val pid = peltierId.trim().lowercase()
        if (pid != "peltier1" && pid != "peltier2") return
        enqueueLine("TEMP_SET:$pid,$enabled,$minC,$maxC")
    }

    /**
     * Same line protocol as desktop serial [controller.change_device_password].
     * Result is parsed from Nordic UART TX notifications.
     */
    fun requestSetPassword(current: String, newPin: String, timeoutMs: Long = 3500, onResult: (Boolean, String) -> Unit) {
        val ctx = appContext
        if (ctx == null) {
            onResult(false, "Not initialized")
            return
        }
        init(ctx)
        if (!isConnected()) {
            onResult(false, "Not connected to device")
            return
        }
        val cur = current.filter { it.isDigit() }
        val neu = newPin.filter { it.isDigit() }
        if (cur.isEmpty() || neu.isEmpty()) {
            onResult(false, "Enter current and new PIN")
            return
        }
        if (neu.length < 4) {
            onResult(false, "New PIN must be at least 4 digits")
            return
        }
        clearPasswordPending(reason = "", notify = false)
        awaitingPasswordResponse = true
        passwordResultCallback = onResult
        val timeout = Runnable {
            if (!awaitingPasswordResponse) return@Runnable
            awaitingPasswordResponse = false
            passwordResultCallback = null
            onResult(false, "No response from device")
        }
        passwordTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
        enqueueLine("SET_PASSWORD:$cur,$neu")
    }

    private fun clearPasswordPending(reason: String, notify: Boolean) {
        passwordTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        passwordTimeoutRunnable = null
        if (!awaitingPasswordResponse && passwordResultCallback == null) return
        awaitingPasswordResponse = false
        val cb = passwordResultCallback
        passwordResultCallback = null
        if (notify && cb != null && reason.isNotEmpty()) {
            mainHandler.post { cb(false, reason) }
        }
    }

    private fun finishPasswordResult(ok: Boolean, msg: String) {
        awaitingPasswordResponse = false
        passwordTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        passwordTimeoutRunnable = null
        val cb = passwordResultCallback
        passwordResultCallback = null
        cb?.let { mainHandler.post { it(ok, msg) } }
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
                    handleUartLine(line)
                }
            }
        }
    }

    private fun handleUartLine(line: String) {
        if (passwordResultCallback == null) return
        val up = line.uppercase(Locale.US)
        when {
            listOf("PASSWORD_OK", "PWD_OK", "SUCCESS").any { tok -> tok in up } ->
                finishPasswordResult(true, "Device password updated. Use the new PIN next time.")
            "PASSWORD_FAIL_OLD" in up -> finishPasswordResult(false, "Current password is incorrect")
            "PASSWORD_FAIL_FORMAT" in up -> finishPasswordResult(false, "Invalid password format")
            "FAIL" in up -> finishPasswordResult(false, line.ifBlank { "Device rejected change" })
            else -> { /* ignore unrelated chatter until timeout */ }
        }
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
                    clearPasswordPending(reason = "Disconnected", notify = true)
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
