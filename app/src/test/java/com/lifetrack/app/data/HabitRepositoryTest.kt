package com.lifetrack.app.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.room.Room
import com.lifetrack.app.domain.Analytics
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun csvExportWritesUtf8HeaderAndOneRowPerCheckInWithoutBackupSideEffects() = runBlocking {
        val habit = createHabit("Export me")
        repository.setLog(habit, today, LogStatus.COMPLETED, "A note with, comma and \"quote\"")
        // A CSV export must not disturb the backup reminder's last-successful-backup timestamp.
        val backup = backupFile()
        repository.exportTo(Uri.fromFile(backup))
        val lastBackupAt = repository.settings.first().lastExportAt
        assertNotNull(lastBackupAt)
        val file = backupFile()
        repository.exportCsvTo(Uri.fromFile(file))
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("\uFEFF"))
        assertEquals("log_updated_at", text.removePrefix("\uFEFF").substringBefore("\r\n").substringAfterLast(','))
        assertTrue(text.contains("Export me"))
        assertTrue(text.contains("\"A note with, comma and \"\"quote\"\"\""))
        assertEquals(2, text.split("\r\n").size - 1)
        assertEquals(lastBackupAt, repository.settings.first().lastExportAt)
    }

    @Test
    fun directionalActivityValidationBackupRoundTripAndEntryGuards() = runBlocking {
        expectRejected { repository.saveActivity(WorkoutActivity(name = "Sideways", unit = "u", trackingMode = "SIDEWAYS")) }
        expectRejected { repository.saveActivity(WorkoutActivity(name = "Negative start", unit = "u", trackingMode = ActivityMode.DECREASING.name, startingValue = -1.0)) }
        expectRejected { repository.saveActivity(WorkoutActivity(name = "Zero target", unit = "u", trackingMode = ActivityMode.INCREASING.name, target = 0.0)) }
        // saveActivity echoes id 0 for inserts; the flow carries the real Room id.
        repository.saveActivity(WorkoutActivity(
            name = "Book", unit = "pages", trackingMode = ActivityMode.INCREASING.name, startingValue = 50.0, target = 300.0
        ))
        val id = repository.activities.first().single().id
        assertEquals("INCREASING", repository.activities.first().single().trackingMode)
        // A clock-skew future entry must be rejected before it can brick later JSON exports.
        expectRejected { repository.setActivityEntry(id, today.plusDays(1), 10.0) }
        repository.setActivityEntry(id, today, 76.0)
        assertEquals(76.0, repository.activityEntries.first().single().quantity, 0.0)
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val text = output.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"formatVersion\": 5"))
        assertTrue(text.contains("\"trackingMode\": \"INCREASING\""))
        // Replacement import restores the directional activity and its entries exactly.
        repository.deleteActivity(id)
        repository.importFrom(Uri.fromFile(output))
        val restored = repository.activities.first().single()
        assertEquals("INCREASING", restored.trackingMode)
        assertEquals(50.0, restored.startingValue!!, 0.0)
        assertEquals(76.0, repository.activityEntries.first().single().quantity, 0.0)
    }

    @Test
    fun decreasingActivityMayRecordZeroButLoggedMayNot() = runBlocking {
        // saveActivity echoes id 0 for inserts; real ids come from the flow, matched by name.
        repository.saveActivity(WorkoutActivity(
            name = "Tasks left", unit = "tasks", trackingMode = ActivityMode.DECREASING.name, startingValue = 30.0, target = 0.0
        ))
        val countdown = repository.activities.first().single { it.name == "Tasks left" }.id
        repository.setActivityEntry(countdown, today, 0.0)
        assertEquals(0.0, repository.activityEntries.first().single().quantity, 0.0)
        repository.saveActivity(WorkoutActivity(name = "Push-ups", unit = "pushups"))
        val plain = repository.activities.first().single { it.name == "Push-ups" }.id
        expectRejected { repository.setActivityEntry(plain, today, 0.0) }
        repository.setActivityEntry(plain, today, 1.0)
        assertEquals(1.0, repository.activityEntries.first().single { it.activityId == plain }.quantity, 0.0)
    }

    // ---- Flexible scheduling: grid-only interval editing, any-day quota editing, quota guardrails ----

    @Test
    fun intervalHabitsAcceptOnlyGridDatesWhileQuotaHabitsAcceptAnyDay() = runBlocking {
        val (interval, quota) = importFlexibleHabits()
        // Off-grid past date: the anchor is six days ago, so yesterday sits between grid dates.
        expectRejected { repository.setLog(interval, today.minusDays(4), LogStatus.COMPLETED) }
        assertTrue(repository.setLog(interval, today.minusDays(6), LogStatus.COMPLETED))
        assertTrue(repository.setLog(interval, today.minusDays(3), LogStatus.COMPLETED))
        assertTrue(repository.setLog(interval, today, LogStatus.COMPLETED))
        assertEquals(
            listOf(today.minusDays(6), today.minusDays(3), today),
            repository.logs.first().filter { it.habitId == interval }.map { it.date }.sorted()
        )
        // Weekly-quota habits may check in on any day of the week.
        assertTrue(repository.setLog(quota, today, LogStatus.COMPLETED))
        assertTrue(repository.setLog(quota, today.minusDays(2), LogStatus.COMPLETED))
    }

    @Test
    fun weeklyQuotaHabitsDoNotUseStreakFreezes() = runBlocking {
        val (interval, quota) = importFlexibleHabits()
        try {
            repository.useFreeze(quota, today.minusDays(1))
            fail("Weekly-quota habits must never consume a monthly freeze")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Weekly-quota habits do not use streak freezes.", expected.message)
        }
        assertEquals(emptyList<FreezeUsage>(), repository.freezes.first())
        // The interval habit still freezes normally on a missed grid date.
        repository.useFreeze(interval, today.minusDays(6), "Travel")
        assertEquals(1, repository.freezes.first().size)
    }

    @Test
    fun weeklyQuotaDaysRejectStreakDecisionsWhileWeekdayPartialsStillProtect() = runBlocking {
        // A v9 fixture mirroring importFlexibleHabits' quota habit, but with a real 3-unit goal so
        // a genuine quota-era partial day (1 of 3) can be seeded through the public API: the guard
        // must fire ahead of every later check, including the decision-shape requirement itself.
        val quota = Habit(
            id = 8, name = "Three times a week", startDate = today.minusDays(35), targetCount = 3,
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            scheduleHistory = listOf(ScheduleRevision(today.minusDays(6), (1..7).toList(),
                mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)),
            createdAt = 100, updatedAt = 200,
        )
        val backup = LifeTrackBackup(
            formatVersion = 9, exportedAt = 300, habits = listOf(quota),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
        )
        repository.importFrom(Uri.fromFile(backupFile(BackupValidator.json.encodeToString(backup))))
        // Quota days are check-in-eligible every day, so the partial day is a real check-in shape.
        // setProgress returns "newly completed", which a 1-of-3 partial deliberately is not.
        repository.setProgress(quota.id, today.minusDays(2), 1)
        val partial = repository.logs.first().single()
        assertEquals(LogStatus.IN_PROGRESS, partial.status)
        assertEquals(1, partial.progress)
        try {
            repository.setStreakProtection(quota.id, today.minusDays(2), true)
            fail("Weekly-quota days have no chain to protect")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Weekly-quota habits do not use day streaks.", expected.message)
        }
        assertTrue(repository.streakProtections.first().isEmpty())
        // The guard is quota-only: an ordinary weekday habit's partial day still protects.
        val weekday = repository.saveHabit(Habit(name = "Read", startDate = today.minusDays(35), targetCount = 4))
        repository.setProgress(weekday, today.minusDays(1), 3)
        repository.setStreakProtection(weekday, today.minusDays(1), true)
        assertEquals(listOf(weekday), repository.streakProtections.first().map { it.habitId })
    }

    @Test
    fun claimReminderSkipsQuotaHabitsAndNudgesDueWeekdayHabits() = runBlocking {
        val (interval, quota, weekday) = importFlexibleHabits()
        // reminderMinutes = 0 is always in the past, so LocalTime.now() never blocks the claim.
        assertFalse(repository.claimReminder(quota, today))
        assertTrue(repository.claimReminder(weekday, today))
        assertTrue(repository.claimReminder(interval, today))
        // A second claim on the same day is deduplicated by the delivery record.
        assertFalse(repository.claimReminder(weekday, today))
    }

    @Test
    fun weekdayToQuotaSwitchPreservesTheWeekdayEraWithoutFalseRejection() = runBlocking {
        val id = repository.saveHabit(Habit(name = "Read", startDate = today.minusDays(30)))
        repository.setLog(id, today.minusDays(1), LogStatus.COMPLETED)
        val original = database.dao().habit(id)!!
        // The repaired save path: the seeded weekday era plus the quota revision must not false-reject.
        val switchedId = repository.saveHabit(original.copy(
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3, intervalDays = null))
        val habit = database.dao().habit(switchedId)!!
        assertEquals("WEEKLY_QUOTA", habit.scheduleMode)
        assertEquals(3, habit.weeklyTarget)
        assertNull(habit.intervalDays)
        assertEquals(2, habit.scheduleHistory.size)
        assertNull(habit.scheduleHistory.first().mode) // the weekday era stays a null-mode weekday period
        assertEquals(ScheduleMode.WEEKLY_QUOTA.name, habit.scheduleHistory.last().mode)
        assertEquals(today, habit.scheduleHistory.last().effectiveFrom)
        // The weekday-era check-in survived the switch untouched.
        assertEquals(listOf(today.minusDays(1)), repository.logs.first().map { it.date })
    }

    @Test
    fun weekdayToIntervalSwitchKeepsTheWeekdayEraEligibleBeforeTheAnchor() = runBlocking {
        val id = repository.saveHabit(Habit(name = "Walk", startDate = today.minusDays(30)))
        val switchedId = repository.saveHabit(database.dao().habit(id)!!.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, weeklyTarget = null))
        val habit = database.dao().habit(switchedId)!!
        assertEquals("EVERY_N_DAYS", habit.scheduleMode)
        assertEquals(3, habit.intervalDays)
        assertEquals(today, habit.intervalAnchor)
        assertEquals(2, habit.scheduleHistory.size)
        assertNull(habit.scheduleHistory.first().mode)
        assertEquals(ScheduleMode.EVERY_N_DAYS.name, habit.scheduleHistory.last().mode)
        // Mode-at-date: the pre-switch era resolves WEEKDAYS (any weekday eligible), while
        // post-switch dates follow the grid anchored at the switch date.
        assertTrue(Analytics.eligible(habit, habit.startDate.plusDays(1), emptyList()))
        assertFalse(Analytics.eligible(habit, today.plusDays(1), emptyList())) // off-grid under the new anchor
        assertTrue(Analytics.eligible(habit, today.plusDays(3), emptyList()))  // anchor + 3
    }

    @Test
    fun newFlexibleHabitsAnchorTheirWholeVisibleHistoryAtTheStartDate() = runBlocking {
        val start = today.minusDays(10)
        val id = repository.saveHabit(Habit(
            name = "Every third day", startDate = start,
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3))
        val habit = database.dao().habit(id)!!
        // A backdated new habit owns its whole visible history: the seed sits at startDate, not today.
        assertEquals(1, habit.scheduleHistory.size)
        assertEquals(start, habit.scheduleHistory.single().effectiveFrom)
        assertEquals(ScheduleMode.EVERY_N_DAYS.name, habit.scheduleHistory.single().mode)
        assertEquals(3, habit.scheduleHistory.single().intervalDays)
        assertEquals(start, habit.intervalAnchor)
        // The grid starts at startDate: no invented weekday-era dues inside the backdated window.
        assertTrue(Analytics.eligible(habit, start, emptyList()))
        assertFalse(Analytics.eligible(habit, start.plusDays(1), emptyList())) // off-grid
        assertTrue(Analytics.eligible(habit, start.plusDays(3), emptyList()))  // anchor + 3
    }

    @Test
    fun classifyBackupDistinguishesPlainFromEncryptedAndDamagedEnvelopes() = runBlocking {
        val parent = createHabit("Brush teeth")
        repository.setLog(parent, today, LogStatus.COMPLETED)
        val plain = backupFile()
        repository.exportTo(Uri.fromFile(plain))
        assertEquals(BackupKind.PLAIN, repository.classifyBackup(Uri.fromFile(plain)))
        val encrypted = backupFile()
        repository.exportTo(Uri.fromFile(encrypted), "correct horse battery staple".toCharArray())
        assertEquals(BackupKind.ENCRYPTED, repository.classifyBackup(Uri.fromFile(encrypted)))
        // A truncated encrypted envelope cannot fully parse, but its marker still identifies it:
        // the password prompt is the honest next step, not the plain-backup dialog.
        val truncated = backupFile()
        val full = encrypted.readText()
        truncated.writeText(full.substring(0, full.length / 2))
        assertEquals(BackupKind.ENCRYPTED, repository.classifyBackup(Uri.fromFile(truncated)))
        // An unopenable URI (revoked provider, deleted file) surfaces the same error the
        // restore path would raise, so the dialog can explain it before any password UI.
        assertThrows(Exception::class.java) {
            runBlocking { repository.classifyBackup(Uri.parse("content://com.example.invalid/missing.json")) }
        }
        Unit
    }

    @Test
    fun appLockSettingPersistsLocallySurvivesRestoresAndNeverTravelsInBackups() = runBlocking {
        repository.updateSettings(AppSettings(appLockEnabled = true))
        assertTrue(repository.settings.first().appLockEnabled)
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val text = output.readText(Charsets.UTF_8)
        // The lock must be absent from the exported JSON so older app versions can still decode the file.
        val settingsJson = kotlinx.serialization.json.Json.parseToJsonElement(text)
            .jsonObject.getValue("settings").jsonObject
        assertFalse("appLockEnabled" in settingsJson)
        assertEquals(3, BackupValidator.decode(text).formatVersion)
        // Restoring must never silently weaken the local lock, even though backups never carry it.
        repository.importFrom(Uri.fromFile(output))
        assertTrue(repository.settings.first().appLockEnabled)
        repository.updateSettings(AppSettings(appLockEnabled = false))
        assertFalse(repository.settings.first().appLockEnabled)
    }

    @Test
    fun resetErasesEveryRecordAndRestoresDefaultSettings() = runBlocking {
        val habit = createHabit("Keep me")
        repository.setLog(habit, today, LogStatus.COMPLETED, "Keep this note")
        repository.useFreeze(habit, today.minusDays(1), "Rest")
        val archived = createHabit("Archived habit")
        repository.archiveHabit(archived, true)
        repository.saveActivity(WorkoutActivity(name = "Push-ups", unit = "pushups", target = 20.0))
        // saveActivity echoes the entity back with id 0 for inserts; the flow carries the real Room id.
        val activityId = repository.activities.first().single().id
        repository.setActivityEntry(activityId, today, 15.0)
        database.dao().insertReminderDelivery(ReminderDelivery(habit, today, 5L))
        repository.updateSettings(AppSettings(theme = "DARK", backupReminder = true, appLockEnabled = true))

        val resetIds = repository.resetAllData()
        assertEquals(listOf(habit, archived), resetIds)
        assertTrue(repository.habits.first().isEmpty())
        assertTrue(repository.logs.first().isEmpty())
        assertTrue(repository.freezes.first().isEmpty())
        assertTrue(repository.pauses.first().isEmpty())
        assertTrue(repository.activities.first().isEmpty())
        assertTrue(repository.activityEntries.first().isEmpty())
        // Reset restores default preferences but must never silently weaken the local lock.
        assertEquals(AppSettings(appLockEnabled = true), repository.settings.first())
    }

    @Test
    fun resetRejectsStaleWidgetReminderAndUndoActions() = runBlocking {
        val habit = createHabit("Original habit")
        val createdAt = database.dao().habit(habit)!!.createdAt
        val undo = repository.toggleCompletion(habit, today)
        repository.resetAllData()
        expectRejected { repository.completeHabit(habit, today, expectedCreatedAt = createdAt) }
        expectRejected { repository.undoCheckIn(undo) }
        assertFalse(repository.claimReminder(habit, today))
    }

    @Test
    fun afterResetTheRepositoryRemainsUsableAndExportsAValidEmptyBackup() = runBlocking {
        createHabit("Before reset")
        repository.resetAllData()
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val backup = BackupValidator.decode(output.readText())
        assertEquals(3, backup.formatVersion)
        assertTrue(backup.habits.isEmpty())
        assertTrue(backup.logs.isEmpty())
        assertTrue(backup.activities.isEmpty())
        assertTrue(backup.activityEntries.isEmpty())

        val habit = repository.saveHabit(Habit(name = "After reset", startDate = today))
        assertTrue(repository.setLog(habit, today, LogStatus.COMPLETED))
        assertEquals(LogStatus.COMPLETED, repository.logs.first().single().status)
        assertNotNull(repository.settings.first().lastExportAt)
    }

    @Test
    fun exportThenResetThenImportRestoresEveryRecord() = runBlocking {
        val parent = createHabit("Brush teeth")
        val child = repository.saveHabit(Habit(name = "Meditate", afterHabitId = parent, startDate = today.minusDays(10)))
        repository.setLog(parent, today, LogStatus.COMPLETED, "Done offline")
        repository.useFreeze(child, today.minusDays(1), "Rest")
        repository.updateSettings(AppSettings(theme = "DARK", backupReminder = true))
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val expectedHabits = repository.habits.first()
        val expectedLogs = repository.logs.first()
        val expectedFreezes = repository.freezes.first()
        assertTrue(expectedHabits.isNotEmpty())

        repository.resetAllData()
        assertTrue(repository.habits.first().isEmpty())
        repository.importFrom(Uri.fromFile(output))
        assertEquals(expectedHabits, repository.habits.first())
        assertEquals(expectedLogs, repository.logs.first())
        assertEquals(expectedFreezes, repository.freezes.first())
        assertEquals("DARK", repository.settings.first().theme)
    }

    private suspend fun createHabit(name: String = "Drink water"): Long =
        repository.saveHabit(Habit(name = name, startDate = today.minusDays(35)))

    /** Imports one interval, one quota and one weekday habit so engine dispatch sees real stored
     *  history: a mid-history anchor cannot be produced by saveHabit, which seeds new flexible
     *  habits at their start date and re-anchors to today only on an existing habit's change. */
    private suspend fun importFlexibleHabits(): Triple<Long, Long, Long> {
        val anchor = today.minusDays(6)
        val interval = Habit(
            id = 5, name = "Every third day", startDate = today.minusDays(35), reminderMinutes = 0,
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = anchor,
            scheduleHistory = listOf(ScheduleRevision(anchor, (1..7).toList(),
                mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3)),
            createdAt = 100, updatedAt = 200,
        )
        val quota = Habit(
            id = 6, name = "Three times a week", startDate = today.minusDays(35), reminderMinutes = 0,
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            scheduleHistory = listOf(ScheduleRevision(anchor, (1..7).toList(),
                mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)),
            createdAt = 100, updatedAt = 200,
        )
        val weekday = Habit(
            id = 7, name = "Daily read", startDate = today.minusDays(35), reminderMinutes = 0,
            scheduleHistory = listOf(ScheduleRevision(anchor, (1..7).toList())),
            createdAt = 100, updatedAt = 200,
        )
        val backup = LifeTrackBackup(
            formatVersion = 9, exportedAt = 300, habits = listOf(interval, quota, weekday),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList(),
        )
        repository.importFrom(Uri.fromFile(backupFile(BackupValidator.json.encodeToString(backup))))
        return Triple(interval.id, quota.id, weekday.id)
    }

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
