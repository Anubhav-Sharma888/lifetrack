package com.lifetrack.app.data

import android.app.Application
import androidx.room.Room
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Prompt 6 recovery semantics against the real Room database: explicit PARTIAL/SKIPPED outcomes,
 * manual check-ins without fabricated starts, auditable reschedules, bounded snoozes, and the
 * honest invariants a recovery decision must keep (nothing auto-completes, no duplicate work).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SessionRecoveryTest {
    private lateinit var database: AppDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var recovery: SessionRecoveryRepository
    private val today get() = LocalDate.now()

    private fun session(
        title: String = "Study vectors",
        start: LocalDate = today.minusDays(1),
        status: GoalSessionStatus = GoalSessionStatus.PLANNED
    ) = GoalSession(
        uuid = "", title = title,
        plannedStartDate = start, plannedStartTime = LocalTime.of(17, 0),
        plannedEndDate = start, plannedEndTime = LocalTime.of(18, 30),
        timezoneId = ZoneId.systemDefault().id, status = status.name
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        sessions = SessionRepository(database)
        recovery = SessionRecoveryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- Partial outcome ----

    @Test fun `a partial outcome records reported minutes below the plan`() = runBlocking {
        val saved = sessions.save(session())
        val partial = recovery.confirmOutcome(saved.uuid, GoalSessionStatus.PARTIAL, reportedMinutes = 40, completedAt = 1_000L)
        assertEquals(GoalSessionStatus.PARTIAL.name, partial.status)
        assertNull(partial.actualStartAt)
        assertNotNull(partial.actualEndAt)
        assertEquals(40, partial.reportedMinutes)
        assertEquals(1_000L, partial.actualEndAt)
    }

    @Test fun `a partial outcome matching the planned window is rejected as dishonest`() = runBlocking {
        val saved = sessions.save(session())
        try {
            recovery.confirmOutcome(saved.uuid, GoalSessionStatus.PARTIAL, reportedMinutes = 90)
            fail("Partial must be genuinely below the planned window.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Completed, not Partial"))
        }
    }

    @Test fun `a partial outcome via instrumented time records the end at confirmation`() = runBlocking {
        val saved = sessions.save(session())
        sessions.start(saved.uuid, startedAt = 1_000L)
        val partial = recovery.confirmOutcome(saved.uuid, GoalSessionStatus.PARTIAL, completedAt = 2_400_000L)
        assertEquals(GoalSessionStatus.PARTIAL.name, partial.status)
        assertEquals(1_000L, partial.actualStartAt)
        assertEquals(2_400_000L, partial.actualEndAt)
        assertNull(partial.reportedMinutes)
    }

    // ---- Skipped outcome ----

    @Test fun `a skipped outcome carries no work evidence and stays in history`() = runBlocking {
        val saved = sessions.save(session())
        val skipped = recovery.confirmOutcome(saved.uuid, GoalSessionStatus.SKIPPED, completedAt = 1_000L)
        assertEquals(GoalSessionStatus.SKIPPED.name, skipped.status)
        assertNull(skipped.actualStartAt)
        assertNull(skipped.actualEndAt)
        assertNull(skipped.reportedMinutes)
        assertNotNull(sessions.sessionByUuid(saved.uuid))
    }

    @Test fun `a skipped outcome cannot smuggle reported minutes`() = runBlocking {
        val saved = sessions.save(session())
        val skipped = recovery.confirmOutcome(saved.uuid, GoalSessionStatus.SKIPPED, reportedMinutes = 30)
        assertNull(skipped.reportedMinutes)
    }

    // ---- Manual check-in without a fabricated start ----

    @Test fun `a completed manual check-in records the end and reported minutes without a start`() = runBlocking {
        val saved = sessions.save(session())
        val completed = recovery.confirmOutcome(saved.uuid, GoalSessionStatus.COMPLETED, reportedMinutes = 90, completedAt = 5_000L)
        assertEquals(GoalSessionStatus.COMPLETED.name, completed.status)
        assertEquals(5_000L, completed.actualEndAt)
        assertNull(completed.actualStartAt)
        assertEquals(90, completed.reportedMinutes)
    }

    @Test fun `a manual completed check-in without any evidence is refused`() = runBlocking {
        val saved = sessions.save(session())
        try {
            recovery.confirmOutcome(saved.uuid, GoalSessionStatus.COMPLETED)
            fail("A completed check-in needs reported minutes or a recorded start.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Record what you actually did"))
        }
    }

    @Test fun `duplicate confirmations are rejected`() = runBlocking {
        val saved = sessions.save(session())
        recovery.confirmOutcome(saved.uuid, GoalSessionStatus.COMPLETED, reportedMinutes = 60)
        try {
            recovery.confirmOutcome(saved.uuid, GoalSessionStatus.PARTIAL, reportedMinutes = 30)
            fail("A terminal session cannot change outcome.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot become"))
        }
    }

    // ---- Reschedule ----

    @Test fun `rescheduling preserves the original window on the row`() = runBlocking {
        val saved = sessions.save(session())
        val moved = recovery.reschedule(
            saved.uuid, today.plusDays(2), LocalTime.of(9, 0), today.plusDays(2), LocalTime.of(10, 30))
        assertEquals(today.minusDays(1), moved.originalPlannedStartDate)
        assertEquals(LocalTime.of(17, 0), moved.originalPlannedStartTime)
        assertEquals(today.plusDays(2), moved.plannedStartDate)
        assertEquals(LocalTime.of(9, 0), moved.plannedStartTime)
        // Actual work already recorded survives the move.
        sessions.start(saved.uuid, startedAt = 1_000L)
        assertEquals(1_000L, sessions.sessionByUuid(saved.uuid)!!.actualStartAt)
    }

    @Test fun `a second reschedule keeps the earliest original window`() = runBlocking {
        val saved = sessions.save(session())
        recovery.reschedule(saved.uuid, today.plusDays(2), LocalTime.of(9, 0), today.plusDays(2), LocalTime.of(10, 0))
        val movedAgain = recovery.reschedule(
            saved.uuid, today.plusDays(5), LocalTime.of(9, 0), today.plusDays(5), LocalTime.of(10, 0))
        assertEquals(today.minusDays(1), movedAgain.originalPlannedStartDate)
        assertEquals(today.plusDays(5), movedAgain.plannedStartDate)
    }

    @Test fun `a completed session cannot be rescheduled`() = runBlocking {
        val saved = sessions.save(session())
        recovery.confirmOutcome(saved.uuid, GoalSessionStatus.COMPLETED, reportedMinutes = 60)
        try {
            recovery.reschedule(saved.uuid, today.plusDays(2), LocalTime.of(9, 0), today.plusDays(2), LocalTime.of(10, 0))
            fail("Only planned/in-progress sessions can be rescheduled.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("can be rescheduled"))
        }
    }

    @Test fun `an inverted reschedule window is rejected`() = runBlocking {
        val saved = sessions.save(session())
        try {
            recovery.reschedule(saved.uuid, today.plusDays(2), LocalTime.of(10, 0), today.plusDays(2), LocalTime.of(9, 0))
            fail("An inverted reschedule must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot end before"))
        }
    }

    // ---- Snooze ----

    @Test fun `snoozing records bounded reminder state without rewriting the plan`() = runBlocking {
        val saved = sessions.save(session())
        val snoozed = recovery.snoozeCheckIn(saved.uuid, untilEpochMillis = System.currentTimeMillis() + 600_000L)
        assertTrue(snoozed.snoozedUntilAt!! > System.currentTimeMillis())
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(LocalTime.of(17, 0), snoozed.plannedStartTime)
        assertEquals(GoalSessionStatus.PLANNED.name, snoozed.status)
    }

    @Test fun `snoozing past the limit is refused`() = runBlocking {
        val saved = sessions.save(session())
        var count = 0
        repeat(MAX_SESSION_SNOOZES) { index ->
            recovery.snoozeCheckIn(saved.uuid, untilEpochMillis = System.currentTimeMillis() + 60_000L * (index + 1))
            count++
        }
        assertEquals(MAX_SESSION_SNOOZES, count)
        try {
            recovery.snoozeCheckIn(saved.uuid, untilEpochMillis = System.currentTimeMillis() + 600_000L)
            fail("The snooze limit must hold.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("snooze limit"))
        }
    }

    @Test fun `any recovery decision clears a pending snooze`() = runBlocking {
        val saved = sessions.save(session())
        recovery.snoozeCheckIn(saved.uuid, untilEpochMillis = System.currentTimeMillis() + 600_000L)
        val completed = recovery.confirmOutcome(saved.uuid, GoalSessionStatus.PARTIAL, reportedMinutes = 30, completedAt = 2_000L)
        assertNull(completed.snoozedUntilAt)
    }

    // ---- Recovery candidates ----

    @Test fun `recovery candidates list past unreviewed and partial sessions only`() = runBlocking {
        val past = sessions.save(session(title = "Past", start = today.minusDays(1)))
        val future = sessions.save(session(title = "Future", start = today.plusDays(3)))
        val done = sessions.save(session(title = "Done", start = today.minusDays(2)))
        recovery.confirmOutcome(done.uuid, GoalSessionStatus.COMPLETED, reportedMinutes = 60)
        val skipped = sessions.save(session(title = "Skipped", start = today.minusDays(2)))
        recovery.confirmOutcome(skipped.uuid, GoalSessionStatus.SKIPPED)
        val candidates = recovery.recoveryCandidates(today)
        val titles = candidates.map { it.title }
        assertTrue("Past unreviewed session must be listed", titles.contains("Past"))
        assertTrue("Future session must not be listed", !titles.contains("Future"))
        assertTrue("Completed session must not be listed", !titles.contains("Done"))
        assertTrue("Skipped session must not be listed", !titles.contains("Skipped"))
        assertEquals(past.id, candidates.first().id)
    }
}
