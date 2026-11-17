package com.lifetrack.app.domain

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.data.StreakProtection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Local-date calculations avoid DST drift. History uses the schedule effective on each date. */
object Analytics {
    data class Rate(val completed: Long, val opportunities: Long, val skipped: Long = 0) {
        val fraction: Float get() = if (opportunities == 0L) 0f else completed.toFloat() / opportunities
        val percent: Int get() = (fraction * 100).roundToInt()
    }

    fun dayNumber(habit: Habit, date: LocalDate): Long? =
        if (date < habit.startDate) null else ChronoUnit.DAYS.between(habit.startDate, date) + 1

    fun scheduledDays(habit: Habit, date: LocalDate): List<Int> =
        habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date }?.days
            ?: habit.scheduleHistory.firstOrNull()?.days ?: habit.scheduledDays

    fun eligible(habit: Habit, date: LocalDate, pauses: List<HabitPause> = emptyList()): Boolean =
        date >= habit.startDate && (habit.archivedOn == null || date <= habit.archivedOn) &&
            scheduleAllows(habit, date) &&
            pauses.none { it.habitId == habit.id && date >= it.startDate && (it.endDate == null || date < it.endDate) }

    /** Schedule-mode dispatch: WEEKDAYS keeps selected-weekday rules, EVERY_N_DAYS qualifies only
     *  on the recurrence grid, WEEKLY_QUOTA allows a completion on any day of the week. */
    private fun scheduleAllows(habit: Habit, date: LocalDate): Boolean = when (Scheduling.mode(habit, date)) {
        ScheduleMode.WEEKDAYS -> date.dayOfWeek.value in scheduledDays(habit, date)
        ScheduleMode.EVERY_N_DAYS -> Scheduling.isScheduledOn(habit, date)
        ScheduleMode.WEEKLY_QUOTA -> true
        else -> false
    }

    /** Habits that were due on a date but carry no check-in record at all. */
    fun unmarked(habits: List<Habit>, logs: List<HabitLog>, date: LocalDate, pauses: List<HabitPause> = emptyList()): List<Habit> =
        habits.filter { habit -> !habit.isArchived && eligible(habit, date, pauses) && logs.none { log -> log.habitId == habit.id && log.date == date } }

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Counts whole weeks arithmetically; long histories do not allocate every calendar date.
     *  Mode dispatch is per segment (the governing revision at each boundary): WEEKDAYS uses
     *  weekday arithmetic, EVERY_N_DAYS counts grid dates via epoch-day math, WEEKLY_QUOTA
     *  contributes no daily dues (its progress is period-based and excluded from daily
     *  denominators by the callers). */
    fun dueCount(habit: Habit, from: LocalDate, through: LocalDate, pauses: List<HabitPause> = emptyList()): Long {
        val start = maxOf(from, habit.startDate)
        val end = minOf(through, habit.archivedOn ?: through)
        if (start > end) return 0
        val endExclusive = end.plusDays(1)
        val ownPauses = pauses.filter { it.habitId == habit.id }
        val boundaries = (listOf(start, endExclusive) + habit.scheduleHistory.map { it.effectiveFrom } +
            ownPauses.flatMap { listOfNotNull(it.startDate, it.endDate) })
            .filter { it >= start && it <= endExclusive }.distinct().sorted()
        var total = 0L
        boundaries.zipWithNext().forEach { (a, b) ->
            if (ownPauses.none { a >= it.startDate && (it.endDate == null || a < it.endDate) }) {
                when (Scheduling.mode(habit, a)) {
                    ScheduleMode.EVERY_N_DAYS -> {
                        val interval = Scheduling.intervalDays(habit, a) ?: return@forEach
                        val anchor = Scheduling.intervalAnchor(habit, a) ?: return@forEach
                        val segmentStart = maxOf(a, anchor)
                        val first = anchor.plusDays(
                            ((segmentStart.toEpochDay() - anchor.toEpochDay()) + interval - 1) / interval * interval
                        )
                        if (first.isBefore(b)) {
                            total += (b.toEpochDay() - first.toEpochDay()) / interval + 1
                        }
                    }
                    ScheduleMode.WEEKLY_QUOTA -> { } // period-based: no daily dues
                    else -> {
                        val weekdays = scheduledDays(habit, a)
                        val length = ChronoUnit.DAYS.between(a, b)
                        total += length / 7 * weekdays.size
                        repeat((length % 7).toInt()) { offset -> if (((a.dayOfWeek.value - 1 + offset) % 7 + 1) in weekdays) total++ }
                    }
                }
            }
        }
        return total
    }

    /** Skips, unscheduled weekdays, archived intervals and bridged partial days continue the chain without adding days.
     *  WEEKLY_QUOTA habits have no day-based streak: their consistency is period-based (see Scheduling). */
    fun currentStreak(habit: Habit, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause> = emptyList(), protections: List<StreakProtection> = emptyList()): Int {
        if (habit.isArchived || today < habit.startDate) return 0
        if (Scheduling.mode(habit, today) == ScheduleMode.WEEKLY_QUOTA) return 0
        val own = logs.filter { it.habitId == habit.id && it.date <= today && eligible(habit, it.date, pauses) }
        val todayLog = own.firstOrNull { it.date == today }
        var cursor = if (todayLog == null || todayLog.status == LogStatus.IN_PROGRESS) today.minusDays(1) else today
        var count = 0
        for (log in own.filter { it.date <= cursor }.sortedByDescending { it.date }) {
            if (dueCount(habit, log.date.plusDays(1), cursor, pauses) > 0) break
            when (log.status) {
                LogStatus.COMPLETED -> count++
                LogStatus.SKIPPED -> Unit
                // A bridged partial day continues the chain without counting toward it.
                LogStatus.IN_PROGRESS -> if (StreakPolicy.bridges(habit, log, protections) != true) break
                else -> break
            }
            cursor = log.date.minusDays(1)
        }
        return count
    }

    fun bestStreak(habit: Habit, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause> = emptyList(), protections: List<StreakProtection> = emptyList()): Int {
        // WEEKLY_QUOTA habits earn period-based consistency, never day-based streaks.
        if (Scheduling.mode(habit, today) == ScheduleMode.WEEKLY_QUOTA) return 0
        var current = 0
        var best = 0
        var previous: LocalDate? = null
        logs.filter { it.habitId == habit.id && it.date <= today && eligible(habit, it.date, pauses) }.sortedBy { it.date }.forEach { log ->
            if (previous?.let { dueCount(habit, it.plusDays(1), log.date.minusDays(1), pauses) > 0 } == true) current = 0
            when (log.status) {
                LogStatus.COMPLETED -> { current++; best = maxOf(best, current) }
                LogStatus.SKIPPED -> Unit
                // Same bridging as currentStreak: a bridged partial day keeps the chain alive.
                LogStatus.IN_PROGRESS -> if (StreakPolicy.bridges(habit, log, protections) != true) current = 0
                else -> current = 0
            }
            previous = log.date
        }
        return best
    }

    fun rate(habits: List<Habit>, logs: List<HabitLog>, from: LocalDate, through: LocalDate, pauses: List<HabitPause> = emptyList()): Rate {
        if (from > through) return Rate(0, 0)
        var opportunities = 0L
        var completed = 0L
        var skipped = 0L
        val grouped = logs.groupBy { it.habitId }
        val groupedPauses = pauses.groupBy { it.habitId }
        habits.forEach { habit ->
            // WEEKLY_QUOTA habits are excluded from daily completion-rate math entirely: their
            // consistency is period-based, and a daily ratio would be misleading or undefined.
            if (Scheduling.mode(habit, through) == ScheduleMode.WEEKLY_QUOTA) return@forEach
            val ownPauses = groupedPauses[habit.id].orEmpty()
            opportunities += dueCount(habit, from, through, ownPauses)
            grouped[habit.id].orEmpty().filter { it.date in from..through && eligible(habit, it.date, ownPauses) }.forEach { log ->
                when (log.status) {
                    LogStatus.COMPLETED -> completed++
                    LogStatus.SKIPPED -> { skipped++; opportunities-- }
                    else -> Unit
                }
            }
        }
        return Rate(completed, opportunities, skipped)
    }

    /** Density remains completions / all scheduled habits; rate denominators exclude skips. */
    fun density(habits: List<Habit>, logs: List<HabitLog>, date: LocalDate, pauses: List<HabitPause> = emptyList()): Rate {
        val eligibleIds = habits.filter { eligible(it, date, pauses) }.map { it.id }.toSet()
        val completed = logs.count { it.date == date && it.habitId in eligibleIds && it.status == LogStatus.COMPLETED }
        return Rate(completed.toLong(), eligibleIds.size.toLong())
    }

    fun weeklyVolumes(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause> = emptyList()): List<Pair<LocalDate, Int>> {
        val byId = habits.associateBy { it.id }
        val monday = weekStart(today)
        return (7 downTo 0).map { ago ->
            val from = monday.minusWeeks(ago.toLong())
            val end = minOf(from.plusDays(6), today)
            from to logs.count { log -> log.status == LogStatus.COMPLETED && log.date in from..end && byId[log.habitId]?.let { eligible(it, log.date, pauses) } == true }
        }
    }
}
