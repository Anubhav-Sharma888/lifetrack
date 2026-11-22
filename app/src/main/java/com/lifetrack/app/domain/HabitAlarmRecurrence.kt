package com.lifetrack.app.domain

import com.lifetrack.app.data.HabitAlarm
import com.lifetrack.app.data.HabitAlarmRecurrence
import java.time.LocalDate

/**
 * Pure alarm-recurrence engine (Prompt 6, Part B): for one alarm definition, computes the next
 * eligible occurrence strictly after a given instant, in the device-local timezone.
 *
 * Ringing alarms follow the device clock by convention — the user says "ring at 06:00" and
 * expects it to mean the device's 06:00, like a bedside alarm clock. Recurring alarms therefore
 * advance one wall-clock day at a time and never depend on a persisted instant.
 *
 * Recurrence semantics are the alarm's OWN, independent of the habit's tracking schedule:
 * - DAILY: every day.
 * - WEEKDAYS: the alarm's own weekday selection (ISO 1=Mon..7=Sun), independent of the habit's
 *   tracking-day grid — a medicine alarm can ring on days the habit isn't tracked.
 * - ONCE: exactly the stored date (or nothing after it passes).
 *
 * A WEEKLY_QUOTA habit is never silently interpreted as a daily alarm rule: quota habits may
 * carry alarm definitions, but their occurrences follow the alarm's own recurrence and never
 * change the quota or imply a due-day completion.
 */
object HabitAlarmRecurrenceEngine {

    /** The next occurrence instant in epoch millis strictly after [afterEpochMillis], or null
     *  when the definition has no further occurrence (ONCE in the past, disabled habit days
     *  exhausted within the horizon). [horizonDays] bounds the search. */
    fun nextOccurrenceMillis(
        alarm: HabitAlarm,
        afterEpochMillis: Long,
        horizonDays: Int = 366,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Long? {
        if (!alarm.enabled) return null
        val recurrence = HabitAlarmRecurrence.fromStored(alarm.recurrence)
        val after = java.time.Instant.ofEpochMilli(afterEpochMillis).atZone(zone)
        var candidateDay = after.toLocalDate()
        val lastDay = candidateDay.plusDays(horizonDays.toLong())
        val alarmTime = java.time.LocalTime.of(alarm.timeMinutes / 60, alarm.timeMinutes % 60)
        while (!candidateDay.isAfter(lastDay)) {
            if (firesOn(alarm, recurrence, candidateDay)) {
                val candidate = candidateDay.atTime(alarmTime).atZone(zone).toInstant().toEpochMilli()
                if (candidate > afterEpochMillis) return candidate
            }
            candidateDay = candidateDay.plusDays(1)
        }
        return null
    }

    /** Does this alarm definition fire on the given local day? */
    fun firesOn(alarm: HabitAlarm, recurrence: HabitAlarmRecurrence, day: LocalDate): Boolean {
        if (!alarm.enabled) return false
        return when (recurrence) {
            HabitAlarmRecurrence.DAILY -> true
            HabitAlarmRecurrence.WEEKDAYS -> day.dayOfWeek.value in alarm.weekdays
            HabitAlarmRecurrence.ONCE -> alarm.onceDate == day
        }
    }

    /** Stable, collision-safe occurrence identity: habit uuid + alarm uuid + the intended local
     *  wall-clock time. Restored databases may reuse local row ids — identity never depends on
     *  them, so a restored habit cannot inherit another habit's old alarm. */
    fun occurrenceKey(alarm: HabitAlarm, occurrenceMillis: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val local = java.time.Instant.ofEpochMilli(occurrenceMillis).atZone(zone)
        return "alarm-${alarm.uuid}-${local.toLocalDate()}-${local.toLocalTime()}"
    }
}
