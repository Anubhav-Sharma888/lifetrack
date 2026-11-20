package com.lifetrack.app.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the generated Room DAO, the transactions and the real restore path — no fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GoalRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: GoalRepository
    private lateinit var habits: HabitRepository
    private val files = mutableListOf<File>()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        habits = HabitRepository(database, context)
        repository = GoalRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        files.forEach { it.delete() }
    }

    private fun goal(title: String = "Learn machine learning") = Goal(
        uuid = "", title = title, plannedStartDate = today, targetDate = today.plusWeeks(24)
    )

    private fun tempFile(name: String, content: ByteArray): File {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, name)
        file.writeBytes(content)
        files.add(file)
        return file
    }

    // ---- Identity and lifecycle ----

    @Test fun `creating a goal assigns identity, order and the planned state`() = runBlocking {
        val saved = repository.saveGoal(goal("First"))
        assertTrue(saved.id > 0)
        assertTrue(isCanonicalUuid(saved.uuid))
        assertEquals(GoalStatus.PLANNED, GoalStatus.fromStored(saved.status))
        assertEquals(0, saved.sortOrder)
        assertEquals(saved.createdAt, saved.updatedAt)
        assertNull(saved.completedAt)
        assertFalse(saved.isArchived)
        val second = repository.saveGoal(goal("Second"))
        assertEquals(1, second.sortOrder)
    }

    @Test fun `updating a goal preserves its identity and monotonic timestamps`() = runBlocking {
        val saved = repository.saveGoal(goal())
        val updated = repository.saveGoal(saved.copy(title = "Learn deep learning", description = "rewritten"))
        assertEquals(saved.id, updated.id)
        assertEquals(saved.uuid, updated.uuid)
        assertEquals(saved.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > updated.createdAt)
        assertEquals("rewritten", updated.description)
    }

    @Test fun `a blank uuid is minted by the repository, a reused one is rejected`() = runBlocking {
        val first = repository.saveGoal(goal())
        try {
            repository.saveGoal(goal("Sneaky").copy(uuid = first.uuid))
            fail("A uuid may identify only one goal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("identifier"))
        }
        try {
            repository.saveGoal(goal().copy(uuid = "not-a-uuid"))
            fail("Non-canonical identifiers are rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("canonical"))
        }
    }

    // ---- Status machines ----

    @Test fun `the goal state machine walks legal transitions and reconciles completions`() = runBlocking {
        var saved = repository.saveGoal(goal())
        val originalCreatedAt = saved.createdAt
        saved = repository.saveGoal(saved.copy(status = GoalStatus.ACTIVE.name))
        assertEquals(GoalStatus.ACTIVE, GoalStatus.fromStored(saved.status))
        saved = repository.saveGoal(saved.copy(status = GoalStatus.PAUSED.name))
        assertEquals(GoalStatus.PAUSED, GoalStatus.fromStored(saved.status))
        saved = repository.saveGoal(saved.copy(status = GoalStatus.ACTIVE.name))
        saved = repository.saveGoal(saved.copy(status = GoalStatus.COMPLETED.name))
        assertNotNull(saved.completedAt)
        val completedAt = saved.completedAt!!
        saved = repository.saveGoal(saved.copy(description = "finished"))
        assertEquals(completedAt, saved.completedAt)
        saved = repository.saveGoal(saved.copy(status = GoalStatus.ACTIVE.name))
        assertNull(saved.completedAt)
        saved = repository.saveGoal(saved.copy(status = GoalStatus.CANCELLED.name))
        saved = repository.saveGoal(saved.copy(status = GoalStatus.PLANNED.name))
        assertEquals(originalCreatedAt, saved.createdAt)
    }

    @Test fun `illegal goal transitions are rejected`() = runBlocking {
        val saved = repository.saveGoal(goal())
        listOf(GoalStatus.COMPLETED, GoalStatus.PAUSED).forEach { illegal ->
            try {
                repository.saveGoal(saved.copy(status = illegal.name))
                fail("PLANNED cannot move straight to $illegal")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("cannot move"))
            }
        }
        val active = repository.saveGoal(saved.copy(status = GoalStatus.ACTIVE.name))
        try {
            repository.saveGoal(active.copy(status = GoalStatus.PLANNED.name))
            fail("ACTIVE cannot move back to PLANNED")
        } catch (expected: IllegalArgumentException) { }
        val completed = repository.saveGoal(active.copy(status = GoalStatus.COMPLETED.name))
        try {
            repository.saveGoal(completed.copy(status = GoalStatus.PAUSED.name))
            fail("COMPLETED cannot pause; reopen first")
        } catch (expected: IllegalArgumentException) { }
        val reopened = repository.saveGoal(completed.copy(status = GoalStatus.ACTIVE.name))
        val cancelled = repository.saveGoal(reopened.copy(status = GoalStatus.CANCELLED.name))
        try {
            repository.saveGoal(cancelled.copy(status = GoalStatus.COMPLETED.name))
            fail("CANCELLED cannot complete; revive first")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun `new records must start in their initial state`() = runBlocking {
        try {
            repository.saveGoal(goal().copy(status = GoalStatus.ACTIVE.name))
            fail("New goals start PLANNED")
        } catch (expected: IllegalArgumentException) { }
        val savedGoal = repository.saveGoal(goal())
        try {
            repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "P", status = PhaseStatus.COMPLETED.name))
            fail("New phases start PLANNED")
        } catch (expected: IllegalArgumentException) { }
        try {
            repository.saveMilestone(GoalMilestone(uuid = "", goalId = savedGoal.id, title = "M", status = MilestoneStatus.ACHIEVED.name))
            fail("New milestones start PENDING")
        } catch (expected: IllegalArgumentException) { }
        try {
            repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "T", status = GoalTaskStatus.COMPLETED.name))
            fail("New tasks start NOT_STARTED")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun `archiving is its own operation and survives a form save`() = runBlocking {
        val saved = repository.saveGoal(goal())
        repository.archiveGoal(saved.id, true)
        val archived = repository.goals.first().single()
        assertTrue(archived.isArchived)
        assertNotNull(archived.archivedOn)
        assertEquals(GoalStatus.PLANNED, GoalStatus.fromStored(archived.status))
        repository.saveGoal(archived.copy(isArchived = false, archivedOn = null))
        val afterFormSave = repository.goals.first().single()
        assertTrue("A form edit cannot unarchive", afterFormSave.isArchived)
        repository.archiveGoal(saved.id, false)
        val restored = repository.goals.first().single()
        assertFalse(restored.isArchived)
        assertNull(restored.archivedOn)
    }

    // ---- Phases and milestones ----

    @Test fun `phases order per goal and reorder explicitly`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val first = repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "Math foundations"))
        val second = repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "Programming foundations"))
        assertEquals(listOf(0, 1), listOf(first.sortOrder, second.sortOrder))
        repository.reorderPhases(savedGoal.id, listOf(second.id, first.id))
        val phases = database.goalDao().phasesForGoal(savedGoal.id)
        assertEquals(listOf(second.id, first.id), phases.map { it.id })
        assertEquals(listOf(0, 1), phases.map { it.sortOrder })
    }

    @Test fun `deleting a phase detaches its milestones and tasks without destroying them`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "Phase"))
        val milestone = repository.saveMilestone(GoalMilestone(uuid = "", goalId = savedGoal.id, phaseId = phase.id, title = "Checkpoint"))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, phaseId = phase.id, title = "Work"))
        repository.deletePhase(phase.id)
        val phases = database.goalDao().phasesForGoal(savedGoal.id)
        assertTrue(phases.isEmpty())
        val milestones = database.goalDao().milestonesForGoal(savedGoal.id)
        assertEquals(listOf(milestone.id), milestones.map { it.id })
        assertNull(milestones.single().phaseId)
        val tasks = database.goalDao().tasksForGoal(savedGoal.id)
        assertEquals(listOf(task.id), tasks.map { it.id })
        assertNull(tasks.single().phaseId)
    }

    @Test fun `milestones achieve and revert with their instant`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val milestone = repository.saveMilestone(GoalMilestone(uuid = "", goalId = savedGoal.id, title = "First checkpoint"))
        assertNull(milestone.achievedAt)
        val achieved = repository.saveMilestone(milestone.copy(status = MilestoneStatus.ACHIEVED.name))
        assertNotNull(achieved.achievedAt)
        val reverted = repository.saveMilestone(achieved.copy(status = MilestoneStatus.PENDING.name))
        assertNull(reverted.achievedAt)
    }

    @Test fun `deleting a milestone detaches its tasks`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val milestone = repository.saveMilestone(GoalMilestone(uuid = "", goalId = savedGoal.id, title = "Checkpoint"))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, milestoneId = milestone.id, title = "Work"))
        repository.deleteMilestone(milestone.id)
        val surviving = database.goalDao().tasksForGoal(savedGoal.id).single()
        assertEquals(task.id, surviving.id)
        assertNull(surviving.milestoneId)
    }

    // ---- Tasks ----

    @Test fun `task statuses walk the machine and completion follows the timestamp`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        var task = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "Study vectors"))
        assertNull(task.completedAt)
        task = repository.saveTask(task.copy(status = GoalTaskStatus.IN_PROGRESS.name))
        assertEquals(GoalTaskStatus.IN_PROGRESS, GoalTaskStatus.fromStored(task.status))
        task = repository.saveTask(task.copy(status = GoalTaskStatus.COMPLETED.name))
        assertNotNull(task.completedAt)
        val completedAt = task.completedAt!!
        task = repository.saveTask(task.copy(status = GoalTaskStatus.NOT_STARTED.name))
        assertNull("Reopening clears the completion instant", task.completedAt)
        task = repository.saveTask(task.copy(status = GoalTaskStatus.SKIPPED.name))
        assertNull(task.completedAt)
        try {
            repository.saveTask(task.copy(status = GoalTaskStatus.IN_PROGRESS.name))
            fail("SKIPPED reopens to NOT_STARTED only")
        } catch (expected: IllegalArgumentException) { }
        assertTrue(completedAt > 0)
    }

    @Test fun `task order is goal-global and reorder reindexes`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val one = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "One"))
        val two = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "Two"))
        val three = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "Three"))
        assertEquals(listOf(0, 1, 2), listOf(one.sortOrder, two.sortOrder, three.sortOrder))
        repository.reorderTasks(listOf(three.id, one.id))
        val tasks = database.goalDao().tasksForGoal(savedGoal.id)
        assertEquals(listOf(three.id, one.id, two.id), tasks.map { it.id })
        assertEquals(listOf(0, 1, 2), tasks.map { it.sortOrder })
    }

    // ---- Referential integrity ----

    @Test fun `children reference parents inside their own goal only`() = runBlocking {
        val first = repository.saveGoal(goal())
        val second = repository.saveGoal(goal("Second goal"))
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = second.id, name = "Foreign phase"))
        val milestone = repository.saveMilestone(GoalMilestone(uuid = "", goalId = second.id, title = "Foreign milestone"))
        val violations = listOf<suspend () -> Unit>(
            { repository.savePhase(GoalPhase(uuid = "", goalId = 999, name = "Orphan phase")) },
            { repository.saveMilestone(GoalMilestone(uuid = "", goalId = 999, title = "Orphan milestone")) },
            { repository.saveTask(GoalTask(uuid = "", goalId = 999, title = "Orphan task")) },
            { repository.saveTask(GoalTask(uuid = "", goalId = first.id, phaseId = phase.id, title = "Cross phase")) },
            { repository.saveMilestone(GoalMilestone(uuid = "", goalId = first.id, phaseId = phase.id, title = "Cross phase milestone")) },
            { repository.saveTask(GoalTask(uuid = "", goalId = first.id, milestoneId = milestone.id, title = "Cross milestone")) }
        )
        violations.forEach { violation ->
            try {
                violation()
                fail("Cross-goal or dangling references must be rejected")
            } catch (expected: IllegalArgumentException) { }
        }
    }

    @Test fun `records cannot move between goals`() = runBlocking {
        val first = repository.saveGoal(goal())
        val second = repository.saveGoal(goal("Second goal"))
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = first.id, name = "Phase"))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = first.id, title = "Task"))
        try {
            repository.savePhase(phase.copy(goalId = second.id))
            fail("A phase cannot move between goals")
        } catch (expected: IllegalArgumentException) { }
        try {
            repository.saveTask(task.copy(goalId = second.id))
            fail("A task cannot move between goals")
        } catch (expected: IllegalArgumentException) { }
    }

    // ---- Dependencies ----

    @Test fun `dependencies reject self, cross-goal, duplicate and circular edges`() = runBlocking {
        val first = repository.saveGoal(goal())
        val second = repository.saveGoal(goal("Second goal"))
        val a = repository.saveTask(GoalTask(uuid = "", goalId = first.id, title = "A"))
        val b = repository.saveTask(GoalTask(uuid = "", goalId = first.id, title = "B"))
        val c = repository.saveTask(GoalTask(uuid = "", goalId = first.id, title = "C"))
        val foreign = repository.saveTask(GoalTask(uuid = "", goalId = second.id, title = "Foreign"))
        try {
            repository.addDependency(a.id, a.id)
            fail("A task cannot depend on itself")
        } catch (expected: IllegalArgumentException) { }
        try {
            repository.addDependency(a.id, foreign.id)
            fail("Dependencies must stay within one goal")
        } catch (expected: IllegalArgumentException) { }
        repository.addDependency(a.id, b.id)
        try {
            repository.addDependency(a.id, b.id)
            fail("Duplicate edges are rejected")
        } catch (expected: IllegalArgumentException) { }
        try {
            repository.addDependency(b.id, a.id)
            fail("A two-edge cycle is rejected")
        } catch (expected: IllegalArgumentException) { }
        repository.addDependency(b.id, c.id)
        try {
            repository.addDependency(c.id, a.id)
            fail("A three-edge cycle is rejected")
        } catch (expected: IllegalArgumentException) { }
        val edges = database.goalDao().allDependencies()
        val sortedEdges = edges.map { it.dependentTaskId to it.prerequisiteTaskId }
            .sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(listOf(a.id to b.id, b.id to c.id), sortedEdges)
    }

    @Test fun `diamond prerequisite graphs are accepted`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val a = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "A"))
        val b = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "B"))
        val c = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "C"))
        val d = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "D"))
        repository.addDependency(a.id, b.id)
        repository.addDependency(a.id, c.id)
        repository.addDependency(b.id, d.id)
        repository.addDependency(c.id, d.id)
        assertEquals(4, database.goalDao().allDependencies().size)
    }

    @Test fun `removing an edge or deleting a task never leaves dangling rows`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        val a = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "A"))
        val b = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "B"))
        repository.addDependency(a.id, b.id)
        repository.removeDependency(a.id, b.id)
        assertTrue(database.goalDao().allDependencies().isEmpty())
        repository.addDependency(a.id, b.id)
        repository.deleteTask(b.id)
        assertTrue("Deleting the prerequisite clears its edges", database.goalDao().allDependencies().isEmpty())
        assertNull(database.goalDao().task(b.id))
    }

    // ---- Deletion and coexistence ----

    @Test fun `goals reorder explicitly like habits do`() = runBlocking {
        val one = repository.saveGoal(goal("One"))
        val two = repository.saveGoal(goal("Two"))
        repository.reorderGoals(listOf(two.id, one.id))
        val goals = database.goalDao().allGoals()
        assertEquals(listOf(two.id, one.id), goals.map { it.id })
        assertEquals(listOf(0, 1), goals.map { it.sortOrder })
    }

    @Test fun `goal deletion cascades explicitly and coexists with habit data`() = runBlocking {
        val habit = habits.saveHabit(Habit(name = "Read", startDate = today))
        habits.setLog(habit, today, LogStatus.COMPLETED)
        val savedGoal = repository.saveGoal(goal())
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "Phase"))
        val milestone = repository.saveMilestone(GoalMilestone(uuid = "", goalId = savedGoal.id, title = "Milestone"))
        val prerequisite = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "Read chapter"))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, phaseId = phase.id, milestoneId = milestone.id, title = "Solve exercises"))
        repository.addDependency(task.id, prerequisite.id)
        repository.deleteGoal(savedGoal.id)
        assertTrue(database.goalDao().allGoals().isEmpty())
        assertTrue(database.goalDao().allPhases().isEmpty())
        assertTrue(database.goalDao().allMilestones().isEmpty())
        assertTrue(database.goalDao().allTasks().isEmpty())
        assertTrue(database.goalDao().allDependencies().isEmpty())
        val survivingHabits = habits.habits.first()
        assertEquals(1, survivingHabits.size)
        assertEquals(1, habits.logs.first().size)
        assertEquals(habit, survivingHabits.single().id)
    }

    // ---- Caps ----

    @Test fun `repository caps reject runaway graphs without building them through the API`() = runBlocking {
        val savedGoal = repository.saveGoal(goal())
        database.goalDao().insertPhases((1..GoalValidator.MAX_PHASES).map { index ->
            GoalPhase(uuid = uuid(index), goalId = savedGoal.id, name = "Phase $index")
        })
        try {
            repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "One too many"))
            fail("Phase cap must hold")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("phases"))
        }
        database.goalDao().insertTasks((1..GoalValidator.MAX_TASKS).map { index ->
            GoalTask(uuid = uuid(index), goalId = savedGoal.id, title = "Task $index")
        })
        try {
            repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "One too many"))
            fail("Task cap must hold")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("tasks"))
        }
        database.goalDao().insertMilestones((1..GoalValidator.MAX_MILESTONES).map { index ->
            GoalMilestone(uuid = uuid(index), goalId = savedGoal.id, title = "Milestone $index")
        })
        try {
            repository.saveMilestone(GoalMilestone(uuid = "", goalId = savedGoal.id, title = "One too many"))
            fail("Milestone cap must hold")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("milestones"))
        }
        val taskIds = (1..102).map { index ->
            database.goalDao().insertTask(GoalTask(uuid = uuid(index + 10_000), goalId = savedGoal.id, title = "Edge task $index"))
        }
        val pairs = buildList { for (a in taskIds) for (b in taskIds) if (a != b) add(a to b) }
        database.goalDao().insertDependencies(pairs.take(GoalValidator.MAX_DEPENDENCIES).map { GoalTaskDependency(it.first, it.second) })
        val uninserted = pairs[GoalValidator.MAX_DEPENDENCIES]
        try {
            repository.addDependency(uninserted.first, uninserted.second)
            fail("Dependency cap must hold")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too many task dependencies"))
        }
    }

    // ---- Backup integration (export version, import transaction, normalization) ----

    @Test fun `exports carry goal data only when it exists and compute the format version`() = runBlocking {
        val plainFile = tempFile("no-goals.json", ByteArray(0))
        habits.exportTo(Uri.fromFile(plainFile))
        val plain = BackupValidator.decode(plainFile.readText())
        assertEquals(3, plain.formatVersion)
        val document = Json.parseToJsonElement(plainFile.readText()).jsonObject
        assertFalse("goal-free exports must not carry goal keys", "goals" in document)

        val savedGoal = repository.saveGoal(goal())
        repository.savePhase(GoalPhase(uuid = "", goalId = savedGoal.id, name = "Phase"))
        repository.saveTask(GoalTask(uuid = "", goalId = savedGoal.id, title = "Task"))
        val goalFile = tempFile("with-goals.json", ByteArray(0))
        habits.exportTo(Uri.fromFile(goalFile))
        val restored = BackupValidator.decode(goalFile.readText())
        assertEquals(10, restored.formatVersion)
        assertEquals(1, restored.goals.size)
        assertEquals(1, restored.goalPhases.size)
        assertEquals(1, restored.goalTasks.size)
    }

    @Test fun `import restores goal records transactionally and normalizes text`() = runBlocking {
        // A hand-built v10 backup with deliberately untrimmed text: import must normalize like saves do.
        val backup = LifeTrackBackup(
            formatVersion = 10, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
            goals = listOf(Goal(id = 1, uuid = uuid(1), title = "  Learn ML  ", description = "  words  ", category = " Study ", createdAt = 10, updatedAt = 20)),
            goalPhases = listOf(GoalPhase(id = 2, uuid = uuid(2), goalId = 1, name = "  Phase one  ", createdAt = 10, updatedAt = 20)),
            goalMilestones = listOf(GoalMilestone(id = 3, uuid = uuid(3), goalId = 1, title = "  Checkpoint  ", createdAt = 10, updatedAt = 20)),
            goalTasks = listOf(
                GoalTask(id = 4, uuid = uuid(4), goalId = 1, title = "  First  ", sortOrder = 1, createdAt = 10, updatedAt = 20),
                GoalTask(id = 5, uuid = uuid(5), goalId = 1, title = "Second", sortOrder = 0, createdAt = 10, updatedAt = 20)
            ),
            goalTaskDependencies = listOf(GoalTaskDependency(4, 5))
        )
        habits.importFrom(Uri.fromFile(tempFile("goals.json", BackupValidator.json.encodeToString(backup).toByteArray())))
        val goals = database.goalDao().allGoals()
        assertEquals("Learn ML", goals.single().title)
        assertEquals("words", goals.single().description)
        assertEquals("Study", goals.single().category)
        assertEquals("Phase one", database.goalDao().allPhases().single().name)
        assertEquals("Checkpoint", database.goalDao().allMilestones().single().title)
        assertEquals("First", database.goalDao().allTasks().first { it.id == 4L }.title)
        assertEquals(listOf(GoalTaskDependency(4, 5)), database.goalDao().allDependencies())
        assertEquals(1, habits.habits.first().size)
    }

    @Test fun `a damaged goal import writes nothing`() = runBlocking {
        val habit = habits.saveHabit(Habit(name = "Read", startDate = today))
        val savedGoal = repository.saveGoal(goal())
        // A backup whose task points at a missing goal must be rejected before any write.
        val damaged = LifeTrackBackup(
            formatVersion = 10, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
            goals = listOf(savedGoal.copy(id = 1)),
            goalTasks = listOf(GoalTask(id = 5, uuid = uuid(5), goalId = 99, title = "Dangling"))
        )
        val uri = tempFile("damaged.json", BackupValidator.json.encodeToString(damaged).toByteArray())
        try {
            habits.importFrom(Uri.fromFile(uri))
            fail("A damaged backup must not import")
        } catch (expected: IllegalArgumentException) { }
        assertEquals(1, database.goalDao().allGoals().size)
        assertEquals(savedGoal.id, database.goalDao().allGoals().single().id)
        assertEquals(1, habits.habits.first().size)
        assertEquals(habit, habits.habits.first().single().id)
    }

    @Test fun `a successful import replaces an existing goal tree`() = runBlocking {
        val oldGoal = repository.saveGoal(goal("Old plan"))
        repository.savePhase(GoalPhase(uuid = "", goalId = oldGoal.id, name = "Old phase"))
        val backup = LifeTrackBackup(
            formatVersion = 10, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
            goals = listOf(Goal(id = 9, uuid = uuid(9), title = "Restored plan", createdAt = 10, updatedAt = 20)),
            goalTasks = listOf(GoalTask(id = 10, uuid = uuid(10), goalId = 9, title = "Restored task", createdAt = 10, updatedAt = 20))
        )
        habits.importFrom(Uri.fromFile(tempFile("replace.json", BackupValidator.json.encodeToString(backup).toByteArray())))
        val goals = database.goalDao().allGoals()
        assertEquals(listOf(9L), goals.map { it.id })
        assertEquals("Restored plan", goals.single().title)
        assertTrue("Old phases do not survive a replace-all", database.goalDao().allPhases().isEmpty())
        assertEquals(listOf(10L), database.goalDao().allTasks().map { it.id })
    }

    @Test fun `an old-format restore clears goal roadmaps`() = runBlocking {
        repository.saveGoal(goal("Old plan"))
        val backup = LifeTrackBackup(
            formatVersion = 3, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList()
        )
        habits.importFrom(Uri.fromFile(tempFile("old.json", BackupValidator.json.encodeToString(backup).toByteArray())))
        assertTrue(database.goalDao().allGoals().isEmpty())
        assertTrue(database.goalDao().allTasks().isEmpty())
        assertTrue(database.goalDao().allDependencies().isEmpty())
        assertEquals("Backed up", habits.habits.first().single().name)
    }

    // ---- Helpers ----

    private fun uuid(index: Int): String = String.format("aaaaaaaa-bbbb-4ccc-%04x-%012x", index, index)
}
