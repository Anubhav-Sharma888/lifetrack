package com.lifetrack.app.domain

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.StreakProtection
import com.lifetrack.app.data.goalOn
// The stored enum shares this object's name, so it is imported under an alias.
import com.lifetrack.app.data.StreakPolicy as StoredStreakPolicy

/** Smart Streaks rules, pure like Analytics: explicit rows carry decisions, the habit policy fills only unmarked days. */
object StreakPolicy {

    /** Unknown stored values fall back to STRICT. */
    fun fromStored(value: String): StoredStreakPolicy = StoredStreakPolicy.fromStored(value)

    /** True when a habit carries any Smart Streaks configuration (used by backup version gating). */
    fun isConfigured(habit: Habit): Boolean =
        habit.streakPolicy != StoredStreakPolicy.STRICT.name || habit.partialCreditPercent != 75

    /** Tri-state for a check-in log: true bridges the chain WITHOUT counting as completed, false
     *  breaks it, null means the rule does not apply (not an IN_PROGRESS log, or zero progress).
     *  An explicit [StreakProtection] row always overrides the habit policy; AUTOMATIC bridges when
     *  the partial progress reaches the habit's current threshold, compared without floats so the
     *  boundary stays inclusive (6 of 8 at 75% bridges). Zero-progress logs never bridge, even
     *  with a stale row; FAILED and COMPLETED days are the status's own business. */
    fun bridges(habit: Habit, log: HabitLog, protections: List<StreakProtection>): Boolean? {
        if (log.status != LogStatus.IN_PROGRESS) return null
        if (log.progress <= 0) return null
        protections.firstOrNull { it.habitId == habit.id && it.date == log.date }?.let { return it.protectChain }
        if (fromStored(habit.streakPolicy) != StoredStreakPolicy.AUTOMATIC) return null
        return log.progress * 100 >= habit.partialCreditPercent * habit.goalOn(log.date).targetCount
    }
}
