package com.lifetrack.app.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Habit-goal linking against the real Room database: links associate existing habits with goals
 * read-only — habit logs, streaks and scheduling stay owned by the habit system, duplicates are
 * rejected, both deletion directions remove only links, and per-goal growth is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GoalHabitLinkTest {
    private lateinit var database: AppDatabase
    private lateinit var goals: GoalRepository
    private lateinit var habits: HabitRepository
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        goals = GoalRepository(database)
        habits = HabitRepository(database, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seededGoal(title: String = "Learn ML") =
        goals.saveGoal(Goal(uuid = "", title = title))

    private suspend fun seededHabit(name: String = "Study 60 minutes daily") =
        habits.saveHabit(Habit(name = name, startDate = today.minusDays(7)))

    @Test fun `linking an existing habit records the association`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        val link = goals.linkHabit(goal.id, habit)
        assertEquals(goal.id, link.goalId)
        assertEquals(habit, link.habitId)
        assertEquals(listOf(habit), goals.habitLinks(goal.id).first().map { it.habitId })
    }

    @Test fun `a duplicate link is rejected with a friendly message`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        try {
            goals.linkHabit(goal.id, habit)
            fail("Duplicate links must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("already supports"))
        }
        assertEquals(1, goals.habitLinks(goal.id).first().size)
    }

    @Test fun `a link to a missing goal or habit is rejected`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        try {
            goals.linkHabit(999, habit)
            fail("Missing goal must be rejected")
        } catch (expected: IllegalArgumentException) { }
        try {
            goals.linkHabit(goal.id, 999)
            fail("Missing habit must be rejected")
        } catch (expected: IllegalArgumentException) { }
        assertTrue(goals.habitLinks(goal.id).first().isEmpty())
    }

    @Test fun `unlinking is idempotent and removes only the association`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        goals.unlinkHabit(goal.id, habit)
        assertTrue(goals.habitLinks(goal.id).first().isEmpty())
        assertEquals(1, habits.habits.first().size) // the habit survives
        goals.unlinkHabit(goal.id, habit) // idempotent
        assertEquals(1, habits.habits.first().size)
    }

    @Test fun `one goal may carry several habits and one habit may support several goals`() = runBlocking {
        val goalA = seededGoal("Learn ML")
        val goalB = seededGoal("Learn deep learning")
        val habit1 = seededHabit("Study 60 minutes daily")
        val habit2 = seededHabit("Review flashcards")
        goals.linkHabit(goalA.id, habit1)
        goals.linkHabit(goalA.id, habit2)
        goals.linkHabit(goalB.id, habit1)
        assertEquals(listOf(habit1, habit2), goals.habitLinks(goalA.id).first().map { it.habitId })
        assertEquals(listOf(habit1), goals.habitLinks(goalB.id).first().map { it.habitId })
        // The habit row is not duplicated per goal: one habit record, two links.
        assertEquals(2, habits.habits.first().size)
        assertEquals(3, goals.goalHabitLinks().size)
    }

    @Test fun `archived habits may remain linked and stay marked archived`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        habits.archiveHabit(habit, true)
        goals.linkHabit(goal.id, habit)
        val signal = goals.habitActivity(habit, windowDays = 28)
        assertEquals(true, signal.isArchived)
        assertEquals(1, goals.habitLinks(goal.id).first().size)
    }

    @Test fun `deleting the habit removes only the link and preserves the goal`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        habits.deleteHabit(habit)
        assertTrue(goals.habitLinks(goal.id).first().isEmpty())
        assertTrue(goals.goalHabitLinks().isEmpty())
        // The goal survives with all of its own records.
        assertEquals(goal.id, goals.goalById(goal.id)!!.id)
    }

    @Test fun `deleting the goal removes only the link and preserves the habit`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        goals.deleteGoal(goal.id)
        assertTrue(goals.goalHabitLinks().isEmpty())
        assertEquals(1, habits.habits.first().size)
    }

    @Test fun `per-goal link growth is bounded`() = runBlocking {
        val goal = seededGoal()
        val last = (1..GoalRepository.MAX_HABIT_LINKS_PER_GOAL).map { index ->
            val habit = habits.saveHabit(Habit(name = "Habit $index", startDate = today))
            goals.linkHabit(goal.id, habit)
            habit
        }.last()
        try {
            val extra = habits.saveHabit(Habit(name = "One too many", startDate = today))
            goals.linkHabit(goal.id, extra)
            fail("Per-goal link cap must hold")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("cannot support more than"))
        }
        assertEquals(GoalRepository.MAX_HABIT_LINKS_PER_GOAL, goals.habitLinks(goal.id).first().size)
        assertTrue(last > 0)
    }

    @Test fun `activity signals are read-only and honest`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        // Two completed check-ins and one in-progress: only completions count.
        habits.setLog(habit, today.minusDays(2), LogStatus.COMPLETED, "done")
        habits.setLog(habit, today.minusDays(1), LogStatus.COMPLETED, "done")
        habits.setLog(habit, today, LogStatus.IN_PROGRESS)
        val signal = goals.habitActivity(habit, windowDays = 28)
        assertEquals(2, signal.totalCompletions)
        assertEquals(2, signal.completionsInWindow)
        assertEquals(today.minusDays(1), signal.lastCompletedDate)
        // The habit log is untouched by goal activity: exactly three records, no fabrication.
        assertEquals(3, habits.logs.first().size)
        // Re-linking (after unlink) changes no habit log.
        goals.unlinkHabit(goal.id, habit)
        goals.linkHabit(goal.id, habit)
        assertEquals(3, habits.logs.first().size)
    }

    @Test fun `backup round trip carries links through export and restore`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        val links = goals.goalHabitLinks()
        assertEquals(1, links.size)
        // The backup validator accepts a v12 document carrying this link.
        val backup = LifeTrackBackup(
            formatVersion = 12, exportedAt = 1,
            habits = listOf(Habit(id = habit, name = "Study", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
            goals = listOf(Goal(id = goal.id, uuid = goal.uuid, title = goal.title, createdAt = 10, updatedAt = 20)),
            goalHabitLinks = links
        )
        BackupValidator.validate(backup)
        val restored = BackupValidator.decode(BackupValidator.json.encodeToString(backup))
        assertEquals(links, restored.goalHabitLinks)
    }

    @Test fun `reset clears links while preserving nothing goal-related`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        goals.linkHabit(goal.id, habit)
        habits.resetAllData()
        assertTrue(goals.goalHabitLinks().isEmpty())
    }

    @Test fun `the real export path promotes the format to twelve when links exist`() = runBlocking {
        val goal = seededGoal()
        val habit = seededHabit()
        val task = goals.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study", plannedDate = today.minusDays(3)))
        goals.rescheduleTask(task.id, today.minusDays(1), null)
        goals.linkHabit(goal.id, habit)
        // Revisions alone would export as v11; the link outranks them in the version cascade.
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "link-export-${System.nanoTime()}.json")
        try {
            habits.exportTo(Uri.fromFile(file))
            val restored = BackupValidator.decode(file.readText())
            assertEquals(12, restored.formatVersion)
            assertEquals(1, restored.goalHabitLinks.size)
            assertEquals(1, restored.goalScheduleRevisions.size)
        } finally {
            file.delete()
        }
    }
}
