package com.lifetrack.app.domain

import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.ActivityMode
import com.lifetrack.app.data.AchievementAcknowledgment
import com.lifetrack.app.data.DailyReflection
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.StreakProtection
import com.lifetrack.app.data.WorkoutActivity
import java.time.LocalDate

/** Stable achievement identifiers. Never reordered, never reused; new ids are appended only. */
object AchievementIds {
    const val FIRST_COMPLETION = "habit.first_completion"
    const val COMPLETIONS_7 = "habit.completions_7"
    const val COMPLETIONS_30 = "habit.completions_30"
    const val COMPLETIONS_100 = "habit.completions_100"
    const val STREAK_DAYS_7 = "streak.days_7"
    const val STREAK_DAYS_30 = "streak.days_30"
    const val STREAK_DAYS_90 = "streak.days_90"
    const val FIRST_ACTIVITY_SESSION = "activity.first_session"
    const val FIRST_PB_IMPROVEMENT = "activity.first_pb_improvement"
    const val FIRST_TARGET_ATTAINED = "activity.first_target_attained"
    const val FIRST_REFLECTION = "reflection.first"
    const val REFLECTION_DATES_7 = "reflection.dates_7"
    const val REFLECTION_DATES_30 = "reflection.dates_30"
}

enum class AchievementCategory { HABIT_COMPLETION, CONSISTENCY, ACTIVITY, REFLECTION }

/** One catalogue entry. [target] is expressed in the same unit as [com.lifetrack.app.domain.Achievements.currentFor]. */
data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val category: AchievementCategory,
    val target: Long
)

/**
 * Deterministic, derived achievement state over canonical Room data — nothing here is stored:
 * editing, deleting, restoring or resetting data re-evaluates achievements exactly as if the
 * history had always looked that way. Acknowledgments (what the user has seen) live separately
 * in their own table and never influence what counts.
 *
 * Scope rules: completion, reflection and activity-session milestones are account-wide totals;
 * streak milestones are per-habit bests, achieved account-wide when any habit qualifies;
 * personal-best and target milestones are per-activity evaluations, achieved account-wide.
 */
object Achievements {

    /** Every catalogue entry in deterministic display order. */
    val catalogue: List<AchievementDefinition> = listOf(
        AchievementDefinition(AchievementIds.FIRST_COMPLETION, "First step",
            "Fully complete a habit for the first time", AchievementCategory.HABIT_COMPLETION, 1),
        AchievementDefinition(AchievementIds.COMPLETIONS_7, "Seven completions",
            "Reach 7 fully completed habit days in total", AchievementCategory.HABIT_COMPLETION, 7),
        AchievementDefinition(AchievementIds.COMPLETIONS_30, "Thirty completions",
            "Reach 30 fully completed habit days in total", AchievementCategory.HABIT_COMPLETION, 30),
        AchievementDefinition(AchievementIds.COMPLETIONS_100, "One hundred completions",
            "Reach 100 fully completed habit days in total", AchievementCategory.HABIT_COMPLETION, 100),
        AchievementDefinition(AchievementIds.STREAK_DAYS_7, "Seven-day streak",
            "Any habit reaching a 7-day qualifying streak", AchievementCategory.CONSISTENCY, 7),
        AchievementDefinition(AchievementIds.STREAK_DAYS_30, "Thirty-day streak",
            "Any habit reaching a 30-day qualifying streak", AchievementCategory.CONSISTENCY, 30),
        AchievementDefinition(AchievementIds.STREAK_DAYS_90, "Ninety-day streak",
            "Any habit reaching a 90-day qualifying streak", AchievementCategory.CONSISTENCY, 90),
        AchievementDefinition(AchievementIds.FIRST_ACTIVITY_SESSION, "First session",
            "Record your first activity entry", AchievementCategory.ACTIVITY, 1),
        AchievementDefinition(AchievementIds.FIRST_PB_IMPROVEMENT, "Personal best",
            "Beat your own best in any activity", AchievementCategory.ACTIVITY, 1),
        AchievementDefinition(AchievementIds.FIRST_TARGET_ATTAINED, "Goal reached",
            "Reach the goal of a directional activity", AchievementCategory.ACTIVITY, 1),
        AchievementDefinition(AchievementIds.FIRST_REFLECTION, "First reflection",
            "Save your first mood or journal entry", AchievementCategory.REFLECTION, 1),
        AchievementDefinition(AchievementIds.REFLECTION_DATES_7, "Seven reflections",
            "Reflect on 7 different days", AchievementCategory.REFLECTION, 7),
        AchievementDefinition(AchievementIds.REFLECTION_DATES_30, "Thirty reflections",
            "Reflect on 30 different days", AchievementCategory.REFLECTION, 30)
    )

    /** The engine's inputs, bundled so call sites pass one value. */
    data class Inputs(
        val habits: List<Habit>,
        val logs: List<HabitLog>,
        val pauses: List<HabitPause>,
        val protections: List<StreakProtection>,
        val activities: List<WorkoutActivity>,
        val activityEntries: List<ActivityEntry>,
        val reflections: List<DailyReflection>,
        val acknowledgments: List<AchievementAcknowledgment>,
        val today: LocalDate
    )

    /** One achievement's derived state. [current] and [target] share the same unit; a protected
     *  partial day contributes to [current] for streak milestones only through the streak rules
     *  (it bridges without counting), and never toward completion totals. */
    data class Progress(
        val definition: AchievementDefinition,
        val current: Long,
        val achieved: Boolean,
        val acknowledged: Boolean
    )

    /** Full completions are COMPLETED logs on eligible dates — protected partials are IN_PROGRESS
     *  and never count. Mirrors the canonical eligibility pattern used by weeklyVolumes. */
    private fun completionCount(habits: List<Habit>, logs: List<HabitLog>, pauses: List<HabitPause>): Long =
        habits.sumOf { habit ->
            logs.count { log ->
                log.habitId == habit.id && log.status == LogStatus.COMPLETED &&
                    Analytics.eligible(habit, log.date, pauses)
            }.toLong()
        }

    /** The best qualifying streak across all habits, computed with Smart Streaks bridging. */
    private fun bestStreak(habits: List<Habit>, logs: List<HabitLog>, pauses: List<HabitPause>,
        protections: List<StreakProtection>, today: LocalDate): Long =
        habits.maxOfOrNull { habit ->
            Analytics.bestStreak(habit, logs, today, pauses, protections).toLong()
        } ?: 0L

    /** Improvement events: PERSONAL_BEST entries strictly above everything recorded earlier for
     *  that activity (the first record only sets the baseline; ties are not improvements). */
    private fun personalBestImprovements(activities: List<WorkoutActivity>, entries: List<ActivityEntry>): Long =
        activities.filter { ProgressTracking.mode(it) == ActivityMode.PERSONAL_BEST }.sumOf { activity ->
            val values = entries.filter { it.activityId == activity.id }.sortedBy { it.date }.map { it.quantity }
            var best = Double.NEGATIVE_INFINITY
            var improvements = 0L
            values.forEachIndexed { index, quantity ->
                // The very first record establishes the baseline; only later records can improve on it.
                if (index > 0 && quantity > best) improvements++
                if (quantity > best || index == 0) best = quantity
            }
            improvements
        }

    /** Directional and personal-best activities whose latest reading has reached their goal.
     *  LOGGED activities have no direction, so target attainment never applies to them. */
    private fun targetsAttained(activities: List<WorkoutActivity>, entries: List<ActivityEntry>): Long =
        activities.count { activity ->
            val latest = entries.filter { it.activityId == activity.id }.maxByOrNull { it.date }?.quantity
            when (ProgressTracking.mode(activity)) {
                ActivityMode.INCREASING -> activity.target != null && latest != null && latest >= activity.target
                ActivityMode.DECREASING -> activity.target != null && latest != null && latest <= activity.target
                ActivityMode.PERSONAL_BEST -> activity.target != null && activity.target > 0 && latest != null && latest >= activity.target
                else -> false
            }
        }.toLong()

    private fun currentFor(definition: AchievementDefinition, inputs: Inputs): Long = when (definition.id) {
        AchievementIds.FIRST_COMPLETION, AchievementIds.COMPLETIONS_7,
        AchievementIds.COMPLETIONS_30, AchievementIds.COMPLETIONS_100 ->
            completionCount(inputs.habits, inputs.logs, inputs.pauses)
        AchievementIds.STREAK_DAYS_7, AchievementIds.STREAK_DAYS_30, AchievementIds.STREAK_DAYS_90 ->
            bestStreak(inputs.habits, inputs.logs, inputs.pauses, inputs.protections, inputs.today)
        AchievementIds.FIRST_ACTIVITY_SESSION ->
            inputs.activityEntries.size.toLong()
        AchievementIds.FIRST_PB_IMPROVEMENT ->
            personalBestImprovements(inputs.activities, inputs.activityEntries)
        AchievementIds.FIRST_TARGET_ATTAINED ->
            targetsAttained(inputs.activities, inputs.activityEntries)
        AchievementIds.FIRST_REFLECTION, AchievementIds.REFLECTION_DATES_7, AchievementIds.REFLECTION_DATES_30 ->
            inputs.reflections.size.toLong()
        else -> 0L
    }

    /** Full evaluation of the catalogue. [Progress.current] never exceeds honest data; achieved
     *  means current >= target and is fully reversible when the underlying data changes. */
    fun evaluate(inputs: Inputs): List<Progress> {
        val acknowledgedIds = inputs.acknowledgments.map { it.achievementId }.toSet()
        return catalogue.map { definition ->
            val current = currentFor(definition, inputs)
            Progress(
                definition = definition,
                current = current,
                achieved = current >= definition.target,
                acknowledged = definition.id in acknowledgedIds
            )
        }
    }
}
