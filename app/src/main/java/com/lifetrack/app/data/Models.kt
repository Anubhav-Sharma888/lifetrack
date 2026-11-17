package com.lifetrack.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** Calendar dates deliberately have no UTC conversion: Day 1 is the chosen local start date. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

@Serializable
@Entity(
    tableName = "habits",
    foreignKeys = [ForeignKey(
        entity = Habit::class, parentColumns = ["id"], childColumns = ["afterHabitId"],
        onDelete = ForeignKey.SET_NULL, deferred = true
    )],
    indices = [Index("afterHabitId")]
)
data class Habit(
    @Required @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @Required val description: String? = null,
    @Required val icon: String = "🌱",
    @Required val colorHex: String = "#43866B",
    @Required @Serializable(with = LocalDateSerializer::class) val startDate: LocalDate = LocalDate.now(),
    @Required val isArchived: Boolean = false,
    @Required val sortOrder: Int = 0,
    @Required val category: String = "Personal",
    @Required val afterHabitId: Long? = null,
    @Required @Serializable(with = LocalDateSerializer::class) val archivedOn: LocalDate? = null,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "'1,2,3,4,5,6,7'") val scheduledDays: List<Int> = (1..7).toList(),
    @ColumnInfo(defaultValue = "1") val targetCount: Int = 1,
    @ColumnInfo(defaultValue = "'times'") val targetUnit: String = "times",
    @ColumnInfo(defaultValue = "NULL") val reminderMinutes: Int? = null,
    @ColumnInfo(defaultValue = "'[]'") val scheduleHistory: List<ScheduleRevision> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ColumnInfo(defaultValue = "'[]'") val goalHistory: List<GoalRevision> = emptyList(),
    // Smart Streaks: both keys stay out of plain exports so older app versions still decode them.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ColumnInfo(defaultValue = "'STRICT'") val streakPolicy: String = StreakPolicy.STRICT.name,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ColumnInfo(defaultValue = "75") val partialCreditPercent: Int = 75
)

@Serializable
data class GoalRevision(
    @Serializable(with = LocalDateSerializer::class) val effectiveFrom: LocalDate,
    val targetCount: Int,
    val targetUnit: String
)

internal fun Habit.goalOn(date: LocalDate): Habit {
    val goal = goalHistory.lastOrNull { it.effectiveFrom <= date } ?: goalHistory.firstOrNull() ?: return this
    return copy(targetCount = goal.targetCount, targetUnit = goal.targetUnit)
}

@Serializable
data class ScheduleRevision(
    @Serializable(with = LocalDateSerializer::class) val effectiveFrom: LocalDate,
    val days: List<Int>
)

@Serializable
enum class LogStatus { COMPLETED, SKIPPED, FAILED, IN_PROGRESS }

@Serializable
@Entity(
    tableName = "habit_logs",
    foreignKeys = [ForeignKey(
        entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["habitId", "date"], unique = true), Index("date")]
)
data class HabitLog(
    @Required @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val status: LogStatus,
    @Required val note: String? = null,
    @Required val isFrozen: Boolean = false,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val progress: Int = 0
)

/** A pause starts inclusively and ends exclusively; an open pause represents an archived habit. */
@Serializable
@Entity(tableName = "habit_pauses", foreignKeys = [ForeignKey(
    entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("habitId"), Index(value = ["habitId", "startDate"], unique = true)])
data class HabitPause(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    @Serializable(with = LocalDateSerializer::class) val startDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val endDate: LocalDate? = null
)

/** Operational once-per-day notification deduplication, intentionally excluded from manual backups. */
@Entity(tableName = "reminder_deliveries", primaryKeys = ["habitId", "date"], foreignKeys = [ForeignKey(
    entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE
)])
data class ReminderDelivery(val habitId: Long, val date: LocalDate, val deliveredAt: Long)

/** A spent freeze is retained even when its original check-in or habit is deleted. */
@Serializable
@Entity(tableName = "freeze_usage")
data class FreezeUsage(@PrimaryKey val month: String, val usedAt: Long)

/**
 * One explicit Smart Streaks decision per habit and date: whether that partially completed day
 * bridges the completion streak. Rows exist only where the user decided manually, and they always
 * override the habit's automatic policy. Actual progress lives solely in the habit log.
 */
@Serializable
@Entity(
    tableName = "streak_protections",
    primaryKeys = ["habitId", "date"],
    foreignKeys = [ForeignKey(
        entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("habitId")]
)
data class StreakProtection(
    val habitId: Long,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val protectChain: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

/**
 * One optional mood and/or journal note per local calendar date. Moods are self-reported feelings
 * on a 1–5 scale — never a diagnosis, and never treated as the cause or the consequence of anything.
 * At least one of the two fields must be present; a row carrying neither is meaningless.
 */
@Serializable
@Entity(tableName = "daily_reflections", primaryKeys = ["date"])
data class DailyReflection(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val mood: Int? = null,
    val journal: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

@Serializable
@Entity(tableName = "app_settings")
data class AppSettings(
    @Required @PrimaryKey val id: Int = 1,
    @Required val theme: String = "SYSTEM",
    @Required val dynamicColor: Boolean = true,
    @Required val backupReminder: Boolean = false,
    @Required val lastExportAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val hapticsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val animationsEnabled: Boolean = true,
    // Local-only security choice: stripped from every backup so restoring can never silently change it.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ColumnInfo(defaultValue = "0") val appLockEnabled: Boolean = false
)

class Converters {
    @TypeConverter fun dateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
    @TypeConverter fun statusToString(value: LogStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): LogStatus = LogStatus.valueOf(value)
    @TypeConverter fun daysToString(value: List<Int>): String = value.joinToString(",")
    @TypeConverter fun stringToDays(value: String): List<Int> = value.split(',').mapNotNull(String::toIntOrNull)
    @TypeConverter fun goalsToString(value: List<GoalRevision>): String = BackupValidator.json.encodeToString(value)
    @TypeConverter fun stringToGoals(value: String): List<GoalRevision> = BackupValidator.json.decodeFromString(value)
    @TypeConverter fun revisionsToString(value: List<ScheduleRevision>): String = BackupValidator.json.encodeToString(value)
    @TypeConverter fun stringToRevisions(value: String): List<ScheduleRevision> = BackupValidator.json.decodeFromString(value)
}

/** Tracking semantics for activities. LOGGED preserves the original per-day quantity records. */
@Serializable
enum class ActivityMode { LOGGED, INCREASING, DECREASING, PERSONAL_BEST;

    companion object {
        /** Unknown stored values fall back to LOGGED so old or foreign data still renders. */
        fun fromStored(value: String): ActivityMode = entries.firstOrNull { it.name == value } ?: LOGGED
    }
}

/** How partially completed quantitative days treat the completion streak. STRICT preserves the original rules. */
@Serializable
enum class StreakPolicy { STRICT, AUTOMATIC, MANUAL;

    companion object {
        /** Unknown stored values fall back to STRICT so old or foreign data keeps its original meaning. */
        fun fromStored(value: String): StreakPolicy = entries.firstOrNull { it.name == value } ?: STRICT
    }
}

/** True when an activity uses directional tracking rather than plain daily logs. */
fun WorkoutActivity.isDirectional(): Boolean = trackingMode != ActivityMode.LOGGED.name || startingValue != null

/** A free-form activity (push-ups, running, crunches). Optional target; actuals are recorded unclamped. */
@Serializable
@Entity(tableName = "activities")
data class WorkoutActivity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Required val name: String,
    @Required val icon: String = "💪",
    @Required val unit: String,
    val target: Double? = null,
    // EncodeDefault NEVER keeps v3/v4 exports free of the new keys, so older app versions can still decode them.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ColumnInfo(defaultValue = "'LOGGED'") val trackingMode: String = ActivityMode.LOGGED.name,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ColumnInfo(defaultValue = "NULL") val startingValue: Double? = null,
    @Required val sortOrder: Int = 0,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

/** One quantity record per activity per day; actuals may sit above or below any target. */
@Serializable
@Entity(tableName = "activity_entries", foreignKeys = [ForeignKey(
    entity = WorkoutActivity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("activityId"), Index(value = ["activityId", "date"], unique = true)])
data class ActivityEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    @Required val quantity: Double,
    @Required val note: String? = null,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)
