package com.moodecho.app.domain.model

/**
 * Supported emotion types detected from voice analysis.
 * Each type maps to specific audio feature patterns.
 */
enum class EmotionType(val displayName: String, val emoji: String, val storageKey: String) {
    HAPPY("Happy", "😊", "happy"),
    SAD("Sad", "😢", "sad"),
    ANGRY("Angry", "😠", "angry"),
    ANXIOUS("Anxious", "😰", "anxious"),
    CALM("Calm", "😌", "calm"),
    EXCITED("Excited", "🤩", "excited"),
    NEUTRAL("Neutral", "😐", "neutral")
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
    val emotionDistribution: Map<EmotionType, Float>,  // emotion → percentage (0.0 ~ 100.0)
    val transcript: String,             // full transcript text
    val summary: String? = null,        // LLM-generated summary (optional)
    val suggestions: String? = null,    // LLM-generated suggestions (optional)
    val emotionTimeline: List<EmotionResult>  // chronological emotion data
)

/**
 * Audio feature vector produced by the AudioFeatureExtractor.
 * Used as input to the EmotionAnalyzer.
 *
 * V1 features: energy, ZCR, pause ratio (baseline)
 * V2 features: MFCC, F0, spectral centroid (added 2026-08-04)
 *
 * P3: added comments for each parameter to clarify semantics when using named arguments.
 * Parameters are listed in order: temporal features first, then spectral features.
 */
data class FeatureVector(
    val timestamp: Long,            // window start time (offset from session start, in millis)
    val averageEnergy: Float,       // mean RMS energy across frames in the window (0.0 ~ 1.0)
    val energyVariance: Float,      // variance of RMS energy (stability indicator)
    val averageZeroCrossingRate: Float,  // mean ZCR (correlates with speech rate, 0.0 ~ 1.0)
    val zeroCrossingRateVariance: Float, // ZCR variance (rhythm stability)
    val pauseRatio: Float,          // fraction of frames below energy threshold (silence, 0.0 ~ 1.0)
    val energyChangeRate: Float,    // rate of energy transitions (sudden changes indicator, 0.0 ~ 1.0)
    // === V2 Spectral Features ===
    val mfcc1Mean: Float = 0f,      // 1st MFCC coefficient mean (spectral tilt: bright ↔ dark)
    val mfcc2Mean: Float = 0f,      // 2nd MFCC coefficient mean (formant structure)
    val fundamentalFrequency: Float = 0f,  // Average F0 in Hz (pitch: high=anger/excited, low=sad)
    val f0StdDev: Float = 0f,       // F0 variability (high=excited/anxious, low=calm/sad)
    val spectralCentroid: Float = 0f,     // Spectral centroid in Hz (brightness)
    val spectralRolloff: Float = 0f       // 85% rolloff frequency in Hz
)