package com.lifetrack.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
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
    @ColumnInfo(defaultValue = "'[]'") val scheduleHistory: List<ScheduleRevision> = emptyList()
)

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

@Serializable
@Entity(tableName = "app_settings")
data class AppSettings(
    @Required @PrimaryKey val id: Int = 1,
    @Required val theme: String = "SYSTEM",
    @Required val dynamicColor: Boolean = true,
    @Required val backupReminder: Boolean = false,
    @Required val lastExportAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val hapticsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val animationsEnabled: Boolean = true
)

class Converters {
    @TypeConverter fun dateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
    @TypeConverter fun statusToString(value: LogStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): LogStatus = LogStatus.valueOf(value)
    @TypeConverter fun daysToString(value: List<Int>): String = value.joinToString(",")
    @TypeConverter fun stringToDays(value: String): List<Int> = value.split(',').mapNotNull(String::toIntOrNull)
    @TypeConverter fun revisionsToString(value: List<ScheduleRevision>): String = BackupValidator.json.encodeToString(value)
    @TypeConverter fun stringToRevisions(value: String): List<ScheduleRevision> = BackupValidator.json.decodeFromString(value)
}

/** A free-form activity (push-ups, running, crunches). Optional target; actuals are recorded unclamped. */
@Serializable
@Entity(tableName = "activities")
data class WorkoutActivity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Required val name: String,
    @Required val icon: String = "💪",
    @Required val unit: String,
    val target: Double? = null,
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
