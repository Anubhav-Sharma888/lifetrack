package com.lifetrack.app.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Rescheduling semantics against the real Room database: explicit moves write schedule history,
 * batch shifts move incomplete dependents transactionally, completion records never change, and
 * every failure leaves both the plan and the history untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GoalRescheduleTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: GoalRepository
    private lateinit var habits: HabitRepository
    private val files = mutableListOf<File>()
    private val today: LocalDate get() = LocalDate.now()
    private val week1 = LocalDate.of(2026, 11, 10)
    private val week2 = LocalDate.of(2026, 11, 20)

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

    private fun tempFile(name: String, content: ByteArray): File {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, name)
        file.writeBytes(content)
        files.add(file)
        return file
    }

    private fun uuid(index: Int): String = String.format("aaaaaaaa-bbbb-4ccc-%04x-%012x", index, index)

    private suspend fun seededTask(planned: LocalDate? = week1, deadline: LocalDate? = week2, order: Int = 0): GoalTask {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML"))
        return repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study vectors",
            sortOrder = order, plannedDate = planned, deadline = deadline))
    }

    // ---- Single moves ----

    @Test fun `rescheduling a task records a revision and moves the dates`() = runBlocking {
        val task = seededTask()
        val newPlanned = week1.plusDays(5)
        repository.rescheduleTask(task.id, plannedDate = newPlanned, deadline = week2)
        val updated = database.goalDao().task(task.id)!!
        assertEquals(newPlanned, updated.plannedDate)
        assertEquals(week2, updated.deadline)
        val revisions = database.goalDao().revisionsForEntityField(task.uuid, "plannedDate")
        assertEquals(1, revisions.size)
        assertEquals(task.plannedDate, revisions.single().formerDate)
        assertEquals(newPlanned, revisions.single().newDate)
        assertEquals("SINGLE", revisions.single().scope)
        assertTrue(database.goalDao().revisionsForEntityField(task.uuid, "deadline").isEmpty())
    }

    @Test fun `an unchanged reschedule writes no history`() = runBlocking {
        val task = seededTask()
        repository.rescheduleTask(task.id, plannedDate = task.plannedDate, deadline = task.deadline)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
    }

    @Test fun `a planned date can never land after its deadline`() = runBlocking {
        val task = seededTask()
        try {
            repository.rescheduleTask(task.id, plannedDate = week2.plusDays(1), deadline = week2)
            fail("Planned after deadline must be rejected")
        } catch (expected: IllegalArgumentException) { }
        assertEquals(week1, database.goalDao().task(task.id)!!.plannedDate)
        assertTrue("A failed reschedule writes no history", database.goalDao().allScheduleRevisions().isEmpty())
    }

    @Test fun `an explicit single move may touch a completed task's planning, never its completion`() = runBlocking {
        val task = seededTask()
        val completed = repository.saveTask(task.copy(status = GoalTaskStatus.COMPLETED.name))
        val completedAt = completed.completedAt!!
        repository.rescheduleTask(task.id, plannedDate = week1.plusDays(7), deadline = week2)
        val updated = database.goalDao().task(task.id)!!
        assertEquals(GoalTaskStatus.COMPLETED, GoalTaskStatus.fromStored(updated.status))
        assertEquals(completedAt, updated.completedAt)
        assertEquals(1, database.goalDao().revisionsForEntityField(task.uuid, "plannedDate").size)
    }

    @Test fun `the original plan stays recoverable across two moves`() = runBlocking {
        val task = seededTask()
        val second = week1.plusDays(4)
        val third = week1.plusDays(9)
        repository.rescheduleTask(task.id, plannedDate = second, deadline = week2)
        repository.rescheduleTask(task.id, plannedDate = third, deadline = week2)
        val revisions = database.goalDao().revisionsForEntityField(task.uuid, "plannedDate")
        assertEquals(2, revisions.size)
        // The earliest revision's former date is the original plan.
        assertEquals(task.plannedDate, revisions.minOf { it.changedAt }.let { earliest ->
            revisions.first { it.changedAt == earliest }.formerDate
        })
        assertEquals(third, database.goalDao().task(task.id)!!.plannedDate)
    }

    // ---- Milestones, phases and the goal deadline ----

    @Test fun `milestone, phase and goal deadline moves each record their own history`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML", targetDate = week2))
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = goal.id, name = "Foundations",
            plannedStartDate = week1, plannedEndDate = week2))
        val milestone = repository.saveMilestone(GoalMilestone(uuid = "", goalId = goal.id, phaseId = phase.id,
            title = "Algebra done", targetDate = week2))
        repository.rescheduleMilestone(milestone.id, week2.plusDays(6))
        repository.reschedulePhase(phase.id, week1.plusDays(3), week2.plusDays(12))
        repository.rescheduleGoal(goal.id, week2.plusWeeks(2))
        val revisions = database.goalDao().allScheduleRevisions()
        assertEquals(4, revisions.size)
        assertTrue(revisions.any { it.entityType == "MILESTONE" && it.field == "targetDate" })
        assertTrue(revisions.any { it.entityType == "PHASE" && it.field == "plannedStartDate" })
        assertTrue(revisions.any { it.entityType == "PHASE" && it.field == "plannedEndDate" })
        assertTrue(revisions.any { it.entityType == "GOAL" && it.field == "targetDate" })
        assertEquals(week2.plusWeeks(2), database.goalDao().goal(goal.id)!!.targetDate)
    }

    @Test fun `a phase cannot end before it starts and writes no history on failure`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = goal.id, name = "Foundations",
            plannedStartDate = week1, plannedEndDate = week2))
        try {
            repository.reschedulePhase(phase.id, week2, week1)
            fail("Phase end before start must be rejected")
        } catch (expected: IllegalArgumentException) { }
        assertEquals(week1, database.goalDao().phase(phase.id)!!.plannedStartDate)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
    }

    // ---- Batch shifts ----

    @Test fun `shifting dependents moves the chain transactionally and skips completed tasks`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val a = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn Python",
            plannedDate = week1, deadline = week1.plusDays(3)))
        val b = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn NumPy",
            sortOrder = 1, plannedDate = week1.plusDays(4), deadline = week1.plusDays(7)))
        val c = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Train model",
            sortOrder = 2, plannedDate = week1.plusDays(8), deadline = week1.plusDays(12)))
        val doneDraft = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Setup notebook",
            sortOrder = 3, plannedDate = week1.minusDays(1), deadline = week1.minusDays(1)))
        val done = repository.saveTask(doneDraft.copy(status = GoalTaskStatus.COMPLETED.name))
        repository.addDependency(b.id, a.id)
        repository.addDependency(c.id, b.id)
        val delta = 5L
        repository.shiftDependentTasks(a.id, week1.plusDays(delta), a.deadline!!.plusDays(delta))
        val tasks = database.goalDao().tasksForGoal(goal.id).associateBy { it.id }
        assertEquals(week1.plusDays(delta), tasks.getValue(a.id).plannedDate)
        assertEquals(week1.plusDays(4 + delta), tasks.getValue(b.id).plannedDate)
        assertEquals(week1.plusDays(8 + delta), tasks.getValue(c.id).plannedDate)
        // The completed task keeps its recorded dates.
        assertEquals(week1.minusDays(1), tasks.getValue(done.id).plannedDate)
        // History: three tasks × two fields = six SHIFT_DEPENDENTS rows (done wrote none).
        val revisions = database.goalDao().allScheduleRevisions()
        assertEquals(6, revisions.size)
        assertTrue(revisions.all { it.scope == "SHIFT_DEPENDENTS" })
    }

    @Test fun `a shift that would invert a dependent is rejected whole`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val a = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn Python",
            plannedDate = week1, deadline = week1.plusDays(2)))
        val b = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn NumPy", sortOrder = 1,
            plannedDate = week1.plusDays(1), deadline = week1.plusDays(2)))
        repository.addDependency(b.id, a.id)
        // Moving A nine days forward while leaving its deadline in place plans it after its own
        // deadline (and its dependents' dates are invalid too) — the whole shift must abort.
        try {
            repository.shiftDependentTasks(a.id, week1.plusDays(9), week1.plusDays(2))
            fail("A shift that inverts a dependent must be rejected")
        } catch (expected: IllegalArgumentException) { }
        assertEquals(week1, database.goalDao().task(a.id)!!.plannedDate)
        assertEquals(week1.plusDays(1), database.goalDao().task(b.id)!!.plannedDate)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
    }

    // ---- History lifecycle ----

    @Test fun `deleting a record removes its history but not other records' history`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val one = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "One", plannedDate = week1))
        val two = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Two", sortOrder = 1, plannedDate = week1))
        repository.rescheduleTask(one.id, week1.plusDays(3), null)
        repository.rescheduleTask(two.id, week1.plusDays(3), null)
        repository.deleteTask(one.id)
        val remaining = database.goalDao().allScheduleRevisions()
        assertEquals(1, remaining.size)
        assertEquals(two.uuid, remaining.single().entityUuid)
    }

    @Test fun `deleting the goal removes its whole history and never touches habits`() = runBlocking {
        val habit = habits.saveHabit(Habit(name = "Read", startDate = today))
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML", targetDate = week2))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study", plannedDate = week1))
        repository.rescheduleTask(task.id, week1.plusDays(2), null)
        repository.rescheduleGoal(goal.id, week2.plusDays(2))
        assertEquals(2, database.goalDao().allScheduleRevisions().size)
        repository.deleteGoal(goal.id)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
        assertEquals(habit, habits.habits.first().single().id)
    }

    // ---- Backup integration ----

    @Test fun `schedule history exports as version eleven and round trips`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML", targetDate = week2))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study", plannedDate = week1))
        repository.rescheduleTask(task.id, week1.plusDays(2), null)
        val output = tempFile("revisions.json", ByteArray(0))
        habits.exportTo(Uri.fromFile(output))
        val restored = BackupValidator.decode(output.readText())
        assertEquals(11, restored.formatVersion)
        assertEquals(1, restored.goalScheduleRevisions.size)
        val revision = restored.goalScheduleRevisions.single()
        assertEquals("TASK", revision.entityType)
        assertEquals(week1, revision.formerDate)
        assertEquals(week1.plusDays(2), revision.newDate)
    }

    @Test fun `restoring an older backup clears schedule history with the goal tree`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML", targetDate = week2))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study", plannedDate = week1))
        repository.rescheduleTask(task.id, week1.plusDays(2), null)
        val backup = LifeTrackBackup(
            formatVersion = 3, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList()
        )
        habits.importFrom(Uri.fromFile(tempFile("old.json", BackupValidator.json.encodeToString(backup).toByteArray())))
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
        assertTrue(database.goalDao().allGoals().isEmpty())
    }

    @Test fun `a v10 file carrying schedule history is rejected by the version gate`() = runBlocking {
        val damaged = LifeTrackBackup(
            formatVersion = 10, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
            goalScheduleRevisions = listOf(GoalScheduleRevision(entityType = "TASK", entityUuid = uuid(1),
                field = "plannedDate", formerDate = week1, newDate = week2, scope = "SINGLE", changedAt = 100))
        )
        try {
            BackupValidator.validate(damaged, today)
            fail("Schedule history requires version 11")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("version 11"))
        }
    }

    @Test fun `an editor save with recordScheduleHistory writes revisions for changed dates`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML", targetDate = week2))
        val phase = repository.savePhase(GoalPhase(uuid = "", goalId = goal.id, name = "Foundations",
            plannedStartDate = week1, plannedEndDate = week2))
        val task = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study", plannedDate = week1))
        // The editor path: one save carrying changed dates AND the history flag.
        repository.saveTask(task.copy(plannedDate = week1.plusDays(4), deadline = week2), recordScheduleHistory = true)
        val planned = database.goalDao().revisionsForEntityField(task.uuid, "plannedDate")
        assertEquals(1, planned.size)
        assertEquals(week1, planned.single().formerDate)
        assertEquals(week1.plusDays(4), planned.single().newDate)
        assertEquals("SINGLE", planned.single().scope)
        // A metadata-only save (flag off) writes nothing even when dates change.
        repository.saveTask(task.copy(plannedDate = week1.plusDays(8)))
        assertEquals(1, database.goalDao().revisionsForEntityField(task.uuid, "plannedDate").size)
        // Goal target-date edits through the editor record goal history too.
        repository.saveGoal(goal.copy(targetDate = week2.plusWeeks(2)), recordScheduleHistory = true)
        assertEquals("targetDate", database.goalDao().allScheduleRevisions()
            .firstOrNull { it.entityType == "GOAL" && it.entityUuid == goal.uuid }?.field)
        // Phase date edits record both fields.
        repository.savePhase(phase.copy(plannedStartDate = week1.plusDays(2), plannedEndDate = week2.plusDays(4)), recordScheduleHistory = true)
        assertEquals(1, database.goalDao().revisionsForEntityField(phase.uuid, "plannedStartDate").size)
        assertEquals(1, database.goalDao().revisionsForEntityField(phase.uuid, "plannedEndDate").size)
    }

    @Test fun `a shift that would strand a dependent names that task and aborts whole`() = runBlocking {
        val goal = repository.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val a = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn Python",
            plannedDate = week1, deadline = week1.plusDays(14)))
        val b = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn NumPy", sortOrder = 1,
            plannedDate = week1.plusDays(3), deadline = week1.plusDays(4)))
        repository.addDependency(b.id, a.id)
        // A forward move whose deadline lags the planned shift strands B (slack 1 day < 4-day move):
        // the moved task itself stays feasible (its own slack is large), the dependent does not.
        val exception = try {
            repository.shiftDependentTasks(a.id, week1.plusDays(4), week1.plusDays(11))
            null
        } catch (expected: IllegalArgumentException) { expected }
        assertTrue(exception?.message!!.contains("Learn NumPy"))
        assertEquals(week1, database.goalDao().task(a.id)!!.plannedDate)
        assertEquals(week1.plusDays(3), database.goalDao().task(b.id)!!.plannedDate)
        assertTrue(database.goalDao().allScheduleRevisions().isEmpty())
    }

    @Test fun `a revision with a field its entity type cannot carry is rejected`() {
        val revision = GoalScheduleRevision(entityType = "MILESTONE", entityUuid = uuid(1),
            field = "plannedDate", formerDate = week1, newDate = week2, scope = "SINGLE", changedAt = 100)
        val exception = try {
            BackupValidator.validate(LifeTrackBackup(
                formatVersion = 11, exportedAt = 1,
                habits = listOf(Habit(id = 1, name = "Backed up", startDate = today, createdAt = 10, updatedAt = 20)),
                logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
                goalScheduleRevisions = listOf(revision)), today)
            null
        } catch (expected: IllegalArgumentException) { expected }
        assertTrue(exception?.message!!.contains("cannot reschedule"))
    }

    @Test fun `a revision may clear a date but never records two nulls`() {
        GoalValidator.validateScheduleRevision(GoalScheduleRevision(entityType = "TASK", entityUuid = uuid(1),
            field = "deadline", formerDate = week1, newDate = null, scope = "SINGLE", changedAt = 100), today)
        val bothNull = GoalScheduleRevision(entityType = "TASK", entityUuid = uuid(1),
            field = "deadline", formerDate = null, newDate = null, scope = "SINGLE", changedAt = 100)
        try {
            GoalValidator.validateScheduleRevision(bothNull, today)
            fail("A revision must record a date")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("must record a date"))
        }
        assertNull(bothNull.newDate)
    }
}
