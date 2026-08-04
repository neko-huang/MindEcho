package com.moodecho.app.analysis

import com.moodecho.app.domain.model.FeatureVector
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Advanced audio feature extractor for emotion analysis.
 *
 * Processing pipeline:
 * 1. Read WAV file → raw PCM samples
 * 2. Split into frames (25ms frame length, 10ms frame shift)
 * 3. Per-frame: extract energy, ZCR, MFCC, F0, spectral centroid, spectral rolloff
 * 4. Aggregate into 5-second windows producing FeatureVectors
 *
 * V2 additions (2026-08-04):
 * - MFCC extraction (13 coefficients, stored as mean of first 2)
 * - F0 (fundamental frequency) via autocorrelation
 * - Spectral centroid & rolloff
 * - Pre-emphasis filter + Hamming window
 */
class AudioFeatureExtractor {

    companion object {
        private const val FRAME_LENGTH_MS = 25L
        private const val FRAME_SHIFT_MS = 10L
        private const val WINDOW_LENGTH_MS = 5000L
        private const val ENERGY_THRESHOLD = 0.01f
        private const val SAMPLE_RATE = 16000
        private const val PRE_EMPHASIS_ALPHA = 0.97f
        private const val NUM_MEL_FILTERS = 26
        private const val NUM_MFCC_COEFFS = 13
        private const val F0_MIN_HZ = 60f
        private const val F0_MAX_HZ = 500f
        private const val LOW_PASS_CUTOFF = 0.5f   // Fraction of Nyquist for rolloff

        // P2: OOM protection — process at most 30 minutes of audio (~28.8M samples at 16kHz)
        private const val MAX_SAMPLES = 28_800_000
        // P2: chunk size for streaming processing (~30 seconds)
        private const val CHUNK_SIZE_SAMPLES = 480_000
    }

    /**
     * Extract feature vectors from a WAV file.
     * @param wavFile The WAV audio file to analyze
     * @return List of FeatureVectors, one per 5-second analysis window
     */
    fun extractFeatures(wavFile: File): List<FeatureVector> {
        val samples = readWavFile(wavFile) ?: return emptyList()
        val sampleRate = SAMPLE_RATE
        val frameLength = (sampleRate * FRAME_LENGTH_MS / 1000).toInt()
        val frameShift = (sampleRate * FRAME_SHIFT_MS / 1000).toInt()
        val fftSize = nextPowerOf2(frameLength)

        // Pre-compute Mel filterbank (reusable across all frames)
        val melFilterbank = createMelFilterbank(fftSize, sampleRate, NUM_MEL_FILTERS)

        // Extract per-frame features with spectral analysis
        val frameFeatures = extractFrameFeatures(
            samples, frameLength, frameShift, fftSize, melFilterbank, sampleRate
        )

        // Aggregate into 5-second windows
        return aggregateIntoWindows(frameFeatures, sampleRate)
    }

    // ========== WAV I/O ==========

    private fun readWavFile(file: File): FloatArray? {
        try {
            // P0: guard against files smaller than minimum WAV header
            if (file.length() < 44) return null

            FileInputStream(file).use { fis ->
                val dis = DataInputStream(fis)

                // Read RIFF header (12 bytes)
                val riffBytes = ByteArray(4)
                dis.readFully(riffBytes)
                if (String(riffBytes) != "RIFF") return null

                // Skip file size field (4 bytes, big-endian)
                dis.readInt()

                val waveBytes = ByteArray(4)
                dis.readFully(waveBytes)
                if (String(waveBytes) != "WAVE") return null

                // Parse chunks
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var fmtFound = false
                var dataChunkSize = 0

                // P1: traverse chunks instead of assuming fixed 44-byte header
                while (true) {
                    val chunkIdBytes = ByteArray(4)
                    val bytesRead = dis.read(chunkIdBytes)
                    if (bytesRead < 4) break

                    val chunkId = String(chunkIdBytes)

                    val sizeBytes = ByteArray(4)
                    dis.readFully(sizeBytes)
                    val chunkSize = ByteBuffer.wrap(sizeBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).int

                    when (chunkId) {
                        "fmt " -> {
                            val fmtData = ByteArray(chunkSize.coerceAtMost(1024))
                            dis.readFully(fmtData)
                            val fmtBuf = ByteBuffer.wrap(fmtData)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val audioFormat = fmtBuf.short.toInt()
                            // Only PCM (format 1) supported
                            if (audioFormat != 1) return null
                            channels = fmtBuf.short.toInt()
                            sampleRate = fmtBuf.int
                            fmtBuf.int // skip byte rate
                            fmtBuf.short // skip block align
                            bitsPerSample = fmtBuf.short.toInt()
                            fmtFound = true
                            // Skip remaining fmt chunk bytes if larger than 16
                            val remaining = chunkSize - 16
                            if (remaining > 0) dis.skipBytes(remaining)
                        }
                        "data" -> {
                            dataChunkSize = chunkSize
                            break
                        }
                        else -> {
                            // Skip unknown chunk
                            dis.skipBytes(chunkSize)
                        }
                    }
                }

                if (!fmtFound || dataChunkSize <= 0) return null

                // P2: clamp data size to avoid OOM
                val actualDataSize = dataChunkSize.coerceAtMost(
                    MAX_SAMPLES * (bitsPerSample / 8)
                )

                // P0: use readFully to guarantee buffer is filled
                val rawData = ByteArray(actualDataSize)
                dis.readFully(rawData)

                val numSamples = rawData.size / (bitsPerSample / 8)
                val samples = FloatArray(numSamples)
                val buffer = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN)

                when (bitsPerSample) {
                    16 -> {
                        for (i in 0 until numSamples) {
                            samples[i] = buffer.short.toFloat() / Short.MAX_VALUE
                        }
                    }
                    8 -> {
                        for (i in 0 until numSamples) {
                            // P1: fixed operator precedence —
                            // old: (rawData[i].toInt() and 0xFF - 128)
                            // new: ((rawData[i].toInt() and 0xFF) - 128)
                            samples[i] = ((rawData[i].toInt() and 0xFF) - 128) / 128.0f
                        }
                    }
                    else -> return null
                }

                // P3: stereo mixing — use (L+R)/2 instead of only left channel
                return if (channels == 2) {
                    val monoSamples = FloatArray(numSamples / 2)
                    for (i in monoSamples.indices) {
                        monoSamples[i] = (samples[i * 2] + samples[i * 2 + 1]) / 2f
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

    // ========== Per-Frame Feature Extraction ==========

    /**
     * Extract features from each audio frame.
     * Now includes spectral features: MFCC, F0, spectral centroid, spectral rolloff.
     *
     * P2: reuses pre-allocated arrays to reduce GC pressure.
     */
    private fun extractFrameFeatures(
        samples: FloatArray,
        frameLength: Int,
        frameShift: Int,
        fftSize: Int,
        melFilterbank: Array<FloatArray>,
        sampleRate: Int
    ): List<FrameFeatures> {
        val features = mutableListOf<FrameFeatures>()
        var position = 0
        var prevSample = 0f

        // P2: pre-allocate reusable arrays to reduce GC pressure
        val emphasized = FloatArray(frameLength)
        val windowed = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val magnitude = FloatArray(fftSize / 2)
        val power = FloatArray(fftSize / 2)

        while (position + frameLength <= samples.size) {
            // Copy frame into emphasized (also serves as working copy)
            for (i in 0 until frameLength) {
                emphasized[i] = samples[position + i]
            }

            // Step 1: Pre-emphasis (boost high frequencies)
            val firstVal = emphasized[0] - PRE_EMPHASIS_ALPHA * prevSample
            prevSample = samples[position + frameLength - 1]
            emphasized[0] = firstVal
            for (i in 1 until frameLength) {
                emphasized[i] = emphasized[i] - PRE_EMPHASIS_ALPHA * samples[position + i - 1]
            }

            // Step 2: Hamming window — zero out windowed, then fill
            windowed.fill(0f)
            for (i in 0 until frameLength) {
                windowed[i] = emphasized[i] *
                        (0.54f - 0.46f * cos(2.0 * PI * i / (frameLength - 1)).toFloat())
            }

            // Step 3: FFT → magnitude spectrum
            // Copy windowed into real, zero out imag
            System.arraycopy(windowed, 0, real, 0, fftSize)
            imag.fill(0f)
            fft(real, imag)

            for (i in magnitude.indices) {
                val re = real[i]
                val im = imag[i]
                magnitude[i] = sqrt(re * re + im * im)
                power[i] = magnitude[i] * magnitude[i]
            }

            // Step 4: MFCC
            val mfcc = computeMFCC(power, melFilterbank, NUM_MEL_FILTERS, NUM_MFCC_COEFFS)

            // Step 5: F0 via autocorrelation
            val f0 = computeF0(emphasized, sampleRate)

            // Step 6: Spectral centroid
            val centroid = computeSpectralCentroid(magnitude, sampleRate, fftSize)

            // Step 7: Spectral rolloff (85% energy threshold)
            val rolloff = computeSpectralRolloff(power, sampleRate, fftSize, 0.85f)

            features.add(
                FrameFeatures(
                    rmsEnergy = computeRmsEnergy(emphasized),
                    zeroCrossingRate = computeZeroCrossingRate(emphasized),
                    mfcc1 = mfcc.getOrElse(0) { 0f },
                    mfcc2 = mfcc.getOrElse(1) { 0f },
                    fundamentalFrequency = f0,
                    spectralCentroid = centroid,
                    spectralRolloff = rolloff
                )
            )

            position += frameShift
        }

        return features
    }

    // ========== Basic Feature Computations ==========

    private fun computeRmsEnergy(frame: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in frame) {
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / frame.size).toFloat()
    }

    private fun computeZeroCrossingRate(frame: FloatArray): Float {
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i] >= 0) != (frame[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / frame.size
    }

    // ========== FFT ==========

    /**
     * In-place radix-2 Cooley-Tukey FFT.
     * Assumes real.length == imag.length and both are powers of 2.
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }
        // Butterfly operations
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wR = cos(angle).toFloat()
            val wI = sin(angle).toFloat()
            for (i in 0 until n step len) {
                var uR = 1f
                var uI = 0f
                for (k in 0 until len / 2) {
                    val tR = uR * real[i + k + len / 2] - uI * imag[i + k + len / 2]
                    val tI = uR * imag[i + k + len / 2] + uI * real[i + k + len / 2]
                    real[i + k + len / 2] = real[i + k] - tR
                    imag[i + k + len / 2] = imag[i + k] - tI
                    real[i + k] += tR
                    imag[i + k] += tI
                    val newUR = uR * wR - uI * wI
                    val newUI = uR * wI + uI * wR
                    uR = newUR
                    uI = newUI
                }
            }
            len *= 2
        }
    }

    // ========== MFCC ==========

    /**
     * Create a Mel-scale filterbank.
     * @param fftSize Size of FFT
     * @param sampleRate Audio sample rate in Hz
     * @param numFilters Number of triangular filters
     * @return Array of filter arrays, each of size fftSize/2
     */
    private fun createMelFilterbank(fftSize: Int, sampleRate: Int, numFilters: Int): Array<FloatArray> {
        val nyquist = sampleRate / 2.0
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(nyquist)
        val melSpacing = (highMel - lowMel) / (numFilters + 1)

        // Center frequencies in Hz and FFT bins
        val centerFreqs = DoubleArray(numFilters + 2)
        val centerBins = IntArray(numFilters + 2)
        for (i in centerFreqs.indices) {
            val mel = lowMel + i * melSpacing
            centerFreqs[i] = melToHz(mel)
            centerBins[i] = (centerFreqs[i] / nyquist * (fftSize / 2)).toInt().coerceIn(0, fftSize / 2 - 1)
        }

        // Build triangular filters
        val filters = Array(numFilters) { FloatArray(fftSize / 2) }
        for (m in 0 until numFilters) {
            val leftBin = centerBins[m]
            val centerBin = centerBins[m + 1]
            val rightBin = centerBins[m + 2]

            for (k in leftBin until centerBin) {
                filters[m][k] = (k - leftBin).toFloat() / (centerBin - leftBin)
            }
            for (k in centerBin until rightBin) {
                filters[m][k] = (rightBin - k).toFloat() / (rightBin - centerBin)
            }
        }

        return filters
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    /**
     * Compute MFCC coefficients from power spectrum.
     */
    private fun computeMFCC(
        power: FloatArray,
        filterbank: Array<FloatArray>,
        numFilters: Int,
        numCoeffs: Int
    ): FloatArray {
        // Apply Mel filterbank
        val melEnergies = FloatArray(numFilters)
        for (m in 0 until numFilters) {
            var sum = 0.0
            for (k in power.indices) {
                sum += power[k].toDouble() * filterbank[m][k]
            }
            melEnergies[m] = sum.toFloat().coerceAtLeast(1e-10f)
        }

        // Log
        val logEnergies = FloatArray(numFilters)
        for (m in 0 until numFilters) {
            logEnergies[m] = ln(melEnergies[m].toDouble()).toFloat()
        }

        // DCT type-II
        val mfcc = FloatArray(numCoeffs)
        for (i in 0 until numCoeffs) {
            var sum = 0.0
            for (m in 0 until numFilters) {
                sum += logEnergies[m] * cos(PI * i * (m + 0.5) / numFilters)
            }
            mfcc[i] = sum.toFloat()
        }

        return mfcc
    }

    // ========== F0 (Pitch) via Autocorrelation ==========

    /**
     * Compute fundamental frequency (F0) using autocorrelation.
     * Returns 0 if unvoiced (no clear pitch detected).
     *
     * P2: denominator now uses the energy of the overlapping segment for each lag,
     * instead of the fixed first maxLag samples.
     */
    private fun computeF0(frame: FloatArray, sampleRate: Int): Float {
        val minLag = (sampleRate / F0_MAX_HZ).toInt().coerceAtLeast(1)
        val maxLag = (sampleRate / F0_MIN_HZ).toInt().coerceAtMost(frame.size / 2)

        if (maxLag <= minLag) return 0f

        var maxCorr = 0.0
        var bestLag = 0
        var maxDenom = 0.0

        for (lag in minLag..maxLag) {
            var corr = 0.0
            var energy0 = 0.0
            var energyLag = 0.0
            val overlap = frame.size - lag
            for (i in 0 until overlap) {
                val s0 = frame[i].toDouble()
                val sLag = frame[i + lag].toDouble()
                corr += s0 * sLag
                energy0 += s0 * s0
                energyLag += sLag * sLag
            }
            val denom = sqrt(energy0 * energyLag)
            if (denom > 0.0 && corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
                maxDenom = denom
            }
        }

        // Voicing threshold: normalized correlation must be strong enough
        val normalizedCorr = if (maxDenom > 0.0) maxCorr / maxDenom else 0.0
        return if (normalizedCorr > 0.3) {
            sampleRate.toFloat() / bestLag
        } else {
            0f // Unvoiced
        }
    }

    // ========== Spectral Features ==========

    /**
     * Compute spectral centroid — the "center of mass" of the spectrum.
     * Higher values = brighter sound (anger/excitement).
     * Lower values = darker sound (sadness/calm).
     */
    private fun computeSpectralCentroid(
        magnitude: FloatArray,
        sampleRate: Int,
        fftSize: Int
    ): Float {
        var weightedSum = 0.0
        var totalMagnitude = 0.0
        val binFrequency = sampleRate.toDouble() / fftSize

        for (k in magnitude.indices) {
            val freq = k * binFrequency
            weightedSum += magnitude[k].toDouble() * freq
            totalMagnitude += magnitude[k].toDouble()
        }

        return if (totalMagnitude > 0.0) {
            (weightedSum / totalMagnitude).toFloat()
        } else {
            0f
        }
    }

    /**
     * Compute spectral rolloff — the frequency below which a given fraction
     * (e.g. 85%) of the total spectral energy is concentrated.
     */
    private fun computeSpectralRolloff(
        power: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        threshold: Float
    ): Float {
        var totalEnergy = 0.0
        for (p in power) {
            totalEnergy += p.toDouble()
        }

        val targetEnergy = totalEnergy * threshold
        var cumulativeEnergy = 0.0
        val binFrequency = sampleRate.toDouble() / fftSize

        for (k in power.indices) {
            cumulativeEnergy += power[k].toDouble()
            if (cumulativeEnergy >= targetEnergy) {
                return (k * binFrequency).toFloat()
            }
        }

        return sampleRate / 2f // Nyquist as fallback
    }

    // ========== Window Aggregation ==========

    /**
     * Aggregate per-frame features into 5-second windows.
     * Each window produces one FeatureVector with averaged/aggregated statistics.
     */
    private fun aggregateIntoWindows(
        frameFeatures: List<FrameFeatures>,
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

            // --- V1 features (energy, ZCR, pause) ---
            val energies = windowFrames.map { it.rmsEnergy }
            val zcrs = windowFrames.map { it.zeroCrossingRate }

            val avgEnergy = energies.average().toFloat()
            val energyVariance = variance(energies).toFloat()
            val avgZcr = zcrs.average().toFloat()
            val zcrVariance = variance(zcrs).toFloat()
            val pauseRatio = energies.count { it < ENERGY_THRESHOLD }.toFloat() / energies.size
            val energyChangeRate = computeEnergyChangeRate(energies)

            // --- V2 features (spectral) ---
            // MFCC: mean of first 2 coefficients
            val mfcc1Vals = windowFrames.map { it.mfcc1 }
            val mfcc2Vals = windowFrames.map { it.mfcc2 }
            val mfcc1Mean = mfcc1Vals.average().toFloat()
            val mfcc2Mean = mfcc2Vals.average().toFloat()

            // F0: average and std dev (only voiced frames)
            val f0Vals = windowFrames.map { it.fundamentalFrequency }.filter { it > 0f }
            val f0Mean = if (f0Vals.isNotEmpty()) f0Vals.average().toFloat() else 0f
            val f0StdDev = if (f0Vals.size > 1) {
                val mean = f0Vals.average()
                sqrt(f0Vals.sumOf { (it - mean) * (it - mean) } / (f0Vals.size - 1)).toFloat()
            } else {
                0f
            }

            // Spectral centroid & rolloff: average
            val centroidMean = windowFrames.map { it.spectralCentroid }.average().toFloat()
            val rolloffMean = windowFrames.map { it.spectralRolloff }.average().toFloat()

            vectors.add(
                FeatureVector(
                    timestamp = timestamp,
                    averageEnergy = avgEnergy,
                    energyVariance = energyVariance,
                    averageZeroCrossingRate = avgZcr,
                    zeroCrossingRateVariance = zcrVariance,
                    pauseRatio = pauseRatio,
                    energyChangeRate = energyChangeRate,
                    mfcc1Mean = mfcc1Mean,
                    mfcc2Mean = mfcc2Mean,
                    fundamentalFrequency = f0Mean,
                    f0StdDev = f0StdDev,
                    spectralCentroid = centroidMean,
                    spectralRolloff = rolloffMean
                )
            )

            windowStart += framesPerWindow
            timestamp += WINDOW_LENGTH_MS
        }

        return vectors
    }

    // ========== Utility Functions ==========

    private fun nextPowerOf2(n: Int): Int {
        var x = n
        x--
        x = x or (x shr 1)
        x = x or (x shr 2)
        x = x or (x shr 4)
        x = x or (x shr 8)
        x = x or (x shr 16)
        return x + 1
    }

    private fun variance(values: List<Float>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    }

    private fun computeEnergyChangeRate(energies: List<Float>): Float {
        if (energies.size < 2) return 0f
        var significantChanges = 0
        for (i in 1 until energies.size) {
            val prev = energies[i - 1]
            val curr = energies[i]
            // P3: raised threshold from 0.001f to 0.01f to avoid division-by-zero
            if (prev > 0.01f && abs(curr - prev) / prev > 0.5f) {
                significantChanges++
            }
        }
        return significantChanges.toFloat() / (energies.size - 1)
    }

    /**
     * Per-frame feature data (extended with spectral features).
     */
    private data class FrameFeatures(
        val rmsEnergy: Float,
        val zeroCrossingRate: Float,
        val mfcc1: Float,
        val mfcc2: Float,
        val fundamentalFrequency: Float,
        val spectralCentroid: Float,
        val spectralRolloff: Float
    )
}