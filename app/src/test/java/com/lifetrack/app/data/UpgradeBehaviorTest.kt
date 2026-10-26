package com.lifetrack.app.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.lifetrack.app.domain.Analytics
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class UpgradeBehaviorTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: HabitRepository
    private val temporaryFiles = mutableListOf<File>()
    private val today get() = LocalDate.now()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = HabitRepository(db, context)
    }
    @After fun close() { db.close(); temporaryFiles.forEach { it.delete() } }

    @Test fun numericProgressCompletesAtTargetAndWidgetFillsWholeGoal() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Read", startDate = today.minusDays(10), targetCount = 10, targetUnit = "pages"))
        assertFalse(repo.setProgress(id, today, 4, "A little reading"))
        assertEquals(LogStatus.IN_PROGRESS, repo.logs.first().single().status)
        assertEquals(4, repo.logs.first().single().progress)
        assertTrue(repo.completeHabit(id, today))
        assertEquals(10, repo.logs.first().single().progress)
        assertEquals("A little reading", repo.logs.first().single().note)
        assertFalse(repo.completeHabit(id, today))
        repo.setProgress(id, today, 5)
        assertTrue(repo.setProgress(id, today, 999))
        assertEquals(10, repo.logs.first().single().progress)
    }

    @Test fun targetAndUnitEditsCannotRewriteRecordedQuantitiesOrCompletionStatus() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Water", startDate = today.minusDays(3), targetCount = 8, targetUnit = "glasses"))
        repo.setProgress(id, today.minusDays(1), 8, "Eight actual glasses")
        repo.setProgress(id, today, 4, "Four actual glasses")
        val originalHabit = db.dao().habit(id)!!
        val originalLogs = repo.logs.first()
        rejected { repo.saveHabit(originalHabit.copy(targetCount = 3)) }
        rejected { repo.saveHabit(originalHabit.copy(targetCount = 12)) }
        rejected { repo.saveHabit(originalHabit.copy(targetUnit = "litres")) }
        assertEquals(originalHabit, db.dao().habit(id))
        assertEquals(originalLogs, repo.logs.first())
        assertEquals(listOf(LogStatus.COMPLETED, LogStatus.IN_PROGRESS), originalLogs.map { it.status })
        assertEquals(listOf(8, 4), originalLogs.map { it.progress })
        repo.saveHabit(originalHabit.copy(name = "Water throughout the day"))
        assertEquals(originalLogs, repo.logs.first())
        val unused = repo.saveHabit(Habit(name = "New goal", startDate = today, targetCount = 2))
        repo.saveHabit(db.dao().habit(unused)!!.copy(targetCount = 12, targetUnit = "pages"))
        assertEquals(12, db.dao().habit(unused)!!.targetCount)
        assertEquals("pages", db.dao().habit(unused)!!.targetUnit)
    }

    @Test fun concurrentNumericIncrementsUseLatestAmountAndPreserveLatestNote() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Read", startDate = today, targetCount = 12, targetUnit = "pages"))
        repo.setProgress(id, today, 0, "Latest persisted note")
        (1..10).map { async(Dispatchers.Default) { repo.incrementProgress(id, today) } }.awaitAll()
        val partial = repo.logs.first().single()
        assertEquals(10, partial.progress)
        assertEquals("Latest persisted note", partial.note)
        assertFalse(repo.incrementProgress(id, today))
        assertTrue(repo.incrementProgress(id, today))
        assertFalse(repo.incrementProgress(id, today))
        assertEquals(12, repo.logs.first().single().progress)
        assertEquals("Latest persisted note", repo.logs.first().single().note)
    }

    @Test fun scheduleChangesKeepEarlierWeekdayHistoryAndRejectNewOffDayLogs() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Walk", startDate = today.minusDays(20)))
        repo.setLog(id, today.minusDays(1), LogStatus.COMPLETED)
        val otherWeekday = today.dayOfWeek.value % 7 + 1
        repo.saveHabit(db.dao().habit(id)!!.copy(scheduledDays = listOf(otherWeekday)))
        val habit = db.dao().habit(id)!!
        assertEquals(2, habit.scheduleHistory.size)
        assertTrue(Analytics.eligible(habit, today.minusDays(1)))
        assertFalse(Analytics.eligible(habit, today))
        rejected { repo.setLog(id, today, LogStatus.COMPLETED) }
        repo.setLog(id, today.minusDays(1), LogStatus.COMPLETED, "Earlier schedule still applies")
        repo.exportTo(Uri.fromFile(file()))
    }

    @Test fun archiveRestoreRetainsGapAndSkipsItInStreaksAndRates() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Read", startDate = today.minusDays(30)))
        repo.setLog(id, today.minusDays(7), LogStatus.COMPLETED)
        repo.archiveHabit(id, true)
        val archived = db.dao().habit(id)!!
        val pause = db.dao().habitPauses(id).single()
        db.dao().updateHabit(archived.copy(archivedOn = today.minusDays(7)))
        db.dao().updatePause(pause.copy(startDate = today.minusDays(6)))
        repo.archiveHabit(id, false)
        val closed = repo.pauses.first().single()
        assertEquals(today, closed.endDate)
        rejected { repo.setLog(id, today.minusDays(3), LogStatus.COMPLETED) }
        repo.setLog(id, today, LogStatus.COMPLETED)
        val habit = db.dao().habit(id)!!
        assertEquals(2, Analytics.currentStreak(habit, repo.logs.first(), today, listOf(closed)))
        assertEquals(2L, Analytics.rate(listOf(habit), repo.logs.first(), today.minusDays(7), today, listOf(closed)).opportunities)
        repo.exportTo(Uri.fromFile(file()))
    }

    @Test fun immediateArchiveRestoreRemovesEmptyPauseAndRepeatedArchiveIsIdempotent() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Later", startDate = today.plusDays(3)))
        repo.archiveHabit(id, true); repo.archiveHabit(id, true)
        assertEquals(1, repo.pauses.first().size)
        repo.archiveHabit(id, false)
        assertTrue(repo.pauses.first().isEmpty())
    }

    @Test fun exactUndoRestoresFrozenEntryAndDoesNotRefundSpentFreeze() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Walk", startDate = today.minusDays(3)))
        repo.useFreeze(id, today.minusDays(1), "Travel day")
        val original = repo.logs.first().single()
        val receipt = repo.toggleCompletion(id, today.minusDays(1))
        assertEquals(LogStatus.COMPLETED, repo.logs.first().single().status)
        repo.undoCheckIn(receipt)
        assertEquals(original, repo.logs.first().single())
        assertEquals(1, repo.freezes.first().size)
        repo.setLog(id, today.minusDays(1), LogStatus.SKIPPED, "Updated travel note")
        assertTrue(repo.logs.first().single().isFrozen)
    }

    @Test fun undoRejectsANewerWidgetOrEditorWriteWithoutOverwritingIt() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Read", startDate = today))
        val receipt = repo.toggleCompletion(id, today)
        repo.setLog(id, today, LogStatus.COMPLETED, "Newer note")
        val latest = repo.logs.first().single()
        rejected { repo.undoCheckIn(receipt) }
        assertEquals(latest, repo.logs.first().single())
    }

    @Test fun quickUndoKeepsExistingNoteInsteadOfDeletingIt() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Read", startDate = today))
        repo.setLog(id, today, LogStatus.COMPLETED, "An important thought")
        repo.toggleCompletion(id, today)
        val remaining = repo.logs.first().single()
        assertEquals("An important thought", remaining.note)
        assertEquals(LogStatus.IN_PROGRESS, remaining.status)
        assertEquals(0, remaining.progress)
    }

    @Test fun clockRollbackCannotMakeEditsOrReorderUnexportable() = runBlocking {
        val futureTimestamp = System.currentTimeMillis() + 86_400_000
        db.dao().insertHabit(Habit(id = 1, name = "Future timestamp", startDate = today, createdAt = futureTimestamp, updatedAt = futureTimestamp))
        repo.saveHabit(db.dao().habit(1)!!.copy(name = "Edited after clock correction"))
        repo.reorderHabits(listOf(1))
        assertTrue(db.dao().habit(1)!!.updatedAt > futureTimestamp)
        assertEquals(futureTimestamp + 1, nextTimestamp(1, futureTimestamp, futureTimestamp))
        repo.exportTo(Uri.fromFile(file()))
    }

    @Test fun remindersAreClaimedOnceAndNeverForCompleteSkippedArchivedOrOffDays() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Walk", startDate = today, reminderMinutes = 0))
        assertTrue(repo.claimReminder(id, today)); assertFalse(repo.claimReminder(id, today))
        val completed = repo.saveHabit(Habit(name = "Done", startDate = today, reminderMinutes = 0))
        repo.setLog(completed, today, LogStatus.COMPLETED)
        assertFalse(repo.claimReminder(completed, today))
        val skipped = repo.saveHabit(Habit(name = "Skip", startDate = today, reminderMinutes = 0))
        repo.setLog(skipped, today, LogStatus.SKIPPED)
        assertFalse(repo.claimReminder(skipped, today))
        val archived = repo.saveHabit(Habit(name = "Archived", startDate = today, reminderMinutes = 0))
        repo.archiveHabit(archived, true)
        assertFalse(repo.claimReminder(archived, today))
        val offDay = repo.saveHabit(Habit(name = "Off day", startDate = today, scheduledDays = listOf(today.dayOfWeek.value % 7 + 1), reminderMinutes = 0))
        assertFalse(repo.claimReminder(offDay, today))
        assertFalse(repo.claimReminder(id, today.minusDays(1)))
        val replaced = repo.saveHabit(Habit(name = "Replaced", startDate = today, reminderMinutes = 0))
        val original = db.dao().habit(replaced)!!
        db.dao().updateHabit(original.copy(createdAt = original.createdAt + 1, updatedAt = original.updatedAt + 1))
        assertFalse(repo.claimReminder(replaced, today, original.createdAt))
        assertTrue(repo.claimReminder(replaced, today, original.createdAt + 1))
    }

    @Test fun missedPartialGoalCanUseFreezeWithoutLosingItsNote() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Read", startDate = today.minusDays(3), targetCount = 10, targetUnit = "pages"))
        repo.setProgress(id, today.minusDays(1), 4, "Stopped at chapter two")
        repo.useFreeze(id, today.minusDays(1))
        val frozen = repo.logs.first().single()
        assertEquals(LogStatus.SKIPPED, frozen.status)
        assertTrue(frozen.isFrozen)
        assertEquals("Stopped at chapter two", frozen.note)
        assertEquals(0, frozen.progress)
    }

    @Test fun failedNotificationClaimCanRetryAndStaleWorkerCannotReleaseReplacementClaim() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Walk", startDate = today, reminderMinutes = 0))
        val original = db.dao().habit(id)!!
        assertTrue(repo.claimReminder(id, today, original.createdAt))
        assertFalse(repo.claimReminder(id, today, original.createdAt))
        repo.releaseReminderClaim(id, today, original.createdAt)
        assertTrue(repo.claimReminder(id, today, original.createdAt))
        val replacement = original.copy(createdAt = original.createdAt + 1, updatedAt = original.updatedAt + 1)
        db.dao().updateHabit(replacement)
        repo.releaseReminderClaim(id, today, original.createdAt)
        assertFalse(repo.claimReminder(id, today, replacement.createdAt))
        repo.releaseReminderClaim(id, today, replacement.createdAt)
        assertTrue(repo.claimReminder(id, today, replacement.createdAt))
        assertFalse(repo.claimReminder(id, today, replacement.createdAt))
    }

    @Test fun encryptedRoundTripAndWrongPasswordLeaveCurrentDatabaseIntact() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Secret journal", startDate = today))
        repo.setLog(id, today, LogStatus.COMPLETED, "A private note")
        val output = file()
        repo.exportTo(Uri.fromFile(output), "correct horse battery staple".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(output.readBytes()))
        assertFalse(output.readText().contains("A private note"))
        repo.saveHabit(db.dao().habit(id)!!.copy(name = "Current name"))
        rejected { repo.importFrom(Uri.fromFile(output), "wrong password".toCharArray()) }
        assertEquals("Current name", db.dao().habit(id)!!.name)
        repo.importFrom(Uri.fromFile(output), "correct horse battery staple".toCharArray())
        assertEquals("Secret journal", db.dao().habit(id)!!.name)
        assertEquals("A private note", repo.logs.first().single().note)
    }

    @Test fun realVersionOneJsonNormalizesProgressAndArchivePause() = runBlocking {
        val archived = Habit(id = 1, name = "Read", startDate = today.minusDays(10), isArchived = true, archivedOn = today.minusDays(2))
        val old = LifeTrackBackup(formatVersion = 1, exportedAt = 1, habits = listOf(archived), logs = listOf(HabitLog(id = 1, habitId = 1, date = today.minusDays(2), status = LogStatus.COMPLETED)), settings = AppSettings(), freezes = emptyList())
        val document = BackupValidator.json.parseToJsonElement(BackupValidator.json.encodeToString(old)).jsonObject
        val oldHabits = JsonArray((document.getValue("habits") as JsonArray).map { JsonObject(it.jsonObject - setOf("scheduledDays", "targetCount", "targetUnit", "reminderMinutes", "scheduleHistory")) })
        val oldLogs = JsonArray((document.getValue("logs") as JsonArray).map { JsonObject(it.jsonObject - "progress") })
        val oldSettings = JsonObject(document.getValue("settings").jsonObject - setOf("hapticsEnabled", "animationsEnabled"))
        val v1 = JsonObject(document - "pauses" + mapOf("formatVersion" to JsonPrimitive(1), "habits" to oldHabits, "logs" to oldLogs, "settings" to oldSettings))
        val output = file(); output.writeText(v1.toString())
        repo.importFrom(Uri.fromFile(output))
        assertEquals(1, repo.logs.first().single().progress)
        assertEquals(archived.archivedOn!!.plusDays(1), repo.pauses.first().single().startDate)
        assertEquals((1..7).toList(), repo.habits.first().single().scheduledDays)
    }

    @Test fun dailySnapshotReadsOnlyRequestedDateAndRetainsConsistentHabitsAndPauses() = runBlocking {
        val id = repo.saveHabit(Habit(name = "Long history", startDate = today.minusDays(120)))
        for (ago in 1L..100L) repo.setLog(id, today.minusDays(ago), LogStatus.COMPLETED, "Historical entry")
        repo.setLog(id, today, LogStatus.COMPLETED, "Today's entry")
        db.dao().insertPause(HabitPause(habitId = id, startDate = today.minusDays(115), endDate = today.minusDays(112)))
        val expectedHabits = repo.habits.first()
        val expectedPauses = repo.pauses.first()
        val daily = repo.readSnapshot(today)
        assertEquals(expectedHabits, daily.habits)
        assertEquals(expectedPauses, daily.pauses)
        assertEquals(1, daily.logs.size)
        assertEquals(today, daily.logs.single().date)
        assertEquals("Today's entry", daily.logs.single().note)
        assertEquals(today.minusDays(50), repo.readSnapshot(today.minusDays(50)).logs.single().date)
        assertEquals(101, repo.readSnapshot().logs.size)
    }

    private fun file() = File.createTempFile("lifetrack-v2-", ".json", RuntimeEnvironment.getApplication().cacheDir).also { temporaryFiles += it }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected a rejected operation") }
        catch (expected: IllegalArgumentException) { assertFalse(expected.message.isNullOrBlank()) }
    }
}
