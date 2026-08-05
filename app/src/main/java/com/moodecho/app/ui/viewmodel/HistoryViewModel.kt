package com.moodecho.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodecho.app.MindEchoApp
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.domain.model.EmotionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            reportDao = db.dailyReportDao()
        )
        loadSessions()
    }

    /**
     * Observe all sessions and compute dominant emotions for each.
     *
     * 【P0 修复】修复 async 作用域错误：
     * 原代码中 async 使用外部 viewModelScope.launch 的 CoroutineScope 接收者，
     * 当 collectLatest 取消内部协程时，async 任务未被取消，积累导致崩溃。
     * 修复：用 coroutineScope { } 包裹，确保 async 任务被正确限定作用域；
     * 同时指定 Dispatchers.IO 避免数据库查询在主线程运行。
     */
    private fun loadSessions() {
        viewModelScope.launch {
            repository.getAllSessions().collectLatest { sessions ->
                val emotionMap = coroutineScope {
                    val deferredList = sessions.map { session ->
                        async(Dispatchers.IO) {
                            session.id to repository.getDominantEmotion(session.id)
                        }
                    }
                    val map = mutableMapOf<Long, EmotionType>()
                    deferredList.awaitAll().forEach { (id, dominant) ->
                        if (dominant != null) {
                            map[id] = dominant
                        }
                    }
                    map
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
