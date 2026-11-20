package com.lifetrack.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
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

    // ---- Goals ----

    suspend fun saveGoal(goal: Goal): Goal = db.withTransaction {
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

    /** Removes the goal and its whole tree. Children go first, explicitly, in one transaction. */
    suspend fun deleteGoal(id: Long) = db.withTransaction {
        require(dao.goal(id) != null) { "This goal has already been deleted." }
        dao.deleteDependenciesForGoal(id)
        dao.deleteTasksForGoal(id)
        dao.deleteMilestonesForGoal(id)
        dao.deletePhasesForGoal(id)
        dao.deleteGoalById(id)
    }

    // ---- Phases ----

    suspend fun savePhase(phase: GoalPhase): GoalPhase = db.withTransaction {
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
        if (existing == null) normalized.copy(id = dao.insertPhase(normalized)) else { dao.updatePhase(normalized); normalized }
    }

    /** Deleting a phase detaches, never destroys: its milestones and tasks survive as goal-level records. */
    suspend fun deletePhase(id: Long) = db.withTransaction {
        require(dao.phase(id) != null) { "This phase has already been deleted." }
        val now = System.currentTimeMillis()
        dao.detachMilestonesFromPhase(id, now)
        dao.detachTasksFromPhase(id, now)
        dao.deletePhaseById(id)
    }

    // ---- Milestones ----

    suspend fun saveMilestone(milestone: GoalMilestone): GoalMilestone = db.withTransaction {
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
        if (existing == null) normalized.copy(id = dao.insertMilestone(normalized)) else { dao.updateMilestone(normalized); normalized }
    }

    /** Deleting a milestone detaches its tasks; the tasks survive as goal- or phase-level work. */
    suspend fun deleteMilestone(id: Long) = db.withTransaction {
        require(dao.milestone(id) != null) { "This milestone has already been deleted." }
        dao.detachTasksFromMilestone(id, System.currentTimeMillis())
        dao.deleteMilestoneById(id)
    }

    // ---- Tasks ----

    suspend fun saveTask(task: GoalTask): GoalTask = db.withTransaction {
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
        if (existing == null) normalized.copy(id = dao.insertTask(normalized)) else { dao.updateTask(normalized); normalized }
    }

    /** Removing a task also removes its edges: no dangling dependency rows survive. */
    suspend fun deleteTask(id: Long) = db.withTransaction {
        require(dao.task(id) != null) { "This task has already been deleted." }
        dao.deleteDependenciesForTask(id)
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
}
