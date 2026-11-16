package com.lifetrack.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/** Backup format version 5: directional activities, and older formats still decoding with defaults. */
class DirectionalBackupTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10), createdAt = 100, updatedAt = 200)
    private val entry = ActivityEntry(id = 1, activityId = 1, date = today.minusDays(1), quantity = 76.0, createdAt = 150, updatedAt = 200)

    private fun directionalActivity() = WorkoutActivity(
        id = 1, name = "Book", unit = "pages", target = 300.0,
        trackingMode = ActivityMode.INCREASING.name, startingValue = 50.0, createdAt = 100, updatedAt = 200
    )

    private fun backup(activities: List<WorkoutActivity>, entries: List<ActivityEntry>, formatVersion: Int) =
        LifeTrackBackup(formatVersion = formatVersion, exportedAt = 300, habits = listOf(habit), logs = emptyList(),
            settings = AppSettings(), freezes = emptyList(), activities = activities, activityEntries = entries)

    @Test fun `version five round trip preserves tracking mode and starting value`() {
        val original = backup(listOf(directionalActivity()), listOf(entry), 5)
        val restored = BackupValidator.json.decodeFromString<LifeTrackBackup>(BackupValidator.json.encodeToString(original))
        BackupValidator.validate(restored, today)
        assertEquals(original, restored)
    }

    @Test fun `version three backups reject directional activities`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(directionalActivity()), listOf(entry), 3), today)
        }
    }

    @Test fun `a logged activity carrying a leftover starting value still requires version five`() {
        // The editor clears hidden baselines on mode switches, but the data model treats any
        // startingValue as directional so older apps are never surprised by unknown keys.
        val hybrid = directionalActivity().copy(trackingMode = ActivityMode.LOGGED.name)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(hybrid), emptyList(), 3), today)
        }
        BackupValidator.validate(backup(listOf(hybrid), emptyList(), 5), today)
    }

    @Test fun `plain activities omit the version five keys so older apps can still decode exports`() {
        val text = BackupValidator.json.encodeToString(
            backup(listOf(directionalActivity().copy(trackingMode = "LOGGED", startingValue = null)), emptyList(), 3)
        )
        org.junit.Assert.assertFalse(text.contains("trackingMode"))
        org.junit.Assert.assertFalse(text.contains("startingValue"))
    }

    @Test fun `older backups without the new fields decode as plain logged activities`() {
        val text = BackupValidator.json.encodeToString(
            backup(listOf(directionalActivity().copy(trackingMode = "LOGGED", startingValue = null)), listOf(entry.copy(quantity = 20.0)), 3)
        )
        // Simulate an older app's JSON, which cannot contain the version-5 keys.
        val document = Json.parseToJsonElement(text).jsonObject
        val stripped = JsonObject(document.toMap() + ("activities" to JsonArray(
            document["activities"]!!.jsonArray.map { item -> JsonObject(item.jsonObject.toMap() - "trackingMode" - "startingValue") }
        )))
        val restored = BackupValidator.decode(stripped.toString(), today)
        assertEquals("LOGGED", restored.activities.single().trackingMode)
        assertNull(restored.activities.single().startingValue)
        assertEquals(20.0, restored.activityEntries.single().quantity, 0.0)
    }

    @Test fun `unknown tracking modes and invalid starting values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(directionalActivity().copy(trackingMode = "SIDEWAYS", startingValue = null)), emptyList(), 5), today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(directionalActivity().copy(startingValue = -1.0)), listOf(entry), 5), today)
        }
    }

    @Test fun `decreasing activities may record zero but other modes may not`() {
        val decreasing = directionalActivity().copy(trackingMode = ActivityMode.DECREASING.name, startingValue = 30.0, target = 0.0, unit = "tasks")
        BackupValidator.validate(backup(listOf(decreasing), listOf(entry.copy(quantity = 0.0)), 5), today)
        val logged = directionalActivity().copy(trackingMode = ActivityMode.LOGGED.name, startingValue = null)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(logged), listOf(entry.copy(quantity = 0.0)), 5), today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(directionalActivity().copy(target = 0.0)), listOf(entry), 5), today)
        }
    }
}
