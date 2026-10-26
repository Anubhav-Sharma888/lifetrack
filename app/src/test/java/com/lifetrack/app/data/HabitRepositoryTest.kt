package com.lifetrack.app.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.room.Room
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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

/** Exercises the generated Room DAO and transactions, rather than an in-memory repository fake. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HabitRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HabitRepository
    private val files = mutableListOf<File>()
    private val today: LocalDate get() = LocalDate.now()

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
    fun checkingInTwiceUpdatesOneDailyRecordAndPreservesItsIdentity() = runBlocking {
        val habit = createHabit()
        repository.setLog(habit, today, LogStatus.COMPLETED, " First note ")
        val first = repository.logs.first().single()
        repository.setLog(habit, today, LogStatus.SKIPPED, " A rest day ")
        val updated = repository.logs.first().single()
        assertEquals(first.id, updated.id)
        assertEquals(first.createdAt, updated.createdAt)
        assertEquals(LogStatus.SKIPPED, updated.status)
        assertEquals("A rest day", updated.note)
        assertTrue(updated.updatedAt >= updated.createdAt)
    }

    @Test
    fun databaseItselfEnforcesOneLogForEachHabitAndDate() = runBlocking {
        val habit = createHabit()
        repository.setLog(habit, today, LogStatus.COMPLETED)
        val saved = repository.logs.first().single()
        try {
            database.dao().insertLog(saved.copy(id = 0))
            fail("Room's unique habit/date index must reject a duplicate")
        } catch (_: SQLiteConstraintException) {
            assertEquals(listOf(saved), repository.logs.first())
        }
    }

    @Test
    fun widgetCompletionPreservesNotesAndRepeatedActionsAreIdempotent() = runBlocking {
        val habit = createHabit()
        repository.setLog(habit, today, LogStatus.SKIPPED, "An existing note")
        assertTrue(repository.completeHabit(habit, today))
        val completed = repository.logs.first().single()
        assertEquals("An existing note", completed.note)
        assertEquals(LogStatus.COMPLETED, completed.status)
        assertFalse(repository.completeHabit(habit, today))
        assertEquals(listOf(completed), repository.logs.first())
    }

    @Test
    fun staleWidgetCannotCompleteADifferentHabitThatReusesAnImportedId() = runBlocking {
        val habitId = createHabit("Original habit")
        val original = database.dao().habit(habitId)!!
        val replacement = original.copy(
            name = "Imported different habit",
            createdAt = original.createdAt + 1,
            updatedAt = original.createdAt + 1,
        )
        val importedLog = HabitLog(
            id = 100,
            habitId = habitId,
            date = today,
            status = LogStatus.SKIPPED,
            note = "An imported note",
        )
        val replacementBackup = LifeTrackBackup(
            exportedAt = System.currentTimeMillis(),
            habits = listOf(replacement),
            logs = listOf(importedLog),
            settings = AppSettings(),
            freezes = emptyList(),
        )
        repository.importFrom(Uri.fromFile(backupFile(BackupValidator.json.encodeToString(replacementBackup))))
        expectRejected {
            repository.completeHabit(habitId, today, expectedCreatedAt = original.createdAt)
        }
        assertEquals(listOf(importedLog), repository.logs.first())
        assertTrue(repository.completeHabit(habitId, today, expectedCreatedAt = replacement.createdAt))
        val completed = repository.logs.first().single()
        assertEquals(LogStatus.COMPLETED, completed.status)
        assertEquals("An imported note", completed.note)
    }

    @Test
    fun failedExportDoesNotMarkAnUnwrittenBackupAsSuccessful() = runBlocking {
        createHabit()
        val directory = RuntimeEnvironment.getApplication().cacheDir
        try {
            repository.exportTo(Uri.fromFile(directory))
            fail("An output stream cannot overwrite a directory")
        } catch (_: java.io.IOException) {
            assertNull(repository.settings.first().lastExportAt)
        }
    }

    @Test
    fun deletingHabitCascadesLogsAndUnlinksItsStackedChildren() = runBlocking {
        val parent = createHabit("Brush teeth")
        val child = repository.saveHabit(Habit(name = "Meditate", afterHabitId = parent, startDate = today.minusDays(3)))
        repository.setLog(parent, today, LogStatus.COMPLETED)
        repository.setLog(child, today, LogStatus.COMPLETED)
        repository.deleteHabit(parent)
        assertNull(database.dao().habit(parent))
        assertEquals(child, repository.habits.first().single().id)
        assertNull(database.dao().habit(child)?.afterHabitId)
        assertEquals(listOf(child), repository.logs.first().map { it.habitId })
    }

    @Test
    fun immediateDeletionLeavesNoOrphanRows() = runBlocking {
        val habit = createHabit()
        repository.deleteHabit(habit)
        assertTrue(repository.habits.first().isEmpty())
        assertTrue(repository.logs.first().isEmpty())
        expectRejected { repository.deleteHabit(habit) }
    }

    @Test
    fun rejectsFutureBeforeStartAndArchivedCheckInsWithoutChangingHistory() = runBlocking {
        val habit = createHabit()
        expectRejected { repository.setLog(habit, today.plusDays(1), LogStatus.COMPLETED) }
        expectRejected { repository.setLog(habit, today.minusDays(36), LogStatus.COMPLETED) }
        repository.setLog(habit, today, LogStatus.COMPLETED)
        repository.archiveHabit(habit, true)
        expectRejected { repository.setLog(habit, today, LogStatus.SKIPPED) }
        expectRejected { repository.clearLog(habit, today) }
        assertEquals(LogStatus.COMPLETED, repository.logs.first().single().status)
        repository.archiveHabit(habit, false)
        repository.clearLog(habit, today)
        assertTrue(repository.logs.first().isEmpty())

        val futureHabit = repository.saveHabit(Habit(name = "Starts later", startDate = today.plusDays(3)))
        expectRejected { repository.setLog(futureHabit, today, LogStatus.COMPLETED) }
    }

    @Test
    fun editingStartDateCannotMoveItPastAnExistingLogAndStacksCannotCycle() = runBlocking {
        val parent = createHabit("Read")
        repository.setLog(parent, today.minusDays(2), LogStatus.COMPLETED)
        val saved = database.dao().habit(parent)!!
        expectRejected { repository.saveHabit(saved.copy(startDate = today)) }
        val child = repository.saveHabit(Habit(name = "Write", afterHabitId = parent, startDate = today))
        expectRejected { repository.saveHabit(saved.copy(afterHabitId = child)) }
        assertNull(database.dao().habit(parent)?.afterHabitId)
    }

    @Test
    fun childRemainsEditableAfterItsStackedParentIsArchivedWithoutAllowingNewArchivedLinks() = runBlocking {
        val parent = createHabit("Brush teeth")
        val child = repository.saveHabit(Habit(name = "Meditate", afterHabitId = parent, startDate = today))
        val other = createHabit("Read")
        repository.archiveHabit(parent, true)
        val originalChild = database.dao().habit(child)!!
        repository.saveHabit(originalChild.copy(name = "Meditate quietly", colorHex = "#aabbcc"))
        val editedChild = database.dao().habit(child)!!
        assertEquals("Meditate quietly", editedChild.name)
        assertEquals("#AABBCC", editedChild.colorHex)
        assertEquals(parent, editedChild.afterHabitId)
        assertTrue(database.dao().habit(parent)!!.isArchived)

        expectRejected {
            repository.saveHabit(Habit(name = "New stack", afterHabitId = parent, startDate = today))
        }
        expectRejected {
            repository.saveHabit(database.dao().habit(other)!!.copy(afterHabitId = parent))
        }
        assertNull(database.dao().habit(other)!!.afterHabitId)
    }

    @Test
    fun aMonthlyFreezeCannotBeSpentAcrossHabitsOrRefundedByDeletingItsLogOrHabit() = runBlocking {
        val original = createHabit("Walk")
        val other = createHabit("Stretch")
        val missed = today.minusDays(1)
        repository.useFreeze(original, missed, "Travel day")
        val log = repository.logs.first().single()
        assertEquals(LogStatus.SKIPPED, log.status)
        assertTrue(log.isFrozen)
        assertEquals(YearMonth.from(missed).toString(), repository.freezes.first().single().month)
        expectRejected { repository.useFreeze(other, missed) }
        repository.clearLog(original, missed)
        expectRejected { repository.useFreeze(original, missed) }
        repository.deleteHabit(original)
        expectRejected { repository.useFreeze(other, missed) }
        assertEquals(1, repository.freezes.first().size)
        assertTrue(repository.logs.first().isEmpty())
    }

    @Test
    fun freezeOnlyProtectsAnUnloggedOrFailedPastDay() = runBlocking {
        val habit = createHabit()
        expectRejected { repository.useFreeze(habit, today) }
        repository.setLog(habit, today.minusDays(1), LogStatus.COMPLETED)
        expectRejected { repository.useFreeze(habit, today.minusDays(1)) }
        repository.setLog(habit, today.minusDays(1), LogStatus.FAILED)
        repository.useFreeze(habit, today.minusDays(1))
        assertTrue(repository.logs.first().single().isFrozen)
    }

    @Test
    fun malformedOrStructurallyInvalidImportNeverErasesCurrentData() = runBlocking {
        val habit = createHabit("Keep me")
        repository.setLog(habit, today, LogStatus.COMPLETED, "Keep this note")
        val originalHabits = repository.habits.first()
        val originalLogs = repository.logs.first()
        val malformed = backupFile("{broken JSON")
        expectRejected { repository.importFrom(Uri.fromFile(malformed)) }
        val duplicate = LifeTrackBackup(
            exportedAt = System.currentTimeMillis(),
            habits = originalHabits + originalHabits,
            logs = originalLogs,
            settings = AppSettings(),
            freezes = emptyList(),
        )
        val structurallyInvalid = backupFile(BackupValidator.json.encodeToString(duplicate))
        expectRejected { repository.importFrom(Uri.fromFile(structurallyInvalid)) }
        assertEquals(originalHabits, repository.habits.first())
        assertEquals(originalLogs, repository.logs.first())
    }

    @Test
    fun jsonRoundTripRestoresIdsStackingNotesSettingsTimestampsAndFreezeLedger() = runBlocking {
        val parent = createHabit("Brush teeth")
        val child = repository.saveHabit(Habit(
            name = "Meditate",
            description = "A quiet moment",
            icon = "🧘",
            category = "Mind",
            afterHabitId = parent,
            startDate = today.minusDays(35),
        ))
        repository.setLog(parent, today, LogStatus.COMPLETED, "Done offline")
        repository.setLog(child, today, LogStatus.COMPLETED, "五 minutes 🌱")
        repository.useFreeze(child, today.minusDays(1), "Rest")
        repository.updateSettings(AppSettings(theme = "DARK", dynamicColor = false, backupReminder = true))
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val expectedHabits = repository.habits.first()
        val expectedLogs = repository.logs.first()
        val expectedSettings = repository.settings.first()
        val expectedFreezes = repository.freezes.first()
        assertNotNull(expectedSettings.lastExportAt)
        assertTrue(output.readText().contains("formatVersion"))

        repository.deleteHabit(parent)
        repository.deleteHabit(child)
        createHabit("Temporary")
        repository.updateSettings(AppSettings(theme = "LIGHT"))
        repository.importFrom(Uri.fromFile(output))
        assertEquals(expectedHabits, repository.habits.first())
        assertEquals(expectedLogs, repository.logs.first())
        assertEquals(expectedSettings, repository.settings.first())
        assertEquals(expectedFreezes, repository.freezes.first())
        val newHabit = createHabit("Next habit")
        assertFalse(newHabit in expectedHabits.map { it.id })
    }

    private suspend fun createHabit(name: String = "Drink water"): Long =
        repository.saveHabit(Habit(name = name, startDate = today.minusDays(35)))

    private fun backupFile(content: String = ""): File = File.createTempFile(
        "lifetrack-test-", ".json", RuntimeEnvironment.getApplication().cacheDir,
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
}
