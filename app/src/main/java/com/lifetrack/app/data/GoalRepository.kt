package com.lifetrack.app.data

import androidx.room.withTransaction
import com.lifetrack.app.domain.GoalTimeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All goal-roadmap mutations pass through here: the same validation, state machines and explicit
 * delete orders apply no matter which surface asks for the change. Every multi-table write runs in
 * one Room transaction — a crashed write can never leave a half-built goal tree behind.
 *
 * Room enforces the declared foreign keys at runtime; every parent/child delete below still
 * removes its children explicitly, in dependency-safe order, so the semantics stay observable and
 * the ordering deterministic regardless of connection settings. The declared cascades (schema 11)
 * remain the integrity contract Room validates at open time and the migration test pins as DDL.
 *
 * Records never complete themselves: a passing deadline, a parent's status and a dependency gate
 * never write a status here. Statuses move only through [GoalTransitions] on explicit requests,
 * and completion instants are reconciled with the status on every save.
 */
@Singleton
class GoalRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.goalDao()

    val goals: Flow<List<Goal>> = dao.observeGoals()
    fun phases(goalId: Long): Flow<List<GoalPhase>> = dao.observePhasesForGoal(goalId)
    fun milestones(goalId: Long): Flow<List<GoalMilestone>> = dao.observeMilestonesForGoal(goalId)
    fun tasks(goalId: Long): Flow<List<GoalTask>> = dao.observeTasksForGoal(goalId)
    fun dependencies(goalId: Long): Flow<List<GoalTaskDependency>> = dao.observeDependenciesForGoal(goalId)
    fun scheduleHistory(goalId: Long): Flow<List<GoalScheduleRevision>> = dao.observeRevisionsForGoal(goalId)
    // Hub summaries read one query for every goal — never per-goal N+1 reads.
    val allTasks: Flow<List<GoalTask>> = dao.observeAllTasks()
    val allPhases: Flow<List<GoalPhase>> = dao.observeAllPhases()

    // ---- Goals ----

    /** [recordScheduleHistory] writes revision rows for changed schedule fields (the editor's
     *  Save button sets it; plain programmatic saves stay revision-free). Comparison happens
     *  against the pre-save row inside the transaction, so the editor path records history too. */
    suspend fun saveGoal(goal: Goal, recordScheduleHistory: Boolean = false): Goal = db.withTransaction {
        val today = LocalDate.now()
        val existing = if (goal.id == 0L) null else dao.goal(goal.id)
        require(goal.id == 0L || existing != null) { "This goal no longer exists." }
        require(goal.status in GoalStatus.entries.map(GoalStatus::name)) { "Unknown goal status." }
        // Identity is minted once on create and pinned to the stored row afterward: a draft can
        // never re-identify an existing record, because the future partial Goal Plan import
        // matches records by these uuids.
        val uuid = existing?.uuid ?: goal.uuid.ifBlank { UUID.randomUUID().toString() }
        val uuidOwner = dao.goalByUuid(uuid)
        require(uuidOwner == null || uuidOwner.id == goal.id) { "Another goal already uses this identifier." }
        val nextStatus = GoalStatus.fromStored(goal.status)
        if (existing == null) {
            require(nextStatus == GoalStatus.PLANNED) { "New goals start as PLANNED." }
            require(dao.allGoals().size < GoalValidator.MAX_GOALS) {
                "LifeTrack cannot hold more than %,d goals.".format(java.util.Locale.ROOT, GoalValidator.MAX_GOALS)
            }
        } else {
            val currentStatus = GoalStatus.fromStored(existing.status)
            require(GoalTransitions.legal(GoalTransitions.goals, currentStatus, nextStatus)) {
                "A $currentStatus goal cannot move to $nextStatus."
            }
        }
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val completedAt = when {
            nextStatus == GoalStatus.COMPLETED -> existing?.completedAt ?: now
            else -> null
        }
        val normalized = goal.copy(
            uuid = uuid,
            title = goal.title.trim(), description = goal.description?.trim()?.takeIf { it.isNotEmpty() },
            category = goal.category.trim(),
            status = nextStatus.name, completedAt = completedAt,
            // Archiving has its own operation so a form edit can never rewrite archive history.
            isArchived = existing?.isArchived ?: false,
            archivedOn = existing?.archivedOn,
            sortOrder = existing?.sortOrder ?: ((dao.allGoals().maxOfOrNull { it.sortOrder } ?: -1) + 1),
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        GoalValidator.validateGoal(normalized, today)
        if (existing != null && recordScheduleHistory && existing.targetDate != normalized.targetDate) {
            dao.insertScheduleRevisions(listOf(revision(
                GoalScheduleEntityType.GOAL, normalized.uuid, "targetDate",
                existing.targetDate, normalized.targetDate, "SINGLE", now)))
        }
        if (existing == null) normalized.copy(id = dao.insertGoal(normalized)) else { dao.updateGoal(normalized); normalized }
    }

    suspend fun archiveGoal(id: Long, archived: Boolean) = db.withTransaction {
        val goal = dao.goal(id) ?: throw IllegalArgumentException("This goal no longer exists.")
        if (goal.isArchived == archived) return@withTransaction
        dao.updateGoal(goal.copy(
            isArchived = archived, archivedOn = if (archived) LocalDate.now() else null,
            updatedAt = nextTimestamp(System.currentTimeMillis(), goal.createdAt, goal.updatedAt)
        ))
    }

    /** Removes the goal and its whole tree. History and children go first, explicitly, in one transaction. */
    suspend fun deleteGoal(id: Long) = db.withTransaction {
        require(dao.goal(id) != null) { "This goal has already been deleted." }
        // Revision cleanup precedes the child deletes: the history query resolves uuids through
        // the (still-present) child rows. Link rows are removed explicitly too (the habit side
        // of the association relies on its declared CASCADE).
        dao.deleteScheduleRevisionsForGoal(id)
        dao.deleteGoalHabitLinksForGoal(id)
        dao.deleteDependenciesForGoal(id)
        dao.deleteTasksForGoal(id)
        dao.deleteMilestonesForGoal(id)
        dao.deletePhasesForGoal(id)
        dao.deleteGoalById(id)
    }

    // ---- Phases ----

    /** [recordScheduleHistory] writes revision rows for changed schedule fields (see [saveGoal]). */
    suspend fun savePhase(phase: GoalPhase, recordScheduleHistory: Boolean = false): GoalPhase = db.withTransaction {
        val today = LocalDate.now()
        val existing = if (phase.id == 0L) null else dao.phase(phase.id)
        require(phase.id == 0L || existing != null) { "This phase no longer exists." }
        require(dao.goal(phase.goalId) != null) { "A phase belongs to a missing goal." }
        if (existing != null) require(existing.goalId == phase.goalId) { "A phase cannot move between goals." }
        require(phase.status in PhaseStatus.entries.map(PhaseStatus::name)) { "Unknown phase status." }
        val uuid = existing?.uuid ?: phase.uuid.ifBlank { UUID.randomUUID().toString() }
        val uuidOwner = dao.phaseByUuid(uuid)
        require(uuidOwner == null || uuidOwner.id == phase.id) { "Another phase already uses this identifier." }
        val nextStatus = PhaseStatus.fromStored(phase.status)
        if (existing == null) {
            require(nextStatus == PhaseStatus.PLANNED) { "New phases start as PLANNED." }
            // The backup layer validates the same totals: the database can never grow past what
            // an export would accept, so the safety net cannot be outgrown through the UI.
            require(dao.allPhases().size < GoalValidator.MAX_PHASES) {
                "LifeTrack cannot hold more than %,d phases.".format(java.util.Locale.ROOT, GoalValidator.MAX_PHASES)
            }
        } else {
            val currentStatus = PhaseStatus.fromStored(existing.status)
            require(GoalTransitions.legal(GoalTransitions.phases, currentStatus, nextStatus)) {
                "A $currentStatus phase cannot move to $nextStatus."
            }
        }
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val completedAt = if (nextStatus == PhaseStatus.COMPLETED) existing?.completedAt ?: now else null
        val normalized = phase.copy(
            uuid = uuid,
            name = phase.name.trim(), description = phase.description?.trim()?.takeIf { it.isNotEmpty() },
            status = nextStatus.name, completedAt = completedAt,
            sortOrder = existing?.sortOrder ?: ((dao.phasesForGoal(phase.goalId).maxOfOrNull { it.sortOrder } ?: -1) + 1),
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        GoalValidator.validatePhase(normalized, setOf(phase.goalId), today)
        if (existing != null && recordScheduleHistory) {
            val revisions = mutableListOf<GoalScheduleRevision>()
            if (existing.plannedStartDate != normalized.plannedStartDate) revisions.add(revision(
                GoalScheduleEntityType.PHASE, normalized.uuid, "plannedStartDate",
                existing.plannedStartDate, normalized.plannedStartDate, "SINGLE", now))
            if (existing.plannedEndDate != normalized.plannedEndDate) revisions.add(revision(
                GoalScheduleEntityType.PHASE, normalized.uuid, "plannedEndDate",
                existing.plannedEndDate, normalized.plannedEndDate, "SINGLE", now))
            if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
        }
        if (existing == null) normalized.copy(id = dao.insertPhase(normalized)) else { dao.updatePhase(normalized); normalized }
    }

    /** Deleting a phase detaches, never destroys: its milestones and tasks survive as goal-level records. */
    suspend fun deletePhase(id: Long) = db.withTransaction {
        val phase = dao.phase(id) ?: throw IllegalArgumentException("This phase has already been deleted.")
        val now = System.currentTimeMillis()
        dao.detachMilestonesFromPhase(id, now)
        dao.detachTasksFromPhase(id, now)
        dao.deleteScheduleRevisionsForEntity(phase.uuid)
        dao.deletePhaseById(id)
    }

    // ---- Milestones ----

    /** [recordScheduleHistory] writes revision rows for changed schedule fields (see [saveGoal]). */
    suspend fun saveMilestone(milestone: GoalMilestone, recordScheduleHistory: Boolean = false): GoalMilestone = db.withTransaction {
        val today = LocalDate.now()
        val existing = if (milestone.id == 0L) null else dao.milestone(milestone.id)
        require(milestone.id == 0L || existing != null) { "This milestone no longer exists." }
        require(dao.goal(milestone.goalId) != null) { "A milestone belongs to a missing goal." }
        if (existing != null) require(existing.goalId == milestone.goalId) { "A milestone cannot move between goals." }
        milestone.phaseId?.let { phaseId ->
            val phase = dao.phase(phaseId) ?: throw IllegalArgumentException("This milestone's phase no longer exists.")
            require(phase.goalId == milestone.goalId) { "A milestone's phase must belong to the milestone's own goal." }
        }
        require(milestone.status in MilestoneStatus.entries.map(MilestoneStatus::name)) { "Unknown milestone status." }
        val uuid = existing?.uuid ?: milestone.uuid.ifBlank { UUID.randomUUID().toString() }
        val uuidOwner = dao.milestoneByUuid(uuid)
        require(uuidOwner == null || uuidOwner.id == milestone.id) { "Another milestone already uses this identifier." }
        val nextStatus = MilestoneStatus.fromStored(milestone.status)
        if (existing == null) {
            require(nextStatus == MilestoneStatus.PENDING) { "New milestones start as PENDING." }
            require(dao.allMilestones().size < GoalValidator.MAX_MILESTONES) {
                "LifeTrack cannot hold more than %,d milestones.".format(java.util.Locale.ROOT, GoalValidator.MAX_MILESTONES)
            }
        } else {
            val currentStatus = MilestoneStatus.fromStored(existing.status)
            require(GoalTransitions.legal(GoalTransitions.milestones, currentStatus, nextStatus)) {
                "A $currentStatus milestone cannot move to $nextStatus."
            }
        }
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val achievedAt = if (nextStatus == MilestoneStatus.ACHIEVED) existing?.achievedAt ?: now else null
        val normalized = milestone.copy(
            uuid = uuid,
            title = milestone.title.trim(), description = milestone.description?.trim()?.takeIf { it.isNotEmpty() },
            status = nextStatus.name, achievedAt = achievedAt,
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        val phaseOwnership = milestone.phaseId?.let { mapOf(it to milestone.goalId) } ?: emptyMap()
        GoalValidator.validateMilestone(normalized, setOf(milestone.goalId), phaseOwnership, today)
        if (existing != null && recordScheduleHistory && existing.targetDate != normalized.targetDate) {
            dao.insertScheduleRevisions(listOf(revision(
                GoalScheduleEntityType.MILESTONE, normalized.uuid, "targetDate",
                existing.targetDate, normalized.targetDate, "SINGLE", now)))
        }
        if (existing == null) normalized.copy(id = dao.insertMilestone(normalized)) else { dao.updateMilestone(normalized); normalized }
    }

    /** Deleting a milestone detaches its tasks; the tasks survive as goal- or phase-level work. */
    suspend fun deleteMilestone(id: Long) = db.withTransaction {
        val milestone = dao.milestone(id) ?: throw IllegalArgumentException("This milestone has already been deleted.")
        dao.detachTasksFromMilestone(id, System.currentTimeMillis())
        dao.deleteScheduleRevisionsForEntity(milestone.uuid)
        dao.deleteMilestoneById(id)
    }

    // ---- Tasks ----

    /** [recordScheduleHistory] writes revision rows for changed schedule fields (see [saveGoal]). */
    suspend fun saveTask(task: GoalTask, recordScheduleHistory: Boolean = false): GoalTask = db.withTransaction {
        val today = LocalDate.now()
        val existing = if (task.id == 0L) null else dao.task(task.id)
        require(task.id == 0L || existing != null) { "This task no longer exists." }
        require(dao.goal(task.goalId) != null) { "A task belongs to a missing goal." }
        if (existing != null) require(existing.goalId == task.goalId) { "A task cannot move between goals." }
        task.phaseId?.let { phaseId ->
            val phase = dao.phase(phaseId) ?: throw IllegalArgumentException("This task's phase no longer exists.")
            require(phase.goalId == task.goalId) { "A task's phase must belong to the task's own goal." }
        }
        task.milestoneId?.let { milestoneId ->
            val milestone = dao.milestone(milestoneId) ?: throw IllegalArgumentException("This task's milestone no longer exists.")
            require(milestone.goalId == task.goalId) { "A task's milestone must belong to the task's own goal." }
        }
        require(task.status in GoalTaskStatus.entries.map(GoalTaskStatus::name)) { "Unknown task status." }
        val uuid = existing?.uuid ?: task.uuid.ifBlank { UUID.randomUUID().toString() }
        val uuidOwner = dao.taskByUuid(uuid)
        require(uuidOwner == null || uuidOwner.id == task.id) { "Another task already uses this identifier." }
        val nextStatus = GoalTaskStatus.fromStored(task.status)
        if (existing == null) {
            require(nextStatus == GoalTaskStatus.NOT_STARTED) { "New tasks start as NOT_STARTED." }
            require(dao.allTasks().size < GoalValidator.MAX_TASKS) {
                "LifeTrack cannot hold more than %,d tasks.".format(java.util.Locale.ROOT, GoalValidator.MAX_TASKS)
            }
        } else {
            val currentStatus = GoalTaskStatus.fromStored(existing.status)
            require(GoalTransitions.legal(GoalTransitions.tasks, currentStatus, nextStatus)) {
                "A $currentStatus task cannot move to $nextStatus."
            }
        }
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val completedAt = if (nextStatus == GoalTaskStatus.COMPLETED) existing?.completedAt ?: now else null
        val normalized = task.copy(
            uuid = uuid,
            title = task.title.trim(), description = task.description?.trim()?.takeIf { it.isNotEmpty() },
            status = nextStatus.name, completedAt = completedAt,
            sortOrder = existing?.sortOrder ?: ((dao.tasksForGoal(task.goalId).maxOfOrNull { it.sortOrder } ?: -1) + 1),
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        val phaseOwnership = task.phaseId?.let { mapOf(it to task.goalId) } ?: emptyMap()
        val milestoneOwnership = task.milestoneId?.let { mapOf(it to task.goalId) } ?: emptyMap()
        GoalValidator.validateTask(normalized, setOf(task.goalId), phaseOwnership, milestoneOwnership, today)
        if (existing != null && recordScheduleHistory) {
            val revisions = mutableListOf<GoalScheduleRevision>()
            if (existing.plannedDate != normalized.plannedDate) revisions.add(revision(
                GoalScheduleEntityType.TASK, normalized.uuid, "plannedDate",
                existing.plannedDate, normalized.plannedDate, "SINGLE", now))
            if (existing.deadline != normalized.deadline) revisions.add(revision(
                GoalScheduleEntityType.TASK, normalized.uuid, "deadline",
                existing.deadline, normalized.deadline, "SINGLE", now))
            if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
        }
        if (existing == null) normalized.copy(id = dao.insertTask(normalized)) else { dao.updateTask(normalized); normalized }
    }

    /** Removing a task also removes its edges and schedule history: no dangling rows survive. */
    suspend fun deleteTask(id: Long) = db.withTransaction {
        val task = dao.task(id) ?: throw IllegalArgumentException("This task has already been deleted.")
        dao.deleteDependenciesForTask(id)
        dao.deleteScheduleRevisionsForEntity(task.uuid)
        dao.deleteTaskById(id)
    }

    // ---- Dependencies ----

    /** A "needs first" edge. Self-edges, cross-goal edges, duplicates and cycles are rejected;
     *  adding dependent→prerequisite closes a cycle exactly when the prerequisite already
     *  transitively depends on the dependent, which the breadth-first walk below proves. */
    suspend fun addDependency(dependentTaskId: Long, prerequisiteTaskId: Long) = db.withTransaction {
        require(dependentTaskId != prerequisiteTaskId) { "A task cannot depend on itself." }
        val dependent = dao.task(dependentTaskId) ?: throw IllegalArgumentException("This task no longer exists.")
        val prerequisite = dao.task(prerequisiteTaskId) ?: throw IllegalArgumentException("The prerequisite task no longer exists.")
        require(dependent.goalId == prerequisite.goalId) { "Dependencies must stay within one goal." }
        val edges = dao.allDependencies()
        require(edges.none { it.dependentTaskId == dependentTaskId && it.prerequisiteTaskId == prerequisiteTaskId }) {
            "This dependency already exists."
        }
        require(edges.size < GoalValidator.MAX_DEPENDENCIES) { "This plan carries too many task dependencies." }
        val dependsOn = edges.groupBy(GoalTaskDependency::dependentTaskId, GoalTaskDependency::prerequisiteTaskId)
        val visited = mutableSetOf(prerequisiteTaskId)
        val queue = ArrayDeque(listOf(prerequisiteTaskId))
        while (queue.isNotEmpty()) {
            dependsOn[queue.removeFirst()].orEmpty().forEach { next ->
                require(next != dependentTaskId) { "This dependency would create a circular chain." }
                if (visited.add(next)) queue.addLast(next)
            }
        }
        dao.insertDependency(GoalTaskDependency(dependentTaskId, prerequisiteTaskId))
    }

    /** Removing an edge changes no status: "blocked" is derived at read time, never stored. */
    suspend fun removeDependency(dependentTaskId: Long, prerequisiteTaskId: Long) = db.withTransaction {
        dao.deleteDependency(dependentTaskId, prerequisiteTaskId)
    }

    // ---- Ordering ----

    /** IDs may contain the visible subset; unlisted goals are appended without losing history. */
    suspend fun reorderGoals(ids: List<Long>) = db.withTransaction {
        require(ids.size == ids.toSet().size) { "A goal appears twice in this order." }
        val all = dao.allGoals()
        require(ids.all { id -> all.any { it.id == id } }) { "The goal list changed. Try ordering it again." }
        val now = System.currentTimeMillis()
        val byId = all.associateBy { it.id }
        (ids + all.map { it.id }.filterNot(ids::contains)).forEachIndexed { index, id ->
            byId.getValue(id).let { goal -> dao.setGoalSortOrder(id, index, nextTimestamp(now, goal.createdAt, goal.updatedAt)) }
        }
    }

    suspend fun reorderPhases(goalId: Long, ids: List<Long>) = db.withTransaction {
        require(ids.size == ids.toSet().size) { "A phase appears twice in this order." }
        val all = dao.phasesForGoal(goalId)
        require(ids.all { id -> all.any { it.id == id } }) { "The phase list changed. Try ordering it again." }
        val now = System.currentTimeMillis()
        val byId = all.associateBy { it.id }
        (ids + all.map { it.id }.filterNot(ids::contains)).forEachIndexed { index, id ->
            byId.getValue(id).let { phase -> dao.setPhaseSortOrder(id, index, nextTimestamp(now, phase.createdAt, phase.updatedAt)) }
        }
    }

    /** Task order is goal-global; the listed tasks lead, every other task of the goal follows. */
    suspend fun reorderTasks(ids: List<Long>) = db.withTransaction {
        require(ids.size == ids.toSet().size) { "A task appears twice in this order." }
        val all = dao.allTasks()
        require(ids.all { id -> all.any { it.id == id } }) { "The task list changed. Try ordering it again." }
        val goalIds = ids.mapNotNull { id -> all.firstOrNull { it.id == id }?.goalId }.toSet()
        require(goalIds.size <= 1) { "Tasks can only be ordered within one goal." }
        val goalId = goalIds.firstOrNull() ?: return@withTransaction
        val now = System.currentTimeMillis()
        val scoped = all.filter { it.goalId == goalId }
        val byId = scoped.associateBy { it.id }
        (ids + scoped.map { it.id }.filterNot(ids::contains)).forEachIndexed { index, id ->
            byId.getValue(id).let { task -> dao.setTaskSortOrder(id, index, nextTimestamp(now, task.createdAt, task.updatedAt)) }
        }
    }

    // ---- Rescheduling ----
    //
    // Only these explicit operations write schedule history — plain form saves stay revision-free.
    // Every operation records one revision row per changed date field (former → new, with the
    // operation scope), updates the record in the same transaction, and never touches completion
    // records: a reschedule can never rewrite when work actually happened.

    private fun revision(entityType: GoalScheduleEntityType, entityUuid: String, field: String,
                         formerDate: LocalDate?, newDate: LocalDate?, scope: String, changedAt: Long) =
        GoalScheduleRevision(
            entityType = entityType.name, entityUuid = entityUuid, field = field,
            formerDate = formerDate, newDate = newDate, scope = scope, changedAt = changedAt
        )

    private fun requireCalendarDate(date: LocalDate?, today: LocalDate, label: String) {
        require(date == null || (date >= BackupValidator.earliestDate && date <= today.plusYears(100))) {
            "$label must be between 1900 and 100 years from today."
        }
    }

    /** Moves one task's planning dates. An explicit move may touch a completed task's planning
     *  metadata (its completion record stays untouched); only this single scope allows that —
     *  batch shifts skip completed dependents entirely. */
    suspend fun rescheduleTask(taskId: Long, plannedDate: LocalDate?, deadline: LocalDate?,
                               scope: String = "SINGLE") = db.withTransaction {
        val task = dao.task(taskId) ?: throw IllegalArgumentException("This task no longer exists.")
        val today = LocalDate.now()
        requireCalendarDate(plannedDate, today, "Planned dates")
        requireCalendarDate(deadline, today, "Deadlines")
        require(plannedDate == null || deadline == null || !plannedDate.isAfter(deadline)) {
            "A task cannot be planned after its deadline."
        }
        val now = nextTimestamp(System.currentTimeMillis(), task.createdAt, task.updatedAt)
        val revisions = mutableListOf<GoalScheduleRevision>()
        if (task.plannedDate != plannedDate) revisions.add(revision(GoalScheduleEntityType.TASK, task.uuid, "plannedDate", task.plannedDate, plannedDate, scope, now))
        if (task.deadline != deadline) revisions.add(revision(GoalScheduleEntityType.TASK, task.uuid, "deadline", task.deadline, deadline, scope, now))
        val updated = task.copy(plannedDate = plannedDate, deadline = deadline, updatedAt = now)
        GoalValidator.validateTask(updated, setOf(task.goalId),
            task.phaseId?.let { mapOf(it to task.goalId) } ?: emptyMap(),
            task.milestoneId?.let { mapOf(it to task.goalId) } ?: emptyMap(), today)
        if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
        dao.updateTask(updated)
    }

    /** Moves the task and shifts every transitively dependent task that still needs work by the
     *  same day deltas, transactionally, with one revision row per changed field. Completed and
     *  skipped dependents keep their recorded dates; unrelated tasks are never touched. */
    suspend fun shiftDependentTasks(taskId: Long, newPlannedDate: LocalDate?, newDeadline: LocalDate?) = db.withTransaction {
        val moved = dao.task(taskId) ?: throw IllegalArgumentException("This task no longer exists.")
        val today = LocalDate.now()
        requireCalendarDate(newPlannedDate, today, "Planned dates")
        requireCalendarDate(newDeadline, today, "Deadlines")
        require(moved.plannedDate == null || newPlannedDate == null || !newPlannedDate.isAfter(newDeadline ?: moved.deadline ?: newPlannedDate)) {
            "A task cannot be planned after its deadline."
        }
        val now = nextTimestamp(System.currentTimeMillis(), moved.createdAt, moved.updatedAt)
        val revisions = mutableListOf<GoalScheduleRevision>()
        if (moved.plannedDate != newPlannedDate) revisions.add(revision(GoalScheduleEntityType.TASK, moved.uuid, "plannedDate", moved.plannedDate, newPlannedDate, "SHIFT_DEPENDENTS", now))
        if (moved.deadline != newDeadline) revisions.add(revision(GoalScheduleEntityType.TASK, moved.uuid, "deadline", moved.deadline, newDeadline, "SHIFT_DEPENDENTS", now))
        val updatedMoved = moved.copy(plannedDate = newPlannedDate, deadline = newDeadline, updatedAt = now)
        GoalValidator.validateTask(updatedMoved, setOf(moved.goalId),
            moved.phaseId?.let { mapOf(it to moved.goalId) } ?: emptyMap(),
            moved.milestoneId?.let { mapOf(it to moved.goalId) } ?: emptyMap(), today)
        dao.updateTask(updatedMoved)
        val goalTasks = dao.tasksForGoal(moved.goalId)
        // The preview deltas come from the ORIGINAL dates — passing the already-updated row would
        // compute a zero-day shift and silently move nothing.
        val shifts = GoalTimeline.shiftPreview(moved, goalTasks, dao.allDependencies(), newPlannedDate, newDeadline)
        // Name an infeasible dependent before any write: the batch must abort as a whole, and the
        // user deserves to know WHICH task the shift would strand past its own deadline.
        shifts.firstOrNull { it.plannedDate != null && it.deadline != null && it.plannedDate.isAfter(it.deadline) }
            ?.let { infeasible ->
                throw IllegalArgumentException(
                    "Moving this task would plan \"${infeasible.title}\" after its own deadline. " +
                        "Move it first, or shift its deadline too."
                )
            }
        shifts.forEach { shift ->
            val dependent = goalTasks.firstOrNull { it.id == shift.taskId } ?: return@forEach
            val dependentNow = nextTimestamp(now, dependent.createdAt, dependent.updatedAt)
            if (dependent.plannedDate != shift.plannedDate) revisions.add(revision(GoalScheduleEntityType.TASK, dependent.uuid, "plannedDate", dependent.plannedDate, shift.plannedDate, "SHIFT_DEPENDENTS", dependentNow))
            if (dependent.deadline != shift.deadline) revisions.add(revision(GoalScheduleEntityType.TASK, dependent.uuid, "deadline", dependent.deadline, shift.deadline, "SHIFT_DEPENDENTS", dependentNow))
            val updated = dependent.copy(plannedDate = shift.plannedDate, deadline = shift.deadline, updatedAt = dependentNow)
            GoalValidator.validateTask(updated, setOf(dependent.goalId),
                dependent.phaseId?.let { mapOf(it to dependent.goalId) } ?: emptyMap(),
                dependent.milestoneId?.let { mapOf(it to dependent.goalId) } ?: emptyMap(), today)
            dao.updateTask(updated)
        }
        if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
    }

    /** Moves a milestone deadline. Achievement records are never altered by a reschedule. */
    suspend fun rescheduleMilestone(milestoneId: Long, targetDate: LocalDate?) = db.withTransaction {
        val milestone = dao.milestone(milestoneId) ?: throw IllegalArgumentException("This milestone no longer exists.")
        val today = LocalDate.now()
        requireCalendarDate(targetDate, today, "Milestone deadlines")
        val now = nextTimestamp(System.currentTimeMillis(), milestone.createdAt, milestone.updatedAt)
        val revisions = if (milestone.targetDate != targetDate) listOf(
            revision(GoalScheduleEntityType.MILESTONE, milestone.uuid, "targetDate", milestone.targetDate, targetDate, "SINGLE", now)
        ) else emptyList()
        val updated = milestone.copy(targetDate = targetDate, updatedAt = now)
        GoalValidator.validateMilestone(updated, setOf(milestone.goalId),
            milestone.phaseId?.let { mapOf(it to milestone.goalId) } ?: emptyMap(), today)
        if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
        dao.updateMilestone(updated)
    }

    /** Moves a phase's planned window; overlapping and reordered windows stay legal. */
    suspend fun reschedulePhase(phaseId: Long, plannedStartDate: LocalDate?, plannedEndDate: LocalDate?) = db.withTransaction {
        val phase = dao.phase(phaseId) ?: throw IllegalArgumentException("This phase no longer exists.")
        val today = LocalDate.now()
        requireCalendarDate(plannedStartDate, today, "Phase start dates")
        requireCalendarDate(plannedEndDate, today, "Phase end dates")
        require(plannedStartDate == null || plannedEndDate == null || !plannedStartDate.isAfter(plannedEndDate)) {
            "A phase cannot end before it starts."
        }
        val now = nextTimestamp(System.currentTimeMillis(), phase.createdAt, phase.updatedAt)
        val revisions = mutableListOf<GoalScheduleRevision>()
        if (phase.plannedStartDate != plannedStartDate) revisions.add(revision(GoalScheduleEntityType.PHASE, phase.uuid, "plannedStartDate", phase.plannedStartDate, plannedStartDate, "SINGLE", now))
        if (phase.plannedEndDate != plannedEndDate) revisions.add(revision(GoalScheduleEntityType.PHASE, phase.uuid, "plannedEndDate", phase.plannedEndDate, plannedEndDate, "SINGLE", now))
        val updated = phase.copy(plannedStartDate = plannedStartDate, plannedEndDate = plannedEndDate, updatedAt = now)
        GoalValidator.validatePhase(updated, setOf(phase.goalId), today)
        if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
        dao.updatePhase(updated)
    }

    /** Extends or moves the goal's own target date; tasks and phases are never touched silently. */
    suspend fun rescheduleGoal(goalId: Long, targetDate: LocalDate?) = db.withTransaction {
        val goal = dao.goal(goalId) ?: throw IllegalArgumentException("This goal no longer exists.")
        val today = LocalDate.now()
        requireCalendarDate(targetDate, today, "Target dates")
        val now = nextTimestamp(System.currentTimeMillis(), goal.createdAt, goal.updatedAt)
        val revisions = if (goal.targetDate != targetDate) listOf(
            revision(GoalScheduleEntityType.GOAL, goal.uuid, "targetDate", goal.targetDate, targetDate, "SINGLE", now)
        ) else emptyList()
        val updated = goal.copy(targetDate = targetDate, updatedAt = now)
        GoalValidator.validateGoal(updated, today)
        if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)
        dao.updateGoal(updated)
    }

    /** The schedule history for one goal, oldest first: the earliest row per (entity, field) holds
     *  the original planned date. */
    suspend fun scheduleRevisionsForEntity(entityUuid: String, field: String): List<GoalScheduleRevision> =
        dao.revisionsForEntityField(entityUuid, field)

    /** One goal as an observable flow, for editor screens that stay live while editing. */
    fun observeGoal(id: Long): Flow<Goal?> = dao.observeGoals().map { goals -> goals.firstOrNull { it.id == id } }
    suspend fun goalById(id: Long): Goal? = dao.goal(id)
    suspend fun phaseById(id: Long): GoalPhase? = dao.phase(id)
    suspend fun milestoneById(id: Long): GoalMilestone? = dao.milestone(id)
    suspend fun taskById(id: Long): GoalTask? = dao.task(id)
    suspend fun phasesForGoal(goalId: Long): List<GoalPhase> = dao.phasesForGoal(goalId)
    suspend fun tasksForGoal(goalId: Long): List<GoalTask> = dao.tasksForGoal(goalId)
    suspend fun milestonesForGoal(goalId: Long): List<GoalMilestone> = dao.milestonesForGoal(goalId)

    // ---- Habit links (read-only supporting association, Prompt 3) ----
    //
    // A link records that an existing habit's recurring effort supports a goal. It never writes
    // habit data: habit logs, streaks, scheduling and achievements stay owned by the habit system,
    // and consistency signals are read through read-only queries. One goal may carry several
    // habits and one habit may support several goals; deleting either side removes only the link.

    /** Supporting-habit links for one goal, as an observable flow. */
    fun habitLinks(goalId: Long): Flow<List<GoalHabitLink>> = dao.observeLinksForGoal(goalId)
    fun allHabitLinks(): Flow<List<GoalHabitLink>> = dao.observeGoalHabitLinks()

    /** Links [habitId] as supporting [goalId]. Rejected: missing records, duplicates, unbounded
     *  per-goal growth. Archived habits may remain linked — historical effort stays visible. */
    suspend fun linkHabit(goalId: Long, habitId: Long): GoalHabitLink = db.withTransaction {
        val goal = dao.goal(goalId) ?: throw IllegalArgumentException("This goal no longer exists.")
        val habit = db.dao().habit(habitId) ?: throw IllegalArgumentException("The selected habit no longer exists.")
        require(dao.goalHabitLink(goalId, habitId) == null) {
            "\"${habit.name}\" already supports \"${goal.title}\"."
        }
        val linked = dao.observeLinksForGoal(goalId).first()
        require(linked.size < MAX_HABIT_LINKS_PER_GOAL) {
            "A goal cannot support more than $MAX_HABIT_LINKS_PER_GOAL habits."
        }
        val link = GoalHabitLink(goalId = goalId, habitId = habitId, createdAt = System.currentTimeMillis())
        dao.insertGoalHabitLink(link)
        link
    }

    /** Removes a supporting-habit link. Idempotent: removing an absent link changes nothing. */
    suspend fun unlinkHabit(goalId: Long, habitId: Long) = db.withTransaction {
        dao.deleteGoalHabitLink(goalId, habitId)
    }

    suspend fun goalHabitLinks(): List<GoalHabitLink> = dao.allGoalHabitLinks()

    /** Read-only supporting-habit activity signal, derived entirely from habit data: total
     *  completions, completions in the trailing window, and the most recent completed day. */
    suspend fun habitActivity(habitId: Long, windowDays: Long = HABIT_ACTIVITY_WINDOW_DAYS): HabitActivitySignal {
        val habit = db.dao().habit(habitId) ?: throw IllegalArgumentException("The selected habit no longer exists.")
        val logs = db.dao().habitLogs(habitId)
        return habitActivitySignal(habit, logs, LocalDate.now(), windowDays)
    }

    data class HabitActivitySignal(
        val habitId: Long,
        val habitName: String,
        val isArchived: Boolean,
        val totalCompletions: Int,
        val completionsInWindow: Int,
        val lastCompletedDate: LocalDate?
    )

    companion object {
        /** Bounded per-goal link count: real roadmaps link a handful of habits; the cap prevents
         *  unbounded growth without blocking ordinary use. The backup layer enforces its own
         *  total-document cap. */
        const val MAX_HABIT_LINKS_PER_GOAL = 50

        /** Trailing window, in days, of the supporting-habit activity signal shown on goal details. */
        const val HABIT_ACTIVITY_WINDOW_DAYS = 28L

        /** Pure supporting-habit activity computation — the single source both the repository read
         *  path and the detail UI (which feeds already-collected flows) use, so the honest signal
         *  can never drift between layers. */
        fun habitActivitySignal(habit: Habit, logs: List<HabitLog>, today: LocalDate,
                                windowDays: Long = HABIT_ACTIVITY_WINDOW_DAYS): HabitActivitySignal {
            val completed = logs.filter { it.status == LogStatus.COMPLETED }
            return HabitActivitySignal(
                habitId = habit.id,
                habitName = habit.name,
                isArchived = habit.isArchived,
                totalCompletions = completed.size,
                completionsInWindow = completed.count { it.date > today.minusDays(windowDays) },
                lastCompletedDate = completed.maxOfOrNull { it.date }
            )
        }
    }
}
