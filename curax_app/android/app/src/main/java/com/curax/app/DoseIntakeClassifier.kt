package com.curax.app

/**
 * Standalone dose outcome: on-time vs late uses scheduled time + [GRACE_AFTER_SCHEDULE_MS].
 * Pre-scheduled "too early" is before today's scheduled instant (not the pre-alert window).
 */
object DoseIntakeClassifier {

    /** One hour after scheduled time — marks in this window count as on-time. */
    const val GRACE_AFTER_SCHEDULE_MS: Long = 3_600_000L

    enum class SlotPhase {
        NO_SCHEDULE,
        TOO_EARLY,
        ON_TIME,
        LATE,
    }

    fun slotPhase(m: AdminDemoData.Medicine, nowMs: Long = System.currentTimeMillis()): SlotPhase {
        val sched = DoseMarkWindow.todayScheduledBaseMillis(m) ?: return SlotPhase.NO_SCHEDULE
        return when {
            nowMs < sched -> SlotPhase.TOO_EARLY
            nowMs <= sched + GRACE_AFTER_SCHEDULE_MS -> SlotPhase.ON_TIME
            else -> SlotPhase.LATE
        }
    }

    fun kindForSuccessfulMark(phase: SlotPhase): String = when (phase) {
        SlotPhase.ON_TIME -> "taken_on_time"
        SlotPhase.LATE -> "taken_late"
        else -> "taken"
    }
}
