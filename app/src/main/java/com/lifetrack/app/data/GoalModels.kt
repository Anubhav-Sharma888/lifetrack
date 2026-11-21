package com.lifetrack.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Goal Roadmaps persistence (Version 2, schema 11). A goal is a long-term desired outcome; phases
 * subdivide it, milestones are verifiable checkpoints, and tasks are concrete units of work.
 * These tables never touch habits, activities or reflections: goal rows cascade only inside the
 * goal graph. Room enforces the declared foreign keys at runtime, and [GoalRepository] still
 * performs every child delete explicitly in dependency-safe order so the semantics stay
 * observable and the ordering deterministic.
 *
 * Every row carries a stable external [uuid] (canonical 8-4-4-4-12 hex) that identifies the record
 * across backups and the future partial Goal Plan import. Row [id]s are local database identity:
 * a full-backup restore reproduces them verbatim, while partial imports match records by uuid and
 * mint fresh row ids. Uuids are never generated during reads or serialization.
 *
 * Planning and progress stay separate: planned dates are calendar dates ([LocalDate], epoch days),
 * actual completions are instants (epoch millis). Nothing here is ever completed automatically —
 * not by a passing deadline, not by a parent's status, not by a notification.
 */
@Serializable
enum class GoalStatus { PLANNED, ACTIVE, COMPLETED, PAUSED, CANCELLED;
    companion object {
        /** Unknown stored values fall back to PLANNED so corrupt local rows still render. */
        fun fromStored(value: String): GoalStatus = entries.firstOrNull { it.name == value } ?: PLANNED
    }
}

@Serializable
enum class GoalPriority { LOW, MEDIUM, HIGH;
    companion object {
        fun fromStored(value: String): GoalPriority = entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

@Serializable
enum class PhaseStatus { PLANNED, ACTIVE, COMPLETED, CANCELLED;
    companion object {
        fun fromStored(value: String): PhaseStatus = entries.firstOrNull { it.name == value } ?: PLANNED
    }
}

/** A milestone is a verifiable checkpoint: the user marks it achieved; verification UI arrives later. */
@Serializable
enum class MilestoneStatus { PENDING, ACHIEVED;
    companion object {
        fun fromStored(value: String): MilestoneStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

@Serializable
enum class GoalTaskStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, SKIPPED;
    companion object {
        fun fromStored(value: String): GoalTaskStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

/**
 * Legal user-driven status transitions. Saving a record without a status change is always allowed;
 * every other move must follow the graph. Reopening COMPLETED records is explicit, never inferred,
 * and clears the completion instant on the way out.
 */
object GoalTransitions {
    val goals = mapOf(
        GoalStatus.PLANNED to setOf(GoalStatus.ACTIVE, GoalStatus.CANCELLED),
        GoalStatus.ACTIVE to setOf(GoalStatus.PAUSED, GoalStatus.COMPLETED, GoalStatus.CANCELLED),
        GoalStatus.PAUSED to setOf(GoalStatus.ACTIVE, GoalStatus.CANCELLED),
        GoalStatus.COMPLETED to setOf(GoalStatus.ACTIVE), // explicit reopen
        GoalStatus.CANCELLED to setOf(GoalStatus.PLANNED) // explicit revive
    )
    val phases = mapOf(
        PhaseStatus.PLANNED to setOf(PhaseStatus.ACTIVE, PhaseStatus.CANCELLED),
        PhaseStatus.ACTIVE to setOf(PhaseStatus.COMPLETED, PhaseStatus.CANCELLED),
        PhaseStatus.COMPLETED to setOf(PhaseStatus.ACTIVE), // explicit reopen
        PhaseStatus.CANCELLED to setOf(PhaseStatus.PLANNED) // explicit revive
    )
    val milestones = mapOf(
        MilestoneStatus.PENDING to setOf(MilestoneStatus.ACHIEVED),
        MilestoneStatus.ACHIEVED to setOf(MilestoneStatus.PENDING) // explicit revert
    )
    val tasks = mapOf(
        GoalTaskStatus.NOT_STARTED to setOf(GoalTaskStatus.IN_PROGRESS, GoalTaskStatus.COMPLETED, GoalTaskStatus.SKIPPED),
        GoalTaskStatus.IN_PROGRESS to setOf(GoalTaskStatus.NOT_STARTED, GoalTaskStatus.COMPLETED, GoalTaskStatus.SKIPPED),
        GoalTaskStatus.COMPLETED to setOf(GoalTaskStatus.NOT_STARTED), // explicit reopen
        GoalTaskStatus.SKIPPED to setOf(GoalTaskStatus.NOT_STARTED) // unskip
    )

    fun <T> legal(map: Map<T, Set<T>>, from: T, to: T): Boolean = from == to || map[from]?.contains(to) == true
}

@Serializable
@Entity(tableName = "goals", indices = [Index(value = ["uuid"], unique = true)])
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @Required val title: String,
    @Required val description: String? = null,
    @Required val category: String = "Personal",
    @Required val priority: String = GoalPriority.MEDIUM.name,
    @Required @Serializable(with = LocalDateSerializer::class) val plannedStartDate: LocalDate? = null,
    @Required @Serializable(with = LocalDateSerializer::class) val targetDate: LocalDate? = null,
    @Required val status: String = GoalStatus.PLANNED.name,
    // Archiving files a goal away without changing its business state; it has its own operation,
    // mirroring habits, so a form edit can never rewrite archive history.
    @Required val isArchived: Boolean = false,
    @Required @Serializable(with = LocalDateSerializer::class) val archivedOn: LocalDate? = null,
    @Required val completedAt: Long? = null,
    @Required val sortOrder: Int = 0,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

@Serializable
@Entity(
    tableName = "goal_phases",
    foreignKeys = [ForeignKey(
        entity = Goal::class, parentColumns = ["id"], childColumns = ["goalId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["uuid"], unique = true), Index("goalId")]
)
data class GoalPhase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val goalId: Long,
    @Required val name: String,
    @Required val description: String? = null,
    @Required val sortOrder: Int = 0,
    @Required @Serializable(with = LocalDateSerializer::class) val plannedStartDate: LocalDate? = null,
    @Required @Serializable(with = LocalDateSerializer::class) val plannedEndDate: LocalDate? = null,
    @Required val status: String = PhaseStatus.PLANNED.name,
    @Required val completedAt: Long? = null,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

@Serializable
@Entity(
    tableName = "goal_milestones",
    foreignKeys = [
        ForeignKey(
            entity = Goal::class, parentColumns = ["id"], childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GoalPhase::class, parentColumns = ["id"], childColumns = ["phaseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["uuid"], unique = true), Index("goalId"), Index("phaseId")]
)
data class GoalMilestone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val goalId: Long,
    // Optional parent phase; deleting the phase detaches the milestone, it does not destroy it.
    val phaseId: Long? = null,
    @Required val title: String,
    @Required val description: String? = null,
    @Required @Serializable(with = LocalDateSerializer::class) val targetDate: LocalDate? = null,
    @Required val status: String = MilestoneStatus.PENDING.name,
    @Required val achievedAt: Long? = null,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

@Serializable
@Entity(
    tableName = "goal_tasks",
    foreignKeys = [
        ForeignKey(
            entity = Goal::class, parentColumns = ["id"], childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GoalPhase::class, parentColumns = ["id"], childColumns = ["phaseId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = GoalMilestone::class, parentColumns = ["id"], childColumns = ["milestoneId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["uuid"], unique = true), Index("goalId"), Index("phaseId"), Index("milestoneId")]
)
data class GoalTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val goalId: Long,
    val phaseId: Long? = null,
    val milestoneId: Long? = null,
    @Required val title: String,
    @Required val description: String? = null,
    @Required val sortOrder: Int = 0,
    // Planning stays decoupled from progress: plannedDate is intent, deadline is pressure,
    // completion is an explicit user action recorded in [status] and [completedAt].
    @Required @Serializable(with = LocalDateSerializer::class) val plannedDate: LocalDate? = null,
    @Required @Serializable(with = LocalDateSerializer::class) val deadline: LocalDate? = null,
    @Required val estimatedMinutes: Int? = null,
    @Required val status: String = GoalTaskStatus.NOT_STARTED.name,
    @Required val completedAt: Long? = null,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

/**
 * A directed "needs first" edge between two tasks of the same goal. Self-edges, duplicate edges
 * and cycles are rejected before any write (see [GoalValidator]); deleting either task removes
 * the edge explicitly. Cross-goal dependencies are out of scope for this version.
 */
@Serializable
@Entity(
    tableName = "goal_task_dependencies",
    primaryKeys = ["dependentTaskId", "prerequisiteTaskId"],
    foreignKeys = [
        ForeignKey(
            entity = GoalTask::class, parentColumns = ["id"], childColumns = ["dependentTaskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GoalTask::class, parentColumns = ["id"], childColumns = ["prerequisiteTaskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("prerequisiteTaskId")]
)
data class GoalTaskDependency(val dependentTaskId: Long, val prerequisiteTaskId: Long)

/** What kind of record a schedule revision describes. The entity row itself may be deleted later —
 *  revisions are audit rows tied to the stable uuid, never to a foreign key. */
@Serializable
enum class GoalScheduleEntityType { TASK, MILESTONE, PHASE, GOAL;
    companion object {
        fun fromStored(value: String): GoalScheduleEntityType =
            entries.firstOrNull { it.name == value } ?: TASK
    }
}

/**
 * One calendar-date revision, written only by the explicit reschedule operations (never by plain
 * form saves, which stay revision-free). Together the rows answer: which record changed, its
 * former date, its new date, when, and within which operation scope. The original plan stays
 * recoverable: the earliest revision per (entity, field) carries the original date. Actual
 * completion records are never written here and never rewritten by a reschedule.
 */
@Serializable
@Entity(
    tableName = "goal_schedule_revisions",
    indices = [Index("entityUuid"), Index(value = ["entityUuid", "field", "changedAt"])]
)
data class GoalScheduleRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityUuid: String,
    val field: String,
    @Serializable(with = LocalDateSerializer::class) val formerDate: LocalDate?,
    @Serializable(with = LocalDateSerializer::class) val newDate: LocalDate?,
    val scope: String,
    val changedAt: Long
)

/** Canonical 8-4-4-4-12 hex form. UUID.fromString is too permissive for record identity. */
private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun isCanonicalUuid(value: String): Boolean = UUID_PATTERN.matches(value)

/**
 * Validation shared by [GoalRepository] writes and backup import. Bounded and deterministic:
 * every limit is sized for real multi-month roadmaps (a 24-week plan with weekly tasks is a few
 * hundred records) while capping the graph a single import may demand from memory.
 */
object GoalValidator {
    const val MAX_TITLE_LENGTH = 120
    const val MAX_DESCRIPTION_LENGTH = 2_000
    const val MAX_CATEGORY_LENGTH = 40
    const val MAX_ESTIMATED_MINUTES = 10_080
    const val MAX_GOALS = 100
    const val MAX_PHASES = 500
    const val MAX_MILESTONES = 1_000
    const val MAX_TASKS = 5_000
    const val MAX_DEPENDENCIES = 10_000
    const val MAX_SCHEDULE_REVISIONS = 20_000

    /** Legitimate revision shapes: which field each entity type may reschedule. */
    val REVISED_FIELDS = mapOf(
        GoalScheduleEntityType.TASK to setOf("plannedDate", "deadline"),
        GoalScheduleEntityType.MILESTONE to setOf("targetDate"),
        GoalScheduleEntityType.PHASE to setOf("plannedStartDate", "plannedEndDate"),
        GoalScheduleEntityType.GOAL to setOf("targetDate")
    )

    fun validateScheduleRevision(revision: GoalScheduleRevision, today: LocalDate = LocalDate.now()) {
        require(isCanonicalUuid(revision.entityUuid)) { "Schedule revisions must reference canonical identifiers." }
        val type = GoalScheduleEntityType.entries.firstOrNull { it.name == revision.entityType }
        require(type != null) { "Unknown schedule revision entity type." }
        require(revision.field in REVISED_FIELDS.getValue(type)) { "A $type record cannot reschedule this field." }
        require(revision.scope in setOf("SINGLE", "SHIFT_DEPENDENTS")) { "Unknown schedule revision scope." }
        listOf(revision.formerDate, revision.newDate).forEach { date ->
            require(date == null || (date >= BackupValidator.earliestDate && date <= today.plusYears(100))) {
                "Schedule revision dates must be between 1900 and 100 years from today."
            }
        }
        require(revision.changedAt >= 0) { "Invalid schedule revision timestamp." }
        // formerDate and newDate carry at most one null between them: a reschedule always has a
        // former value or sets a first value, and clearing produces (date → null) — never both null.
        require(revision.formerDate != null || revision.newDate != null) { "A schedule revision must record a date." }
    }

    private fun validCalendarDate(date: LocalDate?, today: LocalDate): Boolean =
        date == null || (date >= BackupValidator.earliestDate && date <= today.plusYears(100))

    private fun validTimestamps(record: Pair<Long, Long>): Boolean =
        record.first >= 0 && record.second >= record.first

    fun validateGoal(goal: Goal, today: LocalDate = LocalDate.now()) {
        require(isCanonicalUuid(goal.uuid)) { "Goal identifiers must be canonical UUIDs." }
        require(goal.id >= 0 && goal.id < Long.MAX_VALUE) { "Invalid goal ID." }
        require(goal.title.isNotBlank() && goal.title.length <= MAX_TITLE_LENGTH) {
            "Goal titles must contain 1–$MAX_TITLE_LENGTH characters."
        }
        require(goal.description == null || goal.description.length <= MAX_DESCRIPTION_LENGTH) {
            "Goal descriptions are limited to %,d characters.".format(java.util.Locale.ROOT, MAX_DESCRIPTION_LENGTH)
        }
        require(goal.category.isNotBlank() && goal.category.length <= MAX_CATEGORY_LENGTH) {
            "Goal categories must contain 1–$MAX_CATEGORY_LENGTH characters."
        }
        require(goal.priority in GoalPriority.entries.map(GoalPriority::name)) { "Unknown goal priority." }
        require(validCalendarDate(goal.plannedStartDate, today) && validCalendarDate(goal.targetDate, today)) {
            "Goal dates must be between 1900 and 100 years from today."
        }
        require(goal.plannedStartDate == null || goal.targetDate == null || goal.plannedStartDate <= goal.targetDate) {
            "A goal cannot end before it starts."
        }
        require(goal.status in GoalStatus.entries.map(GoalStatus::name)) { "Unknown goal status." }
        require(goal.isArchived == (goal.archivedOn != null)) { "Archived goals must include their archive date." }
        require(goal.archivedOn == null || (goal.archivedOn >= BackupValidator.earliestDate && goal.archivedOn <= today)) {
            "Invalid archive date."
        }
        require(goal.sortOrder >= 0) { "Goal order cannot be negative." }
        require(validTimestamps(goal.createdAt to goal.updatedAt)) { "Invalid goal timestamps." }
        require((goal.status == GoalStatus.COMPLETED.name) == (goal.completedAt != null)) {
            "A completed goal must record when it was completed, and only then."
        }
        require(goal.completedAt == null || goal.completedAt >= 0) { "Invalid goal completion timestamp." }
    }

    fun validatePhase(phase: GoalPhase, goals: Set<Long>, today: LocalDate = LocalDate.now()) {
        require(isCanonicalUuid(phase.uuid)) { "Phase identifiers must be canonical UUIDs." }
        require(phase.id >= 0 && phase.id < Long.MAX_VALUE) { "Invalid phase ID." }
        require(phase.goalId in goals) { "A phase belongs to a missing goal." }
        require(phase.name.isNotBlank() && phase.name.length <= MAX_TITLE_LENGTH) {
            "Phase names must contain 1–$MAX_TITLE_LENGTH characters."
        }
        require(phase.description == null || phase.description.length <= MAX_DESCRIPTION_LENGTH) {
            "Phase descriptions are limited to %,d characters.".format(java.util.Locale.ROOT, MAX_DESCRIPTION_LENGTH)
        }
        require(validCalendarDate(phase.plannedStartDate, today) && validCalendarDate(phase.plannedEndDate, today)) {
            "Phase dates must be between 1900 and 100 years from today."
        }
        require(phase.plannedStartDate == null || phase.plannedEndDate == null || phase.plannedStartDate <= phase.plannedEndDate) {
            "A phase cannot end before it starts."
        }
        require(phase.status in PhaseStatus.entries.map(PhaseStatus::name)) { "Unknown phase status." }
        require(phase.sortOrder >= 0) { "Phase order cannot be negative." }
        require(validTimestamps(phase.createdAt to phase.updatedAt)) { "Invalid phase timestamps." }
        require((phase.status == PhaseStatus.COMPLETED.name) == (phase.completedAt != null)) {
            "A completed phase must record when it was completed, and only then."
        }
        require(phase.completedAt == null || phase.completedAt >= 0) { "Invalid phase completion timestamp." }
    }

    fun validateMilestone(milestone: GoalMilestone, goals: Set<Long>, phases: Map<Long, Long>, today: LocalDate = LocalDate.now()) {
        require(isCanonicalUuid(milestone.uuid)) { "Milestone identifiers must be canonical UUIDs." }
        require(milestone.id >= 0 && milestone.id < Long.MAX_VALUE) { "Invalid milestone ID." }
        require(milestone.goalId in goals) { "A milestone belongs to a missing goal." }
        require(milestone.phaseId == null || phases[milestone.phaseId] == milestone.goalId) {
            "A milestone's phase must belong to the milestone's own goal."
        }
        require(milestone.title.isNotBlank() && milestone.title.length <= MAX_TITLE_LENGTH) {
            "Milestone titles must contain 1–$MAX_TITLE_LENGTH characters."
        }
        require(milestone.description == null || milestone.description.length <= MAX_DESCRIPTION_LENGTH) {
            "Milestone descriptions are limited to %,d characters.".format(java.util.Locale.ROOT, MAX_DESCRIPTION_LENGTH)
        }
        require(validCalendarDate(milestone.targetDate, today)) {
            "Milestone deadlines must be between 1900 and 100 years from today."
        }
        require(milestone.status in MilestoneStatus.entries.map(MilestoneStatus::name)) { "Unknown milestone status." }
        require(validTimestamps(milestone.createdAt to milestone.updatedAt)) { "Invalid milestone timestamps." }
        require((milestone.status == MilestoneStatus.ACHIEVED.name) == (milestone.achievedAt != null)) {
            "An achieved milestone must record when it was achieved, and only then."
        }
        require(milestone.achievedAt == null || milestone.achievedAt >= 0) { "Invalid milestone achievement timestamp." }
    }

    fun validateTask(task: GoalTask, goals: Set<Long>, phases: Map<Long, Long>, milestones: Map<Long, Long>, today: LocalDate = LocalDate.now()) {
        require(isCanonicalUuid(task.uuid)) { "Task identifiers must be canonical UUIDs." }
        require(task.id >= 0 && task.id < Long.MAX_VALUE) { "Invalid task ID." }
        require(task.goalId in goals) { "A task belongs to a missing goal." }
        require(task.phaseId == null || phases[task.phaseId] == task.goalId) {
            "A task's phase must belong to the task's own goal."
        }
        require(task.milestoneId == null || milestones[task.milestoneId] == task.goalId) {
            "A task's milestone must belong to the task's own goal."
        }
        require(task.title.isNotBlank() && task.title.length <= MAX_TITLE_LENGTH) {
            "Task titles must contain 1–$MAX_TITLE_LENGTH characters."
        }
        require(task.description == null || task.description.length <= MAX_DESCRIPTION_LENGTH) {
            "Task descriptions are limited to %,d characters.".format(java.util.Locale.ROOT, MAX_DESCRIPTION_LENGTH)
        }
        require(validCalendarDate(task.plannedDate, today) && validCalendarDate(task.deadline, today)) {
            "Task dates must be between 1900 and 100 years from today."
        }
        require(task.plannedDate == null || task.deadline == null || task.plannedDate <= task.deadline) {
            "A task cannot be planned after its deadline."
        }
        require(task.estimatedMinutes == null || task.estimatedMinutes in 1..MAX_ESTIMATED_MINUTES) {
            "Estimated durations must be between 1 and %,d minutes.".format(java.util.Locale.ROOT, MAX_ESTIMATED_MINUTES)
        }
        require(task.status in GoalTaskStatus.entries.map(GoalTaskStatus::name)) { "Unknown task status." }
        require(task.sortOrder >= 0) { "Task order cannot be negative." }
        require(validTimestamps(task.createdAt to task.updatedAt)) { "Invalid task timestamps." }
        require((task.status == GoalTaskStatus.COMPLETED.name) == (task.completedAt != null)) {
            "A completed task must record when it was completed, and only then."
        }
        require(task.completedAt == null || task.completedAt >= 0) { "Invalid task completion timestamp." }
    }

    /** Duplicate edges are rejected by the caller's uniqueness check; this walks every edge once. */
    fun validateDependencyGraph(tasks: List<GoalTask>, dependencies: List<GoalTaskDependency>) {
        require(dependencies.size <= MAX_DEPENDENCIES) { "This plan carries too many task dependencies." }
        val tasksById = tasks.associateBy { it.id }
        val edges = dependencies.groupBy(GoalTaskDependency::dependentTaskId, GoalTaskDependency::prerequisiteTaskId)
        dependencies.forEach { edge ->
            val dependent = tasksById[edge.dependentTaskId]
            val prerequisite = tasksById[edge.prerequisiteTaskId]
            require(dependent != null && prerequisite != null) { "A dependency points at a missing task." }
            require(dependent.goalId == prerequisite.goalId) { "Dependencies must stay within one goal." }
        }
        // Cycle detection: from every unvisited task, follow "depends on" edges; any return visit
        // is a cycle. Linear in edges — bounded by MAX_DEPENDENCIES, so imports cannot exhaust memory.
        val visited = mutableSetOf<Long>()
        tasks.forEach { task ->
            val stack = ArrayDeque<Pair<Long, Boolean>>() // (task, isExit)
            stack.addLast(task.id to false)
            val onPath = HashSet<Long>()
            while (stack.isNotEmpty()) {
                val (current, isExit) = stack.removeLast()
                when {
                    isExit -> onPath.remove(current)
                    !visited.add(current) -> require(current !in onPath) { "Task dependencies cannot form a cycle." }
                    else -> {
                        onPath.add(current)
                        stack.addLast(current to true)
                        edges[current].orEmpty().forEach { stack.addLast(it to false) }
                    }
                }
            }
        }
    }

    /** Trims imported text the same way saves do; blank descriptions become null. */
    fun normalizeForImport(goal: Goal): Goal = goal.copy(
        title = goal.title.trim(), description = goal.description?.trim()?.takeIf { it.isNotEmpty() },
        category = goal.category.trim()
    )

    fun normalizeForImport(phase: GoalPhase): GoalPhase = phase.copy(
        name = phase.name.trim(), description = phase.description?.trim()?.takeIf { it.isNotEmpty() }
    )

    fun normalizeForImport(milestone: GoalMilestone): GoalMilestone = milestone.copy(
        title = milestone.title.trim(), description = milestone.description?.trim()?.takeIf { it.isNotEmpty() }
    )

    fun normalizeForImport(task: GoalTask): GoalTask = task.copy(
        title = task.title.trim(), description = task.description?.trim()?.takeIf { it.isNotEmpty() }
    )
}
