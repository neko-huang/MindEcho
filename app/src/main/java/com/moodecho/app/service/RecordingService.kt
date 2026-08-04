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
import com.moodecho.app.analysis.TextSentimentAnalyzer
import com.moodecho.app.data.api.AssemblyAiApi
import com.moodecho.app.data.api.TranscriptRequest
import com.moodecho.app.data.api.TranscriptResponse
import com.moodecho.app.data.api.UploadResponse
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.SessionStatus
import com.moodecho.app.data.db.entity.TranscriptEntry
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.data.repository.RepositoryResult
import com.moodecho.app.util.AacWavConverter
import com.moodecho.app.util.AudioRecorder
import com.moodecho.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

        // Processing result for UI observation (null = pending, -1 = failure, >=0 = sessionId)
        private val _processingResult = MutableStateFlow<Long?>(null)
        val processingResult: StateFlow<Long?> = _processingResult.asStateFlow()

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
                    var pollDelay = Constants.ASSEMBLYAI_POLL_INTERVAL_MS
                    var consecutiveFailures = 0
                    while (true) {
                        val pollResponse = assemblyApi.getTranscript(
                            authorization = authHeader,
                            transcriptId = transcriptId
                        )

                        if (pollResponse.isSuccessful) {
                            consecutiveFailures = 0
                            pollDelay = Constants.ASSEMBLYAI_POLL_INTERVAL_MS
                            response = pollResponse.body()
                            when (response?.status) {
                                "completed" -> break
                                "error" -> break
                                else -> {
                                    _transcriptionStatus.value =
                                        "Transcribing... (${response?.status ?: "processing"})"
                                }
                            }
                        } else {
                            consecutiveFailures++
                            pollDelay = (Constants.ASSEMBLYAI_POLL_INTERVAL_MS * (1L shl consecutiveFailures.coerceAtMost(5)))
                                .coerceAtMost(30_000L)
                            _transcriptionStatus.value =
                                "Transcribing... (retry ${consecutiveFailures})"
                        }
                        kotlinx.coroutines.delay(pollDelay)
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
                    reportDao = db.dailyReportDao(),
                    reportSessionDao = db.dailyReportSessionDao()
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
                    try {
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

                        // Step 4: Save audio emotion data points to the database
                        val app = context.applicationContext as MindEchoApp
                        val db = app.database
                        val repository = RecordingRepository(
                            sessionDao = db.recordingSessionDao(),
                            transcriptDao = db.transcriptEntryDao(),
                            emotionDao = db.emotionDataPointDao(),
                            reportDao = db.dailyReportDao(),
                            reportSessionDao = db.dailyReportSessionDao()
                        )

                        if (emotionResults.isNotEmpty()) {
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

                        // Step 5: Text sentiment analysis on transcripts (cross-modal fusion)
                        _analysisStatus.value = "Analyzing language patterns..."
                        val transcriptsResult = repository.getTranscriptsForSessionSync(sessionId)
                        val transcripts = when (transcriptsResult) {
                            is RepositoryResult.Success -> transcriptsResult.data
                            is RepositoryResult.Error -> emptyList()
                        }
                        if (transcripts.isNotEmpty()) {
                            val combinedText = transcripts.joinToString(" ") { it.text }
                            val textAnalyzer = TextSentimentAnalyzer()
                            val textResult = textAnalyzer.analyze(combinedText)

                            // Save text-based sentiment as an additional emotion data point
                            // (timestamp = -1 to distinguish from audio-based points)
                            val textEmotionPoint = EmotionDataPoint(
                                sessionId = sessionId,
                                timestamp = -1L,  // -1 = text-based sentiment
                                emotionType = textResult.primaryEmotion,
                                confidence = textResult.confidence,
                                arousal = textResult.arousal,
                                valence = textResult.valence
                            )
                            repository.saveEmotionDataPoint(textEmotionPoint)

                            // Step 6: Merge audio + text into a final session-level fused result
                            _analysisStatus.value = "Fusing audio & text analysis..."
                            val fusedResult = textAnalyzer.mergeWithAudio(textResult, emotionResults)
                            val fusedEmotionPoint = EmotionDataPoint(
                                sessionId = sessionId,
                                timestamp = -2L,  // -2 = fused audio+text sentiment
                                emotionType = fusedResult.emotionType,
                                confidence = fusedResult.confidence,
                                arousal = fusedResult.arousal,
                                valence = fusedResult.valence
                            )
                            repository.saveEmotionDataPoint(fusedEmotionPoint)
                        }

                        true
                    } finally {
                        // Clean up temporary WAV file
                        if (wavFile.exists()) {
                            wavFile.delete()
                        }
                    }
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioRecorder = AudioRecorder(this)
    private var outputPath: String = ""
    private var amplitudeJob: Job? = null
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

        this.outputPath = outputPath
        _processingResult.value = null
        audioRecorder.start(outputPath)
        _isRecording.value = true
        startTime = System.currentTimeMillis()

        // Cancel previous amplitude collection to avoid accumulation
        amplitudeJob?.cancel()
        amplitudeJob = serviceScope.launch {
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
     * Stop recording, save session to DB, run analysis, then stop the service.
     * The full pipeline (save + processRecording + transcribeAudio) runs in
     * serviceScope so stopSelf() is only called after everything completes,
     * preventing the analysis from being interrupted by service shutdown.
     */
    private fun stopRecording() {
        // Cancel any ongoing amplitude collection
        amplitudeJob?.cancel()
        amplitudeJob = null

        serviceScope.launch {
            try {
                audioRecorder.stop()
                _isRecording.value = false
                _recordingDuration.value = 0L
                _currentAmplitude.value = 0f
                stopForeground(STOP_FOREGROUND_REMOVE)

                // Save recording session to DB
                val sessionId = saveRecordingToDatabase()
                if (sessionId != null) {
                    // Run on-device emotion analysis
                    processRecording(this@RecordingService, outputPath, sessionId)
                    // Run AssemblyAI transcription if configured
                    transcribeAudio(this@RecordingService, outputPath, sessionId)
                    _processingResult.value = sessionId
                } else {
                    _processingResult.value = -1L
                }
            } catch (e: Exception) {
                _processingResult.value = -1L
            } finally {
                stopSelf()
            }
        }
    }

    /**
     * Save the completed recording to the Room database.
     * Creates a RecordingSession entry and returns its ID.
     */
    private suspend fun saveRecordingToDatabase(): Long? {
        return try {
            val app = applicationContext as MindEchoApp
            val db = app.database
            val repository = RecordingRepository(
                sessionDao = db.recordingSessionDao(),
                transcriptDao = db.transcriptEntryDao(),
                emotionDao = db.emotionDataPointDao(),
                reportDao = db.dailyReportDao(),
                reportSessionDao = db.dailyReportSessionDao()
            )
            val endTime = System.currentTimeMillis()
            val session = RecordingSession(
                title = "Recording ${java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(startTime)}",
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                audioFilePath = outputPath,
                status = SessionStatus.COMPLETED
            )
            val result = repository.createSession(session)
            when (result) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Error -> null
            }
        } catch (e: Exception) {
            null
        }
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
