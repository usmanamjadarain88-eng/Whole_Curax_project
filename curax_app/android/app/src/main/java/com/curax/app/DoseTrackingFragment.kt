package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
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

    private fun doseSlotsForMode(): List<String> {
        if (!CareUi.effectiveStandaloneShell(requireContext())) {
            return listOf("B1", "B2", "B3", "B4", "B5", "B6")
        }
        val meds = AdminDemoData.medicines
        val maxN = meds.mapNotNull { m ->
            val s = m.box.trim().uppercase(Locale.US)
            if (!s.startsWith("B") || s.length < 2) null else s.substring(1).toIntOrNull()
        }.maxOrNull() ?: 0
        val hi = maxOf(maxN, 6)
        return (1..hi).map { "B$it" }
    }

    private fun medicineAtSelectedBox(): AdminDemoData.Medicine? {
        val key = doseSelectedBoxUpper ?: return null
        return AdminDemoData.medicines.find { it.box.trim().equals(key, ignoreCase = true) }
    }

    private fun updateScheduleSummary(v: View) {
        val tv = v.findViewById<TextView>(R.id.tvDoseScheduleSummary) ?: return
        val med = medicineAtSelectedBox()
        if (med != null) {
            val timesLine = getString(R.string.medicine_detail_times, med.displayScheduleLabel().ifBlank { "—" })
            val hint = getString(R.string.dose_tracking_schedule_window_hint)
            tv.text = "$timesLine\n$hint"
        } else if (doseSelectedBoxUpper != null) {
            tv.text = getString(R.string.dose_tracking_schedule_summary_slot_empty, doseSelectedBoxUpper!!)
        } else {
            tv.text = getString(R.string.dose_tracking_schedule_summary_empty)
        }
    }

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
        val layout = if (CareUi.effectiveStandaloneShell(requireContext())) {
            R.layout.fragment_dose_tracking_standalone
        } else {
            R.layout.fragment_dose_tracking_default
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (CareUi.effectiveStandaloneShell(requireContext())) {
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
        if (CareUi.isAdminCareMode(ctx)) return false
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
        val primaryBorder = ContextCompat.getColor(ctx, R.color.button_primary_bg)
        val fillClear = Color.TRANSPARENT
        // Mild “hover” on selected: same outline colour as Mark Dose button + soft tint inside
        val fillSelected = ColorUtils.setAlphaComponent(primaryBorder, 32)
        val primaryText = ContextCompat.getColor(ctx, R.color.text_primary)
        val secondaryText = ContextCompat.getColor(ctx, R.color.text_secondary)
        val d = resources.displayMetrics.density
        val strokeNorm = (2f * d).toInt().coerceAtLeast(2)
        val strokeSel = (3.5f * d).toInt().coerceIn(4, 12)
        for (box in doseSlotsForMode()) {
            val triple = doseBoxBinding(box) ?: continue
            val card = v.findViewById<MaterialCardView>(triple.first) ?: continue
            val nameTv = v.findViewById<TextView>(triple.second) ?: continue
            val qtyTv = v.findViewById<TextView>(triple.third) ?: continue
            val m = AdminDemoData.medicines.find { it.box.equals(box, ignoreCase = true) }
            if (m != null && m.name.isNotBlank()) {
                nameTv.text = m.name.trim()
                nameTv.maxLines = 1
                nameTv.ellipsize = TextUtils.TruncateAt.END
                qtyTv.text = getString(R.string.dose_box_qty_left, m.stock)
                nameTv.setTextColor(primaryText)
                qtyTv.setTextColor(secondaryText)
            } else {
                nameTv.text = getString(R.string.dose_box_empty_label)
                nameTv.maxLines = 1
                nameTv.ellipsize = TextUtils.TruncateAt.END
                qtyTv.text = ""
                nameTv.setTextColor(secondaryText)
                qtyTv.setTextColor(secondaryText)
            }
            val sel = doseSelectedBoxUpper == box.uppercase(Locale.US)
            card.strokeWidth = if (sel) strokeSel else strokeNorm
            card.strokeColor = primaryBorder
            card.setCardBackgroundColor(if (sel) fillSelected else fillClear)
            card.cardElevation = 0f
            card.clipToOutline = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                card.outlineSpotShadowColor = Color.TRANSPARENT
                card.outlineAmbientShadowColor = Color.TRANSPARENT
            }
        }
    }

    private fun rebuildDoseUi(v: View) {
        if (CareUi.effectiveStandaloneShell(requireContext())) {
            rebuildDoseChips(v)
        } else {
            rebuildDoseBoxGrid(v)
        }
        updateScheduleSummary(v)
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
        DoseAutoMissedMarker.run(requireContext())
        val still = selectedMedicine?.let { sel ->
            AdminDemoData.medicines.find { it.box.equals(sel.box, ignoreCase = true) }
        }
        selectedMedicine = still
        doseSelectedBoxUpper = selectedMedicine?.box?.trim()?.ifEmpty { null }?.uppercase(Locale.US)
        rebuildDoseUi(v)
        val logRows = DoseTrackingLocalStore.readLog(requireContext())
            .filter { DoseIntakeClassifier.isManualMarkDoseLogKind(it["kind"]?.toString()) }
        historyAdapter?.submit(logRows)
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

    private fun medicineToInventory(m: AdminDemoData.Medicine, salt: Int): AdminOverviewFragment.InventoryItem {
        val eff = m.effectiveScheduleTimes()
        val multi = m.usesMultipleTimesPerDay()
        return AdminOverviewFragment.InventoryItem(
            id = (m.box.hashCode().toLong() + salt),
            name = m.name,
            stock = m.stock,
            dosePerDay = m.dosePerDay,
            exactTime = eff.firstOrNull() ?: m.exactTime,
            expiry = m.expiry,
            status = m.status,
            box = m.box,
            addedAt = System.currentTimeMillis(),
            useMultipleTimesPerDay = multi,
            scheduleTimesList = eff.toMutableList(),
        )
    }

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
            item.stock <= threshold -> "Low"
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
        val ctxMark = DoseIntakeClassifier.markContext(m)
        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ts = tsFmt.format(Date())
        val dayKey = LocalAlertsController.localDayKeyToday()
        val doseAmt = m.dosePerAdministration()
        val slotForLog = ctxMark.activeSlotHhMm

        when (ctxMark.phase) {
            DoseIntakeClassifier.SlotPhase.NO_SCHEDULE -> {
                CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_no_time))
            }
            DoseIntakeClassifier.SlotPhase.TOO_EARLY -> {
                val next = ctxMark.nextSlotHhMm
                if (next != null) {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_too_early_next, next))
                } else {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_too_early))
                }
            }
            DoseIntakeClassifier.SlotPhase.BETWEEN_SLOTS -> {
                val next = ctxMark.nextSlotHhMm
                if (next != null) {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_between_slots, next))
                } else {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_too_late))
                }
            }
            DoseIntakeClassifier.SlotPhase.TOO_LATE -> {
                CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_too_late))
            }
            DoseIntakeClassifier.SlotPhase.ON_TIME, DoseIntakeClassifier.SlotPhase.LATE -> {
                val slot = slotForLog
                if (slot == null) {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_no_time))
                } else if (m.stock < doseAmt) {
                    CuraxFeedback.warn(this, getString(R.string.dose_tracking_err_low_stock))
                    refreshAll()
                    return
                } else {
                val newStock = m.stock - doseAmt
                val threshold = AdminDemoData.getLowStockThreshold()
                val newStatus = when {
                    newStock == 0 -> "Refill"
                    newStock in 1..threshold -> "Low"
                    else -> "Normal"
                }
                val updated = m.copy(stock = newStock, status = newStatus)
                val kind = DoseIntakeClassifier.kindForSuccessfulMark(ctxMark.phase)
                AdminDemoData.mergeMedicines(requireContext(), listOf(updated))
                DoseTrackingLocalStore.appendLogEntry(
                    requireContext(),
                    mapOf(
                        "timestamp" to ts,
                        "box" to m.box,
                        "medicine" to m.name,
                        "dose_taken" to doseAmt,
                        "remaining" to newStock,
                        "kind" to kind,
                        "scheduled_slot" to slot,
                    ),
                )
                DoseTrackingLocalStore.markTakenForSlot(requireContext(), m.box, dayKey, slot)
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
                val msg = if (ctxMark.phase == DoseIntakeClassifier.SlotPhase.ON_TIME) {
                    getString(R.string.dose_tracking_saved_on_time)
                } else {
                    getString(R.string.dose_tracking_saved_late)
                }
                CuraxFeedback.success(this, msg)
                }
            }
        }
        refreshAll()
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
                val slot = m["scheduled_slot"]?.toString()?.trim().orEmpty()
                med.text = if (slot.isNotEmpty()) "${m["medicine"]} ($slot)" else m["medicine"]?.toString().orEmpty()
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
