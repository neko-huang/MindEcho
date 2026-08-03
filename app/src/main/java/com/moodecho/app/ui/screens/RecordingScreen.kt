package com.moodecho.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.moodecho.app.MindEchoApp
import com.moodecho.app.analysis.AudioFeatureExtractor
import com.moodecho.app.analysis.EmotionAnalyzer
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.SessionStatus
import com.moodecho.app.data.repository.RecordingRepository
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.service.RecordingService
import com.moodecho.app.ui.components.EmotionChip
import com.moodecho.app.ui.components.WaveformAnimation
import com.moodecho.app.util.AudioRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Recording screen: displays live waveform, current emotion, and recording controls.
 * Observes the RecordingService state via shared StateFlows.
 * Starts the RecordingService on entry and sends control Intents for pause/resume/stop.
 *
 * @param onFinish Callback with session ID when recording is stopped and saved
 * @param onCancel Callback when recording is cancelled
 */
@Composable
fun RecordingScreen(
    onFinish: (Long) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe recording state from the service
    val isRecording by RecordingService.isRecording.collectAsState()
    val duration by RecordingService.recordingDuration.collectAsState()
    val amplitude by RecordingService.currentAmplitude.collectAsState()

    // Local UI state
    var isPaused by remember { mutableStateOf(false) }
    var currentEmotion by remember { mutableStateOf(EmotionType.NEUTRAL) }
    var emotionConfidence by remember { mutableStateOf(0.5f) }
    var isStopping by remember { mutableStateOf(false) }
    var recordingStarted by remember { mutableStateOf(false) }

    // Track the output path so we can save to DB on stop
    var outputPath by remember { mutableStateOf("") }
    var sessionStartTime by remember { mutableStateOf(0L) }

    // Start RecordingService when entering the screen
    LaunchedEffect(Unit) {
        val path = AudioRecorder.generateOutputPath(context.filesDir)
        outputPath = path
        sessionStartTime = System.currentTimeMillis()

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_OUTPUT_PATH, path)
        }
        ContextCompat.startForegroundService(context, intent)
        recordingStarted = true
    }

    // Format duration as MM:SS
    val minutes = (duration / 1000) / 60
    val seconds = (duration / 1000) % 60
    val durationText = "%02d:%02d".format(minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Recording status text
        Text(
            text = when {
                isStopping -> "Saving..."
                isPaused -> "Paused"
                isRecording -> "Recording..."
                else -> "Ready"
            },
            style = MaterialTheme.typography.labelLarge,
            color = when {
                isStopping -> MaterialTheme.colorScheme.tertiary
                isRecording && !isPaused -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Duration display
        Text(
            text = durationText,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Live waveform animation
        WaveformAnimation(
            amplitude = if (isRecording && !isPaused && !isStopping) amplitude else 0f,
            barCount = 40,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Current detected emotion
        Text(
            text = "Detected Emotion",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        EmotionChip(
            emotionType = currentEmotion,
            confidence = emotionConfidence
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Recording controls
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pause / Resume button
            FilledIconButton(
                onClick = {
                    if (isPaused) {
                        // Resume recording: send ACTION_RESUME to service
                        val intent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_RESUME
                        }
                        ContextCompat.startForegroundService(context, intent)
                        isPaused = false
                    } else {
                        // Pause recording: send ACTION_PAUSE to service
                        val intent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_PAUSE
                        }
                        ContextCompat.startForegroundService(context, intent)
                        isPaused = true
                    }
                },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                enabled = isRecording && !isStopping,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "Resume" else "Pause",
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(32.dp))

            // Stop button
            FilledIconButton(
                onClick = {
                    if (isStopping) return@FilledIconButton
                    isStopping = true

                    scope.launch {
                        // Send STOP intent to RecordingService
                        val stopIntent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_STOP
                        }
                        ContextCompat.startForegroundService(context, stopIntent)

                        // Wait for the service to stop recording
                        delay(500)

                        // Save the recording session to the database
                        val sessionId = saveRecordingToDatabase(
                            context = context,
                            outputPath = outputPath,
                            startTime = sessionStartTime,
                            duration = duration
                        )

                        if (sessionId != null) {
                            onFinish(sessionId)
                        } else {
                            onCancel()
                        }
                    }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                enabled = isRecording && !isStopping,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop Recording",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hint text
        Text(
            text = if (isStopping) "Saving recording..." else "Tap stop to finish recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Save the completed recording to the Room database.
 * Creates a RecordingSession, then analyzes the audio file for emotion data points.
 *
 * @param context Android context for accessing app resources
 * @param outputPath Path to the recorded audio file
 * @param startTime Epoch millis when recording started
 * @param duration Duration of the recording in milliseconds
 * @return The ID of the newly created session, or null on failure
 */
private suspend fun saveRecordingToDatabase(
    context: Context,
    outputPath: String,
    startTime: Long,
    duration: Long
): Long? {
    return try {
        val app = context.applicationContext as MindEchoApp
        val db = app.database
        val repository = RecordingRepository(
            sessionDao = db.recordingSessionDao(),
            transcriptDao = db.transcriptEntryDao(),
            emotionDao = db.emotionDataPointDao(),
            reportDao = db.dailyReportDao()
        )

        val endTime = System.currentTimeMillis()

        // Create and insert the recording session
        val session = RecordingSession(
            title = "Recording ${java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(startTime)}",
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            audioFilePath = outputPath,
            status = SessionStatus.COMPLETED
        )
        val sessionId = repository.createSession(session)

        // Analyze audio for emotion data points
        val audioFile = File(outputPath)
        if (audioFile.exists()) {
            try {
                val featureExtractor = AudioFeatureExtractor()
                val emotionAnalyzer = EmotionAnalyzer()

                val featureVectors = featureExtractor.extractFeatures(audioFile)
                val emotionResults = emotionAnalyzer.analyze(featureVectors)

                // Convert EmotionResults to EmotionDataPoints and save
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

                if (emotionDataPoints.isNotEmpty()) {
                    repository.saveEmotionDataPoints(emotionDataPoints)
                }
            } catch (e: Exception) {
                // Audio analysis failed, but session is still saved
                // The session can be re-analyzed later
            }
        }

        sessionId
    } catch (e: Exception) {
        null
    }
}
