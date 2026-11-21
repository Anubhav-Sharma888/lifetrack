package com.lifetrack.app.domain

import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalScheduleRevision
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskDependency
import com.lifetrack.app.data.GoalTaskStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calendar-correct timeline semantics: overdue/due/upcoming classifications, phase windows,
 * dependency blocking, timing conflicts, topological order and batch shift previews — all against
 * fixed dates so results never depend on the device clock or timezone.
 */
class GoalTimelineTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 21)
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun goal() = Goal(id = 1, uuid = "g", title = "Learn ML", plannedStartDate = today.minusWeeks(2),
        targetDate = today.plusWeeks(24), status = "ACTIVE", createdAt = 100, updatedAt = 200)

    private fun phase(id: Long, start: LocalDate?, end: LocalDate?, order: Int = 0, status: String = "ACTIVE") =
        GoalPhase(id = id, uuid = "p$id", goalId = 1, name = "Phase $id", sortOrder = order,
            plannedStartDate = start, plannedEndDate = end, status = status, createdAt = 100, updatedAt = 200)

    private fun milestone(id: Long, deadline: LocalDate?, achieved: Boolean = false) = GoalMilestone(
        id = id, uuid = "m$id", goalId = 1, title = "Milestone $id", targetDate = deadline,
        status = if (achieved) "ACHIEVED" else "PENDING", achievedAt = if (achieved) 500L else null,
        createdAt = 100, updatedAt = 200
    )

    private fun task(id: Long, deadline: LocalDate? = null, planned: LocalDate? = null, status: String = "NOT_STARTED",
        order: Int = 0, phaseId: Long? = null, completedAt: Long? = null) = GoalTask(
        id = id, uuid = "t$id", goalId = 1, phaseId = phaseId, title = "Task $id", sortOrder = order,
        plannedDate = planned, deadline = deadline, status = status, completedAt = completedAt,
        createdAt = 100, updatedAt = 200
    )

    private fun snapshot(tasks: List<GoalTask>, phases: List<GoalPhase> = emptyList(),
        milestones: List<GoalMilestone> = emptyList(), dependencies: List<GoalTaskDependency> = emptyList()) =
        GoalTimeline.snapshot(goal(), phases, milestones, tasks, dependencies, today, zone)

    // ---- Task urgency classifications ----

    @Test fun `overdue tasks count exact calendar days including month boundaries`() {
        // Deadline Oct 31, today Nov 21: 21 days across the October/November boundary.
        val view = snapshot(listOf(task(1, deadline = LocalDate.of(2026, 10, 31)))).tasks.single()
        assertEquals(GoalTimeline.Urgency.OVERDUE, view.urgency)
        assertEquals(21L, view.daysOverdue)
    }

    @Test fun `leap year boundaries count exact days`() {
        // Feb 28 2028 (leap year) to Mar 1 2028 is two calendar days: Feb 29 exists.
        val leapToday = LocalDate.of(2028, 3, 1)
        val view = GoalTimeline.snapshot(goal(), emptyList(), emptyList(),
            listOf(task(1, deadline = LocalDate.of(2028, 2, 28))), emptyList(), leapToday, zone).tasks.single()
        assertEquals(2L, view.daysOverdue)
    }

    @Test fun `due today is distinct from overdue and upcoming`() {
        val views = snapshot(listOf(
            task(1, deadline = today),
            task(2, deadline = today.plusDays(1)),
            task(3, deadline = today.minusDays(3))
        )).tasks
        assertEquals(GoalTimeline.Urgency.DUE_TODAY, views.first { it.task.id == 1L }.urgency)
        assertEquals(GoalTimeline.Urgency.UPCOMING, views.first { it.task.id == 2L }.urgency)
        assertEquals(GoalTimeline.Urgency.OVERDUE, views.first { it.task.id == 3L }.urgency)
        assertEquals(3L, views.first { it.task.id == 3L }.daysOverdue)
    }

    @Test fun `tasks without deadlines are unscheduled and never overdue`() {
        val view = snapshot(listOf(task(1))).tasks.single()
        assertEquals(GoalTimeline.Urgency.UNSCHEDULED, view.urgency)
        assertEquals(0L, view.daysOverdue)
    }

    @Test fun `completed and skipped tasks never carry urgency even past deadlines`() {
        val views = snapshot(listOf(
            task(1, deadline = today.minusDays(5), status = GoalTaskStatus.COMPLETED.name, completedAt = 900),
            task(2, deadline = today.minusDays(5), status = GoalTaskStatus.SKIPPED.name)
        )).tasks
        views.forEach { assertEquals(GoalTimeline.Urgency.UNSCHEDULED, it.urgency) }
    }

    @Test fun `a completion after its deadline is flagged but never rewritten`() {
        // Deadline Nov 10, completed Nov 13 (epoch millis in UTC zone): three days late.
        val completedAt = LocalDate.of(2026, 11, 13).atStartOfDay(zone).toInstant().toEpochMilli()
        val view = snapshot(listOf(task(1, deadline = LocalDate.of(2026, 11, 10),
            status = GoalTaskStatus.COMPLETED.name, completedAt = completedAt))).tasks.single()
        assertTrue(view.completedAfterDeadline)
        val onTime = snapshot(listOf(task(2, deadline = LocalDate.of(2026, 11, 13),
            status = GoalTaskStatus.COMPLETED.name, completedAt = completedAt))).tasks.single()
        assertFalse(onTime.completedAfterDeadline)
    }

    @Test fun `tasks sort by urgency then their own order`() {
        val views = snapshot(listOf(
            task(5, deadline = today.plusDays(2), order = 1),
            task(4, deadline = today, order = 0),
            task(3, deadline = today.minusDays(1), order = 1),
            task(2, deadline = today.minusDays(2), order = 0),
            task(1)
        )).tasks
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), views.map { it.task.id })
        assertEquals(2, snapshot(listOf(task(2, deadline = today.minusDays(2)), task(3, deadline = today.minusDays(1)))).overdueTasks)
    }

    // ---- Snapshot counters (honest counts only) ----

    @Test fun `snapshot counters summarize the roadmap honestly`() {
        val state = snapshot(listOf(
            task(1, deadline = today.minusDays(1)),                                    // overdue
            task(2, deadline = today),                                                 // due today
            task(3, deadline = today.plusDays(3)),                                     // upcoming
            task(4, status = GoalTaskStatus.COMPLETED.name, completedAt = 900),        // completed
            task(5, status = GoalTaskStatus.SKIPPED.name),                             // skipped
            task(6)                                                                     // unscheduled
        ))
        assertEquals(6, state.totalTasks)
        assertEquals(1, state.completedTasks)
        assertEquals(1, state.overdueTasks)
        assertEquals(1, state.dueTodayTasks)
        assertEquals(1, state.upcomingDeadlines)
        // Skipped needs no work; only the dateless open task is unscheduled.
        assertEquals(1, state.unscheduledTasks)
    }

    // ---- Phase windows ----

    @Test fun `one phase gets its window and current flag`() {
        val view = snapshot(emptyList(), phases = listOf(
            phase(1, today.minusDays(3), today.plusDays(3))
        )).phases.single()
        assertTrue(view.isCurrent)
        assertFalse(view.isPast)
        assertFalse(view.isFuture)
    }

    @Test fun `sequential phases classify past current and future`() {
        val views = snapshot(emptyList(), phases = listOf(
            phase(1, today.minusWeeks(8), today.minusWeeks(3), order = 0),
            phase(2, today.minusDays(2), today.plusDays(5), order = 1),
            phase(3, today.plusWeeks(3), today.plusWeeks(9), order = 2)
        )).phases
        assertTrue(views[0].isPast)
        assertTrue(views[1].isCurrent)
        assertTrue(views[2].isFuture)
        assertEquals(0, views[0].completedTasks)
    }

    @Test fun `overlapping phases both report current without invalidating each other`() {
        val views = snapshot(emptyList(), phases = listOf(
            phase(1, today.minusWeeks(2), today.plusWeeks(4), order = 0),
            phase(2, today.minusDays(1), today.plusWeeks(6), order = 1)
        )).phases
        assertTrue(views.all { it.isCurrent })
    }

    @Test fun `phase task counters stay scoped to their own phase`() {
        val views = snapshot(listOf(
            task(1, phaseId = 1, status = GoalTaskStatus.COMPLETED.name, completedAt = 900),
            task(2, phaseId = 1),
            task(3, phaseId = 2),
            task(4)
        ), phases = listOf(phase(1, today.minusDays(1), today.plusDays(1)), phase(2, today.plusDays(1), today.plusDays(2)))).phases
        assertEquals(2 to 1, views[0].totalTasks to views[0].completedTasks)
        assertEquals(1 to 0, views[1].totalTasks to views[1].completedTasks)
    }

    @Test fun `phases without dates are neither current past nor future`() {
        val view = snapshot(emptyList(), phases = listOf(phase(1, null, null))).phases.single()
        assertFalse(view.isCurrent)
        assertFalse(view.isPast)
        assertFalse(view.isFuture)
    }

    // ---- Milestones ----

    @Test fun `milestones classify upcoming overdue and achieved`() {
        val views = snapshot(emptyList(), milestones = listOf(
            milestone(1, today.plusDays(4)),
            milestone(2, today.minusDays(4)),
            milestone(3, today.minusDays(9), achieved = true),
            milestone(4, null)
        )).milestones
        val byId = views.associateBy { it.milestone.id }
        assertTrue(byId.getValue(1L).upcoming && !byId.getValue(1L).overdue)
        assertTrue(byId.getValue(2L).overdue)
        assertEquals(4L, byId.getValue(2L).daysOverdue)
        assertFalse(byId.getValue(3L).upcoming || byId.getValue(3L).overdue)
        assertFalse(byId.getValue(4L).upcoming || byId.getValue(4L).overdue)
        // Deadlines sort chronologically (Nov 12 before Nov 17), then dateless milestones anchor the end.
        assertEquals(listOf(3L, 2L, 1L, 4L), views.map { it.milestone.id })
    }

    // ---- Dependencies: blocking and conflicts ----

    @Test fun `incomplete prerequisites block and completed or skipped ones do not`() {
        val state = snapshot(listOf(
            task(1, status = GoalTaskStatus.COMPLETED.name, completedAt = 900),
            task(2, status = GoalTaskStatus.SKIPPED.name),
            task(3, status = GoalTaskStatus.IN_PROGRESS.name),
            task(4),
            task(5),
            task(6)
        ), dependencies = listOf(
            GoalTaskDependency(5, 1), // completed prerequisite: satisfied by completion
            GoalTaskDependency(6, 2), // skipped prerequisite: satisfied as an intentional bypass
            GoalTaskDependency(4, 3)  // in-progress prerequisite: still blocking
        ))
        assertFalse(state.tasks.first { it.task.id == 5L }.blocked)
        assertFalse(state.tasks.first { it.task.id == 6L }.blocked)
        val blocked = state.tasks.first { it.task.id == 4L }
        assertTrue(blocked.blocked)
        assertEquals(listOf("Task 3"), blocked.blockingTaskTitles)
        // The gate policy distinguishes completion from intentional bypass explicitly.
        val gates = state.tasks.associate { it.task.id to it.prerequisites.map { p -> p.gate } }
        assertEquals(listOf(GoalTimeline.PrerequisiteGate.SATISFIED_COMPLETED), gates.getValue(5L))
        assertEquals(listOf(GoalTimeline.PrerequisiteGate.SATISFIED_BYPASSED), gates.getValue(6L))
        assertEquals(listOf(GoalTimeline.PrerequisiteGate.PENDING), gates.getValue(4L))
    }

    @Test fun `timing conflicts flag a dependent planned before its prerequisite finishes`() {
        val state = snapshot(listOf(
            task(1, deadline = LocalDate.of(2026, 11, 30)),
            task(2, planned = LocalDate.of(2026, 11, 25))
        ), dependencies = listOf(GoalTaskDependency(2, 1)))
        assertTrue(state.tasks.first { it.task.id == 2L }.timingConflict)
        // Same dates without an edge: no conflict.
        val plain = snapshot(listOf(task(1, deadline = LocalDate.of(2026, 11, 30)), task(2, planned = LocalDate.of(2026, 11, 25))))
        assertFalse(plain.tasks.first { it.task.id == 2L }.timingConflict)
    }

    @Test fun `completed tasks never conflict and completed prerequisites cannot conflict`() {
        val completed = task(1, deadline = LocalDate.of(2026, 11, 30), status = GoalTaskStatus.COMPLETED.name, completedAt = 900)
        val state = snapshot(listOf(completed, task(2, planned = LocalDate.of(2026, 11, 25))),
            dependencies = listOf(GoalTaskDependency(2, 1)))
        // Both sides resolved: the dependent is completed and the prerequisite is satisfied.
        assertFalse(state.tasks.first { it.task.id == 2L }.timingConflict)
    }

    // ---- Topological order ----

    @Test fun `topological order is deterministic and honors dependencies`() {
        val tasks = listOf(task(3, order = 1), task(1, order = 0), task(2, order = 2))
        val order = GoalTimeline.topologicalOrder(tasks, listOf(
            GoalTaskDependency(2, 1), // 2 needs 1
            GoalTaskDependency(3, 2)  // 3 needs 2
        ))!!
        assertEquals(listOf(1L, 2L, 3L), order)
    }

    @Test fun `tasks without edges keep their sort order`() {
        val tasks = listOf(task(3, order = 2), task(1, order = 0), task(2, order = 1))
        assertEquals(listOf(1L, 2L, 3L), GoalTimeline.topologicalOrder(tasks, emptyList()))
    }

    @Test fun `a cycle yields no order instead of a misleading partial one`() {
        val tasks = listOf(task(1), task(2))
        assertNull(GoalTimeline.topologicalOrder(tasks, listOf(GoalTaskDependency(1, 2), GoalTaskDependency(2, 1))))
    }

    // ---- Batch shift preview ----

    @Test fun `shift preview moves transitive dependents by the same delta and skips completed ones`() {
        val tasks = listOf(
            task(1, planned = today, deadline = today.plusDays(2)),
            task(2, planned = today.plusDays(1), deadline = today.plusDays(3)),
            task(3, planned = today.plusDays(2), deadline = today.plusDays(4), status = GoalTaskStatus.IN_PROGRESS.name),
            task(4, planned = today.plusDays(3), deadline = today.plusDays(5), status = GoalTaskStatus.COMPLETED.name, completedAt = 900)
        )
        val shifts = GoalTimeline.shiftPreview(tasks[0], tasks, listOf(
            GoalTaskDependency(2, 1),
            GoalTaskDependency(3, 2)
        ), newPlannedDate = today.plusDays(5), newDeadline = today.plusDays(7))
        // Task 4 is completed: excluded. Tasks 2 and 3 shift by the moved task's +5-day delta.
        assertEquals(listOf(2L, 3L), shifts.map { it.taskId })
        assertEquals(today.plusDays(6), shifts[0].plannedDate)
        assertEquals(today.plusDays(8), shifts[0].deadline)
        assertEquals(today.plusDays(7), shifts[1].plannedDate)
        assertEquals(today.plusDays(9), shifts[1].deadline)
    }

    @Test fun `shift preview preserves each dependent slack when the moved task has no deadline`() {
        val tasks = listOf(
            task(1, planned = today),                          // no deadline
            task(2, planned = today.plusDays(1), deadline = today.plusDays(9))
        )
        val shifts = GoalTimeline.shiftPreview(tasks[0], tasks, listOf(GoalTaskDependency(2, 1)),
            newPlannedDate = today.plusDays(4), newDeadline = null)
        val shift = shifts.single()
        assertEquals(today.plusDays(5), shift.plannedDate)
        // Deadline shifts by the planned delta, keeping the dependent's 8-day slack.
        assertEquals(today.plusDays(13), shift.deadline)
    }

    @Test fun `original planned dates come from the earliest revision per record`() {
        val revisions = listOf(
            GoalScheduleRevision(entityType = "TASK", entityUuid = "t1", field = "plannedDate",
                formerDate = today, newDate = today.plusDays(2), scope = "SINGLE", changedAt = 100),
            GoalScheduleRevision(entityType = "TASK", entityUuid = "t1", field = "plannedDate",
                formerDate = today.plusDays(2), newDate = today.plusDays(9), scope = "SHIFT_DEPENDENTS", changedAt = 300),
            GoalScheduleRevision(entityType = "TASK", entityUuid = "t2", field = "deadline",
                formerDate = today, newDate = today.plusDays(5), scope = "SINGLE", changedAt = 400)
        )
        val originals = GoalTimeline.originalPlannedDates(revisions)
        assertEquals(today, originals["t1"])
        assertFalse(originals.containsKey("t2")) // deadline revisions never feed planned-date origins
        assertTrue(GoalTimeline.originalPlannedDates(emptyList()).isEmpty())
    }

    @Test fun `cycle pre-filter flags candidates that already depend on the task`() {
        // Edges: 1 needs 2 first; 2 needs 3 first (1 <- 2 <- 3).
        val edges = listOf(GoalTaskDependency(1, 2), GoalTaskDependency(2, 3))
        // Offering 1 as a prerequisite of 3 closes 3 <- 2 <- 1... a cycle.
        assertTrue(GoalTimeline.wouldCreateCycle(3, 1, edges))
        // Offering 1 as a prerequisite of 2 closes 2 <- 1 <- 2 — a cycle.
        assertTrue(GoalTimeline.wouldCreateCycle(2, 1, edges))
        // Offering 3 as a prerequisite of 1 merely extends the chain downstream — legal.
        assertFalse(GoalTimeline.wouldCreateCycle(1, 3, edges))
        // Offering 2 where 1 already needs it is a duplicate edge, not a cycle — legal here
        // (the editor filters existing prerequisites separately).
        assertFalse(GoalTimeline.wouldCreateCycle(1, 2, edges))
    }

    // ---- Scale sanity: a long roadmap classifies in one pass ----

    @Test fun `a long roadmap with many tasks classifies without deep recursion`() {
        val tasks = (1..600).map { index ->
            task(index.toLong(), deadline = today.plusDays(index % 40L - 10L), order = index)
        }
        val edges = (2..600).map { GoalTaskDependency(it.toLong(), (it - 1).toLong()) } // linear chain, no cycle
        val state = snapshot(tasks, dependencies = edges)
        assertEquals(600, state.totalTasks)
        // Residues 0..9 give deadlines up to ten days in the past: 10 of every 40 tasks are overdue.
        assertEquals(150, state.overdueTasks)
        assertEquals(listOf(1L, 2L, 3L), GoalTimeline.topologicalOrder(tasks, edges)!!.take(3))
    }
}
