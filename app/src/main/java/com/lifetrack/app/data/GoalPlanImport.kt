package com.lifetrack.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A Goal Plan import refused for a concrete, user-actionable reason. The import never surfaces a
 *  bare validation error at the UI: every refusal names what happened and what to do next. */
class GoalPlanImportException(message: String) : Exception(message)

/**
 * Goal Plan import (Version 2, Prompt 4): turn a parsed [GoalPlanDocument] into user-reviewed,
 * transactionally applied goal-domain changes without ever touching unrelated data.
 *
 * Pipeline: bounded file read ([GoalPlanImportFileReader]) → strict parse ([GoalPlanParser]) →
 * database comparison ([classify]) → user decisions ([summarize]) → final preview → transactional
 * revalidation → atomic apply ([apply]) → result summary. Three invariants shape every stage:
 *
 * 1. **Identity is the record uuid**, never a title — two tasks may legally share a name.
 * 2. **Absence from the file never deletes** anything: import is purely additive or
 *    metadata-updating; existing status, completion history, schedule revisions and habit links
 *    are untouchable by construction. "Use imported" updates only the fields the plan actually
 *    provides — a missing plan field is unknown intent, never permission to erase.
 * 3. **Everything or nothing**: [apply] runs in one Room transaction that revalidates the whole
 *    plan (decision legality, stale-preview fingerprint, count caps, entity validation with the
 *    shared [GoalValidator] rules including calendar ranges, and the merged dependency graph)
 *    before writing; any failure rolls the database back to the exact pre-import state.
 */
@Singleton
class GoalPlanImportService @Inject constructor(private val db: AppDatabase) {
    private val dao = db.goalDao()

    // ---- Comparison snapshot ----

    /** One batched read of the goal domain — the basis for classification, decisions and the
     *  stale-preview fingerprint. Five queries total; never a per-record lookup loop. */
    private data class DomainSnapshot(
        val goals: List<Goal>,
        val phases: List<GoalPhase>,
        val milestones: List<GoalMilestone>,
        val tasks: List<GoalTask>,
        val dependencies: List<GoalTaskDependency>,
        val revisions: List<GoalScheduleRevision>
    ) {
        val goalByUuid = goals.associateBy(Goal::uuid)
        val phaseByUuid = phases.associateBy(GoalPhase::uuid)
        val milestoneByUuid = milestones.associateBy(GoalMilestone::uuid)
        val taskByUuid = tasks.associateBy(GoalTask::uuid)
        /** uuid → (row id, updatedAt, status) for every goal-domain row of every type. */
        val rowsByUuid: Map<String, DomainRow> =
            goals.associate { it.uuid to DomainRow(GoalPlanRecordKind.GOAL, it.id, it.updatedAt, it.status) } +
            phases.associate { it.uuid to DomainRow(GoalPlanRecordKind.PHASE, it.id, it.updatedAt, it.status) } +
            milestones.associate { it.uuid to DomainRow(GoalPlanRecordKind.MILESTONE, it.id, it.updatedAt, it.status) } +
            tasks.associate { it.uuid to DomainRow(GoalPlanRecordKind.TASK, it.id, it.updatedAt, it.status) }
        val existingEdgesByUuid: List<Pair<String, String>> = run {
            val uuidById = tasks.associate { it.id to it.uuid }
            dependencies.mapNotNull { edge ->
                uuidById[edge.dependentTaskId]?.let { dependent ->
                    uuidById[edge.prerequisiteTaskId]?.let { prerequisite -> dependent to prerequisite }
                }
            }
        }
        val counts = GoalDomainCounts(
            goals = goals.size, phases = phases.size, milestones = milestones.size,
            tasks = tasks.size, dependencies = dependencies.size, revisions = revisions.size
        )
    }

    private data class DomainRow(val kind: GoalPlanRecordKind, val id: Long, val updatedAt: Long, val status: String)

    private suspend fun snapshot(): DomainSnapshot = DomainSnapshot(
        goals = dao.allGoals(), phases = dao.allPhases(), milestones = dao.allMilestones(),
        tasks = dao.allTasks(), dependencies = dao.allDependencies(), revisions = dao.allScheduleRevisions()
    )

    // ---- Classification ----

    /** Reads the goal domain and classifies the document against it. Read-only: nothing writes. */
    suspend fun buildPreview(document: GoalPlanDocument): GoalPlanImportPreview =
        classify(document, snapshot())

    /**
     * Classifies every document record against the given database state: same inputs, same
     * verdicts. Equality covers only the fields the plan actually provides; text is normalized
     * exactly like repository saves before comparison, so " Title " equals a stored "Title".
     * Parent references compare as names whenever the referenced record already exists.
     */
    private fun classify(document: GoalPlanDocument, state: DomainSnapshot): GoalPlanImportPreview {
        val goal = document.goal
        val existingGoal = state.goalByUuid[goal.uuid]
        val changes = mutableListOf<GoalPlanRecordChange>()
        // The stale check covers EVERY record uuid the document mentions: a row that existed at
        // preview time must be unchanged at apply time, and a uuid that was absent must still be
        // absent — otherwise the user reviewed decisions about a different database.
        val fingerprint = mutableMapOf<String, Pair<Long, String>>()
        val absentUuids = mutableSetOf<String>()
        fun remember(uuid: String, row: DomainRow?) {
            if (row == null) absentUuids.add(uuid) else fingerprint[uuid] = row.updatedAt to row.status
        }
        remember(goal.uuid, existingGoal?.let { DomainRow(GoalPlanRecordKind.GOAL, it.id, it.updatedAt, it.status) })

        val goalDates = existingGoal?.let {
            mapOf<String, LocalDate?>("Planned start" to it.plannedStartDate, "Target date" to it.targetDate)
        } ?: emptyMap()
        changes.add(goalChange(goal, existingGoal, state, goalDates))

        document.phases.forEach { phase ->
            val existing = state.phaseByUuid[phase.uuid]
            remember(phase.uuid, existing?.let { DomainRow(GoalPlanRecordKind.PHASE, it.id, it.updatedAt, it.status) })
            changes.add(childChange(
                kind = GoalPlanRecordKind.PHASE, uuid = phase.uuid,
                importedTitle = phase.name.trim(), existing = existing,
                existingKind = state.rowsByUuid[phase.uuid]?.kind,
                differences = existing?.let { phaseDifferences(phase, it) }.orEmpty(),
                goalRowId = existingGoal?.id,
                existingDates = existing?.let {
                    mapOf<String, LocalDate?>("Planned start" to it.plannedStartDate, "Planned end" to it.plannedEndDate)
                } ?: emptyMap()
            ))
        }
        document.milestones.forEach { milestone ->
            val existing = state.milestoneByUuid[milestone.uuid]
            remember(milestone.uuid, existing?.let { DomainRow(GoalPlanRecordKind.MILESTONE, it.id, it.updatedAt, it.status) })
            changes.add(childChange(
                kind = GoalPlanRecordKind.MILESTONE, uuid = milestone.uuid,
                importedTitle = milestone.title.trim(), existing = existing,
                existingKind = state.rowsByUuid[milestone.uuid]?.kind,
                differences = existing?.let { milestoneDifferences(milestone, it, document, state) }.orEmpty(),
                goalRowId = existingGoal?.id,
                parentUuid = milestone.phaseUuid
            ))
        }
        document.tasks.forEach { task ->
            val existing = state.taskByUuid[task.uuid]
            remember(task.uuid, existing?.let { DomainRow(GoalPlanRecordKind.TASK, it.id, it.updatedAt, it.status) })
            changes.add(childChange(
                kind = GoalPlanRecordKind.TASK, uuid = task.uuid,
                importedTitle = task.title.trim(), existing = existing,
                existingKind = state.rowsByUuid[task.uuid]?.kind,
                differences = existing?.let { taskDifferences(task, it, document, state) }.orEmpty(),
                goalRowId = existingGoal?.id,
                parentUuid = task.phaseUuid, milestoneUuid = task.milestoneUuid,
                existingDates = existing?.let {
                    mapOf<String, LocalDate?>("Planned date" to it.plannedDate, "Deadline" to it.deadline)
                } ?: emptyMap()
            ))
        }

        val dependencyChanges = document.dependencies.map { edge ->
            val task = document.tasks.firstOrNull { it.uuid == edge.taskUuid }
            val prerequisite = document.tasks.firstOrNull { it.uuid == edge.prerequisiteUuid }
            GoalPlanDependencyChange(
                taskUuid = edge.taskUuid, prerequisiteUuid = edge.prerequisiteUuid,
                taskTitle = task?.title?.trim().orEmpty(),
                prerequisiteTitle = prerequisite?.title?.trim().orEmpty(),
                initialStatus = if (state.dependencies.any {
                        it.dependentTaskId == state.taskByUuid[edge.taskUuid]?.id &&
                        it.prerequisiteTaskId == state.taskByUuid[edge.prerequisiteUuid]?.id
                    }) GoalPlanEdgeStatus.ALREADY_PRESENT else GoalPlanEdgeStatus.NEW
            )
        }

        return GoalPlanImportPreview(
            document = document, changes = changes, dependencies = dependencyChanges,
            goalRowId = existingGoal?.id, fingerprint = fingerprint, absentRecordUuids = absentUuids,
            existingEdgesByUuid = state.existingEdgesByUuid, existingCounts = state.counts,
            classifiedAt = System.currentTimeMillis()
        )
    }

    private fun goalChange(goal: GoalPlanGoal, existing: Goal?, state: DomainSnapshot,
                           existingDates: Map<String, LocalDate?> = emptyMap()): GoalPlanRecordChange {
        if (existing == null) {
            // The uuid may still belong to a non-goal record: identity never crosses types.
            val kind = state.rowsByUuid[goal.uuid]?.kind
            if (kind != null && kind != GoalPlanRecordKind.GOAL) return GoalPlanRecordChange(
                kind = GoalPlanRecordKind.GOAL, uuid = goal.uuid, importedTitle = goal.title.trim(),
                existingTitle = null, classification = GoalPlanClassification.CROSS_TYPE_COLLISION,
                differences = emptyList(), defaultDecision = GoalPlanDecision.SKIP,
                allowedDecisions = setOf(GoalPlanDecision.SKIP, GoalPlanDecision.IMPORT_AS_COPY)
            )
            return GoalPlanRecordChange(
                kind = GoalPlanRecordKind.GOAL, uuid = goal.uuid, importedTitle = goal.title.trim(),
                existingTitle = null, classification = GoalPlanClassification.NEW, differences = emptyList(),
                defaultDecision = GoalPlanDecision.KEEP_IMPORTED,
                allowedDecisions = setOf(GoalPlanDecision.KEEP_IMPORTED, GoalPlanDecision.IMPORT_AS_COPY)
            )
        }
        val differences = goalDifferences(goal, existing)
        val classification = if (differences.isEmpty()) GoalPlanClassification.EQUIVALENT
        else GoalPlanClassification.CONFLICT
        return GoalPlanRecordChange(
            kind = GoalPlanRecordKind.GOAL, uuid = goal.uuid, importedTitle = goal.title.trim(),
            existingTitle = existing.title.trim(), classification = classification, differences = differences,
            defaultDecision = GoalPlanDecision.KEEP_EXISTING,
            allowedDecisions = setOf(
                GoalPlanDecision.KEEP_EXISTING, GoalPlanDecision.KEEP_IMPORTED,
                GoalPlanDecision.IMPORT_AS_COPY, GoalPlanDecision.SKIP),
            existingDates = existingDates
        )
    }

    /** A child uuid matching a row of another type, or of another goal, is a structural conflict:
     *  ownership is never silently reassigned and identity never crosses entity types. */
    private fun childChange(
        kind: GoalPlanRecordKind,
        uuid: String,
        importedTitle: String,
        existing: Any?,
        existingKind: GoalPlanRecordKind?,
        differences: List<GoalPlanFieldDifference>,
        goalRowId: Long?,
        parentUuid: String? = null,
        milestoneUuid: String? = null,
        existingDates: Map<String, LocalDate?> = emptyMap()
    ): GoalPlanRecordChange {
        if (existing == null) {
            if (existingKind != null && existingKind != kind) return GoalPlanRecordChange(
                kind = kind, uuid = uuid, importedTitle = importedTitle, existingTitle = null,
                classification = GoalPlanClassification.CROSS_TYPE_COLLISION, differences = emptyList(),
                defaultDecision = GoalPlanDecision.SKIP,
                allowedDecisions = setOf(GoalPlanDecision.SKIP, GoalPlanDecision.IMPORT_AS_COPY),
                parentUuid = parentUuid, milestoneUuid = milestoneUuid
            )
            return GoalPlanRecordChange(
                kind = kind, uuid = uuid, importedTitle = importedTitle, existingTitle = null,
                classification = GoalPlanClassification.NEW, differences = emptyList(),
                defaultDecision = GoalPlanDecision.KEEP_IMPORTED,
                allowedDecisions = setOf(GoalPlanDecision.KEEP_IMPORTED, GoalPlanDecision.IMPORT_AS_COPY, GoalPlanDecision.SKIP),
                parentUuid = parentUuid, milestoneUuid = milestoneUuid
            )
        }
        val existingGoalId = when (existing) {
            is GoalPhase -> existing.goalId
            is GoalMilestone -> existing.goalId
            is GoalTask -> existing.goalId
            else -> null
        }
        val ownedByImportedGoal = goalRowId != null && existingGoalId == goalRowId
        val (classification, default, allowed) = when {
            !ownedByImportedGoal -> Triple(
                GoalPlanClassification.FOREIGN_GOAL_OWNERSHIP, GoalPlanDecision.SKIP,
                setOf(GoalPlanDecision.SKIP, GoalPlanDecision.IMPORT_AS_COPY))
            differences.isEmpty() -> Triple(
                GoalPlanClassification.EQUIVALENT, GoalPlanDecision.KEEP_EXISTING,
                setOf(GoalPlanDecision.KEEP_EXISTING, GoalPlanDecision.KEEP_IMPORTED, GoalPlanDecision.IMPORT_AS_COPY, GoalPlanDecision.SKIP))
            else -> Triple(
                GoalPlanClassification.CONFLICT, GoalPlanDecision.KEEP_EXISTING,
                setOf(GoalPlanDecision.KEEP_EXISTING, GoalPlanDecision.KEEP_IMPORTED, GoalPlanDecision.IMPORT_AS_COPY, GoalPlanDecision.SKIP))
        }
        val existingTitle = when (existing) {
            is GoalPhase -> existing.name.trim()
            is GoalMilestone -> existing.title.trim()
            is GoalTask -> existing.title.trim()
            else -> ""
        }
        return GoalPlanRecordChange(
            kind = kind, uuid = uuid, importedTitle = importedTitle,
            existingTitle = existingTitle, classification = classification,
            differences = differences, defaultDecision = default, allowedDecisions = allowed,
            parentUuid = parentUuid, milestoneUuid = milestoneUuid, existingDates = existingDates
        )
    }

    // Difference computation: only fields the plan actually provides are compared — a missing
    // plan field can never read as "erase the stored value".

    private fun goalDifferences(goal: GoalPlanGoal, existing: Goal): List<GoalPlanFieldDifference> = buildList {
        textDifference(this, "Title", goal.title.trim(), existing.title.trim())
        goal.description?.let { textDifference(this, "Description", it.trim().takeIf { d -> d.isNotEmpty() }, existing.description) }
        goal.category?.let { textDifference(this, "Category", it.trim(), existing.category) }
        goal.priority?.let { textDifference(this, "Priority", it, existing.priority) }
        goal.plannedStartDate?.let { dateDifference(this, "Planned start", it, existing.plannedStartDate) }
        goal.targetDate?.let { dateDifference(this, "Target date", it, existing.targetDate) }
    }

    private fun phaseDifferences(phase: GoalPlanPhase, existing: GoalPhase): List<GoalPlanFieldDifference> = buildList {
        textDifference(this, "Name", phase.name.trim(), existing.name.trim())
        phase.description?.let { textDifference(this, "Description", it.trim().takeIf { d -> d.isNotEmpty() }, existing.description) }
        phase.plannedStartDate?.let { dateDifference(this, "Planned start", it, existing.plannedStartDate) }
        phase.plannedEndDate?.let { dateDifference(this, "Planned end", it, existing.plannedEndDate) }
        phase.order?.takeIf { it != existing.sortOrder }?.let {
            add(GoalPlanFieldDifference("Order", existing.sortOrder.toString(), it.toString()))
        }
    }

    private fun milestoneDifferences(milestone: GoalPlanMilestone, existing: GoalMilestone,
                                     document: GoalPlanDocument, state: DomainSnapshot): List<GoalPlanFieldDifference> = buildList {
        textDifference(this, "Title", milestone.title.trim(), existing.title.trim())
        milestone.description?.let { textDifference(this, "Description", it.trim().takeIf { d -> d.isNotEmpty() }, existing.description) }
        milestone.targetDate?.let { dateDifference(this, "Target date", it, existing.targetDate) }
        milestone.phaseUuid?.let {
            parentDifference(this, "Phase", it, existing.phaseId, document, state)
        }
    }

    private fun taskDifferences(task: GoalPlanTask, existing: GoalTask,
                                document: GoalPlanDocument, state: DomainSnapshot): List<GoalPlanFieldDifference> = buildList {
        textDifference(this, "Title", task.title.trim(), existing.title.trim())
        task.description?.let { textDifference(this, "Description", it.trim().takeIf { d -> d.isNotEmpty() }, existing.description) }
        task.plannedDate?.let { dateDifference(this, "Planned date", it, existing.plannedDate) }
        task.deadline?.let { dateDifference(this, "Deadline", it, existing.deadline) }
        task.estimatedMinutes?.takeIf { it != existing.estimatedMinutes }?.let {
            add(GoalPlanFieldDifference("Estimate", existing.estimatedMinutes?.let { "$it min" } ?: "none", "$it min"))
        }
        task.order?.takeIf { it != existing.sortOrder }?.let {
            add(GoalPlanFieldDifference("Order", existing.sortOrder.toString(), it.toString()))
        }
        task.phaseUuid?.let { parentDifference(this, "Phase", it, existing.phaseId, document, state) }
        task.milestoneUuid?.let { parentDifference(this, "Milestone", it, existing.milestoneId, document, state) }
    }

    /** Compares a plan parent reference with the stored parent by name — users never see uuids. */
    private fun parentDifference(list: MutableList<GoalPlanFieldDifference>, field: String,
                                 planUuid: String, existingRowId: Long?,
                                 document: GoalPlanDocument, state: DomainSnapshot) {
        val planName = when {
            document.phases.any { it.uuid == planUuid } -> document.phases.first { it.uuid == planUuid }.name.trim()
            document.milestones.any { it.uuid == planUuid } -> document.milestones.first { it.uuid == planUuid }.title.trim()
            else -> planUuid
        }
        val resolvedId = state.rowsByUuid[planUuid]?.id
        val changed = when {
            resolvedId == null -> true // the referenced record is itself new — a real change
            else -> resolvedId != existingRowId
        }
        if (changed) {
            val existingName = when (field) {
                "Phase" -> state.phases.firstOrNull { it.id == existingRowId }?.name?.trim()
                else -> state.milestones.firstOrNull { it.id == existingRowId }?.title?.trim()
            } ?: "none"
            list.add(GoalPlanFieldDifference(field, existingName, planName))
        }
    }

    private fun textDifference(list: MutableList<GoalPlanFieldDifference>, field: String, imported: String?, existing: String?) {
        if (imported != existing) list.add(GoalPlanFieldDifference(field, existing, imported))
    }

    /** Difference labels that map one-to-one onto schedule-revision fields. */
    private val SCHEDULABLE_FIELDS = setOf("Planned start", "Planned end", "Target date", "Planned date", "Deadline")

    private fun dateDifference(list: MutableList<GoalPlanFieldDifference>, field: String, imported: LocalDate, existing: LocalDate?) {
        if (imported != existing) list.add(GoalPlanFieldDifference(field, existing?.toString(), imported.toString()))
    }

    // ---- Decision resolution (pure) ----

    /**
     * Computes the outcome of the user's decisions without writing anything: confirmation
     * counts, blocked relationships, count-cap overflow and the merged dependency graph's
     * acyclicity. The importer is only confirmable when [GoalPlanImportSummary.blocked] is empty.
     */
    fun summarize(preview: GoalPlanImportPreview, decisions: Map<String, GoalPlanDecision>): GoalPlanImportSummary {
        val document = preview.document
        val goalChange = preview.changes.first { it.kind == GoalPlanRecordKind.GOAL }
        val goalDecision = decisions[goalChange.uuid] ?: goalChange.defaultDecision
        val forceCopy = goalDecision == GoalPlanDecision.IMPORT_AS_COPY
        val effective = preview.changes.associate { change ->
            val decision = when {
                change.kind == GoalPlanRecordKind.GOAL -> goalDecision
                forceCopy -> GoalPlanDecision.IMPORT_AS_COPY
                else -> decisions[change.uuid] ?: change.defaultDecision
            }
            change.uuid to decision
        }

        var toAdd = 0
        var toUpdate = 0
        var toCopy = 0
        var reused = 0
        var skipped = 0
        var newPhases = 0
        var newMilestones = 0
        var newTasks = 0
        val blocked = mutableListOf<String>()

        if (goalDecision == GoalPlanDecision.SKIP) {
            blocked.add("The goal decision is Skip — nothing can be imported while the goal itself is skipped.")
        }

        preview.changes.forEach { change ->
            val decision = effective.getValue(change.uuid)
            if (decision !in change.allowedDecisions) {
                blocked.add("\"${change.importedTitle}\": \"${decision.label}\" is not available for this record.")
            }
            val writesRow = when (decision) {
                GoalPlanDecision.KEEP_IMPORTED -> change.classification == GoalPlanClassification.NEW ||
                    change.classification == GoalPlanClassification.CONFLICT ||
                    change.classification == GoalPlanClassification.FOREIGN_GOAL_OWNERSHIP
                GoalPlanDecision.IMPORT_AS_COPY -> true
                else -> false
            }
            when (decision) {
                GoalPlanDecision.KEEP_IMPORTED -> when (change.classification) {
                    GoalPlanClassification.NEW -> toAdd++
                    GoalPlanClassification.EQUIVALENT -> reused++ // no write needed
                    else -> toUpdate++
                }
                GoalPlanDecision.IMPORT_AS_COPY -> toCopy++
                GoalPlanDecision.KEEP_EXISTING -> reused++
                GoalPlanDecision.SKIP -> skipped++
            }
            if (writesRow && change.kind == GoalPlanRecordKind.PHASE) newPhases++
            if (writesRow && change.kind == GoalPlanRecordKind.MILESTONE) newMilestones++
            if (writesRow && change.kind == GoalPlanRecordKind.TASK) newTasks++
            // Cross-field merges are validated at preview: a plan-provided date outside the
            // supported range, or a merged start/deadline pair that only this combination
            // creates, is named here instead of failing mid-transaction at confirm.
            if (writesRow) {
                val planDates = planProvidedDates(change, document)
                val outOfRange = planDates.any { (label, date) ->
                    date != null && (date < BackupValidator.earliestDate || date > LocalDate.now().plusYears(100))
                }
                if (outOfRange) blocked.add(
                    "\"${change.importedTitle}\": dates must be between 1900 and 100 years from today.")
                if (decision == GoalPlanDecision.KEEP_IMPORTED) {
                    val inverted = mergedInversion(change, document)
                    if (inverted != null) blocked.add(
                        "\"${change.importedTitle}\" would $inverted after this import — change the decision or the dates.")
                }
            }
            // A child cannot resolve a parent that is skipped: the relationship is blocking until
            // the user skips this record too or changes the parent's decision.
            if (change.kind != GoalPlanRecordKind.GOAL && decision != GoalPlanDecision.SKIP) {
                val parentSkipped = (change.parentUuid != null && effective[change.parentUuid] == GoalPlanDecision.SKIP) ||
                    (change.milestoneUuid != null && effective[change.milestoneUuid] == GoalPlanDecision.SKIP)
                if (parentSkipped) {
                    blocked.add("\"${change.importedTitle}\" needs its imported ${parentLabel(change)}, which is set to Skip. Skip this record too, or change the parent's decision.")
                }
            }
        }

        // Final-database count caps: the importer can never outgrow what an export would accept.
        val counts = preview.existingCounts
        val newGoalRow = when {
            goalDecision == GoalPlanDecision.IMPORT_AS_COPY -> 1
            goalDecision == GoalPlanDecision.KEEP_IMPORTED && preview.goalRowId == null -> 1
            else -> 0
        }
        if (counts.goals + newGoalRow > GoalValidator.MAX_GOALS) {
            blocked.add("LifeTrack cannot hold more than %,d goals.".format(java.util.Locale.ROOT, GoalValidator.MAX_GOALS))
        }
        if (counts.phases + newPhases > GoalValidator.MAX_PHASES) {
            blocked.add("Importing these records would exceed the %,d-phase limit.".format(java.util.Locale.ROOT, GoalValidator.MAX_PHASES))
        }
        if (counts.milestones + newMilestones > GoalValidator.MAX_MILESTONES) {
            blocked.add("Importing these records would exceed the %,d-milestone limit.".format(java.util.Locale.ROOT, GoalValidator.MAX_MILESTONES))
        }
        if (counts.tasks + newTasks > GoalValidator.MAX_TASKS) {
            blocked.add("Importing these records would exceed the %,d-task limit.".format(java.util.Locale.ROOT, GoalValidator.MAX_TASKS))
        }

        // Dependencies: skipped endpoints drop their edges; the rest are added unless already present.
        var edgesToAdd = 0
        var edgesSkipped = 0
        val effectiveEdges = mutableListOf<Pair<String, String>>()
        preview.dependencies.forEach { edge ->
            when {
                effective[edge.taskUuid] == GoalPlanDecision.SKIP || effective[edge.prerequisiteUuid] == GoalPlanDecision.SKIP -> edgesSkipped++
                else -> { edgesToAdd++; effectiveEdges.add(edge.taskUuid to edge.prerequisiteUuid) }
            }
        }
        if (counts.dependencies + edgesToAdd > GoalValidator.MAX_DEPENDENCIES) {
            blocked.add("Importing these records would exceed the %,d-dependency limit.".format(java.util.Locale.ROOT, GoalValidator.MAX_DEPENDENCIES))
        }
        // The schedule-revision ledger cap is enforced inside the apply transaction (with full
        // rollback) — the preview cannot predict the exact number of changed date fields.
        // The merged graph (existing edges + effective plan edges) must stay acyclic. The plan's
        // own graph was acyclic, but merged with existing edges a new cycle can appear.
        mergedCycle(effectiveEdges, preview.existingEdgesByUuid)?.let { chain ->
            blocked.add("These decisions would create a circular task chain: ${chain.joinToString(" → ") { name ->
                preview.changes.firstOrNull { it.uuid == name }?.importedTitle ?: "task"
            }}.")
        }

        val noop = toAdd == 0 && toUpdate == 0 && toCopy == 0 && edgesToAdd == 0
        return GoalPlanImportSummary(
            toAdd = toAdd, toUpdate = toUpdate, toCopy = toCopy, reused = reused, skipped = skipped,
            edgesToAdd = edgesToAdd, edgesSkipped = edgesSkipped, noop = noop,
            blocked = blocked, goalDecision = goalDecision, effectiveDecisions = effective
        )
    }

    private fun parentLabel(change: GoalPlanRecordChange): String = when (change.kind) {
        GoalPlanRecordKind.MILESTONE -> "phase"
        GoalPlanRecordKind.TASK -> if (change.milestoneUuid != null) "phase or milestone" else "phase"
        else -> "parent"
    }

    /** Longest-chain cycle detection over uuid nodes with an explicit stack — no recursion, so
     *  a 10,000-edge chain cannot overflow. Returns one cycle's uuid chain when found. */
    private fun mergedCycle(
        planEdges: List<Pair<String, String>>,
        existingEdges: List<Pair<String, String>>
    ): List<String>? {
        val edges = (existingEdges + planEdges).groupBy({ it.first }, { it.second })
        val visited = mutableSetOf<String>()
        val onPath = mutableSetOf<String>()
        val path = ArrayDeque<String>()
        val stack = ArrayDeque<Pair<String, Boolean>>()
        fun walk(root: String): List<String>? {
            stack.clear(); onPath.clear(); path.clear()
            stack.addLast(root to false)
            while (stack.isNotEmpty()) {
                val (current, isExit) = stack.removeLast()
                if (isExit) { onPath.remove(current); path.removeLast() }
                when {
                    isExit -> Unit
                    current in onPath -> {
                        val start = path.indexOf(current)
                        return path.drop(start) + current
                    }
                    !visited.add(current) -> Unit
                    else -> {
                        onPath.add(current); path.addLast(current)
                        stack.addLast(current to true)
                        edges[current].orEmpty().forEach { stack.addLast(it to false) }
                    }
                }
            }
            return null
        }
        (planEdges.map { it.first } + existingEdges.map { it.first }).distinct().forEach { root ->
            walk(root)?.let { return it }
        }
        return null
    }

    // ---- Transactional apply ----

    /**
     * Applies the user's decisions in ONE Room transaction. The transaction revalidates
     * everything against live data — decision legality, the stale-preview fingerprint, count
     * caps, entity-level validation (calendar ranges included) and the merged dependency graph —
     * then writes goals, phases, milestones, tasks, dependency edges and schedule revisions in
     * dependency order. Any throw rolls the whole import back.
     */
    suspend fun apply(document: GoalPlanDocument, decisions: Map<String, GoalPlanDecision>,
                      reviewed: GoalPlanImportPreview): GoalPlanImportResult =
        db.withTransaction {
            val state = snapshot()
            // Stale-preview protection: the USER'S reviewed preview is the authority. Every row it
            // matched must still carry the same updatedAt and status, and every record uuid that
            // was absent at preview time must still be absent — otherwise the confirmed decisions
            // describe a database the user never reviewed, and the import is refused.
            require(reviewed.document == document) {
                "The confirmed plan does not match the reviewed preview. Restart the import."
            }
            reviewed.fingerprint.forEach { (uuid, seen) ->
                val row = state.rowsByUuid[uuid]
                require(row != null && row.updatedAt == seen.first && row.status == seen.second) {
                    "LifeTrack data changed while this preview was open. Refresh the import and review the plan again."
                }
            }
            reviewed.absentRecordUuids.forEach { uuid ->
                require(state.rowsByUuid[uuid] == null) {
                    "LifeTrack data changed while this preview was open. Refresh the import and review the plan again."
                }
            }
            val summary = summarize(reviewed, decisions)
            require(summary.blocked.isEmpty()) {
                "This import is no longer applicable: ${summary.blocked.first()}"
            }
            applyDecisions(document, state, summary)
        }

    /** Runs inside the apply transaction after revalidation. */
    private suspend fun applyDecisions(
        document: GoalPlanDocument,
        state: DomainSnapshot,
        summary: GoalPlanImportSummary
    ): GoalPlanImportResult {
        val today = LocalDate.now()
        val effective = summary.effectiveDecisions
        val goalDecision = effective.getValue(document.goal.uuid)
        val stats = WriteStats()
        val revisions = mutableListOf<GoalScheduleRevision>()

        if (goalDecision == GoalPlanDecision.SKIP) { // unreachable: summarize blocks it first
            throw GoalPlanImportException("The goal decision is Skip — nothing was imported.")
        }

        // ---- Goal identity resolution ----
        val existingGoal = state.goalByUuid[document.goal.uuid]
        val goalRowId: Long
        when (goalDecision) {
            GoalPlanDecision.KEEP_EXISTING -> {
                goalRowId = existingGoal!!.id
                stats.reused++
            }
            GoalPlanDecision.KEEP_IMPORTED -> {
                if (existingGoal == null) {
                    require(state.counts.goals < GoalValidator.MAX_GOALS) {
                        "LifeTrack cannot hold more than %,d goals.".format(java.util.Locale.ROOT, GoalValidator.MAX_GOALS)
                    }
                    goalRowId = dao.insertGoal(newGoalCandidate(document.goal, state, today))
                    stats.createdGoal = true
                } else if (goalDifferences(document.goal, existingGoal).isEmpty()) {
                    goalRowId = existingGoal.id // equivalent: no unnecessary write
                    stats.reused++
                } else {
                    val goal = existingGoal
                    val updated = goal.copy(
                        title = document.goal.title.trim(),
                        description = document.goal.description?.let { it.trim().takeIf { d -> d.isNotEmpty() } } ?: goal.description,
                        category = document.goal.category ?: goal.category,
                        priority = document.goal.priority ?: goal.priority,
                        plannedStartDate = document.goal.plannedStartDate ?: goal.plannedStartDate,
                        targetDate = document.goal.targetDate ?: goal.targetDate,
                        updatedAt = nextTimestamp(System.currentTimeMillis(), goal.createdAt, goal.updatedAt)
                    )
                    GoalValidator.validateGoal(updated, today)
                    if (existingGoal.targetDate != updated.targetDate) revisions.add(revisionRow(
                        GoalScheduleEntityType.GOAL, updated.uuid, "targetDate",
                        existingGoal.targetDate, updated.targetDate, updated.updatedAt))
                    dao.updateGoal(updated)
                    stats.updatedGoal = true
                    goalRowId = goal.id
                }
            }
            GoalPlanDecision.IMPORT_AS_COPY -> {
                require(state.counts.goals < GoalValidator.MAX_GOALS) {
                    "LifeTrack cannot hold more than %,d goals.".format(java.util.Locale.ROOT, GoalValidator.MAX_GOALS)
                }
                goalRowId = dao.insertGoal(copyGoalCandidate(document.goal, state, today))
                stats.copied++
                stats.createdGoal = true
            }
            else -> throw GoalPlanImportException("Unsupported goal decision.")
        }
        val goalIsNew = stats.createdGoal

        // ---- Children, parents before children ----
        // Resolution maps: plan uuid → resulting row id. Copy decisions mint fresh canonical
        // uuids HERE, inside the authorized import transaction, scoped to this operation; the
        // parsed document itself is never mutated.
        val phaseRowByUuid = mutableMapOf<String, Long>()
        val milestoneRowByUuid = mutableMapOf<String, Long>()
        val taskRowByUuid = mutableMapOf<String, Long>()
        val existingPhases = state.phases.filter { it.goalId == goalRowId }
        val existingTasks = state.tasks.filter { it.goalId == goalRowId }
        val phaseOrderBase = if (goalIsNew) 0 else (existingPhases.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val taskOrderBase = if (goalIsNew) 0 else (existingTasks.maxOfOrNull { it.sortOrder } ?: -1) + 1

        // Fresh count caps, mirroring the goal/dependency/revision checks: rows created outside
        // the document between preview and confirm never trip the fingerprint, so the caps are
        // re-enforced against the live in-transaction state before any write.
        require(state.counts.phases + document.phases.count {
            val decision = effective.getValue(it.uuid)
            (decision == GoalPlanDecision.KEEP_IMPORTED && state.phaseByUuid[it.uuid] == null) ||
                decision == GoalPlanDecision.IMPORT_AS_COPY
        } <= GoalValidator.MAX_PHASES) {
            "LifeTrack cannot hold more than %,d phases.".format(java.util.Locale.ROOT, GoalValidator.MAX_PHASES)
        }
        require(state.counts.milestones + document.milestones.count {
            val decision = effective.getValue(it.uuid)
            (decision == GoalPlanDecision.KEEP_IMPORTED && state.milestoneByUuid[it.uuid] == null) ||
                decision == GoalPlanDecision.IMPORT_AS_COPY
        } <= GoalValidator.MAX_MILESTONES) {
            "LifeTrack cannot hold more than %,d milestones.".format(java.util.Locale.ROOT, GoalValidator.MAX_MILESTONES)
        }
        require(state.counts.tasks + document.tasks.count {
            val decision = effective.getValue(it.uuid)
            (decision == GoalPlanDecision.KEEP_IMPORTED && state.taskByUuid[it.uuid] == null) ||
                decision == GoalPlanDecision.IMPORT_AS_COPY
        } <= GoalValidator.MAX_TASKS) {
            "LifeTrack cannot hold more than %,d tasks.".format(java.util.Locale.ROOT, GoalValidator.MAX_TASKS)
        }

        document.phases.forEach { phase ->
            when (effective.getValue(phase.uuid)) {
                GoalPlanDecision.KEEP_EXISTING -> {
                    phaseRowByUuid[phase.uuid] = state.phaseByUuid.getValue(phase.uuid).id
                    stats.reused++
                }
                GoalPlanDecision.KEEP_IMPORTED -> {
                    val existing = state.phaseByUuid[phase.uuid]
                    if (existing == null) {
                        val stamp = nextTimestamp(System.currentTimeMillis())
                        val candidate = GoalPhase(
                            uuid = phase.uuid, goalId = goalRowId, name = phase.name.trim(),
                            description = phase.description?.trim()?.takeIf { it.isNotEmpty() },
                            sortOrder = phaseOrderBase + (phase.order ?: 0),
                            plannedStartDate = phase.plannedStartDate, plannedEndDate = phase.plannedEndDate,
                            status = PhaseStatus.PLANNED.name, createdAt = stamp, updatedAt = stamp
                        )
                        // Same entity-level rules as manual saves — calendar ranges included — so
                        // an invalid phase can never poison backups or later edits.
                        GoalValidator.validatePhase(candidate, setOf(goalRowId), today)
                        val rowId = dao.insertPhase(candidate)
                        phaseRowByUuid[phase.uuid] = rowId
                        stats.createdPhases++
                    } else if (phaseDifferences(phase, existing).isEmpty()) {
                        phaseRowByUuid[phase.uuid] = existing.id // equivalent: no unnecessary write
                        stats.reused++
                    } else {
                        val stamp = nextTimestamp(System.currentTimeMillis(), existing.createdAt, existing.updatedAt)
                        val updated = existing.copy(
                            name = phase.name.trim(),
                            description = phase.description?.let { it.trim().takeIf { d -> d.isNotEmpty() } } ?: existing.description,
                            plannedStartDate = phase.plannedStartDate ?: existing.plannedStartDate,
                            plannedEndDate = phase.plannedEndDate ?: existing.plannedEndDate,
                            sortOrder = phase.order ?: existing.sortOrder,
                            updatedAt = stamp
                        )
                        GoalValidator.validatePhase(updated, setOf(goalRowId), today)
                        if (existing.plannedStartDate != updated.plannedStartDate) revisions.add(revisionRow(
                            GoalScheduleEntityType.PHASE, existing.uuid, "plannedStartDate",
                            existing.plannedStartDate, updated.plannedStartDate, stamp))
                        if (existing.plannedEndDate != updated.plannedEndDate) revisions.add(revisionRow(
                            GoalScheduleEntityType.PHASE, existing.uuid, "plannedEndDate",
                            existing.plannedEndDate, updated.plannedEndDate, stamp))
                        dao.updatePhase(updated)
                        phaseRowByUuid[phase.uuid] = existing.id
                        stats.updatedPhases++
                    }
                }
                GoalPlanDecision.IMPORT_AS_COPY -> {
                    val stamp = nextTimestamp(System.currentTimeMillis())
                    val candidate = GoalPhase(
                        uuid = UUID.randomUUID().toString(), goalId = goalRowId, name = phase.name.trim(),
                        description = phase.description?.trim()?.takeIf { it.isNotEmpty() },
                        sortOrder = phaseOrderBase + (phase.order ?: 0),
                        plannedStartDate = phase.plannedStartDate, plannedEndDate = phase.plannedEndDate,
                        status = PhaseStatus.PLANNED.name, createdAt = stamp, updatedAt = stamp
                    )
                    GoalValidator.validatePhase(candidate, setOf(goalRowId), today)
                    val rowId = dao.insertPhase(candidate)
                    phaseRowByUuid[phase.uuid] = rowId
                    stats.copied++
                }
                GoalPlanDecision.SKIP -> stats.skipped++
            }
        }

        document.milestones.forEach { milestone ->
            when (effective.getValue(milestone.uuid)) {
                GoalPlanDecision.KEEP_EXISTING -> {
                    milestoneRowByUuid[milestone.uuid] = state.milestoneByUuid.getValue(milestone.uuid).id
                    stats.reused++
                }
                GoalPlanDecision.KEEP_IMPORTED -> {
                    val existing = state.milestoneByUuid[milestone.uuid]
                    if (existing == null) {
                        val stamp = nextTimestamp(System.currentTimeMillis())
                        val rowId = dao.insertMilestone(GoalMilestone(
                            uuid = milestone.uuid, goalId = goalRowId,
                            phaseId = milestone.phaseUuid?.let { phaseRowByUuid.getValue(it) },
                            title = milestone.title.trim(),
                            description = milestone.description?.trim()?.takeIf { it.isNotEmpty() },
                            targetDate = milestone.targetDate,
                            status = MilestoneStatus.PENDING.name, createdAt = stamp, updatedAt = stamp
                        ))
                        milestoneRowByUuid[milestone.uuid] = rowId
                        stats.createdMilestones++
                    } else if (milestoneDifferences(milestone, existing, document, state).isEmpty()) {
                        milestoneRowByUuid[milestone.uuid] = existing.id
                        stats.reused++
                    } else {
                        val stamp = nextTimestamp(System.currentTimeMillis(), existing.createdAt, existing.updatedAt)
                        val phaseId = milestone.phaseUuid?.let { phaseRowByUuid.getValue(it) } ?: existing.phaseId
                        val updated = existing.copy(
                            title = milestone.title.trim(),
                            description = milestone.description?.let { it.trim().takeIf { d -> d.isNotEmpty() } } ?: existing.description,
                            targetDate = milestone.targetDate ?: existing.targetDate,
                            phaseId = phaseId,
                            updatedAt = stamp
                        )
                        GoalValidator.validateMilestone(updated, setOf(goalRowId),
                            if (phaseId != null) mapOf(phaseId to goalRowId) else emptyMap(), today)
                        if (existing.targetDate != updated.targetDate) revisions.add(revisionRow(
                            GoalScheduleEntityType.MILESTONE, existing.uuid, "targetDate",
                            existing.targetDate, updated.targetDate, stamp))
                        dao.updateMilestone(updated)
                        milestoneRowByUuid[milestone.uuid] = existing.id
                        stats.updatedMilestones++
                    }
                }
                GoalPlanDecision.IMPORT_AS_COPY -> {
                    val stamp = nextTimestamp(System.currentTimeMillis())
                    val rowId = dao.insertMilestone(GoalMilestone(
                        uuid = UUID.randomUUID().toString(), goalId = goalRowId,
                        phaseId = milestone.phaseUuid?.let { phaseRowByUuid.getValue(it) },
                        title = milestone.title.trim(),
                        description = milestone.description?.trim()?.takeIf { it.isNotEmpty() },
                        targetDate = milestone.targetDate,
                        status = MilestoneStatus.PENDING.name, createdAt = stamp, updatedAt = stamp
                    ))
                    milestoneRowByUuid[milestone.uuid] = rowId
                    stats.copied++
                }
                GoalPlanDecision.SKIP -> stats.skipped++
            }
        }

        document.tasks.forEach { task ->
            when (effective.getValue(task.uuid)) {
                GoalPlanDecision.KEEP_EXISTING -> {
                    taskRowByUuid[task.uuid] = state.taskByUuid.getValue(task.uuid).id
                    stats.reused++
                }
                GoalPlanDecision.KEEP_IMPORTED -> {
                    val existing = state.taskByUuid[task.uuid]
                    if (existing == null) {
                        val stamp = nextTimestamp(System.currentTimeMillis())
                        val rowId = dao.insertTask(GoalTask(
                            uuid = task.uuid, goalId = goalRowId,
                            phaseId = task.phaseUuid?.let { phaseRowByUuid.getValue(it) },
                            milestoneId = task.milestoneUuid?.let { milestoneRowByUuid.getValue(it) },
                            title = task.title.trim(),
                            description = task.description?.trim()?.takeIf { it.isNotEmpty() },
                            sortOrder = taskOrderBase + (task.order ?: 0),
                            plannedDate = task.plannedDate, deadline = task.deadline,
                            estimatedMinutes = task.estimatedMinutes,
                            status = GoalTaskStatus.NOT_STARTED.name, createdAt = stamp, updatedAt = stamp
                        ))
                        taskRowByUuid[task.uuid] = rowId
                        stats.createdTasks++
                    } else if (taskDifferences(task, existing, document, state).isEmpty()) {
                        taskRowByUuid[task.uuid] = existing.id
                        stats.reused++
                    } else {
                        val stamp = nextTimestamp(System.currentTimeMillis(), existing.createdAt, existing.updatedAt)
                        val phaseId = task.phaseUuid?.let { phaseRowByUuid.getValue(it) } ?: existing.phaseId
                        val milestoneId = task.milestoneUuid?.let { milestoneRowByUuid.getValue(it) } ?: existing.milestoneId
                        val updated = existing.copy(
                            title = task.title.trim(),
                            description = task.description?.let { it.trim().takeIf { d -> d.isNotEmpty() } } ?: existing.description,
                            plannedDate = task.plannedDate ?: existing.plannedDate,
                            deadline = task.deadline ?: existing.deadline,
                            estimatedMinutes = task.estimatedMinutes ?: existing.estimatedMinutes,
                            phaseId = phaseId,
                            milestoneId = milestoneId,
                            sortOrder = task.order ?: existing.sortOrder,
                            updatedAt = stamp
                        )
                        // Status, completedAt, uuid and id are structurally preserved — an import
                        // can never rewrite when work actually happened.
                        GoalValidator.validateTask(updated, setOf(goalRowId),
                            if (phaseId != null) mapOf(phaseId to goalRowId) else emptyMap(),
                            if (milestoneId != null) mapOf(milestoneId to goalRowId) else emptyMap(), today)
                        if (existing.plannedDate != updated.plannedDate) revisions.add(revisionRow(
                            GoalScheduleEntityType.TASK, existing.uuid, "plannedDate",
                            existing.plannedDate, updated.plannedDate, stamp))
                        if (existing.deadline != updated.deadline) revisions.add(revisionRow(
                            GoalScheduleEntityType.TASK, existing.uuid, "deadline",
                            existing.deadline, updated.deadline, stamp))
                        dao.updateTask(updated)
                        taskRowByUuid[task.uuid] = existing.id
                        stats.updatedTasks++
                    }
                }
                GoalPlanDecision.IMPORT_AS_COPY -> {
                    val stamp = nextTimestamp(System.currentTimeMillis())
                    val rowId = dao.insertTask(GoalTask(
                        uuid = UUID.randomUUID().toString(), goalId = goalRowId,
                        phaseId = task.phaseUuid?.let { phaseRowByUuid.getValue(it) },
                        milestoneId = task.milestoneUuid?.let { milestoneRowByUuid.getValue(it) },
                        title = task.title.trim(),
                        description = task.description?.trim()?.takeIf { it.isNotEmpty() },
                        sortOrder = taskOrderBase + (task.order ?: 0),
                        plannedDate = task.plannedDate, deadline = task.deadline,
                        estimatedMinutes = task.estimatedMinutes,
                        status = GoalTaskStatus.NOT_STARTED.name, createdAt = stamp, updatedAt = stamp
                    ))
                    taskRowByUuid[task.uuid] = rowId
                    stats.copied++
                }
                GoalPlanDecision.SKIP -> stats.skipped++
            }
        }

        // ---- Dependencies: resolve endpoints, skip dropped ones, dedupe, cap, write ----
        val existingEdgeSet = state.dependencies.map { it.dependentTaskId to it.prerequisiteTaskId }.toSet()
        val writtenEdges = LinkedHashSet<Pair<Long, Long>>()
        document.dependencies.forEach { edge ->
            val dependentRow = taskRowByUuid[edge.taskUuid]
            val prerequisiteRow = taskRowByUuid[edge.prerequisiteUuid]
            if (dependentRow == null || prerequisiteRow == null) { stats.dependenciesSkipped++; return@forEach }
            val pair = dependentRow to prerequisiteRow
            if (pair !in existingEdgeSet && pair !in writtenEdges) writtenEdges.add(pair)
        }
        require(state.counts.dependencies + writtenEdges.size <= GoalValidator.MAX_DEPENDENCIES) {
            "This plan carries too many task dependencies."
        }
        writtenEdges.forEach { (dependent, prerequisite) ->
            dao.insertDependency(GoalTaskDependency(dependent, prerequisite))
            stats.dependenciesAdded++
        }

        // ---- Schedule revisions: bounded, then written with the import ----
        if (revisions.isNotEmpty()) {
            require(state.counts.revisions + revisions.size <= GoalValidator.MAX_SCHEDULE_REVISIONS) {
                "LifeTrack cannot hold more than %,d schedule revisions.".format(java.util.Locale.ROOT, GoalValidator.MAX_SCHEDULE_REVISIONS)
            }
        }

        // ---- Final integrity checks over the merged goal state ----
        val finalPhases = dao.phasesForGoal(goalRowId)
        val finalMilestones = dao.milestonesForGoal(goalRowId)
        val finalTasks = dao.tasksForGoal(goalRowId)
        val phaseOwnership = finalPhases.associate { it.id to it.goalId }
        val milestoneOwnership = finalMilestones.associate { it.id to it.goalId }
        finalPhases.forEach { phase ->
            GoalValidator.validatePhase(phase, setOf(goalRowId), today)
        }
        finalTasks.forEach { task ->
            GoalValidator.validateTask(task, setOf(task.goalId), phaseOwnership, milestoneOwnership, today)
        }
        finalMilestones.forEach { milestone ->
            GoalValidator.validateMilestone(milestone, setOf(goalRowId), phaseOwnership, today)
        }
        GoalValidator.validateDependencyGraph(dao.allTasks(), dao.allDependencies())

        if (revisions.isNotEmpty()) dao.insertScheduleRevisions(revisions)

        return GoalPlanImportResult(
            goalId = goalRowId,
            createdGoal = stats.createdGoal,
            updatedGoal = stats.updatedGoal,
            createdPhases = stats.createdPhases,
            createdMilestones = stats.createdMilestones,
            createdTasks = stats.createdTasks,
            updatedPhases = stats.updatedPhases,
            updatedMilestones = stats.updatedMilestones,
            updatedTasks = stats.updatedTasks,
            copiedRecords = stats.copied,
            reusedRecords = stats.reused,
            skippedRecords = stats.skipped,
            dependenciesAdded = stats.dependenciesAdded,
            dependenciesSkipped = stats.dependenciesSkipped,
            scheduleRevisions = revisions.size,
            noop = stats.createdGoal.not() && stats.updatedGoal.not() &&
                stats.createdPhases == 0 && stats.createdMilestones == 0 && stats.createdTasks == 0 &&
                stats.updatedPhases == 0 && stats.updatedMilestones == 0 && stats.updatedTasks == 0 &&
                stats.copied == 0 && stats.dependenciesAdded == 0
        )
    }

    /** A brand-new goal from the plan: PLANNED, appended to the hub, plan-provided identity. */
    private fun newGoalCandidate(goal: GoalPlanGoal, state: DomainSnapshot, today: LocalDate): Goal {
        val stamp = nextTimestamp(System.currentTimeMillis())
        val candidate = Goal(
            uuid = goal.uuid,
            title = goal.title.trim(),
            description = goal.description?.trim()?.takeIf { it.isNotEmpty() },
            category = goal.category ?: "Personal",
            priority = goal.priority ?: GoalPriority.MEDIUM.name,
            plannedStartDate = goal.plannedStartDate,
            targetDate = goal.targetDate,
            status = GoalStatus.PLANNED.name,
            sortOrder = (state.goals.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = stamp, updatedAt = stamp
        )
        GoalValidator.validateGoal(candidate, today)
        return candidate
    }

    /** An independent copy of the plan's goal under a freshly minted uuid; PLANNED, no history. */
    private fun copyGoalCandidate(goal: GoalPlanGoal, state: DomainSnapshot, today: LocalDate): Goal {
        val stamp = nextTimestamp(System.currentTimeMillis())
        val candidate = Goal(
            uuid = UUID.randomUUID().toString(),
            title = goal.title.trim(),
            description = goal.description?.trim()?.takeIf { it.isNotEmpty() },
            category = goal.category ?: "Personal",
            priority = goal.priority ?: GoalPriority.MEDIUM.name,
            plannedStartDate = goal.plannedStartDate,
            targetDate = goal.targetDate,
            status = GoalStatus.PLANNED.name,
            sortOrder = (state.goals.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = stamp, updatedAt = stamp
        )
        GoalValidator.validateGoal(candidate, today)
        return candidate
    }

    private fun revisionRow(entityType: GoalScheduleEntityType, entityUuid: String, field: String,
                            formerDate: LocalDate?, newDate: LocalDate?, changedAt: Long) =
        GoalScheduleRevision(
            entityType = entityType.name, entityUuid = entityUuid, field = field,
            formerDate = formerDate, newDate = newDate, scope = "SINGLE", changedAt = changedAt
        )

    /** Plan-provided date fields by label for one record — the values this import would write. */
    private fun planProvidedDates(change: GoalPlanRecordChange, document: GoalPlanDocument):
            List<Pair<String, LocalDate?>> = when (change.kind) {
        GoalPlanRecordKind.GOAL -> document.goal.let {
            listOf("Planned start" to it.plannedStartDate, "Target date" to it.targetDate)
        }
        GoalPlanRecordKind.PHASE -> document.phases.firstOrNull { it.uuid == change.uuid }?.let {
            listOf("Planned start" to it.plannedStartDate, "Planned end" to it.plannedEndDate)
        } ?: emptyList()
        GoalPlanRecordKind.MILESTONE -> document.milestones.firstOrNull { it.uuid == change.uuid }?.let {
            listOf("Target date" to it.targetDate)
        } ?: emptyList()
        GoalPlanRecordKind.TASK -> document.tasks.firstOrNull { it.uuid == change.uuid }?.let {
            listOf("Planned date" to it.plannedDate, "Deadline" to it.deadline)
        } ?: emptyList()
    }

    /** The first inverted date pair a merged (plan-provided ∪ stored) record would produce, or null. */
    private fun mergedInversion(change: GoalPlanRecordChange, document: GoalPlanDocument): String? {
        val planDates = planProvidedDates(change, document).toMap()
        val merged = { label: String -> planDates[label] ?: change.existingDates[label] }
        return when (change.kind) {
            GoalPlanRecordKind.GOAL -> {
                val start = merged("Planned start"); val target = merged("Target date")
                if (start != null && target != null && start.isAfter(target)) "end before it starts" else null
            }
            GoalPlanRecordKind.PHASE -> {
                val start = merged("Planned start"); val end = merged("Planned end")
                if (start != null && end != null && start.isAfter(end)) "end before it starts" else null
            }
            GoalPlanRecordKind.TASK -> {
                val planned = merged("Planned date"); val deadline = merged("Deadline")
                if (planned != null && deadline != null && planned.isAfter(deadline)) "be planned after its deadline" else null
            }
            else -> null
        }
    }

    /** Mutable apply-time counters, folded into the immutable result at the end. */
    private class WriteStats {
        var createdGoal = false
        var updatedGoal = false
        var createdPhases = 0
        var createdMilestones = 0
        var createdTasks = 0
        var updatedPhases = 0
        var updatedMilestones = 0
        var updatedTasks = 0
        var copied = 0
        var reused = 0
        var skipped = 0
        var dependenciesAdded = 0
        var dependenciesSkipped = 0
    }
}

/** Record identity inside the goal plan document. */
enum class GoalPlanRecordKind(val label: String) {
    GOAL("Goal"), PHASE("Phase"), MILESTONE("Milestone"), TASK("Task")
}

/** How a document record relates to the current database. */
enum class GoalPlanClassification(val label: String) {
    NEW("New"),
    EQUIVALENT("Already imported"),
    CONFLICT("Has changes"),
    /** The uuid exists in the goal domain but as a different entity type — identity never
     *  crosses types, so the record may only be skipped or copied under a fresh uuid. */
    CROSS_TYPE_COLLISION("Identifier used by another record type"),
    FOREIGN_GOAL_OWNERSHIP("Belongs to another goal")
}

/** What the user may ask the importer to do with one record. */
enum class GoalPlanDecision(val label: String) {
    KEEP_EXISTING("Keep existing"),
    KEEP_IMPORTED("Use imported"),
    IMPORT_AS_COPY("Copy as new"),
    SKIP("Skip")
}

enum class GoalPlanEdgeStatus { NEW, ALREADY_PRESENT }

/** One user-visible difference between the stored record and the imported one. */
data class GoalPlanFieldDifference(val field: String, val existing: String?, val imported: String?)

data class GoalDomainCounts(
    val goals: Int,
    val phases: Int,
    val milestones: Int,
    val tasks: Int,
    val dependencies: Int,
    val revisions: Int
)

/** One record's classification and decision surface (the UI shows titles and differences, never uuids). */
data class GoalPlanRecordChange(
    val kind: GoalPlanRecordKind,
    val uuid: String,
    val importedTitle: String,
    val existingTitle: String?,
    val classification: GoalPlanClassification,
    val differences: List<GoalPlanFieldDifference>,
    val defaultDecision: GoalPlanDecision,
    val allowedDecisions: Set<GoalPlanDecision>,
    /** Plan parent references, for blocked-relationship resolution. */
    val parentUuid: String? = null,
    val milestoneUuid: String? = null,
    /** Stored date fields by label ("Planned start", "Deadline", …) — the other half of the
     *  merged ranges the preview checks before anything is written. */
    val existingDates: Map<String, LocalDate?> = emptyMap()
)

data class GoalPlanDependencyChange(
    val taskUuid: String,
    val prerequisiteUuid: String,
    val taskTitle: String,
    val prerequisiteTitle: String,
    val initialStatus: GoalPlanEdgeStatus
)

/** Read-only result of classification: everything the preview and the confirmation need. */
data class GoalPlanImportPreview(
    val document: GoalPlanDocument,
    val changes: List<GoalPlanRecordChange>,
    val dependencies: List<GoalPlanDependencyChange>,
    /** Existing goal row id when the document goal matched, for goal-context display. */
    val goalRowId: Long?,
    /** (updatedAt, status) of every matched row at classification time — the stale check. */
    val fingerprint: Map<String, Pair<Long, String>>,
    /** Document record uuids that had NO database row at classification time — they must still
     *  be absent when the import applies, or the reviewed decisions are stale. */
    val absentRecordUuids: Set<String>,
    /** uuid pairs of every dependency edge currently in the database, for merged-graph checks. */
    val existingEdgesByUuid: List<Pair<String, String>>,
    val existingCounts: GoalDomainCounts,
    val classifiedAt: Long
)

/** Decision outcome counts for the confirmation section and the honest no-op check. */
data class GoalPlanImportSummary(
    val toAdd: Int,
    val toUpdate: Int,
    val toCopy: Int,
    val reused: Int,
    val skipped: Int,
    val edgesToAdd: Int,
    val edgesSkipped: Int,
    val noop: Boolean,
    /** Blocking problems under the current decisions; the importer is confirmable only when empty. */
    val blocked: List<String>,
    val goalDecision: GoalPlanDecision,
    val effectiveDecisions: Map<String, GoalPlanDecision>
)

/** What the import actually did — real counts, never a misleading success number. */
data class GoalPlanImportResult(
    val goalId: Long?,
    val createdGoal: Boolean,
    val updatedGoal: Boolean,
    val createdPhases: Int,
    val createdMilestones: Int,
    val createdTasks: Int,
    val updatedPhases: Int,
    val updatedMilestones: Int,
    val updatedTasks: Int,
    val copiedRecords: Int,
    val reusedRecords: Int,
    val skippedRecords: Int,
    val dependenciesAdded: Int,
    val dependenciesSkipped: Int,
    val scheduleRevisions: Int,
    val noop: Boolean
)

/**
 * Bounded, strict file reading for Goal Plan imports: the document provider's reported size is
 * never trusted — the stream is capped just past the format limit, decoded as strict UTF-8, and
 * provider failures surface as actionable import errors. Callers run this off the main thread.
 */
object GoalPlanImportFileReader {
    private const val CHUNK_BYTES = 64 * 1024

    /** Reads [opener]'s stream as strict UTF-8 text, bounded by the Goal Plan size limit. */
    fun read(opener: () -> InputStream?): String = try {
        val stream = opener() ?: throw GoalPlanImportException("The selected file could not be opened.")
        stream.use { input -> readBounded(input) }
    } catch (error: GoalPlanImportException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw GoalPlanImportException("The selected file could not be read: ${error.message ?: "unknown error"}.")
    }

    private fun readBounded(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (buffer.size() + read > GoalPlanContract.MAX_FILE_BYTES) {
                throw GoalPlanImportException("Goal Plan files must be 2 MB or smaller. Choose a smaller file.")
            }
            buffer.write(chunk, 0, read)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw GoalPlanImportException("The selected file is not valid UTF-8 text.")
        }
    }

    /** A full LifeTrack backup is a different document type — detected by its own envelope key. */
    fun looksLikeLifeTrackBackup(text: String): Boolean = text.contains("\"formatVersion\"")
}
