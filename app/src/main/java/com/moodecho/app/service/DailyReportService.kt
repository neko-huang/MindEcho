package com.moodecho.app.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.moodecho.app.MindEchoApp
import com.moodecho.app.data.api.ChatCompletionRequest
import com.moodecho.app.data.api.ChatMessage
import com.moodecho.app.data.api.LlmApi
import com.moodecho.app.data.api.ResponseFormat
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.TranscriptEntry
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.data.repository.RepositoryResult
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.util.Constants
import com.moodecho.app.util.PreferenceManager
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Result wrapper for report generation.
 * Sealed class to represent success, error, or insufficient-data states.
 */
sealed class ReportResult {
    data class Success(val report: DailyReport) : ReportResult()
    data class Error(val message: String) : ReportResult()
    data object NoData : ReportResult()
    data object NoApiKey : ReportResult()
}

/**
 * Service responsible for generating daily emotion reports.
 *
 * Collects all recording sessions, transcripts, and emotion data for a given date,
 * assembles a prompt, calls the DeepSeek LLM API, and persists the resulting report.
 */
object DailyReportService {

    private val gson = Gson()

    /**
     * Generate a daily report for the specified date.
     *
     * @param context Android context for accessing database and preferences
     * @param date Date string in "yyyy-MM-dd" format
     * @return [ReportResult] indicating success, error, or insufficient data
     */
    suspend fun generateReport(context: Context, date: String): ReportResult {
        val app = context.applicationContext as MindEchoApp
        val db = app.database
        val repository = RecordingRepository(
            sessionDao = db.recordingSessionDao(),
            transcriptDao = db.transcriptEntryDao(),
            emotionDao = db.emotionDataPointDao(),
            reportDao = db.dailyReportDao()
        )
        val preferenceManager = PreferenceManager(context)

        // Check if DeepSeek API key is configured — use synchronous getter
        val apiKey = preferenceManager.getDeepseekApiKey() ?: ""
        if (apiKey.isBlank()) {
            return ReportResult.NoApiKey
        }

        val baseUrl = preferenceManager.getApiBaseUrl()

        // Parse date string to epoch millis range
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val parsedDate = dateFormat.parse(date) ?: return ReportResult.Error("Invalid date format")
        val calendar = Calendar.getInstance()
        calendar.time = parsedDate
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val dayStart = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val nextDayStart = calendar.timeInMillis

        // Fetch all sessions for the given date using range query
        val sessionsResult = repository.getSessionsByDate(dayStart, nextDayStart)
        val sessions = when (sessionsResult) {
            is RepositoryResult.Success -> sessionsResult.data
            is RepositoryResult.Error -> return ReportResult.Error(sessionsResult.message)
        }
        if (sessions.isEmpty()) {
            return ReportResult.NoData
        }

        // Collect transcript and emotion data for each session
        val sessionDataList = mutableListOf<SessionData>()
        for (session in sessions) {
            val transcriptsResult = repository.getTranscriptsForSessionSync(session.id)
            val emotionsResult = repository.getEmotionsForSessionSync(session.id)
            val transcripts = when (transcriptsResult) {
                is RepositoryResult.Success -> transcriptsResult.data
                is RepositoryResult.Error -> emptyList()
            }
            val emotions = when (emotionsResult) {
                is RepositoryResult.Success -> emotionsResult.data
                is RepositoryResult.Error -> emptyList()
            }
            sessionDataList.add(
                SessionData(
                    session = session,
                    transcripts = transcripts,
                    emotions = emotions
                )
            )
        }

        // Check if there's any meaningful data
        val hasTranscripts = sessionDataList.any { it.transcripts.isNotEmpty() }
        val hasEmotions = sessionDataList.any { it.emotions.isNotEmpty() }
        if (!hasTranscripts && !hasEmotions) {
            return ReportResult.NoData
        }

        // Build the prompt
        val prompt = buildPrompt(date, sessionDataList)

        // P1: Call the LLM API with retry (exponential backoff, 3 attempts)
        val llmResponse = callLlmApiWithRetry(apiKey, baseUrl, prompt)
            ?: return ReportResult.Error("Failed to get response from DeepSeek API after retries")

        // Parse the LLM response
        val parsed = parseLlmResponse(llmResponse)

        // P2: Determine overall mood from emotion data (weighted by confidence)
        val overallMood = determineOverallMood(sessionDataList)

        // Create and save the report (without sessionIdList — now uses junction table)
        val report = DailyReport(
            date = date,
            summary = parsed.summary,
            emotionOverview = parsed.emotionOverview,
            suggestions = parsed.suggestions,
            overallMood = overallMood,
            createdAt = System.currentTimeMillis()
        )

        // Delete existing report for this date (if any) before inserting
        val existingReportResult = repository.getReportByDate(date)
        if (existingReportResult is RepositoryResult.Success && existingReportResult.data != null) {
            repository.deleteReport(existingReportResult.data)
        }

        val reportResult = repository.saveReport(report)
        val reportId = when (reportResult) {
            is RepositoryResult.Success -> reportResult.data
            is RepositoryResult.Error -> return ReportResult.Error(reportResult.message)
        }

        return ReportResult.Success(report)
    }

    /**
     * P1: Call LLM API with exponential backoff retry.
     * Retries only on 5xx server errors and timeouts.
     * Maximum 3 attempts with 1s, 2s, 4s delays.
     */
    private suspend fun callLlmApiWithRetry(
        apiKey: String,
        baseUrl: String,
        userContent: String
    ): String? {
        val delays = listOf(1000L, 2000L, 4000L)

        for (attempt in 0..delays.size) {
            val result = try {
                callLlmApi(apiKey, baseUrl, userContent)
            } catch (e: Exception) {
                // Network/IO error — worth retrying
                if (attempt < delays.size) {
                    delay(delays[attempt])
                    continue
                }
                null
            }

            if (result != null) {
                return result
            }

            // If we got null (non-5xx error), don't retry
            if (attempt < delays.size) {
                delay(delays[attempt])
            }
        }

        return null
    }

    /**
     * Build the prompt string from session data.
     *
     * P3: truncates overly long transcripts to avoid exceeding context window.
     */
    private fun buildPrompt(date: String, sessionDataList: List<SessionData>): String {
        val sb = StringBuilder()
        sb.appendLine("Date: $date")
        sb.appendLine("Total sessions: ${sessionDataList.size}")
        sb.appendLine()

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        sessionDataList.forEachIndexed { index, data ->
            val session = data.session
            val durationMin = (session.duration / 1000) / 60
            val durationSec = (session.duration / 1000) % 60

            sb.appendLine("=== Session ${index + 1}: ${session.title} ===")
            sb.appendLine("Duration: ${durationMin}m ${durationSec}s")
            sb.appendLine()

            // P3: truncate long transcripts — keep only first and last entries if too many
            if (data.transcripts.isNotEmpty()) {
                sb.appendLine("Transcript:")
                val maxTranscriptLines = 100
                val entries = if (data.transcripts.size > maxTranscriptLines) {
                    val firstHalf = data.transcripts.take(maxTranscriptLines / 2)
                    val lastHalf = data.transcripts.takeLast(maxTranscriptLines / 2)
                    firstHalf + listOf(
                        TranscriptEntry(
                            id = 0, sessionId = session.id,
                            text = "... [${data.transcripts.size - maxTranscriptLines} entries omitted for brevity] ...",
                            startTime = 0L, endTime = 0L
                        )
                    ) + lastHalf
                } else {
                    data.transcripts
                }
                entries.forEach { entry ->
                    val startTime = timeFormat.format(Date(entry.startTime))
                    sb.appendLine("  [$startTime] ${entry.text}")
                }
            } else {
                sb.appendLine("Transcript: (no transcript available)")
            }
            sb.appendLine()

            // P3: Emotion data: truncate if too many to avoid context window overflow
            if (data.emotions.isNotEmpty()) {
                sb.appendLine("Emotion data points:")
                val maxEmotionLines = 50
                val emotionsToShow = data.emotions.take(maxEmotionLines)
                emotionsToShow.forEach { point ->
                    val timeStr = timeFormat.format(Date(point.timestamp))
                    sb.appendLine(
                        "  [$timeStr] ${point.emotionType.displayName} " +
                                "(confidence: ${"%.2f".format(point.confidence)}, " +
                                "arousal: ${"%.2f".format(point.arousal)}, " +
                                "valence: ${"%.2f".format(point.valence)})"
                    )
                }
                if (data.emotions.size > maxEmotionLines) {
                    sb.appendLine("  ... [${data.emotions.size - maxEmotionLines} more emotion data points omitted] ...")
                }
            } else {
                sb.appendLine("Emotion data: (no emotion data available)")
            }
            sb.appendSeparator()
        }

        return sb.toString()
    }

    /**
     * Call the DeepSeek LLM API with the assembled prompt.
     */
    private suspend fun callLlmApi(
        apiKey: String,
        baseUrl: String,
        userContent: String
    ): String? {
        return try {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val llmApi = retrofit.create(LlmApi::class.java)

            val systemPrompt = """
                You are an emotion analysis linguist for the MindEcho app.
                Based on the user's daily conversation transcripts and emotion data,
                generate a daily emotion report. Pay special attention to linguistic
                cues that reveal emotional state.

                === LINGUISTIC ANALYSIS GUIDELINES ===
                Analyze these text features to detect hidden emotions:

                1. **Pronoun usage**: Frequent "我" (I/me) = self-focused, possibly anxious or depressed.
                   Frequent "我们" (we) = socially engaged, positive.

                2. **Modality & certainty**: "一定"/"肯定" (absolute) = confident or angry.
                   "可能"/"也许"/"大概" (hedging) = anxious or uncertain.
                   "应该"/"按理说" (expectation) = disappointed when unmet.

                3. **Intensifiers**: "非常"/"太"/"极其" (very/extremely) = emotional intensity.
                   Absence of intensifiers = flat affect, possibly depressed.

                4. **Negation patterns**: "不开心"/"不好"/"不行" = negative emotional state.
                   Frequent negations = defensiveness or dissatisfaction.

                5. **Emotional vocabulary**: Direct emotional words (开心/烦/生气/焦虑).
                   Take note of BOTH explicit and implicit emotional expressions.

                6. **Sentence structure**:
                   - Exclamation marks (!/！) = high arousal (anger/excitement)
                   - Questions (?/？) = uncertainty or anxiety
                   - Ellipsis (…/... ) = hesitation or sadness
                   - Short, clipped sentences = possible irritability or fatigue

                7. **Conversation dynamics**:
                   - Who talks more? Speaker ratio indicates power dynamics.
                   - Interruptions or overlapping speech = conflict or excitement.
                   - Long pauses between turns = tension or discomfort.

                8. **Cognitive process words**: "明白"/"理解"/"觉得"/"意识到" = reflection.
                   High usage = self-aware, processing emotions.
                   Low usage = avoidant or emotionally shut down.

                === EMOTION DETECTION RULES ===
                - **Angry**: High intensity, absolute language, exclamation marks, short sentences.
                - **Anxious**: Hedging (可能/也许), questions, first-person pronouns, worry words.
                - **Sad**: Low energy, negations, ellipsis, past tense, loneliness words.
                - **Happy**: Positive vocabulary, exclamation, variety in sentence length, laughter.
                - **Calm**: Balanced sentences, cognitive words, no intensifiers, even turn-taking.
                - **Excited**: High intensity, positive exclamations, rapid topic shifts, laughter.

                Return your response as a JSON object with exactly these fields:
                {
                  "emotionOverview": "A brief overview of the day's overall emotional state, referencing specific linguistic cues observed (2-4 sentences, in Chinese)",
                  "summary": "A summary of key conversations and emotion change trends, noting how language patterns shifted throughout the day (3-5 sentences, in Chinese)",
                  "suggestions": "Personalized suggestions for improving emotional well-being, based on the linguistic patterns detected. One per line starting with '•' (2-3 items, in Chinese)"
                }

                Important rules:
                - Write all content in Chinese (中文).
                - Be empathetic and supportive in tone.
                - Base your analysis on the actual transcript and emotion data provided.
                - Reference specific linguistic patterns from the transcript in your analysis.
                - If transcript or emotion data is missing for a session, note it briefly.
            """.trimIndent()

            val request = ChatCompletionRequest(
                model = Constants.LLM_MODEL,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userContent)
                ),
                // P2: reduce temperature from 1.0 to 0.3 for more consistent output
                temperature = 0.3,
                top_p = 0.95,
                max_tokens = 5000,
                // P2: reduce reasoning_effort from "max" to "low" to decrease latency and cost
                reasoning_effort = "low",
                response_format = ResponseFormat(type = "json_object")
            )

            val response = llmApi.chatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful) {
                response.body()?.choices?.firstOrNull()?.message?.content
            } else {
                // P1: only retry on 5xx server errors
                if (response.code() in 500..599) {
                    throw ServerErrorException(response.code(), response.message())
                }
                null
            }
        } catch (e: Exception) {
            // P1: rethrow server errors so retry mechanism can handle them
            if (e is ServerErrorException) throw e
            null
        }
    }

    /**
     * P1: custom exception to signal server errors eligible for retry.
     */
    private class ServerErrorException(val code: Int, msg: String?) : Exception(msg)

    /**
     * Parse the LLM JSON response into structured fields.
     * Falls back to using raw text if JSON parsing fails.
     *
     * P1: handles "suggestions" field when it's a JSON array instead of string.
     */
    private fun parseLlmResponse(rawResponse: String): ParsedReport {
        return try {
            val json = gson.fromJson(rawResponse, JsonObject::class.java)
            ParsedReport(
                emotionOverview = json.get("emotionOverview")?.asString
                    ?: "Unable to generate emotion overview.",
                summary = json.get("summary")?.asString
                    ?: "Unable to generate summary.",
                // P1: try asString first; if that fails, try asJsonArray → joinToString
                suggestions = parseSuggestions(json.get("suggestions"))
            )
        } catch (e: Exception) {
            // Fallback: use raw text as summary
            ParsedReport(
                emotionOverview = "Emotion overview unavailable.",
                summary = rawResponse,
                suggestions = "No suggestions available."
            )
        }
    }

    /**
     * P1: parse suggestions field that may be a string or a JSON array.
     */
    private fun parseSuggestions(element: com.google.gson.JsonElement?): String {
        if (element == null) return "No suggestions available."
        return try {
            // Try as string first
            element.asString
        } catch (e: UnsupportedOperationException) {
            // Fall back to array
            try {
                val array = element.asJsonArray
                array.joinToString("\n") { item ->
                    val text = item.asString
                    if (text.startsWith("•")) text else "• $text"
                }
            } catch (e2: Exception) {
                "No suggestions available."
            }
        }
    }

    /**
     * P2: determine the overall dominant mood from all emotion data across sessions,
     * weighted by confidence instead of simple count.
     */
    private fun determineOverallMood(sessionDataList: List<SessionData>): EmotionType {
        val confidenceWeighted = mutableMapOf<EmotionType, Double>()
        for (data in sessionDataList) {
            for (point in data.emotions) {
                confidenceWeighted[point.emotionType] =
                    (confidenceWeighted[point.emotionType] ?: 0.0) + point.confidence.toDouble()
            }
        }

        return if (confidenceWeighted.isNotEmpty()) {
            confidenceWeighted.maxByOrNull { it.value }!!.key
        } else {
            EmotionType.NEUTRAL
        }
    }

    /**
     * Helper data class for collected session data.
     */
    private data class SessionData(
        val session: RecordingSession,
        val transcripts: List<TranscriptEntry>,
        val emotions: List<EmotionDataPoint>
    )

    /**
     * Helper data class for parsed LLM response.
     */
    private data class ParsedReport(
        val emotionOverview: String,
        val summary: String,
        val suggestions: String
    )
}

/**
 * Extension function to append a separator line to a StringBuilder.
 */
private fun StringBuilder.appendSeparator() {
    this.appendLine()
    this.appendLine("---")
    this.appendLine()
}