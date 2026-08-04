package com.moodecho.app.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Audio recording utility wrapping Android's MediaRecorder.
 *
 * Features:
 * - Records audio in AAC format (compatible with most devices)
 * - Exposes real-time amplitude via StateFlow for waveform visualization
 * - Supports pause/resume on API 24+
 * - Outputs to the app's external files directory
 *
 * @param context Application context required for MediaRecorder creation on API 31+.
 *                Pass null only if targeting API < 31 exclusively.
 */
class AudioRecorder(private val context: Context? = null) {

    @Volatile
    private var recorder: MediaRecorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var _isRecording = false
    @Volatile
    private var recordingStartTime = 0L

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private var amplitudeJob: kotlinx.coroutines.Job? = null

    /**
     * 双重保障标志：当 recorder 已被释放时设为 true。
     * 在 amplitude 协程循环中每次迭代前检查此标志，避免释放后仍访问 native 资源。
     */
    @Volatile
    private var _released = false

    /**
     * Start recording audio to the specified output path.
     * @param outputPath Full file path for the output audio file
     */
    fun start(outputPath: String) {
        // 【P1 修复】防止连续两次 start() 未 stop() 时泄漏前一个 MediaRecorder
        if (_isRecording) {
            return
        }

        // Create the recorder first, then configure it. If configuration fails,
        // we must release the newly created recorder to avoid leaking native resources.
        val newRecorder = try {
            createRecorder()
        } catch (e: Exception) {
            // Context missing on API 31+; nothing to release
            return
        }
        try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputPath)
                prepare()
                start()
            }
            recorder = newRecorder
            _isRecording = true
            recordingStartTime = System.currentTimeMillis()
            startAmplitudePolling()
        } catch (e: Exception) {
            // 【P1 修复】catch 块中只释放 newRecorder，不调用 release()
            // 因为 release() 会释放正在录制的旧 recorder（如果存在）
            try {
                newRecorder.release()
            } catch (_: Exception) { }
        }
    }

    /**
     * Stop recording and release resources.
     *
     * CRITICAL: `cancel()` is cooperative — the coroutine on Dispatchers.Default
     * may still be executing `recorder.maxAmplitude` when cancel() returns.
     * We must WAIT for it to actually finish via `join()` before releasing the
     * MediaRecorder. Otherwise, both threads access the native MediaRecorder
     * simultaneously, causing a native crash (IllegalStateException / SIGSEGV).
     *
     * If the amplitude coroutine does not finish within the timeout, we
     * SKIP `stop()` entirely and only `release()`. This prevents the
     * native crash from concurrent access even if the coroutine is stuck.
     *
     * 【P0 修复】改为 suspend 函数，用 withTimeout + join() 替代 runBlocking，
     * 避免在主线程调用时阻塞导致 ANR。
     */
    suspend fun stop() {
        // 1. Signal the amplitude coroutine to exit
        _isRecording = false

        // 2. Cancel the amplitude polling coroutine
        amplitudeJob?.cancel()

        // 3. WAIT for it to actually finish before touching the recorder
        //    Use a timeout to avoid hanging if the native call hangs
        var amplitudeTimedOut = false
        try {
            withTimeout(1000) {
                amplitudeJob?.join()
            }
        } catch (e: Exception) {
            // Timeout — amplitude coroutine may still be accessing recorder
            amplitudeTimedOut = true
        }
        amplitudeJob = null

        // 设置释放标志，防止 amplitude 协程后续访问
        _released = true

        val r = recorder
        recorder = null
        if (r != null) {
            if (amplitudeTimedOut) {
                // Amplitude coroutine may still be accessing the recorder.
                // Skip stop() to avoid native crash from concurrent access.
                try {
                    r.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            } else {
                // Normal stop: separate try-catch blocks so release() always runs
                // even if stop() throws a Java exception.
                try {
                    r.stop()
                } catch (e: Exception) {
                    // May throw if recorder is already stopped or in invalid state
                }
                try {
                    r.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            }
        }
        _amplitudeFlow.value = 0f
    }

    /**
     * Pause recording (API 24+).
     */
    fun pause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.pause()
            } catch (e: Exception) {
                // Ignore pause errors
            }
        }
    }

    /**
     * Resume recording (API 24+).
     */
    fun resume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.resume()
            } catch (e: Exception) {
                // Ignore resume errors
            }
        }
    }

    /**
     * Release all resources without stopping cleanly.
     * Used in error cases.
     *
     * 此处不等待 amplitude 协程完成（避免阻塞主线程），
     * 仅通过 _released 标志防止协程后续访问已释放的 recorder。
     */
    fun release() {
        _isRecording = false
        amplitudeJob?.cancel()
        amplitudeJob = null

        // 设置释放标志，amplitude 协程会在每次循环前检查此标志
        _released = true

        val r = recorder
        recorder = null
        if (r != null) {
            try {
                r.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        _amplitudeFlow.value = 0f
    }

    /**
     * Poll the current amplitude at regular intervals for waveform visualization.
     * Amplitude is normalized to 0.0 ~ 1.0 range.
     */
    private fun startAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isActive && _isRecording && !_released) {
                try {
                    val maxAmplitude = recorder?.maxAmplitude?.toFloat() ?: 0f
                    // Normalize: MediaRecorder maxAmplitude range is 0 ~ 32767
                    _amplitudeFlow.value = (maxAmplitude / 32767f).coerceIn(0f, 1f)
                } catch (e: Exception) {
                    _amplitudeFlow.value = 0f
                }
                kotlinx.coroutines.delay(50) // 20 FPS for smooth waveform
            }
        }
    }

    /**
     * Create a MediaRecorder instance using the appropriate API for the device.
     *
     * On API 31+ (Android 12+), MediaRecorder(Context) requires a valid
     * application context. Passing a bare `android.app.Application()` instance
     * without proper initialization causes a crash. Always use the provided
     * [context] parameter.
     */
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val appContext = context?.applicationContext
                ?: throw IllegalStateException(
                    "AudioRecorder requires a valid Context on API 31+. " +
                    "Pass the application context to the constructor."
                )
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    companion object {
        /**
         * Generate a unique output file path for a new recording.
         * @param filesDir The app's external files directory
         * @return Absolute path for the new recording file
         */
        fun generateOutputPath(filesDir: File): String {
            val recordingsDir = File(filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            return File(recordingsDir, "recording_$timestamp.aac").absolutePath
        }
    }
}