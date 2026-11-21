package com.lifetrack.app.domain

import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskStatus
import com.lifetrack.app.data.MilestoneStatus

/**
 * Honest progress mathematics for Goal Roadmaps (Prompt 3). Pure functions over persisted records
 * — no clock, no database, no writes.
 *
 * Definitions (each deliberately explicit and test-covered):
 *  - The task denominator is EVERY task of the scope: completed, skipped, in-progress and
 *    not-started alike. Skipped work never inflates completion (12 completed of 20 total is 60%,
 *    never 12/16), and a skipped task is never counted as completed.
 *  - A completion rate is `null` when the scope holds no tasks: "not yet measurable", never 100%.
 *  - Milestones count only their persisted PENDING/ACHIEVED state — the user's explicit
 *    verification, never derived from task completion.
 *  - "Ready for review" is a derived suggestion: every task completed and every milestone
 *    achieved. It never writes a status; a human still confirms phase/goal completion.
 *  - Progress, persisted business status, readiness and delay indicators are separate concepts
 *    and are never combined into one weighted score.
 */
object GoalProgress {

    /** Task counts for one scope. [incomplete] counts NOT_STARTED only; [notDone] = in progress + incomplete. */
    data class TaskBreakdown(
        val total: Int,
        val completed: Int,
        val inProgress: Int,
        val skipped: Int,
        val incomplete: Int
    ) {
        val notDone: Int get() = inProgress + incomplete
    }

    /** Milestone counts for one scope. */
    data class MilestoneBreakdown(
        val total: Int,
        val achieved: Int,
        val pending: Int
    )

    /** Aggregated progress for one phase. Rates are null when the scope has no tasks. */
    data class PhaseProgress(
        val phase: GoalPhase,
        val tasks: TaskBreakdown,
        val milestones: MilestoneBreakdown,
        val taskCompletionRate: Int?,
        val readyForReview: Boolean?
    )

    /** Aggregated progress for one goal. */
    data class GoalProgress(
        val tasks: TaskBreakdown,
        val milestones: MilestoneBreakdown,
        val taskCompletionRate: Int?,
        val milestoneAchievementRate: Int?,
        val readyForReview: Boolean?
    )

    private fun GoalTask.isCompleted(): Boolean = status == GoalTaskStatus.COMPLETED.name
    private fun GoalTask.isSkipped(): Boolean = status == GoalTaskStatus.SKIPPED.name
    private fun GoalTask.isInProgress(): Boolean = status == GoalTaskStatus.IN_PROGRESS.name

    fun taskBreakdown(tasks: List<GoalTask>): TaskBreakdown = TaskBreakdown(
        total = tasks.size,
        completed = tasks.count { it.isCompleted() },
        inProgress = tasks.count { it.isInProgress() },
        skipped = tasks.count { it.isSkipped() },
        incomplete = tasks.count { it.status == GoalTaskStatus.NOT_STARTED.name }
    )

    fun milestoneBreakdown(milestones: List<GoalMilestone>): MilestoneBreakdown = MilestoneBreakdown(
        total = milestones.size,
        achieved = milestones.count { it.status == MilestoneStatus.ACHIEVED.name },
        pending = milestones.count { it.status == MilestoneStatus.PENDING.name }
    )

    /** Whole-percentage floor; `null` when there is nothing to measure. */
    fun completionRate(completed: Int, total: Int): Int? =
        if (total == 0) null else (completed * 100 / total)

    /**
     * Derived review-readiness suggestion: `true` only when the scope has at least one task, every
     * task is completed and every milestone (if any) is achieved. `null` when the scope has no
     * tasks and no milestones — nothing to review. Never a persisted state.
     */
    fun readyForReview(tasks: List<GoalTask>, milestones: List<GoalMilestone>): Boolean? {
        if (tasks.isEmpty() && milestones.isEmpty()) return null
        if (tasks.isEmpty()) return null // milestones alone carry their own achieved state
        val allTasksDone = tasks.all { it.isCompleted() }
        val allMilestonesAchieved = milestones.all { it.status == MilestoneStatus.ACHIEVED.name }
        return allTasksDone && allMilestonesAchieved
    }

    /** Aggregates one phase from its own tasks and milestones. */
    fun phaseProgress(phase: GoalPhase, tasks: List<GoalTask>, milestones: List<GoalMilestone>): PhaseProgress {
        val taskBreakdown = taskBreakdown(tasks)
        val milestoneBreakdown = milestoneBreakdown(milestones)
        return PhaseProgress(
            phase = phase,
            tasks = taskBreakdown,
            milestones = milestoneBreakdown,
            taskCompletionRate = completionRate(taskBreakdown.completed, taskBreakdown.total),
            readyForReview = readyForReview(tasks, milestones)
        )
    }

    /** Aggregates one goal from all of its tasks and milestones. */
    fun goalProgress(tasks: List<GoalTask>, milestones: List<GoalMilestone>): GoalProgress {
        val taskBreakdown = taskBreakdown(tasks)
        val milestoneBreakdown = milestoneBreakdown(milestones)
        return GoalProgress(
            tasks = taskBreakdown,
            milestones = milestoneBreakdown,
            taskCompletionRate = completionRate(taskBreakdown.completed, taskBreakdown.total),
            milestoneAchievementRate = completionRate(milestoneBreakdown.achieved, milestoneBreakdown.total),
            readyForReview = readyForReview(tasks, milestones)
        )
    }
}
