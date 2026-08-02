package com.moodecho.app.analysis

import com.moodecho.app.domain.model.FeatureVector
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Extracts audio features from WAV/PCM files for emotion analysis.
 *
 * Processing pipeline:
 * 1. Read WAV file and extract raw PCM samples
 * 2. Split into frames (25ms frame length, 10ms frame shift)
 * 3. Per-frame: compute RMS energy, zero-crossing rate
 * 4. Aggregate into windows (5 seconds) producing FeatureVectors
 */
class AudioFeatureExtractor {

    companion object {
        private const val FRAME_LENGTH_MS = 25L     // frame duration in milliseconds
        private const val FRAME_SHIFT_MS = 10L      // frame shift in milliseconds
        private const val WINDOW_LENGTH_MS = 5000L   // analysis window in milliseconds
        private const val ENERGY_THRESHOLD = 0.01f   // below this = silence/pause
        private const val SAMPLE_RATE = 16000        // expected sample rate
    }

    /**
     * Extract feature vectors from a WAV file.
     * @param wavFile The WAV audio file to analyze
     * @return List of FeatureVectors, one per analysis window
     */
    fun extractFeatures(wavFile: File): List<FeatureVector> {
        val samples = readWavFile(wavFile) ?: return emptyList()
        val sampleRate = SAMPLE_RATE

        val frameLength = (sampleRate * FRAME_LENGTH_MS / 1000).toInt()
        val frameShift = (sampleRate * FRAME_SHIFT_MS / 1000).toInt()
        val windowShift = (sampleRate * WINDOW_LENGTH_MS / 1000).toInt()

        // Extract per-frame features
        val frameFeatures = extractFrameFeatures(samples, frameLength, frameShift)

        // Aggregate into windows
        return aggregateIntoWindows(frameFeatures, FRAME_SHIFT_MS, windowShift, sampleRate)
    }

    /**
     * Read a WAV file and return mono 16-bit PCM samples as float array.
     * Handles standard WAV header parsing.
     */
    private fun readWavFile(file: File): FloatArray? {
        try {
            FileInputStream(file).use { fis ->
                // Read WAV header (44 bytes minimum)
                val header = ByteArray(44)
                if (fis.read(header) < 44) return null

                // Validate RIFF header
                val riff = String(header, 0, 4)
                if (riff != "RIFF") return null

                val wave = String(header, 8, 4)
                if (wave != "WAVE") return null

                // Parse audio format details from header
                val channels = ByteBuffer.wrap(header, 22, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val sampleRate = ByteBuffer.wrap(header, 24, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val bitsPerSample = ByteBuffer.wrap(header, 34, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()

                // Read remaining audio data
                val dataSize = (file.length() - 44).toInt()
                val rawData = ByteArray(dataSize)
                fis.read(rawData)

                // Convert to float samples (normalize to -1.0 ~ 1.0)
                val numSamples = rawData.size / (bitsPerSample / 8)
                val samples = FloatArray(numSamples)

                val buffer = ByteBuffer.wrap(rawData)
                    .order(ByteOrder.LITTLE_ENDIAN)

                when (bitsPerSample) {
                    16 -> {
                        for (i in 0 until numSamples) {
                            samples[i] = buffer.short.toFloat() / Short.MAX_VALUE
                        }
                    }
                    8 -> {
                        // 8-bit WAV is unsigned (0-255), center at 128
                        for (i in 0 until numSamples) {
                            samples[i] = (rawData[i].toInt() and 0xFF - 128) / 128.0f
                        }
                    }
                    else -> return null // Unsupported bit depth
                }

                // If stereo, take only the first channel
                return if (channels == 2) {
                    val monoSamples = FloatArray(numSamples / 2)
                    for (i in monoSamples.indices) {
                        monoSamples[i] = samples[i * 2]
                    }
                    monoSamples
                } else {
                    samples
                }
            }
        } catch (e: IOException) {
            return null
        }
    }

    /**
     * Extract per-frame features (RMS energy, zero-crossing rate).
     */
    private fun extractFrameFeatures(
        samples: FloatArray,
        frameLength: Int,
        frameShift: Int
    ): List<FrameFeatures> {
        val features = mutableListOf<FrameFeatures>()

        var position = 0
        while (position + frameLength <= samples.size) {
            val frame = samples.copyOfRange(position, position + frameLength)
            features.add(
                FrameFeatures(
                    rmsEnergy = computeRmsEnergy(frame),
                    zeroCrossingRate = computeZeroCrossingRate(frame)
                )
            )
            position += frameShift
        }

        return features
    }

    /**
     * Compute RMS (Root Mean Square) energy of a frame.
     */
    private fun computeRmsEnergy(frame: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in frame) {
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / frame.size).toFloat()
    }

    /**
     * Compute Zero-Crossing Rate of a frame.
     * Counts sign changes and normalizes by frame length.
     */
    private fun computeZeroCrossingRate(frame: FloatArray): Float {
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i] >= 0) != (frame[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / frame.size
    }

    /**
     * Aggregate per-frame features into analysis windows.
     * Each window produces one FeatureVector.
     */
    private fun aggregateIntoWindows(
        frameFeatures: List<FrameFeatures>,
        frameShiftMs: Long,
        windowShiftSamples: Int,
        sampleRate: Int
    ): List<FeatureVector> {
        if (frameFeatures.isEmpty()) return emptyList()

        val framesPerWindow = (WINDOW_LENGTH_MS / FRAME_SHIFT_MS).toInt()
        val vectors = mutableListOf<FeatureVector>()

        var windowStart = 0
        var timestamp = 0L

        while (windowStart < frameFeatures.size) {
            val windowEnd = minOf(windowStart + framesPerWindow, frameFeatures.size)
            val windowFrames = frameFeatures.subList(windowStart, windowEnd)

            if (windowFrames.isEmpty()) break

            // Compute aggregated statistics
            val energies = windowFrames.map { it.rmsEnergy }
            val zcrs = windowFrames.map { it.zeroCrossingRate }

            val avgEnergy = energies.average().toFloat()
            val energyVariance = variance(energies).toFloat()
            val avgZcr = zcrs.average().toFloat()
            val zcrVariance = variance(zcrs).toFloat()

            // Pause ratio: fraction of frames below energy threshold
            val pauseRatio = energies.count { it < ENERGY_THRESHOLD }.toFloat() / energies.size

            // Energy change rate: fraction of frame transitions with significant energy shift
            val energyChangeRate = computeEnergyChangeRate(energies)

            vectors.add(
                FeatureVector(
                    timestamp = timestamp,
                    averageEnergy = avgEnergy,
                    energyVariance = energyVariance,
                    averageZeroCrossingRate = avgZcr,
                    zeroCrossingRateVariance = zcrVariance,
                    pauseRatio = pauseRatio,
                    energyChangeRate = energyChangeRate
                )
            )

            windowStart += framesPerWindow
            timestamp += WINDOW_LENGTH_MS
        }

        return vectors
    }

    /**
     * Compute the variance of a list of float values.
     */
    private fun variance(values: List<Float>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    }

    /**
     * Compute the rate of significant energy transitions between consecutive frames.
     * A transition is "significant" if the energy change exceeds 50% of the current frame's energy.
     */
    private fun computeEnergyChangeRate(energies: List<Float>): Float {
        if (energies.size < 2) return 0f
        var significantChanges = 0
        for (i in 1 until energies.size) {
            val prevEnergy = energies[i - 1]
            val currEnergy = energies[i]
            if (prevEnergy > 0.001f && abs(currEnergy - prevEnergy) / prevEnergy > 0.5f) {
                significantChanges++
            }
        }
        return significantChanges.toFloat() / (energies.size - 1)
    }

    /**
     * Intermediate per-frame feature data used during aggregation.
     */
    private data class FrameFeatures(
        val rmsEnergy: Float,
        val zeroCrossingRate: Float
    )
}
