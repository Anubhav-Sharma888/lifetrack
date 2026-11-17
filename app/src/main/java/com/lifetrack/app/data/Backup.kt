package com.lifetrack.app.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.YearMonth

@Serializable
data class LifeTrackBackup(
    @Required val formatVersion: Int = 3,
    val exportedAt: Long,
    val habits: List<Habit>,
    val logs: List<HabitLog>,
    val settings: AppSettings,
    val freezes: List<FreezeUsage>,
    val pauses: List<HabitPause> = emptyList(),
    val activities: List<WorkoutActivity> = emptyList(),
    val activityEntries: List<ActivityEntry> = emptyList(),
    // Smart Streaks: absent from every plain export so older app versions still decode them.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val streakProtections: List<StreakProtection> = emptyList(),
    // Daily reflections: same pattern — present only in v7 exports that carry them.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dailyReflections: List<DailyReflection> = emptyList(),
    // Achievement acknowledgments: present only in v8 exports that carry them.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val achievementAcknowledgments: List<AchievementAcknowledgment> = emptyList()
)

/** Validation is independent of Android/Room so corrupt imports can be rejected before writes. */
object BackupValidator {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }
    val earliestDate: LocalDate = LocalDate.of(1900, 1, 1)

    fun validateHabit(habit: Habit, today: LocalDate = LocalDate.now()) {
        require(habit.id >= 0 && habit.id < Long.MAX_VALUE) { "Invalid habit ID." }
        require(habit.name.isNotBlank() && habit.name.length <= 120) { "Habit names must contain 1–120 characters." }
        require(habit.description == null || habit.description.length <= 2_000) { "Descriptions are limited to 2,000 characters." }
        require(habit.icon.isNotBlank() && habit.icon.length <= 32) { "Choose an emoji or short icon (up to 32 characters)." }
        require(Regex("#[0-9a-fA-F]{6}").matches(habit.colorHex)) { "Habit colors must be six-digit hex colors, such as #43866B." }
        require(habit.category.isNotBlank() && habit.category.length <= 40) { "Categories must contain 1–40 characters." }
        require(habit.startDate >= earliestDate && habit.startDate <= today.plusYears(100)) { "Start dates must be between 1900 and 100 years from today." }
        require(habit.sortOrder >= 0) { "Habit order cannot be negative." }
        require(habit.scheduledDays.isNotEmpty() && habit.scheduledDays == habit.scheduledDays.distinct().sorted() && habit.scheduledDays.all { it in 1..7 }) { "Choose at least one unique scheduled weekday." }
        require(habit.targetCount in 1..100_000) { "Targets must be between 1 and 100,000." }
        require(habit.targetUnit.isNotBlank() && habit.targetUnit.length <= 24) { "Goal units must contain 1–24 characters." }
        require(habit.goalHistory.zipWithNext().all { (first, second) -> first.effectiveFrom < second.effectiveFrom }) { "Goal history must be chronological and unique." }
        require(habit.goalHistory.all { it.effectiveFrom in earliestDate..today.plusYears(100) && it.targetCount in 1..100_000 && it.targetUnit.isNotBlank() && it.targetUnit.length <= 24 }) { "Invalid goal revision." }
        habit.goalHistory.lastOrNull()?.let {
            require(it.targetCount == habit.targetCount && it.targetUnit == habit.targetUnit) { "The latest goal revision must match the current goal." }
        }
        require(habit.reminderMinutes == null || habit.reminderMinutes in 0..1439) { "Choose a valid local reminder time." }
        require(habit.scheduleHistory.map { it.effectiveFrom } == habit.scheduleHistory.map { it.effectiveFrom }.distinct().sorted()) { "Schedule history must be chronological and unique." }
        habit.scheduleHistory.forEach { revision ->
            require(revision.effectiveFrom >= earliestDate && revision.effectiveFrom <= today.plusYears(100)) { "Invalid schedule revision date." }
            require(revision.days.isNotEmpty() && revision.days == revision.days.distinct().sorted() && revision.days.all { it in 1..7 }) { "Invalid historic weekday schedule." }
        }
        require(habit.scheduleHistory.isEmpty() || habit.scheduleHistory.last().days == habit.scheduledDays) { "The latest schedule revision must match the habit's selected weekdays." }
        require(habit.createdAt >= 0 && habit.updatedAt >= habit.createdAt) { "Invalid habit timestamps." }
        require(habit.isArchived == (habit.archivedOn != null)) { "Archived habits must include their archive date." }
        require(habit.archivedOn == null || (habit.archivedOn >= earliestDate && habit.archivedOn <= today)) { "Invalid archive date." }
        require(habit.streakPolicy in StreakPolicy.entries.map(StreakPolicy::name)) { "Unknown streak policy for this habit." }
        require(habit.partialCreditPercent in 1..100) { "Partial-credit thresholds must be between 1 and 100 percent." }
    }

    /** One optional mood and/or journal per date; a row carrying neither is rejected before any write. */
    fun validateReflection(reflection: DailyReflection, today: LocalDate = LocalDate.now()) {
        require(reflection.date >= earliestDate && reflection.date <= today) { "Reflection dates cannot be in the future or before 1900." }
        require(reflection.mood == null || reflection.mood in 1..5) { "Moods are recorded on a scale of 1 to 5." }
        require(reflection.journal == null || reflection.journal.isNotBlank() && reflection.journal.length <= 2_000) { "Journals are limited to 2,000 characters." }
        require(reflection.mood != null || reflection.journal != null) { "A reflection needs a mood or a journal entry." }
        require(reflection.createdAt >= 0 && reflection.updatedAt >= reflection.createdAt) { "Invalid reflection timestamps." }
    }

    /** Acknowledgment rows carry no user content — only a catalogue id and a durable timestamp. */
    fun validateAchievementAcknowledgment(acknowledgment: AchievementAcknowledgment) {
        require(acknowledgment.achievementId.isNotBlank() && acknowledgment.achievementId.length <= 100) { "Invalid achievement identifier." }
        require(acknowledgment.acknowledgedAt >= 0) { "Invalid achievement acknowledgment timestamp." }
    }

    fun validateSettings(settings: AppSettings) {
        require(settings.id == 1) { "Invalid settings record." }
        require(settings.theme in setOf("SYSTEM", "LIGHT", "DARK")) { "Unknown theme preference." }
        require(settings.lastExportAt == null || settings.lastExportAt >= 0) { "Invalid backup timestamp." }
    }

    fun validateActivity(activity: WorkoutActivity, today: LocalDate = LocalDate.now()) {
        require(activity.id >= 0 && activity.id < Long.MAX_VALUE) { "Invalid activity ID." }
        require(activity.name.isNotBlank() && activity.name.length <= 80) { "Activity names must contain 1–80 characters." }
        require(activity.icon.isNotBlank() && activity.icon.length <= 32) { "Choose an emoji or short icon for each activity." }
        require(activity.unit.isNotBlank() && activity.unit.length <= 24) { "Activity units must contain 1–24 characters." }
        require(activity.trackingMode in ActivityMode.entries.map(ActivityMode::name)) { "Unknown activity tracking mode." }
        require(activity.startingValue == null || activity.startingValue.isFinite() && activity.startingValue >= 0 && activity.startingValue <= 1_000_000.0) { "Activity starting values must be between 0 and 1,000,000." }
        // Countdown activities may aim at zero; every other mode keeps positive targets.
        require(activity.target == null || activity.target.isFinite() && activity.target >= 0 && activity.target <= 1_000_000.0 &&
            (activity.target > 0 || activity.trackingMode == ActivityMode.DECREASING.name)) { "Activity targets must be between 0 and 1,000,000." }
        require(activity.sortOrder >= 0) { "Activity order cannot be negative." }
        require(activity.createdAt >= 0 && activity.updatedAt >= activity.createdAt) { "Invalid activity timestamps." }
    }

    fun validateActivityEntry(entry: ActivityEntry, activities: Map<Long, WorkoutActivity>, today: LocalDate = LocalDate.now()) {
        require(entry.id >= 0 && entry.id < Long.MAX_VALUE) { "Invalid activity entry ID." }
        require(entry.activityId in activities) { "An activity entry belongs to a missing activity." }
        require(entry.date >= earliestDate && entry.date <= today) { "Activity entries cannot be in the future." }
        // Only countdown activities may record a completed state of zero.
        val allowsZero = activities[entry.activityId]?.trackingMode == ActivityMode.DECREASING.name
        require(entry.quantity.isFinite() && entry.quantity >= 0 && entry.quantity <= 1_000_000.0 &&
            (entry.quantity > 0 || allowsZero)) { "Activity quantities must be between 0 and 1,000,000." }
        require(entry.note == null || entry.note.length <= 1_000) { "Activity entry notes are limited to 1,000 characters." }
        require(entry.createdAt >= 0 && entry.updatedAt >= entry.createdAt) { "Invalid activity entry timestamps." }
    }

    fun validate(backup: LifeTrackBackup, today: LocalDate = LocalDate.now()) {
        require(backup.formatVersion in 1..8) { "This backup version is not supported by this app." }
        require(backup.formatVersion >= 4 || backup.habits.none { it.goalHistory.isNotEmpty() }) { "Goal revisions require backup version 4." }
        require(backup.formatVersion >= 5 || backup.activities.none { it.isDirectional() }) { "Directional activity tracking requires backup version 5." }
        require(backup.formatVersion >= 6 || (backup.habits.none { com.lifetrack.app.domain.StreakPolicy.isConfigured(it) } &&
            backup.streakProtections.isEmpty())) { "Smart Streaks data requires backup version 6." }
        require(backup.formatVersion >= 7 || backup.dailyReflections.isEmpty()) { "Daily reflections require backup version 7." }
        require(backup.formatVersion >= 8 || backup.achievementAcknowledgments.isEmpty()) { "Achievement acknowledgments require backup version 8." }
        require(backup.exportedAt >= 0) { "Invalid export timestamp." }
        validateSettings(backup.settings)
        // App lock is a local-only security choice and never roams through backups, plain or encrypted.
        require(!backup.settings.appLockEnabled) { "App lock is not carried in backups. Enable it again after restoring." }
        require(backup.habits.map { it.id }.toSet().size == backup.habits.size) { "Duplicate habit IDs in backup." }
        val habits = backup.habits.associateBy { it.id }
        backup.habits.forEach { habit ->
            validateHabit(habit, today)
            require(habit.id > 0) { "Backed-up habits must have saved IDs." }
            require(habit.afterHabitId == null || habits.containsKey(habit.afterHabitId)) { "A habit stack points to a missing habit." }
        }
        // Linear traversal, including long stacking chains in a bounded-size imported file.
        val validatedStacks = mutableSetOf<Long>()
        backup.habits.forEach { habit ->
            val visited = mutableSetOf<Long>()
            var cursor: Habit? = habit
            while (cursor != null && cursor.id !in validatedStacks) {
                require(visited.add(cursor.id)) { "Habit stacks cannot form a cycle." }
                cursor = cursor.afterHabitId?.let(habits::get)
            }
            validatedStacks.addAll(visited)
        }
        require(backup.logs.map { it.id }.toSet().size == backup.logs.size) { "Duplicate check-in IDs in backup." }
        require(backup.logs.map { it.habitId to it.date }.toSet().size == backup.logs.size) { "More than one check-in exists for the same habit and date." }
        require(backup.freezes.map { it.month }.toSet().size == backup.freezes.size) { "Duplicate monthly freeze credits in backup." }
        backup.freezes.forEach { freeze ->
            val month = runCatching { YearMonth.parse(freeze.month) }.getOrNull()
            require(month != null && month.toString() == freeze.month && month >= YearMonth.from(earliestDate) && month <= YearMonth.from(today)) { "Invalid freeze month." }
            require(freeze.usedAt >= 0) { "Invalid freeze timestamp." }
        }
        val freezeMonths = backup.freezes.map { it.month }.toSet()
        require(backup.pauses.map { it.id }.toSet().size == backup.pauses.size) { "Duplicate pause IDs in backup." }
        backup.pauses.groupBy { it.habitId }.forEach { (habitId, pauses) ->
            val habit = habits[habitId]
            require(habit != null) { "A pause belongs to a missing habit." }
            val sorted = pauses.sortedBy { it.startDate }
            sorted.forEachIndexed { index, pause ->
                require(pause.id > 0 && pause.id < Long.MAX_VALUE) { "Invalid pause ID." }
                require(pause.startDate >= earliestDate && pause.startDate <= today.plusDays(1)) { "Invalid pause start date." }
                require(pause.endDate == null || pause.endDate > pause.startDate && pause.endDate <= today.plusDays(1)) { "Pause intervals must end after they start." }
                require(index == 0 || sorted[index - 1].endDate?.let { it <= pause.startDate } == true) { "Pause intervals cannot overlap." }
                if (pause.endDate == null) require(habit.isArchived && pause.startDate == habit.archivedOn!!.plusDays(1)) { "An open pause must match an archived habit." }
            }
        }
        if (backup.formatVersion >= 2) backup.habits.filter { it.isArchived }.forEach { habit ->
            require(backup.pauses.count { it.habitId == habit.id && it.endDate == null && it.startDate == habit.archivedOn!!.plusDays(1) } == 1) { "Archived habits must retain their open pause interval." }
        }
        val activitiesById = backup.activities.associateBy { it.id }
        require(backup.activities.map { it.id }.toSet().size == backup.activities.size) { "Duplicate activity IDs in backup." }
        backup.activities.forEach { activity ->
            validateActivity(activity, today)
            require(activity.id > 0) { "Backed-up activities must have saved IDs." }
        }
        require(backup.activityEntries.map { it.id }.toSet().size == backup.activityEntries.size) { "Duplicate activity entry IDs in backup." }
        require(backup.activityEntries.map { it.activityId to it.date }.toSet().size == backup.activityEntries.size) { "More than one activity entry exists for the same activity and date." }
        backup.activityEntries.forEach { entry -> validateActivityEntry(entry, activitiesById, today) }
        val frozenLogMonths = mutableSetOf<String>()
        backup.logs.forEach { log ->
            val habit = habits[log.habitId]
            require(habit != null) { "A check-in belongs to a missing habit." }
            require(log.id > 0 && log.id < Long.MAX_VALUE) { "Invalid check-in ID." }
            require(log.date >= habit.startDate && log.date <= today) { "Check-in dates cannot be before a habit starts or in the future." }
            require(habit.archivedOn == null || log.date <= habit.archivedOn) { "A check-in occurs after its habit was archived." }
            require(backup.pauses.none { it.habitId == log.habitId && log.date >= it.startDate && (it.endDate == null || log.date < it.endDate) }) { "A check-in occurs during an archived pause." }
            require(log.note == null || log.note.length <= 4_000) { "Check-in notes are limited to 4,000 characters." }
            require(log.createdAt >= 0 && log.updatedAt >= log.createdAt) { "Invalid check-in timestamps." }
            val target = habit.goalOn(log.date).targetCount
            require(log.progress in 0..target) { "Check-in progress is outside its goal." }
            if (backup.formatVersion >= 2) {
                require(when (log.status) {
                    LogStatus.COMPLETED -> log.progress == target
                    LogStatus.IN_PROGRESS -> log.progress < target
                    LogStatus.SKIPPED, LogStatus.FAILED -> log.progress == 0
                }) { "Check-in progress does not match its status." }
            }
            if (log.isFrozen) {
                val month = YearMonth.from(log.date).toString()
                require(log.status == LogStatus.SKIPPED && month in freezeMonths && frozenLogMonths.add(month)) { "Invalid frozen check-in or multiple freezes in one month." }
            }
        }
        // A protection decision is meaningful only on a real check-in of the same habit and date.
        require(backup.streakProtections.map { it.habitId to it.date }.toSet().size == backup.streakProtections.size) { "Duplicate streak protection decisions in backup." }
        backup.streakProtections.forEach { protection ->
            val habit = habits[protection.habitId]
            require(habit != null) { "A streak protection belongs to a missing habit." }
            require(protection.date >= habit.startDate && protection.date <= today) { "Streak protection dates cannot be before a habit starts or in the future." }
            require(habit.archivedOn == null || protection.date <= habit.archivedOn) { "A streak protection occurs after its habit was archived." }
            require(protection.createdAt >= 0 && protection.updatedAt >= protection.createdAt) { "Invalid streak protection timestamps." }
        }
        // One reflection per date, each carrying a mood and/or a journal.
        require(backup.dailyReflections.map { it.date }.toSet().size == backup.dailyReflections.size) { "More than one reflection exists for the same date." }
        backup.dailyReflections.forEach { validateReflection(it, today) }
        // Acknowledgments: no user content, no duplicates, sane timestamps.
        require(backup.achievementAcknowledgments.map { it.achievementId }.toSet().size == backup.achievementAcknowledgments.size) { "Duplicate achievement acknowledgments in backup." }
        backup.achievementAcknowledgments.forEach { validateAchievementAcknowledgment(it) }
    }

    /** Version 1 lacks schedules, progress and intervals; normalize before replacing any table. */
    fun decode(text: String, today: LocalDate = LocalDate.now()): LifeTrackBackup {
        val document = json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        val version = document["formatVersion"]?.jsonPrimitive?.intOrNull
        require(version != null && version in 1..8) { "This backup version is not supported by this app." }
        if (version >= 2) {
            require("pauses" in document) { "This version 2 backup is missing its pause history." }
            (document["habits"] as? JsonArray)?.forEach { entry ->
                require(setOf("scheduledDays", "targetCount", "targetUnit", "reminderMinutes", "scheduleHistory").all { it in entry.jsonObject }) { "This version 2 backup is missing habit scheduling or goal fields." }
            }
            (document["logs"] as? JsonArray)?.forEach { require("progress" in it.jsonObject) { "This version 2 backup is missing progress." } }
            require(setOf("hapticsEnabled", "animationsEnabled").all { it in document.getValue("settings").jsonObject }) { "This version 2 backup is missing feedback settings." }
        }
        if (version >= 3) {
            require("activities" in document && "activityEntries" in document) { "This version 3 backup is missing its activity collections." }
        }
        val decoded = json.decodeFromString<LifeTrackBackup>(text.removePrefix("\uFEFF"))
        val normalized = if (version == 1) {
            val byId = decoded.habits.associateBy { it.id }
            decoded.copy(
                formatVersion = 3,
                logs = decoded.logs.map { log -> log.copy(progress = if (log.status == LogStatus.COMPLETED) byId[log.habitId]?.targetCount ?: 1 else 0) },
                pauses = decoded.habits.filter { it.isArchived && it.archivedOn != null }.mapIndexed { index, habit ->
                    HabitPause(index + 1L, habit.id, habit.archivedOn!!.plusDays(1))
                }
            )
        } else decoded.copy(formatVersion = maxOf(3, version))
        validate(normalized, today)
        return normalized
    }
}
