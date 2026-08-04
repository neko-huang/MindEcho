package com.moodecho.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moodecho.app.MindEchoApp
import com.moodecho.app.data.api.ChatCompletionRequest
import com.moodecho.app.data.api.ChatMessage
import com.moodecho.app.data.api.LlmApi
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.TranscriptEntry
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.data.repository.RepositoryResult
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.util.Constants
import com.moodecho.app.util.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * UI state holder for the Session Detail screen.
 * Contains all the data needed to render a single recording session.
 */
data class SessionDetailUiState(
    val isLoading: Boolean = true,
    val session: RecordingSession? = null,
    val transcriptEntries: List<TranscriptEntry> = emptyList(),
    val emotionDataPoints: List<EmotionDataPoint> = emptyList(),
    val dominantEmotion: EmotionType? = null,
    val emotionDistribution: Map<EmotionType, Float> = emptyMap(),
    val summary: String? = null,
    val isGeneratingSummary: Boolean = false,
    val errorMessage: String? = null
) {
    /** Format the session start time as a readable date string */
    val formattedDate: String
        get() = session?.let {
            SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault()).format(Date(it.startTime))
        } ?: ""

    /** Format the duration as MM:SS */
    val formattedDuration: String
        get() = session?.let {
            val totalSeconds = it.duration / 1000
            "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        } ?: "--:--"

    /** Human-readable file size of the recording */
    val formattedFileSize: String
        get() = session?.let {
            val file = File(it.audioFilePath)
            if (file.exists()) {
                val bytes = file.length()
                when {
                    bytes < 1024 -> "${bytes} B"
                    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
                    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
                }
            } else {
                "Unknown"
            }
        } ?: "Unknown"
}

/**
 * ViewModel for the Session Detail screen.
 * Loads a recording session and its associated data (transcripts, emotions),
 * and supports AI summary generation via DeepSeek.
 *
 * @param application The application instance for accessing the database
 */
class SessionDetailViewModel(
    application: Application,
    private val sessionId: Long
) : AndroidViewModel(application) {

    private val repository: RecordingRepository
    private val preferenceManager: PreferenceManager

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    /** Lazily create OkHttpClient to avoid creating new instances on every generateSummary() call */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Lazily create Retrofit instance — client is shared across all generateSummary() calls */
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/") // placeholder, overridden in generateSummary
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

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
        preferenceManager = PreferenceManager(application)
        loadSessionData()
    }

    /**
     * Load the session, its transcript entries, and emotion data points.
     */
    private fun loadSessionData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Load session metadata
                val sessionResult = repository.getSessionById(sessionId)
                val session = when (sessionResult) {
                    is RepositoryResult.Success -> sessionResult.data
                    is RepositoryResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = sessionResult.message
                        )
                        return@launch
                    }
                }
                if (session == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Session not found"
                    )
                    return@launch
                }

                // Load transcript entries
                val transcripts = repository.getTranscriptsForSession(sessionId).first()

                // Load emotion data points
                val emotions = repository.getEmotionsForSession(sessionId).first()

                // Compute dominant emotion and distribution
                val dominantEmotion = repository.getDominantEmotion(sessionId)
                val distribution = computeEmotionDistribution(emotions)

                _uiState.value = SessionDetailUiState(
                    isLoading = false,
                    session = session,
                    transcriptEntries = transcripts,
                    emotionDataPoints = emotions,
                    dominantEmotion = dominantEmotion,
                    emotionDistribution = distribution,
                    summary = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load session: ${e.message}"
                )
            }
        }
    }

    /**
     * Compute the emotion distribution as a map of emotion -> percentage.
     */
    private fun computeEmotionDistribution(
        dataPoints: List<EmotionDataPoint>
    ): Map<EmotionType, Float> {
        if (dataPoints.isEmpty()) return emptyMap()
        val total = dataPoints.size.toFloat()
        return dataPoints
            .groupingBy { it.emotionType }
            .eachCount()
            .mapValues { it.value / total }
            .toList()
            .sortedByDescending { it.second }
            .toMap()
    }

    /**
     * Generate an AI summary of the session using DeepSeek.
     * Sends the transcript and emotion data to the LLM for summarization.
     */
    fun generateSummary() {
        if (_uiState.value.isGeneratingSummary) return

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingSummary = true, errorMessage = null) }

            try {
                // Check if API key is configured
                val apiKey = preferenceManager.deepseekApiKey.first() ?: ""
                if (apiKey.isBlank()) {
                    _uiState.update {
                        it.copy(
                            isGeneratingSummary = false,
                            errorMessage = "Please configure a DeepSeek API key in Settings"
                        )
                    }
                    return@launch
                }

                // Check if cloud processing is enabled
                val cloudEnabled = preferenceManager.isCloudProcessingEnabled.first()
                if (!cloudEnabled) {
                    _uiState.update {
                        it.copy(
                            isGeneratingSummary = false,
                            errorMessage = "Please enable Cloud Processing in Settings"
                        )
                    }
                    return@launch
                }

                // Reload latest data from repository to avoid stale snapshots
                val sessionResult = repository.getSessionById(sessionId)
                val session = when (sessionResult) {
                    is RepositoryResult.Success -> sessionResult.data
                    is RepositoryResult.Error -> null
                }
                val transcriptsResult = repository.getTranscriptsForSessionSync(sessionId)
                val transcripts = when (transcriptsResult) {
                    is RepositoryResult.Success -> transcriptsResult.data
                    is RepositoryResult.Error -> emptyList()
                }
                val emotionsResult = repository.getEmotionsForSessionSync(sessionId)
                val emotions = when (emotionsResult) {
                    is RepositoryResult.Success -> emotionsResult.data
                    is RepositoryResult.Error -> emptyList()
                }
                val dominantEmotion = repository.getDominantEmotion(sessionId)
                val distribution = computeEmotionDistribution(emotions)

                // Build the prompt from freshly loaded transcript and emotion data
                val transcriptText = transcripts.joinToString("\n") { entry ->
                    entry.text
                }.ifBlank { "No transcript available." }

                val emotionSummary = dominantEmotion?.let {
                    "Dominant emotion: ${it.displayName} ${it.emoji}"
                } ?: "No emotion data available."

                val distributionText = if (distribution.isNotEmpty()) {
                    distribution.entries.joinToString(", ") { (emotion, pct) ->
                        "${emotion.displayName}: ${(pct * 100).toInt()}%"
                    }
                } else {
                    "No distribution data."
                }

                val duration = session?.let {
                    val totalSeconds = it.duration / 1000
                    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
                } ?: "--:--"

                val prompt = """
                    |You are an emotion analysis assistant. Below is a recording session from the MindEcho app.
                    |
                    |Transcript:
                    |$transcriptText
                    |
                    |Emotion Analysis:
                    |$emotionSummary
                    |Distribution: $distributionText
                    |Duration: $duration
                    |
                    |Please provide a concise summary (3-5 sentences) of this conversation, focusing on:
                    |1. The main topic and flow of the conversation
                    |2. The emotional patterns observed
                    |3. Any notable moments or shifts in mood
                    |
                    |Write the summary in the same language as the transcript.
                """.trimMargin()

                // Build auth interceptor with the current API key
                val baseUrl = preferenceManager.apiBaseUrl.first()
                val authInterceptor = Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()
                    chain.proceed(request)
                }

                // Create a Retrofit instance with the correct base URL and auth interceptor
                // Use a new OkHttpClient with the auth interceptor but share the connection pool
                val clientWithAuth = okHttpClient.newBuilder()
                    .addInterceptor(authInterceptor)
                    .build()

                val retrofitWithUrl = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(clientWithAuth)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val llmApi = retrofitWithUrl.create(LlmApi::class.java)

                val request = ChatCompletionRequest(
                    model = Constants.LLM_MODEL,
                    messages = listOf(
                        ChatMessage(
                            role = "system",
                            content = "You are a helpful assistant that analyzes conversation recordings and provides concise, insightful summaries."
                        ),
                        ChatMessage(
                            role = "user",
                            content = prompt
                        )
                    ),
                    temperature = 1.0,
                    top_p = 0.95,
                    max_tokens = 5000,
                    reasoning_effort = "max"
                )

                val response = llmApi.chatCompletion(
                    authorization = "Bearer $apiKey",
                    request = request
                )

                if (response.isSuccessful && response.body() != null) {
                    val summaryText = response.body()!!
                        .choices?.firstOrNull()?.message?.content
                        ?: "Summary generation returned empty result."

                    _uiState.update {
                        it.copy(
                            isGeneratingSummary = false,
                            summary = summaryText
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isGeneratingSummary = false,
                            errorMessage = "API error: ${response.code()} ${response.message()}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingSummary = false,
                        errorMessage = "Failed to generate summary: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

/**
 * Factory for creating SessionDetailViewModel with the required sessionId parameter.
 */
class SessionDetailViewModelFactory(
    private val application: Application,
    private val sessionId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionDetailViewModel::class.java)) {
            return SessionDetailViewModel(application, sessionId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
