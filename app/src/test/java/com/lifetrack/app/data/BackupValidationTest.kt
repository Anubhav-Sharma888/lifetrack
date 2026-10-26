package com.lifetrack.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}

