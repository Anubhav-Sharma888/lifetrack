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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Smart Streaks decisions: repository guards, cascade on deletion and the version-6 backup gate. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StreakProtectionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HabitRepository
    private val files = mutableListOf<File>()
    private val today: LocalDate get() = LocalDate.now()
    private val yesterday: LocalDate get() = today.minusDays(1)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HabitRepository(database, context)
    }

    @After
    fun tearDown() {
        database.close()
        files.forEach { it.delete() }
    }

    @Test
    fun `only a partially completed past day can be decided`() = runBlocking {
        val habit = createQuantitativeHabit()
        expectRejected { repository.setStreakProtection(habit, yesterday, true) } // no check-in at all
        repository.setLog(habit, yesterday, LogStatus.IN_PROGRESS) // a fresh partial log starts at zero progress
        expectRejected { repository.setStreakProtection(habit, yesterday, true) }
        repository.setLog(habit, yesterday, LogStatus.FAILED)
        expectRejected { repository.setStreakProtection(habit, yesterday, false) }
        repository.setLog(habit, yesterday, LogStatus.COMPLETED)
        expectRejected { repository.setStreakProtection(habit, yesterday, true) }
        repository.setProgress(habit, yesterday, 3)
        repository.setStreakProtection(habit, yesterday, true)
        assertTrue(repository.streakProtections.first().single().protectChain)
    }

    @Test
    fun `streak decisions apply only to past days inside the habit history`() = runBlocking {
        val habit = createQuantitativeHabit()
        expectRejected { repository.setStreakProtection(habit, today, true) }
        expectRejected { repository.setStreakProtection(habit, today.plusDays(1), true) }
        expectRejected { repository.setStreakProtection(habit, today.minusDays(36), true) }
        expectRejected { repository.setStreakProtection(999, yesterday, true) }
        expectRejected { repository.clearStreakProtection(999, yesterday) }
        partialDay(habit)
        repository.setStreakProtection(habit, yesterday, true)
        repository.archiveHabit(habit, true)
        expectRejected { repository.setStreakProtection(habit, today.minusDays(2), true) }
        expectRejected { repository.clearStreakProtection(habit, yesterday) }
    }

    @Test
    fun `unscheduled weekdays reject streak decisions under the effective schedule`() = runBlocking {
        val restricted = repository.saveHabit(
            Habit(name = "MWF", startDate = today.minusDays(35), targetCount = 4, scheduledDays = listOf(1, 3, 5))
        )
        val offDay = generateSequence(yesterday) { it.minusDays(1) }
            .first { it.dayOfWeek.value !in listOf(1, 3, 5) }
        expectRejected { repository.setStreakProtection(restricted, offDay, true) }

        // Schedule history decides the effective weekdays: a non-MWF day that WAS scheduled
        // under the older revision stays decidable, a recent non-MWF day does not.
        val revised = repository.saveHabit(
            Habit(
                name = "Revised", startDate = today.minusDays(35), targetCount = 4,
                scheduledDays = listOf(1, 3, 5),
                scheduleHistory = listOf(
                    ScheduleRevision(today.minusDays(30), (1..7).toList()),
                    ScheduleRevision(today.minusDays(10), listOf(1, 3, 5))
                )
            )
        )
        val oldOffDay = generateSequence(today.minusDays(11)) { it.minusDays(1) }
            .first { it.dayOfWeek.value !in listOf(1, 3, 5) }
        repository.setProgress(revised, oldOffDay, 3)
        repository.setStreakProtection(revised, oldOffDay, true)
        assertEquals(listOf(oldOffDay), repository.streakProtections.first().map { it.date })
        val recentOffDay = generateSequence(today.minusDays(10)) { it.plusDays(1) }
            .first { it < today && it.dayOfWeek.value !in listOf(1, 3, 5) }
        expectRejected { repository.setStreakProtection(revised, recentOffDay, true) }
    }

    @Test
    fun `dates inside a pause reject streak decisions`() = runBlocking {
        val habit = createQuantitativeHabit()
        partialDay(habit)
        partialDay(habit, today.minusDays(4))
        database.dao().insertPause(HabitPause(habitId = habit, startDate = today.minusDays(3), endDate = today))
        expectRejected { repository.setStreakProtection(habit, yesterday, true) }
        repository.setStreakProtection(habit, today.minusDays(4), true)
        assertEquals(listOf(today.minusDays(4)), repository.streakProtections.first().map { it.date })
    }

    @Test
    fun `protecting and clearing a partial day round trips and stays idempotent`() = runBlocking {
        val habit = createQuantitativeHabit()
        partialDay(habit)
        repository.setStreakProtection(habit, yesterday, true)
        val saved = repository.streakProtections.first().single()
        assertEquals(habit, saved.habitId)
        assertEquals(yesterday, saved.date)
        assertTrue(saved.protectChain)
        assertTrue(saved.updatedAt >= saved.createdAt)
        // Re-deciding replaces the single row and keeps its original creation timestamp.
        repository.setStreakProtection(habit, yesterday, false)
        val flipped = repository.streakProtections.first().single()
        assertFalse(flipped.protectChain)
        assertEquals(saved.createdAt, flipped.createdAt)
        assertTrue(flipped.updatedAt > flipped.createdAt)
        // Clearing is idempotent regardless of any date, and the day can be decided again afterwards.
        repository.clearStreakProtection(habit, yesterday)
        repository.clearStreakProtection(habit, yesterday)
        repository.clearStreakProtection(habit, today.plusDays(5))
        assertTrue(repository.streakProtections.first().isEmpty())
        repository.setStreakProtection(habit, yesterday, true)
        assertTrue(repository.streakProtections.first().single().protectChain)
    }

    @Test
    fun `protection rows survive later log edits`() = runBlocking {
        val habit = createQuantitativeHabit(policy = StreakPolicy.AUTOMATIC.name)
        partialDay(habit)
        repository.setStreakProtection(habit, yesterday, true)
        // Completing or failing the day makes the row inert (the status rules the chain),
        // but the stored decision itself is never rewritten by check-in edits.
        repository.setLog(habit, yesterday, LogStatus.COMPLETED)
        assertEquals(1, repository.streakProtections.first().size)
        repository.setLog(habit, yesterday, LogStatus.FAILED)
        assertEquals(1, repository.streakProtections.first().size)
        repository.clearStreakProtection(habit, yesterday)
        assertTrue(repository.streakProtections.first().isEmpty())
    }

    @Test
    fun `deleting a habit cascades its decisions and reset clears everything preserving app lock`() = runBlocking {
        val kept = createQuantitativeHabit("Keep me")
        val deleted = createQuantitativeHabit("Delete me")
        partialDay(kept)
        partialDay(deleted)
        repository.setStreakProtection(kept, yesterday, true)
        repository.setStreakProtection(deleted, yesterday, true)
        repository.deleteHabit(deleted)
        assertEquals(listOf(kept), repository.streakProtections.first().map { it.habitId })
        repository.updateSettings(AppSettings(appLockEnabled = true))
        assertEquals(listOf(kept), repository.resetAllData())
        assertTrue(repository.habits.first().isEmpty())
        assertTrue(repository.streakProtections.first().isEmpty())
        assertTrue(repository.settings.first().appLockEnabled)
    }

    @Test
    fun `smart streaks data exports as version six and restores exactly`() = runBlocking {
        val habit = createQuantitativeHabit(policy = StreakPolicy.AUTOMATIC.name)
        partialDay(habit)
        repository.setStreakProtection(habit, yesterday, true)
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val text = output.readText()
        assertTrue(text.contains("\"formatVersion\": 6"))
        assertTrue(text.contains("\"streakProtections\""))
        assertTrue(text.contains("\"streakPolicy\": \"AUTOMATIC\""))
        val expectedHabits = repository.habits.first()
        val expectedProtections = repository.streakProtections.first()

        repository.resetAllData()
        assertTrue(repository.streakProtections.first().isEmpty())
        repository.importFrom(Uri.fromFile(output))
        assertEquals(expectedHabits, repository.habits.first())
        assertEquals(expectedProtections, repository.streakProtections.first())
    }

    @Test
    fun `a protection row alone forces version six even for strict habits`() = runBlocking {
        val habit = createQuantitativeHabit()
        partialDay(habit)
        repository.setStreakProtection(habit, yesterday, false) // an explicit break decision
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        assertEquals(6, BackupValidator.decode(output.readText()).formatVersion)
        assertTrue(output.readText().contains("\"protectChain\": false"))
    }

    @Test
    fun `plain habits export without smart streaks keys at version three`() = runBlocking {
        val habit = createQuantitativeHabit()
        partialDay(habit)
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val text = output.readText()
        assertEquals(3, BackupValidator.decode(text).formatVersion)
        // Older app versions decode these exports, so the new keys must stay absent entirely.
        assertFalse(text.contains("streakPolicy"))
        assertFalse(text.contains("partialCreditPercent"))
        assertFalse(text.contains("streakProtections"))
    }

    @Test
    fun `the backup gate rejects smart streaks data below version six`() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10), createdAt = 100, updatedAt = 200)
        val row = StreakProtection(habitId = 1, date = today.minusDays(1), protectChain = true, createdAt = 100, updatedAt = 200)
        fun backup(version: Int, forHabit: Habit, protections: List<StreakProtection>) = LifeTrackBackup(
            formatVersion = version, exportedAt = 300, habits = listOf(forHabit), logs = emptyList(),
            settings = AppSettings(), freezes = emptyList(), streakProtections = protections
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(5, habit, listOf(row)), today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(5, habit.copy(streakPolicy = StreakPolicy.AUTOMATIC.name), emptyList()), today)
        }
        BackupValidator.validate(backup(6, habit, listOf(row)), today)
        BackupValidator.validate(backup(6, habit.copy(streakPolicy = StreakPolicy.AUTOMATIC.name), emptyList()), today)
    }

    @Test
    fun `protection rows must reference a real habit day within bounds`() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10), createdAt = 100, updatedAt = 200)
        fun backup(rows: List<StreakProtection>, forHabit: Habit = habit) = LifeTrackBackup(
            formatVersion = 6, exportedAt = 300, habits = listOf(forHabit), logs = emptyList(),
            settings = AppSettings(), freezes = emptyList(), streakProtections = rows
        )
        val valid = StreakProtection(habitId = 1, date = today.minusDays(1), protectChain = true, createdAt = 100, updatedAt = 200)
        BackupValidator.validate(backup(listOf(valid)), today)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(valid, valid.copy(updatedAt = 300))), today) // duplicate decision
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(valid.copy(habitId = 2))), today) // missing habit
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(valid.copy(date = habit.startDate.minusDays(1)))), today) // before start
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(valid.copy(date = today.plusDays(1)))), today) // future date
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(valid.copy(updatedAt = 50))), today) // timestamps
        }
        val archived = habit.copy(isArchived = true, archivedOn = today.minusDays(5))
        val archivedBackup = LifeTrackBackup(
            formatVersion = 6, exportedAt = 300, habits = listOf(archived), logs = emptyList(),
            settings = AppSettings(), freezes = emptyList(),
            pauses = listOf(HabitPause(id = 1, habitId = 1, startDate = archived.archivedOn!!.plusDays(1))),
            streakProtections = listOf(valid.copy(date = today.minusDays(1)))
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(archivedBackup, today) // after the archive date
        }
    }

    private suspend fun createQuantitativeHabit(
        name: String = "Push-ups",
        policy: String = StreakPolicy.STRICT.name
    ): Long = repository.saveHabit(
        Habit(name = name, startDate = today.minusDays(35), targetCount = 4,
            streakPolicy = policy, partialCreditPercent = 75)
    )

    private suspend fun partialDay(habitId: Long, date: LocalDate = yesterday, progress: Int = 3) {
        repository.setProgress(habitId, date, progress)
    }

    private fun backupFile(content: String = ""): File = File.createTempFile(
        "lifetrack-streaks-test-", ".json", RuntimeEnvironment.getApplication().cacheDir,
    ).also {
        files += it
        it.writeText(content)
    }

    private suspend fun expectRejected(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected the repository to reject this operation")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun `clearing a check in also clears its streak decision`() = runBlocking {
        val habit = createQuantitativeHabit()
        repository.setLog(habit, yesterday, LogStatus.IN_PROGRESS)
        repository.setProgress(habit, yesterday, 3)
        repository.setStreakProtection(habit, yesterday, true)
        assertTrue(repository.streakProtections.first().single().protectChain)
        repository.clearLog(habit, yesterday)
        // Re-entering a partial day starts clean: no invisible decision survives the clear.
        assertTrue(repository.streakProtections.first().isEmpty())
    }
}
