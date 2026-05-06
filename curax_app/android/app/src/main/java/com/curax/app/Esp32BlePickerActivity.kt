package com.curax.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Scan for BLE peripherals; pick one to store as ESP32 UART bridge (Nordic UART Service).
 */
class Esp32BlePickerActivity : AppCompatActivity() {

    private var scanner: BluetoothLeScanner? = null
    private val rows = linkedMapOf<String, BleRow>()
    private lateinit var adapter: BleRowsAdapter
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            beginScanUi()
        } else {
            CuraxFeedback.warn(this, getString(R.string.ble_picker_permission_denied))
            finish()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val addr = dev.address ?: return
            val name = result.scanRecord?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
                ?: dev.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: getString(R.string.ble_picker_unknown_device)
            runOnUiThread {
                rows[addr] = BleRow(addr, name)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_esp32_ble_picker)

        findViewById<MaterialToolbar>(R.id.toolbarBlePicker).setNavigationOnClickListener {
            stopScan()
            finish()
        }

        rv = findViewById(R.id.rvBleDevices)
        tvEmpty = findViewById(R.id.tvBleEmpty)
        progress = findViewById(R.id.progressBleScan)
        adapter = BleRowsAdapter(rows) { row ->
            stopScan()
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_MAC, row.address)
                    putExtra(EXTRA_NAME, row.displayName)
                },
            )
            finish()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapterBt = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapterBt == null || !adapterBt.isEnabled) {
            CuraxFeedback.warn(this, getString(R.string.ble_picker_bt_off))
            finish()
            return
        }
        scanner = adapterBt.bluetoothLeScanner

        if (!hasScanPermissions()) {
            permLauncher.launch(requiredPermissions())
        } else {
            beginScanUi()
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasScanPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginScanUi() {
        rows.clear()
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        val sc = scanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            sc.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            CuraxFeedback.warn(this, getString(R.string.ble_picker_permission_denied))
            finish()
            return
        }
        rv.postDelayed({ stopScan() }, 15_000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        progress.visibility = View.GONE
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private data class BleRow(val address: String, val displayName: String)

    private class BleRowsAdapter(
        private val data: MutableMap<String, BleRow>,
        private val onPick: (BleRow) -> Unit,
    ) : RecyclerView.Adapter<BleRowsAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.tvBleRowName)
            val addr: TextView = itemView.findViewById(R.id.tvBleRowAddr)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ble_scan_row, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data.values.toList()[position]
            holder.name.text = row.displayName
            holder.addr.text = row.address
            holder.itemView.setOnClickListener { onPick(row) }
        }
    }

    companion object {
        const val EXTRA_MAC = "extra_ble_mac"
        const val EXTRA_NAME = "extra_ble_name"
    }
}
