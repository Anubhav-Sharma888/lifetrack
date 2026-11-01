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

    /** Actual quantities are recorded exactly as performed; targets never clamp them. */
    suspend fun saveActivity(activity: WorkoutActivity): WorkoutActivity = db.withTransaction {
        require(activity.name.isNotBlank() && activity.name.length <= 80) { "Activity names must contain 1–80 characters." }
        require(activity.icon.isNotBlank() && activity.icon.length <= 32) { "Choose an emoji or short icon for the activity." }
        require(activity.unit.isNotBlank() && activity.unit.length <= 24) { "Activity units must contain 1–24 characters." }
        require(activity.target == null || activity.target > 0 && activity.target <= 1_000_000.0) { "Activity targets must be between 0 and 1,000,000." }
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
        require(dao.activity(activityId) != null) { "This activity no longer exists." }
        require(quantity.isFinite() && quantity > 0 && quantity <= 1_000_000.0) { "Activity quantities must be between 0 and 1,000,000." }
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
        val all = dao.allHabits()
        val days = habit.scheduledDays.distinct().sorted()
        val history = if (existing != null && existing.scheduledDays != days) {
            val earlier = existing.scheduleHistory.ifEmpty { listOf(ScheduleRevision(existing.startDate, existing.scheduledDays)) }
            earlier.filter { it.effectiveFrom < today } + ScheduleRevision(today, days)
        } else existing?.scheduleHistory ?: habit.scheduleHistory
        val normalized = habit.copy(
            name = habit.name.trim(), description = habit.description?.trim()?.takeIf { it.isNotEmpty() },
            category = habit.category.trim(), colorHex = habit.colorHex.uppercase(),
            targetUnit = habit.targetUnit.trim(), scheduledDays = days, scheduleHistory = history,
            // Archiving has its own operation so editing a form cannot change historical eligibility.
            isArchived = existing?.isArchived ?: false,
            archivedOn = existing?.archivedOn,
            sortOrder = existing?.sortOrder ?: ((all.maxOfOrNull { it.sortOrder } ?: -1) + 1),
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        BackupValidator.validateHabit(normalized, today)
        if (existing != null) {
            val history = dao.habitLogs(existing.id)
            val first = history.minOfOrNull { it.date }
            require(first == null || normalized.startDate <= first) { "The start date cannot move after an existing check-in. Clear those entries first." }
            require(history.isEmpty() || normalized.targetCount == existing.targetCount && normalized.targetUnit == existing.targetUnit) {
                "Existing check-ins keep their original goal. Create a new habit to change its target or unit."
            }
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
        val weekdays = habit.scheduleHistory.lastOrNull { it.effectiveFrom <= date }?.days ?: habit.scheduleHistory.firstOrNull()?.days ?: habit.scheduledDays
        require(date.dayOfWeek.value in weekdays) { "This habit is not scheduled for that weekday." }
        require(dao.habitPauses(id).none { date >= it.startDate && (it.endDate == null || date < it.endDate) }) { "This date belongs to an archived pause. Choose an active day." }
        return habit
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
    suspend fun incrementProgress(habitId: Long, date: LocalDate): Boolean = db.withTransaction {
        val habit = editableHabit(habitId, date)
        val existing = dao.log(habitId, date)
        if (existing?.status == LogStatus.COMPLETED) return@withTransaction false
        setProgress(habitId, date, ((existing?.progress ?: 0) + 1).coerceAtMost(habit.targetCount), existing?.note)
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
    }

    suspend fun useFreeze(habitId: Long, date: LocalDate, note: String? = null) = db.withTransaction {
        editableHabit(habitId, date)
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
        if (habit.isArchived || habit.reminderMinutes == null || !com.lifetrack.app.domain.Analytics.eligible(habit, date, dao.habitPauses(habitId))) return@withTransaction false
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
            LifeTrackBackup(
                exportedAt = now, habits = dao.allHabits(), logs = dao.allLogs(),
                settings = (dao.getSettings() ?: AppSettings()).copy(lastExportAt = now),
                freezes = dao.allFreezes(), pauses = dao.allPauses(),
                activities = dao.allActivities(), activityEntries = dao.allActivityEntries()
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
        BackupValidator.validate(backup)
        db.withTransaction {
            dao.deleteLogs()
            dao.deletePauses()
            dao.deleteReminderDeliveries()
            dao.deleteHabits()
            dao.deleteFreezes()
            dao.deleteSettings()
            dao.deleteAllActivityEntries()
            dao.deleteAllActivities()
            dao.insertHabits(backup.habits)
            dao.insertLogs(backup.logs)
            dao.insertPauses(backup.pauses)
            dao.insertFreezes(backup.freezes)
            dao.insertActivities(backup.activities)
            dao.insertActivityEntries(backup.activityEntries)
            dao.saveSettings(backup.settings)
        }
    } }

    companion object {
        const val MAX_BACKUP_BYTES = 10 * 1_024 * 1_024
        const val MAX_ENCRYPTED_BACKUP_BYTES = 15 * 1_024 * 1_024
    }
}
