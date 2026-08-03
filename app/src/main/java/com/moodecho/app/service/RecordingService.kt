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
import com.moodecho.app.MindEchoApp
import com.moodecho.app.R
import com.moodecho.app.analysis.AudioFeatureExtractor
import com.moodecho.app.analysis.EmotionAnalyzer
import com.moodecho.app.data.api.AssemblyAiApi
import com.moodecho.app.data.api.TranscriptRequest
import com.moodecho.app.data.api.TranscriptResponse
import com.moodecho.app.data.api.UploadResponse
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.TranscriptEntry
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.util.AacWavConverter
import com.moodecho.app.util.AudioRecorder
import com.moodecho.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground service for audio recording.
 *
 * Ensures recording continues even when the app is in the background.
 * Displays a persistent notification with recording duration and status.
 * Exposes recording state via StateFlow for UI observation.
 * After recording stops, optionally transcribes audio via AssemblyAI.
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

        // Transcription state for UI observation
        private val _isTranscribing = MutableStateFlow(false)
        val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

        private val _transcriptionStatus = MutableStateFlow("")
        val transcriptionStatus: StateFlow<String> = _transcriptionStatus.asStateFlow()

        // Emotion analysis state for UI observation
        private val _isAnalyzing = MutableStateFlow(false)
        val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

        private val _analysisStatus = MutableStateFlow("")
        val analysisStatus: StateFlow<String> = _analysisStatus.asStateFlow()
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
     * If AssemblyAI key is configured, starts transcription in the background.
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

    object TranscriptionHelper {

        /**
         * Transcribe an audio file using AssemblyAI with speaker diarization.
         * This is a suspend function intended to be called from a coroutine scope.
         *
         * @param context Android context
         * @param audioFilePath Path to the recorded audio file
         * @param sessionId Database session ID for saving transcript entries
         * @return True if transcription succeeded, false otherwise
         */
        suspend fun transcribeAudio(
            context: android.content.Context,
            audioFilePath: String,
            sessionId: Long
        ): Boolean {
            val app = context.applicationContext as MindEchoApp
            val preferenceManager = com.moodecho.app.util.PreferenceManager(context)

            // Check if AssemblyAI key is configured
            val apiKey = preferenceManager.assemblyAiApiKey.first() ?: ""
            if (apiKey.isBlank()) return false

            _isTranscribing.value = true
            _transcriptionStatus.value = "Uploading audio..."

            try {
                // Build Retrofit client for AssemblyAI
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(Constants.ASSEMBLYAI_BASE_URL)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val assemblyApi = retrofit.create(AssemblyAiApi::class.java)
                val authHeader = apiKey

                // Step 1: Upload the audio file
                val audioFile = File(audioFilePath)
                if (!audioFile.exists()) {
                    _isTranscribing.value = false
                    _transcriptionStatus.value = ""
                    return false
                }

                val requestBody = audioFile.asRequestBody("audio/aac".toMediaTypeOrNull())
                val uploadResponse = assemblyApi.uploadAudio(
                    url = Constants.ASSEMBLYAI_BASE_URL + "upload",
                    authorization = authHeader,
                    file = requestBody
                )

                if (!uploadResponse.isSuccessful || uploadResponse.body() == null) {
                    _isTranscribing.value = false
                    _transcriptionStatus.value = ""
                    return false
                }

                val uploadUrl = uploadResponse.body()!!.upload_url
                _transcriptionStatus.value = "Creating transcript..."

                // Step 2: Create transcript request with speaker diarization
                val transcriptRequest = TranscriptRequest(
                    audio_url = uploadUrl,
                    speaker_labels = true,
                    language_code = "zh"
                )

                val createResponse = assemblyApi.createTranscript(
                    authorization = authHeader,
                    request = transcriptRequest
                )

                if (!createResponse.isSuccessful || createResponse.body()?.id == null) {
                    _isTranscribing.value = false
                    _transcriptionStatus.value = ""
                    return false
                }

                val transcriptId = createResponse.body()!!.id!!
                _transcriptionStatus.value = "Transcribing..."

                // Step 3: Poll for transcript completion with timeout
                val result = withTimeoutOrNull(Constants.ASSEMBLYAI_TIMEOUT_MS) {
                    var response: TranscriptResponse? = null
                    while (true) {
                        val pollResponse = assemblyApi.getTranscript(
                            authorization = authHeader,
                            transcriptId = transcriptId
                        )

                        if (pollResponse.isSuccessful) {
                            response = pollResponse.body()
                            when (response?.status) {
                                "completed" -> break
                                "error" -> break
                                else -> {
                                    _transcriptionStatus.value =
                                        "Transcribing... (${response?.status ?: "processing"})"
                                }
                            }
                        }
                        kotlinx.coroutines.delay(Constants.ASSEMBLYAI_POLL_INTERVAL_MS)
                    }
                    response
                }

                _isTranscribing.value = false

                if (result == null) {
                    _transcriptionStatus.value = ""
                    return false // Timeout
                }

                if (result.status == "error") {
                    _transcriptionStatus.value = ""
                    return false
                }

                // Step 4: Save transcript entries to the database
                val db = app.database
                val repository = RecordingRepository(
                    sessionDao = db.recordingSessionDao(),
                    transcriptDao = db.transcriptEntryDao(),
                    emotionDao = db.emotionDataPointDao(),
                    reportDao = db.dailyReportDao()
                )

                if (result.utterances != null && result.utterances.isNotEmpty()) {
                    // Save utterances with speaker labels as transcript entries
                    val entries = result.utterances.map { utterance ->
                        TranscriptEntry(
                            sessionId = sessionId,
                            startTime = utterance.start,
                            endTime = utterance.end,
                            text = "[说话人${utterance.speaker}] ${utterance.text}"
                        )
                    }
                    repository.saveTranscripts(entries)
                } else if (!result.text.isNullOrBlank()) {
                    // Fallback: save full text as a single entry (no speaker labels)
                    repository.saveTranscript(
                        TranscriptEntry(
                            sessionId = sessionId,
                            startTime = 0,
                            endTime = 0,
                            text = result.text
                        )
                    )
                }

                _transcriptionStatus.value = "Transcription complete"
                kotlinx.coroutines.delay(1500)
                _transcriptionStatus.value = ""
                return true

            } catch (e: Exception) {
                _isTranscribing.value = false
                _transcriptionStatus.value = ""
                return false
            }
        }

        /**
         * Check if AssemblyAI key is configured.
         */
        suspend fun isAssemblyAiConfigured(context: android.content.Context): Boolean {
            val preferenceManager = com.moodecho.app.util.PreferenceManager(context)
            val key = preferenceManager.assemblyAiApiKey.first() ?: ""
            return key.isNotBlank()
        }

        /**
         * Process a recorded audio file for emotion analysis.
         *
         * Pipeline:
         * 1. Convert the AAC recording to WAV (16 kHz, mono, 16-bit PCM)
         * 2. Extract audio features (RMS energy, zero-crossing rate, etc.)
         * 3. Analyze features to detect emotions per 5-second window
         * 4. Save the resulting [EmotionDataPoint]s to the database
         *
         * This method runs entirely on-device and does not require network access.
         * It is safe to call from a coroutine on any dispatcher; CPU-intensive
         * work is offloaded to [Dispatchers.Default].
         *
         * @param context Android context for accessing the database
         * @param audioFilePath Path to the recorded AAC audio file
         * @param sessionId Database ID of the [RecordingSession] this audio belongs to
         * @return `true` if analysis completed successfully, `false` on failure
         */
        suspend fun processRecording(
            context: android.content.Context,
            audioFilePath: String,
            sessionId: Long
        ): Boolean {
            _isAnalyzing.value = true
            _analysisStatus.value = "Converting audio..."

            try {
                val aacFile = File(audioFilePath)
                if (!aacFile.exists()) {
                    _isAnalyzing.value = false
                    _analysisStatus.value = ""
                    return false
                }

                // Offload CPU-intensive conversion + analysis to a background thread
                val success = withContext(Dispatchers.Default) {
                    // Step 1: Convert AAC → WAV (16 kHz, mono, 16-bit PCM)
                    val wavFile = File(
                        aacFile.parentFile,
                        aacFile.nameWithoutExtension + ".wav"
                    )
                    if (!AacWavConverter.convert(aacFile, wavFile)) {
                        return@withContext false
                    }

                    _analysisStatus.value = "Analyzing emotions..."

                    // Step 2: Extract audio features from the WAV file
                    val featureExtractor = AudioFeatureExtractor()
                    val featureVectors = featureExtractor.extractFeatures(wavFile)

                    // Step 3: Analyze features to detect emotions
                    val emotionAnalyzer = EmotionAnalyzer()
                    val emotionResults = emotionAnalyzer.analyze(featureVectors)

                    // Step 4: Save emotion data points to the database
                    if (emotionResults.isNotEmpty()) {
                        val app = context.applicationContext as MindEchoApp
                        val db = app.database
                        val repository = RecordingRepository(
                            sessionDao = db.recordingSessionDao(),
                            transcriptDao = db.transcriptEntryDao(),
                            emotionDao = db.emotionDataPointDao(),
                            reportDao = db.dailyReportDao()
                        )

                        val emotionDataPoints = emotionResults.map { result ->
                            EmotionDataPoint(
                                sessionId = sessionId,
                                timestamp = result.timestamp,
                                emotionType = result.emotionType,
                                confidence = result.confidence,
                                arousal = result.arousal,
                                valence = result.valence
                            )
                        }
                        repository.saveEmotionDataPoints(emotionDataPoints)
                    }

                    true
                }

                _analysisStatus.value = if (success) "Analysis complete" else ""
                if (success) {
                    kotlinx.coroutines.delay(800)
                }
                _isAnalyzing.value = false
                _analysisStatus.value = ""
                return success
            } catch (e: Exception) {
                _isAnalyzing.value = false
                _analysisStatus.value = ""
                return false
            }
        }
    }
}
