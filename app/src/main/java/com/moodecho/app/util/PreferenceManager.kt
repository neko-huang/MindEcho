package com.moodecho.app.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Manages app preferences using SharedPreferences with an in-memory StateFlow layer.
 *
 * Why SharedPreferences instead of DataStore?
 * - DataStore relies on async Flow + file I/O, which creates race conditions when
 *   a composable is disposed and recreated (e.g., bottom tab navigation with saveState).
 * - SharedPreferences writes are synchronous in-memory (apply() updates the in-memory
 *   cache immediately, and getXxx() reads from that cache), eliminating all async races.
 * - The in-memory StateFlow keeps the reactive API for existing consumers (ViewModels, Services).
 *
 * Design:
 * - All saveXxx() methods write to SharedPreferences synchronously AND update the
 *   in-memory StateFlow immediately.
 * - All read access goes through the StateFlow (reactive) or SharedPreferences (synchronous).
 * - The StateFlow is initialized from SharedPreferences in the constructor, so it's
 *   always up-to-date from the moment of creation.
 */
class PreferenceManager(private val context: Context) {

    // ---- SharedPreferences (synchronous storage) ----

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- In-memory reactive state (always up-to-date) ----

    data class SettingsState(
        val deepseekApiKey: String? = null,
        val apiBaseUrl: String = DEFAULT_API_BASE_URL,
        val assemblyAiApiKey: String? = null,
        val cloudProcessingEnabled: Boolean = false,
        val autoTranscribe: Boolean = false,
        val autoAnalyzeEmotion: Boolean = true,
        val privacyConsented: Boolean = false
    )

    private val _settingsState = MutableStateFlow(loadSettings())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    /** Load all settings from SharedPreferences synchronously */
    private fun loadSettings(): SettingsState {
        return SettingsState(
            deepseekApiKey = prefs.getString(KEY_DEEPSEEK_API_KEY, null),
            apiBaseUrl = prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL,
            assemblyAiApiKey = prefs.getString(KEY_ASSEMBLYAI_API_KEY, null),
            cloudProcessingEnabled = prefs.getBoolean(KEY_CLOUD_PROCESSING_ENABLED, false),
            autoTranscribe = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, false),
            autoAnalyzeEmotion = prefs.getBoolean(KEY_AUTO_ANALYZE_EMOTION, true),
            privacyConsented = prefs.getBoolean(KEY_PRIVACY_CONSENTED, false)
        )
    }

    // ---- Reactive Flow API (backward compatible with existing consumers) ----

    val allSettings: Flow<SettingsState> = _settingsState
    val deepseekApiKey: Flow<String?> = _settingsState.map { it.deepseekApiKey }
    val apiBaseUrl: Flow<String> = _settingsState.map { it.apiBaseUrl }
    val assemblyAiApiKey: Flow<String?> = _settingsState.map { it.assemblyAiApiKey }
    val isCloudProcessingEnabled: Flow<Boolean> = _settingsState.map { it.cloudProcessingEnabled }
    val autoTranscribe: Flow<Boolean> = _settingsState.map { it.autoTranscribe }
    val autoAnalyzeEmotion: Flow<Boolean> = _settingsState.map { it.autoAnalyzeEmotion }
    val hasPrivacyConsent: Flow<Boolean> = _settingsState.map { it.privacyConsented }

    // ---- Synchronous read methods (for SettingsScreen) ----

    fun getDeepseekApiKey(): String? = prefs.getString(KEY_DEEPSEEK_API_KEY, null)
    fun getApiBaseUrl(): String = prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL
    fun getAssemblyAiApiKey(): String? = prefs.getString(KEY_ASSEMBLYAI_API_KEY, null)
    fun isCloudProcessingEnabled(): Boolean = prefs.getBoolean(KEY_CLOUD_PROCESSING_ENABLED, false)
    fun isAutoTranscribe(): Boolean = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, false)
    fun isAutoAnalyzeEmotion(): Boolean = prefs.getBoolean(KEY_AUTO_ANALYZE_EMOTION, true)
    fun hasPrivacyConsent(): Boolean = prefs.getBoolean(KEY_PRIVACY_CONSENTED, false)

    // ---- Save methods (synchronous write + StateFlow update) ----

    fun saveDeepseekApiKey(key: String) {
        prefs.edit().putString(KEY_DEEPSEEK_API_KEY, key).apply()
        _settingsState.update { it.copy(deepseekApiKey = key) }
    }

    fun saveApiBaseUrl(url: String) {
        prefs.edit().putString(KEY_API_BASE_URL, url).apply()
        _settingsState.update { it.copy(apiBaseUrl = url) }
    }

    fun saveAssemblyAiApiKey(key: String) {
        prefs.edit().putString(KEY_ASSEMBLYAI_API_KEY, key).apply()
        _settingsState.update { it.copy(assemblyAiApiKey = key) }
    }

    fun saveCloudProcessingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_PROCESSING_ENABLED, enabled).apply()
        _settingsState.update { it.copy(cloudProcessingEnabled = enabled) }
    }

    fun saveAutoTranscribe(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRANSCRIBE, enabled).apply()
        _settingsState.update { it.copy(autoTranscribe = enabled) }
    }

    fun saveAutoAnalyzeEmotion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ANALYZE_EMOTION, enabled).apply()
        _settingsState.update { it.copy(autoAnalyzeEmotion = enabled) }
    }

    fun savePrivacyConsented(consented: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY_CONSENTED, consented).apply()
        _settingsState.update { it.copy(privacyConsented = consented) }
    }

    /** Save all settings in a single SharedPreferences transaction */
    fun saveAll(
        deepseekApiKey: String,
        apiBaseUrl: String,
        assemblyAiApiKey: String,
        cloudProcessingEnabled: Boolean,
        autoTranscribe: Boolean,
        autoAnalyzeEmotion: Boolean
    ) {
        prefs.edit()
            .putString(KEY_DEEPSEEK_API_KEY, deepseekApiKey)
            .putString(KEY_API_BASE_URL, apiBaseUrl)
            .putString(KEY_ASSEMBLYAI_API_KEY, assemblyAiApiKey)
            .putBoolean(KEY_CLOUD_PROCESSING_ENABLED, cloudProcessingEnabled)
            .putBoolean(KEY_AUTO_TRANSCRIBE, autoTranscribe)
            .putBoolean(KEY_AUTO_ANALYZE_EMOTION, autoAnalyzeEmotion)
            .apply()
        _settingsState.update {
            it.copy(
                deepseekApiKey = deepseekApiKey,
                apiBaseUrl = apiBaseUrl,
                assemblyAiApiKey = assemblyAiApiKey,
                cloudProcessingEnabled = cloudProcessingEnabled,
                autoTranscribe = autoTranscribe,
                autoAnalyzeEmotion = autoAnalyzeEmotion
            )
        }
    }

    // ---- Suspend write methods (for backward compatibility, now synchronous) ----

    suspend fun setDeepseekApiKey(key: String) { saveDeepseekApiKey(key) }
    suspend fun setApiBaseUrl(url: String) { saveApiBaseUrl(url) }
    suspend fun setAssemblyAiApiKey(key: String) { saveAssemblyAiApiKey(key) }
    suspend fun setPrivacyConsented(consented: Boolean) { savePrivacyConsented(consented) }
    suspend fun setCloudProcessingEnabled(enabled: Boolean) { saveCloudProcessingEnabled(enabled) }
    suspend fun setAutoTranscribe(enabled: Boolean) { saveAutoTranscribe(enabled) }
    suspend fun setAutoAnalyzeEmotion(enabled: Boolean) { saveAutoAnalyzeEmotion(enabled) }

    companion object {
        // Storage file name
        private const val PREFS_NAME = "mindecho_prefs"

        // API Configuration
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_ASSEMBLYAI_API_KEY = "assemblyai_api_key"

        // Privacy
        const val KEY_PRIVACY_CONSENTED = "privacy_consented"
        const val KEY_CLOUD_PROCESSING_ENABLED = "cloud_processing_enabled"

        // Recording Settings
        const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
        const val KEY_AUTO_ANALYZE_EMOTION = "auto_analyze_emotion"

        // Defaults
        const val DEFAULT_API_BASE_URL = "https://api.deepseek.com/"
    }
}