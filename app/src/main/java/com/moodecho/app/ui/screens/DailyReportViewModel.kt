package com.moodecho.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodecho.app.MindEchoApp
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.service.DailyReportService
import com.moodecho.app.service.ReportResult
import com.moodecho.app.util.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI state for the daily report screen.
 */
data class DailyReportUiState(
    val isLoading: Boolean = false,
    val report: DailyReport? = null,
    val errorMessage: String? = null,
    val hasApiKey: Boolean = false,
    val sessionCount: Int = 0,
    val recentReports: List<DailyReport> = emptyList()
)

/**
 * ViewModel for the daily report screen.
 *
 * Manages report generation, loading states, and API key status.
 * Uses AndroidViewModel to access the application context for
 * database and preference access.
 */
class DailyReportViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val app = application as MindEchoApp
    private val db = app.database
    private val repository = RecordingRepository(
        sessionDao = db.recordingSessionDao(),
        transcriptDao = db.transcriptEntryDao(),
        emotionDao = db.emotionDataPointDao(),
        reportDao = db.dailyReportDao()
    )
    private val preferenceManager = PreferenceManager(application)

    private val _uiState = MutableStateFlow(DailyReportUiState())
    val uiState: StateFlow<DailyReportUiState> = _uiState.asStateFlow()

    /**
     * Load data for a specific date: check API key, fetch existing report,
     * count sessions, and load recent reports.
     */
    fun loadForDate(date: String) {
        viewModelScope.launch {
            // Check API key status
            val apiKey = preferenceManager.deepseekApiKey.first() ?: ""
            _uiState.value = _uiState.value.copy(hasApiKey = apiKey.isNotBlank())

            // Fetch existing report for this date
            val report = repository.getReportByDate(date)

            // Count sessions for this date
            val sessions = repository.getSessionsByDate(date)

            _uiState.value = _uiState.value.copy(
                report = report,
                sessionCount = sessions.size
            )
        }
    }

    /**
     * Load recent reports for the history list.
     */
    fun loadRecentReports() {
        viewModelScope.launch {
            repository.getRecentReports(30).collect { reports ->
                _uiState.value = _uiState.value.copy(recentReports = reports)
            }
        }
    }

    /**
     * Generate a daily report for the specified date.
     * Calls the DeepSeek API via DailyReportService.
     */
    fun generateReport(date: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null
            )

            val result = DailyReportService.generateReport(getApplication(), date)

            when (result) {
                is ReportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        report = result.report,
                        errorMessage = null
                    )
                }
                is ReportResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                is ReportResult.NoData -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "No recording data found for this date. " +
                                "Start recording to generate a report."
                    )
                }
                is ReportResult.NoApiKey -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        hasApiKey = false,
                        errorMessage = null
                    )
                }
            }
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    companion object {
        /**
         * Get today's date string in "yyyy-MM-dd" format.
         */
        fun getTodayDate(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            return sdf.format(Date())
        }
    }
}
