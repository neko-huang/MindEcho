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
import kotlinx.coroutines.runBlocking
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
     * Start recording audio to the specified output path.
     * @param outputPath Full file path for the output audio file
     */
    fun start(outputPath: String) {
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
            // Configuration or start failed. Release the new recorder
            // (which is NOT yet assigned to `recorder`, so release() won't help).
            try {
                newRecorder.release()
            } catch (_: Exception) { }
            // Also release any old recorder that might still be set
            release()
        }
    }

    /**
     * Stop recording and release resources.
     *
     * CRITICAL #1: `cancel()` is cooperative — the coroutine on Dispatchers.Default
     * may still be executing `recorder.maxAmplitude` when cancel() returns.
     * We must WAIT for it to actually finish via `join()` before releasing the
     * MediaRecorder. Otherwise, both threads access the native MediaRecorder
     * simultaneously, causing a native crash (IllegalStateException / SIGSEGV).
     *
     * CRITICAL #2: `MediaRecorder.stop()` can cause a native SIGSEGV crash (not
     * catchable by Java try-catch) if the recording duration is too short (< 1 sec).
     * To avoid this, we skip stop() entirely and just release() for short recordings.
     */
    fun stop() {
        // 1. Cancel the amplitude polling coroutine
        amplitudeJob?.cancel()
        // 2. WAIT for it to actually finish before touching the recorder
        //    Use a timeout to avoid ANR if the native call hangs
        try {
            runBlocking {
                withTimeout(500) {
                    amplitudeJob?.join()
                }
            }
        } catch (e: Exception) {
            // Timeout or cancellation — proceed anyway
        }
        amplitudeJob = null

        val r = recorder
        recorder = null
        if (r != null) {
            val durationMs = System.currentTimeMillis() - recordingStartTime
            if (durationMs < 1000) {
                // Recording too short: MediaRecorder.stop() can cause a native
                // SIGSEGV that crashes the app. Skip stop() entirely, just release().
                try {
                    r.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            } else {
                // Normal stop: separate try-catch blocks so release() always runs
                // even if stop() throws.
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
        _isRecording = false
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
     */
    fun release() {
        // Same as stop(): cancel + wait for completion before releasing
        amplitudeJob?.cancel()
        try {
            runBlocking {
                withTimeout(500) {
                    amplitudeJob?.join()
                }
            }
        } catch (e: Exception) {
            // Timeout or cancellation — proceed anyway
        }
        amplitudeJob = null

        val r = recorder
        recorder = null
        if (r != null) {
            try {
                r.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
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
