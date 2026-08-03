package com.moodecho.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages app preferences using DataStore (modern replacement for SharedPreferences).
 * Stores API keys, privacy consent, and user settings.
 */
class PreferenceManager(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "mindecho_prefs"
    )

    companion object {
        // API Configuration
        val KEY_DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_ASSEMBLYAI_API_KEY = stringPreferencesKey("assemblyai_api_key")

        // Privacy
        val KEY_PRIVACY_CONSENTED = booleanPreferencesKey("privacy_consented")
        val KEY_CLOUD_PROCESSING_ENABLED = booleanPreferencesKey("cloud_processing_enabled")

        // Recording Settings
        val KEY_AUTO_TRANSCRIBE = booleanPreferencesKey("auto_transcribe")
        val KEY_AUTO_ANALYZE_EMOTION = booleanPreferencesKey("auto_analyze_emotion")

        // Defaults
        const val DEFAULT_API_BASE_URL = "https://api.deepseek.com/"
    }

    // ---- API Configuration ----

    /** Get the configured DeepSeek API key */
    val deepseekApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEEPSEEK_API_KEY]
    }

    /** Get the configured API base URL */
    val apiBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_BASE_URL] ?: DEFAULT_API_BASE_URL
    }

    /** Save the DeepSeek API key */
    suspend fun setDeepseekApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEEPSEEK_API_KEY] = key
        }
    }

    /** Save the API base URL */
    suspend fun setApiBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_BASE_URL] = url
        }
    }

    /** Get the configured AssemblyAI API key */
    val assemblyAiApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ASSEMBLYAI_API_KEY]
    }

    /** Save the AssemblyAI API key */
    suspend fun setAssemblyAiApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ASSEMBLYAI_API_KEY] = key
        }
    }

    // ---- Privacy ----

    /** Whether the user has consented to the privacy policy */
    val hasPrivacyConsent: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PRIVACY_CONSENTED] ?: false
    }

    /** Whether cloud processing is enabled (requires API key) */
    val isCloudProcessingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_PROCESSING_ENABLED] ?: false
    }

    /** Set privacy consent status */
    suspend fun setPrivacyConsented(consented: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PRIVACY_CONSENTED] = consented
        }
    }

    /** Enable or disable cloud processing */
    suspend fun setCloudProcessingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_PROCESSING_ENABLED] = enabled
        }
    }

    // ---- Recording Settings ----

    /** Whether to auto-transcribe recordings */
    val autoTranscribe: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_TRANSCRIBE] ?: false
    }

    /** Whether to auto-analyze emotions */
    val autoAnalyzeEmotion: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_ANALYZE_EMOTION] ?: true
    }

    /** Set auto-transcribe preference */
    suspend fun setAutoTranscribe(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_TRANSCRIBE] = enabled
        }
    }

    /** Set auto-analyze emotion preference */
    suspend fun setAutoAnalyzeEmotion(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_ANALYZE_EMOTION] = enabled
        }
    }
}
