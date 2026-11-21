package com.lifetrack.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.time.LocalTime
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Builds a real exported schema from app/schemas by hand, then lets Room migrate and validate the
 * current schema. Room validates the migrated structure against the compiled schema when it opens,
 * which is the same guarantee `MigrationTestHelper.runMigrationsAndValidate` would give — no test
 * assets wiring for the schemas directory is needed this way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DatabaseMigrationTest {
    private val seedDate = LocalDate.of(2026, 10, 6)
    private val loggedDate = LocalDate.of(2026, 10, 16)

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
            .addMigrations(
                DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3,
                DatabaseMigrations.MIGRATION_3_4, DatabaseMigrations.MIGRATION_4_5,
                DatabaseMigrations.MIGRATION_5_6, DatabaseMigrations.MIGRATION_6_7,
                DatabaseMigrations.MIGRATION_7_8, DatabaseMigrations.MIGRATION_8_9,
                DatabaseMigrations.MIGRATION_9_10, DatabaseMigrations.MIGRATION_10_11, DatabaseMigrations.MIGRATION_11_12, DatabaseMigrations.MIGRATION_12_13, DatabaseMigrations.MIGRATION_13_14
            )
            .allowMainThreadQueries().build()
        try {
            val habit = migrated.dao().allHabits().single()
            assertEquals(7L, habit.id)
            assertEquals("Read", habit.name)
            assertEquals((1..7).toList(), habit.scheduledDays)
            assertEquals(1, habit.targetCount)
            assertEquals("times", habit.targetUnit)
            assertNull(habit.reminderMinutes)
            assertTrue(habit.scheduleHistory.isEmpty())
            assertTrue(habit.goalHistory.isEmpty())
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
            // MIGRATION_5_6 added the opt-in app lock; existing settings start with it off.
            assertFalse(settings.appLockEnabled)
            // MIGRATION_6_7 added Smart Streaks; every migrated habit keeps STRICT semantics.
            assertEquals("STRICT", migrated.dao().allHabits().single().streakPolicy)
            assertEquals(75, migrated.dao().allHabits().single().partialCreditPercent)
            assertTrue("Streak protections start empty after migration", migrated.dao().allStreakProtections().isEmpty())
            // MIGRATION_7_8 added daily reflections; the migrated table starts empty and accepts a row.
            assertTrue(migrated.dao().allReflections().isEmpty())
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO daily_reflections (date,mood,journal,createdAt,updatedAt) VALUES (?,3,NULL,100,200)", arrayOf(today.toEpochDay())
            )
            val reflection = migrated.dao().allReflections().single()
            assertEquals(3, reflection.mood)
            assertNull(reflection.journal)
            assertEquals(today, reflection.date)
            // MIGRATION_8_9 added achievement acknowledgments; the migrated table accepts a row.
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO achievement_acknowledgments (achievementId,acknowledgedAt) VALUES ('habit.first_completion',300)"
            )
            val acknowledgment = migrated.dao().allAchievementAcknowledgments().single()
            assertEquals("habit.first_completion", acknowledgment.achievementId)
            assertEquals(300, acknowledgment.acknowledgedAt)
            // MIGRATION_10_11 added the Goal Roadmap tables; they start empty and accept rows.
            assertTrue(migrated.goalDao().allGoals().isEmpty())
            migrated.goalDao().insertGoal(Goal(uuid = "11111111-1111-4111-8111-111111111111", title = "Learn ML", createdAt = 100, updatedAt = 200))
            val insertedGoal = migrated.goalDao().allGoals().single()
            assertEquals("Learn ML", insertedGoal.title)
            assertEquals("PLANNED", insertedGoal.status)
            migrated.goalDao().insertPhase(GoalPhase(uuid = "22222222-2222-4222-8222-222222222222", goalId = insertedGoal.id, name = "Foundations", createdAt = 100, updatedAt = 200))
            assertEquals(1, migrated.goalDao().allPhases().size)
            // MIGRATION_9_10 added flexible scheduling; every migrated habit keeps WEEKDAYS semantics.
            assertEquals("WEEKDAYS", migrated.dao().allHabits().single().scheduleMode)
            assertNull(migrated.dao().allHabits().single().intervalAnchor)
            val pause = migrated.dao().allPauses().single()
            assertEquals(7L, pause.habitId)
            assertEquals(today, pause.startDate)
            assertNull(pause.endDate)
            assertEquals("2026-10", migrated.dao().allFreezes().single().month)
            assertEquals(14, migrated.openHelper.readableDatabase.version)
            assertTrue("Version-3 activity tables start empty after migration from version 1", migrated.dao().allActivities().isEmpty())
            // MIGRATION_4_5 added the tracking columns; the migrated schema must apply their defaults.
            // (Opening schema 5 at all proves Room accepted the migrated structure — a mismatch would throw.)
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO activities (name,icon,unit,sortOrder,createdAt,updatedAt) VALUES ('Push-ups','💪','pushups',0,100,200)"
            )
            val activity = migrated.dao().allActivities().single()
            assertEquals("LOGGED", activity.trackingMode)
            assertNull(activity.startingValue)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    // ---- Every old install reaches the new release: each exported era walks the full chain ----

    @Test fun everyExportedSchemaVersionStillMigratesForwardToVersionFourteen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // Any supported install — not just the oldest one — must reach schema 14. Room validates
        // every end state against the compiled schema when it opens, so a broken middle leg,
        // a dropped column or a default mismatch anywhere on the chain fails here.
        for (startVersion in 2..13) {
            val name = "lifetrack-era-$startVersion-${System.nanoTime()}.db"
            buildDatabaseAt(context, name, startVersion) { db -> seedEraRows(db, startVersion) }
            val migrated = migrateForward(context, name)
            try {
                val habit = migrated.dao().allHabits().single()
                assertEquals(7L, habit.id)
                assertEquals("Read", habit.name)
                assertEquals((1..7).toList(), habit.scheduledDays)
                assertEquals(1, habit.targetCount)
                assertEquals("times", habit.targetUnit)
                // Every era lands on the same schema-11 semantics.
                assertEquals("STRICT", habit.streakPolicy)
                assertEquals(75, habit.partialCreditPercent)
                assertEquals("WEEKDAYS", habit.scheduleMode)
                assertNull(habit.intervalDays)
                assertNull(habit.intervalAnchor)
                assertNull(habit.weeklyTarget)
                val log = migrated.dao().allLogs().single()
                assertEquals(19L, log.id)
                assertEquals("Keep this note 🌱", log.note)
                assertEquals(loggedDate, log.date)
                assertEquals(150L, log.createdAt)
                val settings = migrated.dao().getSettings()!!
                assertEquals("DARK", settings.theme)
                assertEquals(123L, settings.lastExportAt)
                assertEquals("2026-10", migrated.dao().allFreezes().single().month)
                // MIGRATION_10_11 mints the Goal Roadmap tables empty for every era.
                assertTrue(migrated.goalDao().allGoals().isEmpty())
                assertTrue(migrated.goalDao().allPhases().isEmpty())
                assertTrue(migrated.goalDao().allMilestones().isEmpty())
                assertTrue(migrated.goalDao().allTasks().isEmpty())
                assertTrue(migrated.goalDao().allDependencies().isEmpty())
                // MIGRATION_11_12 mints the schedule-history table empty for every era.
                assertTrue(migrated.goalDao().allScheduleRevisions().isEmpty())
                // MIGRATION_12_13 mints the habit-link table empty for every era.
                assertTrue(migrated.goalDao().allGoalHabitLinks().isEmpty())
                assertEquals(14, migrated.openHelper.readableDatabase.version)
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }
    }

    // ---- Per-era legs: each feature's own migration, seeded with rows of that era ----

    @Test fun versionSixRowsGainStrictSmartStreakDefaultsWhenMigratingForward() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-six-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 6) { db -> seedEraRows(db, 6) }
        val migrated = migrateForward(context, name)
        try {
            val habit = migrated.dao().allHabits().single()
            assertEquals(7L, habit.id)
            assertEquals("Read", habit.name)
            // MIGRATION_6_7 supplies both Smart Streak defaults for rows written before version 7;
            // the habit itself is untouched — no destructive fallback rewrote it.
            assertEquals("STRICT", habit.streakPolicy)
            assertEquals(75, habit.partialCreditPercent)
            assertTrue("Streak protections start empty after migration", migrated.dao().allStreakProtections().isEmpty())
            assertEquals(19L, migrated.dao().allLogs().single().id)
            assertEquals(14, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSevenMigrationCreatesTheDailyReflectionTable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-seven-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 7) { db -> seedEraRows(db, 7) }
        val migrated = migrateForward(context, name)
        try {
            assertEquals(7L, migrated.dao().allHabits().single().id)
            // The reflection table is minted by MIGRATION_7_8 itself: it starts empty and accepts a row.
            assertTrue(migrated.dao().allReflections().isEmpty())
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO daily_reflections (date,mood,journal,createdAt,updatedAt) VALUES (?,2,'Steady day',100,200)",
                arrayOf(loggedDate.toEpochDay())
            )
            val reflection = migrated.dao().allReflections().single()
            assertEquals(loggedDate, reflection.date)
            assertEquals(2, reflection.mood)
            assertEquals("Steady day", reflection.journal)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionEightMigrationCreatesTheAchievementAcknowledgmentTable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-eight-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 8) { db -> seedEraRows(db, 8) }
        val migrated = migrateForward(context, name)
        try {
            assertEquals(7L, migrated.dao().allHabits().single().id)
            // The acknowledgment table is minted by MIGRATION_8_9 itself: it starts empty and accepts a row.
            assertTrue(migrated.dao().allAchievementAcknowledgments().isEmpty())
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO achievement_acknowledgments (achievementId,acknowledgedAt) VALUES ('habit.first_completion',300)"
            )
            val acknowledgment = migrated.dao().allAchievementAcknowledgments().single()
            assertEquals("habit.first_completion", acknowledgment.achievementId)
            assertEquals(300, acknowledgment.acknowledgedAt)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionNineMigrationKeepsScheduleAndGoalHistoryWithWeekdayDefaults() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-nine-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 9) { db ->
            db.execSQL(
                "INSERT INTO habits (id,name,description,icon,colorHex,startDate,isArchived,sortOrder,category,afterHabitId,archivedOn,createdAt,updatedAt,scheduleHistory,goalHistory) " +
                    "VALUES (7,'Read','A quiet chapter','📚','#43866B',?,0,0,'Mind',NULL,NULL,100,200,?,?)",
                arrayOf(
                    seedDate.toEpochDay(),
                    """[{"effectiveFrom":"2026-10-01","days":[1,2,3,4,5,6,7]}]""",
                    """[{"effectiveFrom":"2026-10-01","targetCount":2,"targetUnit":"sessions"}]"""
                )
            )
        }
        val migrated = migrateForward(context, name)
        try {
            val habit = migrated.dao().allHabits().single()
            assertEquals(7L, habit.id)
            // History written under schema 9 survives the flexible-scheduling leg unchanged.
            assertEquals(
                listOf(ScheduleRevision(LocalDate.of(2026, 10, 1), (1..7).toList())),
                habit.scheduleHistory
            )
            assertEquals(
                listOf(GoalRevision(LocalDate.of(2026, 10, 1), 2, "sessions")),
                habit.goalHistory
            )
            // MIGRATION_9_10 keeps weekday semantics for every pre-10 row — no reinterpretation.
            // (Opening at all proves Room accepted the migrated structure — a mismatch would throw,
            // and the builder registers no destructive fallback.)
            assertEquals("WEEKDAYS", habit.scheduleMode)
            assertNull(habit.intervalDays)
            assertNull(habit.intervalAnchor)
            assertNull(habit.weeklyTarget)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    // ---- Foreign keys: declarations travel with the migrations and Room enforces them at runtime ----

    @Test fun migratedSchemasDeclareTheirForeignKeys() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-fk-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 1) { db -> seedEraRows(db, 1) }
        val migrated = migrateForward(context, name)
        try {
            assertEquals(7L, migrated.dao().allHabits().single().id)
            val db = migrated.openHelper.readableDatabase
            // Every declared cascade Room validates at open time, asserted against the migrated
            // DDL text (sqlite_master) — the declaration itself, per table. Room wraps identifiers
            // in backticks, so the text is normalized before matching.
            fun ddlOf(table: String): String = createTableSql(db, table).replace("`", "")
            val habitLogs = ddlOf("habit_logs")
            val habitPauses = ddlOf("habit_pauses")
            val reminderDeliveries = ddlOf("reminder_deliveries")
            val streakProtections = ddlOf("streak_protections")
            val activityEntries = ddlOf("activity_entries")
            val habits = ddlOf("habits")
            // Room emits ON UPDATE NO ACTION for schemas without an update action, so the
            // reference and the delete action are asserted as separate substrings.
            assertTrue("habit_logs must reference habits on delete CASCADE",
                habitLogs.contains("FOREIGN KEY(habitId) REFERENCES habits(id)") && habitLogs.contains("ON DELETE CASCADE"))
            assertTrue("habit_pauses must reference habits on delete CASCADE",
                habitPauses.contains("FOREIGN KEY(habitId) REFERENCES habits(id)") && habitPauses.contains("ON DELETE CASCADE"))
            assertTrue("reminder_deliveries must reference habits on delete CASCADE",
                reminderDeliveries.contains("FOREIGN KEY(habitId) REFERENCES habits(id)") && reminderDeliveries.contains("ON DELETE CASCADE"))
            assertTrue("streak_protections must reference habits on delete CASCADE",
                streakProtections.contains("FOREIGN KEY(habitId) REFERENCES habits(id)") && streakProtections.contains("ON DELETE CASCADE"))
            assertTrue("activity_entries must reference activities on delete CASCADE",
                activityEntries.contains("FOREIGN KEY(activityId) REFERENCES activities(id)") && activityEntries.contains("ON DELETE CASCADE"))
            assertTrue("habits must self-reference stacking with SET NULL",
                habits.contains("FOREIGN KEY(afterHabitId) REFERENCES habits(id)") && habits.contains("ON DELETE SET NULL"))
            // Date-scoped (reflections) and id-scoped (acknowledgments) rows carry no habit foreign
            // key by design, so habit cascades never touch them and import/reset must clear both
            // explicitly.
            listOf("daily_reflections", "achievement_acknowledgments").forEach { table ->
                val ddl = createTableSql(db, table)
                assertTrue("$table must exist", ddl.isNotBlank())
                assertFalse("$table must declare no habit foreign key (found: $ddl)", ddl.contains("FOREIGN KEY"))
            }
            // MIGRATION_10_11 declared the goal graph: goal-owned children cascade, optional
            // phase/milestone attachments SET NULL, and dependency edges cascade with their tasks.
            val goalPhases = ddlOf("goal_phases")
            assertTrue("goal_phases must reference goals on delete CASCADE",
                goalPhases.contains("FOREIGN KEY(goalId) REFERENCES goals(id)") && goalPhases.contains("ON DELETE CASCADE"))
            val goalMilestones = ddlOf("goal_milestones")
            assertTrue("goal_milestones must reference goals on delete CASCADE",
                goalMilestones.contains("FOREIGN KEY(goalId) REFERENCES goals(id)") && goalMilestones.contains("ON DELETE CASCADE"))
            assertTrue("goal_milestones must SET NULL on phase deletion",
                goalMilestones.contains("FOREIGN KEY(phaseId) REFERENCES goal_phases(id)") && goalMilestones.contains("ON DELETE SET NULL"))
            val goalTasks = ddlOf("goal_tasks")
            assertTrue("goal_tasks must reference goals on delete CASCADE",
                goalTasks.contains("FOREIGN KEY(goalId) REFERENCES goals(id)") && goalTasks.contains("ON DELETE CASCADE"))
            assertTrue("goal_tasks must SET NULL on phase deletion",
                goalTasks.contains("FOREIGN KEY(phaseId) REFERENCES goal_phases(id)") && goalTasks.contains("ON DELETE SET NULL"))
            assertTrue("goal_tasks must SET NULL on milestone deletion",
                goalTasks.contains("FOREIGN KEY(milestoneId) REFERENCES goal_milestones(id)") && goalTasks.contains("ON DELETE SET NULL"))
            val goalTaskDependencies = ddlOf("goal_task_dependencies")
            assertTrue("goal_task_dependencies must reference tasks on delete CASCADE in both directions",
                goalTaskDependencies.contains("FOREIGN KEY(dependentTaskId) REFERENCES goal_tasks(id)") &&
                    goalTaskDependencies.contains("FOREIGN KEY(prerequisiteTaskId) REFERENCES goal_tasks(id)") &&
                    goalTaskDependencies.contains("ON DELETE CASCADE"))
            // Schedule history is uuid-bound audit data: no foreign key by design (its record
            // rows may be deleted independently), pinned here like the reflections/acks tables.
            val scheduleRevisions = ddlOf("goal_schedule_revisions")
            assertTrue("goal_schedule_revisions must exist", scheduleRevisions.isNotBlank())
            assertFalse("goal_schedule_revisions must declare no foreign key (found: $scheduleRevisions)",
                scheduleRevisions.contains("FOREIGN KEY"))
            val habitLinks = ddlOf("goal_habit_links")
            assertTrue("goal_habit_links must exist", habitLinks.isNotBlank())
            assertTrue("goal_habit_links must reference goals on delete CASCADE",
                habitLinks.contains("FOREIGN KEY(goalId) REFERENCES goals(id)") && habitLinks.contains("ON DELETE CASCADE"))
            assertTrue("goal_habit_links must reference habits on delete CASCADE",
                habitLinks.contains("FOREIGN KEY(habitId) REFERENCES habits(id)") && habitLinks.contains("ON DELETE CASCADE"))
            listOf("goals", "goal_phases", "goal_milestones", "goal_tasks").forEach { table ->
                val ddl = createTableSql(db, table)
                assertTrue("$table must exist", ddl.isNotBlank())
                assertTrue("$table must carry its uuid column", ddl.contains("uuid"))
            }
            val goalIndexes = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_goal%'").use { cursor ->
                while (cursor.moveToNext()) goalIndexes.add(cursor.getString(0))
            }
            listOf(
                "index_goals_uuid", "index_goal_phases_uuid", "index_goal_phases_goalId",
                "index_goal_milestones_uuid", "index_goal_milestones_goalId", "index_goal_milestones_phaseId",
                "index_goal_tasks_uuid", "index_goal_tasks_goalId", "index_goal_tasks_phaseId",
                "index_goal_tasks_milestoneId", "index_goal_task_dependencies_prerequisiteTaskId",
                // MIGRATION_11_12 / 12_13 index surfaces, pinned with the rest of the goal schema.
                "index_goal_schedule_revisions_entityUuid", "index_goal_schedule_revisions_entityUuid_field_changedAt",
                "index_goal_habit_links_habitId"
            ).forEach { index ->
                assertTrue("index $index must exist after migration", goalIndexes.contains(index))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionTenGainsTheGoalRoadmapTablesAndTheyStartEmptyAndUsable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-ten-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 10) { db -> seedEraRows(db, 10) }
        val migrated = migrateForward(context, name)
        try {
            assertEquals(7L, migrated.dao().allHabits().single().id)
            // The five goal tables are minted by MIGRATION_10_11 itself: empty, then fully usable.
            assertTrue(migrated.goalDao().allGoals().isEmpty())
            val goal = Goal(uuid = "11111111-1111-4111-8111-111111111111", title = "Learn ML", createdAt = 100, updatedAt = 200)
            val savedGoal = migrated.goalDao().insertGoal(goal)
            val phase = migrated.goalDao().insertPhase(GoalPhase(uuid = "22222222-2222-4222-8222-222222222222", goalId = savedGoal, name = "Foundations", createdAt = 100, updatedAt = 200))
            val milestone = migrated.goalDao().insertMilestone(GoalMilestone(uuid = "33333333-3333-4333-8333-333333333333", goalId = savedGoal, phaseId = phase, title = "Basics down", createdAt = 100, updatedAt = 200))
            val prerequisite = migrated.goalDao().insertTask(GoalTask(uuid = "44444444-4444-4444-8444-444444444444", goalId = savedGoal, phaseId = phase, milestoneId = milestone, title = "Read chapter", createdAt = 100, updatedAt = 200))
            val dependent = migrated.goalDao().insertTask(GoalTask(uuid = "55555555-5555-4555-8555-555555555555", goalId = savedGoal, phaseId = phase, title = "Solve exercises", sortOrder = 1, createdAt = 100, updatedAt = 200))
            migrated.goalDao().insertDependency(GoalTaskDependency(dependent, prerequisite))
            assertEquals(1, migrated.goalDao().allGoals().size)
            assertEquals(2, migrated.goalDao().allTasks().size)
            assertEquals(1, migrated.goalDao().allDependencies().size)
            // Deleting the goal clears the tree explicitly (the repository does this) — habits untouched.
            migrated.goalDao().deleteDependenciesForGoal(savedGoal)
            migrated.goalDao().deleteTasksForGoal(savedGoal)
            migrated.goalDao().deleteMilestonesForGoal(savedGoal)
            migrated.goalDao().deletePhasesForGoal(savedGoal)
            migrated.goalDao().deleteGoalById(savedGoal)
            assertTrue(migrated.goalDao().allGoals().isEmpty())
            assertTrue(migrated.goalDao().allTasks().isEmpty())
            assertEquals(7L, migrated.dao().allHabits().single().id)
            assertEquals(14, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionElevenGainsTheScheduleHistoryTableAndItStartsEmptyAndUsable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-eleven-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 11) { db -> seedEraRows(db, 11) }
        val migrated = migrateForward(context, name)
        try {
            assertEquals(7L, migrated.dao().allHabits().single().id)
            // The schedule-history table is minted by MIGRATION_11_12 itself: empty, then usable.
            assertTrue(migrated.goalDao().allScheduleRevisions().isEmpty())
            val goal = migrated.goalDao().insertGoal(Goal(uuid = "11111111-1111-4111-8111-111111111111", title = "Learn ML", createdAt = 100, updatedAt = 200))
            migrated.goalDao().insertScheduleRevisions(listOf(GoalScheduleRevision(
                entityType = "TASK", entityUuid = "aaaaaaaa-bbbb-4ccc-0001-000000000000", field = "plannedDate",
                formerDate = seedDate, newDate = seedDate.plusDays(3), scope = "SINGLE", changedAt = 300)))
            val revision = migrated.goalDao().allScheduleRevisions().single()
            assertEquals("TASK", revision.entityType)
            assertEquals(seedDate, revision.formerDate)
            // Deleting the owning entity clears its history explicitly (the repository does this).
            migrated.goalDao().deleteScheduleRevisionsForEntity("aaaaaaaa-bbbb-4ccc-0001-000000000000")
            assertTrue(migrated.goalDao().allScheduleRevisions().isEmpty())
            assertEquals(7L, migrated.dao().allHabits().single().id)
            assertEquals(14, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionTwelveGainsTheHabitLinkTableAndItStartsEmptyAndUsable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-twelve-" + System.nanoTime() + ".db"
        buildDatabaseAt(context, name, 12) { db -> seedEraRows(db, 12) }
        val migrated = migrateForward(context, name)
        try {
            assertEquals(7L, migrated.dao().allHabits().single().id)
            // The link table is minted by MIGRATION_12_13 itself: empty, then usable.
            assertTrue(migrated.goalDao().allGoalHabitLinks().isEmpty())
            val goal = migrated.goalDao().insertGoal(Goal(uuid = "11111111-1111-4111-8111-111111111111", title = "Learn ML", createdAt = 100, updatedAt = 200))
            migrated.goalDao().insertGoalHabitLink(GoalHabitLink(goalId = goal, habitId = 7, createdAt = 300))
            val link = migrated.goalDao().allGoalHabitLinks().single()
            assertEquals(goal, link.goalId)
            assertEquals(7L, link.habitId)
            // Duplicate composite keys are rejected by the table itself.
            try {
                migrated.goalDao().insertGoalHabitLink(GoalHabitLink(goalId = goal, habitId = 7, createdAt = 400))
                throw AssertionError("Duplicate link must be rejected")
            } catch (expected: Exception) { /* SQLite constraint */ }
            // Deleting the goal removes the link; the habit row survives.
            migrated.goalDao().deleteGoalById(goal)
            assertTrue(migrated.goalDao().allGoalHabitLinks().isEmpty())
            assertEquals(7L, migrated.dao().allHabits().single().id)
            assertEquals(14, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
    // ---- Helpers: build a real exported schema of one era, then migrate it with Room ----

    @Test fun `version thirteen gains the session table and it starts empty and usable`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "lifetrack-era-thirteen-${System.nanoTime()}.db"
        buildDatabaseAt(context, name, 13) { db -> seedEraRows(db, 13) }
        val migrated = migrateForward(context, name)
        try {
            // The session table is minted by MIGRATION_13_14 itself: empty, then usable, with the
            // SET NULL detach semantics and unique uuid enforced by the migrated DDL.
            assertTrue(migrated.sessionDao().allSessions().isEmpty())
            val goal = migrated.goalDao().insertGoal(Goal(uuid = "31111111-1111-4111-8111-111111111111", title = "Learn ML", createdAt = 100, updatedAt = 200))
            migrated.sessionDao().insertSession(GoalSession(
                uuid = "f2000000-0000-4000-8000-000000000001", title = "Study",
                plannedStartDate = LocalDate.now(), plannedStartTime = LocalTime.of(17, 0),
                plannedEndDate = LocalDate.now(), plannedEndTime = LocalTime.of(18, 0),
                timezoneId = "UTC", goalId = goal, createdAt = 100, updatedAt = 200))
            val session = migrated.sessionDao().allSessions().single()
            assertEquals(goal, session.goalId)
            // Duplicate uuid is rejected by the unique index.
            try {
                migrated.sessionDao().insertSession(GoalSession(
                    uuid = "f2000000-0000-4000-8000-000000000001", title = "Duplicate",
                    plannedStartDate = LocalDate.now(), plannedStartTime = LocalTime.of(17, 0),
                    plannedEndDate = LocalDate.now(), plannedEndTime = LocalTime.of(18, 0),
                    timezoneId = "UTC", createdAt = 100, updatedAt = 200))
                fail("A duplicate session uuid must be rejected.")
            } catch (error: Exception) { /* SQLite constraint violation — expected. */ }
            // Goal deletion detaches the session (SET NULL) without removing it.
            migrated.goalDao().deleteGoalById(goal)
            val detached = migrated.sessionDao().allSessions().single()
            assertNull(detached.goalId)
            assertEquals(14, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private val allMigrations = arrayOf<Migration>(
        DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4, DatabaseMigrations.MIGRATION_4_5,
        DatabaseMigrations.MIGRATION_5_6, DatabaseMigrations.MIGRATION_6_7,
        DatabaseMigrations.MIGRATION_7_8, DatabaseMigrations.MIGRATION_8_9,
        DatabaseMigrations.MIGRATION_9_10, DatabaseMigrations.MIGRATION_10_11,
        DatabaseMigrations.MIGRATION_11_12,
        DatabaseMigrations.MIGRATION_12_13, DatabaseMigrations.MIGRATION_13_14
    )

    private fun schemaFile(version: Int): File = listOf(
        File("schemas/com.lifetrack.app.data.AppDatabase/$version.json"),
        File("app/schemas/com.lifetrack.app.data.AppDatabase/$version.json")
    ).firstOrNull(File::exists) ?: error("The version-$version Room schema is required for migration tests")

    private fun buildDatabaseAt(context: Context, name: String, version: Int, seed: (SQLiteDatabase) -> Unit) {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            seed(db)
            db.version = version
        }
    }

    private fun migrateForward(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*allMigrations)
            .allowMainThreadQueries().build()

    /** One habit, log, settings and freeze row, seeded with only the columns the era's schema
     *  declares: scheduleHistory arrives with v2, goalHistory with v4, and every later column
     *  carries an SQL default. */
    private fun seedEraRows(db: SQLiteDatabase, version: Int) {
        val historyColumns = if (version >= 4) ",scheduleHistory,goalHistory" else if (version >= 2) ",scheduleHistory" else ""
        val historyValues = if (version >= 4) ",'[]','[]'" else if (version >= 2) ",'[]'" else ""
        db.execSQL(
            "INSERT INTO habits (id,name,description,icon,colorHex,startDate,isArchived,sortOrder,category,afterHabitId,archivedOn,createdAt,updatedAt$historyColumns) " +
                "VALUES (7,'Read','A quiet chapter','📚','#43866B',?,0,0,'Mind',NULL,NULL,100,200$historyValues)",
            arrayOf(seedDate.toEpochDay())
        )
        db.execSQL(
            "INSERT INTO habit_logs (id,habitId,date,status,note,isFrozen,createdAt,updatedAt) VALUES (19,7,?,'COMPLETED','Keep this note 🌱',0,150,200)",
            arrayOf(loggedDate.toEpochDay())
        )
        db.execSQL("INSERT INTO app_settings (id,theme,dynamicColor,backupReminder,lastExportAt) VALUES (1,'DARK',0,1,123)")
        db.execSQL("INSERT INTO freeze_usage (month,usedAt) VALUES ('2026-10',123)")
    }

    /** Reads a table's CREATE statement from sqlite_master — the DDL is the FK declaration Room
     *  validates at open time. (SupportSQLiteDatabase.query returns empty cursors for PRAGMAs
     *  under this Room version, so the DDL text is the reliable declaration source.) */
    private fun createTableSql(db: SupportSQLiteDatabase, table: String): String =
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }
}
