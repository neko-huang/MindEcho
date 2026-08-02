package com.moodecho.app.util

/**
 * Global constants for the MindEcho application.
 */
object Constants {

    // ---- Database ----
    const val DATABASE_NAME = "mindecho_database"
    const val DATABASE_VERSION = 1

    // ---- Audio ----
    const val SAMPLE_RATE = 44100
    const val AUDIO_BIT_RATE = 128000
    const val ANALYSIS_SAMPLE_RATE = 16000  // Downsampled for analysis
    const val FRAME_LENGTH_MS = 25L
    const val FRAME_SHIFT_MS = 10L
    const val ANALYSIS_WINDOW_MS = 5000L    // 5-second analysis windows
    const val AMPLITUDE_POLL_INTERVAL_MS = 50L

    // ---- Notification ----
    const val RECORDING_NOTIFICATION_CHANNEL = "recording_channel"
    const val RECORDING_NOTIFICATION_ID = 1

    // ---- API ----
    const val DEFAULT_API_BASE_URL = "https://api.openai.com/"
    const val WHISPER_MODEL = "whisper-1"
    const val LLM_MODEL = "gpt-3.5-turbo"

    // ---- File Storage ----
    const val RECORDINGS_DIR = "recordings"
    const val REPORTS_DIR = "reports"

    // ---- Navigation Routes ----
    const val ROUTE_HOME = "home"
    const val ROUTE_RECORDING = "recording"
    const val ROUTE_HISTORY = "history"
    const val ROUTE_SESSION_DETAIL = "session/{id}"
    const val ROUTE_REPORT = "report/{date}"
    const val ROUTE_SETTINGS = "settings"

    // ---- Date Format ----
    const val DATE_FORMAT = "yyyy-MM-dd"
    const val TIME_FORMAT = "HH:mm"
    const val DATETIME_FORMAT = "yyyy-MM-dd HH:mm"

    // ---- Privacy ----
    const val PRIVACY_POLICY_URL = "https://moodecho.app/privacy"
}
