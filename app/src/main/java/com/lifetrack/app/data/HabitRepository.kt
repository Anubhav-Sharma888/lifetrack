package com.lifetrack.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

data class HabitSnapshot(val habits: List<Habit>, val logs: List<HabitLog>, val pauses: List<HabitPause> = emptyList())
data class CheckInUndo(val before: HabitLog?, val after: HabitLog?, val habitId: Long, val date: LocalDate, val habitCreatedAt: Long)

/** Wall-clock corrections cannot make a saved record older than its creation or previous edit. */
fun nextTimestamp(now: Long, createdAt: Long = 0, updatedAt: Long = 0): Long =
    maxOf(0, now, createdAt, if (updatedAt == Long.MAX_VALUE) updatedAt else updatedAt + 1)

/** All mutations, including imports and widget check-ins, pass through the same domain rules. */
@Singleton
class HabitRepository @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context
) {
    private val dao = db.dao()
    // Keep a slow provider write and its final export timestamp from straddling an import.
    // Database transactions separately serialize widget mutations and snapshot replacement.
    private val backupMutex = Mutex()
    val habits: Flow<List<Habit>> = dao.observeHabits()
    val logs: Flow<List<HabitLog>> = dao.observeLogs()
    val freezes: Flow<List<FreezeUsage>> = dao.observeFreezes()
    val settings: Flow<AppSettings> = dao.observeSettings().map { it ?: AppSettings() }
    val pauses: Flow<List<HabitPause>> = dao.observePauses()
    val activities: Flow<List<WorkoutActivity>> = dao.observeActivities()
    val activityEntries: Flow<List<ActivityEntry>> = dao.observeActivityEntries()
    val streakProtections: Flow<List<StreakProtection>> = dao.observeStreakProtections()
    val reflections: Flow<List<DailyReflection>> = dao.observeReflections()
    val achievementAcknowledgments: Flow<List<AchievementAcknowledgment>> = dao.observeAchievementAcknowledgments()

    /** Actual quantities are recorded exactly as performed; targets never clamp them. */
    suspend fun saveActivity(activity: WorkoutActivity): WorkoutActivity = db.withTransaction {
        require(activity.name.isNotBlank() && activity.name.length <= 80) { "Activity names must contain 1–80 characters." }
        require(activity.icon.isNotBlank() && activity.icon.length <= 32) { "Activity icons must contain 1–32 characters." }
        require(activity.unit.isNotBlank() && activity.unit.length <= 24) { "Activity units must contain 1–24 characters." }
        require(activity.trackingMode in ActivityMode.entries.map(ActivityMode::name)) { "Unknown tracking mode for this activity." }
        require(activity.startingValue == null || activity.startingValue.isFinite() && activity.startingValue >= 0 && activity.startingValue <= 1_000_000.0) { "Starting values must be between 0 and 1,000,000." }
        // Only countdown activities may aim at zero; other modes keep positive targets.
        require(activity.target == null || activity.target.isFinite() && activity.target >= 0 && activity.target <= 1_000_000.0 &&
            (activity.target > 0 || activity.trackingMode == ActivityMode.DECREASING.name)) { "Activity targets must be between 0 and 1,000,000." }
        val now = nextTimestamp(System.currentTimeMillis())
        val saved = if (activity.id == 0L) activity.copy(createdAt = now, updatedAt = now) else activity.copy(updatedAt = now)
        dao.upsertActivity(saved)
        saved
    }

    suspend fun deleteActivity(id: Long) = db.withTransaction {
        dao.deleteActivityEntriesByActivity(id)
        dao.deleteActivityById(id)
    }

    suspend fun setActivityEntry(activityId: Long, date: LocalDate, quantity: Double, note: String? = null) = db.withTransaction {
        val activity = dao.activity(activityId) ?: throw IllegalArgumentException("This activity no longer exists.")
        // A clock-skew entry would otherwise make every later JSON export fail validation.
        require(date <= LocalDate.now()) { "Activity entries cannot be in the future." }
        // Only countdown activities may record a completed state of zero.
        val allowsZero = activity.trackingMode == ActivityMode.DECREASING.name
        require(quantity.isFinite() && quantity >= 0 && quantity <= 1_000_000.0 &&
            (quantity > 0 || allowsZero)) { "Activity quantities must be between 0 and 1,000,000." }
        require(note == null || note.length <= 1_000) { "Activity entry notes are limited to 1,000 characters." }
        val existing = dao.activityEntry(activityId, date)
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        dao.upsertActivityEntry(ActivityEntry(id = existing?.id ?: 0, activityId = activityId, date = date,
            quantity = quantity, note = note?.ifBlank { null }, createdAt = existing?.createdAt ?: now, updatedAt = now))
    }

    suspend fun clearActivityEntry(activityId: Long, date: LocalDate) = db.withTransaction {
        dao.deleteActivityEntry(activityId, date)
    }

    /** Read both tables at one point in time, including while a backup is replacing the database. */
    suspend fun readSnapshot(date: LocalDate? = null): HabitSnapshot = db.withTransaction {
        HabitSnapshot(dao.allHabits(), if (date == null) dao.allLogs() else dao.logsOnDate(date), dao.allPauses())
    }

    suspend fun saveHabit(habit: Habit): Long = db.withTransaction {
        val today = LocalDate.now()
        val existing = if (habit.id == 0L) null else dao.habit(habit.id)
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        require(habit.id == 0L || existing != null) { "This habit no longer exists." }
        require(habit.streakPolicy in StreakPolicy.entries.map(StreakPolicy::name)) { "Unknown streak policy for this habit." }
        require(habit.partialCreditPercent in 1..100) { "Partial-credit thresholds must be between 1 and 100 percent." }
        require(habit.scheduleMode in ScheduleMode.entries.map(ScheduleMode::name)) { "Unknown schedule mode for this habit." }
        require(habit.intervalDays == null || habit.intervalDays in 2..365) { "Day intervals must be between 2 and 365 days." }
        require(habit.weeklyTarget == null || habit.weeklyTarget in 1..7) { "Weekly targets must be between 1 and 7 days." }
        val scheduleMode = ScheduleMode.fromStored(habit.scheduleMode)
        require(
            when (scheduleMode) {
                ScheduleMode.WEEKDAYS -> habit.intervalDays == null && habit.weeklyTarget == null
                ScheduleMode.EVERY_N_DAYS -> habit.weeklyTarget == null && habit.intervalDays != null
                ScheduleMode.WEEKLY_QUOTA -> habit.intervalDays == null
            }
        ) { "The schedule mode and its parameters do not match." }
        val all = dao.allHabits()
        // Non-WEEKDAYS habits keep a normalized placeholder grid so legacy readers and CSV never
        // crash; the engine ignores `days` outside WEEKDAYS mode.
        val days = if (scheduleMode == ScheduleMode.WEEKDAYS) habit.scheduledDays.distinct().sorted() else (1..7).toList()
        val scheduleChanged = existing != null && (
            existing.scheduleMode != scheduleMode.name ||
                (scheduleMode == ScheduleMode.EVERY_N_DAYS && existing.intervalDays != habit.intervalDays) ||
                (scheduleMode == ScheduleMode.WEEKLY_QUOTA && existing.weeklyTarget != habit.weeklyTarget) ||
                (scheduleMode == ScheduleMode.WEEKDAYS && existing.scheduledDays != days)
            )
        val history = if (existing != null && scheduleChanged) {
            val earlier = existing.scheduleHistory.ifEmpty { listOf(ScheduleRevision(existing.startDate, existing.scheduledDays)) }
            earlier.filter { it.effectiveFrom < today } + when (scheduleMode) {
                ScheduleMode.EVERY_N_DAYS -> ScheduleRevision(today, days, mode = scheduleMode.name, intervalDays = habit.intervalDays)
                ScheduleMode.WEEKLY_QUOTA -> ScheduleRevision(today, days, mode = scheduleMode.name, weeklyTarget = habit.weeklyTarget)
                // WEEKDAYS revisions stay key-free: @EncodeDefault(NEVER) omits the mode fields.
                ScheduleMode.WEEKDAYS -> ScheduleRevision(today, days)
            }
        } else if (existing == null && scheduleMode != ScheduleMode.WEEKDAYS) {
            listOf(ScheduleRevision(today, days, mode = scheduleMode.name, intervalDays = habit.intervalDays, weeklyTarget = habit.weeklyTarget))
        } else existing?.scheduleHistory ?: habit.scheduleHistory
        val logs = existing?.let { dao.habitLogs(it.id) }.orEmpty()
        val unit = habit.targetUnit.trim()
        val goals = if (existing != null && (existing.targetCount != habit.targetCount || existing.targetUnit != unit)) {
            val effective = if (logs.any { it.date == today }) today.plusDays(1) else today
            val earlier = existing.goalHistory.ifEmpty {
                listOf(GoalRevision(existing.startDate, existing.targetCount, existing.targetUnit))
            }
            earlier.filter { it.effectiveFrom < effective } + GoalRevision(effective, habit.targetCount, unit)
        } else existing?.goalHistory.orEmpty()
        val normalized = habit.copy(
            name = habit.name.trim(), description = habit.description?.trim()?.takeIf { it.isNotEmpty() },
            category = habit.category.trim(), colorHex = habit.colorHex.uppercase(),
            targetUnit = unit, scheduledDays = days, scheduleHistory = history, goalHistory = goals,
            scheduleMode = scheduleMode.name,
            intervalDays = if (scheduleMode == ScheduleMode.EVERY_N_DAYS) habit.intervalDays else null,
            intervalAnchor = if (scheduleMode == ScheduleMode.EVERY_N_DAYS) today else null,
            weeklyTarget = if (scheduleMode == ScheduleMode.WEEKLY_QUOTA) habit.weeklyTarget else null,
            // Archiving has its own operation so editing a form cannot change historical eligibility.
            isArchived = existing?.isArchived ?: false,
            archivedOn = existing?.archivedOn,
            sortOrder = existing?.sortOrder ?: ((all.maxOfOrNull { it.sortOrder } ?: -1) + 1),
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        BackupValidator.validateHabit(normalized, today)
        if (existing != null) {
            val first = logs.minOfOrNull { it.date }
            require(first == null || normalized.startDate <= first) { "The start date cannot move after an existing check-in. Clear those entries first." }
        }
        if (normalized.afterHabitId != null) {
            val parent = all.firstOrNull { it.id == normalized.afterHabitId }
            val keepsExistingLink = existing != null && existing.afterHabitId == normalized.afterHabitId
            require(parent != null && (keepsExistingLink || !parent.isArchived)) { "Choose an active habit for your stack." }
            val visited = mutableSetOf<Long>()
            var cursor: Habit? = parent
            while (cursor != null) {
                require(cursor.id != normalized.id && visited.add(cursor.id)) { "Habit stacks cannot form a cycle." }
                cursor = cursor.afterHabitId?.let { id -> all.firstOrNull { it.id == id } }
            }
        }
        if (existing == null) dao.insertHabit(normalized) else {
            dao.updateHabit(normalized)
            normalized.id
        }
    }

    suspend fun archiveHabit(id: Long, archived: Boolean) = db.withTransaction {
        val habit = dao.habit(id) ?: throw IllegalArgumentException("This habit no longer exists.")
        if (habit.isArchived == archived) return@withTransaction
        val today = LocalDate.now()
        dao.updateHabit(habit.copy(
            isArchived = archived, archivedOn = if (archived) today else null,
            updatedAt = nextTimestamp(System.currentTimeMillis(), habit.createdAt, habit.updatedAt)
        ))
        if (archived) dao.insertPause(HabitPause(habitId = id, startDate = today.plusDays(1)))
        else dao.habitPauses(id).filter { it.endDate == null }.forEach { pause ->
            if (pause.startDate >= today) dao.deletePause(pause.id)
            else dao.updatePause(pause.copy(endDate = today))
        }
    }

    suspend fun deleteHabit(id: Long) = db.withTransaction {
        require(dao.habit(id) != null) { "This habit has already been deleted." }
        dao.deleteHabit(id)
    }

    /** IDs may contain the visible active subset; archived IDs are appended without losing history. */
    suspend fun reorderHabits(ids: List<Long>) = db.withTransaction {
        require(ids.size == ids.toSet().size) { "A habit appears twice in this order." }
        val all = dao.allHabits()
        require(ids.all { id -> all.any { it.id == id } }) { "The habit list changed. Try ordering it again." }
        val order = ids + all.map { it.id }.filterNot(ids::contains)
        val now = System.currentTimeMillis()
        val byId = all.associateBy { it.id }
        order.forEachIndexed { index, id -> byId.getValue(id).let { habit -> dao.setSortOrder(id, index, nextTimestamp(now, habit.createdAt, habit.updatedAt)) } }
    }

    private suspend fun editableHabit(id: Long, date: LocalDate): Habit {
        val habit = dao.habit(id) ?: throw IllegalArgumentException("This habit no longer exists.")
        require(!habit.isArchived) { "Restore this archived habit before changing its check-ins." }
        require(date <= LocalDate.now()) { "Future check-ins are not allowed. Come back on that date." }
        require(date >= habit.startDate) { "Check-ins cannot be before this habit's start date." }
        when (com.lifetrack.app.domain.Scheduling.mode(habit, date)) {
            com.lifetrack.app.data.ScheduleMode.WEEKDAYS -> {
                val weekdays = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date }?.days
                    ?: habit.scheduleHistory.firstOrNull()?.days ?: habit.scheduledDays
                require(date.dayOfWeek.value in weekdays) { "This habit is not scheduled for that weekday." }
            }
            com.lifetrack.app.data.ScheduleMode.EVERY_N_DAYS -> require(
                com.lifetrack.app.domain.Scheduling.isScheduledOn(habit, date)
            ) { "This habit is only scheduled every ${com.lifetrack.app.domain.Scheduling.intervalDays(habit, date)} days — the next window opens later." }
            com.lifetrack.app.data.ScheduleMode.WEEKLY_QUOTA -> { } // any day may count toward the quota
        }
        require(dao.habitPauses(id).none { date >= it.startDate && (it.endDate == null || date < it.endDate) }) { "This date belongs to an archived pause. Choose an active day." }
        return habit.goalOn(date)
    }

    suspend fun setLog(habitId: Long, date: LocalDate, status: LogStatus, note: String? = null): Boolean = db.withTransaction {
        val habit = editableHabit(habitId, date)
        require(note == null || note.length <= 4_000) { "Notes are limited to 4,000 characters." }
        val existing = dao.log(habitId, date)
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val log = HabitLog(
            id = existing?.id ?: 0, habitId = habitId, date = date, status = status,
            note = note?.trim()?.takeIf { it.isNotEmpty() }, isFrozen = status == LogStatus.SKIPPED && existing?.isFrozen == true,
            createdAt = existing?.createdAt ?: now, updatedAt = now,
            progress = when (status) {
                LogStatus.COMPLETED -> habit.targetCount
                LogStatus.IN_PROGRESS -> (existing?.progress ?: 0).coerceAtMost(habit.targetCount - 1)
                else -> 0
            }
        )
        if (existing == null) dao.insertLog(log) else dao.updateLog(log)
        // Return the committed transition so duplicate rapid taps never emit duplicate success effects.
        status == LogStatus.COMPLETED && existing?.status != LogStatus.COMPLETED
    }

    suspend fun setProgress(habitId: Long, date: LocalDate, progress: Int, note: String? = null): Boolean = db.withTransaction {
        val habit = editableHabit(habitId, date)
        require(progress >= 0) { "Progress cannot be negative." }
        require(note == null || note.length <= 4_000) { "Notes are limited to 4,000 characters." }
        val existing = dao.log(habitId, date)
        val amount = progress.coerceAtMost(habit.targetCount)
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        val log = HabitLog(id = existing?.id ?: 0, habitId = habitId, date = date,
            status = if (amount >= habit.targetCount) LogStatus.COMPLETED else LogStatus.IN_PROGRESS,
            note = note?.trim()?.takeIf { it.isNotEmpty() }, progress = amount,
            createdAt = existing?.createdAt ?: now, updatedAt = now)
        if (existing == null) dao.insertLog(log) else dao.updateLog(log)
        log.status == LogStatus.COMPLETED && existing?.status != LogStatus.COMPLETED
    }

    /** Repeated taps and widget/editor races use the latest persisted amount and note. */
    suspend fun incrementProgress(habitId: Long, date: LocalDate, percent: Int? = null): Boolean = db.withTransaction {
        require(percent == null || percent in setOf(25, 50)) { "Choose an available progress step." }
        val habit = editableHabit(habitId, date)
        val existing = dao.log(habitId, date)
        if (existing?.status == LogStatus.COMPLETED) return@withTransaction false
        val step = percent?.let { (habit.targetCount * it + 99) / 100 } ?: 1
        setProgress(habitId, date, ((existing?.progress ?: 0) + step).coerceAtMost(habit.targetCount), existing?.note)
    }

    /** The receipt records exactly what changed, including protected skips, progress and notes. */
    suspend fun toggleCompletion(habitId: Long, date: LocalDate): CheckInUndo = db.withTransaction {
        val habit = editableHabit(habitId, date)
        val before = dao.log(habitId, date)
        if (before?.status == LogStatus.COMPLETED) {
            if (before.note != null || habit.targetCount > 1) {
                dao.updateLog(before.copy(status = LogStatus.IN_PROGRESS, progress = (habit.targetCount - 1).coerceAtLeast(0),
                    isFrozen = false, updatedAt = nextTimestamp(System.currentTimeMillis(), before.createdAt, before.updatedAt)))
            } else dao.deleteLog(habitId, date)
        } else setLog(habitId, date, LogStatus.COMPLETED, before?.note)
        CheckInUndo(before, dao.log(habitId, date), habitId, date, habit.createdAt)
    }

    suspend fun undoCheckIn(undo: CheckInUndo) = db.withTransaction {
        val habit = editableHabit(undo.habitId, undo.date)
        require(habit.createdAt == undo.habitCreatedAt && dao.log(undo.habitId, undo.date) == undo.after) {
            "This check-in changed again. Open the habit to review its latest entry."
        }
        when {
            undo.before == null -> dao.deleteLog(undo.habitId, undo.date)
            undo.after == null -> dao.insertLog(undo.before)
            else -> dao.updateLog(undo.before)
        }
        Unit
    }

    /** Widget completion preserves the latest note under the same transaction as its status update. */
    suspend fun completeHabit(habitId: Long, date: LocalDate, expectedCreatedAt: Long? = null): Boolean = db.withTransaction {
        val habit = editableHabit(habitId, date)
        require(expectedCreatedAt == null || habit.createdAt == expectedCreatedAt) {
            "This widget is out of date after a restore. Refresh it and try again."
        }
        val existing = dao.log(habitId, date)
        if (existing?.status == LogStatus.COMPLETED) false
        else setLog(habitId, date, LogStatus.COMPLETED, existing?.note)
    }

    suspend fun clearLog(habitId: Long, date: LocalDate) = db.withTransaction {
        editableHabit(habitId, date)
        dao.deleteLog(habitId, date)
        // A cleared check-in also clears its streak decision: re-entering a partial starts clean.
        dao.deleteStreakProtection(habitId, date)
    }

    /** Creates or updates the day's optional mood and/or journal. Historical dates are allowed;
     *  future dates are not. A call carrying neither a mood nor text is rejected — use [clearReflection]. */
    suspend fun setReflection(date: LocalDate, mood: Int?, journal: String?) = db.withTransaction {
        require(date >= BackupValidator.earliestDate && date <= LocalDate.now()) { "Reflections cannot be dated in the future or before 1900." }
        require(mood == null || mood in 1..5) { "Moods are recorded on a scale of 1 to 5." }
        val text = journal?.trim()?.takeIf { it.isNotEmpty() }
        require(text == null || text.length <= 2_000) { "Journals are limited to 2,000 characters." }
        require(mood != null || text != null) { "Add a mood or a few words about the day." }
        val existing = dao.reflection(date)
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        dao.upsertReflection(DailyReflection(
            date = date, mood = mood, journal = text,
            createdAt = existing?.createdAt ?: now, updatedAt = now
        ))
    }

    /** Removes the day's reflection entirely; idempotent for dates that never had one. */
    suspend fun clearReflection(date: LocalDate) = db.withTransaction {
        require(date >= BackupValidator.earliestDate && date <= LocalDate.now()) { "Reflections cannot be dated in the future or before 1900." }
        dao.deleteReflection(date)
    }

    /** Marks an achievement as acknowledged (its celebration was shown). Idempotent: the first
     *  acknowledgment time is the durable fact and is never overwritten. */
    suspend fun acknowledgeAchievement(achievementId: String) = db.withTransaction {
        require(achievementId.isNotBlank() && achievementId.length <= 100) { "Invalid achievement identifier." }
        if (dao.achievementAcknowledgment(achievementId) == null) {
            dao.upsertAchievementAcknowledgment(AchievementAcknowledgment(achievementId, nextTimestamp(System.currentTimeMillis())))
        }
    }

    /** One explicit decision per past partial day: bridge the completion chain or break it.
     *  Rows turn inert once the day completes, clears or fails — the log status stays the source of truth. */
    suspend fun setStreakProtection(habitId: Long, date: LocalDate, protectChain: Boolean) = db.withTransaction {
        val habit = dao.habit(habitId) ?: throw IllegalArgumentException("This habit no longer exists.")
        require(!habit.isArchived) { "Restore this archived habit before changing its check-ins." }
        require(date < LocalDate.now()) { "Streak decisions apply to past days. Today's check-in is still available." }
        require(date >= habit.startDate) { "Check-ins cannot be before this habit's start date." }
        val weekdays = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date }?.days ?: habit.scheduleHistory.firstOrNull()?.days ?: habit.scheduledDays
        require(date.dayOfWeek.value in weekdays) { "This habit is not scheduled for that weekday." }
        require(dao.habitPauses(habitId).none { date >= it.startDate && (it.endDate == null || date < it.endDate) }) { "This date belongs to an archived pause. Choose an active day." }
        val log = dao.log(habitId, date)
        require(log != null && log.status == LogStatus.IN_PROGRESS && log.progress > 0) { "Only a partially completed day can be protected or broken." }
        val existing = dao.streakProtection(habitId, date)
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        dao.insertStreakProtections(listOf(StreakProtection(
            habitId = habitId, date = date, protectChain = protectChain,
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )))
    }

    /** Removing a decision returns the day to the habit's automatic policy; deleting an absent row is fine. */
    suspend fun clearStreakProtection(habitId: Long, date: LocalDate) = db.withTransaction {
        val habit = dao.habit(habitId) ?: throw IllegalArgumentException("This habit no longer exists.")
        require(!habit.isArchived) { "Restore this archived habit before changing its check-ins." }
        dao.deleteStreakProtection(habitId, date)
    }

    suspend fun useFreeze(habitId: Long, date: LocalDate, note: String? = null) = db.withTransaction {
        val habit = editableHabit(habitId, date)
        // Weekly-quota habits are never due on a specific day: a freeze would be meaningless.
        if (com.lifetrack.app.domain.Scheduling.mode(habit, date) == com.lifetrack.app.data.ScheduleMode.WEEKLY_QUOTA) {
            throw IllegalArgumentException("Weekly-quota habits do not use streak freezes.")
        }
        require(date < LocalDate.now()) { "A freeze protects a past missed day. Today's check-in is still available." }
        require(note == null || note.length <= 4_000) { "Notes are limited to 4,000 characters." }
        val month = YearMonth.from(date).toString()
        require(dao.freeze(month) == null) { "Your freeze for $month has already been used." }
        val existing = dao.log(habitId, date)
        require(existing == null || existing.status == LogStatus.FAILED || existing.status == LogStatus.IN_PROGRESS) { "This date already protects your streak; choose a missed day." }
        val now = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        dao.insertFreeze(FreezeUsage(month, now))
        val frozen = HabitLog(
            id = existing?.id ?: 0, habitId = habitId, date = date, status = LogStatus.SKIPPED,
            isFrozen = true, note = if (note == null) existing?.note else note.trim().takeIf { it.isNotEmpty() },
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        if (existing == null) dao.insertLog(frozen) else dao.updateLog(frozen)
        Unit
    }

    suspend fun updateSettings(settings: AppSettings) {
        BackupValidator.validateSettings(settings)
        db.withTransaction {
            val current = dao.getSettings() ?: AppSettings()
            // A delayed theme-toggle state must not overwrite the successful backup timestamp.
            dao.saveSettings(settings.copy(lastExportAt = current.lastExportAt))
        }
    }

    suspend fun claimReminder(habitId: Long, date: LocalDate, expectedCreatedAt: Long? = null): Boolean = db.withTransaction {
        if (date != LocalDate.now()) return@withTransaction false
        val habit = dao.habit(habitId) ?: return@withTransaction false
        if (expectedCreatedAt != null && habit.createdAt != expectedCreatedAt) return@withTransaction false
        if (habit.isArchived || habit.reminderMinutes == null) return@withTransaction false
        // Weekly-quota habits get no daily "overdue" reminder: the user picks the days (Prompt 8 UX).
        if (com.lifetrack.app.data.ScheduleMode.fromStored(habit.scheduleMode) == com.lifetrack.app.data.ScheduleMode.WEEKLY_QUOTA) {
            return@withTransaction false
        }
        if (!com.lifetrack.app.domain.Analytics.eligible(habit, date, dao.habitPauses(habitId))) return@withTransaction false
        val localTime = java.time.LocalTime.now()
        if (localTime.hour * 60 + localTime.minute < habit.reminderMinutes) return@withTransaction false
        if (dao.log(habitId, date)?.status in setOf(LogStatus.COMPLETED, LogStatus.SKIPPED)) return@withTransaction false
        dao.insertReminderDelivery(ReminderDelivery(habitId, date, System.currentTimeMillis())) != -1L
    }

    /** A failed notification post can retry; a stale worker cannot release a replacement habit's claim. */
    suspend fun releaseReminderClaim(habitId: Long, date: LocalDate, expectedCreatedAt: Long? = null) = db.withTransaction {
        val habit = dao.habit(habitId) ?: return@withTransaction
        if (expectedCreatedAt != null && habit.createdAt != expectedCreatedAt) return@withTransaction
        dao.deleteReminderDelivery(habitId, date)
    }

    suspend fun exportTo(uri: Uri, password: CharArray? = null) = backupMutex.withLock { withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val snapshot = db.withTransaction {
            val habits = dao.allHabits()
            val activities = dao.allActivities()
            val streakProtections = dao.allStreakProtections()
            val reflections = dao.allReflections()
            val acknowledgments = dao.allAchievementAcknowledgments()
            LifeTrackBackup(
                formatVersion = when {
                    habits.any { com.lifetrack.app.domain.Scheduling.isConfigured(it) } -> 9
                    acknowledgments.isNotEmpty() -> 8
                    reflections.isNotEmpty() -> 7
                    streakProtections.isNotEmpty() || habits.any { com.lifetrack.app.domain.StreakPolicy.isConfigured(it) } -> 6
                    activities.any { it.isDirectional() } -> 5
                    habits.any { it.goalHistory.isNotEmpty() } -> 4
                    else -> 3
                },
                exportedAt = now, habits = habits, logs = dao.allLogs(),
                settings = (dao.getSettings() ?: AppSettings()).copy(lastExportAt = now, appLockEnabled = false),
                freezes = dao.allFreezes(), pauses = dao.allPauses(),
                activities = activities, activityEntries = dao.allActivityEntries(),
                streakProtections = streakProtections, dailyReflections = reflections,
                achievementAcknowledgments = acknowledgments
            )
        }
        BackupValidator.validate(snapshot)
        val bytes = BackupValidator.json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "This backup exceeds the 10 MB import limit. Export was not written." }
        val content = if (password == null || password.isEmpty()) bytes else BackupCrypto.encrypt(bytes, password)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("The selected location cannot be opened for writing.")
        output.use { stream -> stream.write(content); stream.flush() }
        db.withTransaction {
            if (dao.getSettings() == null) dao.saveSettings(AppSettings(lastExportAt = now))
            else dao.markExported(now)
        }
    } }

    /** Analysis copy for spreadsheets. JSON backups remain the only restorable format,
     *  so this reads one consistent transaction snapshot and never updates `lastExportAt`. */
    suspend fun exportCsvTo(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = readSnapshot()
        val csv = buildString { CsvExport.write(snapshot, this) }
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("The selected location cannot be opened for writing.")
        output.use { stream -> stream.write(csv.toByteArray(Charsets.UTF_8)); stream.flush() }
    }

    /** Replace, never merge: IDs, stack relationships, notes and the spent-freeze ledger survive exactly. */
    suspend fun importFrom(uri: Uri, password: CharArray? = null) = backupMutex.withLock { withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected backup cannot be opened.")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_ENCRYPTED_BACKUP_BYTES) { "Backup files must be 15 MB or smaller." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val plain = if (BackupCrypto.isEncrypted(bytes)) {
            require(password != null && password.isNotEmpty()) { "This backup is encrypted. Enter its password and choose the file again." }
            BackupCrypto.decrypt(bytes, password!!)
        } else bytes
        require(plain.size <= MAX_BACKUP_BYTES) { "Decrypted backups must be 10 MB or smaller." }
        val backup = try {
            val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
            BackupValidator.decode(utf8)
        } catch (exception: Exception) {
            throw IllegalArgumentException("This is not a valid LifeTrack JSON backup.", exception)
        }
        // Imported journals normalize like saved ones: trimmed, blank-turned-null before validation.
        val normalized = backup.copy(dailyReflections = backup.dailyReflections.map { reflection ->
            reflection.copy(journal = reflection.journal?.trim()?.takeIf { it.isNotEmpty() })
        })
        BackupValidator.validate(normalized)
        db.withTransaction {
            // Restoring must never silently weaken the local lock: the local flag survives every import.
            val keepAppLock = (dao.getSettings() ?: AppSettings()).appLockEnabled
            dao.deleteLogs()
            dao.deletePauses()
            dao.deleteReminderDeliveries()
            dao.deleteHabits()
            dao.deleteFreezes()
            dao.deleteSettings()
            dao.deleteAllActivityEntries()
            dao.deleteAllActivities()
            // Date-scoped rows have no habit FK to cascade them: reflections must be cleared explicitly.
            dao.deleteReflections()
            // Acknowledgments are device-state with no FK either; a restore replaces them wholesale.
            dao.deleteAchievementAcknowledgments()
            // Unknown ids (older/newer catalogue versions) are pruned — they could never display.
            val knownAchievementIds = com.lifetrack.app.domain.Achievements.catalogue.map { it.id }.toSet()
            val acknowledgmentsToRestore = backup.achievementAcknowledgments.filter { it.achievementId in knownAchievementIds }
            dao.insertHabits(backup.habits)
            dao.insertLogs(backup.logs)
            dao.insertPauses(backup.pauses)
            dao.insertFreezes(backup.freezes)
            dao.insertActivities(backup.activities)
            dao.insertActivityEntries(backup.activityEntries)
            dao.insertStreakProtections(backup.streakProtections)
            dao.insertReflections(normalized.dailyReflections)
            dao.insertAchievementAcknowledgments(acknowledgmentsToRestore)
            dao.saveSettings(backup.settings.copy(appLockEnabled = keepAppLock))
        }
    } }

    /** Erases every record and restores default settings in one transaction; the same cleanup importFrom uses. Safe to retry after interruption.
     *  Like a restore, this never silently weakens the local app lock: the flag survives the reset. */
    suspend fun resetAllData(): List<Long> = backupMutex.withLock { withContext(Dispatchers.IO) {
        db.withTransaction {
            val keepAppLock = (dao.getSettings() ?: AppSettings()).appLockEnabled
            val habitIds = dao.allHabits().map { it.id }
            dao.deleteLogs()
            dao.deletePauses()
            dao.deleteReminderDeliveries()
            dao.deleteHabits()
            dao.deleteFreezes()
            dao.deleteSettings()
            dao.deleteAllActivityEntries()
            dao.deleteAllActivities()
            dao.deleteReflections()
            // A reset restarts the achievement journey too: acknowledgments start fresh.
            dao.deleteAchievementAcknowledgments()
            dao.saveSettings(AppSettings().copy(appLockEnabled = keepAppLock))
            habitIds
        }
    } }

    companion object {
        const val MAX_BACKUP_BYTES = 10 * 1_024 * 1_024
        const val MAX_ENCRYPTED_BACKUP_BYTES = 15 * 1_024 * 1_024
    }
}
