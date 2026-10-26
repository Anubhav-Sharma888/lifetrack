package com.lifetrack.app.data

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
    @Required val formatVersion: Int = 2,
    val exportedAt: Long,
    val habits: List<Habit>,
    val logs: List<HabitLog>,
    val settings: AppSettings,
    val freezes: List<FreezeUsage>,
    val pauses: List<HabitPause> = emptyList()
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
    }

    fun validateSettings(settings: AppSettings) {
        require(settings.id == 1) { "Invalid settings record." }
        require(settings.theme in setOf("SYSTEM", "LIGHT", "DARK")) { "Unknown theme preference." }
        require(settings.lastExportAt == null || settings.lastExportAt >= 0) { "Invalid backup timestamp." }
    }

    fun validate(backup: LifeTrackBackup, today: LocalDate = LocalDate.now()) {
        require(backup.formatVersion in 1..2) { "This backup version is not supported by this app." }
        require(backup.exportedAt >= 0) { "Invalid export timestamp." }
        validateSettings(backup.settings)
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
        if (backup.formatVersion == 2) backup.habits.filter { it.isArchived }.forEach { habit ->
            require(backup.pauses.count { it.habitId == habit.id && it.endDate == null && it.startDate == habit.archivedOn!!.plusDays(1) } == 1) { "Archived habits must retain their open pause interval." }
        }
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
            require(log.progress in 0..habit.targetCount) { "Check-in progress is outside its goal." }
            if (backup.formatVersion == 2) {
                require(when (log.status) {
                    LogStatus.COMPLETED -> log.progress == habit.targetCount
                    LogStatus.IN_PROGRESS -> log.progress < habit.targetCount
                    LogStatus.SKIPPED, LogStatus.FAILED -> log.progress == 0
                }) { "Check-in progress does not match its status." }
            }
            if (log.isFrozen) {
                val month = YearMonth.from(log.date).toString()
                require(log.status == LogStatus.SKIPPED && month in freezeMonths && frozenLogMonths.add(month)) { "Invalid frozen check-in or multiple freezes in one month." }
            }
        }
    }

    /** Version 1 lacks schedules, progress and intervals; normalize before replacing any table. */
    fun decode(text: String, today: LocalDate = LocalDate.now()): LifeTrackBackup {
        val document = json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        val version = document["formatVersion"]?.jsonPrimitive?.intOrNull
        require(version in 1..2) { "This backup version is not supported by this app." }
        if (version == 2) {
            require("pauses" in document) { "This version 2 backup is missing its pause history." }
            (document["habits"] as? JsonArray)?.forEach { entry ->
                require(setOf("scheduledDays", "targetCount", "targetUnit", "reminderMinutes", "scheduleHistory").all { it in entry.jsonObject }) { "This version 2 backup is missing habit scheduling or goal fields." }
            }
            (document["logs"] as? JsonArray)?.forEach { require("progress" in it.jsonObject) { "This version 2 backup is missing progress." } }
            require(setOf("hapticsEnabled", "animationsEnabled").all { it in document.getValue("settings").jsonObject }) { "This version 2 backup is missing feedback settings." }
        }
        val decoded = json.decodeFromString<LifeTrackBackup>(text.removePrefix("\uFEFF"))
        val normalized = if (version == 1) {
            val byId = decoded.habits.associateBy { it.id }
            decoded.copy(
                formatVersion = 2,
                logs = decoded.logs.map { log -> log.copy(progress = if (log.status == LogStatus.COMPLETED) byId[log.habitId]?.targetCount ?: 1 else 0) },
                pauses = decoded.habits.filter { it.isArchived && it.archivedOn != null }.mapIndexed { index, habit ->
                    HabitPause(index + 1L, habit.id, habit.archivedOn!!.plusDays(1))
                }
            )
        } else decoded
        validate(normalized, today)
        return normalized
    }
}
