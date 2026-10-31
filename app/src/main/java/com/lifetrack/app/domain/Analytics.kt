package com.lifetrack.app.domain

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
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
            date.dayOfWeek.value in scheduledDays(habit, date) &&
            pauses.none { it.habitId == habit.id && date >= it.startDate && (it.endDate == null || date < it.endDate) }

    /** Habits that were due on a date but carry no check-in record at all. */
    fun unmarked(habits: List<Habit>, logs: List<HabitLog>, date: LocalDate, pauses: List<HabitPause> = emptyList()): List<Habit> =
        habits.filter { habit -> !habit.isArchived && eligible(habit, date, pauses) && logs.none { log -> log.habitId == habit.id && log.date == date } }

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Counts whole weeks arithmetically; long histories do not allocate every calendar date. */
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
                val weekdays = scheduledDays(habit, a)
                val length = ChronoUnit.DAYS.between(a, b)
                total += length / 7 * weekdays.size
                repeat((length % 7).toInt()) { offset -> if (((a.dayOfWeek.value - 1 + offset) % 7 + 1) in weekdays) total++ }
            }
        }
        return total
    }

    /** Skips, unscheduled weekdays and archived intervals bridge the chain without adding days. */
    fun currentStreak(habit: Habit, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause> = emptyList()): Int {
        if (habit.isArchived || today < habit.startDate) return 0
        val own = logs.filter { it.habitId == habit.id && it.date <= today && eligible(habit, it.date, pauses) }
        val todayLog = own.firstOrNull { it.date == today }
        var cursor = if (todayLog == null || todayLog.status == LogStatus.IN_PROGRESS) today.minusDays(1) else today
        var count = 0
        for (log in own.filter { it.date <= cursor }.sortedByDescending { it.date }) {
            if (dueCount(habit, log.date.plusDays(1), cursor, pauses) > 0) break
            when (log.status) {
                LogStatus.COMPLETED -> count++
                LogStatus.SKIPPED -> Unit
                else -> break
            }
            cursor = log.date.minusDays(1)
        }
        return count
    }

    fun bestStreak(habit: Habit, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause> = emptyList()): Int {
        var current = 0
        var best = 0
        var previous: LocalDate? = null
        logs.filter { it.habitId == habit.id && it.date <= today && eligible(habit, it.date, pauses) }.sortedBy { it.date }.forEach { log ->
            if (previous?.let { dueCount(habit, it.plusDays(1), log.date.minusDays(1), pauses) > 0 } == true) current = 0
            when (log.status) {
                LogStatus.COMPLETED -> { current++; best = maxOf(best, current) }
                LogStatus.SKIPPED -> Unit
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
