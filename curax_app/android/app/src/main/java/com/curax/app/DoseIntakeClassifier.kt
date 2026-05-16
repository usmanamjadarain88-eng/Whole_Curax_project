package com.curax.app

/**
 * Standalone "Mark dose taken": only within ±30 minutes of each scheduled time from
 * [AdminDemoData.Medicine.effectiveScheduleTimes]. Between windows, [SlotPhase.BETWEEN_SLOTS].
 *
 * Auto-missed uses [MISSED_AFTER_SCHEDULE_MS] — see [DoseAutoMissedMarker].
 */
object DoseIntakeClassifier {

    /** Allowed mark window: 30 minutes before scheduled dose time. */
    const val MARK_WINDOW_BEFORE_MS: Long = 30L * 60_000L

    /** Allowed mark window: 30 minutes after scheduled dose time. */
    const val MARK_WINDOW_AFTER_MS: Long = 30L * 60_000L

    /** If still not marked this long after the scheduled instant, log as missed ([DoseAutoMissedMarker]). */
    const val MISSED_AFTER_SCHEDULE_MS: Long = 30L * 60_000L

    enum class SlotPhase {
        NO_SCHEDULE,
        TOO_EARLY,
        ON_TIME,
        LATE,
        /** After last window of the day for this medicine. */
        TOO_LATE,
        /** Between two scheduled windows (same calendar day). */
        BETWEEN_SLOTS,
    }

    data class MarkContext(
        val phase: SlotPhase,
        /** HH:mm for dose log + slot suppress when [phase] is ON_TIME or LATE. */
        val activeSlotHhMm: String?,
        /** Next schedule HH:mm when [phase] is TOO_EARLY or BETWEEN_SLOTS. */
        val nextSlotHhMm: String? = null,
    )

    private data class Win(val start: Long, val end: Long, val sched: Long, val label: String)

    fun markContext(m: AdminDemoData.Medicine, nowMs: Long = System.currentTimeMillis()): MarkContext {
        val slots = m.effectiveScheduleTimes()
        if (slots.isEmpty()) return MarkContext(SlotPhase.NO_SCHEDULE, null, null)

        val wins = mutableListOf<Win>()
        for (label in slots) {
            val sched = DoseMarkWindow.todayMillisForHms(label) ?: continue
            val norm = MedicineSchedule.normalizeToHhMm(label)
            wins.add(
                Win(
                    start = sched - MARK_WINDOW_BEFORE_MS,
                    end = sched + MARK_WINDOW_AFTER_MS,
                    sched = sched,
                    label = norm,
                ),
            )
        }
        if (wins.isEmpty()) return MarkContext(SlotPhase.NO_SCHEDULE, null, null)
        wins.sortBy { it.sched }

        for (w in wins) {
            if (nowMs in w.start..w.end) {
                val phase = if (nowMs <= w.sched) SlotPhase.ON_TIME else SlotPhase.LATE
                return MarkContext(phase, w.label, null)
            }
        }

        if (nowMs < wins.first().start) {
            return MarkContext(SlotPhase.TOO_EARLY, null, wins.first().label)
        }

        for (i in 0 until wins.lastIndex) {
            if (nowMs > wins[i].end && nowMs < wins[i + 1].start) {
                return MarkContext(SlotPhase.BETWEEN_SLOTS, null, wins[i + 1].label)
            }
        }

        if (nowMs > wins.last().end) {
            return MarkContext(SlotPhase.TOO_LATE, null, null)
        }

        return MarkContext(SlotPhase.TOO_LATE, null, null)
    }

    fun slotPhase(m: AdminDemoData.Medicine, nowMs: Long = System.currentTimeMillis()): SlotPhase =
        markContext(m, nowMs).phase

    fun kindForSuccessfulMark(phase: SlotPhase): String = when (phase) {
        SlotPhase.ON_TIME -> "taken_on_time"
        SlotPhase.LATE -> "taken_late"
        else -> "taken"
    }

    /** Rows the in-app dose history table should list: only successful taps on "Mark dose taken". */
    fun isManualMarkDoseLogKind(kind: String?): Boolean {
        val k = kind?.trim().orEmpty()
        return k == "taken_on_time" || k == "taken_late" || k == "taken"
    }
}
