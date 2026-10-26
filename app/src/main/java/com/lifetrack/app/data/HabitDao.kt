package com.lifetrack.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder, id") fun observeHabits(): Flow<List<Habit>>
    @Query("SELECT * FROM habit_logs ORDER BY date, id") fun observeLogs(): Flow<List<HabitLog>>
    @Query("SELECT * FROM freeze_usage ORDER BY month") fun observeFreezes(): Flow<List<FreezeUsage>>
    @Query("SELECT * FROM app_settings WHERE id = 1") fun observeSettings(): Flow<AppSettings?>
    @Query("SELECT * FROM habit_pauses ORDER BY startDate, id") fun observePauses(): Flow<List<HabitPause>>

    @Query("SELECT * FROM habits ORDER BY sortOrder, id") suspend fun allHabits(): List<Habit>
    @Query("SELECT * FROM habit_logs ORDER BY date, id") suspend fun allLogs(): List<HabitLog>
    @Query("SELECT * FROM habit_logs WHERE date = :date ORDER BY habitId, id")
    suspend fun logsOnDate(date: LocalDate): List<HabitLog>
    @Query("SELECT * FROM freeze_usage ORDER BY month") suspend fun allFreezes(): List<FreezeUsage>
    @Query("SELECT * FROM app_settings WHERE id = 1") suspend fun getSettings(): AppSettings?
    @Query("SELECT * FROM habit_pauses ORDER BY startDate, id") suspend fun allPauses(): List<HabitPause>
    @Query("SELECT * FROM habit_pauses WHERE habitId = :habitId ORDER BY startDate, id") suspend fun habitPauses(habitId: Long): List<HabitPause>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun habit(id: Long): Habit?
    @Query("SELECT * FROM habit_logs WHERE habitId = :habitId AND date = :date")
    suspend fun log(habitId: Long, date: LocalDate): HabitLog?
    @Query("SELECT * FROM habit_logs WHERE habitId = :habitId ORDER BY date")
    suspend fun habitLogs(habitId: Long): List<HabitLog>
    @Query("SELECT * FROM freeze_usage WHERE month = :month") suspend fun freeze(month: String): FreezeUsage?

    @Insert suspend fun insertHabit(habit: Habit): Long
    @Insert suspend fun insertHabits(habits: List<Habit>)
    @Update suspend fun updateHabit(habit: Habit)
    @Insert suspend fun insertLog(log: HabitLog): Long
    @Insert suspend fun insertLogs(logs: List<HabitLog>)
    @Update suspend fun updateLog(log: HabitLog)
    @Insert suspend fun insertFreeze(freeze: FreezeUsage)
    @Insert suspend fun insertFreezes(freezes: List<FreezeUsage>)
    @Insert suspend fun insertPause(pause: HabitPause): Long
    @Insert suspend fun insertPauses(pauses: List<HabitPause>)
    @Update suspend fun updatePause(pause: HabitPause)
    @Query("DELETE FROM habit_pauses WHERE id = :id") suspend fun deletePause(id: Long)
    @Query("DELETE FROM habit_pauses") suspend fun deletePauses()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReminderDelivery(delivery: ReminderDelivery): Long
    @Query("DELETE FROM reminder_deliveries") suspend fun deleteReminderDeliveries()
    @Query("DELETE FROM reminder_deliveries WHERE habitId = :habitId AND date = :date")
    suspend fun deleteReminderDelivery(habitId: Long, date: LocalDate)
    @Upsert suspend fun saveSettings(settings: AppSettings)
    @Query("UPDATE app_settings SET lastExportAt = :timestamp WHERE id = 1")
    suspend fun markExported(timestamp: Long)
    @Query("UPDATE habits SET sortOrder = :position, updatedAt = :timestamp WHERE id = :id")
    suspend fun setSortOrder(id: Long, position: Int, timestamp: Long)
    @Query("DELETE FROM habit_logs WHERE habitId = :habitId AND date = :date")
    suspend fun deleteLog(habitId: Long, date: LocalDate)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteHabit(id: Long)
    @Query("DELETE FROM habit_logs") suspend fun deleteLogs()
    @Query("DELETE FROM habits") suspend fun deleteHabits()
    @Query("DELETE FROM freeze_usage") suspend fun deleteFreezes()
    @Query("DELETE FROM app_settings") suspend fun deleteSettings()
}
