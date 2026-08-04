package com.moodecho.app.data.api

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * Retrofit API interface for DeepSeek Chat Completions (OpenAI-compatible).
 * Used for conversation summaries and report generation.
 * Endpoint: POST /v1/chat/completions
 */
interface LlmApi {

    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}

/**
 * Optional response format specification for chat completion.
 * Setting type to "json_object" forces the model to return valid JSON.
 */
data class ResponseFormat(
    val type: String = "text"
)

/**
 * Request body for chat completion API.
 */
data class ChatCompletionRequest(
    val model: String = "deepseek-v4-flash",
    val messages: List<ChatMessage>,
    val temperature: Double = 1.0,
    val top_p: Double = 0.95,
    val max_tokens: Int = 5000,
    val reasoning_effort: String? = null,
    val response_format: ResponseFormat? = null
)

/**
 * A single message in the chat completion conversation.
 */
data class ChatMessage(
    val role: String,   // "system", "user", or "assistant"
    val content: String
)

/**
 * Response from the chat completion API.
 */
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatChoice>? = null,
    val usage: ChatUsage? = null
)

/**
 * A single choice in the chat completion response.
 */
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    val finish_reason: String? = null
)

/**
 * Token usage statistics.
 */
data class ChatUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

/**
 * Generic API error response model.
 */
data class ApiError(
    val error: ApiErrorDetail? = null
)

data class ApiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

/**
 * Retrofit API interface for AssemblyAI Transcription.
 * Supports audio upload, transcript creation with speaker diarization, and polling.
 */
interface AssemblyAiApi {

    /**
     * Upload an audio file to AssemblyAI.
     * The returned upload_url is used as audio_url in the transcript request.
     */
    @Headers("Content-Type: application/octet-stream")
    @POST
    suspend fun uploadAudio(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body file: RequestBody
    ): Response<UploadResponse>

    /**
     * Create a transcript request with speaker diarization.
     */
    @POST("transcript")
    suspend fun createTranscript(
        @Header("Authorization") authorization: String,
        @Body request: TranscriptRequest
    ): Response<TranscriptResponse>

    /**
     * Poll the transcript status until completed or errored.
     */
    @GET("transcript/{id}")
    suspend fun getTranscript(
        @Header("Authorization") authorization: String,
        @Path("id") transcriptId: String
    ): Response<TranscriptResponse>
}

/**
 * Response from AssemblyAI file upload.
 */
data class UploadResponse(
    val upload_url: String
)

/**
 * Request body for creating an AssemblyAI transcript.
 */
data class TranscriptRequest(
    val audio_url: String,
    val speaker_labels: Boolean = true,
    val language_code: String = "zh"
)

/**
 * Response from AssemblyAI transcript creation and polling.
 */
data class TranscriptResponse(
    val id: String? = null,
    val status: String? = null,       // "queued", "processing", "completed", "error"
    val utterances: List<Utterance>? = null,
    val error: String? = null,
    val text: String? = null          // Full transcript text (fallback if no utterances)
)

/**
 * A single utterance from speaker diarization.
 */
data class Utterance(
    val speaker: String,    // e.g. "A", "B"
    val text: String,
    val start: Long,        // Start time in milliseconds
    val end: Long           // End time in milliseconds
)
