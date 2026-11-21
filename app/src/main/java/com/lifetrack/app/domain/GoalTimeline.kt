package com.lifetrack.app.domain

import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskDependency
import com.lifetrack.app.data.GoalTaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Deterministic, calendar-safe timeline semantics for Goal Roadmaps (Prompt 2).
 *
 * Every calculation works on `LocalDate` calendar arithmetic — never elapsed milliseconds — so
 * month lengths, leap years and DST transitions cannot drift a saved date to another day. The
 * caller supplies `today` once (the ViewModel's shared clock) and a `zone` used only to interpret
 * actual completion instants as local dates for "finished late" display; planning dates themselves
 * are never reinterpreted through any timezone.
 *
 * Definitions (each is deliberately explicit and test-covered):
 *  - OVERDUE: an uncompleted, unskipped task whose deadline is before [today]; days overdue is the
 *    exact calendar-day difference. A passing deadline never completes anything.
 *  - DUE_TODAY: uncompleted, unskipped, deadline == today.
 *  - UPCOMING: uncompleted, unskipped, deadline after today. UNSCHEDULED: no deadline at all.
 *  - COMPLETED tasks are never overdue; one that finished after its deadline is flagged
 *    `completedAfterDeadline` for honest display, never rewritten.
 *  - BLOCKED: a task that still needs work and has at least one prerequisite that also still needs
 *    work. A SKIPPED prerequisite satisfies its gate (an explicit user waiver) — a read-time policy
 *    Prompt 3 may revisit; nothing here ever writes a status.
 *  - TIMING CONFLICT: an edge A←B (B needs A first) where B committed to a planned start before A
 *    is scheduled to finish. Conflicts are displayed, never silently repaired.
 *  - Current phase: today falls inside its planned window. Overlapping windows are legal and all
 *    match; phase status is displayed alongside, never derived from the calendar.
 */
object GoalTimeline {

    enum class Urgency { OVERDUE, DUE_TODAY, UPCOMING, UNSCHEDULED }

    /** One task row of the timeline snapshot: persisted facts plus derived, defined display state. */
    data class TaskView(
        val task: GoalTask,
        val urgency: Urgency,
        val daysOverdue: Long,
        val blocked: Boolean,
        val blockingTaskTitles: List<String>,
        val timingConflict: Boolean,
        val completedAfterDeadline: Boolean
    )

    data class PhaseView(
        val phase: GoalPhase,
        val isCurrent: Boolean,
        val isPast: Boolean,
        val isFuture: Boolean,
        val totalTasks: Int,
        val completedTasks: Int
    )

    data class MilestoneView(
        val milestone: GoalMilestone,
        val upcoming: Boolean,
        val overdue: Boolean,
        val daysOverdue: Long
    )

    data class Snapshot(
        val goal: Goal,
        val phases: List<PhaseView>,
        val tasks: List<TaskView>,
        val milestones: List<MilestoneView>,
        val completedTasks: Int,
        val totalTasks: Int,
        val overdueTasks: Int,
        val dueTodayTasks: Int,
        val upcomingDeadlines: Int,
        val unscheduledTasks: Int
    )

    /** Uncompleted, unskipped tasks — the only tasks that carry urgency. */
    private fun GoalTask.needsWork(): Boolean =
        status != GoalTaskStatus.COMPLETED.name && status != GoalTaskStatus.SKIPPED.name

    fun completionDate(instant: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(instant).atZone(zone).toLocalDate()

    /**
     * Full snapshot for one goal. Tasks are returned ordered: overdue first, then due today,
     * upcoming, unscheduled; ties break by (sortOrder, id) so the display never depends on the
     * database's row order.
     */
    fun snapshot(goal: Goal, phases: List<GoalPhase>, milestones: List<GoalMilestone>, tasks: List<GoalTask>,
                 dependencies: List<GoalTaskDependency>, today: LocalDate, zone: ZoneId): Snapshot {
        val tasksById = tasks.associateBy { it.id }
        val titles = tasks.associate { it.id to it.title }
        // Prerequisite ids per dependent task; dependents per prerequisite for conflict checks.
        val prerequisites = dependencies.groupBy(GoalTaskDependency::dependentTaskId, GoalTaskDependency::prerequisiteTaskId)
        val statusById = tasks.associate { it.id to it.status }
        val blockedBy = tasks.associate { task ->
            val blocking = prerequisites[task.id].orEmpty()
                .mapNotNull { prerequisiteId -> tasksById[prerequisiteId] }
                .filter { it.needsWork() }
            task.id to blocking
        }
        val completedBy = dependencies.filter { edge ->
            statusById[edge.prerequisiteTaskId] in setOf(GoalTaskStatus.COMPLETED.name, GoalTaskStatus.SKIPPED.name)
        }.map { it.dependentTaskId }.toSet()

        val views = tasks.map { task ->
            val needsWork = task.needsWork()
            val urgency = when {
                !needsWork -> Urgency.UNSCHEDULED // completed/skipped rows carry no urgency display
                task.deadline == null -> Urgency.UNSCHEDULED
                task.deadline.isBefore(today) -> Urgency.OVERDUE
                task.deadline == today -> Urgency.DUE_TODAY
                else -> Urgency.UPCOMING
            }
            val blocking = blockedBy.getValue(task.id)
            val conflict = needsWork && !completedBy.contains(task.id) && task.plannedDate != null &&
                prerequisites[task.id].orEmpty().any { prerequisiteId ->
                    tasksById[prerequisiteId]?.let { prerequisite ->
                        prerequisite.needsWork() && prerequisite.deadline != null && task.plannedDate.isBefore(prerequisite.deadline)
                    } == true
                }
            TaskView(
                task = task,
                urgency = urgency,
                daysOverdue = if (urgency == Urgency.OVERDUE) ChronoUnit.DAYS.between(task.deadline, today) else 0,
                blocked = needsWork && blocking.isNotEmpty(),
                blockingTaskTitles = blocking.map { titles.getValue(it.id) },
                timingConflict = conflict,
                completedAfterDeadline = task.completedAt != null && task.deadline != null &&
                    completionDate(task.completedAt, zone).isAfter(task.deadline)
            )
        }.sortedWith(
            compareBy<TaskView> {
                when (it.urgency) { Urgency.OVERDUE -> 0; Urgency.DUE_TODAY -> 1; Urgency.UPCOMING -> 2; Urgency.UNSCHEDULED -> 3 }
            }.thenBy { it.task.sortOrder }.thenBy { it.task.id }
        )

        val tasksByPhase = tasks.groupBy { it.phaseId }
        val phaseViews = phases.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { phase ->
            val scoped = tasksByPhase[phase.id].orEmpty()
            PhaseView(
                phase = phase,
                isCurrent = phase.plannedStartDate != null && phase.plannedEndDate != null &&
                    !today.isBefore(phase.plannedStartDate) && !today.isAfter(phase.plannedEndDate),
                isPast = phase.plannedEndDate != null && phase.plannedEndDate.isBefore(today),
                isFuture = phase.plannedStartDate != null && phase.plannedStartDate.isAfter(today),
                totalTasks = scoped.size,
                completedTasks = scoped.count { it.status == GoalTaskStatus.COMPLETED.name }
            )
        }

        val milestoneViews = milestones.sortedWith(
            compareBy<GoalMilestone> { it.targetDate ?: LocalDate.MAX }.thenBy { it.createdAt }.thenBy { it.id }
        ).map { milestone ->
            val achieved = milestone.status == com.lifetrack.app.data.MilestoneStatus.ACHIEVED.name
            val overdue = !achieved && milestone.targetDate != null && milestone.targetDate.isBefore(today)
            MilestoneView(
                milestone = milestone,
                upcoming = !achieved && milestone.targetDate != null && !milestone.targetDate.isBefore(today),
                overdue = overdue,
                daysOverdue = if (overdue) ChronoUnit.DAYS.between(milestone.targetDate, today) else 0
            )
        }

        val pending = views.filter { it.task.needsWork() }
        return Snapshot(
            goal = goal,
            phases = phaseViews,
            tasks = views,
            milestones = milestoneViews,
            completedTasks = tasks.count { it.status == GoalTaskStatus.COMPLETED.name },
            totalTasks = tasks.size,
            overdueTasks = pending.count { it.urgency == Urgency.OVERDUE },
            dueTodayTasks = pending.count { it.urgency == Urgency.DUE_TODAY },
            upcomingDeadlines = pending.count { it.urgency == Urgency.UPCOMING },
            unscheduledTasks = pending.count { it.task.deadline == null }
        )
    }

    /**
     * Kahn's topological order over the dependency edges (O(V+E)). Tasks without edges keep their
     * (sortOrder, id) relative order; a cycle — which the repository rejects before persisting —
     * returns null rather than a partial order, so callers never render a misleading sequence.
     */
    fun topologicalOrder(tasks: List<GoalTask>, dependencies: List<GoalTaskDependency>): List<Long>? {
        val ordered = tasks.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val position = ordered.withIndex().associate { (index, task) -> task.id to index }
        val indegree = LinkedHashMap<Long, Int>()
        val dependents = LinkedHashMap<Long, MutableList<Long>>()
        ordered.forEach { indegree[it.id] = 0 }
        dependencies.forEach { edge ->
            if (edge.dependentTaskId in indegree && edge.prerequisiteTaskId in indegree) {
                indegree[edge.dependentTaskId] = (indegree[edge.dependentTaskId] ?: 0) + 1
                dependents.getOrPut(edge.prerequisiteTaskId) { mutableListOf() }.add(edge.dependentTaskId)
            }
        }
        // Seed with tasks in display order so equal-indegree tasks resolve deterministically.
        val ready = ArrayDeque(ordered.map { it.id }.filter { indegree.getValue(it) == 0 })
        val result = mutableListOf<Long>()
        while (ready.isNotEmpty()) {
            val current = ready.removeFirst()
            result.add(current)
            dependents[current].orEmpty().forEach { next ->
                val remaining = indegree.getValue(next) - 1
                indegree[next] = remaining
                if (remaining == 0) ready.addLast(next)
            }
        }
        return if (result.size == tasks.size) result else null
    }

    data class TaskShift(val taskId: Long, val plannedDate: LocalDate?, val deadline: LocalDate?, val title: String)

    /**
     * Preview for a batch shift: moving [moved] to its new dates shifts every transitively
     * dependent task that still needs work. Dependents move their planned date by the moved
     * task's planned delta, and their deadline by the moved task's deadline delta — or, when the
     * moved task carries no deadline, by the planned delta — so each dependent keeps its own
     * planned-to-deadline slack. Completed and skipped dependents keep their recorded dates;
     * nothing is written here — the repository applies the preview.
     */
    fun shiftPreview(moved: GoalTask, tasks: List<GoalTask>, dependencies: List<GoalTaskDependency>,
                     newPlannedDate: LocalDate?, newDeadline: LocalDate?): List<TaskShift> {
        val plannedDelta = if (moved.plannedDate != null && newPlannedDate != null)
            ChronoUnit.DAYS.between(moved.plannedDate, newPlannedDate) else null
        val deadlineDelta = if (moved.deadline != null && newDeadline != null)
            ChronoUnit.DAYS.between(moved.deadline, newDeadline) else null
        val byId = tasks.associateBy { it.id }
        val dependents = dependencies.groupBy(GoalTaskDependency::prerequisiteTaskId, GoalTaskDependency::dependentTaskId)
        val shifts = mutableListOf<TaskShift>()
        val visited = mutableSetOf(moved.id)
        val queue = ArrayDeque(listOf(moved.id))
        while (queue.isNotEmpty()) {
            dependents[queue.removeFirst()].orEmpty().forEach { dependentId ->
                if (visited.add(dependentId)) {
                    val dependent = byId[dependentId] ?: return@forEach
                    if (dependent.needsWork()) {
                        shifts.add(TaskShift(
                            taskId = dependent.id, title = dependent.title,
                            plannedDate = dependent.plannedDate?.let { date -> plannedDelta?.let(date::plusDays) ?: date },
                            deadline = dependent.deadline?.let { date ->
                                (deadlineDelta ?: plannedDelta)?.let(date::plusDays) ?: date
                            }
                        ))
                    }
                    queue.addLast(dependentId)
                }
            }
        }
        return shifts
    }

    /** Original planned dates by record uuid, recovered from the earliest revision of each
     *  "plannedDate" history. Consumed by the detail markers and the Move dialog. */
    fun originalPlannedDates(history: List<com.lifetrack.app.data.GoalScheduleRevision>): Map<String, LocalDate> =
        history.filter { it.field == "plannedDate" && it.formerDate != null }
            .groupBy { it.entityUuid }
            .mapValues { (_, rows) -> rows.minByOrNull { it.changedAt }!!.formerDate!! }

    /** True when making [candidateId] a prerequisite of [dependentTaskId] would close a cycle
     *  (the candidate already depends, transitively, on the dependent). Used to pre-filter the
     *  editor's candidate list so the repository's rejection becomes unreachable from the UI. */
    fun wouldCreateCycle(dependentTaskId: Long, candidateId: Long, dependencies: List<GoalTaskDependency>): Boolean {
        val dependsOn = dependencies.groupBy(GoalTaskDependency::dependentTaskId, GoalTaskDependency::prerequisiteTaskId)
        val visited = mutableSetOf(candidateId)
        val queue = ArrayDeque(listOf(candidateId))
        while (queue.isNotEmpty()) {
            dependsOn[queue.removeFirst()].orEmpty().forEach { next ->
                if (next == dependentTaskId) return true
                if (visited.add(next)) queue.addLast(next)
            }
        }
        return false
    }
}
