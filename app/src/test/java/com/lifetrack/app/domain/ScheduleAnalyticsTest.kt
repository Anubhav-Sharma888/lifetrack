package com.lifetrack.app.domain

import com.lifetrack.app.data.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleAnalyticsTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val habit = Habit(id = 1, name = "Exercise", startDate = monday, scheduledDays = listOf(1, 3, 5))
    private fun log(date: LocalDate, status: LogStatus = LogStatus.COMPLETED) = HabitLog(id = date.toEpochDay(), habitId = 1, date = date, status = status)

    @Test fun weekdayDenominatorsAndStreaksIgnoreOffDays() {
        val logs = listOf(log(monday), log(monday.plusDays(2)), log(monday.plusDays(4)))
        assertEquals(3L, Analytics.rate(listOf(habit), logs, monday, monday.plusDays(6)).opportunities)
        assertEquals(3, Analytics.currentStreak(habit, logs, monday.plusDays(6)))
        assertEquals(3, Analytics.bestStreak(habit, logs, monday.plusDays(6)))
        assertFalse(Analytics.eligible(habit, monday.plusDays(1)))
    }

    @Test fun partialTodayHasGraceButPartialPastBreaksAChain() {
        val partial = log(monday.plusDays(2), LogStatus.IN_PROGRESS).copy(progress = 2)
        val logs = listOf(log(monday), partial)
        assertEquals(1, Analytics.currentStreak(habit, logs, monday.plusDays(2)))
        assertEquals(0, Analytics.currentStreak(habit, logs, monday.plusDays(4)))
        assertEquals(2L, Analytics.rate(listOf(habit), logs, monday, monday.plusDays(2)).opportunities)
        assertEquals(1L, Analytics.rate(listOf(habit), logs, monday, monday.plusDays(2)).completed)
    }

    @Test fun pauseIntervalsExcludeDatesAndBridgeCompletions() {
        val daily = habit.copy(scheduledDays = (1..7).toList())
        val pause = HabitPause(1, 1, monday.plusDays(1), monday.plusDays(5))
        val logs = listOf(log(monday), log(monday.plusDays(5)))
        assertEquals(2L, Analytics.rate(listOf(daily), logs, monday, monday.plusDays(5), listOf(pause)).opportunities)
        assertEquals(2, Analytics.currentStreak(daily, logs, monday.plusDays(5), listOf(pause)))
        assertEquals(2, Analytics.bestStreak(daily, logs, monday.plusDays(5), listOf(pause)))
        assertEquals(0L, Analytics.density(listOf(daily), logs, monday.plusDays(3), listOf(pause)).opportunities)
    }

    @Test fun historicWeekdayEditsDoNotRewriteEarlierDenominators() {
        val revised = habit.copy(scheduledDays = listOf(2), scheduleHistory = listOf(
            ScheduleRevision(monday, listOf(1, 3, 5)), ScheduleRevision(monday.plusWeeks(1), listOf(2))))
        assertEquals(3L, Analytics.dueCount(revised, monday, monday.plusDays(6)))
        assertEquals(1L, Analytics.dueCount(revised, monday.plusWeeks(1), monday.plusDays(13)))
        assertEquals(4L, Analytics.dueCount(revised, monday, monday.plusDays(13)))
    }

    @Test fun arithmeticDayCountMatchesCalendarReferenceAcrossLeapYearAndPauses() {
        val start = LocalDate.of(2024, 2, 20)
        val end = LocalDate.of(2024, 3, 12)
        val scheduled = habit.copy(startDate = start, scheduledDays = listOf(2, 4, 6))
        val pause = HabitPause(1, 1, LocalDate.of(2024, 2, 27), LocalDate.of(2024, 3, 3))
        var expected = 0L; var date = start
        while (date <= end) { if (Analytics.eligible(scheduled, date, listOf(pause))) expected++; date = date.plusDays(1) }
        assertEquals(expected, Analytics.dueCount(scheduled, start, end, listOf(pause)))
    }

    @Test fun intervalDueCountsAreHalfOpenPerSegmentAndHonorPauses() {
        val anchor = LocalDate.of(2026, 9, 1) // Tuesday
        val interval = habit.copy(
            startDate = anchor, scheduledDays = (1..7).toList(),
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = anchor,
            scheduleHistory = listOf(ScheduleRevision(anchor, (1..7).toList(),
                mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3)))
        // Calendar reference over the window: the grid dates are Sep 1 and Sep 4 (Sep 7 lies outside).
        val end = LocalDate.of(2026, 9, 6)
        var expected = 0L; var date = anchor
        while (date <= end) { if (Analytics.eligible(interval, date)) expected++; date = date.plusDays(1) }
        assertEquals(expected, Analytics.dueCount(interval, anchor, end))
        assertEquals(2L, Analytics.dueCount(interval, anchor, end))
        // through is inclusive: Sep 4 counts; the phantom Sep 7 of the old open formula must not.
        assertEquals(2L, Analytics.dueCount(interval, anchor, LocalDate.of(2026, 9, 4)))
        // Sep 2..3 sits between grid dates: nothing due.
        assertEquals(0L, Analytics.dueCount(interval, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)))
        // A pause starting on the Sep 4 grid date removes it from the same window.
        val pause = HabitPause(1, 1, LocalDate.of(2026, 9, 4))
        assertEquals(1L, Analytics.dueCount(interval, anchor, end, listOf(pause)))
    }

    @Test fun quotaEraCompletionsOfASwitchedHabitStayOutOfDailyRates() {
        val start = LocalDate.of(2026, 10, 1) // Thursday; quota era runs to Oct 9
        val switched = habit.copy(
            startDate = start, scheduledDays = listOf(1, 3, 5),
            scheduleHistory = listOf(
                ScheduleRevision(start, (1..7).toList(),
                    mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3),
                ScheduleRevision(LocalDate.of(2026, 10, 10), listOf(1, 3, 5)))) // weekday era from Sat Oct 10
        fun completed(date: LocalDate) = HabitLog(id = date.toEpochDay(), habitId = 1, date = date, status = LogStatus.COMPLETED)
        val quotaEra = listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5),
            LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 9)).map(::completed)
        val logs = quotaEra + listOf(completed(LocalDate.of(2026, 10, 12))) // Monday of the weekday era
        val rate = Analytics.rate(listOf(switched), logs, start, LocalDate.of(2026, 10, 15))
        // The weekday era dues are Mon Oct 12 and Wed Oct 14; only the Monday completion counts,
        // so the five quota-era completions contribute zero completed and zero opportunities.
        assertEquals(1L, rate.completed)
        assertEquals(2L, rate.opportunities)
        assertEquals(.5f, rate.fraction, .0001f)
        // The pure quota window contributes nothing at all to daily rates.
        val quotaWindow = Analytics.rate(listOf(switched), logs, start, LocalDate.of(2026, 10, 9))
        assertEquals(0L, quotaWindow.completed)
        assertEquals(0L, quotaWindow.opportunities)
    }

    @Test(timeout = 1500) fun hundredYearHistoryCountsWithoutScanningEveryDay() {
        val old = habit.copy(startDate = LocalDate.of(1900, 1, 1))
        repeat(1000) { assertTrue(Analytics.dueCount(old, old.startDate, LocalDate.of(2026, 10, 26)) > 19_000) }
    }
}
