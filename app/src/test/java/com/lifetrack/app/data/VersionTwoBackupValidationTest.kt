package com.lifetrack.app.data

import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class VersionTwoBackupValidationTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(30), targetCount = 10, targetUnit = "pages", createdAt = 1, updatedAt = 2)
    private val backup = LifeTrackBackup(exportedAt = 3, habits = listOf(habit), logs = emptyList(), settings = AppSettings(), freezes = emptyList())
    private fun rejected(value: LifeTrackBackup) = assertThrows(IllegalArgumentException::class.java) { BackupValidator.validate(value, today) }

    @Test fun overlappingOrOrphanedPausesAreRejectedBeforeRestore() {
        rejected(backup.copy(pauses = listOf(HabitPause(1, 9, today.minusDays(2), today))))
        rejected(backup.copy(pauses = listOf(HabitPause(1, 1, today.minusDays(3), today), HabitPause(2, 1, today.minusDays(2), today))))
        rejected(backup.copy(pauses = listOf(HabitPause(1, 1, today, today))))
    }
    @Test fun partialAndCompletedProgressMustMatchTargetSemantics() {
        val partial = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.IN_PROGRESS, progress = 4)
        BackupValidator.validate(backup.copy(logs = listOf(partial)), today)
        BackupValidator.validate(backup.copy(logs = listOf(partial.copy(progress = 0, note = "A note before starting"))), today)
        rejected(backup.copy(logs = listOf(partial.copy(progress = 10))))
        rejected(backup.copy(logs = listOf(partial.copy(status = LogStatus.COMPLETED))))
        rejected(backup.copy(logs = listOf(partial.copy(progress = -1))))
    }
    @Test fun validClosedPauseCannotContainCheckIns() {
        val pause = HabitPause(1, 1, today.minusDays(3), today)
        val log = HabitLog(id = 1, habitId = 1, date = today.minusDays(2), status = LogStatus.COMPLETED, progress = 10)
        rejected(backup.copy(pauses = listOf(pause), logs = listOf(log)))
    }
    @Test fun schedulesAndGoalBoundsAreValidated() {
        rejected(backup.copy(habits = listOf(habit.copy(scheduledDays = emptyList()))))
        rejected(backup.copy(habits = listOf(habit.copy(scheduledDays = listOf(2, 1)))))
        rejected(backup.copy(habits = listOf(habit.copy(targetCount = 100_001))))
        rejected(backup.copy(habits = listOf(habit.copy(reminderMinutes = 1440))))
        rejected(backup.copy(habits = listOf(habit.copy(scheduleHistory = listOf(ScheduleRevision(today, listOf(1)))))))
    }
    @Test fun missingV2ProgressOrScheduleFieldsCannotSilentlyDefaultDuringRestore() {
        val document = BackupValidator.json.parseToJsonElement(BackupValidator.json.encodeToString(backup)).jsonObject
        val damagedHabit = JsonObject((document.getValue("habits") as JsonArray).first().jsonObject - "scheduledDays")
        val damaged = JsonObject(document + ("habits" to JsonArray(listOf(damagedHabit))))
        assertThrows(IllegalArgumentException::class.java) { BackupValidator.decode(damaged.toString(), today) }
    }
}
