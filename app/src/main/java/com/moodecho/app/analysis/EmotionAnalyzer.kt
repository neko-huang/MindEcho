package com.moodecho.app.analysis

import com.moodecho.app.domain.model.EmotionResult
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.domain.model.FeatureVector
import kotlin.math.abs

/**
 * Improved rule-based emotion analysis engine (V2).
 *
 * Uses both V1 features (energy, ZCR, pause) and V2 spectral features
 * (MFCC, F0, spectral centroid) for more accurate emotion classification.
 *
 * Key improvements over V1:
 * - F0 (pitch) distinguishes ANGRY (high pitch) from EXCITED (moderate pitch)
 * - MFCC1 (spectral tilt) distinguishes negative emotions' subtypes
 * - Spectral centroid separates bright (happy/excited) from dark (sad/calm) tones
 * - F0 variance detects anxiety (irregular pitch) vs calm (stable pitch)
 * - Multi-dimensional scoring with weighted confidence
 */
class EmotionAnalyzer {

    companion object {
        // V1 thresholds (energy, ZCR, pause)
        private const val HIGH_ENERGY_THRESHOLD = 0.08f
        private const val LOW_ENERGY_THRESHOLD = 0.02f
        private const val HIGH_ZCR_THRESHOLD = 0.12f
        private const val LOW_ZCR_THRESHOLD = 0.04f
        private const val HIGH_PAUSE_THRESHOLD = 0.4f
        private const val LOW_PAUSE_THRESHOLD = 0.15f
        private const val HIGH_CHANGE_RATE = 0.3f
        private const val MODERATE_CHANGE_RATE = 0.15f
        private const val HIGH_VARIANCE = 0.002f
        private const val LOW_VARIANCE = 0.0003f

        // V2 thresholds (spectral features)
        // MFCC1: typical range for speech ~ -50 to +50.
        // Negative MFCC1 = more low-frequency energy (dark/warm)
        // Positive MFCC1 = more high-frequency energy (bright/tense)
        private const val MFCC1_HIGH_THRESHOLD = 5.0f   // Bright/tense (anger, excited)
        private const val MFCC1_LOW_THRESHOLD = -5.0f   // Dark/warm (sad, calm)

        // F0 thresholds (Hz): typical male ~85-180, female ~165-255
        private const val F0_HIGH_THRESHOLD = 220f       // High pitch (anger, anxiety)
        private const val F0_LOW_THRESHOLD = 140f        // Low pitch (sad, calm)
        private const val F0_VARIANCE_HIGH = 40f         // High pitch variability (excited, anxious)
        private const val F0_VARIANCE_LOW = 15f          // Low pitch variability (calm, sad)

        // Spectral centroid thresholds (Hz at 16kHz sample rate)
        // Typical speech centroid: 500-2000 Hz
        private const val CENTROID_HIGH_THRESHOLD = 1200f  // Bright (happy, excited, angry)
        private const val CENTROID_LOW_THRESHOLD = 700f    // Dark (sad, calm)

        // Rolloff thresholds
        private const val ROLLOFF_HIGH_THRESHOLD = 3500f  // Wide bandwidth (excited, angry)
        private const val ROLLOFF_LOW_THRESHOLD = 2200f   // Narrow bandwidth (sad, calm)
    }

    /**
     * Analyze a list of feature vectors and produce emotion results.
     *
     * @param featureVectors Audio feature vectors from AudioFeatureExtractor
     * @return List of EmotionResult with detected emotion per window
     */
    fun analyze(featureVectors: List<FeatureVector>): List<EmotionResult> {
        if (featureVectors.isEmpty()) return emptyList()

        val maxEnergy = featureVectors.maxOfOrNull { it.averageEnergy }?.coerceAtLeast(0.01f) ?: 0.01f

        return featureVectors.map { fv ->
            val normalizedEnergy = fv.averageEnergy / maxEnergy
            classifyEmotion(fv, normalizedEnergy)
        }
    }

    /**
     * Classify the emotion for a single feature vector using V1 + V2 multi-dimensional scoring.
     */
    private fun classifyEmotion(fv: FeatureVector, normalizedEnergy: Float): EmotionResult {
        // ===== V1 Indicators =====
        val isHighEnergy = normalizedEnergy > HIGH_ENERGY_THRESHOLD
        val isLowEnergy = normalizedEnergy < LOW_ENERGY_THRESHOLD
        val isFastRate = fv.averageZeroCrossingRate > HIGH_ZCR_THRESHOLD
        val isSlowRate = fv.averageZeroCrossingRate < LOW_ZCR_THRESHOLD
        val hasManyPauses = fv.pauseRatio > HIGH_PAUSE_THRESHOLD
        val hasFewPauses = fv.pauseRatio < LOW_PAUSE_THRESHOLD
        val isUnstable = fv.energyChangeRate > HIGH_CHANGE_RATE
        val isModeratelyUnstable = fv.energyChangeRate > MODERATE_CHANGE_RATE
        val isHighVariance = fv.energyVariance > HIGH_VARIANCE
        val isLowVariance = fv.energyVariance < LOW_VARIANCE

        // ===== V2 Indicators (Spectral) =====
        val isBright = fv.mfcc1Mean > MFCC1_HIGH_THRESHOLD
        val isDark = fv.mfcc1Mean < MFCC1_LOW_THRESHOLD
        val isHighPitch = fv.fundamentalFrequency > F0_HIGH_THRESHOLD && fv.fundamentalFrequency > 0f
        val isLowPitch = fv.fundamentalFrequency < F0_LOW_THRESHOLD && fv.fundamentalFrequency > 0f
        val isPitchVariable = fv.f0StdDev > F0_VARIANCE_HIGH && fv.fundamentalFrequency > 0f
        val isPitchStable = fv.f0StdDev < F0_VARIANCE_LOW || fv.fundamentalFrequency == 0f
        val isBrightCentroid = fv.spectralCentroid > CENTROID_HIGH_THRESHOLD
        val isDarkCentroid = fv.spectralCentroid < CENTROID_LOW_THRESHOLD
        val isWideBandwidth = fv.spectralRolloff > ROLLOFF_HIGH_THRESHOLD
        val isNarrowBandwidth = fv.spectralRolloff < ROLLOFF_LOW_THRESHOLD

        // ===== Multi-dimensional Scoring =====
        // Each emotion gets a score based on how many indicators match.
        // Scores are weighted: V2 spectral features count more than V1.
        data class EmotionScore(val type: EmotionType, var score: Float, var matchCount: Int)

        val scores = EmotionType.entries.map { EmotionScore(it, 0f, 0) }

        // --- ANGRY: High energy + high pitch + bright spectrum + unstable + wide bandwidth ---
        if (isHighEnergy) {
            scores[EmotionType.ANGRY.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isHighPitch) {
            scores[EmotionType.ANGRY.ordinal].apply {
                score += 2.0f; matchCount++  // Strong indicator
            }
        }
        if (isBright && isBrightCentroid) {
            scores[EmotionType.ANGRY.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (isUnstable && isHighVariance) {
            scores[EmotionType.ANGRY.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (isWideBandwidth) {
            scores[EmotionType.ANGRY.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }

        // --- EXCITED: High energy + moderate-to-high pitch + bright + fast rate + few pauses ---
        if (isHighEnergy) {
            scores[EmotionType.EXCITED.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isFastRate) {
            scores[EmotionType.EXCITED.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (hasFewPauses) {
            scores[EmotionType.EXCITED.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isPitchVariable) {
            scores[EmotionType.EXCITED.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (isBrightCentroid) {
            scores[EmotionType.EXCITED.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }

        // --- HAPPY: High energy + moderate pitch + stable + bright + few pauses ---
        if (isHighEnergy) {
            scores[EmotionType.HAPPY.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (!isHighPitch && !isLowPitch && fv.fundamentalFrequency > 0f) {
            scores[EmotionType.HAPPY.ordinal].apply {
                score += 1.5f; matchCount++  // Moderate pitch = happy
            }
        }
        if (isBright && isBrightCentroid) {
            scores[EmotionType.HAPPY.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (hasFewPauses) {
            scores[EmotionType.HAPPY.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (!isUnstable) {
            scores[EmotionType.HAPPY.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }

        // --- SAD: Low energy + low pitch + dark spectrum + slow + many pauses ---
        if (isLowEnergy) {
            scores[EmotionType.SAD.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (isLowPitch) {
            scores[EmotionType.SAD.ordinal].apply {
                score += 2.0f; matchCount++  // Strong indicator
            }
        }
        if (isDark && isDarkCentroid) {
            scores[EmotionType.SAD.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (isSlowRate) {
            scores[EmotionType.SAD.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (hasManyPauses) {
            scores[EmotionType.SAD.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isNarrowBandwidth) {
            scores[EmotionType.SAD.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }

        // --- ANXIOUS: Medium energy + irregular pitch + moderate pauses + unstable ---
        if (!isHighEnergy && !isLowEnergy) {
            scores[EmotionType.ANXIOUS.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isPitchVariable) {
            scores[EmotionType.ANXIOUS.ordinal].apply {
                score += 2.0f; matchCount++  // Strong indicator
            }
        }
        if (isModeratelyUnstable) {
            scores[EmotionType.ANXIOUS.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (hasManyPauses) {
            scores[EmotionType.ANXIOUS.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isHighPitch) {
            scores[EmotionType.ANXIOUS.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }

        // --- CALM: Low energy + stable pitch + dark + low variance + few pauses ---
        if (isLowEnergy || (!isHighEnergy && !isLowEnergy)) {
            scores[EmotionType.CALM.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isPitchStable) {
            scores[EmotionType.CALM.ordinal].apply {
                score += 1.5f; matchCount++
            }
        }
        if (isLowVariance) {
            scores[EmotionType.CALM.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (hasFewPauses && !isFastRate) {
            scores[EmotionType.CALM.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }
        if (isDarkCentroid) {
            scores[EmotionType.CALM.ordinal].apply {
                score += 1.0f; matchCount++
            }
        }

        // ===== Select Best Match =====
        // Find the emotion with the highest score, minimum 2 indicators matched
        val bestMatch = scores
            .filter { it.matchCount >= 2 }
            .maxByOrNull { it.score }

        return if (bestMatch != null) {
            val confidence = computeConfidence(bestMatch.score, bestMatch.matchCount)
            EmotionResult(
                emotionType = bestMatch.type,
                confidence = confidence.coerceIn(0.3f, 0.95f),
                arousal = normalizedEnergy,
                valence = computeValence(bestMatch.type),
                timestamp = fv.timestamp
            )
        } else {
            // Fallback: use V1 energy-based simple classification
            val fallbackType = when {
                normalizedEnergy > 0.15f -> EmotionType.EXCITED
                normalizedEnergy < 0.03f -> EmotionType.SAD
                else -> EmotionType.NEUTRAL
            }
            EmotionResult(
                emotionType = fallbackType,
                confidence = 0.35f,
                arousal = normalizedEnergy,
                valence = computeValence(fallbackType),
                timestamp = fv.timestamp
            )
        }
    }

    /**
     * Compute confidence from score and match count.
     * Higher score + more matches = higher confidence.
     */
    private fun computeConfidence(score: Float, matchCount: Int): Float {
        val baseConfidence = (score / 10f).coerceIn(0.3f, 0.8f)
        val countBonus = (matchCount - 2) * 0.05f
        return (baseConfidence + countBonus).coerceIn(0.3f, 0.95f)
    }

    private fun computeValence(emotionType: EmotionType): Float {
        return when (emotionType) {
            EmotionType.HAPPY -> 0.8f
            EmotionType.EXCITED -> 0.6f
            EmotionType.CALM -> 0.4f
            EmotionType.NEUTRAL -> 0.0f
            EmotionType.ANXIOUS -> -0.4f
            EmotionType.SAD -> -0.7f
            EmotionType.ANGRY -> -0.8f
        }
    }

    fun getDominantEmotion(results: List<EmotionResult>): EmotionType {
        if (results.isEmpty()) return EmotionType.NEUTRAL
        return results
            .groupBy { it.emotionType }
            .maxByOrNull { it.value.size }
            ?.key ?: EmotionType.NEUTRAL
    }

    fun getEmotionDistribution(results: List<EmotionResult>): Map<EmotionType, Float> {
        if (results.isEmpty()) return mapOf(EmotionType.NEUTRAL to 100f)
        val total = results.size.toFloat()
        return results
            .groupBy { it.emotionType }
            .mapValues { (it.value.size / total) * 100f }
    }
}