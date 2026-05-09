package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dose tracking: **Standalone** uses the same B1–B6 chip row as the medicine inventory; **default mode**
 * uses the 6-box grid plus optional BLE `LED_ON`/`LED_OFF` when a box is selected.
 */
class DoseTrackingFragment : Fragment() {

    private fun doseSlotsForMode(): List<String> =
        listOf("B1", "B2", "B3", "B4", "B5", "B6")

    private fun doseSlotSet(): Set<String> =
        doseSlotsForMode().map { it.uppercase(Locale.US) }.toSet()

    private fun doseBoxBinding(box: String): Triple<Int, Int, Int>? =
        when (box.uppercase(Locale.US)) {
            "B1" -> Triple(R.id.dose_card_b1, R.id.tv_dose_b1_name, R.id.tv_dose_b1_qty)
            "B2" -> Triple(R.id.dose_card_b2, R.id.tv_dose_b2_name, R.id.tv_dose_b2_qty)
            "B3" -> Triple(R.id.dose_card_b3, R.id.tv_dose_b3_name, R.id.tv_dose_b3_qty)
            "B4" -> Triple(R.id.dose_card_b4, R.id.tv_dose_b4_name, R.id.tv_dose_b4_qty)
            "B5" -> Triple(R.id.dose_card_b5, R.id.tv_dose_b5_name, R.id.tv_dose_b5_qty)
            "B6" -> Triple(R.id.dose_card_b6, R.id.tv_dose_b6_name, R.id.tv_dose_b6_qty)
            else -> null
        }

    private var doseSelectedBoxUpper: String? = null
    private var historyAdapter: DoseHistoryRowsAdapter? = null
    private var selectedMedicine: AdminDemoData.Medicine? = null

    /** Last box we lit on ESP32 (default mode BLE only). */
    private var lastEsp32LedBox: String? = null

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
    ): View {
        val layout = if (StandaloneUi.isUserStandalone(requireContext())) {
            R.layout.fragment_dose_tracking_standalone
        } else {
            R.layout.fragment_dose_tracking_default
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (StandaloneUi.isUserStandalone(requireContext())) {
            view.findViewById<HorizontalScrollView>(R.id.hsvDoseTrackingMedicineChips)
                ?.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = true)
            view.findViewById<HorizontalScrollView>(R.id.hsvDoseHistoryTable)
                ?.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = false)
        } else {
            bindDoseBoxGrid(view)
        }

        view.findViewById<MaterialButton>(R.id.btnMarkDoseTaken).setOnClickListener {
            if (!StandaloneUserMutationGate.warnIfBlocked(this)) return@setOnClickListener
            onMarkDoseClicked(view)
        }

        val rvHist = view.findViewById<RecyclerView>(R.id.rvDoseHistory)
        historyAdapter = DoseHistoryRowsAdapter()
        rvHist.layoutManager = LinearLayoutManager(requireContext())
        rvHist.adapter = historyAdapter
        rvHist.isNestedScrollingEnabled = false
        rvHist.setHasFixedSize(false)

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (bleBridgeEnabled()) {
            CuraxEsp32BleLink.init(requireContext())
            CuraxEsp32BleLink.connectSavedDevice(requireContext())
        }
        if (view != null) {
            refreshAll()
        }
    }

    override fun onPause() {
        if (bleBridgeEnabled()) {
            CuraxEsp32BleLink.sendLedAllOff()
            lastEsp32LedBox = null
        }
        super.onPause()
    }

    private fun bleBridgeEnabled(): Boolean {
        val ctx = context ?: return false
        return !StandaloneUi.isUserStandalone(ctx)
    }

    /** Mirror desktop: selecting a chip turns that box LED on (and turns previous off). */
    private fun pushEsp32LedForBox(boxUpper: String?) {
        if (!bleBridgeEnabled()) return
        val b = boxUpper?.trim()?.uppercase(Locale.US)?.takeIf { it in doseSlotSet() }
            ?: run {
                lastEsp32LedBox?.let { CuraxEsp32BleLink.sendLedOff(it) }
                lastEsp32LedBox = null
                return
            }
        lastEsp32LedBox?.let { prev ->
            if (prev != b) CuraxEsp32BleLink.sendLedOff(prev)
        }
        CuraxEsp32BleLink.sendLedOn(b)
        lastEsp32LedBox = b
    }

    private fun bindDoseBoxGrid(view: View) {
        for (box in doseSlotsForMode()) {
            val ids = doseBoxBinding(box) ?: continue
            view.findViewById<MaterialCardView>(ids.first)?.setOnClickListener {
                val m = AdminDemoData.medicines.find { it.box.equals(box, ignoreCase = true) }
                if (m != null && m.stock > 0) {
                    selectedMedicine = m
                    doseSelectedBoxUpper = m.box.trim().uppercase(Locale.US)
                } else {
                    selectedMedicine = null
                    doseSelectedBoxUpper = box.trim().uppercase(Locale.US)
                }
                rebuildDoseUi(view)
                updateMarkButtonState(view)
                pushEsp32LedForBox(doseSelectedBoxUpper)
            }
        }
    }

    private fun rebuildDoseBoxGrid(v: View) {
        val ctx = requireContext()
        val ledRing = ContextCompat.getColor(ctx, R.color.dose_box_led_ring)
        val normalStroke = ContextCompat.getColor(ctx, R.color.med_box_stroke)
        val fillNormal = ContextCompat.getColor(ctx, R.color.med_box_bg)
        val fillSelected = ContextCompat.getColor(ctx, R.color.dose_box_bg_selected)
        val d = resources.displayMetrics.density
        val strokeSel = (5f * d).toInt().coerceIn(4, 10)
        val strokeNorm = (2f * d).toInt().coerceAtLeast(2)
        val elevSel = (8f * d).coerceIn(6f, 22f)
        for (box in doseSlotsForMode()) {
            val triple = doseBoxBinding(box) ?: continue
            val card = v.findViewById<MaterialCardView>(triple.first) ?: continue
            val nameTv = v.findViewById<TextView>(triple.second) ?: continue
            val qtyTv = v.findViewById<TextView>(triple.third) ?: continue
            val m = AdminDemoData.medicines.find { it.box.equals(box, ignoreCase = true) }
            if (m != null && m.name.isNotBlank()) {
                nameTv.text = m.name
                qtyTv.text = getString(R.string.dose_box_qty_left, m.stock)
            } else {
                nameTv.text = getString(R.string.dose_box_empty_label)
                qtyTv.text = ""
            }
            val sel = doseSelectedBoxUpper == box.uppercase(Locale.US)
            card.strokeWidth = if (sel) strokeSel else strokeNorm
            card.strokeColor = if (sel) ledRing else normalStroke
            card.setCardBackgroundColor(if (sel) fillSelected else fillNormal)
            card.cardElevation = if (sel) elevSel else 0f
            card.clipToOutline = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (sel) {
                    card.outlineSpotShadowColor = ColorUtils.setAlphaComponent(ledRing, 0xDD)
                    card.outlineAmbientShadowColor = ColorUtils.setAlphaComponent(ledRing, 0x55)
                } else {
                    card.outlineSpotShadowColor = Color.TRANSPARENT
                    card.outlineAmbientShadowColor = Color.TRANSPARENT
                }
            }
        }
    }

    private fun rebuildDoseUi(v: View) {
        if (StandaloneUi.isUserStandalone(requireContext())) {
            rebuildDoseChips(v)
        } else {
            rebuildDoseBoxGrid(v)
        }
    }

    private fun rebuildDoseChips(v: View) {
        val ll = v.findViewById<LinearLayout>(R.id.llDoseTrackingMedicineChips) ?: return
        val inflater = LayoutInflater.from(requireContext())
        populateStandaloneMedicineChipRow(
            container = ll,
            inflater = inflater,
            items = buildSlotsForUi(),
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
                rebuildDoseUi(v)
                updateMarkButtonState(v)
                pushEsp32LedForBox(doseSelectedBoxUpper)
            },
            onPlaceholderClick = {},
            showBoxLabelWhenPlaceholder = true,
            onPlaceholderItemClick = { item ->
                selectedMedicine = null
                doseSelectedBoxUpper = item.box.trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
                rebuildDoseUi(v)
                updateMarkButtonState(v)
                pushEsp32LedForBox(doseSelectedBoxUpper)
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
        super.onStop()
    }

    override fun onDestroyView() {
        if (syncReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(syncReceiver)
            } catch (_: Exception) {
            }
            syncReceiverRegistered = false
        }
        super.onDestroyView()
    }

    /** Apply server/WebSocket snapshot while this tab is off-screen (ViewPager2 stops receivers if unregistered in onStop). */
    fun applyRemoteUserDataSync() {
        if (!isAdded) return
        view?.post { refreshAll() }
    }

    private fun refreshAll() {
        val v = view ?: return
        if (StandaloneUi.isUserStandalone(requireContext()) && AppRole.isUser(requireContext())) {
            DoseTrackingLocalStore.seedStandaloneDemoHistoryIfNeeded(requireContext())
        }
        DoseAutoMissedMarker.run(requireContext())
        val still = selectedMedicine?.let { sel ->
            AdminDemoData.medicines.find { it.box.equals(sel.box, ignoreCase = true) }
        }
        selectedMedicine = still
        doseSelectedBoxUpper = selectedMedicine?.box?.trim()?.ifEmpty { null }?.uppercase(Locale.US)
        rebuildDoseUi(v)
        historyAdapter?.submit(DoseTrackingLocalStore.readLog(requireContext()))
        v.findViewById<RecyclerView>(R.id.rvDoseHistory)?.requestLayout()
        updateMarkButtonState(v)
        if (bleBridgeEnabled()) {
            pushEsp32LedForBox(doseSelectedBoxUpper)
        }
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

    private fun buildSlotsForUi(): List<AdminOverviewFragment.InventoryItem> {
        val list = mutableListOf<AdminOverviewFragment.InventoryItem>()
        var salt = 0
        for (slot in doseSlotsForMode()) {
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
        val linkedOk = StandaloneUserMutationGate.allowMutations(requireContext())
        btn.isEnabled = linkedOk && m != null && m.stock > 0
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
                if (bleBridgeEnabled()) {
                    val bx = m.box.trim().uppercase(Locale.US)
                    CuraxEsp32BleLink.sendLedOff(bx)
                    lastEsp32LedBox = null
                }
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
