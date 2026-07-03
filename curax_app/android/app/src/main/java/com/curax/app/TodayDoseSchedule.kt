package com.curax.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Today's medicine slots + recent missed doses for dashboard strip and home widget. */
object TodayDoseSchedule {

    enum class SlotStatus {
        TAKEN,
        MISSED,
        DUE_NOW,
        UPCOMING,
    }

    data class DoseSlot(
        val box: String,
        val medicineName: String,
        val slotHhMm: String,
        val scheduledMillis: Long,
        val status: SlotStatus,
        val dayKey: String,
    )

    data class Summary(
        val dueNow: List<DoseSlot>,
        val next: DoseSlot?,
        val missedToday: List<DoseSlot>,
        val upcomingToday: List<DoseSlot>,
        val takenToday: List<DoseSlot>,
    )

    data class MissedEntry(
        val box: String,
        val medicineName: String,
        val slotHhMm: String,
        val timestamp: String,
        val dayKey: String,
    )

    data class NextDosePreview(
        val medicineName: String,
        val box: String,
        val slotHhMm: String,
        val scheduledMillis: Long,
        val isDueNow: Boolean,
    )

    private val tsDayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /** Slot counts for today only if the schedule existed before that time today. */
    private fun slotCountsForToday(box: String, schedMs: Long): Boolean {
        val touch = AdminDemoData.medicineScheduleTouchMs(box)
        return touch <= schedMs
    }

    fun buildSummary(context: Context, nowMs: Long = System.currentTimeMillis()): Summary {
        val app = context.applicationContext
        if (AppRole.isUser(app)) {
            DoseAutoMissedMarker.run(app)
        }
        AdminDemoData.warmScheduleTouchCache(app)
        val dayKey = LocalAlertsController.localDayKeyToday()
        val slots = mutableListOf<DoseSlot>()

        for (m in AdminDemoData.medicines) {
            if (m.stock <= 0) continue
            if (m.effectiveScheduleTimes().isEmpty()) continue
            val single = !m.usesMultipleTimesPerDay()
            for (slot in m.effectiveScheduleTimes()) {
                val sched = DoseMarkWindow.todayMillisForHms(slot) ?: continue
                if (!slotCountsForToday(m.box, sched)) continue
                val slotNorm = MedicineSchedule.normalizeToHhMm(slot)
                val status = resolveStatus(app, m.box, dayKey, slotNorm, single, sched, nowMs)
                slots.add(
                    DoseSlot(
                        box = m.box,
                        medicineName = m.name,
                        slotHhMm = slotNorm,
                        scheduledMillis = sched,
                        status = status,
                        dayKey = dayKey,
                    ),
                )
            }
        }
        slots.sortBy { it.scheduledMillis }

        val dueNow = slots.filter { it.status == SlotStatus.DUE_NOW }
        val missedToday = slots.filter { it.status == SlotStatus.MISSED }
        val upcomingToday = slots.filter { it.status == SlotStatus.UPCOMING }
        val takenToday = slots.filter { it.status == SlotStatus.TAKEN }
        val next = dueNow.firstOrNull()
            ?: upcomingToday.firstOrNull()
            ?: findNextFutureSlot(app, nowMs)

        return Summary(
            dueNow = dueNow,
            next = next,
            missedToday = missedToday,
            upcomingToday = upcomingToday,
            takenToday = takenToday,
        )
    }

    fun nextDosePreview(context: Context, nowMs: Long = System.currentTimeMillis()): NextDosePreview? {
        val s = buildSummary(context, nowMs)
        val pick = s.dueNow.firstOrNull()
            ?: s.upcomingToday.firstOrNull()
            ?: s.next
            ?: findNextFutureSlot(context.applicationContext, nowMs)
            ?: return null
        return NextDosePreview(
            medicineName = pick.medicineName,
            box = pick.box,
            slotHhMm = pick.slotHhMm,
            scheduledMillis = pick.scheduledMillis,
            isDueNow = pick.status == SlotStatus.DUE_NOW,
        )
    }

    /** Scans up to 7 days ahead for the next schedulable dose (skips retroactive today slots). */
    private fun findNextFutureSlot(context: Context, nowMs: Long): DoseSlot? {
        AdminDemoData.warmScheduleTouchCache(context)
        val candidates = mutableListOf<DoseSlot>()
        for (dayOff in 0 until LocalAlertsController.DAY_WINDOW) {
            val dayKey = dayKeyFromOffset(dayOff)
            for (m in AdminDemoData.medicines) {
                if (m.stock <= 0) continue
                val single = !m.usesMultipleTimesPerDay()
                for (slot in m.effectiveScheduleTimes()) {
                    val nh = MedicineSchedule.toHhMmSs(MedicineSchedule.normalizeToHhMm(slot)) ?: continue
                    val slotNorm = MedicineSchedule.normalizeToHhMm(slot)
                    val sched = millisForLocalTimeOnDayOffset(nh, dayOff) ?: continue
                    if (dayOff == 0 && !slotCountsForToday(m.box, sched)) continue
                    if (sched <= nowMs) continue
                    if (DoseTrackingLocalStore.isTakenForSlot(context, m.box, dayKey, slotNorm)) continue
                    if (isLoggedTaken(context, m.box, dayKey, slotNorm, single)) continue
                    candidates.add(
                        DoseSlot(
                            box = m.box,
                            medicineName = m.name,
                            slotHhMm = slotNorm,
                            scheduledMillis = sched,
                            status = SlotStatus.UPCOMING,
                            dayKey = dayKey,
                        ),
                    )
                }
            }
        }
        return candidates.minByOrNull { it.scheduledMillis }
    }

    private fun dayKeyFromOffset(dayOffset: Int): String {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        return String.format(
            Locale.US,
            "%04d%02d%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun millisForLocalTimeOnDayOffset(hms: String, dayOffset: Int): Long? {
        val parts = hms.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.SECOND, parts.getOrNull(2)?.coerceIn(0, 59) ?: 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, parts[0].coerceIn(0, 23))
            set(Calendar.MINUTE, parts[1].coerceIn(0, 59))
        }
        return cal.timeInMillis
    }

    fun recentMissedDoses(context: Context, lookbackDays: Int = 14): List<MissedEntry> {
        val app = context.applicationContext
        if (AppRole.isUser(app)) {
            DoseAutoMissedMarker.run(app)
        }
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.add(Calendar.DAY_OF_YEAR, -lookbackDays.coerceAtLeast(1))
        val cutoff = tsDayFmt.format(cal.time)
        val out = mutableListOf<MissedEntry>()
        for (row in DoseTrackingLocalStore.readLog(app)) {
            val kind = row["kind"]?.toString().orEmpty()
            if (kind != "missed_auto" && kind != "missed") continue
            val ts = row["timestamp"]?.toString()?.trim().orEmpty()
            if (ts.length < 10 || ts.take(10) < cutoff) continue
            val slot = row["scheduled_slot"]?.toString()?.let { MedicineSchedule.normalizeToHhMm(it) }.orEmpty()
            out.add(
                MissedEntry(
                    box = row["box"]?.toString().orEmpty(),
                    medicineName = row["medicine"]?.toString().orEmpty(),
                    slotHhMm = slot.ifEmpty { "—" },
                    timestamp = ts,
                    dayKey = ts.take(10).replace("-", ""),
                ),
            )
        }
        return out.sortedByDescending { it.timestamp }
    }

    /** Missed doses for dashboard count + dialog: logged history plus today's schedule misses not yet in log. */
    fun dashboardMissedEntries(context: Context, summary: Summary?): List<MissedEntry> {
        val logged = recentMissedDoses(context)
        val seen = logged.map { missedEntryKey(it) }.toMutableSet()
        val out = logged.toMutableList()
        for (slot in summary?.missedToday.orEmpty()) {
            val entry = slot.toMissedEntry()
            val key = missedEntryKey(entry)
            if (key !in seen) {
                out.add(entry)
                seen.add(key)
            }
        }
        return out.sortedByDescending { it.timestamp }
    }

    private fun missedEntryKey(entry: MissedEntry): String =
        "${entry.box.trim().uppercase(Locale.US)}|${entry.dayKey}|${entry.slotHhMm}"

    private fun DoseSlot.toMissedEntry(): MissedEntry {
        val dayIso = when (dayKey.length) {
            8 -> "${dayKey.take(4)}-${dayKey.substring(4, 6)}-${dayKey.substring(6, 8)}"
            else -> dayKey
        }
        return MissedEntry(
            box = box,
            medicineName = medicineName,
            slotHhMm = slotHhMm,
            timestamp = "${dayIso}T${slotHhMm}:00",
            dayKey = dayKey,
        )
    }

    private fun resolveStatus(
        context: Context,
        box: String,
        dayKey: String,
        slotNorm: String,
        singleDailySlot: Boolean,
        schedMs: Long,
        nowMs: Long,
    ): SlotStatus {
        if (DoseTrackingLocalStore.isTakenForSlot(context, box, dayKey, slotNorm)) {
            return SlotStatus.TAKEN
        }
        if (isLoggedTaken(context, box, dayKey, slotNorm, singleDailySlot)) {
            return SlotStatus.TAKEN
        }
        if (isLoggedMissed(context, box, dayKey, slotNorm, singleDailySlot)) {
            return SlotStatus.MISSED
        }
        val windowStart = schedMs - DoseIntakeClassifier.MARK_WINDOW_BEFORE_MS
        val windowEnd = schedMs + DoseIntakeClassifier.MARK_WINDOW_AFTER_MS
        if (nowMs in windowStart..windowEnd) {
            return SlotStatus.DUE_NOW
        }
        val missEnd = schedMs + DoseIntakeClassifier.MISSED_AFTER_SCHEDULE_MS
        if (nowMs > missEnd) {
            return SlotStatus.MISSED
        }
        return SlotStatus.UPCOMING
    }

    private fun isLoggedTaken(
        context: Context,
        box: String,
        dayKey: String,
        slotNorm: String,
        singleDailySlot: Boolean,
    ): Boolean {
        val prefix = DoseTrackingLocalStore.dayPrefixFromDayKey(dayKey)
        for (row in DoseTrackingLocalStore.readLog(context)) {
            if (!row["box"].toString().equals(box, ignoreCase = true)) continue
            val ts = row["timestamp"]?.toString() ?: continue
            if (!ts.startsWith(prefix)) continue
            val k = row["kind"]?.toString().orEmpty()
            if (!DoseIntakeClassifier.isManualMarkDoseLogKind(k)) continue
            val rowSlot = row["scheduled_slot"]?.toString()?.let { MedicineSchedule.normalizeToHhMm(it) }.orEmpty()
            if (singleDailySlot && rowSlot.isEmpty()) return true
            if (rowSlot == slotNorm) return true
        }
        return false
    }

    private fun isLoggedMissed(
        context: Context,
        box: String,
        dayKey: String,
        slotNorm: String,
        singleDailySlot: Boolean,
    ): Boolean {
        val prefix = DoseTrackingLocalStore.dayPrefixFromDayKey(dayKey)
        for (row in DoseTrackingLocalStore.readLog(context)) {
            if (!row["box"].toString().equals(box, ignoreCase = true)) continue
            val ts = row["timestamp"]?.toString() ?: continue
            if (!ts.startsWith(prefix)) continue
            val k = row["kind"]?.toString().orEmpty()
            if (k != "missed_auto" && k != "missed") continue
            val rowSlot = row["scheduled_slot"]?.toString()?.let { MedicineSchedule.normalizeToHhMm(it) }.orEmpty()
            if (singleDailySlot && rowSlot.isEmpty()) return true
            if (rowSlot == slotNorm) return true
        }
        return false
    }
}