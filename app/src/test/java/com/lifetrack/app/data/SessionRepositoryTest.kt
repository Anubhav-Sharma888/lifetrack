package com.lifetrack.app.data

import android.app.Application
import androidx.room.Room
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
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

/**
 * Session repository behavior against the real Room database: lifecycle transitions (start /
 * explicit completion / cancel), truthful actual timestamps, association resolution and
 * conflicts, deleted-parent detachment, caps, and idempotent re-operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SessionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var goals: GoalRepository
    private lateinit var sessions: SessionRepository
    private val today get() = LocalDate.now()

    private fun session(
        title: String = "Study vectors",
        startDate: LocalDate = today,
        startTime: LocalTime = LocalTime.of(17, 0),
        endDate: LocalDate = startDate,
        endTime: LocalTime = LocalTime.of(18, 30),
        timezoneId: String = ZoneId.systemDefault().id,
        goalId: Long? = null, phaseId: Long? = null, taskId: Long? = null
    ) = GoalSession(
        uuid = "", title = title, description = null,
        plannedStartDate = startDate, plannedStartTime = startTime,
        plannedEndDate = endDate, plannedEndTime = endTime, timezoneId = timezoneId,
        goalId = goalId, phaseId = phaseId, taskId = taskId
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        goals = GoalRepository(database)
        sessions = SessionRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- Creation and validation ----

    @Test fun `a session is created planned with its own identity and wall clock`() = runBlocking {
        val saved = sessions.save(session())
        assertTrue(saved.id > 0)
        assertTrue(isCanonicalUuid(saved.uuid))
        assertEquals(GoalSessionStatus.PLANNED.name, saved.status)
        assertNull(saved.actualStartAt)
        assertNull(saved.actualEndAt)
        assertEquals(LocalTime.of(17, 0), saved.plannedStartTime)
    }

    @Test fun `a cross-midnight session is valid through explicit end date semantics`() = runBlocking {
        val saved = sessions.save(session(endDate = today.plusDays(1), endTime = LocalTime.of(1, 0)))
        assertEquals(today.plusDays(1), saved.plannedEndDate)
    }

    @Test fun `a zero duration session is rejected`() {
        try {
            runBlocking { sessions.save(session(endTime = LocalTime.of(17, 0))) }
            fail("Zero duration must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot end before or exactly"))
        }
    }

    @Test fun `an inverted same-day window is rejected`() {
        try {
            runBlocking { sessions.save(session(startTime = LocalTime.of(19, 0), endTime = LocalTime.of(17, 0))) }
            fail("An inverted window must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot end before or exactly"))
        }
    }

    @Test fun `an excessive duration is rejected`() {
        try {
            runBlocking { sessions.save(session(endDate = today.plusDays(2), endTime = LocalTime.of(17, 0))) }
            fail("A 48-hour session must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("24 hours"))
        }
    }

    @Test fun `an invalid timezone is rejected`() {
        try {
            runBlocking { sessions.save(session(timezoneId = "Not/AZone")) }
            fail("An invalid timezone must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("time zone"))
        }
    }

    @Test fun `an out-of-range session date is rejected`() {
        try {
            runBlocking { sessions.save(session(startDate = LocalDate.of(1500, 1, 1), endDate = LocalDate.of(1500, 1, 1))) }
            fail("Out-of-range dates must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("1900"))
        }
    }

    @Test fun `a blank title is rejected`() {
        try {
            runBlocking { sessions.save(session(title = "   ")) }
            fail("A blank title must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("titles"))
        }
    }

    // ---- Associations ----

    @Test fun `a task fixes the goal when no goal was chosen`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study"))
        val saved = sessions.save(session(taskId = task.id))
        assertEquals(goal.id, saved.goalId)
        assertEquals(task.id, saved.taskId)
    }

    @Test fun `a task-goal mismatch is rejected`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val other = goals.saveGoal(Goal(uuid = "", title = "Other"))
        val task = goals.saveTask(GoalTask(uuid = "", goalId = other.id, title = "Other task"))
        try {
            runBlocking { sessions.save(session(goalId = goal.id, taskId = task.id)) }
            fail("A cross-goal task association must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("same goal"))
        }
    }

    @Test fun `a phase-task mismatch is rejected`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val phase = goals.savePhase(GoalPhase(uuid = "", goalId = goal.id, name = "Foundations"))
        val other = goals.saveGoal(Goal(uuid = "", title = "Other"))
        val task = goals.saveTask(GoalTask(uuid = "", goalId = other.id, title = "Other task"))
        try {
            runBlocking { sessions.save(session(phaseId = phase.id, taskId = task.id)) }
            fail("A cross-goal phase-task pair must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("same goal"))
        }
    }

    @Test fun `deleting the linked task detaches the session and preserves it`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val task = goals.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study"))
        val saved = sessions.save(session(taskId = task.id))
        goals.deleteTask(task.id)
        val after = sessions.sessionByUuid(saved.uuid)!!
        assertNull(after.taskId)
        assertEquals("Study vectors", after.title)
    }

    @Test fun `deleting the linked goal detaches the session and preserves it`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val saved = sessions.save(session(goalId = goal.id))
        goals.deleteGoal(goal.id)
        val after = sessions.sessionByUuid(saved.uuid)!!
        assertNull(after.goalId)
        assertEquals("Study vectors", after.title)
    }

    @Test fun `deleting the linked phase detaches the session and preserves it`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML"))
        val phase = goals.savePhase(GoalPhase(uuid = "", goalId = goal.id, name = "Foundations"))
        val saved = sessions.save(session(phaseId = phase.id))
        goals.deletePhase(phase.id)
        assertNull(sessions.sessionByUuid(saved.uuid)!!.phaseId)
    }

    // ---- Lifecycle ----

    @Test fun `starting records the actual start and preserves planned times`() = runBlocking {
        val saved = sessions.save(session())
        val started = sessions.start(saved.uuid, startedAt = 5_000L)
        assertEquals(GoalSessionStatus.IN_PROGRESS.name, started.status)
        assertEquals(5_000L, started.actualStartAt)
        assertNull(started.actualEndAt)
        assertEquals(LocalTime.of(17, 0), started.plannedStartTime)
    }

    @Test fun `a duplicate start is rejected`() = runBlocking {
        val saved = sessions.save(session())
        sessions.start(saved.uuid, startedAt = 5_000L)
        try {
            runBlocking { sessions.start(saved.uuid, startedAt = 6_000L) }
            fail("A duplicate start must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot be started"))
        }
        assertEquals(5_000L, sessions.sessionByUuid(saved.uuid)!!.actualStartAt)
    }

    @Test fun `explicit completion after start records only the actual end`() = runBlocking {
        val saved = sessions.save(session())
        sessions.start(saved.uuid, startedAt = 5_000L)
        val completed = sessions.confirmCompletion(saved.uuid, completedAt = 9_000_000L)
        assertEquals(GoalSessionStatus.COMPLETED.name, completed.status)
        assertEquals(5_000L, completed.actualStartAt)
        assertEquals(9_000_000L, completed.actualEndAt)
    }

    @Test fun `completion without a start leaves the start unrecorded, never fabricated`() = runBlocking {
        val saved = sessions.save(session())
        val completed = sessions.confirmCompletion(saved.uuid, completedAt = 9_000_000L)
        assertEquals(GoalSessionStatus.COMPLETED.name, completed.status)
        assertEquals(9_000_000L, completed.actualEndAt)
        assertNull(completed.actualStartAt)
    }

    @Test fun `a duplicate completion is rejected`() = runBlocking {
        val saved = sessions.save(session())
        sessions.confirmCompletion(saved.uuid, completedAt = 1L)
        try {
            runBlocking { sessions.confirmCompletion(saved.uuid, completedAt = 2L) }
            fail("A duplicate completion must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot be completed"))
        }
        assertEquals(1L, sessions.sessionByUuid(saved.uuid)!!.actualEndAt)
    }

    @Test fun `canceling a planned session preserves the row`() = runBlocking {
        val saved = sessions.save(session())
        sessions.cancel(saved.uuid)
        val after = sessions.sessionByUuid(saved.uuid)!!
        assertEquals(GoalSessionStatus.CANCELED.name, after.status)
        assertNull(after.actualStartAt)
    }

    @Test fun `canceling a started session keeps its start and exports cleanly`() = runBlocking {
        val saved = sessions.save(session())
        sessions.start(saved.uuid, startedAt = 5_000L)
        val canceled = sessions.cancel(saved.uuid)
        assertEquals(GoalSessionStatus.CANCELED.name, canceled.status)
        assertEquals(5_000L, canceled.actualStartAt)
        assertNull(canceled.actualEndAt)
        // The row must pass backup validation: a poisoned row would brick every future export.
        GoalSessionValidator.validateForBackup(canceled, today)
        val snapshot = LifeTrackBackup(
            formatVersion = 13, exportedAt = 1,
            habits = emptyList(), logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
            goalSessions = listOf(canceled.copy(id = 1, uuid = "a1000000-0000-4000-8000-000000000021")))
        BackupValidator.validate(snapshot, today)
    }

    @Test fun `canceling a completed session is illegal`() = runBlocking {
        val saved = sessions.save(session())
        sessions.confirmCompletion(saved.uuid, completedAt = 1L)
        try {
            runBlocking { sessions.cancel(saved.uuid) }
            fail("A completed session cannot be canceled.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot be canceled"))
        }
    }

    @Test fun `an end check-in cannot start a completed session`() = runBlocking {
        val saved = sessions.save(session())
        sessions.confirmCompletion(saved.uuid, completedAt = 1L)
        try {
            runBlocking { sessions.start(saved.uuid, startedAt = 2L) }
            fail("A completed session cannot be started.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot be started"))
        }
    }

    @Test fun `editing a session preserves identity and actual timestamps`() = runBlocking {
        val saved = sessions.save(session())
        sessions.start(saved.uuid, startedAt = 5_000L)
        val edited = sessions.save(saved.copy(title = "Renamed session",
            plannedStartTime = LocalTime.of(18, 0), plannedEndTime = LocalTime.of(19, 30)))
        assertEquals(saved.uuid, edited.uuid)
        assertEquals(5_000L, edited.actualStartAt)
        assertEquals(GoalSessionStatus.IN_PROGRESS.name, edited.status)
        assertEquals("Renamed session", edited.title)
        assertEquals(LocalTime.of(18, 0), edited.plannedStartTime)
    }

    @Test fun `deleting a session removes only that row`() = runBlocking {
        val one = sessions.save(session(title = "One"))
        val two = sessions.save(session(title = "Two"))
        sessions.delete(one.uuid)
        assertNull(sessions.sessionByUuid(one.uuid))
        assertNotNull(sessions.sessionByUuid(two.uuid))
    }

    // ---- Derived awaiting review ----

    @Test fun `an unconfirmed session past its planned end is awaiting review, not completed`() = runBlocking {
        val past = sessions.save(session(
            startDate = today.minusDays(1), startTime = LocalTime.of(9, 0),
            endDate = today.minusDays(1), endTime = LocalTime.of(10, 0)))
        val future = sessions.save(session(
            startDate = today.plusDays(1), startTime = LocalTime.of(9, 0),
            endDate = today.plusDays(1), endTime = LocalTime.of(10, 0)))
        assertTrue(sessions.awaitingReview(past, now = System.currentTimeMillis()))
        assertFalse(sessions.awaitingReview(future, now = System.currentTimeMillis()))
        assertEquals(GoalSessionStatus.PLANNED.name, sessions.sessionByUuid(past.uuid)!!.status)
    }

    // ---- Caps and idempotency ----

    @Test fun `the total session cap is enforced on creation`() = runBlocking {
        // Bulk-seed to the cap boundary, then prove the next creation is refused.
        val seeded = List(MAX_SESSIONS) { index ->
            GoalSession(
                uuid = "b7000000-0000-4000-8000-%012d".format(java.util.Locale.ROOT, index),
                title = "Seed $index", plannedStartDate = today, plannedStartTime = LocalTime.of(9, 0),
                plannedEndDate = today, plannedEndTime = LocalTime.of(9, 30), timezoneId = "UTC",
                createdAt = index.toLong(), updatedAt = index.toLong())
        }
        database.sessionDao().insertSessions(seeded)
        assertEquals(MAX_SESSIONS, sessions.sessionCount())
        try {
            sessions.save(session(title = "One too many"))
            fail("The session cap must be enforced.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("5,000"))
        }
        assertEquals(MAX_SESSIONS, sessions.sessionCount())
    }

    @Test fun `re-saving an identical session is idempotent on identity`() = runBlocking {
        val saved = sessions.save(session())
        val again = sessions.save(saved.copy(title = "Study vectors"))
        assertEquals(saved.uuid, again.uuid)
        assertEquals(1, sessions.sessionCount())
    }
}
