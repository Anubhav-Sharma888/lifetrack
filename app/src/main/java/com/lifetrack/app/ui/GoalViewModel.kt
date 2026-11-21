package com.lifetrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.GoalScheduleRevision
import com.lifetrack.app.data.GoalStatus
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskDependency
import com.lifetrack.app.data.GoalTaskStatus
import com.lifetrack.app.domain.GoalTimeline
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Hub filters. ARCHIVED shows the filed-away goals; the others are business states. */
enum class GoalFilter { ACTIVE, PLANNED, COMPLETED, ARCHIVED, ALL }

/** One hub card: honest counts only — never a weighted percentage without a basis. */
data class GoalCardState(
    val goal: Goal,
    val currentPhaseName: String?,
    val nextPhaseName: String?,
    val completedTasks: Int,
    val totalTasks: Int,
    val overdueTasks: Int,
    val dueTodayTasks: Int
)

data class GoalsHubState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val filter: GoalFilter = GoalFilter.ACTIVE,
    val cards: List<GoalCardState> = emptyList(),
    val databaseEmpty: Boolean = false
)

data class GoalDetailState(
    val goal: Goal,
    val today: LocalDate,
    val phases: List<GoalTimeline.PhaseView>,
    val milestones: List<GoalTimeline.MilestoneView>,
    val tasks: List<GoalTimeline.TaskView>,
    val dependencies: List<GoalTaskDependency>,
    /** Original planned dates by task uuid, recovered from the earliest schedule revision. */
    val originalPlannedDates: Map<String, LocalDate>
) {
    val tasksByPhase: Map<Long?, List<GoalTimeline.TaskView>> =
        tasks.groupBy { it.task.phaseId }.toSortedMap(compareBy { it ?: Long.MIN_VALUE })
    val completedTasks: Int get() = tasks.count { it.task.status == GoalTaskStatus.COMPLETED.name }
}

@HiltViewModel
class GoalViewModel @Inject constructor(private val repository: GoalRepository) : ViewModel() {
    private val today = MutableStateFlow(LocalDate.now())
    private val _filter = MutableStateFlow(GoalFilter.ACTIVE)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError = _operationError.asStateFlow()

    val hub: StateFlow<GoalsHubState> = combine(repository.goals, repository.allTasks, repository.allPhases, today, _filter) {
        goals, tasks, phases, currentToday, filter ->
        val tasksByGoal = tasks.groupBy { it.goalId }
        val phasesByGoal = phases.groupBy { it.goalId }
        val cards = goals.filter { matches(filter, it) }.map { goal ->
            card(goal, tasksByGoal[goal.id].orEmpty(), phasesByGoal[goal.id].orEmpty(), currentToday)
        }
        GoalsHubState(loaded = true, today = currentToday, filter = filter, cards = cards, databaseEmpty = goals.isEmpty())
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(GoalsHubState(loaded = true))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsHubState())

    fun setFilter(filter: GoalFilter) { _filter.value = filter }
    fun refreshToday() { today.value = LocalDate.now() }
    fun clearOperationError() { _operationError.value = null }

    /** Live goal stream for the editor screen. */
    fun observeGoalForEditor(id: Long): Flow<Goal?> = repository.observeGoal(id)

    private fun matches(filter: GoalFilter, goal: Goal): Boolean = when (filter) {
        GoalFilter.ALL -> true
        GoalFilter.ARCHIVED -> goal.isArchived
        GoalFilter.ACTIVE -> !goal.isArchived && goal.status == GoalStatus.ACTIVE.name
        GoalFilter.PLANNED -> !goal.isArchived && goal.status == GoalStatus.PLANNED.name
        GoalFilter.COMPLETED -> !goal.isArchived && goal.status == GoalStatus.COMPLETED.name
    }

    private fun card(goal: Goal, tasks: List<GoalTask>, phases: List<GoalPhase>, today: LocalDate): GoalCardState {
        val snapshot = GoalTimeline.snapshot(goal, phases, emptyList(), tasks, emptyList(), today, ZoneId.systemDefault())
        val current = snapshot.phases.firstOrNull { it.isCurrent }?.phase
        val next = snapshot.phases.firstOrNull { it.isFuture }?.phase
        return GoalCardState(
            goal = goal,
            currentPhaseName = current?.name,
            nextPhaseName = if (current == null) next?.name else null,
            completedTasks = snapshot.completedTasks,
            totalTasks = snapshot.totalTasks,
            overdueTasks = snapshot.overdueTasks,
            dueTodayTasks = snapshot.dueTodayTasks
        )
    }

    private data class DetailCore(
        val goal: Goal?, val phases: List<GoalPhase>, val milestones: List<GoalMilestone>,
        val tasks: List<GoalTask>, val dependencies: List<GoalTaskDependency>
    )

    /** Live detail state for one goal; null while the id points at a deleted goal. */
    fun detailState(goalId: Long): Flow<GoalDetailState?> =
        combine(repository.observeGoal(goalId), repository.phases(goalId), repository.milestones(goalId),
            repository.tasks(goalId), repository.dependencies(goalId)) { goal, phases, milestones, tasks, dependencies ->
            DetailCore(goal, phases, milestones, tasks, dependencies)
        }.combine(repository.scheduleHistory(goalId)) { core, history -> core to history }
        .combine(today) { (core, history), currentToday ->
            core.goal?.let { goal -> buildDetail(goal, core.phases, core.milestones, core.tasks, core.dependencies, history, currentToday) }
        }

    private fun buildDetail(goal: Goal, phases: List<GoalPhase>, milestones: List<GoalMilestone>,
                            tasks: List<GoalTask>, dependencies: List<GoalTaskDependency>,
                            history: List<GoalScheduleRevision>, currentToday: LocalDate): GoalDetailState {
        val snapshot = GoalTimeline.snapshot(goal, phases, milestones, tasks, dependencies, currentToday, ZoneId.systemDefault())
        return GoalDetailState(
            goal = goal, today = currentToday, phases = snapshot.phases, milestones = snapshot.milestones,
            tasks = snapshot.tasks, dependencies = dependencies,
            originalPlannedDates = GoalTimeline.originalPlannedDates(history)
        )
    }

    // ---- Mutations ----
    //
    // Every operation is busy-gated (rapid taps cannot double-submit), validates through the
    // repository, then reports through one snackbar message. The operation error surfaces inline
    // on the screen that raised it.

    private fun launchOperation(operation: suspend () -> String, onSuccess: () -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        _operationError.value = null
        viewModelScope.launch {
            try {
                val message = operation()
                _events.emit(UiEvent.Message(message))
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _operationError.value = error.message ?: "The change could not be saved."
            } finally {
                _busy.value = false
            }
        }
    }

    fun saveGoal(goal: Goal, onSaved: (Goal) -> Unit = {}) {
        var saved = goal
        launchOperation(operation = {
            saved = repository.saveGoal(goal)
            if (goal.id == 0L) "Goal created" else "Goal saved"
        }, onSuccess = { onSaved(saved) })
    }

    /** Editing an existing goal records a schedule revision when its target date changes — the
     *  repository compares against the pre-save row inside the transaction. */
    fun saveGoalWithDates(goal: Goal, onSaved: (Goal) -> Unit = {}) {
        var saved = goal
        launchOperation(operation = {
            saved = repository.saveGoal(goal, recordScheduleHistory = goal.id != 0L)
            if (goal.id == 0L) "Goal created" else "Goal saved"
        }, onSuccess = { onSaved(saved) })
    }

    fun archiveGoal(id: Long, archived: Boolean) = launchOperation(operation = {
        repository.archiveGoal(id, archived)
        if (archived) "Goal archived" else "Goal restored"
    })

    fun deleteGoal(id: Long) = launchOperation(operation = {
        repository.deleteGoal(id)
        "Goal deleted"
    })

    fun savePhase(phase: GoalPhase, datesChanged: Boolean, onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            repository.savePhase(phase, recordScheduleHistory = datesChanged)
            if (phase.id == 0L) "Phase added" else "Phase saved"
        }, onSuccess = onSaved)
    }

    fun deletePhase(id: Long, onDeleted: () -> Unit = {}) {
        launchOperation(operation = {
            repository.deletePhase(id)
            "Phase deleted — its milestones and tasks stay with the goal"
        }, onSuccess = onDeleted)
    }

    fun saveMilestone(milestone: GoalMilestone, deadlineChanged: Boolean, onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            repository.saveMilestone(milestone, recordScheduleHistory = deadlineChanged)
            if (milestone.id == 0L) "Milestone added" else "Milestone saved"
        }, onSuccess = onSaved)
    }

    fun deleteMilestone(id: Long, onDeleted: () -> Unit = {}) {
        launchOperation(operation = {
            repository.deleteMilestone(id)
            "Milestone deleted — its tasks stay with the goal"
        }, onSuccess = onDeleted)
    }

    fun saveTask(task: GoalTask, datesChanged: Boolean, onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            repository.saveTask(task, recordScheduleHistory = datesChanged)
            if (task.id == 0L) "Task added" else "Task saved"
        }, onSuccess = onSaved)
    }

    fun deleteTask(id: Long, onDeleted: () -> Unit = {}) {
        launchOperation(operation = {
            repository.deleteTask(id)
            "Task deleted"
        }, onSuccess = onDeleted)
    }

    fun addDependency(dependentTaskId: Long, prerequisiteTaskId: Long) = launchOperation(operation = {
        repository.addDependency(dependentTaskId, prerequisiteTaskId)
        "Dependency added"
    })

    fun removeDependency(dependentTaskId: Long, prerequisiteTaskId: Long) = launchOperation(operation = {
        repository.removeDependency(dependentTaskId, prerequisiteTaskId)
        "Dependency removed"
    })

    /** A deliberate date move with schedule history; optionally shifts incomplete dependents too. */
    fun rescheduleTask(taskId: Long, plannedDate: LocalDate?, deadline: LocalDate?, shiftDependents: Boolean,
                       onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            if (shiftDependents) repository.shiftDependentTasks(taskId, plannedDate, deadline)
            else repository.rescheduleTask(taskId, plannedDate, deadline)
            "Dates moved"
        }, onSuccess = onSaved)
    }

    fun rescheduleMilestone(milestoneId: Long, targetDate: LocalDate?, onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            repository.rescheduleMilestone(milestoneId, targetDate)
            "Deadline moved"
        }, onSuccess = onSaved)
    }

    fun reschedulePhase(phaseId: Long, start: LocalDate?, end: LocalDate?, onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            repository.reschedulePhase(phaseId, start, end)
            "Phase window moved"
        }, onSuccess = onSaved)
    }

    fun rescheduleGoal(goalId: Long, targetDate: LocalDate?, onSaved: () -> Unit = {}) {
        launchOperation(operation = {
            repository.rescheduleGoal(goalId, targetDate)
            "Target date moved"
        }, onSuccess = onSaved)
    }

    /** Up/down reorder helpers: derive the new full order from the current persisted order. */
    fun movePhase(goalId: Long, phaseId: Long, direction: Int) = launchOperation(operation = {
        val ids = repository.phasesForGoal(goalId).map { it.id }.toMutableList()
        val index = ids.indexOf(phaseId)
        val target = index + direction
        require(index >= 0 && target in ids.indices) { "This phase cannot move further." }
        ids.removeAt(index)
        ids.add(target, phaseId)
        repository.reorderPhases(goalId, ids)
        "Phases reordered"
    })

    fun moveTask(taskId: Long, direction: Int) = launchOperation(operation = {
        val task = repository.taskById(taskId) ?: throw IllegalArgumentException("This task no longer exists.")
        val ids = repository.tasksForGoal(task.goalId).map { it.id }.toMutableList()
        val index = ids.indexOf(taskId)
        val target = index + direction
        require(index >= 0 && target in ids.indices) { "This task cannot move further." }
        ids.removeAt(index)
        ids.add(target, taskId)
        repository.reorderTasks(ids)
        "Tasks reordered"
    })
}
