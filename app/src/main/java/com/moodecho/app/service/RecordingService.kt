package com.moodecho.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.moodecho.app.MainActivity
import com.moodecho.app.R
import com.moodecho.app.util.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service for audio recording.
 *
 * Ensures recording continues even when the app is in the background.
 * Displays a persistent notification with recording duration and status.
 * Exposes recording state via StateFlow for UI observation.
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val CHANNEL_NAME = "Recording"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.moodecho.app.ACTION_START_RECORDING"
        const val ACTION_STOP = "com.moodecho.app.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE = "com.moodecho.app.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME = "com.moodecho.app.ACTION_RESUME_RECORDING"
        const val EXTRA_OUTPUT_PATH = "output_path"

        // Shared state accessible from the UI layer
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _recordingDuration = MutableStateFlow(0L)
        val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

        private val _currentAmplitude = MutableStateFlow(0f)
        val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioRecorder = AudioRecorder()
    private var startTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                    ?: return START_NOT_STICKY
                startRecording(outputPath)
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.release()
        _isRecording.value = false
        serviceScope.cancel()
    }

    /**
     * Start recording and promote the service to foreground.
     */
    private fun startRecording(outputPath: String) {
        val notification = createNotification("Recording... 0:00")

        // Start as foreground service with microphone type (API 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        audioRecorder.start(outputPath)
        _isRecording.value = true
        startTime = System.currentTimeMillis()

        // Update duration and amplitude periodically
        serviceScope.launch {
            audioRecorder.amplitudeFlow.collect { amplitude ->
                _currentAmplitude.value = amplitude
            }
        }

        serviceScope.launch {
            while (_isRecording.value) {
                val elapsed = System.currentTimeMillis() - startTime
                _recordingDuration.value = elapsed
                val minutes = (elapsed / 1000) / 60
                val seconds = (elapsed / 1000) % 60
                updateNotification("Recording... %d:%02d".format(minutes, seconds))
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /**
     * Stop recording and stop the service.
     */
    private fun stopRecording() {
        audioRecorder.stop()
        _isRecording.value = false
        _recordingDuration.value = 0L
        _currentAmplitude.value = 0f
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Pause the current recording.
     */
    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecorder.pause()
            updateNotification("Recording paused")
        }
    }

    /**
     * Resume a paused recording.
     */
    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecorder.resume()
            updateNotification("Recording...")
        }
    }

    /**
     * Create the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when recording is in progress"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the foreground notification.
     */
    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MindEcho")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Update the foreground notification text.
     */
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
