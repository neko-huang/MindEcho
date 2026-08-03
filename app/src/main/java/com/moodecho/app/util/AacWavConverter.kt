package com.moodecho.app.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts AAC audio files to WAV format (16kHz, mono, 16-bit PCM).
 *
 * Uses Android's MediaExtractor to demux the AAC container and MediaCodec
 * to decode AAC frames into raw PCM samples. The decoded PCM is then
 * downmixed to mono and resampled to 16 kHz to match the format expected
 * by [com.moodecho.app.analysis.AudioFeatureExtractor].
 *
 * All processing happens on-device without any third-party libraries.
 */
object AacWavConverter {

    /** Target sample rate for analysis (must match AudioFeatureExtractor expectations). */
    private const val TARGET_SAMPLE_RATE = 16000

    /** Target channel count: mono. */
    private const val TARGET_CHANNELS = 1

    /** Target bit depth: 16-bit signed PCM. */
    private const val TARGET_BITS_PER_SAMPLE = 16

    /**
     * Convert an AAC file to a WAV file with the target format.
     *
     * @param aacFile Input AAC (or AAC_ADTS) file produced by MediaRecorder.
     * @param wavFile Output WAV file path. Will be created or overwritten.
     * @return `true` if conversion succeeded, `false` otherwise.
     */
    fun convert(aacFile: File, wavFile: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(aacFile.absolutePath)
        } catch (e: Exception) {
            return false
        }

        // Find the first audio track
        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                break
            }
        }
        if (audioTrackIndex < 0 || inputFormat == null) {
            extractor.release()
            return false
        }

        extractor.selectTrack(audioTrackIndex)
        val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        // Retrieve original sample rate and channel count (with safe defaults)
        val inputSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100

        val inputChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1

        // Create and configure the AAC decoder
        val codec = try {
            MediaCodec.createDecoderByType(inputMime)
        } catch (e: Exception) {
            extractor.release()
            return false
        }

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            // Decode all AAC frames into PCM samples
            val pcmSamples = decodeToPcm(extractor, codec, inputSampleRate, inputChannels)

            codec.stop()
            codec.release()
            extractor.release()

            if (pcmSamples.isEmpty()) return false

            // Write the WAV file
            return writeWavFile(wavFile, pcmSamples)
        } catch (e: Exception) {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
            return false
        }
    }

    /**
     * Run the MediaCodec decode loop, collecting all PCM output into a ShortArray.
     *
     * After decoding, the samples are downmixed to mono and resampled to the
     * target sample rate.
     */
    private fun decodeToPcm(
        extractor: MediaExtractor,
        codec: MediaCodec,
        initialSampleRate: Int,
        initialChannels: Int
    ): ShortArray {
        val pcmBytes = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        // The actual output format may differ from the input; update when changed
        var outputSampleRate = initialSampleRate
        var outputChannels = initialChannels

        while (!sawOutputEOS) {
            // ---- Feed input to the decoder ----
            if (!sawInputEOS) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        // End of input stream
                        codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(
                            inputBufferIndex, 0, sampleSize,
                            extractor.sampleTime, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // ---- Drain decoded output ----
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outputChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                outputBufferIndex >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                    // Skip codec-config buffers (no actual audio data)
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmBytes.write(chunk)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
                // INFO_TRY_AGAIN_LATER or other negative: just loop again
            }
        }

        // Convert raw bytes to 16-bit signed samples (little-endian, native on Android)
        val rawData = pcmBytes.toByteArray()
        if (rawData.size < 2) return ShortArray(0)

        val numSamples = rawData.size / 2
        val samples = ShortArray(numSamples)
        ByteBuffer.wrap(rawData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        // Downmix to mono if the source has multiple channels
        val monoSamples = if (outputChannels > 1) {
            downmixToMono(samples, outputChannels)
        } else {
            samples
        }

        // Resample to the target sample rate
        return if (outputSampleRate != TARGET_SAMPLE_RATE) {
            resample(monoSamples, outputSampleRate, TARGET_SAMPLE_RATE)
        } else {
            monoSamples
        }
    }

    /**
     * Downmix multi-channel PCM to mono by averaging all channels per frame.
     */
    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val numFrames = samples.size / channels
        val mono = ShortArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += samples[i * channels + c].toInt()
            }
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    /**
     * Resample PCM data from [fromRate] to [toRate] using linear interpolation.
     *
     * Linear interpolation is sufficient for the down-sampling case (44.1 kHz → 16 kHz)
     * used in this app and avoids the complexity of polyphase anti-alias filters.
     */
    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val newLength = (samples.size * ratio).toInt().coerceAtLeast(1)
        val result = ShortArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()
            val fraction = srcPos - srcIndex
            if (srcIndex + 1 < samples.size) {
                val s1 = samples[srcIndex].toInt()
                val s2 = samples[srcIndex + 1].toInt()
                result[i] = (s1 + (s2 - s1) * fraction).toInt().toShort()
            } else if (srcIndex < samples.size) {
                result[i] = samples[srcIndex]
            }
        }
        return result
    }

    /**
     * Write a standard 44-byte-header WAV file containing the given 16-bit PCM samples.
     */
    private fun writeWavFile(file: File, samples: ShortArray): Boolean {
        return try {
            FileOutputStream(file).use { fos ->
                val dataSize = samples.size * 2
                val byteRate = TARGET_SAMPLE_RATE * TARGET_CHANNELS * (TARGET_BITS_PER_SAMPLE / 8)
                val blockAlign = TARGET_CHANNELS * (TARGET_BITS_PER_SAMPLE / 8)

                // ---- RIFF header ----
                fos.write("RIFF".toByteArray())
                writeLittleEndianInt(fos, 36 + dataSize) // ChunkSize = file size - 8
                fos.write("WAVE".toByteArray())

                // ---- fmt sub-chunk ----
                fos.write("fmt ".toByteArray())
                writeLittleEndianInt(fos, 16)             // Subchunk1Size for PCM
                writeLittleEndianShort(fos, 1)            // AudioFormat = PCM
                writeLittleEndianShort(fos, TARGET_CHANNELS)
                writeLittleEndianInt(fos, TARGET_SAMPLE_RATE)
                writeLittleEndianInt(fos, byteRate)
                writeLittleEndianShort(fos, blockAlign)
                writeLittleEndianShort(fos, TARGET_BITS_PER_SAMPLE)

                // ---- data sub-chunk ----
                fos.write("data".toByteArray())
                writeLittleEndianInt(fos, dataSize)

                // Write PCM sample data (little-endian)
                val byteBuffer = ByteBuffer.allocate(dataSize)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (s in samples) {
                    byteBuffer.putShort(s)
                }
                fos.write(byteBuffer.array())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Write a 32-bit little-endian integer to the output stream. */
    private fun writeLittleEndianInt(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
        fos.write((value shr 16) and 0xFF)
        fos.write((value shr 24) and 0xFF)
    }

    /** Write a 16-bit little-endian integer to the output stream. */
    private fun writeLittleEndianShort(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
    }
}
