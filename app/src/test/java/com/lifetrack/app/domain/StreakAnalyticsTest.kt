package com.lifetrack.app.domain

import com.lifetrack.app.data.GoalRevision
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.data.ScheduleRevision
import com.lifetrack.app.data.StreakProtection
import com.lifetrack.app.data.StreakPolicy as StoredStreakPolicy
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Streak chains with Smart Streaks: a bridged partial day continues the chain without counting toward it. */
class StreakAnalyticsTest {
    private val start = LocalDate.of(2026, 3, 7)
    private fun habit(
        policy: String = StoredStreakPolicy.STRICT.name,
        target: Int = 4,
        credit: Int = 75,
        goals: List<GoalRevision> = emptyList()
    ) = Habit(
        id = 1, name = "Read", startDate = start, targetCount = target,
        partialCreditPercent = credit, streakPolicy = policy, goalHistory = goals
    )
    private fun log(day: Long, status: LogStatus = LogStatus.COMPLETED, progress: Int = 0, habitId: Long = 1) = HabitLog(
        id = day + 1, habitId = habitId, date = start.plusDays(day), status = status, progress = progress
    )
    private fun row(day: Long, protect: Boolean, habitId: Long = 1) =
        StreakProtection(habitId = habitId, date = start.plusDays(day), protectChain = protect)

    @Test fun `strict habits match the original rules with empty protections`() {
        val strict = habit()
        val partialChain = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 3), log(2))
        assertEquals(1, Analytics.currentStreak(strict, partialChain, start.plusDays(2)))
        assertEquals(1, Analytics.bestStreak(strict, partialChain, start.plusDays(2)))
        // The explicit empty default must behave exactly like the omitted parameter.
        assertEquals(1, Analytics.currentStreak(strict, partialChain, start.plusDays(2), protections = emptyList()))
        assertEquals(1, Analytics.bestStreak(strict, partialChain, start.plusDays(2), protections = emptyList()))
        assertEquals(3, Analytics.currentStreak(strict, listOf(log(0), log(1), log(2)), start.plusDays(2)))
        assertEquals(3, Analytics.bestStreak(strict, listOf(log(0), log(1), log(2)), start.plusDays(2)))
    }

    @Test fun `automatic partial above the threshold bridges without counting`() {
        val automatic = habit(policy = StoredStreakPolicy.AUTOMATIC.name)
        // Two completed days plus a bridged 3/4 day: the chain counts only completed days.
        val logs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 3), log(2))
        assertEquals(2, Analytics.currentStreak(automatic, logs, start.plusDays(2)))
        assertEquals(2, Analytics.bestStreak(automatic, logs, start.plusDays(2)))
        val longer = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 3), log(2), log(3))
        assertEquals(3, Analytics.currentStreak(automatic, longer, start.plusDays(3)))
        assertEquals(3, Analytics.bestStreak(automatic, longer, start.plusDays(3)))
    }

    @Test fun `automatic partial below the threshold breaks the chain`() {
        val automatic = habit(policy = StoredStreakPolicy.AUTOMATIC.name)
        val logs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 2), log(2), log(3))
        assertEquals(2, Analytics.currentStreak(automatic, logs, start.plusDays(3)))
        assertEquals(2, Analytics.bestStreak(automatic, logs, start.plusDays(3)))
    }

    @Test fun `manual rows carry the decision and absence keeps strict rules`() {
        val manual = habit(policy = StoredStreakPolicy.MANUAL.name)
        val logs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 1), log(2))
        // No row on the partial day: a manual habit behaves exactly like a strict one.
        assertEquals(1, Analytics.currentStreak(manual, logs, start.plusDays(2)))
        assertEquals(1, Analytics.bestStreak(manual, logs, start.plusDays(2)))
        assertEquals(2, Analytics.currentStreak(manual, logs, start.plusDays(2), protections = listOf(row(1, true))))
        assertEquals(2, Analytics.bestStreak(manual, logs, start.plusDays(2), protections = listOf(row(1, true))))
        assertEquals(1, Analytics.currentStreak(manual, logs, start.plusDays(2), protections = listOf(row(1, false))))
        assertEquals(1, Analytics.bestStreak(manual, logs, start.plusDays(2), protections = listOf(row(1, false))))
    }

    @Test fun `a protect row bridges even under the strict policy`() {
        val logs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 1), log(2))
        assertEquals(2, Analytics.currentStreak(habit(), logs, start.plusDays(2), protections = listOf(row(1, true))))
        assertEquals(2, Analytics.bestStreak(habit(), logs, start.plusDays(2), protections = listOf(row(1, true))))
    }

    @Test fun `retroactive threshold edits rewrite past decisions`() {
        // The same 2/4 day: breaks at the default 75, bridges once the habit is softened to 50.
        val forgivingLogs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 2), log(2))
        assertEquals(1, Analytics.currentStreak(habit(policy = StoredStreakPolicy.AUTOMATIC.name), forgivingLogs, start.plusDays(2)))
        assertEquals(2, Analytics.currentStreak(habit(policy = StoredStreakPolicy.AUTOMATIC.name, credit = 50), forgivingLogs, start.plusDays(2)))
        assertEquals(2, Analytics.bestStreak(habit(policy = StoredStreakPolicy.AUTOMATIC.name, credit = 50), forgivingLogs, start.plusDays(2)))
        // The same 3/4 day: bridges at 75, re-breaks once the habit is tightened to 90.
        val tightenedLogs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 3), log(2))
        assertEquals(2, Analytics.currentStreak(habit(policy = StoredStreakPolicy.AUTOMATIC.name), tightenedLogs, start.plusDays(2)))
        assertEquals(1, Analytics.currentStreak(habit(policy = StoredStreakPolicy.AUTOMATIC.name, credit = 90), tightenedLogs, start.plusDays(2)))
        assertEquals(1, Analytics.bestStreak(habit(policy = StoredStreakPolicy.AUTOMATIC.name, credit = 90), tightenedLogs, start.plusDays(2)))
    }

    @Test fun `goal revisions retarget the automatic threshold mid streak`() {
        val revised = habit(
            policy = StoredStreakPolicy.AUTOMATIC.name, target = 8,
            goals = listOf(GoalRevision(start, 4, "times"), GoalRevision(start.plusDays(1), 8, "times"))
        )
        // 3 units on the day of the old 4-goal: still above the 75% line, so the chain bridges it.
        val before = listOf(log(0, LogStatus.IN_PROGRESS, progress = 3), log(1))
        assertEquals(1, Analytics.currentStreak(revised, before, start.plusDays(1)))
        assertEquals(1, Analytics.bestStreak(revised, before, start.plusDays(1)))
        // The same 3 units after the revision is 3 of 8 and breaks the chain on recompute.
        val after = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 3), log(2))
        assertEquals(1, Analytics.currentStreak(revised, after, start.plusDays(2)))
        assertEquals(1, Analytics.bestStreak(revised, after, start.plusDays(2)))
        // Under the old goal throughout, the identical log set would have bridged.
        val oldGoal = habit(policy = StoredStreakPolicy.AUTOMATIC.name)
        assertEquals(2, Analytics.currentStreak(oldGoal, after, start.plusDays(2)))
        assertEquals(2, Analytics.bestStreak(oldGoal, after, start.plusDays(2)))
    }

    @Test fun `irrelevant protection rows never touch the chain`() {
        val manual = habit(policy = StoredStreakPolicy.MANUAL.name)
        val logs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 1), log(2))
        val rows = listOf(row(5, true), row(9, false), row(1, true, habitId = 2), row(4, false, habitId = 2))
        // Decisions for other habits and other dates leave this manual day broken.
        assertEquals(1, Analytics.currentStreak(manual, logs, start.plusDays(2), protections = rows))
        assertEquals(1, Analytics.bestStreak(manual, logs, start.plusDays(2), protections = rows))
    }

    @Test fun `log status rules protected days and rows stay inert`() {
        val manual = habit(policy = StoredStreakPolicy.MANUAL.name)
        val protectedRow = listOf(row(1, true))
        assertEquals(2, Analytics.currentStreak(manual, listOf(log(0), log(1, LogStatus.SKIPPED), log(2)), start.plusDays(2), protections = protectedRow))
        assertEquals(2, Analytics.bestStreak(manual, listOf(log(0), log(1, LogStatus.SKIPPED), log(2)), start.plusDays(2), protections = protectedRow))
        // A completed day counts as completed no matter what a stale row says: status is the source of truth.
        assertEquals(3, Analytics.currentStreak(manual, listOf(log(0), log(1, LogStatus.COMPLETED), log(2)), start.plusDays(2), protections = protectedRow))
        assertEquals(3, Analytics.bestStreak(manual, listOf(log(0), log(1, LogStatus.COMPLETED), log(2)), start.plusDays(2), protections = protectedRow))
        // A protected day that later failed still breaks: the row cannot rescue a failed check-in.
        assertEquals(1, Analytics.currentStreak(manual, listOf(log(0), log(1, LogStatus.FAILED), log(2)), start.plusDays(2), protections = protectedRow))
        assertEquals(1, Analytics.bestStreak(manual, listOf(log(0), log(1, LogStatus.FAILED), log(2)), start.plusDays(2), protections = protectedRow))
    }

    @Test fun `today partial keeps grace without consulting the policy`() {
        val automatic = habit(policy = StoredStreakPolicy.AUTOMATIC.name)
        // An unfinished today never breaks, no matter what the threshold says.
        val logs = listOf(log(0), log(1), log(2, LogStatus.IN_PROGRESS, progress = 3))
        assertEquals(2, Analytics.currentStreak(automatic, logs, start.plusDays(2)))
        // A below-threshold partial yesterday breaks even while today is still unmarked.
        val lateLogs = listOf(log(0), log(1, LogStatus.IN_PROGRESS, progress = 1))
        assertEquals(0, Analytics.currentStreak(automatic, lateLogs, start.plusDays(2)))
        // An explicit decision resurrects that chain across the unmarked today.
        assertEquals(1, Analytics.currentStreak(automatic, lateLogs, start.plusDays(2), protections = listOf(row(1, true))))
    }

    @Test fun `gaps still break bridged chains`() {
        val anyPartial = habit(policy = StoredStreakPolicy.AUTOMATIC.name, credit = 1)
        val gapped = listOf(log(0), log(2, LogStatus.IN_PROGRESS, progress = 1), log(3))
        assertEquals(1, Analytics.currentStreak(anyPartial, gapped, start.plusDays(3)))
        assertEquals(1, Analytics.bestStreak(anyPartial, gapped, start.plusDays(3)))
    }

    @Test fun `weekly quota habits keep zero day streaks across weekday era and quota era partials`() {
        // Weekday era for the first three days, then a mode-bearing revision switches to quota.
        val quota = Habit(
            id = 1, name = "Read", startDate = start, targetCount = 4,
            streakPolicy = StoredStreakPolicy.AUTOMATIC.name, partialCreditPercent = 75,
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            scheduleHistory = listOf(
                ScheduleRevision(start, (1..7).toList()),
                ScheduleRevision(start.plusDays(3), (1..7).toList(),
                    mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)
            )
        )
        val logs = listOf(
            log(0), log(1),                                   // weekday-era chain of two completions
            log(2, LogStatus.IN_PROGRESS, progress = 3),      // weekday-era 3/4 partial — bridges
            log(4, LogStatus.IN_PROGRESS, progress = 3)       // quota-era partial after the switch
        )
        val today = start.plusDays(6)
        assertEquals(ScheduleMode.WEEKLY_QUOTA, Scheduling.mode(quota, today))
        // The quota block is period-based: both streaks read zero even though every link of this
        // chain would bridge under AUTOMATIC (3*100 >= 75*4) and the weekday era was due daily —
        // without the block the exact same history walks to a streak of 2 across both eras.
        assertEquals(0, Analytics.currentStreak(quota, logs, today))
        assertEquals(0, Analytics.bestStreak(quota, logs, today))
    }
}
