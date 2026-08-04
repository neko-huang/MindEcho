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
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.util.Constants
import com.moodecho.app.util.PreferenceManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
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

        // Fetch all sessions for the given date
        val sessions = repository.getSessionsByDate(date)
        if (sessions.isEmpty()) {
            return ReportResult.NoData
        }

        // Collect transcript and emotion data for each session
        val sessionDataList = mutableListOf<SessionData>()
        for (session in sessions) {
            val transcripts = repository.getTranscriptsForSessionSync(session.id)
            val emotions = repository.getEmotionsForSessionSync(session.id)
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

        // Call the LLM API
        val llmResponse = callLlmApi(apiKey, baseUrl, prompt)
            ?: return ReportResult.Error("Failed to get response from DeepSeek API")

        // Parse the LLM response
        val parsed = parseLlmResponse(llmResponse)

        // Determine overall mood from emotion data
        val overallMood = determineOverallMood(sessionDataList)

        // Build session ID list
        val sessionIdList = sessions.joinToString(",") { it.id.toString() }

        // Create and save the report
        val report = DailyReport(
            date = date,
            sessionIdList = sessionIdList,
            summary = parsed.summary,
            emotionOverview = parsed.emotionOverview,
            suggestions = parsed.suggestions,
            overallMood = overallMood,
            createdAt = System.currentTimeMillis()
        )

        // Delete existing report for this date (if any) before inserting
        val existingReport = repository.getReportByDate(date)
        if (existingReport != null) {
            repository.deleteReport(existingReport)
        }

        repository.saveReport(report)

        return ReportResult.Success(report)
    }

    /**
     * Build the prompt string from session data.
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

            // Transcript
            if (data.transcripts.isNotEmpty()) {
                sb.appendLine("Transcript:")
                data.transcripts.forEach { entry ->
                    val startTime = timeFormat.format(Date(entry.startTime))
                    sb.appendLine("  [$startTime] ${entry.text}")
                }
            } else {
                sb.appendLine("Transcript: (no transcript available)")
            }
            sb.appendLine()

            // Emotion data
            if (data.emotions.isNotEmpty()) {
                sb.appendLine("Emotion data points:")
                data.emotions.forEach { point ->
                    val timeStr = timeFormat.format(Date(point.timestamp))
                    sb.appendLine(
                        "  [$timeStr] ${point.emotionType.displayName} " +
                                "(confidence: ${"%.2f".format(point.confidence)}, " +
                                "arousal: ${"%.2f".format(point.arousal)}, " +
                                "valence: ${"%.2f".format(point.valence)})"
                    )
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
                temperature = 1.0,
                top_p = 0.95,
                max_tokens = 5000,
                reasoning_effort = "max",
                response_format = ResponseFormat(type = "json_object")
            )

            val response = llmApi.chatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful) {
                response.body()?.choices?.firstOrNull()?.message?.content
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse the LLM JSON response into structured fields.
     * Falls back to using raw text if JSON parsing fails.
     */
    private fun parseLlmResponse(rawResponse: String): ParsedReport {
        return try {
            val json = gson.fromJson(rawResponse, JsonObject::class.java)
            ParsedReport(
                emotionOverview = json.get("emotionOverview")?.asString
                    ?: "Unable to generate emotion overview.",
                summary = json.get("summary")?.asString
                    ?: "Unable to generate summary.",
                suggestions = json.get("suggestions")?.asString
                    ?: "No suggestions available."
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
     * Determine the overall dominant mood from all emotion data across sessions.
     */
    private fun determineOverallMood(sessionDataList: List<SessionData>): EmotionType {
        val emotionCounts = mutableMapOf<EmotionType, Int>()
        for (data in sessionDataList) {
            for (point in data.emotions) {
                emotionCounts[point.emotionType] =
                    (emotionCounts[point.emotionType] ?: 0) + 1
            }
        }

        return if (emotionCounts.isNotEmpty()) {
            emotionCounts.maxByOrNull { it.value }!!.key
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
