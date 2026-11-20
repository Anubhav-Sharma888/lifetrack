package com.lifetrack.app.reminder

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.domain.Scheduling
import java.time.LocalDate

/** Pure decision logic; Android may defer the actual background execution.
 *  WEEKLY_QUOTA habits never receive daily "overdue" reminders: the user picks the days,
 *  and a daily nudge would be misleading. Prompt 8 may add a dedicated weekly nudge. */
object ReminderPolicy {
    fun isDue(habit: Habit, log: HabitLog?, date: LocalDate, minuteOfDay: Int, pauses: List<HabitPause> = emptyList()): Boolean =
        !habit.isArchived && habit.reminderMinutes?.let { minuteOfDay >= it } == true &&
            Scheduling.mode(habit, date) != ScheduleMode.WEEKLY_QUOTA &&
            Analytics.eligible(habit, date, pauses) && log?.status !in setOf(LogStatus.COMPLETED, LogStatus.SKIPPED)
}
