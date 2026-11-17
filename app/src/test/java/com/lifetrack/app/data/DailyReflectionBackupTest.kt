package com.lifetrack.app.data

import java.time.LocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backup format version 7: daily reflections, and older formats still decoding without them. */
class DailyReflectionBackupTest {
    private val today = LocalDate.of(2026, 11, 17)
    private val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10), createdAt = 100, updatedAt = 200)
    private val reflection = DailyReflection(date = today.minusDays(1), mood = 4, journal = "calm evening", createdAt = 100, updatedAt = 150)

    private fun backup(reflections: List<DailyReflection>, formatVersion: Int) = LifeTrackBackup(
        formatVersion = formatVersion, exportedAt = 300, habits = listOf(habit), logs = emptyList(),
        settings = AppSettings(), freezes = emptyList(), dailyReflections = reflections
    )

    // ---- B1: reflections ride in version 7 exports ----

    @Test fun `a version seven backup with reflections validates and decodes as version seven`() {
        val original = backup(listOf(reflection), 7)
        BackupValidator.validate(original, today)
        val restored = BackupValidator.decode(BackupValidator.json.encodeToString(original), today)
        assertEquals(7, restored.formatVersion)
        assertEquals(listOf(reflection), restored.dailyReflections)
    }

    // ---- B2: plain exports stay key-free ----

    @Test fun `plain exports omit the dailyReflections key so older apps can still decode them`() {
        listOf(3, 4, 5, 6).forEach { version ->
            val plain = backup(emptyList(), version)
            BackupValidator.validate(plain, today)
            val document = Json.parseToJsonElement(BackupValidator.json.encodeToString(plain)).jsonObject
            assertFalse("version $version must not carry reflections", "dailyReflections" in document)
        }
    }

    // ---- B3: round trip ----

    @Test fun `version seven round trip preserves every reflection field exactly`() {
        val rows = listOf(
            DailyReflection(date = today.minusDays(3), mood = 1, journal = null, createdAt = 10, updatedAt = 10),
            DailyReflection(date = today.minusDays(2), mood = null, journal = "journal only", createdAt = 150, updatedAt = 300),
            DailyReflection(date = today.minusDays(1), mood = 5, journal = "mood and words", createdAt = 0, updatedAt = 25)
        )
        val original = backup(rows, 7)
        val restored = BackupValidator.json.decodeFromString<LifeTrackBackup>(BackupValidator.json.encodeToString(original))
        BackupValidator.validate(restored, today)
        assertEquals(original, restored)
        assertNull(restored.dailyReflections[0].journal)
        assertNull(restored.dailyReflections[1].mood)
        assertEquals(0L, restored.dailyReflections[2].createdAt)
    }

    // ---- B4: per-row validation ----

    @Test fun `every valid reflection shape is accepted at version seven`() {
        BackupValidator.validate(backup(listOf(
            DailyReflection(date = today, mood = 3, journal = null, createdAt = 100, updatedAt = 200),
            DailyReflection(date = today.minusDays(1), mood = null, journal = "words only", createdAt = 100, updatedAt = 200),
            DailyReflection(date = today.minusDays(2), mood = 2, journal = "mood and words", createdAt = 100, updatedAt = 200),
            DailyReflection(date = today.minusDays(3), mood = null, journal = "x".repeat(2_000), createdAt = 100, updatedAt = 200),
            DailyReflection(date = LocalDate.of(1900, 1, 1), mood = 5, journal = null, createdAt = 0, updatedAt = 0)
        ), 7), today)
    }

    @Test fun `reflection rows without timestamps decode with defaults and validate`() {
        val text = BackupValidator.json.encodeToString(backup(listOf(reflection), 7))
        val document = Json.parseToJsonElement(text).jsonObject
        val stripped = JsonObject(document.toMap() + ("dailyReflections" to JsonArray(
            document.getValue("dailyReflections").jsonArray.map { item ->
                JsonObject(item.jsonObject.toMap() - "createdAt" - "updatedAt")
            }
        )))
        val restored = BackupValidator.decode(stripped.toString(), today)
        val row = restored.dailyReflections.single()
        assertTrue(row.createdAt >= 0)
        assertEquals(row.createdAt, row.updatedAt)
        assertEquals(reflection.date, row.date)
        assertEquals(reflection.mood, row.mood)
        assertEquals(reflection.journal, row.journal)
    }

    @Test fun `invalid reflection rows are rejected before any write`() {
        fun reject(rows: List<DailyReflection>) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupValidator.validate(backup(rows, 7), today)
            }
        }
        reject(listOf(reflection.copy(date = today.plusDays(1)))) // future date
        reject(listOf(reflection.copy(date = LocalDate.of(1899, 12, 31)))) // before 1900
        reject(listOf(reflection.copy(mood = 0)))
        reject(listOf(reflection.copy(mood = 6)))
        reject(listOf(reflection.copy(mood = -1)))
        reject(listOf(DailyReflection(date = today.minusDays(1), mood = null, journal = "x".repeat(2_001), createdAt = 100, updatedAt = 200))) // too long
        reject(listOf(DailyReflection(date = today.minusDays(1), mood = null, journal = "   ", createdAt = 100, updatedAt = 200))) // blank-only journal
        reject(listOf(DailyReflection(date = today.minusDays(1), mood = null, journal = null, createdAt = 100, updatedAt = 200))) // neither present
        reject(listOf(DailyReflection(date = today.minusDays(1), mood = 3, journal = null, createdAt = -1, updatedAt = 200))) // negative creation
        reject(listOf(reflection.copy(updatedAt = 50))) // edited before it was created
        reject(listOf(reflection, reflection.copy(updatedAt = 151))) // duplicate date
    }

    // ---- B5: backward compatibility ----

    @Test fun `older backups without reflections still decode unchanged`() {
        listOf(3, 4, 5, 6).forEach { version ->
            val restored = BackupValidator.decode(BackupValidator.json.encodeToString(backup(emptyList(), version)), today)
            assertEquals(version, restored.formatVersion)
            assertTrue(restored.dailyReflections.isEmpty())
        }
    }

    @Test fun `a hand made version one backup decodes with defaults and no reflections`() {
        val text = BackupValidator.json.encodeToString(backup(emptyList(), 3))
        val document = Json.parseToJsonElement(text).jsonObject
        // Simulate a genuine version 1 file: none of the later collections or scheduling fields exist yet.
        val habits = document.getValue("habits").jsonArray.map { item ->
            JsonObject(item.jsonObject.toMap() - "scheduledDays" - "targetCount" - "targetUnit" - "reminderMinutes" - "scheduleHistory")
        }
        val v1 = JsonObject(
            document.toMap()
                - "pauses" - "activities" - "activityEntries"
                + ("formatVersion" to JsonPrimitive(1))
                + ("habits" to JsonArray(habits))
                + ("settings" to JsonObject(document.getValue("settings").jsonObject.toMap() - "hapticsEnabled" - "animationsEnabled"))
        )
        val restored = BackupValidator.decode(v1.toString(), today)
        assertEquals(3, restored.formatVersion) // version 1 normalizes to version 3
        assertTrue(restored.dailyReflections.isEmpty())
        assertTrue(restored.pauses.isEmpty())
        assertEquals((1..7).toList(), restored.habits.single().scheduledDays)
    }

    @Test fun `hand made version six backups carrying reflections are rejected`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(listOf(reflection), 6), today)
        }
        assertTrue(exception.message!!.contains("require backup version 7"))

        // The decode path enforces the same gate: a v7 export relabeled as v6 is not smuggled through.
        val relabeled = BackupValidator.json.encodeToString(backup(listOf(reflection), 7))
            .replace("\"formatVersion\": 7", "\"formatVersion\": 6")
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.decode(relabeled, today)
        }
    }

    @Test fun `backups above version nine are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(backup(emptyList(), 10), today)
        }
    }

    @Test fun `a mood sent as a numeric JSON string is coerced and still range checked`() {
        val document = Json.parseToJsonElement(BackupValidator.json.encodeToString(backup(listOf(reflection), 7))).jsonObject
        // kotlinx coerces a numeric string to Int even in strict mode; the 1..5 range check then applies.
        val coerced = JsonObject(document.toMap() + ("dailyReflections" to JsonArray(
            document["dailyReflections"]!!.jsonArray.map { item -> JsonObject(item.jsonObject.toMap() + ("mood" to JsonPrimitive("4"))) }
        )))
        assertEquals(4, BackupValidator.decode(coerced.toString(), today).dailyReflections.single().mood)
        val outOfRange = JsonObject(document.toMap() + ("dailyReflections" to JsonArray(
            document["dailyReflections"]!!.jsonArray.map { item -> JsonObject(item.jsonObject.toMap() + ("mood" to JsonPrimitive("9"))) }
        )))
        assertThrows(IllegalArgumentException::class.java) { BackupValidator.decode(outOfRange.toString(), today) }
    }
}
