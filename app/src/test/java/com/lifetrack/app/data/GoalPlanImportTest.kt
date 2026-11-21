package com.lifetrack.app.data

import android.app.Application
import androidx.room.Room
import com.lifetrack.app.data.GoalPlanContract.KIND
import java.io.ByteArrayInputStream
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Goal Plan import pipeline against the real Room database: bounded file reading, uuid-based
 * classification, all four conflict decisions, copy remapping, merged-graph validation, stale
 * previews, transactional rollback and the data-preservation guarantees. The database state is
 * asserted exactly after every failure — not just "no new goal".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GoalPlanImportTest {
    private lateinit var database: AppDatabase
    private lateinit var goals: GoalRepository
    private lateinit var habits: HabitRepository
    private lateinit var service: GoalPlanImportService
    private val today get() = LocalDate.now()

    private val goalUuid = "a1000000-0000-4000-8000-000000000001"
    private val phaseUuid = "a1000000-0000-4000-8000-000000000002"
    private val milestoneUuid = "a1000000-0000-4000-8000-000000000003"
    private val taskUuid = "a1000000-0000-4000-8000-000000000004"
    private val task2Uuid = "a1000000-0000-4000-8000-000000000005"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        goals = GoalRepository(database)
        habits = HabitRepository(database, context)
        service = GoalPlanImportService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- Document builders ----

    private fun planGoal(
        uuid: String = goalUuid, title: String = "Learn ML",
        description: String? = null, category: String? = null, priority: String? = null,
        plannedStartDate: LocalDate? = null, targetDate: LocalDate? = null
    ) = GoalPlanGoal(uuid, title, description, category, priority, plannedStartDate, targetDate)

    private fun planPhase(
        uuid: String = phaseUuid, name: String = "Foundations", order: Int? = 0,
        start: LocalDate? = null, end: LocalDate? = null
    ) = GoalPlanPhase(uuid, name, null, order, start, end)

    private fun planMilestone(
        uuid: String = milestoneUuid, title: String = "Basics down", phaseUuid: String? = this.phaseUuid,
        targetDate: LocalDate? = null
    ) = GoalPlanMilestone(uuid, title, null, phaseUuid, targetDate)

    private fun planTask(
        uuid: String = taskUuid, title: String = "Study vectors", phaseUuid: String? = this.phaseUuid,
        milestoneUuid: String? = null, order: Int? = 0,
        plannedDate: LocalDate? = null, deadline: LocalDate? = null, estimatedMinutes: Int? = null
    ) = GoalPlanTask(uuid, title, null, order, phaseUuid, milestoneUuid, plannedDate, deadline, estimatedMinutes)

    private fun document(
        goal: GoalPlanGoal = planGoal(),
        phases: List<GoalPlanPhase> = listOf(planPhase()),
        milestones: List<GoalPlanMilestone> = listOf(planMilestone()),
        tasks: List<GoalPlanTask> = listOf(planTask()),
        dependencies: List<GoalPlanDependency> = emptyList()
    ) = GoalPlanDocument(KIND, 1, goal.uuid, goal, phases, milestones, tasks, dependencies)

    private fun decisionsOf(document: GoalPlanDocument, vararg overrides: Pair<String, GoalPlanDecision>) =
        document.run {
            val defaults = listOf(goal).map { it.uuid to GoalPlanDecision.KEEP_IMPORTED } +
                phases.map { it.uuid to GoalPlanDecision.KEEP_IMPORTED } +
                milestones.map { it.uuid to GoalPlanDecision.KEEP_IMPORTED } +
                tasks.map { it.uuid to GoalPlanDecision.KEEP_IMPORTED }
            (defaults + overrides).toMap()
        }

    private fun previewOf(document: GoalPlanDocument) = runBlocking { service.buildPreview(document) }

    private fun classifyOf(document: GoalPlanDocument, uuid: String): GoalPlanRecordChange =
        previewOf(document).changes.first { it.uuid == uuid }

    // ---- A. File handling ----

    @Test fun `the bounded reader accepts a document with a byte order mark`() {
        val text = "\uFEFF" + GoalPlanParser.write(document())
        val read = GoalPlanImportFileReader.read { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }
        assertEquals(goalUuid, GoalPlanParser.parse(read).goal.uuid)
    }

    @Test fun `an oversized file is refused before it can exhaust memory`() {
        val oversized = ByteArray(GoalPlanContract.MAX_FILE_BYTES + 1) { 'x'.code.toByte() }
        try {
            GoalPlanImportFileReader.read { ByteArrayInputStream(oversized) }
            fail("An oversized file must be refused.")
        } catch (error: GoalPlanImportException) {
            assertTrue(error.message!!.contains("2 MB"))
        }
    }

    @Test fun `malformed utf-8 is refused`() {
        try {
            GoalPlanImportFileReader.read { ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x7B)) }
            fail("Malformed UTF-8 must be refused.")
        } catch (error: GoalPlanImportException) {
            assertTrue(error.message!!.contains("UTF-8"))
        }
    }

    @Test fun `a failed provider read surfaces an actionable message`() {
        try {
            GoalPlanImportFileReader.read { null }
            fail("A failed provider read must be refused.")
        } catch (error: GoalPlanImportException) {
            assertTrue(error.message!!.contains("could not be opened"))
        }
    }

    @Test fun `a full life-track backup is detected as a different document type`() {
        val backup = BackupValidator.json.encodeToString(LifeTrackBackup(
            formatVersion = 12, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Read", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList()
        ))
        assertTrue(GoalPlanImportFileReader.looksLikeLifeTrackBackup(backup))
        assertFalse(GoalPlanImportFileReader.looksLikeLifeTrackBackup(GoalPlanParser.write(document())))
    }

    // ---- B. Preview and comparison ----

    @Test fun `a new goal classifies every record as new and the preview writes nothing`() = runBlocking {
        val plan = document()
        val before = database.goalDao().allGoals().size
        val preview = previewOf(plan)
        assertTrue(preview.changes.all { it.classification == GoalPlanClassification.NEW })
        assertTrue(preview.changes.all { it.defaultDecision == GoalPlanDecision.KEEP_IMPORTED })
        assertEquals(before, database.goalDao().allGoals().size)
        assertEquals(0, database.goalDao().allTasks().size)
        assertEquals(0, database.goalDao().allPhases().size)
    }

    @Test fun `an identical re-import classifies everything as already imported and is a no-op`() = runBlocking {
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)))
        val decisions = decisionsOf(plan)
        val first = service.apply(plan, decisions, previewOf(plan))
        assertFalse(first.noop)
        // Second import of the SAME content: nothing to do.
        val preview = previewOf(plan)
        val notEquivalent = preview.changes.filter { it.classification != GoalPlanClassification.EQUIVALENT }
        assertTrue("not equivalent: " + notEquivalent.joinToString { "${it.kind} \"${it.importedTitle}\" -> ${it.classification} ${it.differences}" },
            preview.changes.all { it.classification == GoalPlanClassification.EQUIVALENT })
        val summary = service.summarize(preview, decisions)
        assertTrue(summary.noop)
        val second = service.apply(plan, decisions, previewOf(plan))
        assertTrue(second.noop)
        assertEquals(1, database.goalDao().allGoals().size)
        assertEquals(1, database.goalDao().allTasks().size)
    }

    @Test fun `the same title under a different uuid classifies as new, never a conflict`() {
        runBlocking {
            goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        }
        val change = classifyOf(document(), goalUuid)
        assertEquals(GoalPlanClassification.NEW, change.classification)
    }

    @Test fun `the same uuid with a changed title classifies a conflict naming the difference`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML", plannedStartDate = today, targetDate = today.plusWeeks(12)))
        goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study vectors old"))
        val plan = document(
            goal = planGoal(plannedStartDate = today, targetDate = today.plusWeeks(12)),
            phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(title = "Study vectors"))
        )
        val change = classifyOf(plan, taskUuid)
        assertEquals(GoalPlanClassification.CONFLICT, change.classification)
        val difference = change.differences.first { it.field == "Title" }
        assertEquals("Study vectors old", difference.existing)
        assertEquals("Study vectors", difference.imported)
        assertEquals(GoalPlanDecision.KEEP_EXISTING, change.defaultDecision)
    }

    @Test fun `a child uuid owned by another goal is a structural conflict that forbids updating`() = runBlocking {
        val other = goals.saveGoal(Goal(uuid = "", title = "Other goal"))
        goals.savePhase(GoalPhase(uuid = phaseUuid, goalId = other.id, name = "Foreign phase"))
        val change = classifyOf(document(), phaseUuid)
        assertEquals(GoalPlanClassification.FOREIGN_GOAL_OWNERSHIP, change.classification)
        assertEquals(setOf(GoalPlanDecision.SKIP, GoalPlanDecision.IMPORT_AS_COPY), change.allowedDecisions)
        assertEquals(GoalPlanDecision.SKIP, change.defaultDecision)
    }

    @Test fun `a task uuid that belongs to a milestone is a cross-type collision`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        goals.saveMilestone(GoalMilestone(uuid = taskUuid, goalId = goal.id, title = "A milestone"))
        val plan = document(phases = emptyList(), milestones = emptyList(), tasks = listOf(planTask(phaseUuid = null)))
        val change = classifyOf(plan, taskUuid)
        assertEquals(GoalPlanClassification.CROSS_TYPE_COLLISION, change.classification)
        assertEquals(setOf(GoalPlanDecision.SKIP, GoalPlanDecision.IMPORT_AS_COPY), change.allowedDecisions)
    }

    @Test fun `the goal uuid colliding with a task is a cross-type collision`() = runBlocking {
        val other = goals.saveGoal(Goal(uuid = "", title = "Other"))
        goals.saveTask(GoalTask(uuid = goalUuid, goalId = other.id, title = "A task"))
        val change = classifyOf(document(), goalUuid)
        assertEquals(GoalPlanClassification.CROSS_TYPE_COLLISION, change.classification)
        assertEquals(GoalPlanDecision.SKIP, change.defaultDecision)
    }

    @Test fun `plan text is normalized before comparison so padding is not a conflict`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val plan = document(goal = planGoal(title = "  Learn ML  "), phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        val change = classifyOf(plan, goalUuid)
        assertEquals(GoalPlanClassification.EQUIVALENT, change.classification)
        assertEquals(goal.id, previewOf(plan).goalRowId)
    }

    // ---- C/D. Decisions ----

    @Test fun `keep existing preserves the record, its status and its completion instant`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study vectors old"))
        goals.saveTask(task.copy(status = GoalTaskStatus.COMPLETED.name))
        val completed = database.goalDao().task(task.id)!!
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(title = "Study vectors", phaseUuid = null)))
        val result = service.apply(plan, decisionsOf(plan, taskUuid to GoalPlanDecision.KEEP_EXISTING,
            goalUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        val after = database.goalDao().task(task.id)!!
        assertEquals("Study vectors old", after.title)
        assertEquals(GoalTaskStatus.COMPLETED.name, after.status)
        assertEquals(completed.completedAt, after.completedAt)
        assertEquals(completed.updatedAt, after.updatedAt)
        assertEquals(2, result.reusedRecords) // the goal and the task are both kept
    }

    @Test fun `use imported updates metadata while preserving status and completion history`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study vectors old",
            plannedDate = today.minusDays(2)))
        goals.saveTask(task.copy(status = GoalTaskStatus.COMPLETED.name))
        val completed = database.goalDao().task(task.id)!!
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(title = "Study vectors", plannedDate = today, phaseUuid = null)))
        val result = service.apply(plan, decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        val after = database.goalDao().task(task.id)!!
        assertEquals("Study vectors", after.title)
        assertEquals(GoalTaskStatus.COMPLETED.name, after.status)
        assertEquals(completed.completedAt, after.completedAt)
        assertEquals(completed.createdAt, after.createdAt)
        assertTrue(after.updatedAt > completed.updatedAt)
        assertEquals(today, after.plannedDate)
        assertEquals(1, result.updatedTasks)
        // The planned-date move is recorded in the audit trail with the real former date.
        val revision = database.goalDao().allScheduleRevisions().single()
        assertEquals(GoalScheduleEntityType.TASK.name, revision.entityType)
        assertEquals(taskUuid, revision.entityUuid)
        assertEquals("plannedDate", revision.field)
        assertEquals(today.minusDays(2), revision.formerDate)
        assertEquals(today, revision.newDate)
    }

    @Test fun `missing plan fields never erase stored values under use imported`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study",
            deadline = today.plusDays(5), estimatedMinutes = 90))
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(title = "Study harder", estimatedMinutes = 120, phaseUuid = null)))
        service.apply(plan, decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        val after = database.goalDao().taskByUuid(taskUuid)!!
        assertEquals("Study harder", after.title)
        assertEquals(today.plusDays(5), after.deadline)
        assertEquals(120, after.estimatedMinutes)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
    }

    @Test fun `use imported on an equivalent record writes nothing`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study vectors"))
        val plan = document(phases = emptyList(), milestones = emptyList(), tasks = listOf(planTask(phaseUuid = null)))
        val result = service.apply(plan, decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        assertEquals(task.updatedAt, database.goalDao().task(task.id)!!.updatedAt)
        assertTrue(result.noop)
    }

    @Test fun `import as copy creates an independent task with a fresh identity and no history`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Original"))
        goals.saveTask(task.copy(status = GoalTaskStatus.COMPLETED.name))
        val plan = document(phases = emptyList(), milestones = emptyList(), tasks = listOf(planTask(phaseUuid = null)))
        val result = service.apply(plan, decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING,
            taskUuid to GoalPlanDecision.IMPORT_AS_COPY), previewOf(plan))
        val original = database.goalDao().task(task.id)!!
        assertEquals(GoalTaskStatus.COMPLETED.name, original.status)
        assertEquals("Original", original.title)
        val copy = database.goalDao().allTasks().first { it.uuid != taskUuid }
        assertTrue(copy.uuid != taskUuid)
        assertEquals(goal.id, copy.goalId)
        assertEquals(GoalTaskStatus.NOT_STARTED.name, copy.status)
        assertNull(copy.completedAt)
        assertEquals("Study vectors", copy.title)
        assertEquals(1, result.copiedRecords)
    }

    @Test fun `copying the goal copies the whole plan into a new roadmap and leaves the original untouched`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML", targetDate = today.plusWeeks(12)))
        val task = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study"))
        goals.saveTask(task.copy(status = GoalTaskStatus.COMPLETED.name))
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(24)))
        val result = service.apply(plan, decisionsOf(plan, goalUuid to GoalPlanDecision.IMPORT_AS_COPY), previewOf(plan))
        assertEquals(2, database.goalDao().allGoals().size)
        assertEquals(2, database.goalDao().allTasks().size)
        val original = database.goalDao().goal(goal.id)!!
        assertEquals(goalUuid, original.uuid)
        assertEquals(today.plusWeeks(12), original.targetDate)
        assertEquals(GoalTaskStatus.COMPLETED.name, database.goalDao().task(task.id)!!.status)
        val copy = database.goalDao().allGoals().first { it.uuid != goalUuid }
        assertTrue(copy.uuid != goalUuid)
        assertEquals(today.plusWeeks(24), copy.targetDate)
        assertEquals(GoalStatus.PLANNED.name, copy.status)
        val copyTask = database.goalDao().allTasks().first { it.goalId == copy.id }
        assertEquals(GoalTaskStatus.NOT_STARTED.name, copyTask.status)
        assertNull(copyTask.completedAt)
        // "View goal" opens the freshly created copy, not the original.
        assertEquals(copy.id, result.goalId)
        assertFalse(result.noop)
    }

    @Test fun `skip omits the record without deleting its existing match and drops dependent edges`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val prerequisite = goals.saveTask(GoalTask(uuid = task2Uuid, goalId = goal.id, title = "Install Python"))
        val dependent = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study", sortOrder = 1))
        goals.addDependency(dependent.id, prerequisite.id)
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(uuid = task2Uuid, title = "Install Python", phaseUuid = null),
                planTask(title = "Study", phaseUuid = null)),
            dependencies = listOf(GoalPlanDependency(taskUuid, task2Uuid)))
        val preview = previewOf(plan)
        val summary = service.summarize(preview, decisionsOf(plan, task2Uuid to GoalPlanDecision.SKIP,
            taskUuid to GoalPlanDecision.KEEP_EXISTING, goalUuid to GoalPlanDecision.KEEP_EXISTING))
        val result = service.apply(plan, mapOf(
            goalUuid to GoalPlanDecision.KEEP_EXISTING, task2Uuid to GoalPlanDecision.SKIP,
            taskUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        assertEquals(2, database.goalDao().allTasks().size)
        assertEquals(1, database.goalDao().allDependencies().size)
        assertEquals(1, result.dependenciesSkipped)
        assertTrue(summary.edgesSkipped >= 1)
    }

    @Test fun `a child under a skipped parent blocks the import until the decisions resolve`() = runBlocking {
        val plan = document(tasks = listOf(planTask()))
        val preview = previewOf(plan)
        val summary = service.summarize(preview, mapOf(
            goalUuid to GoalPlanDecision.KEEP_IMPORTED, phaseUuid to GoalPlanDecision.SKIP,
            milestoneUuid to GoalPlanDecision.KEEP_IMPORTED, taskUuid to GoalPlanDecision.KEEP_IMPORTED))
        // Both the milestone and the task need the skipped phase: two named blockers.
        assertTrue(summary.blocked.any { it.contains("Study vectors") })
        assertTrue(summary.blocked.any { it.contains("Basics down") })
        assertEquals(2, summary.blocked.size)
    }

    @Test fun `a skipped goal decision blocks everything`() {
        val plan = document()
        val summary = service.summarize(previewOf(plan), mapOf(goalUuid to GoalPlanDecision.SKIP))
        assertTrue(summary.blocked.isNotEmpty())
    }

    @Test fun `an illegal decision for the record's conflict class is blocked`() {
        val plan = document()
        val summary = service.summarize(previewOf(plan), mapOf(goalUuid to GoalPlanDecision.KEEP_EXISTING))
        assertTrue(summary.blocked.isNotEmpty()) // new goal cannot Keep existing
    }

    // ---- E. Copy remapping ----

    @Test fun `copy remaps phase, milestone and dependency references correctly`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        goals.saveTask(GoalTask(uuid = task2Uuid, goalId = goal.id, title = "Prerequisite task"))
        val plan = document(
            tasks = listOf(planTask(milestoneUuid = milestoneUuid), planTask(uuid = task2Uuid, title = "Prereq plan", phaseUuid = null)),
            dependencies = listOf(GoalPlanDependency(taskUuid, task2Uuid))
        )
        val result = service.apply(plan, decisionsOf(plan,
            taskUuid to GoalPlanDecision.IMPORT_AS_COPY, task2Uuid to GoalPlanDecision.IMPORT_AS_COPY,
            phaseUuid to GoalPlanDecision.IMPORT_AS_COPY, milestoneUuid to GoalPlanDecision.IMPORT_AS_COPY,
            goalUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        val tasks = database.goalDao().allTasks()
        assertEquals(3, tasks.size) // seeded prerequisite + two copies
        val copiedDependent = tasks.first { it.uuid != taskUuid && it.uuid != task2Uuid && it.title == "Study vectors" }
        val copiedPrerequisite = tasks.first { it.title == "Prereq plan" }
        val copiedPhase = database.goalDao().allPhases().single { it.uuid != phaseUuid }
        val copiedMilestone = database.goalDao().allMilestones().single { it.uuid != milestoneUuid }
        assertTrue(copiedPhase.uuid != phaseUuid)
        assertTrue(copiedMilestone.uuid != milestoneUuid)
        assertEquals(copiedPhase.id, copiedDependent.phaseId)
        assertEquals(copiedMilestone.id, copiedDependent.milestoneId)
        assertEquals(1, database.goalDao().allDependencies().size)
        val edge = database.goalDao().allDependencies().single()
        assertEquals(copiedDependent.id, edge.dependentTaskId)
        assertEquals(copiedPrerequisite.id, edge.prerequisiteTaskId)
        assertEquals(4, result.copiedRecords) // two tasks + one phase + one milestone
    }

    // ---- F. Transaction safety ----

    @Test fun `a data change after preview rejects the import and leaves every table unchanged`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study old"))
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(title = "Study new", phaseUuid = null)))
        val decisions = decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING)
        val reviewed = previewOf(plan)
        // Concurrent edit elsewhere after the preview was prepared.
        goals.saveTask(task.copy(title = "Edited elsewhere"))
        try {
            service.apply(plan, decisions, reviewed)
            fail("A stale preview must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("changed while this preview was open"))
        }
        // The concurrent edit itself stands; the import wrote nothing anywhere.
        val tasks = database.goalDao().allTasks()
        assertEquals(1, tasks.size)
        assertEquals("Edited elsewhere", tasks.single().title)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
        assertEquals(1, database.goalDao().allGoals().size)
    }

    @Test fun `a record inserted after the preview under a plan uuid rejects the stale import`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val plan = document(phases = emptyList(), milestones = emptyList(), tasks = listOf(planTask(phaseUuid = null)))
        val decisions = decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING)
        val reviewed = previewOf(plan)
        // Someone created a task with the plan's uuid after the user reviewed the preview.
        goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Created concurrently"))
        try {
            service.apply(plan, decisions, reviewed)
            fail("A uuid that appeared after preview must reject the import.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("changed while this preview was open"))
        }
        assertEquals(1, database.goalDao().allTasks().size)
        assertEquals("Created concurrently", database.goalDao().taskByUuid(taskUuid)!!.title)
    }

    @Test fun `a failure after partial writes rolls the whole import back`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML", targetDate = today.plusWeeks(8)))
        // Saturate the schedule-revision ledger to its cap: the goal update writes first, then
        // the cap check fires mid-transaction — everything written before it must roll back.
        val saturated = List(GoalValidator.MAX_SCHEDULE_REVISIONS) { index ->
            GoalScheduleRevision(entityType = GoalScheduleEntityType.TASK.name,
                entityUuid = "b0000000-0000-4000-8000-%012d".format(java.util.Locale.ROOT, index % 10),
                field = "plannedDate", formerDate = null, newDate = today, scope = "SINGLE", changedAt = index.toLong())
        }
        database.goalDao().insertScheduleRevisions(saturated)
        val goalBefore = database.goalDao().goal(goal.id)!!
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(16)),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        try {
            service.apply(plan, decisionsOf(plan), previewOf(plan))
            fail("Exceeding the revision cap must abort the import.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("schedule revisions"))
        }
        // Exact rollback: the goal row is byte-for-byte what it was, the ledger unchanged.
        assertEquals(goalBefore, database.goalDao().goal(goal.id)!!)
        assertEquals(GoalValidator.MAX_SCHEDULE_REVISIONS, database.goalDao().allScheduleRevisions().size)
    }

    @Test fun `the preview counts planned additions and finds no blocking problems on a clean plan`() {
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = (1..5).map { index ->
                planTask(uuid = "a2000000-0000-4000-8000-%012d".format(java.util.Locale.ROOT, index),
                    title = "Task $index", phaseUuid = null)
            })
        val summary = service.summarize(previewOf(plan), decisionsOf(plan))
        assertEquals(6, summary.toAdd) // five tasks plus the goal itself
        assertTrue(summary.blocked.isEmpty())
    }

    // ---- G. Data preservation ----

    @Test fun `importing into a mixed goal preserves completed work, skips, links and revisions`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML", status = GoalStatus.PLANNED.name))
        val habit = habits.saveHabit(Habit(name = "Study habit", startDate = today.minusDays(7)))
        goals.linkHabit(goal.id, habit)
        val completedTask = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Done work"))
        goals.saveTask(completedTask.copy(status = GoalTaskStatus.COMPLETED.name))
        val completedAt = database.goalDao().task(completedTask.id)!!.completedAt
        val plan = document(
            goal = planGoal(),
            phases = listOf(planPhase()),
            milestones = listOf(planMilestone()),
            tasks = listOf(planTask(title = "Done work")),
            dependencies = emptyList()
        )
        val decisions = decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING,
            phaseUuid to GoalPlanDecision.KEEP_IMPORTED, milestoneUuid to GoalPlanDecision.IMPORT_AS_COPY,
            taskUuid to GoalPlanDecision.KEEP_EXISTING)
        service.apply(plan, decisions, previewOf(plan))
        val after = database.goalDao().task(completedTask.id)!!
        assertEquals(GoalTaskStatus.COMPLETED.name, after.status)
        assertEquals(completedAt, after.completedAt)
        assertEquals(1, database.goalDao().allGoalHabitLinks().size)
        assertEquals(habit, database.goalDao().allGoalHabitLinks().single().habitId)
        // Habit logs are untouched by goal imports: none existed, none were fabricated.
        assertEquals(0, habits.logs.first().size)
    }

    @Test fun `a paused goal stays paused under use imported`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        goals.saveGoal(goal.copy(status = GoalStatus.ACTIVE.name))
        goals.saveGoal(goal.copy(status = GoalStatus.PAUSED.name))
        val plan = document(goal = planGoal(description = "new plan description"),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        service.apply(plan, decisionsOf(plan), previewOf(plan))
        val after = database.goalDao().goalByUuid(goalUuid)!!
        assertEquals(GoalStatus.PAUSED.name, after.status)
        assertEquals("new plan description", after.description)
    }

    @Test fun `existing schedule revisions are preserved by the import`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML", targetDate = today.plusWeeks(8)))
        goals.rescheduleGoal(goal.id, today.plusWeeks(10))
        val revisionsBefore = database.goalDao().allScheduleRevisions().size
        assertTrue(revisionsBefore >= 1)
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(8)),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        service.apply(plan, decisionsOf(plan), previewOf(plan))
        val revisions = database.goalDao().allScheduleRevisions()
        assertEquals(revisionsBefore + 1, revisions.size) // the target-date move 10→8 added one
        assertTrue(revisions.any { it.field == "targetDate" && it.formerDate == today.plusWeeks(10) && it.newDate == today.plusWeeks(8) })
    }

    // ---- H. Identity and idempotency ----

    @Test fun `importing the same plan twice never duplicates records or revisions`() = runBlocking {
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)),
            tasks = listOf(planTask(plannedDate = today)))
        val decisions = decisionsOf(plan)
        service.apply(plan, decisions, previewOf(plan))
        val counts = listOf(
            database.goalDao().allGoals().size, database.goalDao().allPhases().size,
            database.goalDao().allMilestones().size, database.goalDao().allTasks().size,
            database.goalDao().allScheduleRevisions().size)
        val second = service.apply(plan, decisions, previewOf(plan))
        assertTrue(second.noop)
        assertEquals(counts, listOf(
            database.goalDao().allGoals().size, database.goalDao().allPhases().size,
            database.goalDao().allMilestones().size, database.goalDao().allTasks().size,
            database.goalDao().allScheduleRevisions().size))
    }

    @Test fun `a modified plan with the same ids shows conflicts instead of overwriting`() = runBlocking {
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)))
        service.apply(plan, decisionsOf(plan), previewOf(plan))
        val revised = document(goal = planGoal(targetDate = today.plusWeeks(20)),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        val preview = previewOf(revised)
        assertEquals(GoalPlanClassification.CONFLICT, preview.changes.first { it.kind == GoalPlanRecordKind.GOAL }.classification)
        // Defaults keep the existing record: a deliberate conflict resolution, not a silent overwrite.
        val summary = service.summarize(preview, preview.changes.associate { it.uuid to it.defaultDecision })
        assertEquals(0, summary.toUpdate)
        assertEquals(1, summary.reused)
        val result = service.apply(revised, preview.changes.associate { it.uuid to it.defaultDecision }, preview)
        assertEquals(today.plusWeeks(12), database.goalDao().goalByUuid(goalUuid)!!.targetDate)
        assertTrue(result.noop)
    }

    @Test fun `repeated confirmation is idempotent through the decision map`() = runBlocking {
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)))
        val decisions = decisionsOf(plan)
        service.apply(plan, decisions, previewOf(plan))
        val result = service.apply(plan, decisions, previewOf(plan))
        assertTrue(result.noop)
        assertEquals(1, database.goalDao().allGoals().size)
    }

    // ---- I. Merged dependency graph ----

    @Test fun `imported edges mix with existing edges without duplicates`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val a = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "A"))
        val b = goals.saveTask(GoalTask(uuid = task2Uuid, goalId = goal.id, title = "B", sortOrder = 1))
        goals.addDependency(b.id, a.id) // existing edge B→A
        val cUuid = "a3000000-0000-4000-8000-000000000006"
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(phaseUuid = null), planTask(uuid = task2Uuid, title = "B", phaseUuid = null),
                planTask(uuid = cUuid, title = "C", phaseUuid = null)),
            dependencies = listOf(
                GoalPlanDependency(task2Uuid, taskUuid), // duplicate of the existing B→A edge
                GoalPlanDependency(cUuid, task2Uuid)))   // a genuinely new edge C→B
        val decisions = decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING,
            taskUuid to GoalPlanDecision.KEEP_EXISTING, task2Uuid to GoalPlanDecision.KEEP_EXISTING)
        val result = service.apply(plan, decisions, previewOf(plan))
        assertEquals(2, database.goalDao().allDependencies().size)
        assertEquals(1, result.dependenciesAdded)
        // Re-import: both plan edges are already present, nothing is written again.
        val again = service.apply(plan, decisions, previewOf(plan))
        assertEquals(0, again.dependenciesAdded)
        assertEquals(2, database.goalDao().allDependencies().size)
    }

    @Test fun `a cycle formed only after merging is blocked at preview`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val a = goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "A"))
        val b = goals.saveTask(GoalTask(uuid = task2Uuid, goalId = goal.id, title = "B", sortOrder = 1))
        goals.addDependency(b.id, a.id) // B depends on A
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(phaseUuid = null), planTask(uuid = task2Uuid, title = "B", phaseUuid = null)),
            dependencies = listOf(GoalPlanDependency(taskUuid, task2Uuid))) // plan adds A depends on B
        val preview = previewOf(plan)
        val summary = service.summarize(preview, decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING,
            taskUuid to GoalPlanDecision.KEEP_EXISTING, task2Uuid to GoalPlanDecision.KEEP_EXISTING))
        assertTrue(summary.blocked.any { it.contains("circular") })
        assertEquals(1, database.goalDao().allDependencies().size) // nothing written
    }

    @Test fun `a long dependency chain imports without recursion overflow`() = runBlocking {
        val chainTasks = (1..300).map { index ->
            planTask(uuid = "c%07d-0000-4000-8000-000000000000".format(java.util.Locale.ROOT, index),
                title = "Step $index", phaseUuid = null)
        }
        val chainEdges = chainTasks.drop(1).mapIndexed { index, task ->
            GoalPlanDependency(taskUuid = task.uuid, prerequisiteUuid = chainTasks[index].uuid)
        }
        val plan = document(
            goal = planGoal(),
            phases = emptyList(), milestones = emptyList(),
            tasks = chainTasks, dependencies = chainEdges
        )
        val result = service.apply(plan, decisionsOf(plan), previewOf(plan))
        assertEquals(300, database.goalDao().allTasks().size)
        assertEquals(299, result.dependenciesAdded)
        val merged = service.summarize(previewOf(plan), decisionsOf(plan))
        assertTrue(merged.blocked.isEmpty())
    }

    @Test fun `skipping a prerequisite drops only the dependent edges`() = runBlocking {
        val plan = document(
            phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(phaseUuid = null), planTask(uuid = task2Uuid, title = "Second", phaseUuid = null)),
            dependencies = listOf(
                GoalPlanDependency(taskUuid, task2Uuid),
                GoalPlanDependency(task2Uuid, taskUuid))
        )
        val result = service.apply(plan, decisionsOf(plan, task2Uuid to GoalPlanDecision.SKIP), previewOf(plan))
        assertEquals(1, database.goalDao().allTasks().size)
        assertEquals(0, database.goalDao().allDependencies().size)
        assertEquals(2, result.dependenciesSkipped)
    }

    // ---- Unrelated data isolation ----

    @Test fun `unrelated goals and their records are untouched by an import`() = runBlocking {
        val other = goals.saveGoal(Goal(uuid = "", title = "Unrelated goal"))
        val otherTask = goals.saveTask(GoalTask(uuid = "", goalId = other.id, title = "Other task"))
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        service.apply(plan, decisionsOf(plan), previewOf(plan))
        assertEquals(2, database.goalDao().allGoals().size)
        val otherAfter = database.goalDao().goal(other.id)!!
        assertEquals("Unrelated goal", otherAfter.title)
        assertEquals(otherTask.title, database.goalDao().task(otherTask.id)!!.title)
    }

    @Test fun `a daily reflection is never touched by goal imports`() = runBlocking {
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        val before = database.dao().allReflections()
        service.apply(plan, decisionsOf(plan), previewOf(plan))
        assertEquals(before, database.dao().allReflections())
        assertTrue(database.dao().allReflections().isEmpty())
    }

    // ---- Classification helper coverage ----

    @Test fun `an equivalent child defaults to keep existing with no fabricated differences`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study vectors", estimatedMinutes = 90))
        val plan = document(phases = emptyList(), milestones = emptyList(),
            tasks = listOf(planTask(estimatedMinutes = 90, phaseUuid = null)))
        val change = classifyOf(plan, taskUuid)
        assertEquals(GoalPlanClassification.EQUIVALENT, change.classification)
        assertTrue(change.differences.isEmpty())
        assertEquals(GoalPlanDecision.KEEP_EXISTING, change.defaultDecision)
        assertEquals(goal.id, previewOf(plan).goalRowId)
    }

    @Test fun `a parent reference change is a named difference, not a silent reassignment`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val otherPhase = goals.savePhase(GoalPhase(uuid = "d1000000-0000-4000-8000-000000000001", goalId = goal.id, name = "Later phase"))
        goals.saveTask(GoalTask(uuid = taskUuid, goalId = goal.id, title = "Study", phaseId = otherPhase.id))
        val plan = document(phases = listOf(planPhase()), milestones = emptyList(),
            tasks = listOf(planTask()))
        val change = classifyOf(plan, taskUuid)
        val difference = change.differences.first { it.field == "Phase" }
        assertEquals("Later phase", difference.existing)
        assertEquals("Foundations", difference.imported)
    }

    // ---- Review regression coverage ----

    @Test fun `an import succeeds when another goal already has dependency edges`() = runBlocking {
        // The final merged-graph validation must consider every goal's tasks, not just the
        // imported goal's: edges elsewhere resolve fine and never fail the import.
        val other = goals.saveGoal(Goal(uuid = "", title = "Other goal"))
        val o1 = goals.saveTask(GoalTask(uuid = "", goalId = other.id, title = "Other A"))
        val o2 = goals.saveTask(GoalTask(uuid = "", goalId = other.id, title = "Other B", sortOrder = 1))
        goals.addDependency(o2.id, o1.id)
        val plan = document(goal = planGoal(targetDate = today.plusWeeks(12)),
            phases = emptyList(), milestones = emptyList(), tasks = emptyList())
        val result = service.apply(plan, decisionsOf(plan), previewOf(plan))
        assertEquals(2, database.goalDao().allGoals().size)
        assertEquals(1, database.goalDao().allDependencies().size)
        assertFalse(result.noop)
    }

    @Test fun `an out-of-range imported phase date is refused and writes nothing`() = runBlocking {
        val plan = document(phases = listOf(planPhase(start = LocalDate.of(1500, 1, 1))))
        val preview = previewOf(plan)
        // Named at preview, and independently refused inside the transaction.
        val summary = service.summarize(preview, decisionsOf(plan))
        assertTrue(summary.blocked.any { it.contains("1900") })
        try {
            service.apply(plan, decisionsOf(plan), preview)
            fail("An out-of-range phase date must abort the import.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("1900"))
        }
        assertEquals(0, database.goalDao().allGoals().size)
        assertEquals(0, database.goalDao().allPhases().size)
    }

    @Test fun `a milestone cap overflow created after preview aborts the import`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val saturated = List(GoalValidator.MAX_MILESTONES - 1) { index ->
            GoalMilestone(uuid = "e0000000-0000-4000-8000-%012d".format(java.util.Locale.ROOT, index),
                goalId = goal.id, title = "Milestone " + index)
        }
        database.goalDao().insertMilestones(saturated)
        val plan = document(goal = planGoal(),
            phases = emptyList(),
            milestones = listOf(planMilestone(uuid = "e1000000-0000-4000-8000-000000000001",
                title = "New milestone", phaseUuid = null)),
            tasks = emptyList())
        val decisions = decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING)
        val reviewed = previewOf(plan)
        assertTrue(service.summarize(reviewed, decisions).blocked.isEmpty())
        // A concurrent milestone elsewhere (outside the reviewed fingerprint) pushes the live
        // count to the cap; the in-transaction re-check must abort with full rollback.
        database.goalDao().insertMilestone(GoalMilestone(
            uuid = "e1000000-0000-4000-8000-000000000002", goalId = goal.id, title = "Concurrent milestone"))
        try {
            service.apply(plan, decisions, reviewed)
            fail("Exceeding the milestone cap mid-transaction must abort the import.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("milestones"))
        }
        assertEquals(GoalValidator.MAX_MILESTONES, database.goalDao().allMilestones().size)
        assertNull(database.goalDao().milestoneByUuid("e1000000-0000-4000-8000-000000000001"))
    }

    @Test fun `a milestone achievement is preserved under use imported`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = goalUuid, title = "Learn ML"))
        val milestone = goals.saveMilestone(GoalMilestone(uuid = milestoneUuid, goalId = goal.id, title = "Basics down"))
        goals.saveMilestone(milestone.copy(status = MilestoneStatus.ACHIEVED.name))
        val achievedAt = database.goalDao().milestone(milestone.id)!!.achievedAt
        val plan = document(phases = emptyList(), milestones = listOf(planMilestone(title = "Basics renamed", phaseUuid = null)),
            tasks = emptyList())
        service.apply(plan, decisionsOf(plan, goalUuid to GoalPlanDecision.KEEP_EXISTING), previewOf(plan))
        val after = database.goalDao().milestoneByUuid(milestoneUuid)!!
        assertEquals("Basics renamed", after.title)
        assertEquals(MilestoneStatus.ACHIEVED.name, after.status)
        assertEquals(achievedAt, after.achievedAt)
    }
}
