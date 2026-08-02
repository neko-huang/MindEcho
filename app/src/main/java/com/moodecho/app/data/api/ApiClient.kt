package com.moodecho.app.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Retrofit API interface for OpenAI Whisper transcription.
 * Endpoint: POST /v1/audio/transcriptions
 */
interface WhisperApi {

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null
    ): Response<WhisperResponse>
}

/**
 * Response from the Whisper transcription API.
 */
data class WhisperResponse(
    val text: String,
    val language: String? = null,
    val duration: Float? = null
)

/**
 * Retrofit API interface for OpenAI Chat Completions (LLM).
 * Used for conversation summaries and report generation.
 * Endpoint: POST /v1/chat/completions
 */
interface LlmApi {

    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}

/**
 * Request body for chat completion API.
 */
data class ChatCompletionRequest(
    val model: String = "gpt-3.5-turbo",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 1000
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
