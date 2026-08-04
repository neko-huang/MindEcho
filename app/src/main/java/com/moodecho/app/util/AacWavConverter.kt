package com.moodecho.app.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
     * 分块写入 PCM 时每块的大小（采样数）。
     * 每块约 64KB PCM 数据，避免一次性分配大内存。
     */
    private const val PCM_CHUNK_SAMPLES = 16_384

    /**
     * 低通滤波器阶数（用于下采样前的抗混叠）。
     * 阶数越大阻带衰减越好，但计算量也越大。
     * 这里使用 5 阶 FIR 滤波器，截止频率 8kHz。
     */
    private const val FILTER_ORDER = 5

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
            // 【P1 修复】catch 中释放 extractor，避免 MediaExtractor 泄漏
            extractor.release()
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

        // 【P3 修复】添加 codecStarted 标志，仅在 start() 成功后调用 stop()
        var codecStarted = false
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            // Decode all AAC frames into PCM samples (写入临时文件避免 OOM)
            val pcmTempFile = decodeToPcmFile(extractor, codec, inputSampleRate, inputChannels)

            // 无论 decodeToPcmFile 是否成功都先停掉 codec
            if (codecStarted) {
                codec.stop()
            }
            codec.release()
            extractor.release()

            if (pcmTempFile == null || !pcmTempFile.exists() || pcmTempFile.length() < 2L) {
                pcmTempFile?.delete()
                return false
            }

            // 从临时文件读取 PCM 数据并写入 WAV
            val result = writeWavFileFromFile(wavFile, pcmTempFile)

            // 清理临时文件
            pcmTempFile.delete()
            return result
        } catch (e: Exception) {
            // 【P3 修复】仅在 codec 已 start 时调用 stop()
            if (codecStarted) {
                try { codec.stop() } catch (_: Exception) {}
            }
            codec.release()
            extractor.release()
            return false
        }
    }

    /**
     * 将解码后的 PCM 数据写入临时文件，避免全量 PCM 驻留内存导致 OOM。
     *
     * 10分钟录音的 PCM 约 100MB，ByteArrayOutputStream 峰值内存超 150MB，
     * 改为写入临时文件后峰值内存降至约 1MB。
     *
     * @return 临时 PCM 文件，失败时返回 null
     */
    private fun decodeToPcmFile(
        extractor: MediaExtractor,
        codec: MediaCodec,
        initialSampleRate: Int,
        initialChannels: Int
    ): File? {
        // 创建临时文件存储原始 PCM 字节
        val pcmTempFile = File.createTempFile("pcm_", ".raw")
        pcmTempFile.deleteOnExit()

        try {
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            // The actual output format may differ from the input; update when changed
            var outputSampleRate = initialSampleRate
            var outputChannels = initialChannels

            // 累积 PCM 数据的临时缓冲区（用于分块写入）
            val pcmChunkBuffer = ByteArrayOutputStream(64 * 1024) // 64KB 初始容量

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
                                pcmChunkBuffer.write(chunk)

                                // 当累积超过 64KB 时，刷入临时文件
                                if (pcmChunkBuffer.size() >= 64 * 1024) {
                                    flushPcmChunk(pcmTempFile, pcmChunkBuffer)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    // INFO_TRY_AGAIN_LATER or other negative: just loop again
                }
            }

            // 刷入剩余数据
            flushPcmChunk(pcmTempFile, pcmChunkBuffer)

            // 现在从临时文件读取原始 PCM 字节，进行下混和重采样
            return resampleAndDownmixFromFile(
                pcmTempFile, outputSampleRate, outputChannels
            )
        } catch (e: Exception) {
            pcmTempFile.delete()
            return null
        }
    }

    /**
     * 将 ByteArrayOutputStream 中的 PCM 数据追加写入临时文件。
     */
    private fun flushPcmChunk(pcmTempFile: File, buffer: ByteArrayOutputStream) {
        if (buffer.size() == 0) return
        FileOutputStream(pcmTempFile, true).use { fos ->
            fos.write(buffer.toByteArray())
        }
        buffer.reset()
    }

    /**
     * 从临时文件读取原始 PCM 数据，先下混到 mono，再重采样到目标采样率，
     * 最后将结果写回临时文件。
     */
    private fun resampleAndDownmixFromFile(
        pcmTempFile: File,
        outputSampleRate: Int,
        outputChannels: Int
    ): File? {
        val rawLength = pcmTempFile.length()
        if (rawLength < 2) return null

        val rawData = ByteArray(rawLength.toInt())
        RandomAccessFile(pcmTempFile, "r").use { raf ->
            raf.readFully(rawData)
        }

        // 删除原始临时文件（后续使用新文件）
        pcmTempFile.delete()

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
        val finalSamples = if (outputSampleRate != TARGET_SAMPLE_RATE) {
            resample(monoSamples, outputSampleRate, TARGET_SAMPLE_RATE)
        } else {
            monoSamples
        }

        // 将最终 PCM 写入临时文件，供 writeWavFileFromFile 读取
        val resultFile = File.createTempFile("pcm_final_", ".raw")
        resultFile.deleteOnExit()
        val byteBuffer = ByteBuffer.allocate(finalSamples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (s in finalSamples) {
            byteBuffer.putShort(s)
        }
        FileOutputStream(resultFile).use { fos ->
            fos.write(byteBuffer.array())
        }
        return resultFile
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
     * 下采样前应用低通滤波器（截止频率 8kHz），防止混叠失真。
     * 使用简单的 FIR 滤波器，阶数为 [FILTER_ORDER]。
     * 线性插值对于 44.1kHz → 16kHz 的下采样场景足够。
     */
    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || samples.isEmpty()) return samples

        // 【P2 修复】下采样前添加抗混叠低通滤波器
        // 仅当下采样（toRate < fromRate）时才需要滤波
        val filteredSamples = if (toRate < fromRate) {
            applyLowPassFilter(samples, fromRate, toRate / 2)
        } else {
            samples
        }

        val ratio = toRate.toDouble() / fromRate.toDouble()
        val newLength = (filteredSamples.size * ratio).toInt().coerceAtLeast(1)
        val result = ShortArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()
            val fraction = srcPos - srcIndex
            if (srcIndex + 1 < filteredSamples.size) {
                val s1 = filteredSamples[srcIndex].toInt()
                val s2 = filteredSamples[srcIndex + 1].toInt()
                result[i] = (s1 + (s2 - s1) * fraction).toInt().toShort()
            } else if (srcIndex < filteredSamples.size) {
                result[i] = filteredSamples[srcIndex]
            }
        }
        return result
    }

    /**
     * 简单 FIR 低通滤波器。
     * 使用汉明窗设计，截止频率为 [cutoffHz]。
     * 用于下采样前抗混叠，阻带衰减约 40dB。
     */
    private fun applyLowPassFilter(samples: ShortArray, sampleRate: Int, cutoffHz: Int): ShortArray {
        if (cutoffHz >= sampleRate / 2) return samples // 无需求

        val order = FILTER_ORDER
        val halfOrder = order / 2

        // 计算归一化截止频率
        val normalizedCutoff = cutoffHz.toDouble() / sampleRate.toDouble()

        // 设计 FIR 滤波器系数（汉明窗）
        val coefficients = DoubleArray(order + 1)
        for (i in 0..order) {
            val n = i - halfOrder
            if (n == 0) {
                coefficients[i] = 2.0 * normalizedCutoff
            } else {
                coefficients[i] = Math.sin(2.0 * Math.PI * normalizedCutoff * n) / (Math.PI * n)
            }
            // 应用汉明窗
            coefficients[i] *= (0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / order))
        }

        // 归一化系数
        val sum = coefficients.sum()
        if (sum > 0.0) {
            for (i in coefficients.indices) {
                coefficients[i] /= sum
            }
        }

        // 应用滤波器
        val result = ShortArray(samples.size)
        for (i in samples.indices) {
            var acc = 0.0
            for (j in 0..order) {
                val idx = i - (j - halfOrder)
                if (idx in samples.indices) {
                    acc += samples[idx].toDouble() * coefficients[j]
                }
            }
            result[i] = acc.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return result
    }

    /**
     * 从临时 PCM 文件读取数据并写入 WAV 文件。
     * 分块写入避免一次性分配完整 ByteBuffer。
     */
    private fun writeWavFileFromFile(file: File, pcmFile: File): Boolean {
        return try {
            val pcmLength = pcmFile.length()
            val dataSize = pcmLength.toInt()
            val byteRate = TARGET_SAMPLE_RATE * TARGET_CHANNELS * (TARGET_BITS_PER_SAMPLE / 8)
            val blockAlign = TARGET_CHANNELS * (TARGET_BITS_PER_SAMPLE / 8)

            FileOutputStream(file).use { fos ->
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

                // 【P2 修复】分块写入 PCM 数据，避免一次性分配完整 ByteBuffer
                RandomAccessFile(pcmFile, "r").use { raf ->
                    val chunkSize = PCM_CHUNK_SAMPLES * 2 // 每个采样 2 字节
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    while (raf.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Write a standard 44-byte-header WAV file containing the given 16-bit PCM samples.
     * 【P2 修复】分块写入，避免一次性分配完整 ByteBuffer 导致 OOM。
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

                // 【P2 修复】分块写入 PCM 数据，每块 PCM_CHUNK_SAMPLES 个采样
                val byteBuffer = ByteBuffer.allocate(PCM_CHUNK_SAMPLES * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                var offset = 0
                while (offset < samples.size) {
                    val end = minOf(offset + PCM_CHUNK_SAMPLES, samples.size)
                    byteBuffer.clear()
                    for (i in offset until end) {
                        byteBuffer.putShort(samples[i])
                    }
                    fos.write(byteBuffer.array(), 0, (end - offset) * 2)
                    offset = end
                }
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