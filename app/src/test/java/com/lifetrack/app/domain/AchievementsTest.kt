package com.lifetrack.app.domain

import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.ActivityMode
import com.lifetrack.app.data.AchievementAcknowledgment
import com.lifetrack.app.data.DailyReflection
import com.lifetrack.app.data.GoalRevision
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.data.ScheduleRevision
import com.lifetrack.app.data.StreakProtection
import com.lifetrack.app.data.WorkoutActivity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure engine tests for the achievement catalogue, following the eligibility matrix from the
 * requirements analysis. 2026-09-07 is a Monday, used for weekday-scheduling cases.
 */
class AchievementsTest {
    private val start = LocalDate.of(2026, 9, 1)

    private fun habit(id: Long = 1, policy: String = "STRICT", target: Int = 1, startDate: LocalDate = start) =
        Habit(id = id, name = "H$id", startDate = startDate, targetCount = target,
            streakPolicy = policy, partialCreditPercent = 75)

    private fun log(date: LocalDate, status: LogStatus = LogStatus.COMPLETED, habitId: Long = 1,
        progress: Int = 1) = HabitLog(id = date.toEpochDay() + habitId * 1000, habitId = habitId,
        date = date, status = status, progress = progress)

    private fun activity(id: Long = 1, mode: String = ActivityMode.PERSONAL_BEST.name,
        target: Double? = null, unit: String = "reps") = WorkoutActivity(id = id, name = "A$id",
        unit = unit, target = target, trackingMode = mode)

    private fun entry(date: LocalDate, quantity: Double, activityId: Long = 1) = ActivityEntry(
        id = date.toEpochDay() + activityId * 1000, activityId = activityId, date = date, quantity = quantity
    )

    private fun pause(habitId: Long, from: LocalDate, toInclusive: LocalDate) =
        HabitPause(habitId = habitId, startDate = from, endDate = toInclusive.plusDays(1))

    private fun inputs(
        habits: List<Habit> = listOf(habit()),
        logs: List<HabitLog> = emptyList(),
        pauses: List<HabitPause> = emptyList(),
        activities: List<WorkoutActivity> = emptyList(),
        entries: List<ActivityEntry> = emptyList(),
        reflections: List<DailyReflection> = emptyList(),
        protections: List<StreakProtection> = emptyList()
    ) = Achievements.Inputs(
        habits = habits, logs = logs, pauses = pauses, protections = protections,
        activities = activities, activityEntries = entries,
        reflections = reflections, acknowledgments = emptyList(), today = start.plusDays(120)
    )

    private fun current(id: String, inputs: Achievements.Inputs): Long =
        Achievements.evaluate(inputs).first { it.definition.id == id }.current

    private fun achieved(id: String, inputs: Achievements.Inputs): Boolean =
        Achievements.evaluate(inputs).first { it.definition.id == id }.achieved

    // ---- Habit completion totals ----

    @Test fun `first completion fires on the first fully completed day`() {
        val inputs = inputs(logs = listOf(log(start)))
        assertEquals(1, current(AchievementIds.FIRST_COMPLETION, inputs))
        assertTrue(achieved(AchievementIds.FIRST_COMPLETION, inputs))
    }

    @Test fun `lifetime totals count every habit and never partial days`() {
        val second = habit(id = 2)
        val logs = listOf(
            log(start, LogStatus.COMPLETED),
            log(start.plusDays(1), LogStatus.COMPLETED),
            log(start.plusDays(2), LogStatus.COMPLETED, habitId = 2),
            log(start.plusDays(3), LogStatus.IN_PROGRESS, progress = 0),
            log(start.plusDays(4), LogStatus.FAILED),
            log(start.plusDays(5), LogStatus.SKIPPED)
        )
        val inputs = inputs(habits = listOf(habit(), second), logs = logs)
        assertEquals(3, current(AchievementIds.COMPLETIONS_7, inputs))
        assertFalse(achieved(AchievementIds.COMPLETIONS_7, inputs))
    }

    @Test fun `empty data yields zero progress and nothing achieved`() {
        val result = Achievements.evaluate(inputs())
        assertEquals(Achievements.catalogue.size, result.size)
        assertTrue(result.none { it.achieved })
        assertTrue(result.all { it.current == 0L })
    }

    @Test fun `weekly quota completions count toward milestones while day streaks stay zero`() {
        // Three full completions on any days of the week: the quota counts distinct completed days.
        val quota = habit().copy(
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            scheduleHistory = listOf(ScheduleRevision(start, (1..7).toList(),
                mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3))
        )
        val logs = listOf(
            log(start), log(start.plusDays(1)), log(start.plusDays(2))
        )
        val inputs = inputs(habits = listOf(quota), logs = logs)
        // Quota days are full completions on eligible dates: completion milestones count them.
        assertTrue(achieved(AchievementIds.FIRST_COMPLETION, inputs))
        assertEquals(3, current(AchievementIds.COMPLETIONS_7, inputs))
        // But quota habits have no day-based streak: the consistency milestone stays unearned.
        assertEquals(0, current(AchievementIds.STREAK_DAYS_7, inputs))
        assertFalse(achieved(AchievementIds.STREAK_DAYS_7, inputs))
    }

    // ---- Qualifying streaks ----

    @Test fun `a seven day completed streak qualifies`() {
        val logs = (0..6).map { log(start.plusDays(it.toLong())) }
        assertEquals(7, current(AchievementIds.STREAK_DAYS_7, inputs(logs = logs)))
        assertTrue(achieved(AchievementIds.STREAK_DAYS_7, inputs(logs = logs)))
    }

    @Test fun `six completed days do not qualify`() {
        val logs = (0..5).map { log(start.plusDays(it.toLong())) }
        assertFalse(achieved(AchievementIds.STREAK_DAYS_7, inputs(logs = logs)))
    }

    @Test fun `skipped and frozen days bridge without counting`() {
        val logs = (0..7).map { day ->
            if (day == 3) log(start.plusDays(day.toLong()), LogStatus.SKIPPED)
            else log(start.plusDays(day.toLong()))
        }
        // 8-day unbroken chain, 7 counted: the milestone is earned inside it.
        assertEquals(7, current(AchievementIds.STREAK_DAYS_7, inputs(logs = logs)))
    }

    @Test fun `unscheduled weekdays keep the chain alive without counting`() {
        // Habit scheduled on Mondays only: three consecutive Mondays, no due days in between.
        val monday = LocalDate.of(2026, 9, 7)
        val scheduled = habit().copy(scheduledDays = listOf(1))
        val logs = (0..2).map { week -> log(monday.plusWeeks(week.toLong())) }
        val inputs = inputs(habits = listOf(scheduled), logs = logs)
        assertEquals(3, current(AchievementIds.STREAK_DAYS_7, inputs))
        assertFalse(achieved(AchievementIds.STREAK_DAYS_7, inputs))
    }

    @Test fun `a missed scheduled day between completions breaks the chain`() {
        val logs = listOf(log(start), log(start.plusDays(2)))
        assertFalse(achieved(AchievementIds.STREAK_DAYS_7, inputs(logs = logs)))
    }

    @Test fun `pauses keep the chain alive without counting`() {
        val logs = listOf(
            log(start), log(start.plusDays(1)),
            log(start.plusDays(3)), log(start.plusDays(4))
        )
        val inputs = inputs(logs = logs, pauses = listOf(pause(1, start.plusDays(2), start.plusDays(2))))
        // Four counted completions around the pause: the chain never breaks.
        assertEquals(4, current(AchievementIds.STREAK_DAYS_7, inputs))
    }

    @Test fun `smart streak bridging keeps the chain alive but protected days never count`() {
        // AUTOMATIC habit with target 4: the day-4 entry is a 3/4 partial (75%) — bridged, not counted.
        val automatic = habit(policy = "AUTOMATIC", target = 4)
        val logs = (0..3).map { day ->
            if (day == 3) log(start.plusDays(day.toLong()), LogStatus.IN_PROGRESS, progress = 3)
            else log(start.plusDays(day.toLong()))
        }
        assertEquals(3, current(AchievementIds.STREAK_DAYS_7, inputs(habits = listOf(automatic), logs = logs)))
        // Seven positions around a bridged partial: 6 counted completions do not satisfy the 7 rule.
        val longLogs = (0..6).map { day ->
            if (day == 3) log(start.plusDays(day.toLong()), LogStatus.IN_PROGRESS, progress = 3)
            else log(start.plusDays(day.toLong()))
        }
        val longInputs = inputs(habits = listOf(automatic), logs = longLogs)
        assertEquals(6, current(AchievementIds.STREAK_DAYS_7, longInputs))
        assertFalse(achieved(AchievementIds.STREAK_DAYS_7, longInputs))
    }

    @Test fun `an explicit protection row bridges under strict without counting`() {
        val strict = habit(policy = "STRICT", target = 4)
        val protections = listOf(StreakProtection(habitId = 1, date = start.plusDays(3), protectChain = true))
        val logs = (0..4).map { day ->
            if (day == 3) log(start.plusDays(day.toLong()), LogStatus.IN_PROGRESS, progress = 3)
            else log(start.plusDays(day.toLong()))
        }
        val inputs = inputs(habits = listOf(strict), logs = logs, protections = protections)
        // Chain: 5 positions, day 3 bridged — 4 counted completions in the unbroken chain.
        assertEquals(4, current(AchievementIds.STREAK_DAYS_7, inputs))
    }

    @Test fun `goal revisions never perturb completion totals or streak counting`() {
        val revised = habit(target = 8).copy(
            goalHistory = listOf(GoalRevision(start, 4, "times"))
        )
        val logs = (0..6).map { log(start.plusDays(it.toLong())) }
        assertEquals(7, current(AchievementIds.STREAK_DAYS_7, inputs(habits = listOf(revised), logs = logs)))
    }

    // ---- Activity tracking ----

    @Test fun `first session fires on any recorded entry`() {
        val inputs = inputs(activities = listOf(activity()), entries = listOf(entry(start, 10.0)))
        assertEquals(1, current(AchievementIds.FIRST_ACTIVITY_SESSION, inputs))
        assertTrue(achieved(AchievementIds.FIRST_ACTIVITY_SESSION, inputs))
    }

    @Test fun `personal best improvement requires a strict new maximum`() {
        val inputs = inputs(
            activities = listOf(activity()),
            entries = listOf(entry(start, 17.0), entry(start.plusDays(1), 18.0), entry(start.plusDays(2), 15.0))
        )
        assertEquals(1, current(AchievementIds.FIRST_PB_IMPROVEMENT, inputs))
        assertTrue(achieved(AchievementIds.FIRST_PB_IMPROVEMENT, inputs))
    }

    @Test fun `ties and first records are not improvements`() {
        val inputs = inputs(
            activities = listOf(activity()),
            entries = listOf(entry(start, 17.0), entry(start.plusDays(1), 17.0))
        )
        assertEquals(0, current(AchievementIds.FIRST_PB_IMPROVEMENT, inputs))
    }

    @Test fun `logged activities never produce personal best improvements`() {
        val logged = activity(mode = ActivityMode.LOGGED.name)
        val inputs = inputs(
            activities = listOf(logged),
            entries = listOf(entry(start, 17.0), entry(start.plusDays(1), 18.0))
        )
        assertEquals(0, current(AchievementIds.FIRST_PB_IMPROVEMENT, inputs))
    }

    @Test fun `directional targets are attained at the latest reading`() {
        val increasing = activity(id = 1, mode = ActivityMode.INCREASING.name, target = 100.0, unit = "pages")
        val decreasing = activity(id = 2, mode = ActivityMode.DECREASING.name, target = 0.0, unit = "tasks")
        val inputs = inputs(
            activities = listOf(increasing, decreasing),
            entries = listOf(
                entry(start, 100.0, activityId = 1),
                entry(start.plusDays(1), 0.0, activityId = 2)
            )
        )
        assertEquals(2, current(AchievementIds.FIRST_TARGET_ATTAINED, inputs))
        assertTrue(achieved(AchievementIds.FIRST_TARGET_ATTAINED, inputs))
    }

    @Test fun `target attainment follows the current target and reverses on edits`() {
        val act = activity(id = 1, mode = ActivityMode.INCREASING.name, target = 100.0)
        val inputs = inputs(activities = listOf(act), entries = listOf(entry(start, 80.0)))
        assertFalse(achieved(AchievementIds.FIRST_TARGET_ATTAINED, inputs))
        // Lowering the target makes the same history attain it — current-target semantics.
        val lowered = inputs.copy(activities = listOf(act.copy(target = 75.0)))
        assertTrue(achieved(AchievementIds.FIRST_TARGET_ATTAINED, lowered))
    }

    @Test fun `logged activities never attain directional targets`() {
        val logged = activity(mode = ActivityMode.LOGGED.name, target = 5.0)
        val inputs = inputs(activities = listOf(logged), entries = listOf(entry(start, 5.0)))
        assertFalse(achieved(AchievementIds.FIRST_TARGET_ATTAINED, inputs))
    }

    // ---- Reflections ----

    @Test fun `reflections count distinct dates and never read the mood`() {
        val reflections = listOf(
            DailyReflection(date = start, mood = 1, journal = null),
            DailyReflection(date = start.plusDays(1), mood = null, journal = "hard day"),
            DailyReflection(date = start.plusDays(2), mood = 5, journal = null)
        )
        val inputs = inputs(reflections = reflections)
        assertEquals(3, current(AchievementIds.REFLECTION_DATES_7, inputs))
        assertFalse(achieved(AchievementIds.REFLECTION_DATES_7, inputs))
        assertTrue(achieved(AchievementIds.FIRST_REFLECTION, inputs))
    }

    @Test fun `deleting reflections reverses achievement progress`() {
        val reflections = (0..6).map { DailyReflection(date = start.plusDays(it.toLong()), mood = 3) }
        val with = inputs(reflections = reflections)
        assertTrue(achieved(AchievementIds.REFLECTION_DATES_7, with))
        val after = inputs(reflections = reflections.dropLast(1))
        assertFalse(achieved(AchievementIds.REFLECTION_DATES_7, after))
    }

    // ---- Acknowledgments ----

    @Test fun `acknowledgments mark seen without changing achievement state`() {
        val reflections = (0..6).map { DailyReflection(date = start.plusDays(it.toLong()), mood = 3) }
        val unacknowledged = Achievements.evaluate(inputs(reflections = reflections))
            .first { it.definition.id == AchievementIds.REFLECTION_DATES_7 }
        assertTrue(unacknowledged.achieved)
        assertFalse(unacknowledged.acknowledged)
        val acknowledged = Achievements.evaluate(
            inputs(reflections = reflections).copy(
                acknowledgments = listOf(AchievementAcknowledgment(AchievementIds.REFLECTION_DATES_7, 500))
            )
        ).first { it.definition.id == AchievementIds.REFLECTION_DATES_7 }
        assertTrue(acknowledged.achieved)
        assertTrue(acknowledged.acknowledged)
        assertEquals(7, acknowledged.current)
    }

    // ---- Catalogue integrity ----

    @Test fun `catalogue ids are unique and thresholds are positive`() {
        val ids = Achievements.catalogue.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(Achievements.catalogue.all { it.target >= 1 })
        assertTrue(Achievements.catalogue.all { it.title.isNotBlank() && it.description.isNotBlank() })
    }

    @Test fun `every catalogue id has a computation branch`() {
        // A catalogue id without a currentFor branch would always report zero progress.
        val allZero = inputs()
        Achievements.catalogue.forEach { definition ->
            assertEquals(0, Achievements.evaluate(allZero).first { it.definition.id == definition.id }.current)
        }
    }
}
