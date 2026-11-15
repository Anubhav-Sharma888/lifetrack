package com.lifetrack.app.domain

import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.ActivityMode
import com.lifetrack.app.data.WorkoutActivity
import java.time.LocalDate

/** Directional activity rules, pure like Analytics: entries carry recorded values, never interpretations. */
object ProgressTracking {

    fun mode(activity: WorkoutActivity): ActivityMode = ActivityMode.fromStored(activity.trackingMode)

    /** Entries are unique per activity and date, so ordering is unambiguous. */
    private fun ordered(entries: List<ActivityEntry>): List<ActivityEntry> = entries.sortedBy { it.date }

    /** INCREASING/DECREASING read the latest dated entry (a position as of that day); PERSONAL_BEST the maximum ever. */
    fun current(activity: WorkoutActivity, entries: List<ActivityEntry>): Double? = when (mode(activity)) {
        ActivityMode.PERSONAL_BEST -> entries.maxOfOrNull { it.quantity }
        else -> ordered(entries).lastOrNull()?.quantity
    }

    /** Positional baseline: the explicit starting value, else the earliest recorded entry. */
    fun baseline(activity: WorkoutActivity, entries: List<ActivityEntry>): Double? =
        activity.startingValue ?: ordered(entries).firstOrNull()?.quantity

    /** Suggested value when logging on [date]: the same-day entry when editing, else a continuation.
     *  LOGGED keeps its historic prefill: the same-day entry, else the newest entry anywhere, else the target. */
    fun continuation(activity: WorkoutActivity, entries: List<ActivityEntry>, date: LocalDate): Double? {
        val mode = mode(activity)
        if (mode == ActivityMode.LOGGED) {
            return entries.firstOrNull { it.date == date }?.quantity
                ?: entries.maxByOrNull { it.date }?.quantity
                ?: activity.target
        }
        ordered(entries).firstOrNull { it.date == date }?.let { return it.quantity }
        val earlier = ordered(entries).filter { it.date < date }
        return when (mode) {
            // Positions and records move upward: resume one step past the last position, or past the best attempt.
            ActivityMode.INCREASING -> earlier.lastOrNull()?.let { it.quantity + 1.0 } ?: activity.startingValue
            ActivityMode.PERSONAL_BEST -> earlier.maxOfOrNull { it.quantity }?.plus(1.0) ?: activity.startingValue
            // Measurements continue from the last reading without inventing direction.
            ActivityMode.DECREASING -> earlier.lastOrNull()?.quantity ?: activity.startingValue
            ActivityMode.LOGGED -> null
        }
    }

    /** Fraction toward the target, unclamped; null where it would be undefined or misleading.
     *  A target at or past the baseline in the wrong direction has no meaningful fraction. */
    fun progressFraction(activity: WorkoutActivity, entries: List<ActivityEntry>): Float? {
        val mode = mode(activity)
        val target = activity.target ?: return null
        val current = current(activity, entries) ?: return null
        return when (mode) {
            ActivityMode.INCREASING -> baseline(activity, entries)?.takeIf { target > it }
                ?.let { base -> ((current - base) / (target - base)).toFloat() }
            ActivityMode.DECREASING -> baseline(activity, entries)?.takeIf { it > target }
                ?.let { base -> ((base - current) / (base - target)).toFloat() }
            ActivityMode.PERSONAL_BEST -> (current / target).toFloat()
            ActivityMode.LOGGED -> null
        }
    }
}
