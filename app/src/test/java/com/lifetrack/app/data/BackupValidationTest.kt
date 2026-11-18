package com.lifetrack.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupValidationTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10), createdAt = 100, updatedAt = 200)
    private val log = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED, note = "Chapter 4", createdAt = 150, updatedAt = 200, progress = 1)
    private fun backup(habits: List<Habit> = listOf(habit), logs: List<HabitLog> = listOf(log)) =
        LifeTrackBackup(exportedAt = 300, habits = habits, logs = logs, settings = AppSettings(), freezes = emptyList(),
            pauses = habits.filter { it.isArchived && it.archivedOn != null }.mapIndexed { index, value -> HabitPause(index + 1L, value.id, value.archivedOn!!.plusDays(1)) })

    private fun invalid(value: LifeTrackBackup) {
        assertThrows(IllegalArgumentException::class.java) { BackupValidator.validate(value, today) }
    }

    @Test fun `backup round trip preserves IDs notes dates settings and stacks`() {
        val parent = habit.copy(id = 8)
        val child = habit.copy(id = 20, afterHabitId = 8)
        val original = backup(listOf(parent, child), listOf(log.copy(habitId = 20))).copy(
            settings = AppSettings(theme = "DARK", backupReminder = true, lastExportAt = 100)
        )
        val restored = BackupValidator.json.decodeFromString<LifeTrackBackup>(BackupValidator.json.encodeToString(original))
        BackupValidator.validate(restored, today)
        assertEquals(original, restored)
    }

    @Test fun `duplicate daily entries are rejected before import`() =
        invalid(backup(logs = listOf(log, log.copy(id = 2))))

    @Test fun `future log is rejected`() = invalid(backup(logs = listOf(log.copy(date = today.plusDays(1)))))

    @Test fun `backfill before habit start is rejected`() =
        invalid(backup(logs = listOf(log.copy(date = habit.startDate.minusDays(1)))))

    @Test fun `future habit is valid without logs`() {
        BackupValidator.validate(backup(listOf(habit.copy(startDate = today.plusDays(5))), emptyList()), today)
    }

    @Test fun `orphaned logs are rejected`() = invalid(backup(logs = listOf(log.copy(habitId = 19))))

    @Test fun `cyclic stacks are rejected`() = invalid(backup(
        habits = listOf(habit.copy(afterHabitId = 2), habit.copy(id = 2, afterHabitId = 1))
    ))

    @Test fun `deleting original freeze log does not invalidate spent ledger`() {
        BackupValidator.validate(backup().copy(freezes = listOf(FreezeUsage("2026-10", 123))), today)
    }

    @Test fun `app lock is a local-only choice and never travels in backups`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup().copy(settings = AppSettings(appLockEnabled = true)))
        }
        assertTrue(exception.message!!.contains("not carried in backups"))
    }

    @Test fun `two frozen logs in one month are rejected across habits`() = invalid(backup(
        habits = listOf(habit, habit.copy(id = 2)),
        logs = listOf(log.copy(status = LogStatus.SKIPPED, isFrozen = true, progress = 0), log.copy(id = 2, habitId = 2, status = LogStatus.SKIPPED, isFrozen = true, progress = 0))
    ).copy(freezes = listOf(FreezeUsage("2026-10", 123))))

    @Test fun `freeze without ledger is rejected`() =
        invalid(backup(logs = listOf(log.copy(status = LogStatus.SKIPPED, isFrozen = true, progress = 0))))

    @Test fun `unknown backup version and invalid colors are rejected`() {
        invalid(backup().copy(formatVersion = 99))
        invalid(backup(habits = listOf(habit.copy(colorHex = "green"))))
    }

    @Test fun `archived habits retain earlier logs and reject later logs`() {
        val archived = habit.copy(isArchived = true, archivedOn = today.minusDays(1))
        BackupValidator.validate(backup(listOf(archived), listOf(log.copy(date = today.minusDays(1)))), today)
        invalid(backup(listOf(archived)))
    }

    @Test fun `missing dates are rejected rather than silently replaced by today`() {
        val document = BackupValidator.json.parseToJsonElement(BackupValidator.json.encodeToString(backup())).jsonObject
        val partialHabit = JsonObject(document.getValue("habits").let { it as JsonArray }.first().jsonObject - "startDate")
        val damaged = JsonObject(document + ("habits" to JsonArray(listOf(partialHabit))))
        assertThrows(SerializationException::class.java) {
            BackupValidator.json.decodeFromString<LifeTrackBackup>(damaged.toString())
        }
    }

    @Test fun `oversized notes invalid settings and invalid monthly keys are rejected`() {
        invalid(backup(logs = listOf(log.copy(note = "x".repeat(4_001)))))
        invalid(backup().copy(settings = AppSettings(theme = "BLUE")))
        invalid(backup().copy(freezes = listOf(FreezeUsage("2026-13", 123))))
        invalid(backup().copy(freezes = listOf(FreezeUsage("2026-11", 123))))
    }

    @Test fun `duplicate and zero saved IDs are rejected`() {
        invalid(backup(habits = listOf(habit, habit)))
        invalid(backup(habits = listOf(habit.copy(id = 0)), logs = emptyList()))
        invalid(backup(logs = listOf(log.copy(id = 0))))
    }

    @Test fun `frozen completions and malformed archive state are rejected`() {
        invalid(backup(logs = listOf(log.copy(isFrozen = true))).copy(freezes = listOf(FreezeUsage("2026-10", 123))))
        invalid(backup(habits = listOf(habit.copy(isArchived = true))))
        invalid(backup(habits = listOf(habit.copy(archivedOn = today))))
    }

    // ---- Flexible scheduling (backup v9) ----

    @Test fun `flexible scheduling requires backup version 9 and decodes fully at version 9`() {
        val flexible = habit.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = today)
        val atV8 = backup(listOf(flexible), emptyList()).copy(formatVersion = 8)
        val exception = assertThrows(IllegalArgumentException::class.java) { BackupValidator.validate(atV8, today) }
        assertTrue(exception.message!!.contains("Flexible scheduling requires backup version 9"))
        val atV9 = atV8.copy(formatVersion = 9)
        BackupValidator.validate(atV9, today)
        val decoded = BackupValidator.decode(BackupValidator.json.encodeToString(atV9), today)
        assertEquals("EVERY_N_DAYS", decoded.habits.single().scheduleMode)
        assertEquals(3, decoded.habits.single().intervalDays)
        assertEquals(today, decoded.habits.single().intervalAnchor)
        assertNull(decoded.habits.single().weeklyTarget)
    }

    @Test fun `schedule mode and its parameters must match on import`() {
        invalid(backup(listOf(habit.copy(scheduleMode = ScheduleMode.EVERY_N_DAYS.name, weeklyTarget = 3)), emptyList()).copy(formatVersion = 9))
        invalid(backup(listOf(habit.copy(scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, intervalDays = 3, intervalAnchor = today)), emptyList()).copy(formatVersion = 9))
        invalid(backup(listOf(habit.copy(scheduleMode = ScheduleMode.WEEKDAYS.name, intervalDays = 3, intervalAnchor = today)), emptyList()).copy(formatVersion = 9))
        invalid(backup(listOf(habit.copy(scheduleMode = ScheduleMode.WEEKDAYS.name, weeklyTarget = 2)), emptyList()).copy(formatVersion = 9))
    }

    @Test fun `weekly quota revisions require a target between 1 and 7`() {
        invalid(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            scheduleHistory = listOf(ScheduleRevision(today.minusDays(5), (1..7).toList(),
                mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 0))
        )), emptyList()).copy(formatVersion = 9))
        invalid(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            scheduleHistory = listOf(ScheduleRevision(today.minusDays(5), (1..7).toList(),
                mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 8))
        )), emptyList()).copy(formatVersion = 9))
    }

    @Test fun `v8 backups without flexible schedule keys decode with weekday defaults`() {
        val document = BackupValidator.json.parseToJsonElement(
            BackupValidator.json.encodeToString(backup().copy(formatVersion = 8))).jsonObject
        val keyFree = JsonObject(document + ("habits" to JsonArray(
            (document.getValue("habits") as JsonArray).map { entry ->
                JsonObject(entry.jsonObject - "scheduleMode" - "intervalDays" - "intervalAnchor" - "weeklyTarget")
            })))
        val decoded = BackupValidator.decode(keyFree.toString(), today)
        assertEquals(8, decoded.formatVersion)
        val restored = decoded.habits.single()
        assertEquals("WEEKDAYS", restored.scheduleMode)
        assertNull(restored.intervalDays)
        assertNull(restored.intervalAnchor)
        assertNull(restored.weeklyTarget)
    }

    @Test fun `interval and weekly target bounds are enforced on the habit itself`() {
        invalid(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 1, intervalAnchor = today)), emptyList()).copy(formatVersion = 9))
        invalid(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 366, intervalAnchor = today)), emptyList()).copy(formatVersion = 9))
        invalid(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 8)), emptyList()).copy(formatVersion = 9))
        // The inclusive boundaries accept: 2..365-day intervals and 1..7 weekly sessions.
        BackupValidator.validate(backup(listOf(
            habit.copy(id = 2, scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 2, intervalAnchor = today),
            habit.copy(id = 3, scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 365, intervalAnchor = today),
            habit.copy(id = 4, scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 1),
            habit.copy(id = 5, scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 7)
        ), emptyList()).copy(formatVersion = 9), today)
    }

    @Test fun `flexible revisions must carry valid parameters`() {
        val outOfRange = assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(habit.copy(
                scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = today,
                scheduleHistory = listOf(ScheduleRevision(today, (1..7).toList(),
                    mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 366))
            )), emptyList()).copy(formatVersion = 9), today)
        }
        assertTrue(outOfRange.message!!.contains("Invalid schedule revision parameters"))
        invalid(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = today,
            scheduleHistory = listOf(ScheduleRevision(today, (1..7).toList(), mode = ScheduleMode.EVERY_N_DAYS.name))
        )), emptyList()).copy(formatVersion = 9))
        // A valid mode-bearing revision at the inclusive boundary stays acceptable.
        BackupValidator.validate(backup(listOf(habit.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 365, intervalAnchor = today,
            scheduleHistory = listOf(ScheduleRevision(today, (1..7).toList(),
                mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 365))
        )), emptyList()).copy(formatVersion = 9), today)
    }

    @Test fun `a weekday to quota switch history is accepted`() {
        // Exactly the shape saveHabit produces: a seeded null-mode weekday era plus the quota revision.
        val switched = habit.copy(
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3,
            startDate = today.minusDays(30),
            scheduleHistory = listOf(
                ScheduleRevision(today.minusDays(30), (1..7).toList()),
                ScheduleRevision(today, (1..7).toList(),
                    mode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)))
        BackupValidator.validate(backup(listOf(switched), emptyList()).copy(formatVersion = 9), today)
    }

    @Test fun `a weekday to interval switch history is accepted`() {
        // The interval-direction repair twin: the null-mode era must resolve WEEKDAYS, not the habit's stored mode.
        val switched = habit.copy(
            scheduleMode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3, intervalAnchor = today,
            startDate = today.minusDays(30),
            scheduleHistory = listOf(
                ScheduleRevision(today.minusDays(30), (1..7).toList()),
                ScheduleRevision(today, (1..7).toList(),
                    mode = ScheduleMode.EVERY_N_DAYS.name, intervalDays = 3)))
        BackupValidator.validate(backup(listOf(switched), emptyList()).copy(formatVersion = 9), today)
    }
}

