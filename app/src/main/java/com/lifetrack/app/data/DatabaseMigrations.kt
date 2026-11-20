package com.lifetrack.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing records remain intact; old daily completions become one-of-one numeric goals. */
object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN scheduledDays TEXT NOT NULL DEFAULT '1,2,3,4,5,6,7'")
            db.execSQL("ALTER TABLE habits ADD COLUMN targetCount INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE habits ADD COLUMN targetUnit TEXT NOT NULL DEFAULT 'times'")
            db.execSQL("ALTER TABLE habits ADD COLUMN reminderMinutes INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE habits ADD COLUMN scheduleHistory TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE habit_logs ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE habit_logs SET progress = 1 WHERE status = 'COMPLETED'")
            db.execSQL("ALTER TABLE app_settings ADD COLUMN hapticsEnabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE app_settings ADD COLUMN animationsEnabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE TABLE IF NOT EXISTS habit_pauses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, habitId INTEGER NOT NULL, startDate INTEGER NOT NULL, endDate INTEGER, FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_pauses_habitId ON habit_pauses(habitId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_pauses_habitId_startDate ON habit_pauses(habitId, startDate)")
            db.execSQL("INSERT INTO habit_pauses (habitId, startDate, endDate) SELECT id, archivedOn + 1, NULL FROM habits WHERE isArchived = 1 AND archivedOn IS NOT NULL")
            db.execSQL("CREATE TABLE IF NOT EXISTS reminder_deliveries (habitId INTEGER NOT NULL, date INTEGER NOT NULL, deliveredAt INTEGER NOT NULL, PRIMARY KEY(habitId, date), FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    }

    /** Activities arrive in version 3; existing habits, logs and settings are untouched. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS activities (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, icon TEXT NOT NULL, unit TEXT NOT NULL, target REAL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS activity_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, activityId INTEGER NOT NULL, date INTEGER NOT NULL, quantity REAL NOT NULL, note TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(activityId) REFERENCES activities(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_entries_activityId ON activity_entries(activityId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_activity_entries_activityId_date ON activity_entries(activityId, date)")
        }
    }
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN goalHistory TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /** Directional tracking arrives in version 5; existing activities keep their plain LOGGED semantics. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE activities ADD COLUMN trackingMode TEXT NOT NULL DEFAULT 'LOGGED'")
            db.execSQL("ALTER TABLE activities ADD COLUMN startingValue REAL DEFAULT NULL")
        }
    }

    /** The opt-in app lock arrives in version 6 as a local-only preference; existing settings are untouched. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE app_settings ADD COLUMN appLockEnabled INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Smart Streaks arrives in version 7: per-habit policy columns plus explicit per-day decisions.
     *  Every existing habit keeps STRICT semantics — no historical interpretation changes. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN streakPolicy TEXT NOT NULL DEFAULT 'STRICT'")
            db.execSQL("ALTER TABLE habits ADD COLUMN partialCreditPercent INTEGER NOT NULL DEFAULT 75")
            db.execSQL("CREATE TABLE IF NOT EXISTS streak_protections (habitId INTEGER NOT NULL, date INTEGER NOT NULL, protectChain INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(habitId, date), FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_streak_protections_habitId ON streak_protections(habitId)")
        }
    }

    /** Daily reflections arrive in version 8: one optional mood and/or journal per local date.
     *  Date-scoped with no habit FK, so resets and imports must clear it explicitly. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS daily_reflections (date INTEGER NOT NULL, mood INTEGER, journal TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(date))")
        }
    }

    /** Achievement acknowledgments arrive in version 9: durable seen-markers that persist
     *  independently of the derived achievement state and of habit deletion. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS achievement_acknowledgments (achievementId TEXT NOT NULL, acknowledgedAt INTEGER NOT NULL, PRIMARY KEY(achievementId))")
        }
    }

    /** Flexible scheduling arrives in version 10: every existing habit keeps WEEKDAYS semantics
     *  with no anchor — no historical reinterpretation. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN scheduleMode TEXT NOT NULL DEFAULT 'WEEKDAYS'")
            db.execSQL("ALTER TABLE habits ADD COLUMN intervalDays INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE habits ADD COLUMN intervalAnchor INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE habits ADD COLUMN weeklyTarget INTEGER DEFAULT NULL")
        }
    }

    /** Goal Roadmaps arrive in version 11 as five new tables: goals, phases, milestones, tasks and
     *  task-dependency edges. Purely additive — no existing table is touched, and the new tables
     *  start empty. The declared foreign keys are enforced by Room at runtime; the repository
     *  still deletes children explicitly for deterministic ordering, and the declarations are
     *  the schema contract Room validates at open time. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `category` TEXT NOT NULL, `priority` TEXT NOT NULL, `plannedStartDate` INTEGER, `targetDate` INTEGER, `status` TEXT NOT NULL, `isArchived` INTEGER NOT NULL, `archivedOn` INTEGER, `completedAt` INTEGER, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_goals_uuid` ON `goals` (`uuid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `goal_phases` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `goalId` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `sortOrder` INTEGER NOT NULL, `plannedStartDate` INTEGER, `plannedEndDate` INTEGER, `status` TEXT NOT NULL, `completedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_goal_phases_uuid` ON `goal_phases` (`uuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_phases_goalId` ON `goal_phases` (`goalId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `goal_milestones` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `goalId` INTEGER NOT NULL, `phaseId` INTEGER, `title` TEXT NOT NULL, `description` TEXT, `targetDate` INTEGER, `status` TEXT NOT NULL, `achievedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`phaseId`) REFERENCES `goal_phases`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_goal_milestones_uuid` ON `goal_milestones` (`uuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_milestones_goalId` ON `goal_milestones` (`goalId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_milestones_phaseId` ON `goal_milestones` (`phaseId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `goal_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `goalId` INTEGER NOT NULL, `phaseId` INTEGER, `milestoneId` INTEGER, `title` TEXT NOT NULL, `description` TEXT, `sortOrder` INTEGER NOT NULL, `plannedDate` INTEGER, `deadline` INTEGER, `estimatedMinutes` INTEGER, `status` TEXT NOT NULL, `completedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`phaseId`) REFERENCES `goal_phases`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`milestoneId`) REFERENCES `goal_milestones`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_goal_tasks_uuid` ON `goal_tasks` (`uuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_tasks_goalId` ON `goal_tasks` (`goalId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_tasks_phaseId` ON `goal_tasks` (`phaseId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_tasks_milestoneId` ON `goal_tasks` (`milestoneId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `goal_task_dependencies` (`dependentTaskId` INTEGER NOT NULL, `prerequisiteTaskId` INTEGER NOT NULL, PRIMARY KEY(`dependentTaskId`, `prerequisiteTaskId`), FOREIGN KEY(`dependentTaskId`) REFERENCES `goal_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`prerequisiteTaskId`) REFERENCES `goal_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_task_dependencies_prerequisiteTaskId` ON `goal_task_dependencies` (`prerequisiteTaskId`)")
        }
    }
}
