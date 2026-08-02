package com.moodecho.app.analysis

import com.moodecho.app.domain.model.EmotionResult
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.domain.model.FeatureVector

/**
 * Rule-based emotion analysis engine.
 *
 * Maps audio feature vectors to emotion categories using heuristic rules:
 * - High energy + fast speech rate + few pauses → EXCITED / HAPPY
 * - Low energy + slow speech rate + many pauses → SAD
 * - High energy + unstable + frequent sudden changes → ANGRY
 * - Medium energy + irregular pauses + unstable rate → ANXIOUS
 * - Stable energy + regular rate + few pauses → CALM
 * - Otherwise → NEUTRAL
 *
 * This is a lightweight approach that works fully offline without ML models.
 * Future versions could integrate a trained SER (Speech Emotion Recognition) model.
 */
class EmotionAnalyzer {

    companion object {
        // Energy thresholds (normalized RMS energy, range ~0.0 to ~0.5 for speech)
        private const val HIGH_ENERGY_THRESHOLD = 0.08f
        private const val LOW_ENERGY_THRESHOLD = 0.02f

        // Zero-crossing rate thresholds (correlates with speech rate / pitch)
        private const val HIGH_ZCR_THRESHOLD = 0.15f
        private const val LOW_ZCR_THRESHOLD = 0.05f

        // Pause ratio thresholds
        private const val HIGH_PAUSE_THRESHOLD = 0.4f   // >40% silence = many pauses
        private const val LOW_PAUSE_THRESHOLD = 0.15f    // <15% silence = few pauses

        // Energy change rate thresholds (sudden energy shifts)
        private const val HIGH_CHANGE_RATE = 0.3f        // >30% frames with significant changes
        private const val MODERATE_CHANGE_RATE = 0.15f   // 15-30% moderate instability

        // Energy variance thresholds
        private const val HIGH_VARIANCE = 0.002f
        private const val LOW_VARIANCE = 0.0003f
    }

    /**
     * Analyze a list of feature vectors and produce emotion results.
     * Each FeatureVector maps to one EmotionResult.
     *
     * @param featureVectors Audio feature vectors from AudioFeatureExtractor
     * @return List of EmotionResult with detected emotion per window
     */
    fun analyze(featureVectors: List<FeatureVector>): List<EmotionResult> {
        if (featureVectors.isEmpty()) return emptyList()

        // Normalize energy across the session for relative comparisons
        val maxEnergy = featureVectors.maxOfOrNull { it.averageEnergy }?.coerceAtLeast(0.01f) ?: 0.01f

        return featureVectors.map { fv ->
            val normalizedEnergy = fv.averageEnergy / maxEnergy
            classifyEmotion(fv, normalizedEnergy)
        }
    }

    /**
     * Classify the emotion for a single feature vector using rule-based logic.
     */
    private fun classifyEmotion(fv: FeatureVector, normalizedEnergy: Float): EmotionResult {
        // Extract key indicators
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

        // Rule-based classification with confidence scoring
        val candidates = mutableListOf<Pair<EmotionType, Float>>()

        // EXCITED: High energy + fast speech + few pauses
        if (isHighEnergy && isFastRate && hasFewPauses) {
            val confidence = computeConfidence(normalizedEnergy, 0.08f, 0.15f)
            candidates.add(EmotionType.EXCITED to confidence)
        }

        // HAPPY: High energy + moderate-to-fast rate + few pauses
        if (isHighEnergy && !isSlowRate && hasFewPauses && !isUnstable) {
            val confidence = computeConfidence(normalizedEnergy, 0.06f, 0.12f)
            candidates.add(EmotionType.HAPPY to confidence)
        }

        // SAD: Low energy + slow rate + many pauses
        if (isLowEnergy && isSlowRate && hasManyPauses) {
            val confidence = computeConfidence(1.0f - normalizedEnergy, 0.02f, 0.06f)
            candidates.add(EmotionType.SAD to confidence)
        }

        // ANGRY: High energy + unstable + frequent sudden changes
        if (isHighEnergy && isUnstable && isHighVariance) {
            val confidence = computeConfidence(fv.energyChangeRate, 0.3f, 0.6f)
            candidates.add(EmotionType.ANGRY to confidence)
        }

        // ANXIOUS: Medium energy + irregular pauses + unstable rate
        if (!isHighEnergy && !isLowEnergy && isModeratelyUnstable && hasManyPauses) {
            val confidence = computeConfidence(fv.energyChangeRate, 0.15f, 0.35f)
            candidates.add(EmotionType.ANXIOUS to confidence)
        }

        // CALM: Stable energy + regular rate + few pauses
        if (isLowVariance && !isUnstable && hasFewPauses) {
            val confidence = computeConfidence(1.0f - fv.energyChangeRate, 0.0f, 0.15f)
            candidates.add(EmotionType.CALM to confidence)
        }

        // Select the best match, or default to NEUTRAL
        val bestMatch = candidates.maxByOrNull { it.second }

        return if (bestMatch != null && bestMatch.second > 0.3f) {
            EmotionResult(
                emotionType = bestMatch.first,
                confidence = bestMatch.second.coerceIn(0f, 1f),
                arousal = normalizedEnergy,
                valence = computeValence(bestMatch.first),
                timestamp = fv.timestamp
            )
        } else {
            EmotionResult(
                emotionType = EmotionType.NEUTRAL,
                confidence = 0.5f,
                arousal = normalizedEnergy,
                valence = 0f,
                timestamp = fv.timestamp
            )
        }
    }

    /**
     * Compute a confidence score based on how far a value exceeds a threshold.
     * Maps (value - minThreshold) / (maxThreshold - minThreshold) to [0, 1].
     */
    private fun computeConfidence(value: Float, minThreshold: Float, maxThreshold: Float): Float {
        if (maxThreshold <= minThreshold) return 0.5f
        return ((value - minThreshold) / (maxThreshold - minThreshold)).coerceIn(0f, 1f)
    }

    /**
     * Map an emotion type to a valence value.
     * Positive valence = pleasant emotion, negative = unpleasant.
     */
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

    /**
     * Compute the dominant emotion from a list of emotion results.
     * Returns the most frequently occurring emotion type.
     */
    fun getDominantEmotion(results: List<EmotionResult>): EmotionType {
        if (results.isEmpty()) return EmotionType.NEUTRAL

        return results
            .groupBy { it.emotionType }
            .maxByOrNull { it.value.size }
            ?.key ?: EmotionType.NEUTRAL
    }

    /**
     * Compute the distribution of emotions as percentages.
     * @return Map of EmotionType to its percentage (0-100)
     */
    fun getEmotionDistribution(results: List<EmotionResult>): Map<EmotionType, Float> {
        if (results.isEmpty()) return mapOf(EmotionType.NEUTRAL to 100f)

        val total = results.size.toFloat()
        return results
            .groupBy { it.emotionType }
            .mapValues { (it.value.size / total) * 100f }
    }
}
