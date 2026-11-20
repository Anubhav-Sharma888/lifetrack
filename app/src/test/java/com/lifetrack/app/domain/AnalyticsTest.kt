package com.lifetrack.app.domain

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AnalyticsTest {
    private val start = LocalDate.of(2026, 3, 7)
    private fun habit(id: Long = 1, date: LocalDate = start) = Habit(
        id = id, name = "Read", icon = "📚", colorHex = "#477A60", startDate = date, sortOrder = 0
    )
    private fun log(day: Long, status: LogStatus = LogStatus.COMPLETED, habitId: Long = 1) = HabitLog(
        id = day + 1, habitId = habitId, date = start.plusDays(day), status = status
    )

    @Test fun dayOneIsStartDateAndFutureHasNoDayNumber() {
        assertEquals(1L, Analytics.dayNumber(habit(), start))
        assertEquals(3L, Analytics.dayNumber(habit(), start.plusDays(2)))
        assertNull(Analytics.dayNumber(habit(), start.minusDays(1)))
    }

    @Test fun skippedDayBridgesStreakWithoutIncrementingIt() {
        val logs = listOf(log(0), log(1, LogStatus.SKIPPED), log(2))
        assertEquals(2, Analytics.currentStreak(habit(), logs, start.plusDays(2)))
        assertEquals(2, Analytics.bestStreak(habit(), logs, start.plusDays(2)))
        assertEquals(1f, Analytics.rate(listOf(habit()), logs, start, start.plusDays(2)).fraction, .0001f)
    }

    @Test fun unfinishedTodayDoesNotBreakButYesterdayDoes() {
        assertEquals(1, Analytics.currentStreak(habit(), listOf(log(0)), start.plusDays(1)))
        assertEquals(0, Analytics.currentStreak(habit(), listOf(log(0)), start.plusDays(2)))
        assertEquals(0, Analytics.currentStreak(habit(), listOf(log(0), log(1, LogStatus.FAILED)), start.plusDays(1)))
    }

    @Test fun futureHabitsAndArchivedDaysDoNotInflateDenominator() {
        val archived = habit().copy(isArchived = true, archivedOn = start.plusDays(1))
        val future = habit(2, start.plusDays(20))
        val rate = Analytics.rate(listOf(archived, future), listOf(log(0)), start, start.plusDays(10))
        assertEquals(2L, rate.opportunities)
        assertEquals(.5f, rate.fraction, .0001f)
        assertTrue(Analytics.eligible(archived, start.plusDays(1)))
        assertFalse(Analytics.eligible(archived, start.plusDays(2)))
        assertEquals(0, Analytics.currentStreak(archived, listOf(log(0)), start.plusDays(1)))
    }

    @Test fun archivingFutureHabitHasNoScheduledDays() {
        val planned = habit(date = start.plusDays(10)).copy(isArchived = true, archivedOn = start)
        assertEquals(0L, Analytics.rate(listOf(planned), emptyList(), start, start.plusDays(20)).opportunities)
        assertEquals(0, Analytics.bestStreak(planned, emptyList(), start.plusDays(20)))
    }

    @Test fun densityUsesAllEligibleHabitsAndIsNotBinary() {
        val habits = listOf(habit(), habit(2), habit(3, start.plusDays(1)))
        assertEquals(.5f, Analytics.density(habits, listOf(log(0)), start).fraction, .0001f)
        assertEquals(0f, Analytics.rate(emptyList(), emptyList(), start, start).fraction, .0001f)
    }

    @Test fun bestStreakResetsAtMissingAndFailedDays() {
        val logs = listOf(log(0), log(1), log(3), log(4, LogStatus.SKIPPED), log(5), log(6, LogStatus.FAILED))
        assertEquals(2, Analytics.bestStreak(habit(), logs, start.plusDays(6)))
    }

    @Test fun weeksStartMondayAndHandleYearBoundary() {
        assertEquals(LocalDate.of(2025, 12, 29), Analytics.weekStart(LocalDate.of(2026, 1, 1)))
    }

    @Test fun unmarkedListsDueHabitsWithoutRecordsAndSkipsArchivedAndFuture() {
        val weekday = habit(2).copy(scheduledDays = listOf(1, 2, 3, 4, 5))
        val monday = start.plusDays(2) // 2026-03-09, a Monday
        val logs = listOf(log(2, LogStatus.COMPLETED), log(3, LogStatus.SKIPPED, habitId = 2))
        assertEquals(listOf(2L), Analytics.unmarked(listOf(habit(), weekday), logs, monday).map { it.id })
        val tuesday = start.plusDays(3)
        assertEquals(listOf(1L), Analytics.unmarked(listOf(habit(), weekday), logs, tuesday).map { it.id })
        val archived = habit(9).copy(isArchived = true, archivedOn = start)
        val future = habit(10).copy(startDate = start.plusDays(5))
        assertEquals(emptyList<Long>(), Analytics.unmarked(listOf(archived, future), logs, monday).map { it.id })
    }

    @Test fun dueOnFollowsTheScheduleMode() {
        val monday = start.plusDays(2) // 2026-03-09
        val weekday = habit(2).copy(scheduledDays = listOf(1))
        assertTrue(Analytics.dueOn(weekday, monday))
        assertFalse(Analytics.dueOn(weekday, monday.plusDays(1)))
        val interval = habit(3).copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = start)
        assertTrue(Analytics.dueOn(interval, start))
        assertTrue(Analytics.dueOn(interval, start.plusDays(6)))
        assertFalse(Analytics.dueOn(interval, start.plusDays(1)))
    }

    @Test fun quotaHabitsAreNeverDueOnAnyDay() {
        val quota = habit(2).copy(scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)
        // Even with no completions this week, no specific day is ever required.
        (0..6).forEach { offset -> assertFalse(Analytics.dueOn(quota, start.plusDays(offset.toLong()))) }
        assertTrue(Analytics.eligible(quota, start))
    }

    @Test fun unmarkedExcludesQuotaHabitsButStillListsOverdueWeekdayHabits() {
        val weekday = habit(2).copy(scheduledDays = listOf(1))
        val quota = habit(3).copy(scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)
        val monday = start.plusDays(2)
        assertEquals(listOf(2L), Analytics.unmarked(listOf(weekday, quota), emptyList(), monday).map { it.id })
    }

    @Test fun densityExcludesQuotaHabitsFromNumeratorAndDenominator() {
        val quota = habit(2).copy(scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)
        val quotaLog = HabitLog(id = 90, habitId = 2, date = start, status = LogStatus.COMPLETED)
        // A quota-only day has nothing due: 0/0, never a zero-percent rate against phantom dues.
        val quotaOnly = Analytics.density(listOf(quota), listOf(quotaLog), start)
        assertEquals(0L, quotaOnly.opportunities)
        assertEquals(0L, quotaOnly.completed)
        // A mixed day counts only the daily-due weekday habits, in both directions.
        val mixed = Analytics.density(listOf(habit(), quota), listOf(log(0), quotaLog), start)
        assertEquals(1L, mixed.opportunities)
        assertEquals(1L, mixed.completed)
    }
}
