package com.moodecho.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodecho.app.MindEchoApp
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.data.repository.RepositoryResult
import com.moodecho.app.service.DailyReportService
import com.moodecho.app.service.ReportResult
import com.moodecho.app.util.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.text.SimpleDateFormat
import java.util.Calendar
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
    val recentReports: List<DailyReport> = emptyList(),
    val initError: Boolean = false
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
     * Uses supervisorScope so that failures in one load don't cancel others.
     */
    fun loadForDate(date: String) {
        viewModelScope.launch {
            supervisorScope {
                try {
                    // Check API key status — use synchronous getter for latest value
                    val apiKey = preferenceManager.getDeepseekApiKey() ?: ""
                    _uiState.value = _uiState.value.copy(hasApiKey = apiKey.isNotBlank())

                    // Parse date string to epoch millis range for the query
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val parsedDate = dateFormat.parse(date)
                    var sessionCount = 0
                    if (parsedDate != null) {
                        val calendar = Calendar.getInstance()
                        calendar.time = parsedDate
                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        val dayStart = calendar.timeInMillis
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                        val nextDayStart = calendar.timeInMillis
                        val sessionsResult = repository.getSessionsByDate(dayStart, nextDayStart)
                        if (sessionsResult is RepositoryResult.Success) {
                            sessionCount = sessionsResult.data.size
                        }
                    }

                    // Fetch existing report for this date
                    val reportResult = repository.getReportByDate(date)
                    val report = if (reportResult is RepositoryResult.Success) reportResult.data else null

                    _uiState.value = _uiState.value.copy(
                        report = report,
                        sessionCount = sessionCount
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        initError = true,
                        errorMessage = "Failed to load data: ${e.message}"
                    )
                }

                // Load recent reports in parallel using stateIn to avoid endless collect
                launch {
                    try {
                        repository.getRecentReports(30)
                            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                            .collect { reports ->
                                _uiState.value = _uiState.value.copy(recentReports = reports)
                            }
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(
                            initError = true,
                            errorMessage = "Failed to load reports: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    /**
     * Load recent reports for the history list.
     * Uses stateIn to convert the Flow to a StateFlow, avoiding an endless collect coroutine.
     */
    fun loadRecentReports() {
        viewModelScope.launch {
            try {
                repository.getRecentReports(30)
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                    .collect { reports ->
                        _uiState.value = _uiState.value.copy(recentReports = reports)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    initError = true,
                    errorMessage = "Failed to load reports: ${e.message}"
                )
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

            try {
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Report generation failed: ${e.localizedMessage ?: "Unknown error"}"
                )
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
