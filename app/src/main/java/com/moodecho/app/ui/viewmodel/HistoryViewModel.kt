package com.moodecho.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodecho.app.MindEchoApp
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.domain.model.EmotionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * UI state for the History screen.
 */
data class HistoryUiState(
    val sessions: List<RecordingSession> = emptyList(),
    val dominantEmotions: Map<Long, EmotionType> = emptyMap(),
    val isLoading: Boolean = true
)

/**
 * ViewModel for the History screen.
 * Loads all recording sessions and their dominant emotions for display.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RecordingRepository

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        val app = application as MindEchoApp
        val db = app.database
        repository = RecordingRepository(
            sessionDao = db.recordingSessionDao(),
            transcriptDao = db.transcriptEntryDao(),
            emotionDao = db.emotionDataPointDao(),
            reportDao = db.dailyReportDao(),
            reportSessionDao = db.dailyReportSessionDao()
        )
        loadSessions()
    }

    /**
     * Observe all sessions and compute dominant emotions for each.
     */
    private fun loadSessions() {
        viewModelScope.launch {
            repository.getAllSessions().collectLatest { sessions ->
                // Compute dominant emotion for each session concurrently using async
                val emotionMap = mutableMapOf<Long, EmotionType>()
                val deferredList = sessions.map { session ->
                    async {
                        session.id to repository.getDominantEmotion(session.id)
                    }
                }
                deferredList.awaitAll().forEach { (id, dominant) ->
                    if (dominant != null) {
                        emotionMap[id] = dominant
                    }
                }
                _uiState.value = HistoryUiState(
                    sessions = sessions,
                    dominantEmotions = emotionMap,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Delete a session by ID.
     */
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }
}
