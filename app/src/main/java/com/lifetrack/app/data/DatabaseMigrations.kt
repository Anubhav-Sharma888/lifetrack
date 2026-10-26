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
}
