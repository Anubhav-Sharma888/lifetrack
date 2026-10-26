package com.lifetrack.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Builds the actual exported version-1 schema, then lets Room migrate and validate version 2. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DatabaseMigrationTest {
    @Test fun versionOneMigrationPreservesHistoryAndAddsSafeDefaultsWithoutDestructiveFallback() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schemaFile = listOf(
            File("schemas/com.lifetrack.app.data.AppDatabase/1.json"),
            File("app/schemas/com.lifetrack.app.data.AppDatabase/1.json")
        ).firstOrNull(File::exists) ?: error("The version-1 Room schema is required for migration tests")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val today = LocalDate.now()
        val start = today.minusDays(10).toEpochDay()
        val archive = today.minusDays(1).toEpochDay()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO habits (id,name,description,icon,colorHex,startDate,isArchived,sortOrder,category,afterHabitId,archivedOn,createdAt,updatedAt) VALUES (7,'Read','A quiet chapter','📚','#43866B',?,1,0,'Mind',NULL,?,100,200)", arrayOf(start, archive))
            old.execSQL("INSERT INTO habit_logs (id,habitId,date,status,note,isFrozen,createdAt,updatedAt) VALUES (19,7,?,'COMPLETED','Keep this note 🌱',0,150,200)", arrayOf(archive))
            old.execSQL("INSERT INTO app_settings (id,theme,dynamicColor,backupReminder,lastExportAt) VALUES (1,'DARK',0,1,123)")
            old.execSQL("INSERT INTO freeze_usage (month,usedAt) VALUES ('2026-10',123)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(DatabaseMigrations.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val habit = migrated.dao().allHabits().single()
            assertEquals(7L, habit.id)
            assertEquals("Read", habit.name)
            assertEquals((1..7).toList(), habit.scheduledDays)
            assertEquals(1, habit.targetCount)
            assertEquals("times", habit.targetUnit)
            assertNull(habit.reminderMinutes)
            assertTrue(habit.scheduleHistory.isEmpty())
            val log = migrated.dao().allLogs().single()
            assertEquals(19L, log.id)
            assertEquals("Keep this note 🌱", log.note)
            assertEquals(1, log.progress)
            assertEquals(150L, log.createdAt)
            val settings = migrated.dao().getSettings()!!
            assertEquals("DARK", settings.theme)
            assertEquals(123L, settings.lastExportAt)
            assertTrue(settings.hapticsEnabled)
            assertTrue(settings.animationsEnabled)
            val pause = migrated.dao().allPauses().single()
            assertEquals(7L, pause.habitId)
            assertEquals(today, pause.startDate)
            assertNull(pause.endDate)
            assertEquals("2026-10", migrated.dao().allFreezes().single().month)
            assertEquals(2, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
