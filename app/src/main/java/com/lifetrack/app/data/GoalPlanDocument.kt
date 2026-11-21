package com.lifetrack.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * The AI-generated Goal Plan document (Prompt 3): a standalone, versioned JSON format that is
 * deliberately DIFFERENT from a full LifeTrack backup.
 *
 * - Document identity: `"kind": "lifetrack.goal-plan"` + an independent `schemaVersion` — never
 *   `LifeTrackBackup.formatVersion`, and never accepted by the full-backup restore path.
 * - Proposed plans only: there are NO status, completion-timestamp or achievement fields in the
 *   schema. Imported work starts as planned/uncompleted; fabricated history is unrepresentable
 *   rather than rejected after the fact.
 * - Stable identity: every record carries a caller-provided canonical UUID; re-parsing the same
 *   file always yields the same identities (no generation during reads).
 * - Bounded input: the parser rejects oversized files, over-nested JSON and unbounded collections
 *   before allocating, reusing the backup layer's depth pre-check conventions.
 * - Pure parsing boundary: parsing validates and returns an in-memory plan; it never writes Room
 *   entities, changes goals, imports habits or touches the backup-restore operation.
 */
object GoalPlanContract {
    const val KIND = "lifetrack.goal-plan"
    const val SCHEMA_VERSION = 1
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_JSON_DEPTH = 64
}

/** The proposed goal. No status: imported plans start PLANNED by policy at import time. */
@Serializable
data class GoalPlanGoal(
    val uuid: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val priority: String? = null,
    @Serializable(with = LocalDateSerializer::class) val plannedStartDate: LocalDate? = null,
    @Serializable(with = LocalDateSerializer::class) val targetDate: LocalDate? = null
)

@Serializable
data class GoalPlanPhase(
    val uuid: String,
    val name: String,
    val description: String? = null,
    val order: Int? = null,
    @Serializable(with = LocalDateSerializer::class) val plannedStartDate: LocalDate? = null,
    @Serializable(with = LocalDateSerializer::class) val plannedEndDate: LocalDate? = null
)

@Serializable
data class GoalPlanMilestone(
    val uuid: String,
    val title: String,
    val description: String? = null,
    val phaseUuid: String? = null,
    @Serializable(with = LocalDateSerializer::class) val targetDate: LocalDate? = null
)

@Serializable
data class GoalPlanTask(
    val uuid: String,
    val title: String,
    val description: String? = null,
    val order: Int? = null,
    val phaseUuid: String? = null,
    val milestoneUuid: String? = null,
    @Serializable(with = LocalDateSerializer::class) val plannedDate: LocalDate? = null,
    @Serializable(with = LocalDateSerializer::class) val deadline: LocalDate? = null,
    val estimatedMinutes: Int? = null
)

/** Task prerequisite edge in document identity — never local Room ids. */
@Serializable
data class GoalPlanDependency(val taskUuid: String, val prerequisiteUuid: String)

@Serializable
data class GoalPlanDocument(
    val kind: String,
    val schemaVersion: Int,
    val planId: String,
    val goal: GoalPlanGoal,
    val phases: List<GoalPlanPhase> = emptyList(),
    val milestones: List<GoalPlanMilestone> = emptyList(),
    val tasks: List<GoalPlanTask> = emptyList(),
    val dependencies: List<GoalPlanDependency> = emptyList()
)

/**
 * Strict offline parser for Goal Plan documents: bounded input, depth pre-check, exact document
 * discriminator, independent schema version, strict field validation and semantic checks —
 * no database writes and no identity generation.
 */
object GoalPlanParser {

    /** Strict reader: unknown keys are rejected (AI output must not drift silently), no leniency. */
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    /** Converts untrusted text into a validated in-memory [GoalPlanDocument]. No writes. */
    fun parse(text: String): GoalPlanDocument {
        val stripped = text.removePrefix("\uFEFF")
        require(stripped.toByteArray(Charsets.UTF_8).size <= GoalPlanContract.MAX_FILE_BYTES) {
            "Goal Plan files must be 2 MB or smaller."
        }
        require(BackupValidator.maxJsonDepth(stripped) <= GoalPlanContract.MAX_JSON_DEPTH) {
            "This file nests data too deeply to be a Goal Plan."
        }
        val root: JsonObject = try {
            json.parseToJsonElement(stripped).jsonObject
        } catch (error: SerializationException) {
            throw IllegalArgumentException("This is not a valid Goal Plan file.", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("This is not a valid Goal Plan file.", error)
        }
        val kind = try { root["kind"]?.jsonPrimitive?.content } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("This file is not a Goal Plan: the document kind is invalid.", error)
        } ?: throw IllegalArgumentException("This file is not a Goal Plan: the document kind is missing.")
        require(kind == GoalPlanContract.KIND) {
            "This file is not a LifeTrack Goal Plan (found document kind \"$kind\")."
        }
        val schemaVersion = root["schemaVersion"]?.let {
            try { it.jsonPrimitive.content.toInt() } catch (error: Exception) {
                throw IllegalArgumentException("The Goal Plan schema version is invalid.", error)
            }
        } ?: throw IllegalArgumentException("The Goal Plan schema version is missing.")
        require(schemaVersion == GoalPlanContract.SCHEMA_VERSION) {
            "This Goal Plan uses schema version $schemaVersion, which this app does not support."
        }
        val document = try {
            json.decodeFromString<GoalPlanDocument>(stripped)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IllegalArgumentException("The Goal Plan file does not match the schema: ${error.message}", error)
        }
        GoalPlanValidator.validate(document)
        return document
    }

    /** Read-only export of a validated plan back to its canonical JSON text (round-trip support). */
    fun write(document: GoalPlanDocument): String =
        json.encodeToString(GoalPlanDocument.serializer(), document)
}

/**
 * Semantic validation for a decoded [GoalPlanDocument]: identity, bounds, references, dependency
 * acyclicity — bounded, deterministic, and free of database access. Completing work is
 * unrepresentable by schema shape, so fabricated progress history cannot pass.
 */
object GoalPlanValidator {

    fun validate(document: GoalPlanDocument) {
        require(document.planId.isNotBlank() && isCanonicalUuid(document.planId)) {
            "The plan identifier must be a canonical UUID."
        }
        validateGoal(document.goal)
        val phases = document.phases
        val milestones = document.milestones
        val tasks = document.tasks
        require(phases.size <= GoalValidator.MAX_PHASES) { "The plan carries too many phases." }
        require(milestones.size <= GoalValidator.MAX_MILESTONES) { "The plan carries too many milestones." }
        require(tasks.size <= GoalValidator.MAX_TASKS) { "The plan carries too many tasks." }
        require(document.dependencies.size <= GoalValidator.MAX_DEPENDENCIES) {
            "The plan carries too many dependencies."
        }
        // Global identity uniqueness across every record of the document.
        val allUuids = listOf(document.goal.uuid) + phases.map { it.uuid } + milestones.map { it.uuid } + tasks.map { it.uuid }
        require(allUuids.size == allUuids.toSet().size) { "Duplicate record identifiers in the plan." }
        val phaseUuids = phases.map { it.uuid }.toSet()
        val milestoneUuids = milestones.map { it.uuid }.toSet()
        val taskUuids = tasks.map { it.uuid }.toSet()
        phases.forEach { phase ->
            require(isCanonicalUuid(phase.uuid)) { "A phase identifier is not a canonical UUID." }
            require(phase.name.isNotBlank() && phase.name.length <= GoalValidator.MAX_TITLE_LENGTH) {
                "Phase names must contain 1–${GoalValidator.MAX_TITLE_LENGTH} characters."
            }
            phase.description?.let {
                require(it.length <= GoalValidator.MAX_DESCRIPTION_LENGTH) { "Phase descriptions are limited to ${GoalValidator.MAX_DESCRIPTION_LENGTH} characters." }
            }
            phase.order?.let { require(it >= 0) { "Phase order cannot be negative." } }
            require(phase.plannedStartDate == null || phase.plannedEndDate == null || phase.plannedStartDate <= phase.plannedEndDate) {
                "A phase cannot end before it starts."
            }
        }
        milestones.forEach { milestone ->
            require(isCanonicalUuid(milestone.uuid)) { "A milestone identifier is not a canonical UUID." }
            require(milestone.title.isNotBlank() && milestone.title.length <= GoalValidator.MAX_TITLE_LENGTH) {
                "Milestone titles must contain 1–${GoalValidator.MAX_TITLE_LENGTH} characters."
            }
            milestone.description?.let {
                require(it.length <= GoalValidator.MAX_DESCRIPTION_LENGTH) { "Milestone descriptions are limited to ${GoalValidator.MAX_DESCRIPTION_LENGTH} characters." }
            }
            milestone.phaseUuid?.let {
                require(it in phaseUuids) { "A milestone references an unknown phase." }
            }
        }
        tasks.forEach { task ->
            require(isCanonicalUuid(task.uuid)) { "A task identifier is not a canonical UUID." }
            require(task.title.isNotBlank() && task.title.length <= GoalValidator.MAX_TITLE_LENGTH) {
                "Task titles must contain 1–${GoalValidator.MAX_TITLE_LENGTH} characters."
            }
            task.description?.let {
                require(it.length <= GoalValidator.MAX_DESCRIPTION_LENGTH) { "Task descriptions are limited to ${GoalValidator.MAX_DESCRIPTION_LENGTH} characters." }
            }
            task.order?.let { require(it >= 0) { "Task order cannot be negative." } }
            task.phaseUuid?.let { require(it in phaseUuids) { "A task references an unknown phase." } }
            task.milestoneUuid?.let { require(it in milestoneUuids) { "A task references an unknown milestone." } }
            require(task.estimatedMinutes == null || task.estimatedMinutes in 1..GoalValidator.MAX_ESTIMATED_MINUTES) {
                "Estimated durations must be between 1 and ${GoalValidator.MAX_ESTIMATED_MINUTES} minutes."
            }
            require(task.plannedDate == null || task.deadline == null || task.plannedDate <= task.deadline) {
                "A task cannot be planned after its deadline."
            }
        }
        // Dependencies: same-goal by construction (one document = one goal), acyclic, no self-edges,
        // no duplicates, both endpoints resolved.
        document.dependencies.forEach { edge ->
            require(edge.taskUuid in taskUuids && edge.prerequisiteUuid in taskUuids) {
                "A dependency references an unknown task."
            }
            require(edge.taskUuid != edge.prerequisiteUuid) { "A task cannot depend on itself." }
        }
        require(
            document.dependencies.map { it.taskUuid to it.prerequisiteUuid }.toSet().size == document.dependencies.size
        ) { "Duplicate dependencies in the plan." }
        val edges = document.dependencies.groupBy(GoalPlanDependency::taskUuid, GoalPlanDependency::prerequisiteUuid)
        // The proven stack-based DFS from GoalValidator.validateDependencyGraph, over document ids.
        val visited = mutableSetOf<String>()
        tasks.forEach { task ->
            val stack = ArrayDeque<Pair<String, Boolean>>()
            stack.addLast(task.uuid to false)
            val onPath = HashSet<String>()
            while (stack.isNotEmpty()) {
                val (current, isExit) = stack.removeLast()
                when {
                    isExit -> onPath.remove(current)
                    !visited.add(current) -> require(current !in onPath) { "Dependencies in the plan form a cycle." }
                    else -> {
                        onPath.add(current)
                        stack.addLast(current to true)
                        edges[current].orEmpty().forEach { stack.addLast(it to false) }
                    }
                }
            }
        }
    }

    private fun validateGoal(goal: GoalPlanGoal) {
        require(isCanonicalUuid(goal.uuid)) { "The goal identifier must be a canonical UUID." }
        require(goal.title.isNotBlank() && goal.title.length <= GoalValidator.MAX_TITLE_LENGTH) {
            "The goal title must contain 1–${GoalValidator.MAX_TITLE_LENGTH} characters."
        }
        goal.description?.let {
            require(it.length <= GoalValidator.MAX_DESCRIPTION_LENGTH) { "The goal description is limited to ${GoalValidator.MAX_DESCRIPTION_LENGTH} characters." }
        }
        goal.category?.let {
            require(it.isNotBlank() && it.length <= GoalValidator.MAX_CATEGORY_LENGTH) { "Goal categories must contain 1–${GoalValidator.MAX_CATEGORY_LENGTH} characters." }
        }
        goal.priority?.let {
            require(it in GoalPriority.entries.map(GoalPriority::name)) { "Unknown goal priority in the plan." }
        }
        require(goal.plannedStartDate == null || goal.targetDate == null || goal.plannedStartDate <= goal.targetDate) {
            "The goal cannot end before it starts."
        }
    }
}

/**
 * Read-only export of locally persisted goal records into a validated Goal Plan document. Used by
 * tests to prove round-trip identity; exposes no import path (Prompt 4 owns importing).
 */
object GoalPlanExporter {

    suspend fun export(goalId: Long, repository: GoalRepository): GoalPlanDocument {
        val goal = repository.goalById(goalId) ?: throw IllegalArgumentException("This goal no longer exists.")
        val phases = repository.phasesForGoal(goalId)
        val milestones = repository.milestonesForGoal(goalId)
        val tasks = repository.tasksForGoal(goalId)
        val dependencies = repository.dependencies(goalId).first()
        return GoalPlanDocument(
            kind = GoalPlanContract.KIND,
            schemaVersion = GoalPlanContract.SCHEMA_VERSION,
            planId = goal.uuid,
            goal = GoalPlanGoal(
                uuid = goal.uuid, title = goal.title, description = goal.description,
                category = goal.category, priority = goal.priority,
                plannedStartDate = goal.plannedStartDate, targetDate = goal.targetDate
            ),
            phases = phases.map { phase ->
                GoalPlanPhase(
                    uuid = phase.uuid, name = phase.name, description = phase.description,
                    order = phase.sortOrder, plannedStartDate = phase.plannedStartDate,
                    plannedEndDate = phase.plannedEndDate
                )
            },
            milestones = milestones.map { milestone ->
                GoalPlanMilestone(
                    uuid = milestone.uuid, title = milestone.title, description = milestone.description,
                    phaseUuid = milestone.phaseId?.let { phaseId -> phases.firstOrNull { it.id == phaseId }?.uuid },
                    targetDate = milestone.targetDate
                )
            },
            tasks = tasks.map { task ->
                GoalPlanTask(
                    uuid = task.uuid, title = task.title, description = task.description,
                    order = task.sortOrder,
                    phaseUuid = task.phaseId?.let { phaseId -> phases.firstOrNull { it.id == phaseId }?.uuid },
                    milestoneUuid = task.milestoneId?.let { milestoneId -> milestones.firstOrNull { it.id == milestoneId }?.uuid },
                    plannedDate = task.plannedDate, deadline = task.deadline,
                    estimatedMinutes = task.estimatedMinutes
                )
            },
            dependencies = dependencies.map { edge ->
                val dependent = tasks.firstOrNull { it.id == edge.dependentTaskId }
                val prerequisite = tasks.firstOrNull { it.id == edge.prerequisiteTaskId }
                GoalPlanDependency(
                    taskUuid = dependent?.uuid ?: "",
                    prerequisiteUuid = prerequisite?.uuid ?: ""
                )
            }.filter { it.taskUuid.isNotBlank() && it.prerequisiteUuid.isNotBlank() }
        )
    }
}
