package com.moodecho.app.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.CircularProgressIndicator
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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recording screen: displays live waveform, current emotion, and recording controls.
 * Observes the RecordingService state via shared StateFlows.
 * Starts the RecordingService on entry and sends control Intents for pause/resume/stop.
 * After stopping, shows transcription progress if AssemblyAI is configured.
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

    // Observe transcription state
    val isTranscribing by RecordingService.isTranscribing.collectAsState()
    val transcriptionStatus by RecordingService.transcriptionStatus.collectAsState()

    // Observe emotion analysis state
    val isAnalyzing by RecordingService.isAnalyzing.collectAsState()
    val analysisStatus by RecordingService.analysisStatus.collectAsState()

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
                isTranscribing -> "Analyzing transcription..."
                isAnalyzing -> analysisStatus.ifBlank { "Analyzing..." }
                isStopping -> "Saving..."
                isPaused -> "Paused"
                isRecording -> "Recording..."
                else -> "Ready"
            },
            style = MaterialTheme.typography.labelLarge,
            color = when {
                isTranscribing -> MaterialTheme.colorScheme.primary
                isAnalyzing -> MaterialTheme.colorScheme.primary
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

        // Live waveform animation or progress indicator
        if (isTranscribing || isAnalyzing) {
            // Show progress indicator during transcription or analysis
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = when {
                        isAnalyzing -> analysisStatus.ifBlank { "Analyzing..." }
                        isTranscribing -> transcriptionStatus.ifBlank { "Transcribing..." }
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Live waveform animation
            WaveformAnimation(
                amplitude = if (isRecording && !isPaused && !isStopping) amplitude else 0f,
                barCount = 40,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Current detected emotion (hide during transcription/analysis)
        if (!isTranscribing && !isAnalyzing) {
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
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Recording controls (hide during transcription/analysis)
        if (!isTranscribing && !isAnalyzing) {
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
                                // Run on-device emotion analysis (AAC → WAV → features → emotions → DB)
                                RecordingService.TranscriptionHelper.processRecording(
                                    context = context,
                                    audioFilePath = outputPath,
                                    sessionId = sessionId
                                )

                                // Attempt AssemblyAI transcription if configured
                                RecordingService.TranscriptionHelper.transcribeAudio(
                                    context = context,
                                    audioFilePath = outputPath,
                                    sessionId = sessionId
                                )

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
}

/**
 * Save the completed recording to the Room database.
 * Creates a RecordingSession entry and returns its ID.
 *
 * Emotion analysis (AAC → WAV conversion, feature extraction, and emotion
 * classification) is handled separately by
 * [RecordingService.TranscriptionHelper.processRecording] to keep this function focused on
 * persistence and to allow the analysis to report progress via StateFlows.
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
            title = "Recording ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(startTime)}",
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            audioFilePath = outputPath,
            status = SessionStatus.COMPLETED
        )
        repository.createSession(session)
    } catch (e: Exception) {
        null
    }
}
