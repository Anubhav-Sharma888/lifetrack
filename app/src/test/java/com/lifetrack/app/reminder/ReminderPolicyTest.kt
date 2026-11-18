package com.lifetrack.app.reminder

import com.lifetrack.app.data.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val monday = LocalDate.of(2026, 10, 26)
    private val habit = Habit(id = 1, name = "Read", startDate = monday.minusDays(10), reminderMinutes = 9 * 60)
    @Test fun onlyDueAtOrAfterSelectedTime() {
        assertFalse(ReminderPolicy.isDue(habit, null, monday, 539))
        assertTrue(ReminderPolicy.isDue(habit, null, monday, 540))
        assertTrue(ReminderPolicy.isDue(habit, null, monday, 1200))
    }
    @Test fun completedAndSkippedDaysStayQuietButPartialProgressCanBeNudged() {
        fun log(status: LogStatus) = HabitLog(id = 1, habitId = 1, date = monday, status = status)
        assertFalse(ReminderPolicy.isDue(habit, log(LogStatus.COMPLETED), monday, 600))
        assertFalse(ReminderPolicy.isDue(habit, log(LogStatus.SKIPPED), monday, 600))
        assertTrue(ReminderPolicy.isDue(habit, log(LogStatus.IN_PROGRESS), monday, 600))
    }
    @Test fun futureArchivedDisabledAndUnscheduledHabitsDoNotNotify() {
        assertFalse(ReminderPolicy.isDue(habit.copy(startDate = monday.plusDays(1)), null, monday, 600))
        assertFalse(ReminderPolicy.isDue(habit.copy(isArchived = true), null, monday, 600))
        assertFalse(ReminderPolicy.isDue(habit.copy(reminderMinutes = null), null, monday, 600))
        assertFalse(ReminderPolicy.isDue(habit.copy(scheduledDays = listOf(2, 4)), null, monday, 600))
    }
    @Test fun pauseIntervalsStayQuiet() {
        val pause = HabitPause(id = 1, habitId = 1, startDate = monday.minusDays(1), endDate = monday.plusDays(1))
        assertFalse(ReminderPolicy.isDue(habit, null, monday, 600, listOf(pause)))
    }
    @Test fun intervalHabitsNotifyOnlyOnTheirGridDates() {
        val interval = habit.copy(scheduledDays = listOf(2), reminderMinutes = 9 * 60,
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = monday,
            scheduleHistory = listOf(ScheduleRevision(monday, listOf(2),
                mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3)))
        assertTrue(ReminderPolicy.isDue(interval, null, monday, 600))               // anchor on the grid
        assertTrue(ReminderPolicy.isDue(interval, null, monday.plusDays(3), 600))   // anchor + 3
        assertFalse(ReminderPolicy.isDue(interval, null, monday.plusDays(1), 600))  // off-grid
        assertFalse(ReminderPolicy.isDue(interval, null, monday.minusDays(3), 600)) // pre-anchor weekday era, not scheduled
    }
    @Test fun quotaHabitsNeverReceiveDailyReminders() {
        val quota = habit.copy(scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3, reminderMinutes = 9 * 60)
        assertFalse(ReminderPolicy.isDue(quota, null, monday, 600))
        assertFalse(ReminderPolicy.isDue(quota, null, monday.plusDays(3), 1200)) // any day, any minute
    }
}
