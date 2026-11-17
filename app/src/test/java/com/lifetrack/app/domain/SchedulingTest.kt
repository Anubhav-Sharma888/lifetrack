package com.lifetrack.app.domain

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.data.ScheduleRevision
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure engine tests for the three schedule modes: weekday grids, every-N-day recurrence and weekly quotas. */
class SchedulingTest {
    private val anchor = LocalDate.of(2026, 9, 1) // Tuesday

    private fun habit(
        id: Long = 1,
        mode: ScheduleMode = ScheduleMode.WEEKDAYS,
        days: List<Int> = (1..7).toList(),
        intervalDays: Int? = null,
        weeklyTarget: Int? = null,
        history: List<ScheduleRevision> = emptyList()
    ) = Habit(
        id = id, name = "Read", startDate = anchor, scheduledDays = days,
        scheduleMode = mode.name, intervalDays = intervalDays,
        intervalAnchor = if (mode == ScheduleMode.EVERY_N_DAYS) anchor else null,
        weeklyTarget = weeklyTarget, scheduleHistory = history
    )

    private fun log(date: LocalDate, status: LogStatus = LogStatus.COMPLETED, habitId: Long = 1) =
        HabitLog(id = date.toEpochDay() + habitId * 1000, habitId = habitId, date = date,
            status = status, progress = if (status == LogStatus.COMPLETED) 1 else 0)

    private fun progress(id: String, inputs: Achievements.Inputs) =
        Achievements.evaluate(inputs).first { it.definition.id == id }

    // ---- WEEKDAYS: unchanged semantics ----

    @Test fun `weekday habits follow the selected days`() {
        val monWedFri = habit(days = listOf(1, 3, 5))
        assertTrue(Scheduling.isScheduledOn(monWedFri, LocalDate.of(2026, 9, 7)))  // Monday
        assertTrue(Scheduling.isScheduledOn(monWedFri, LocalDate.of(2026, 9, 9)))  // Wednesday
        assertFalse(Scheduling.isScheduledOn(monWedFri, LocalDate.of(2026, 9, 8))) // Tuesday
    }

    @Test fun `weekday history revisions are honored per date`() {
        val habit = habit(history = listOf(ScheduleRevision(LocalDate.of(2026, 10, 1), listOf(1))))
        // Before the first revision, the engine falls back to that revision's own schedule.
        assertFalse(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 9, 15))) // Tuesday, first revision is Monday-only
        assertTrue(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 10, 5)))  // Monday after revision
        assertFalse(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 10, 6))) // Tuesday after revision
    }

    // ---- EVERY_N_DAYS: grid eligibility ----

    @Test fun `interval grid qualifies only on anchor plus multiples`() {
        val habit = habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 3)
        assertTrue(Scheduling.isScheduledOn(habit, anchor))                     // anchor inclusive
        assertTrue(Scheduling.isScheduledOn(habit, anchor.plusDays(3)))
        assertTrue(Scheduling.isScheduledOn(habit, anchor.plusDays(6)))
        assertFalse(Scheduling.isScheduledOn(habit, anchor.plusDays(2)))
        assertFalse(Scheduling.isScheduledOn(habit, anchor.minusDays(3)))       // before anchor
    }

    @Test fun `interval grid crosses month boundaries via LocalDate arithmetic`() {
        val habit = habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 2,
            history = listOf(ScheduleRevision(LocalDate.of(2026, 1, 30), (1..7).toList(), mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 2)))
        // Jan 30 + 2 = Feb 1; every second day across the 28-day February without drift.
        assertTrue(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 2, 1)))
        assertFalse(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 2, 2)))
        assertTrue(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 2, 13)))
    }

    @Test fun `interval grid crosses year boundaries`() {
        val habit = habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 2,
            history = listOf(ScheduleRevision(LocalDate.of(2026, 12, 30), (1..7).toList(), mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 2)))
        assertTrue(Scheduling.isScheduledOn(habit, LocalDate.of(2027, 1, 1)))
        assertFalse(Scheduling.isScheduledOn(habit, LocalDate.of(2027, 1, 2)))
    }

    @Test fun `interval revisions re-anchor the grid from the edit date without rewriting history`() {
        val habit = habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 3,
            history = listOf(
                ScheduleRevision(LocalDate.of(2026, 9, 1), (1..7).toList(), mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3),
                ScheduleRevision(LocalDate.of(2026, 10, 1), (1..7).toList(), mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 5)
            ))
        // Old grid (anchor Sep 1, every 3): Sep 4 qualifies under the pre-change revision.
        assertTrue(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 9, 4)))
        // New grid from Oct 1: Oct 1 + 5 = Oct 6; Oct 5 is off-grid under the new interval.
        assertTrue(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 10, 6)))
        assertFalse(Scheduling.isScheduledOn(habit, LocalDate.of(2026, 10, 5)))
    }

    @Test fun `next scheduled date advances along the grid`() {
        val habit = habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 5,
            history = listOf(ScheduleRevision(LocalDate.of(2026, 9, 1), (1..7).toList(), mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 5)))
        assertEquals(LocalDate.of(2026, 9, 6), Scheduling.nextScheduledOn(habit, LocalDate.of(2026, 9, 5)))
    }

    @Test fun `weekly quota habits have no next fixed date`() {
        assertNull(Scheduling.nextScheduledOn(habit(mode = ScheduleMode.WEEKLY_QUOTA, weeklyTarget = 3), LocalDate.of(2026, 9, 5)))
    }

    // ---- WEEKLY_QUOTA: any-day eligibility ----

    @Test fun `quota habits allow completions on any day of the week`() {
        val habit = habit(mode = ScheduleMode.WEEKLY_QUOTA, weeklyTarget = 3)
        (0..6).forEach { offset ->
            assertTrue(Scheduling.isScheduledOn(habit, anchor.plusDays(offset.toLong())))
        }
    }

    @Test fun `weekly quota status distinguishes achieved ongoing missed and rest`() {
        val habit = habit(mode = ScheduleMode.WEEKLY_QUOTA, weeklyTarget = 3)
        val weekMonday = LocalDate.of(2026, 9, 7)
        val logsFor = (0 until 3).map { offset ->
            HabitLog(id = offset.toLong(), habitId = 1, date = weekMonday.plusDays(offset.toLong()),
                status = LogStatus.COMPLETED, progress = 1)
        }
        val achieved = Scheduling.weeklyQuotaStatus(habit, logsFor, emptyList(), weekMonday, weekMonday.plusDays(6))
        assertEquals(3, achieved.completedDays)
        assertEquals(Scheduling.WeeklyQuotaState.ACHIEVED, achieved.state)

        // Two completions mid-current-week: ongoing, never prematurely failed.
        val ongoing = Scheduling.weeklyQuotaStatus(habit, logsFor.take(2), emptyList(), weekMonday, weekMonday.plusDays(1))
        assertEquals(Scheduling.WeeklyQuotaState.ONGOING, ongoing.state)

        // An elapsed week with enough eligible days but too few completions: missed.
        val missed = Scheduling.weeklyQuotaStatus(habit, logsFor.take(2), emptyList(), weekMonday, weekMonday.plusDays(13))
        assertEquals(Scheduling.WeeklyQuotaState.MISSED, missed.state)

        // A week fully inside a pause: rest, not missed.
        val pauses = listOf(HabitPause(habitId = 1, startDate = weekMonday, endDate = weekMonday.plusDays(7)))
        val rest = Scheduling.weeklyQuotaStatus(habit, logsFor.take(2), pauses, weekMonday, weekMonday.plusDays(6))
        assertEquals(Scheduling.WeeklyQuotaState.REST, rest.state)
    }

    // ---- Mode resolution and configuration predicate ----

    @Test fun `mode resolves from history with weekday fallback`() {
        val habit = habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 3,
            history = listOf(ScheduleRevision(LocalDate.of(2026, 10, 1), (1..7).toList(), mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)))
        assertEquals(ScheduleMode.WEEKLY_QUOTA, Scheduling.mode(habit, LocalDate.of(2026, 10, 15)))
        // Dates before the first revision fall back to that revision (firstOrNull), which is QUOTA here.
        assertEquals(ScheduleMode.WEEKLY_QUOTA, Scheduling.mode(habit, LocalDate.of(2026, 9, 15)))
    }

    @Test fun `unknown stored modes fall back to weekdays`() {
        assertEquals(ScheduleMode.WEEKDAYS, ScheduleMode.fromStored("SIDEWAYS"))
    }

    @Test fun `isConfigured detects flexible habits`() {
        assertTrue(Scheduling.isConfigured(habit(mode = ScheduleMode.EVERY_N_DAYS, intervalDays = 3)))
        assertTrue(Scheduling.isConfigured(habit(mode = ScheduleMode.WEEKLY_QUOTA, weeklyTarget = 3)))
        assertFalse(Scheduling.isConfigured(habit()))
    }
}
