package com.lifetrack.app.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Alarm-definition repository (Prompt 6, Part B): the single writer for `habit_alarms` rows.
 * Reminder mode is data-derived — a habit with alarm rows rings; one without keeps its legacy
 * `reminderMinutes` notification untouched. Alarm scheduling itself is a WorkManager/AlarmManager
 * concern owned by [com.lifetrack.app.reminder.HabitAlarmScheduler]; the repository stays
 * framework-free so the recurrence engine and validators stay testable.
 */
@Singleton
class HabitAlarmRepository @Inject constructor(private val db: AppDatabase) {
    private val alarmDao = db.alarmDao()
    private val habitDao = db.dao()

    fun alarmsForHabit(habitId: Long): Flow<List<HabitAlarm>> = alarmDao.observeAlarmsForHabit(habitId)

    suspend fun alarmByUuid(uuid: String): HabitAlarm? = alarmDao.alarmByUuid(uuid)
    suspend fun habitsWithAlarms(): List<AlarmHabitRef> = alarmDao.habitsWithAlarms()
    suspend fun alarmCount(): Int = alarmDao.alarmCount()

    /** Bounded snapshot of every alarm definition — the reconciliation input. */
    suspend fun allAlarms(): List<HabitAlarm> = alarmDao.allAlarms()

    /** The alarm's habit, for delivery-time revalidation (archived/deleted checks). */
    suspend fun habitById(habitId: Long): Habit? = db.dao().habit(habitId)

    /** Creates or updates one alarm definition. Creation validates the habit, the bounded
     *  per-habit/global caps and duplicate times within the same habit. */
    suspend fun save(alarm: HabitAlarm): HabitAlarm = db.withTransaction {
        val existing = if (alarm.id == 0L) null else alarmDao.alarm(alarm.id)
        require(alarm.id == 0L || existing != null) { "This alarm no longer exists." }
        require(habitDao.habit(alarm.habitId) != null) { "An alarm belongs to a missing habit." }
        val uuid = existing?.uuid ?: alarm.uuid.ifBlank { UUID.randomUUID().toString() }
        val uuidOwner = alarmDao.alarmByUuid(uuid)
        require(uuidOwner == null || uuidOwner.id == alarm.id) { "Another alarm already uses this identifier." }
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val candidate = alarm.copy(
            uuid = uuid,
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        HabitAlarmValidator.validate(candidate)
        if (existing == null) {
            val siblings = alarmDao.alarmsForHabitSnapshot(alarm.habitId)
            require(alarmDao.alarmCount() < MAX_HABIT_ALARMS) {
                "LifeTrack cannot hold more than %,d alarms.".format(java.util.Locale.ROOT, MAX_HABIT_ALARMS)
            }
            require(siblings.size < MAX_ALARMS_PER_HABIT) {
                "A habit cannot carry more than $MAX_ALARMS_PER_HABIT alarms."
            }
            val duplicate = siblings.any {
                it.timeMinutes == candidate.timeMinutes && it.recurrence == candidate.recurrence &&
                    it.onceDate == candidate.onceDate
            }
            require(!duplicate) { "This habit already has an alarm at this time." }
            candidate.copy(id = alarmDao.insertAlarm(candidate))
        } else {
            alarmDao.updateAlarm(candidate)
            candidate
        }
    }

    suspend fun setEnabled(alarmUuid: String, enabled: Boolean): HabitAlarm = db.withTransaction {
        val alarm = alarmDao.alarmByUuid(alarmUuid)
            ?: throw IllegalArgumentException("This alarm no longer exists.")
        val updated = alarm.copy(
            enabled = enabled,
            updatedAt = nextTimestamp(System.currentTimeMillis(), alarm.createdAt, alarm.updatedAt)
        )
        alarmDao.updateAlarm(updated)
        updated
    }

    suspend fun delete(alarmUuid: String) = db.withTransaction {
        val alarm = alarmDao.alarmByUuid(alarmUuid)
            ?: throw IllegalArgumentException("This alarm no longer exists.")
        alarmDao.deleteAlarmById(alarm.id)
    }

    /** Removes every alarm for a habit (habit delete cascade also covers this — this explicit
     *  path exists for the editor's "switch back to normal reminder" flow). */
    suspend fun deleteAllForHabit(habitId: Long) = db.withTransaction {
        alarmDao.deleteAlarmsForHabit(habitId)
    }
}
