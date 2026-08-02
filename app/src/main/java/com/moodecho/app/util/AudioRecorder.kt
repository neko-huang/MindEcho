package com.moodecho.app.util

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
import java.io.IOException

/**
 * Audio recording utility wrapping Android's MediaRecorder.
 *
 * Features:
 * - Records audio in AAC format (compatible with most devices)
 * - Exposes real-time amplitude via StateFlow for waveform visualization
 * - Supports pause/resume on API 24+
 * - Outputs to the app's external files directory
 */
class AudioRecorder {

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
        } catch (e: IOException) {
            release()
        }
    }

    /**
     * Stop recording and release resources.
     */
    fun stop() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // MediaRecorder may throw if stop() called too soon after start()
        } finally {
            recorder = null
            _isRecording = false
            amplitudeJob?.cancel()
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
        try {
            recorder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        recorder = null
        _isRecording = false
        amplitudeJob?.cancel()
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
     */
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(android.app.Application())
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
