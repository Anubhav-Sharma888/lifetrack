package com.lifetrack.app.di

import android.content.Context
import androidx.room.Room
import com.lifetrack.app.data.AppDatabase
import com.lifetrack.app.data.DatabaseMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "lifetrack.db")
            .addMigrations(DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3, DatabaseMigrations.MIGRATION_3_4, DatabaseMigrations.MIGRATION_4_5, DatabaseMigrations.MIGRATION_5_6, DatabaseMigrations.MIGRATION_6_7, DatabaseMigrations.MIGRATION_7_8, DatabaseMigrations.MIGRATION_8_9, DatabaseMigrations.MIGRATION_9_10, DatabaseMigrations.MIGRATION_10_11, DatabaseMigrations.MIGRATION_11_12, DatabaseMigrations.MIGRATION_12_13, DatabaseMigrations.MIGRATION_13_14, DatabaseMigrations.MIGRATION_14_15).build()
}

/** Bridges the alarm ViewModel to the Android AlarmManager scheduler (Prompt 6). */
@Module
@InstallIn(SingletonComponent::class)
object AlarmSchedulerModule {
    @Provides
    @Singleton
    fun alarmSchedulerBridge(@ApplicationContext context: Context): com.lifetrack.app.ui.AlarmSchedulerBridge =
        object : com.lifetrack.app.ui.AlarmSchedulerBridge {
            override fun upsert(alarm: com.lifetrack.app.data.HabitAlarm) {
                com.lifetrack.app.reminder.HabitAlarmScheduler.upsert(context, alarm)
            }

            override fun cancel(alarmUuid: String) {
                com.lifetrack.app.reminder.HabitAlarmScheduler.cancel(context, alarmUuid)
            }
        }
}
