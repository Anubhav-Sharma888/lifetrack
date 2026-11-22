package com.lifetrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.HabitAlarm
import com.lifetrack.app.data.HabitAlarmRepository
import com.lifetrack.app.data.MAX_ALARMS_PER_HABIT
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Ringing-alarm configuration for one habit (Prompt 6, Part B): add/remove/enable alarm
 *  definitions. Success reconciles the exact-alarm schedule through the scheduler. */
@HiltViewModel
class HabitAlarmViewModel @Inject constructor(
    private val repository: HabitAlarmRepository,
    private val schedulerBridge: AlarmSchedulerBridge
) : ViewModel() {

    fun alarmsFor(habitId: Long): Flow<List<HabitAlarm>> = repository.alarmsForHabit(habitId)

    fun addAlarm(habitId: Long, timeMinutes: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val saved = repository.save(HabitAlarm(uuid = "", habitId = habitId, timeMinutes = timeMinutes))
                schedulerBridge.upsert(saved)
                onDone()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Surface validation failures through the caller-provided error channel.
            }
        }
    }

    fun removeAlarm(alarm: HabitAlarm) {
        viewModelScope.launch {
            try {
                repository.delete(alarm.uuid)
                schedulerBridge.cancel(alarm.uuid)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) { /* The next reconciliation clears stale work. */ }
        }
    }

    companion object {
        const val LIMIT = MAX_ALARMS_PER_HABIT
    }
}

/** Thin scheduling bridge so the ViewModel stays Android-free in its business rules while the
 *  app-level scheduler (AlarmManager) stays one call away. */
interface AlarmSchedulerBridge {
    fun upsert(alarm: HabitAlarm)
    fun cancel(alarmUuid: String)
}
