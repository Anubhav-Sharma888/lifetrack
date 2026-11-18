package com.lifetrack.app.domain

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import java.time.LocalDate

/**
 * Deterministic scheduling rules for the three schedule modes. Everything derives from canonical
 * data (habit fields + schedule history via the effective-revision lookups); nothing is stored or
 * fabricated.
 *
 * - WEEKDAYS: the original selected-weekday rules, unchanged for every existing habit.
 * - EVERY_N_DAYS: date-based recurrence from an explicit anchor (LocalDate arithmetic, so month
 *   lengths, leap years and year boundaries are handled by java.time, never by elapsed millis).
 * - WEEKLY_QUOTA: any day of the ISO week may be completed; there are no fixed due dates, so
 *   progress is period-based (distinct completed days per Monday–Sunday week) and day-based
 *   streak semantics deliberately do not apply.
 *
 * Pause handling stays with the caller (`Analytics.eligible`), exactly as before.
 */
object Scheduling {

    /** Bounds for every-N-day intervals. N = 1 is daily, which WEEKDAYS already expresses. */
    const val MIN_INTERVAL_DAYS = 2
    const val MAX_INTERVAL_DAYS = 365

    /** The schedule mode in force on [date]. Null-mode revisions are weekday periods: weekday
     *  saves and pre-schema-10 revisions never carry a mode, and dates before the first revision
     *  predate any schedule change, which was a weekday period too. Only revision-free habits
     *  report their stored [Habit.scheduleMode]. This keeps weekday-era history intact across
     *  weekday-to-flexible switches instead of leaking the current mode into the past. */
    fun mode(habit: Habit, date: LocalDate): ScheduleMode {
        val stored = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date }?.mode
            ?: if (habit.scheduleHistory.isEmpty()) habit.scheduleMode else ScheduleMode.WEEKDAYS.name
        return ScheduleMode.fromStored(stored)
    }

    /** The interval length in force on [date], or null outside EVERY_N_DAYS mode. */
    fun intervalDays(habit: Habit, date: LocalDate): Int? {
        if (mode(habit, date) != ScheduleMode.EVERY_N_DAYS) return null
        val revision = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date && it.intervalDays != null }
            ?: habit.scheduleHistory.lastOrNull { it.intervalDays != null }
            ?: return habit.intervalDays
        return revision.intervalDays ?: habit.intervalDays
    }

    /** The recurrence anchor in force on [date]: the effectiveFrom of the interval revision. */
    fun intervalAnchor(habit: Habit, date: LocalDate): LocalDate? {
        if (mode(habit, date) != ScheduleMode.EVERY_N_DAYS) return null
        val revision = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date && it.intervalDays != null }
            ?: habit.scheduleHistory.lastOrNull { it.intervalDays != null }
            ?: return habit.intervalAnchor
        return revision.effectiveFrom
    }

    /** The weekly quota in force on [date], or null outside WEEKLY_QUOTA mode. */
    fun weeklyTarget(habit: Habit, date: LocalDate): Int? {
        if (mode(habit, date) != ScheduleMode.WEEKLY_QUOTA) return null
        val revision = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date && it.weeklyTarget != null }
            ?: habit.scheduleHistory.lastOrNull { it.weeklyTarget != null }
            ?: return habit.weeklyTarget
        return revision.weeklyTarget ?: habit.weeklyTarget
    }

    /** True when a habit carries any flexible-scheduling configuration (backup v9 gating). */
    fun isConfigured(habit: Habit): Boolean =
        mode(habit, LocalDate.now()) != ScheduleMode.WEEKDAYS ||
            habit.intervalDays != null || habit.weeklyTarget != null ||
            habit.scheduleHistory.any { it.mode != null && it.mode != ScheduleMode.WEEKDAYS.name } ||
            habit.scheduleHistory.any { it.intervalDays != null || it.weeklyTarget != null }

    /** The weekdays scheduled on [date] under WEEKDAYS mode (historical revisions honored). */
    private fun weekdayDaysOn(habit: Habit, date: LocalDate): List<Int> {
        val revision = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date }?.days
            ?: habit.scheduleHistory.firstOrNull()?.days
            ?: habit.scheduledDays
        return revision
    }

    /** Whether a habit may be completed on [date]. For WEEKLY_QUOTA habits every day is an
     *  optional completion day (the quota counts distinct completed days per week); for
     *  EVERY_N_DAYS only dates on the recurrence grid qualify; WEEKDAYS is unchanged.
     *  Pause handling stays with the caller (`Analytics.eligible`), exactly as before. */
    fun isScheduledOn(habit: Habit, date: LocalDate): Boolean = when (mode(habit, date)) {
        ScheduleMode.WEEKDAYS -> date.dayOfWeek.value in weekdayDaysOn(habit, date)
        ScheduleMode.EVERY_N_DAYS -> onIntervalGrid(habit, date)
        ScheduleMode.WEEKLY_QUOTA -> true
    }

    /** True when [date] sits exactly on the every-N-days recurrence grid from the anchor. */
    private fun onIntervalGrid(habit: Habit, date: LocalDate): Boolean {
        val interval = intervalDays(habit, date) ?: return false
        val anchor = intervalAnchor(habit, date) ?: return false
        if (date.isBefore(anchor)) return false
        val elapsed = date.toEpochDay() - anchor.toEpochDay()
        return elapsed % interval == 0L
    }

    /** The next date on the recurrence grid strictly after [after], or null when not applicable. */
    fun nextScheduledOn(habit: Habit, after: LocalDate): LocalDate? {
        return when (mode(habit, after)) {
            ScheduleMode.WEEKDAYS -> {
                val days = weekdayDaysOn(habit, after)
                (1L..7L).map { after.plusDays(it) }
                    .firstOrNull { candidate -> candidate.dayOfWeek.value in days }
            }
            ScheduleMode.EVERY_N_DAYS -> {
                val interval = intervalDays(habit, after) ?: return null
                val anchor = intervalAnchor(habit, after) ?: return null
                if (after.isBefore(anchor)) return null
                val steps = (after.toEpochDay() - anchor.toEpochDay()) / interval + 1
                anchor.plusDays(steps * interval)
            }
            ScheduleMode.WEEKLY_QUOTA -> null // any day may count; there is no next fixed date
            else -> null
        }
    }

    /** Weekly-quota accounting for one ISO week (Monday–Sunday containing [anyDayOfWeek]). */
    data class WeeklyQuotaStatus(
        val completedDays: Int,
        val requiredDays: Int,
        val eligibleDays: Int,
        val isCurrentWeek: Boolean,
        val state: WeeklyQuotaState
    )

    enum class WeeklyQuotaState { ACHIEVED, ONGOING, MISSED, REST }

    /**
     * Period-based quota evaluation for the ISO week containing [anyDayOfWeek]:
     * - achieved: distinct completed days ≥ required
     * - ongoing: the current week with fewer completions so far — never prematurely failed
     * - missed: an elapsed week with at least one eligible (non-paused) day and too few completions
     * - rest: every eligible day of the week was inside a pause (nothing was required)
     */
    fun weeklyQuotaStatus(
        habit: Habit,
        logs: List<HabitLog>,
        pauses: List<HabitPause>,
        anyDayOfWeek: LocalDate,
        today: LocalDate
    ): WeeklyQuotaStatus {
        val weekStart = Analytics.weekStart(anyDayOfWeek)
        val weekEnd = weekStart.plusDays(6)
        // Completions on paused days are rest, not progress: they never count toward the quota.
        val completed = logs.count {
            it.habitId == habit.id && it.status == LogStatus.COMPLETED &&
                it.date >= weekStart && it.date <= weekEnd &&
                pauses.none { p -> p.habitId == habit.id && it.date >= p.startDate && (p.endDate == null || it.date < p.endDate) }
        }
        val required = weeklyTarget(habit, anyDayOfWeek) ?: 0
        val eligibleDays = (0..6).count { offset ->
            val day = weekStart.plusDays(offset.toLong())
            day >= habit.startDate && (habit.archivedOn == null || day <= habit.archivedOn) &&
                !day.isAfter(today) &&
                pauses.none { it.habitId == habit.id && day >= it.startDate && (it.endDate == null || day < it.endDate) }
        }
        val isCurrentWeek = weekStart == Analytics.weekStart(today)
        val state = when {
            completed >= required && required > 0 -> WeeklyQuotaState.ACHIEVED
            eligibleDays == 0 -> WeeklyQuotaState.REST
            isCurrentWeek -> WeeklyQuotaState.ONGOING
            else -> WeeklyQuotaState.MISSED
        }
        return WeeklyQuotaStatus(completed, required, eligibleDays, isCurrentWeek, state)
    }

    /** Consecutive qualifying ISO weeks are deliberately deferred to Prompt 8: the weekly
     *  status above is the required Phase-1 metric, and a week-walking streak needs product
     *  decisions (rest-week bridging, partial first weeks) that belong with its UI. */
}
