package com.lifetrack.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Habit::class, HabitLog::class, FreezeUsage::class, AppSettings::class, HabitPause::class, ReminderDelivery::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): HabitDao
}
