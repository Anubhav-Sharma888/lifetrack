package com.lifetrack.app.domain

import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskStatus
import com.lifetrack.app.data.MilestoneStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honest progress mathematics: skipped work never inflates completion, empty scopes are "not yet
 * measurable" (null, never 100%), and milestones count only their explicit verification state.
 */
class GoalProgressTest {

    private fun task(status: GoalTaskStatus) = GoalTask(
        id = 1, uuid = "t", goalId = 1, title = "Task", status = status.name,
        createdAt = 100, updatedAt = 200
    )

    private fun milestone(status: MilestoneStatus) = GoalMilestone(
        id = 1, uuid = "m", goalId = 1, title = "Milestone", status = status.name,
        achievedAt = if (status == MilestoneStatus.ACHIEVED) 500L else null,
        createdAt = 100, updatedAt = 200
    )

    @Test fun `zero tasks are not yet measurable rather than complete`() {
        assertNull(GoalProgress.completionRate(0, 0))
        assertNull(GoalProgress.goalProgress(emptyList(), emptyList()).taskCompletionRate)
    }

    @Test fun `the prompt example keeps skipped work in the denominator`() {
        // 20 total: 12 completed, 4 skipped, 4 incomplete (in progress + not started mix).
        val tasks = listOf(
            task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED),
            task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED),
            task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED),
            task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED),
            task(GoalTaskStatus.SKIPPED), task(GoalTaskStatus.SKIPPED), task(GoalTaskStatus.SKIPPED),
            task(GoalTaskStatus.SKIPPED),
            task(GoalTaskStatus.IN_PROGRESS), task(GoalTaskStatus.IN_PROGRESS),
            task(GoalTaskStatus.NOT_STARTED), task(GoalTaskStatus.NOT_STARTED)
        )
        val breakdown = GoalProgress.taskBreakdown(tasks)
        assertEquals(20, breakdown.total)
        assertEquals(12, breakdown.completed)
        assertEquals(4, breakdown.skipped)
        assertEquals(2, breakdown.inProgress)
        assertEquals(2, breakdown.incomplete)
        assertEquals(4, breakdown.notDone)
        assertEquals(60, GoalProgress.goalProgress(tasks, emptyList()).taskCompletionRate)
    }

    @Test fun `all completed is one hundred percent and ready for review`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.COMPLETED)), emptyList())
        assertEquals(100, progress.taskCompletionRate)
        assertEquals(true, progress.readyForReview)
    }

    @Test fun `all skipped claims zero completion never full credit`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.SKIPPED), task(GoalTaskStatus.SKIPPED)), emptyList())
        assertEquals(0, progress.taskCompletionRate)
        assertEquals(2, progress.tasks.skipped)
        assertEquals(false, progress.readyForReview)
    }

    @Test fun `a single task measures fifty percent when in progress exists`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.IN_PROGRESS)), emptyList())
        assertEquals(50, progress.taskCompletionRate)
    }

    @Test fun `milestones without tasks leave readiness undefined`() {
        val progress = GoalProgress.goalProgress(emptyList(), listOf(milestone(MilestoneStatus.PENDING)))
        assertNull(progress.taskCompletionRate)
        assertNull(progress.readyForReview)
        assertEquals(0, progress.milestones.achieved)
        assertEquals(1, progress.milestones.pending)
        assertNull(GoalProgress.goalProgress(emptyList(), emptyList()).milestoneAchievementRate)
    }

    @Test fun `achieved milestones are counted only from explicit verification`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.COMPLETED)),
            listOf(milestone(MilestoneStatus.ACHIEVED), milestone(MilestoneStatus.PENDING))
        )
        assertEquals(1, progress.milestones.achieved)
        assertEquals(1, progress.milestones.pending)
        assertEquals(50, progress.milestoneAchievementRate)
        // The pending milestone keeps the goal from being "ready for review".
        assertEquals(false, progress.readyForReview)
    }

    @Test fun `all milestones achieved with all tasks complete suggests review`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.COMPLETED)),
            listOf(milestone(MilestoneStatus.ACHIEVED))
        )
        assertEquals(true, progress.readyForReview)
    }

    @Test fun `milestones without tasks are never auto-suggested complete`() {
        val progress = GoalProgress.goalProgress(emptyList(), listOf(milestone(MilestoneStatus.ACHIEVED)))
        assertNull(progress.readyForReview)
        assertEquals(1, progress.milestones.achieved)
    }

    @Test fun `phase aggregation stays scoped to its own records`() {
        val phase = GoalPhase(id = 1, uuid = "p", goalId = 1, name = "Phase", createdAt = 1, updatedAt = 2)
        val other = GoalPhase(id = 2, uuid = "q", goalId = 1, name = "Other", createdAt = 1, updatedAt = 2)
        val tasks = listOf(
            GoalTask(id = 1, uuid = "a", goalId = 1, phaseId = 1, title = "A", status = GoalTaskStatus.COMPLETED.name, createdAt = 1, updatedAt = 2),
            GoalTask(id = 2, uuid = "b", goalId = 1, phaseId = 1, title = "B", status = GoalTaskStatus.SKIPPED.name, createdAt = 1, updatedAt = 2),
            GoalTask(id = 3, uuid = "c", goalId = 1, phaseId = 2, title = "C", status = GoalTaskStatus.COMPLETED.name, createdAt = 1, updatedAt = 2)
        )
        val milestones = listOf(
            GoalMilestone(id = 1, uuid = "m1", goalId = 1, phaseId = 1, title = "M", status = MilestoneStatus.ACHIEVED.name, achievedAt = 5, createdAt = 1, updatedAt = 2)
        )
        val progress = GoalProgress.phaseProgress(phase, tasks.filter { it.phaseId == 1L }, milestones.filter { it.phaseId == 1L })
        assertEquals(2, progress.tasks.total)
        assertEquals(1, progress.tasks.completed)
        assertEquals(1, progress.tasks.skipped)
        assertEquals(50, progress.taskCompletionRate)
        assertEquals(1, progress.milestones.achieved)
        // A skipped task is not a completed one: the phase is not ready for review.
        assertEquals(false, progress.readyForReview)
        // The other phase has no records of its own: not measurable, never borrowed.
        val empty = GoalProgress.phaseProgress(other, emptyList(), emptyList())
        assertNull(empty.taskCompletionRate)
        assertNull(empty.readyForReview)
        assertEquals(0, empty.tasks.total)
    }

    @Test fun `tasks without phases or milestones still aggregate at goal level`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.COMPLETED), task(GoalTaskStatus.NOT_STARTED)), emptyList())
        assertEquals(2, progress.tasks.total)
        assertEquals(50, progress.taskCompletionRate)
    }

    @Test fun `reopened tasks count as incomplete again`() {
        val progress = GoalProgress.goalProgress(
            listOf(task(GoalTaskStatus.NOT_STARTED), task(GoalTaskStatus.COMPLETED)), emptyList())
        assertEquals(1, progress.tasks.completed)
        assertEquals(1, progress.tasks.incomplete)
        assertEquals(50, progress.taskCompletionRate)
        assertEquals(false, progress.readyForReview)
    }

    @Test fun `a large plan aggregates without special casing`() {
        val tasks = (1..2000).map { index ->
            task(when (index % 4) {
                0 -> GoalTaskStatus.COMPLETED
                1 -> GoalTaskStatus.COMPLETED
                2 -> GoalTaskStatus.SKIPPED
                else -> GoalTaskStatus.NOT_STARTED
            })
        }
        val progress = GoalProgress.goalProgress(tasks, emptyList())
        assertEquals(2000, progress.tasks.total)
        assertEquals(1000, progress.tasks.completed)
        assertEquals(500, progress.tasks.skipped)
        assertEquals(500, progress.tasks.incomplete)
        assertEquals(50, progress.taskCompletionRate)
    }
}
