package com.lifetrack.app

import android.app.Application
import android.util.Log
import com.lifetrack.app.data.HabitRepository
import com.lifetrack.app.reminder.BackupReminderScheduler
import com.lifetrack.app.reminder.HabitReminderScheduler
import com.lifetrack.app.widget.WidgetRefreshScheduler
import com.lifetrack.app.widget.refreshWidgets
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@HiltAndroidApp
class LifeTrackApplication : Application() {
    @Inject lateinit var repository: HabitRepository
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ ->
        Log.w("LifeTrack", "A local background task could not finish; it will retry when the app restarts")
    })

    override fun onCreate() {
        super.onCreate()
        WidgetRefreshScheduler.schedule(this)
        applicationScope.launch {
            repository.settings.map { it.backupReminder }.distinctUntilChanged().onEach {
                BackupReminderScheduler.sync(this@LifeTrackApplication, it)
            }.retryLocalWork().collect()
        }
        applicationScope.launch {
            repository.habits.map { habits -> habits.any { !it.isArchived && it.reminderMinutes != null } }.distinctUntilChanged().onEach {
                HabitReminderScheduler.sync(this@LifeTrackApplication, it)
            }.retryLocalWork().collect()
        }
        applicationScope.launch {
            combine(repository.habits, repository.logs, repository.pauses) { _, _, _ -> Unit }.onEach {
                try { refreshWidgets(this@LifeTrackApplication) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { Log.w("LifeTrack", "Widget refresh will retry on its next update") }
            }.retryLocalWork().collect()
        }
    }
}

/** A failed Room read or scheduler must not bypass the screen's recoverable error state. */
private fun <T> Flow<T>.retryLocalWork(): Flow<T> = retryWhen { error, attempt ->
    if (error is CancellationException) throw error
    Log.w("LifeTrack", "Local background work is waiting to retry")
    delay(minOf(30_000L, 1_000L * (attempt + 1).coerceAtMost(30)))
    true
}
