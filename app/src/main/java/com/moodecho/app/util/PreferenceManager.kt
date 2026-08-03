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
import kotlinx.coroutines.launch

/**
 * Manages app preferences using DataStore (modern replacement for SharedPreferences).
 * Stores API keys, privacy consent, and user settings.
 *
 * Performance: All settings are read in a single DataStore subscription via [allSettings],
 * avoiding multiple disk reads. Saves use a persistent ioScope that survives composable disposal.
 */
class PreferenceManager(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "mindecho_prefs"
    )

    // Independent scope that survives composable/ViewModel lifecycle
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    // ---- Unified Settings State (single DataStore read) ----

    /** Snapshot of all user settings — read once from a single DataStore subscription */
    data class SettingsState(
        val deepseekApiKey: String? = null,
        val apiBaseUrl: String = DEFAULT_API_BASE_URL,
        val assemblyAiApiKey: String? = null,
        val cloudProcessingEnabled: Boolean = false,
        val autoTranscribe: Boolean = false,
        val autoAnalyzeEmotion: Boolean = true
    )

    /** Single Flow that reads all settings at once — replaces 6 separate flows */
    val allSettings: Flow<SettingsState> = context.dataStore.data.map { prefs ->
        SettingsState(
            deepseekApiKey = prefs[KEY_DEEPSEEK_API_KEY],
            apiBaseUrl = prefs[KEY_API_BASE_URL] ?: DEFAULT_API_BASE_URL,
            assemblyAiApiKey = prefs[KEY_ASSEMBLYAI_API_KEY],
            cloudProcessingEnabled = prefs[KEY_CLOUD_PROCESSING_ENABLED] ?: false,
            autoTranscribe = prefs[KEY_AUTO_TRANSCRIBE] ?: false,
            autoAnalyzeEmotion = prefs[KEY_AUTO_ANALYZE_EMOTION] ?: true
        )
    }

    // ---- Legacy individual flows (kept for backward compatibility in non-UI code) ----

    val deepseekApiKey: Flow<String?> = context.dataStore.data.map { it[KEY_DEEPSEEK_API_KEY] }
    val apiBaseUrl: Flow<String> = context.dataStore.data.map { it[KEY_API_BASE_URL] ?: DEFAULT_API_BASE_URL }
    val assemblyAiApiKey: Flow<String?> = context.dataStore.data.map { it[KEY_ASSEMBLYAI_API_KEY] }
    val isCloudProcessingEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CLOUD_PROCESSING_ENABLED] ?: false }
    val autoTranscribe: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_TRANSCRIBE] ?: false }
    val autoAnalyzeEmotion: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_ANALYZE_EMOTION] ?: true }
    val hasPrivacyConsent: Flow<Boolean> = context.dataStore.data.map { it[KEY_PRIVACY_CONSENTED] ?: false }

    // ---- Fire-and-forget saves (survive composable disposal) ----

    fun saveDeepseekApiKey(key: String) {
        ioScope.launch { setDeepseekApiKey(key) }
    }

    fun saveApiBaseUrl(url: String) {
        ioScope.launch { setApiBaseUrl(url) }
    }

    fun saveAssemblyAiApiKey(key: String) {
        ioScope.launch { setAssemblyAiApiKey(key) }
    }

    fun saveCloudProcessingEnabled(enabled: Boolean) {
        ioScope.launch { setCloudProcessingEnabled(enabled) }
    }

    fun saveAutoTranscribe(enabled: Boolean) {
        ioScope.launch { setAutoTranscribe(enabled) }
    }

    fun saveAutoAnalyzeEmotion(enabled: Boolean) {
        ioScope.launch { setAutoAnalyzeEmotion(enabled) }
    }

    /** Save all settings in a single DataStore transaction */
    fun saveAll(
        deepseekApiKey: String,
        apiBaseUrl: String,
        assemblyAiApiKey: String,
        cloudProcessingEnabled: Boolean,
        autoTranscribe: Boolean,
        autoAnalyzeEmotion: Boolean
    ) {
        ioScope.launch {
            context.dataStore.edit { prefs ->
                prefs[KEY_DEEPSEEK_API_KEY] = deepseekApiKey
                prefs[KEY_API_BASE_URL] = apiBaseUrl
                prefs[KEY_ASSEMBLYAI_API_KEY] = assemblyAiApiKey
                prefs[KEY_CLOUD_PROCESSING_ENABLED] = cloudProcessingEnabled
                prefs[KEY_AUTO_TRANSCRIBE] = autoTranscribe
                prefs[KEY_AUTO_ANALYZE_EMOTION] = autoAnalyzeEmotion
            }
        }
    }

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

    // ---- Suspend save functions (for internal / ioScope use) ----

    suspend fun setDeepseekApiKey(key: String) {
        context.dataStore.edit { it[KEY_DEEPSEEK_API_KEY] = key }
    }

    suspend fun setApiBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_API_BASE_URL] = url }
    }

    suspend fun setAssemblyAiApiKey(key: String) {
        context.dataStore.edit { it[KEY_ASSEMBLYAI_API_KEY] = key }
    }

    suspend fun setPrivacyConsented(consented: Boolean) {
        context.dataStore.edit { it[KEY_PRIVACY_CONSENTED] = consented }
    }

    suspend fun setCloudProcessingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CLOUD_PROCESSING_ENABLED] = enabled }
    }

    suspend fun setAutoTranscribe(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_TRANSCRIBE] = enabled }
    }

    suspend fun setAutoAnalyzeEmotion(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_ANALYZE_EMOTION] = enabled }
    }
}
