package com.lifetrack.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.GoalSession
import com.lifetrack.app.data.GoalSessionStatus
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.SessionRepository
import com.lifetrack.app.data.SessionRecoveryRepository
import com.lifetrack.app.reminder.SessionReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a session stands at read time — awaiting review is derived, never stored. */
enum class SessionBucket { UPCOMING, IN_PROGRESS, AWAITING_REVIEW, COMPLETED }

/** One row of the sessions overview: the session plus its derived bucket and display names. */
data class SessionCardState(
    val session: GoalSession,
    val bucket: SessionBucket,
    val goalTitle: String?,
    val taskTitle: String?
)

data class SessionsOverviewState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val upcoming: List<SessionCardState> = emptyList(),
    val awaitingReview: List<SessionCardState> = emptyList(),
    val inProgress: List<SessionCardState> = emptyList(),
    val history: List<SessionCardState> = emptyList()
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val recovery: SessionRecoveryRepository,
    private val goalRepository: GoalRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError = _operationError.asStateFlow()

    // ---- Overview (bounded reads: upcoming is capped, history takes a fixed window) ----

    private val overviewRefresh = MutableStateFlow(0)
    private val _overview = MutableStateFlow(SessionsOverviewState())
    val overview = _overview.asStateFlow()

    init {
        viewModelScope.launch {
            // Startup reconciliation: the schedule follows the committed Room state.
            try { SessionReminderScheduler.reconcileAll(context, repository) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The next lifecycle event reconciles again. */ }
            refreshOverview()
        }
    }

    /** Event-driven reconciliation after any session data change: bounded upcoming list only. */
    private suspend fun reconcile() {
        try { SessionReminderScheduler.reconcileAll(context, repository) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Workers re-validate at delivery; the next change retries. */ }
        refreshOverview()
    }

    suspend fun refreshOverview() {
        val today = LocalDate.now()
        // The overview reads a bounded snapshot of ALL sessions: past unconfirmed sessions are
        // never in the upcoming list, but they still owe the user a review.
        val all = runCatching { repository.overviewSnapshot() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val goalTitles = runCatching { goalRepository.goalsSnapshot().associate { it.id to it.title } }.getOrDefault(emptyMap())
        val taskTitles = runCatching { goalRepository.tasksSnapshot().associate { it.id to it.title } }.getOrDefault(emptyMap())
        val card = { session: GoalSession ->
            val state = GoalSessionStatus.fromStored(session.status)
            val bucket = when {
                state == GoalSessionStatus.IN_PROGRESS -> SessionBucket.IN_PROGRESS
                repository.awaitingReview(session, now) -> SessionBucket.AWAITING_REVIEW
                state == GoalSessionStatus.COMPLETED || state == GoalSessionStatus.PARTIAL || state == GoalSessionStatus.SKIPPED -> SessionBucket.COMPLETED
                else -> SessionBucket.UPCOMING
            }
            SessionCardState(
                session = session, bucket = bucket,
                goalTitle = session.goalId?.let { goalTitles[it] },
                taskTitle = session.taskId?.let { taskTitles[it] }
            )
        }
        // Canceled sessions are planning decisions, not work records: the overview keeps the
        // four honest buckets above and leaves canceled rows to their detail screen.
        val cards = all.filter { GoalSessionStatus.fromStored(it.status) != GoalSessionStatus.CANCELED }.map(card)
        recoveryCandidates.value = runCatching { recovery.recoveryCandidates(today) }.getOrDefault(emptyList())
        val history = repository.recentHistory(HISTORY_WINDOW).map(card)
        _overview.value = SessionsOverviewState(
            loaded = true, today = today,
            upcoming = cards.filter { it.bucket == SessionBucket.UPCOMING },
            inProgress = cards.filter { it.bucket == SessionBucket.IN_PROGRESS },
            awaitingReview = cards.filter { it.bucket == SessionBucket.AWAITING_REVIEW },
            history = history.filter { it.bucket == SessionBucket.COMPLETED }
        )
    }

    /** Recovery suggestions: bounded unreviewed/partial list, refreshed with the overview. */
    val recoveryCandidates = MutableStateFlow<List<GoalSession>>(emptyList())

    /** Force a refresh (called on ON_RESUME so elapsed sessions move to awaiting review). */
    fun refresh() {
        overviewRefresh.value++
        viewModelScope.launch { runCatching { refreshOverview() } }
    }

    // ---- Detail (hot, resilient — the same architecture as the goal detail) ----

    private val selectedSessionUuid = MutableStateFlow<String?>(null)

    fun selectSession(uuid: String) { selectedSessionUuid.value = uuid }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    val detail: StateFlow<GoalSession?> = selectedSessionUuid.flatMapLatest { uuid ->
        if (uuid == null) flowOf(null)
        else repository.observeSession(uuid)
            .onStart { emit(null) }
            .retryWhen { error, attempt ->
                if (error is CancellationException) throw error
                delay(minOf(5_000L, 100L * (attempt + 1)))
                true
            }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(null)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Reference data for the editor ----

    fun observeGoals() = goalRepository.goals

    /** One canonical session read for the editor's prefill. */
    suspend fun findSession(uuid: String): GoalSession? = repository.sessionByUuid(uuid)
    fun phasesForGoal(goalId: Long) = goalRepository.phases(goalId)
    fun tasksForGoal(goalId: Long) = goalRepository.tasks(goalId)

    // ---- Mutations (busy-gated; every success reconciles reminders) ----

    private fun launchOperation(operation: suspend () -> String, onSuccess: suspend () -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        _operationError.value = null
        viewModelScope.launch {
            try {
                val message = operation()
                reconcile()
                _events.emit(UiEvent.Message(message))
                onSuccess()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _operationError.value = error.message ?: "The session could not be saved."
            } finally {
                _busy.value = false
            }
        }
    }

    fun saveSession(session: GoalSession, onSaved: (GoalSession) -> Unit = {}) {
        var saved = session
        launchOperation(operation = {
            saved = repository.save(session)
            if (session.id == 0L) "Session scheduled" else "Session saved"
        }, onSuccess = { onSaved(saved) })
    }

    fun startSession(uuid: String) = launchOperation(operation = {
        repository.start(uuid)
        "Session started"
    })

    fun confirmCompletion(uuid: String) = launchOperation(operation = {
        repository.confirmCompletion(uuid)
        "Session completed"
    })

    fun cancelSession(uuid: String) = launchOperation(operation = {
        repository.cancel(uuid)
        "Session canceled"
    })

    fun deleteSession(uuid: String) = launchOperation(operation = {
        repository.delete(uuid)
        "Session deleted"
    })

    /** Read-time "awaiting review" for the detail screen — derived, never stored. */
    fun isAwaitingReview(session: GoalSession, now: Long = System.currentTimeMillis()): Boolean =
        repository.awaitingReview(session, now)

    /** Explicit partial outcome (Prompt 6): minutes actually spent, honestly below the plan. */
    fun recordPartial(uuid: String, reportedMinutes: Int) = launchOperation(operation = {
        recovery.confirmOutcome(uuid, GoalSessionStatus.PARTIAL, reportedMinutes = reportedMinutes)
        "Partial session recorded"
    })

    /** Deliberate skip: a user decision with no work evidence. */
    fun skipSession(uuid: String) = launchOperation(operation = {
        recovery.confirmOutcome(uuid, GoalSessionStatus.SKIPPED)
        "Session skipped"
    })

    /** Manual completed check-in: reported minutes without a fabricated start. */
    fun confirmCompletedReported(uuid: String, reportedMinutes: Int) = launchOperation(operation = {
        recovery.confirmOutcome(uuid, GoalSessionStatus.COMPLETED, reportedMinutes = reportedMinutes)
        "Session completed"
    })

    /** Auditable reschedule: the original plan is preserved on the row. */
    fun rescheduleSession(uuid: String, newStart: LocalDate, newStartTime: LocalTime,
                          newEnd: LocalDate, newEndTime: LocalTime) = launchOperation(operation = {
        recovery.reschedule(uuid, newStart, newStartTime, newEnd, newEndTime)
        "Session rescheduled"
    })

    /** Bounded end check-in snooze. */
    fun snoozeCheckIn(uuid: String, minutes: Int) = launchOperation(operation = {
        val until = System.currentTimeMillis() + minutes * 60_000L
        recovery.snoozeCheckIn(uuid, untilEpochMillis = until)
        "Check-in snoozed $minutes minutes"
    })

    fun clearOperationError() { _operationError.value = null }

    companion object {
        /** Bounded completed-history window for the overview. */
        const val HISTORY_WINDOW = 60
    }
}
