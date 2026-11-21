package com.lifetrack.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.GoalPlanDecision
import com.lifetrack.app.data.GoalPlanImportException
import com.lifetrack.app.data.GoalPlanImportFileReader
import com.lifetrack.app.data.GoalPlanImportPreview
import com.lifetrack.app.data.GoalPlanImportResult
import com.lifetrack.app.data.GoalPlanImportService
import com.lifetrack.app.data.GoalPlanParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where the import flow stands. Discrete phases keep the screen honest: nothing renders a
 *  success that did not happen, and nothing mutates data outside [Phase.READY] confirmation. */
enum class GoalPlanImportPhase { IDLE, READING, READY, IMPORTING, DONE, FAILED }

/**
 * Goal Plan import state. The parsed document and preview live only in this ViewModel (never in
 * SavedStateHandle): a process death returns the flow to [GoalPlanImportPhase.IDLE] with zero
 * writes, which is the safe direction for a transient import. Decisions survive recomposition
 * because they live here, and every confirm is busy-gated so a double tap cannot double-import.
 */
data class GoalPlanImportUiState(
    val phase: GoalPlanImportPhase = GoalPlanImportPhase.IDLE,
    val fileName: String? = null,
    val preview: GoalPlanImportPreview? = null,
    val decisions: Map<String, GoalPlanDecision> = emptyMap(),
    val summary: com.lifetrack.app.data.GoalPlanImportSummary? = null,
    val result: GoalPlanImportResult? = null,
    val error: String? = null
)

@HiltViewModel
class GoalPlanImportViewModel @Inject constructor(
    private val service: GoalPlanImportService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(GoalPlanImportUiState())
    val state = _state.asStateFlow()

    /** Reads, parses and classifies the chosen document. No database writes happen here. */
    fun load(uri: Uri, fileName: String?) {
        if (_state.value.phase == GoalPlanImportPhase.READING || _state.value.phase == GoalPlanImportPhase.IMPORTING) return
        _state.value = GoalPlanImportUiState(phase = GoalPlanImportPhase.READING, fileName = fileName)
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val text = GoalPlanImportFileReader.read {
                        try { context.contentResolver.openInputStream(uri) } catch (error: Exception) { null }
                    }
                    val document = try {
                        GoalPlanParser.parse(text)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // A whole-app backup is a different document type: sniffing its envelope
                        // key only matters when parsing failed, so a valid plan that merely
                        // mentions the key in prose is never misrouted.
                        if (GoalPlanImportFileReader.looksLikeLifeTrackBackup(text)) {
                            throw GoalPlanImportException(
                                "This file is a LifeTrack backup, not a Goal Plan. Backups restore your whole " +
                                    "app through Settings → Backup & restore; a Goal Plan only adds or updates one goal."
                            )
                        }
                        throw error
                    }
                    service.buildPreview(document)
                }
                val decisions = preview.changes.associate { it.uuid to it.defaultDecision }
                val summary = service.summarize(preview, decisions)
                _state.value = GoalPlanImportUiState(
                    phase = GoalPlanImportPhase.READY, fileName = fileName,
                    preview = preview, decisions = decisions, summary = summary
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = GoalPlanImportUiState(
                    phase = GoalPlanImportPhase.FAILED, fileName = fileName,
                    error = error.message ?: "The selected file could not be imported."
                )
            }
        }
    }

    /** Changes one record's decision; illegal choices never reach this path (the UI restricts),
     *  and a stale illegal choice still fails closed at summarize. While an import runs the
     *  applied decision set is frozen: taps are ignored so the state always describes what is
     *  actually being applied. */
    fun setDecision(uuid: String, decision: GoalPlanDecision) {
        val current = _state.value
        if (current.phase == GoalPlanImportPhase.IMPORTING || current.phase == GoalPlanImportPhase.READING) return
        val preview = current.preview ?: return
        val change = preview.changes.firstOrNull { it.uuid == uuid } ?: return
        if (decision !in change.allowedDecisions) return
        val decisions = current.decisions + (uuid to decision)
        val summary = service.summarize(preview, decisions)
        _state.value = current.copy(decisions = decisions, summary = summary)
    }

    /** Rebuilds the preview against the live database: the answer to a stale-preview failure or
     *  any "refresh conflicts" request. Reuses the already-parsed document — no file re-read. */
    fun refresh() {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.phase == GoalPlanImportPhase.READING || current.phase == GoalPlanImportPhase.IMPORTING) return
        _state.value = current.copy(phase = GoalPlanImportPhase.READING)
        viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    service.buildPreview(preview.document)
                }
                val decisions = current.decisions.filterKeys { uuid -> fresh.changes.any { it.uuid == uuid } }
                val defaults = fresh.changes.associate { it.uuid to it.defaultDecision }
                val merged = defaults + decisions
                val summary = service.summarize(fresh, merged)
                _state.value = current.copy(
                    phase = GoalPlanImportPhase.READY, preview = fresh, decisions = merged, summary = summary
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = current.copy(
                    phase = GoalPlanImportPhase.FAILED,
                    error = error.message ?: "The import preview could not be refreshed."
                )
            }
        }
    }

    /** The one write path: applies the confirmed decisions transactionally. Double taps are
     *  ignored while an import runs; a failure keeps the preview so the user can act on it. */
    fun confirmImport() {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.phase != GoalPlanImportPhase.READY) return
        val summary = current.summary ?: return
        if (summary.blocked.isNotEmpty()) return
        _state.value = current.copy(phase = GoalPlanImportPhase.IMPORTING, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    service.apply(preview.document, current.decisions, preview)
                }
                _state.value = GoalPlanImportUiState(
                    phase = GoalPlanImportPhase.DONE, fileName = current.fileName, result = result
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = current.copy(
                    phase = GoalPlanImportPhase.FAILED,
                    error = error.message ?: "The import could not be completed. Your data is unchanged."
                )
            }
        }
    }

    /** Abandons the flow and releases the parsed document: canceled previews write nothing. */
    fun reset() {
        _state.value = GoalPlanImportUiState()
    }

    /** Clears a failure but keeps the preview when one exists (Return to preview). */
    fun dismissError() {
        val current = _state.value
        if (current.phase != GoalPlanImportPhase.FAILED) return
        _state.value = if (current.preview != null) {
            current.copy(phase = GoalPlanImportPhase.READY, error = null)
        } else {
            GoalPlanImportUiState()
        }
    }
}
