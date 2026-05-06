package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Standalone dose tracking for boxes B1–B3: same chip style as dashboard, mark dose in the active
 * alert window, history table, and local alert suppression for the rest of that day.
 */
class DoseTrackingFragment : Fragment() {

    private val doseSlots = listOf("B1", "B2", "B3")
    private var doseSelectedBoxUpper: String? = null
    private var historyAdapter: DoseHistoryRowsAdapter? = null
    private var selectedMedicine: AdminDemoData.Medicine? = null

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) {
                view?.post { refreshAll() }
            }
        }
    }
    private var syncReceiverRegistered = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_dose_tracking_standalone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<HorizontalScrollView>(R.id.hsvDoseTrackingMedicineChips)
            ?.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = true)

        view.findViewById<MaterialButton>(R.id.btnMarkDoseTaken).setOnClickListener {
            onMarkDoseClicked(view)
        }

        val rvHist = view.findViewById<RecyclerView>(R.id.rvDoseHistory)
        historyAdapter = DoseHistoryRowsAdapter()
        rvHist.layoutManager = LinearLayoutManager(requireContext())
        rvHist.adapter = historyAdapter
        rvHist.isNestedScrollingEnabled = false

        refreshAll()
    }

    private fun rebuildDoseChips(v: View) {
        val ll = v.findViewById<LinearLayout>(R.id.llDoseTrackingMedicineChips) ?: return
        val inflater = LayoutInflater.from(requireContext())
        populateStandaloneMedicineChipRow(
            container = ll,
            inflater = inflater,
            items = buildThreeSlots(),
            computedStatus = { chipStatus(it) },
            selectedBoxUpper = doseSelectedBoxUpper,
            onItemClick = { item ->
                val m = AdminDemoData.medicines.find { it.box.equals(item.box, ignoreCase = true) }
                if (m != null && m.stock > 0) {
                    selectedMedicine = m
                    doseSelectedBoxUpper = m.box.trim().uppercase(Locale.US)
                } else {
                    selectedMedicine = null
                    doseSelectedBoxUpper = item.box.trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
                }
                rebuildDoseChips(v)
                updateMarkButtonState(v)
            },
            onPlaceholderClick = {},
            showBoxLabelWhenPlaceholder = true,
            onPlaceholderItemClick = { item ->
                selectedMedicine = null
                doseSelectedBoxUpper = item.box.trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
                rebuildDoseChips(v)
                updateMarkButtonState(v)
            },
        )
    }

    override fun onStart() {
        super.onStart()
        if (!syncReceiverRegistered) {
            val f = IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(syncReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(syncReceiver, f)
            }
            syncReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (syncReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(syncReceiver)
            } catch (_: Exception) {
            }
            syncReceiverRegistered = false
        }
        super.onStop()
    }

    private fun refreshAll() {
        val v = view ?: return
        DoseAutoMissedMarker.run(requireContext())
        val still = selectedMedicine?.let { sel ->
            AdminDemoData.medicines.find { it.box.equals(sel.box, ignoreCase = true) }
        }
        selectedMedicine = still
        doseSelectedBoxUpper = selectedMedicine?.box?.trim()?.ifEmpty { null }?.uppercase(Locale.US)
        rebuildDoseChips(v)
        historyAdapter?.submit(DoseTrackingLocalStore.readLog(requireContext()))
        updateMarkButtonState(v)
    }

    private fun buildPlaceholderForSlot(slot: String, pad: Int): AdminOverviewFragment.InventoryItem =
        AdminOverviewFragment.InventoryItem(
            id = StandaloneMedicineChipAdapter.PLACEHOLDER_CHIP_MAX_ID - 20 - pad,
            name = "",
            stock = 0,
            dosePerDay = 0,
            exactTime = "",
            expiry = "",
            status = "Normal",
            box = slot,
            addedAt = 0L,
        )

    private fun medicineToInventory(m: AdminDemoData.Medicine, salt: Int): AdminOverviewFragment.InventoryItem =
        AdminOverviewFragment.InventoryItem(
            id = (m.box.hashCode().toLong() + salt),
            name = m.name,
            stock = m.stock,
            dosePerDay = m.dosePerDay,
            exactTime = m.exactTime,
            expiry = m.expiry,
            status = m.status,
            box = m.box,
            addedAt = System.currentTimeMillis(),
        )

    private fun buildThreeSlots(): List<AdminOverviewFragment.InventoryItem> {
        val list = mutableListOf<AdminOverviewFragment.InventoryItem>()
        var salt = 0
        for (slot in doseSlots) {
            val m = AdminDemoData.medicines.find { it.box.equals(slot, ignoreCase = true) }
            if (m != null) {
                list.add(medicineToInventory(m, salt++))
            } else {
                list.add(buildPlaceholderForSlot(slot, salt++))
            }
        }
        return list
    }

    private fun chipStatus(item: AdminOverviewFragment.InventoryItem): String {
        val threshold = AdminDemoData.getLowStockThreshold()
        return when {
            isExpiringSoon(item.expiry) -> "Expiring"
            item.stock in 1..threshold -> "Low"
            else -> "Normal"
        }
    }

    private fun isExpiringSoon(expiry: String): Boolean {
        if (expiry.isBlank()) return false
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val expiryCal = Calendar.getInstance().apply { time = fmt.parse(expiry)!! }
            val now = Calendar.getInstance()
            val limit = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 30) }
            !expiryCal.before(now) && !expiryCal.after(limit)
        } catch (_: Exception) {
            false
        }
    }

    private fun updateMarkButtonState(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.btnMarkDoseTaken)
        val m = selectedMedicine
        btn.isEnabled = m != null && m.stock > 0
    }

    private fun onMarkDoseClicked(view: View) {
        val m = selectedMedicine ?: run {
            CuraxFeedback.warn(this, getString(R.string.dose_tracking_select_box))
            return
        }
        val phase = DoseIntakeClassifier.slotPhase(m)
        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ts = tsFmt.format(Date())
        val dayKey = LocalAlertsController.localDayKeyToday()
        val doseAmt = m.dosePerDay.coerceAtLeast(1)

        when (phase) {
            DoseIntakeClassifier.SlotPhase.NO_SCHEDULE -> {
                appendHistoryRow(
                    ts,
                    m.box,
                    "${m.name} (${getString(R.string.dose_tracking_result_no_schedule)})",
                    0,
                    m.stock,
                    "missed",
                )
                CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_no_time))
            }
            DoseIntakeClassifier.SlotPhase.TOO_EARLY -> {
                appendHistoryRow(
                    ts,
                    m.box,
                    "${m.name} (${getString(R.string.dose_tracking_result_too_early)})",
                    0,
                    m.stock,
                    "skipped_early",
                )
                CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_too_early))
            }
            DoseIntakeClassifier.SlotPhase.ON_TIME, DoseIntakeClassifier.SlotPhase.LATE -> {
                if (m.stock < doseAmt) {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_low_stock))
                    refreshAll()
                    return
                }
                val newStock = m.stock - doseAmt
                val threshold = AdminDemoData.getLowStockThreshold()
                val newStatus = when {
                    newStock == 0 -> "Refill"
                    newStock in 1..threshold -> "Low"
                    else -> "Normal"
                }
                val updated = m.copy(stock = newStock, status = newStatus)
                val kind = DoseIntakeClassifier.kindForSuccessfulMark(phase)
                AdminDemoData.mergeMedicines(listOf(updated))
                DoseTrackingLocalStore.appendLogEntry(
                    requireContext(),
                    mapOf(
                        "timestamp" to ts,
                        "box" to m.box,
                        "medicine" to m.name,
                        "dose_taken" to doseAmt,
                        "remaining" to newStock,
                        "kind" to kind,
                    ),
                )
                DoseTrackingLocalStore.markTakenForDay(requireContext(), m.box, dayKey)
                selectedMedicine = AdminDemoData.medicines.find { it.box.equals(m.box, ignoreCase = true) }
                StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
                requireContext().sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                StandaloneUserMutationSink.notifyLocalChange(
                    requireActivity(),
                    requireContext(),
                    PendingSyncQueueStore.TYPE_DOSE,
                    getString(R.string.pending_sync_title_dose),
                    getString(R.string.pending_sync_subtitle_not_synced),
                )
                val msg = if (phase == DoseIntakeClassifier.SlotPhase.ON_TIME) {
                    getString(R.string.dose_tracking_saved_on_time)
                } else {
                    getString(R.string.dose_tracking_saved_late)
                }
                CuraxFeedback.success(this, msg)
            }
        }
        refreshAll()
    }

    private fun appendHistoryRow(
        ts: String,
        box: String,
        medicine: String,
        doseTaken: Int,
        remaining: Int,
        kind: String,
    ) {
        DoseTrackingLocalStore.appendLogEntry(
            requireContext(),
            mapOf(
                "timestamp" to ts,
                "box" to box,
                "medicine" to medicine,
                "dose_taken" to doseTaken,
                "remaining" to remaining,
                "kind" to kind,
            ),
        )
        StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
    }

    private class DoseHistoryRowsAdapter : RecyclerView.Adapter<DoseHistoryRowsAdapter.VH>() {
        private val rows = mutableListOf<Map<String, Any?>>()

        fun submit(list: List<Map<String, Any?>>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_dose_history_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(rows[position])
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ts = itemView.findViewById<TextView>(R.id.tvDoseHistTs)
            private val box = itemView.findViewById<TextView>(R.id.tvDoseHistBox)
            private val med = itemView.findViewById<TextView>(R.id.tvDoseHistMed)
            private val dose = itemView.findViewById<TextView>(R.id.tvDoseHistDose)
            private val rem = itemView.findViewById<TextView>(R.id.tvDoseHistRem)

            fun bind(m: Map<String, Any?>) {
                ts.text = m["timestamp"]?.toString().orEmpty()
                box.text = m["box"]?.toString().orEmpty()
                med.text = m["medicine"]?.toString().orEmpty()
                val dt = m["dose_taken"]
                dose.text = when (dt) {
                    is Number -> dt.toInt().toString()
                    else -> dt?.toString().orEmpty().ifEmpty { "—" }
                }
                val r = m["remaining"]
                rem.text = when (r) {
                    is Number -> r.toInt().toString()
                    else -> r?.toString().orEmpty().ifEmpty { "—" }
                }
            }
        }
    }
}
