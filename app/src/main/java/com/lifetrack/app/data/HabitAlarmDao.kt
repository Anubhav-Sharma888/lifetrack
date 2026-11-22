package com.lifetrack.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Alarm-definition reads and writes. The scheduler reconciles from the canonical rows; the
 *  ringing service revalidates its occurrence against them at delivery time. */
@Dao
interface HabitAlarmDao {
    @Query("SELECT * FROM habit_alarms ORDER BY habitId, timeMinutes") fun observeAlarms(): Flow<List<HabitAlarm>>
    @Query("SELECT * FROM habit_alarms WHERE habitId = :habitId ORDER BY timeMinutes") fun observeAlarmsForHabit(habitId: Long): Flow<List<HabitAlarm>>
    @Query("SELECT * FROM habit_alarms WHERE habitId = :habitId ORDER BY timeMinutes") suspend fun alarmsForHabitSnapshot(habitId: Long): List<HabitAlarm>
    @Query("SELECT * FROM habit_alarms WHERE uuid = :uuid") suspend fun alarmByUuid(uuid: String): HabitAlarm?
    @Query("SELECT * FROM habit_alarms WHERE id = :id") suspend fun alarm(id: Long): HabitAlarm?
    @Query("SELECT * FROM habit_alarms ORDER BY habitId, timeMinutes") suspend fun allAlarms(): List<HabitAlarm>
    @Query("SELECT EXISTS(SELECT 1 FROM habit_alarms WHERE habitId = :habitId)") suspend fun habitHasAlarms(habitId: Long): Boolean
    @Query("SELECT habitId, COUNT(*) > 0 AS has FROM habit_alarms GROUP BY habitId") suspend fun habitsWithAlarms(): List<AlarmHabitRef>
    @Query("SELECT COUNT(*) FROM habit_alarms") suspend fun alarmCount(): Int
    @Insert suspend fun insertAlarm(alarm: HabitAlarm): Long
    @Insert suspend fun insertAlarms(alarms: List<HabitAlarm>)
    @Update suspend fun updateAlarm(alarm: HabitAlarm)
    @Query("DELETE FROM habit_alarms WHERE id = :id") suspend fun deleteAlarmById(id: Long)
    @Query("DELETE FROM habit_alarms WHERE habitId = :habitId") suspend fun deleteAlarmsForHabit(habitId: Long)
    @Query("DELETE FROM habit_alarms") suspend fun deleteAlarms()
}

/** Lightweight projection for the reminder worker's alarm-mode skip check. */
data class AlarmHabitRef(val habitId: Long, val has: Boolean)
