package com.moodecho.app.domain.model

/**
 * Supported emotion types detected from voice analysis.
 * Each type maps to specific audio feature patterns.
 */
enum class EmotionType(val displayName: String, val emoji: String) {
    HAPPY("Happy", "😊"),
    SAD("Sad", "😢"),
    ANGRY("Angry", "😠"),
    ANXIOUS("Anxious", "😰"),
    CALM("Calm", "😌"),
    EXCITED("Excited", "🤩"),
    NEUTRAL("Neutral", "😐")
}

/**
 * Result of emotion analysis for a time window.
 * Contains the detected emotion and associated metrics.
 */
data class EmotionResult(
    val emotionType: EmotionType,
    val confidence: Float,      // 0.0 ~ 1.0
    val arousal: Float,         // energy level: low (0) to high (1)
    val valence: Float,         // positivity: negative (-1) to positive (1)
    val timestamp: Long         // offset from session start in millis
)

/**
 * Aggregated report for a recording session.
 * Combines transcript and emotion data into a presentable summary.
 */
data class SessionReport(
    val sessionId: Long,
    val title: String,
    val duration: Long,
    val dominantEmotion: EmotionType,
    val emotionDistribution: Map<EmotionType, Float>,  // emotion → percentage
    val transcript: String,             // full transcript text
    val summary: String? = null,        // LLM-generated summary (optional)
    val suggestions: String? = null,    // LLM-generated suggestions (optional)
    val emotionTimeline: List<EmotionResult>  // chronological emotion data
)

/**
 * Audio feature vector produced by the AudioFeatureExtractor.
 * Used as input to the EmotionAnalyzer.
 */
data class FeatureVector(
    val timestamp: Long,            // window start time (offset from session start)
    val averageEnergy: Float,       // mean RMS energy across frames in the window
    val energyVariance: Float,      // variance of RMS energy (stability indicator)
    val averageZeroCrossingRate: Float,  // mean ZCR (correlates with speech rate)
    val zeroCrossingRateVariance: Float, // ZCR variance (rhythm stability)
    val pauseRatio: Float,          // fraction of frames below energy threshold (silence)
    val energyChangeRate: Float     // rate of energy transitions (sudden changes indicator)
)
