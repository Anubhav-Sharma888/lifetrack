package com.lifetrack.app.reminder

import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.domain.Analytics
import java.time.LocalDate

/** Pure decision logic; Android may defer the actual background execution. */
object ReminderPolicy {
    fun isDue(habit: Habit, log: HabitLog?, date: LocalDate, minuteOfDay: Int, pauses: List<HabitPause> = emptyList()): Boolean =
        !habit.isArchived && habit.reminderMinutes?.let { minuteOfDay >= it } == true &&
            Analytics.eligible(habit, date, pauses) && log?.status !in setOf(LogStatus.COMPLETED, LogStatus.SKIPPED)
}
