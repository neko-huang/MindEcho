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

    private var recorder: MediaRecorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var _isRecording = false

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private var amplitudeJob: kotlinx.coroutines.Job? = null

    /**
     * Start recording audio to the specified output path.
     * @param outputPath Full file path for the output audio file
     */
    fun start(outputPath: String) {
        try {
            recorder = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputPath)
                prepare()
                start()
            }
            _isRecording = true
            startAmplitudePolling()
        } catch (e: Exception) {
            // Catch all exceptions, not just IOException.
            // MediaRecorder methods like setAudioSource(), prepare(), and start()
            // can throw RuntimeException (IllegalStateException) if the device's
            // microphone is unavailable, the recorder is in an invalid state, etc.
            // Silently release and let the caller check _isRecording.
            release()
        }
    }

    /**
     * Stop recording and release resources.
     *
     * IMPORTANT: Cancel the amplitude polling coroutine BEFORE stopping the
     * MediaRecorder to prevent a race condition where the polling coroutine
     * (running on Dispatchers.Default) calls recorder.maxAmplitude while the
     * main thread is releasing the MediaRecorder. This race can cause
     * IllegalStateException or other crashes on stop().
     */
    fun stop() {
        // Cancel amplitude polling first to prevent any concurrent access to
        // the MediaRecorder from background threads during stop/release.
        amplitudeJob?.cancel()
        amplitudeJob = null

        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // MediaRecorder may throw if stop() called too soon after start()
            // or if the recorder is in an invalid state. Safe to ignore.
        } finally {
            recorder = null
            _isRecording = false
            _amplitudeFlow.value = 0f
        }
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
     */
    fun release() {
        // Cancel amplitude polling first to prevent concurrent access to
        // the MediaRecorder from background threads during release.
        amplitudeJob?.cancel()
        amplitudeJob = null

        try {
            recorder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        recorder = null
        _isRecording = false
        _amplitudeFlow.value = 0f
    }

    /**
     * Poll the current amplitude at regular intervals for waveform visualization.
     * Amplitude is normalized to 0.0 ~ 1.0 range.
     */
    private fun startAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isActive && _isRecording) {
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
