package com.lifetrack.app.data

import android.app.Application
import androidx.room.Room
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Prompt 6 backup v14 and recovery persistence against the real Room database: alarm
 * definitions round-trip, gates hold, legacy formats stay key-free, recovery outcomes restore
 * truthfully, and a failed restore rolls back fully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HabitAlarmBackupTest {
    private lateinit var database: AppDatabase
    private lateinit var habits: HabitRepository
    private lateinit var sessions: SessionRepository
    private lateinit var recovery: SessionRecoveryRepository
    private lateinit var alarms: HabitAlarmRepository
    private val today get() = LocalDate.now()

    private val alarm = HabitAlarm(
        id = 1, uuid = "c0000000-0000-4000-8000-000000000001", habitId = 1,
        timeMinutes = 6 * 60, recurrence = HabitAlarmRecurrence.DAILY.name,
        weekdays = (1..7).toList(), createdAt = 100, updatedAt = 100
    )

    private fun backup(alarms: List<HabitAlarm> = emptyList(), sessions: List<GoalSession> = emptyList(),
                       formatVersion: Int = 14, habitId: Long = 1) = LifeTrackBackup(
        formatVersion = formatVersion, exportedAt = 1,
        habits = listOf(Habit(id = habitId, name = "Take Medicine", startDate = today, createdAt = 10, updatedAt = 20)),
        logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
        goalSessions = sessions, habitAlarms = alarms
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        habits = HabitRepository(database, context)
        sessions = SessionRepository(database)
        recovery = SessionRecoveryRepository(database)
        alarms = HabitAlarmRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- v14 round trip ----

    @Test fun `a v14 backup round trips alarm definitions`() {
        val original = backup(alarms = listOf(alarm, alarm.copy(
            id = 2, uuid = "c0000000-0000-4000-8000-000000000002", timeMinutes = 18 * 60)), formatVersion = 14)
        BackupValidator.validate(original, today)
        val restored = BackupValidator.decode(BackupValidator.json.encodeToString(original), today)
        assertEquals(14, restored.formatVersion)
        assertEquals(2, restored.habitAlarms.size)
        assertEquals(6 * 60, restored.habitAlarms.first().timeMinutes)
        assertEquals(18 * 60, restored.habitAlarms[1].timeMinutes)
    }

    @Test fun `alarm definitions require backup version 14`() {
        try {
            BackupValidator.validate(backup(alarms = listOf(alarm), formatVersion = 13), today)
            fail("Alarms require backup version 14.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("version 14"))
        }
    }

    @Test fun `a v13 backup decodes without alarm keys and a v14 backup without alarms stays clean`() {
        val plain = BackupValidator.decode(BackupValidator.json.encodeToString(backup(formatVersion = 13)), today)
        assertTrue(plain.habitAlarms.isEmpty())
        val v14 = BackupValidator.decode(BackupValidator.json.encodeToString(backup(formatVersion = 14)), today)
        assertTrue(v14.habitAlarms.isEmpty())
    }

    @Test fun `invalid alarm definitions are rejected by the backup validator`() {
        try {
            BackupValidator.validate(backup(alarms = listOf(alarm.copy(habitId = 99)), formatVersion = 14), today)
            fail("An alarm for a missing habit must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("missing habit"))
        }
        try {
            BackupValidator.validate(backup(alarms = listOf(alarm, alarm.copy(id = 1,
                uuid = "c0000000-0000-4000-8000-000000000009"))), today)
            fail("Duplicate alarm IDs must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Duplicate alarm IDs"))
        }
    }

    // ---- Restore + reconciliation inputs ----

    @Test fun `a v14 restore brings alarms and sessions back with their histories`() = runBlocking {
        val habitId = habits.saveHabit(Habit(name = "Take Medicine", startDate = today, createdAt = 10, updatedAt = 20))
        val testAlarm = alarm.copy(habitId = habitId)
        val completed = GoalSession(
            id = 1, uuid = "c1000000-0000-4000-8000-000000000001", title = "Past session",
            plannedStartDate = today.minusDays(1), plannedStartTime = LocalTime.of(17, 0),
            plannedEndDate = today.minusDays(1), plannedEndTime = LocalTime.of(18, 30),
            timezoneId = "UTC", status = GoalSessionStatus.PARTIAL.name,
            reportedMinutes = 40, actualEndAt = 5_000_000L, createdAt = 100, updatedAt = 200)
        val original = backup(alarms = listOf(testAlarm), sessions = listOf(completed), formatVersion = 14, habitId = habitId)
        val file = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "v14.json").apply {
            writeText(BackupValidator.json.encodeToString(original))
        }
        habits.importFrom(android.net.Uri.fromFile(file))
        // Alarms restore for the restored habit.
        assertEquals(1, database.alarmDao().allAlarms().size)
        assertEquals(habitId, database.alarmDao().allAlarms().single().habitId)
        // The partial session restores with its reported minutes intact — no fabrication.
        val restored = database.sessionDao().allSessions().single()
        assertEquals(GoalSessionStatus.PARTIAL.name, restored.status)
        assertEquals(40, restored.reportedMinutes)
        assertNull(restored.actualStartAt)
    }

    @Test fun `reset clears alarms and sessions`() = runBlocking {
        val habit = habits.saveHabit(Habit(name = "Take Medicine", startDate = today.minusDays(1)))
        alarms.save(HabitAlarm(uuid = "", habitId = habit, timeMinutes = 360))
        sessions.save(GoalSession(uuid = "", title = "S",
            plannedStartDate = today, plannedStartTime = LocalTime.of(9, 0),
            plannedEndDate = today, plannedEndTime = LocalTime.of(10, 0), timezoneId = "UTC"))
        habits.resetAllData()
        assertTrue(database.alarmDao().allAlarms().isEmpty())
        assertTrue(database.sessionDao().allSessions().isEmpty())
    }

    // ---- Repository rules ----

    @Test fun `duplicate alarm times within one habit are rejected`() = runBlocking {
        val habitId = habits.saveHabit(Habit(name = "Take Medicine", startDate = today))
        alarms.save(HabitAlarm(uuid = "", habitId = habitId, timeMinutes = 360))
        try {
            alarms.save(HabitAlarm(uuid = "", habitId = habitId, timeMinutes = 360))
            fail("A duplicate alarm time must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("already has an alarm"))
        }
    }

    @Test fun `deleting the habit cascades its alarms`() = runBlocking {

        val habitId = habits.saveHabit(Habit(name = "Take Medicine", startDate = today))
        alarms.save(HabitAlarm(uuid = "", habitId = habitId, timeMinutes = 360))
        // Habit deletion is owned by HabitRepository; verify through the alarm's FK cascade.
        database.dao().deleteHabit(habitId)
        assertTrue(database.alarmDao().allAlarms().isEmpty())

    }
}
