package com.lifetrack.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Habit::class, HabitLog::class, FreezeUsage::class, AppSettings::class, HabitPause::class, ReminderDelivery::class, WorkoutActivity::class, ActivityEntry::class, StreakProtection::class, DailyReflection::class, AchievementAcknowledgment::class, Goal::class, GoalPhase::class, GoalMilestone::class, GoalTask::class, GoalTaskDependency::class, GoalScheduleRevision::class],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): HabitDao
    abstract fun goalDao(): GoalDao
}
