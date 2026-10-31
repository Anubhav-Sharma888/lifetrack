package com.lifetrack.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.AppSettings
import com.lifetrack.app.data.FreezeUsage
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitRepository
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.CheckInUndo
import com.lifetrack.app.reminder.DailyCheckInReminderScheduler
import com.lifetrack.app.reminder.DailyReminderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class LifeTrackState(
    val habits: List<Habit> = emptyList(),
    val logs: List<HabitLog> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val freezes: List<FreezeUsage> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
    val pauses: List<HabitPause> = emptyList(),
    val loadError: String? = null
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class CheckedIn(val habitId: Long, val day: Long) : UiEvent
    data class Undoable(val text: String, val token: Long) : UiEvent
}

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HabitViewModel @Inject constructor(
    private val repository: HabitRepository,
    private val dailyReminderStore: DailyReminderStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now())
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    private val mutationMutex = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError = _operationError.asStateFlow()
    val events = _events.asSharedFlow()
    private val reload = MutableStateFlow(0)
    private val undoHistory = linkedMapOf<Long, CheckInUndo>()
    private var undoSequence = 0L
    val state = reload.flatMapLatest { combine(repository.habits, repository.logs, repository.settings, repository.freezes, date) {
            habits, logs, settings, freezes, today ->
        LifeTrackState(habits, logs, settings, freezes, today, loaded = true)
    }.combine(repository.pauses) { current, pauses -> current.copy(pauses = pauses) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(LifeTrackState(loadError = "Your local data could not be opened. Retry or restart the app; do not clear storage."))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LifeTrackState())

    init {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                refreshToday()
                delay(30_000)
            }
        }
    }

    fun refreshToday() { date.value = LocalDate.now() }
    fun retryLoading() { reload.value++ }
    fun clearOperationError() { _operationError.value = null }

    private fun perform(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutationMutex.withLock {
                _operationError.value = null
                _busy.value = true
                try { block() }
                catch (exception: CancellationException) { throw exception }
                catch (exception: Exception) {
                    val message = when (exception) {
                        is IllegalArgumentException, is IllegalStateException -> exception.message ?: "The change could not be saved."
                        is java.io.IOException, is SecurityException -> "The file could not be accessed. Choose a writable location and try again."
                        else -> "The operation could not be completed. Please try again."
                    }
                    _operationError.value = message
                    _events.emit(UiEvent.Message(message))
                }
                finally { _busy.value = false }
            }
        }
    }

    fun saveHabit(habit: Habit, onSaved: (Long) -> Unit = {}) = perform {
        val id = repository.saveHabit(habit)
        _events.emit(UiEvent.Message("Habit saved"))
        onSaved(id)
    }

    fun archiveHabit(id: Long, archive: Boolean) = perform {
        repository.archiveHabit(id, archive)
        _events.emit(UiEvent.Message(if (archive) "Habit archived. Its history is safe." else "Habit restored"))
    }

    fun deleteHabit(id: Long, onDeleted: () -> Unit = {}) = perform {
        repository.deleteHabit(id)
        _events.emit(UiEvent.Message("Habit and its history deleted"))
        onDeleted()
    }

    fun reorderHabits(ids: List<Long>) = perform { repository.reorderHabits(ids) }

    fun setLog(id: Long, date: LocalDate, status: LogStatus, note: String? = null, onSaved: () -> Unit = {}) = perform {
        val newlyCompleted = repository.setLog(id, date, status, note)
        if (newlyCompleted) {
            val habit = state.value.habits.firstOrNull { it.id == id }
            val day = habit?.let { ChronoUnit.DAYS.between(it.startDate, date) + 1 } ?: 0
            _events.emit(UiEvent.CheckedIn(id, day))
        } else _events.emit(UiEvent.Message(if (status == LogStatus.SKIPPED) "Skipped. Your chain is protected." else "Check-in saved"))
        onSaved()
    }

    fun setProgress(id: Long, date: LocalDate, progress: Int, note: String? = null, onSaved: () -> Unit = {}) = perform {
        val newlyCompleted = repository.setProgress(id, date, progress, note)
        if (newlyCompleted) {
            val day = state.value.habits.firstOrNull { it.id == id }?.let { ChronoUnit.DAYS.between(it.startDate, date) + 1 } ?: 0
            _events.emit(UiEvent.CheckedIn(id, day))
        } else _events.emit(UiEvent.Message("Progress saved"))
        onSaved()
    }

    fun incrementProgress(id: Long, date: LocalDate) = perform {
        if (repository.incrementProgress(id, date)) {
            val day = state.value.habits.firstOrNull { it.id == id }?.let { ChronoUnit.DAYS.between(it.startDate, date) + 1 } ?: 0
            _events.emit(UiEvent.CheckedIn(id, day))
        } else _events.emit(UiEvent.Message("Progress saved"))
    }

    fun quickCheckIn(id: Long, date: LocalDate) = perform {
        val undo = repository.toggleCompletion(id, date)
        val token = ++undoSequence
        undoHistory[token] = undo
        while (undoHistory.size > 20) undoHistory.remove(undoHistory.keys.first())
        val completed = undo.after?.status == LogStatus.COMPLETED
        if (completed && undo.before?.status != LogStatus.COMPLETED) {
            val day = state.value.habits.firstOrNull { it.id == id }?.let { ChronoUnit.DAYS.between(it.startDate, date) + 1 } ?: 0
            _events.emit(UiEvent.CheckedIn(id, day))
        }
        _events.emit(UiEvent.Undoable(if (completed) "Check-in saved" else "Completion undone; your note is safe", token))
    }

    fun undoCheckIn(token: Long) = perform {
        val receipt = undoHistory[token] ?: throw IllegalArgumentException("This undo is no longer available.")
        repository.undoCheckIn(receipt)
        undoHistory.remove(token)
        _events.emit(UiEvent.Message("Previous check-in restored"))
    }

    fun clearLog(id: Long, date: LocalDate, onSaved: () -> Unit = {}) = perform {
        repository.clearLog(id, date)
        _events.emit(UiEvent.Message("Check-in cleared"))
        onSaved()
    }

    fun freeze(id: Long, date: LocalDate, note: String? = null, onSaved: () -> Unit = {}) = perform {
        repository.useFreeze(id, date, note)
        _events.emit(UiEvent.Message("Streak protected with this month's freeze"))
        onSaved()
    }

    fun updateSettings(settings: AppSettings) = perform { repository.updateSettings(settings) }

    val dailyReminderMinutes: StateFlow<Int?> = dailyReminderStore.minutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setDailyReminder(minutesOfDay: Int?) = perform {
        dailyReminderStore.set(minutesOfDay)
        DailyCheckInReminderScheduler.sync(appContext, minutesOfDay)
        _events.emit(UiEvent.Message(if (minutesOfDay == null) "Daily reminder turned off" else "Daily reminder scheduled"))
    }

    fun export(uri: Uri, password: CharArray? = null, onSaved: () -> Unit = {}) = perform {
        try {
            repository.exportTo(uri, password)
            _events.emit(UiEvent.Message("Backup exported. Keep a copy somewhere safe."))
            onSaved()
        } finally { password?.fill('\u0000') }
    }
    fun import(uri: Uri, password: CharArray? = null, onSaved: () -> Unit = {}) = perform {
        try {
            repository.importFrom(uri, password)
            undoHistory.clear()
            _events.emit(UiEvent.Message("Backup restored successfully"))
            onSaved()
        } finally { password?.fill('\u0000') }
    }
}
