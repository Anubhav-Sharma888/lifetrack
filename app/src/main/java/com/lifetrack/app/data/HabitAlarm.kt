package com.lifetrack.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * A ringing-alarm definition for one habit (Prompt 6, Part B): an explicit opt-in upgrade from
 * the legacy lightweight reminder to genuine alarm-style delivery at user-chosen times.
 *
 * Reminder mode is per habit and derived from data: a habit with rows in `habit_alarms` rings;
 * a habit without rows keeps the legacy `reminderMinutes` notification untouched. No existing
 * user ever starts ringing after updating — alarm rows exist only because the user created them.
 *
 * Recurrence is explicit and independent of the habit's tracking schedule (`ScheduleMode`):
 * ringing at 06:00 does not imply that 06:00 is a due day for a WEEKLY_QUOTA habit, and alarm
 * dismissal never completes the habit or counts as a check-in. WEEKDAYS recurrence uses this
 * alarm's own [weekdays] selection (ISO days 1=Monday..7=Sunday), independent of the habit's
 * tracking-day grid — a user may want the medicine alarm on days the habit isn't tracked.
 */
@Serializable
enum class HabitAlarmRecurrence { DAILY, WEEKDAYS, ONCE;
    companion object {
        fun fromStored(value: String): HabitAlarmRecurrence =
            entries.firstOrNull { it.name == value } ?: DAILY
    }
}

@Serializable
@Entity(
    tableName = "habit_alarms",
    foreignKeys = [ForeignKey(
        entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["uuid"], unique = true), Index("habitId")]
)
data class HabitAlarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val habitId: Long,
    /** Minutes since local midnight (0..1439) — the alarm's wall-clock time, device-local. */
    @Required val timeMinutes: Int,
    @Required val recurrence: String = HabitAlarmRecurrence.DAILY.name,
    /** ISO weekdays for WEEKDAYS recurrence, stored CSV via the shared converter ("1,2,3"). */
    @Required val weekdays: List<Int> = (1..7).toList(),
    /** The single occurrence date for ONCE alarms; null for recurring definitions. */
    @Required @Serializable(with = LocalDateSerializer::class) val onceDate: LocalDate? = null,
    @Required val enabled: Boolean = true,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

/** Bounded alarms per habit: real schedules use a handful of times; the cap prevents abuse. */
const val MAX_ALARMS_PER_HABIT = 12

/** Bounded total alarm definitions across the database (mirrors the session cap discipline). */
const val MAX_HABIT_ALARMS = 2_000

/** Ringing safety timeout: a missed dismissal stops ringing after this many seconds (2 minutes). */
const val ALARM_RINGING_TIMEOUT_SECONDS = 120

/** Bounded snoozes per ringing occurrence; further snoozes are refused (documented policy). */
const val MAX_ALARM_SNOOZES = 3

/** Snooze choices for ringing alarms, in minutes. */
val ALARM_SNOOZE_CHOICES_MINUTES = listOf(5, 10, 15)

/** Shared validation for alarm definitions, used by the repository and the backup validator. */
object HabitAlarmValidator {
    fun validate(alarm: HabitAlarm, today: LocalDate = LocalDate.now()) {
        require(isCanonicalUuid(alarm.uuid)) { "Alarm identifiers must be canonical UUIDs." }
        require(alarm.id >= 0 && alarm.id < Long.MAX_VALUE) { "Invalid alarm ID." }
        require(alarm.habitId > 0) { "An alarm belongs to a missing habit." }
        require(alarm.timeMinutes in 0..1_439) { "Alarm times must be between 00:00 and 23:59." }
        val recurrence = HabitAlarmRecurrence.fromStored(alarm.recurrence)
        when (recurrence) {
            HabitAlarmRecurrence.WEEKDAYS -> require(alarm.weekdays.isNotEmpty() && alarm.weekdays.all { it in 1..7 } &&
                alarm.weekdays.toSet().size == alarm.weekdays.size) {
                "Weekday alarms need distinct weekdays between Monday and Sunday."
            }
            HabitAlarmRecurrence.ONCE -> require(alarm.onceDate != null &&
                !alarm.onceDate.isBefore(BackupValidator.earliestDate) &&
                !alarm.onceDate.isAfter(today.plusYears(100))) {
                "One-time alarms need a valid future date."
            }
            HabitAlarmRecurrence.DAILY -> require(alarm.onceDate == null) {
                "Daily alarms cannot carry a one-time date."
            }
        }
        require(alarm.createdAt >= 0 && alarm.updatedAt >= alarm.createdAt) { "Invalid alarm timestamps." }
    }

    /** Trims nothing (no free text), but keeps the import normalization hook symmetric. */
    fun normalizeForImport(alarm: HabitAlarm): HabitAlarm = alarm
}
