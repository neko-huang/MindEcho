package com.moodecho.app.ui.screens

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.service.RecordingService
import com.moodecho.app.ui.components.EmotionChip
import com.moodecho.app.ui.components.WaveformAnimation
import com.moodecho.app.util.AudioRecorder

/**
 * Recording screen: displays live waveform, current emotion, and recording controls.
 * Observes the RecordingService state via shared StateFlows.
 * Starts the RecordingService on entry and sends control Intents for pause/resume/stop.
 * After stopping, the RecordingService handles save + analysis and signals completion
 * via [RecordingService.processingResult] so the UI can react without hardcoded delays.
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

    // Observe processing result from the service (null = pending, -1 = failure, >=0 = sessionId)
    val processingResult by RecordingService.processingResult.collectAsState()

    // Local UI state
    var isPaused by remember { mutableStateOf(false) }
    var currentEmotion by remember { mutableStateOf(EmotionType.NEUTRAL) }
    var emotionConfidence by remember { mutableStateOf(0.5f) }
    var isStopping by rememberSaveable { mutableStateOf(false) }
    var recordingStarted by remember { mutableStateOf(false) }
    // Track amplitude history for real-time emotion estimation
    var amplitudeHistory by remember { mutableStateOf(listOf<Float>()) }

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

    // Real-time emotion estimation based on amplitude data during recording.
    // Uses a running average of the last ~20 samples (~1 second at 50ms intervals)
    // to estimate arousal level, then maps it to a basic emotion.
    // Low amplitude → CALM, Medium → NEUTRAL, High → HAPPY/EXCITED, Very high → EXCITED
    // Uses snapshotFlow to avoid restarting the coroutine on every amplitude change.
    LaunchedEffect(Unit) {
        snapshotFlow { amplitude }
            .collect { currentAmplitude ->
                if (!isRecording || isPaused || isStopping || isTranscribing || isAnalyzing) return@collect

                // Add current amplitude to history, keep last 20 samples
                val newHistory = (amplitudeHistory + currentAmplitude).takeLast(20)
                amplitudeHistory = newHistory

                if (newHistory.size < 5) return@collect // Need at least 5 samples

                val avgAmplitude = newHistory.average().toFloat()
                val maxAmplitude = newHistory.maxOrNull() ?: 0f
                val variance = if (newHistory.size > 1) {
                    val mean = newHistory.average()
                    newHistory.map { (it - mean) * (it - mean) }.average().toFloat()
                } else 0f

                // Rule-based emotion estimation from amplitude features
                val (emotion, confidence) = when {
                    // High amplitude + high variance = volatile/sudden changes → EXCITED or ANGRY
                    avgAmplitude > 0.35f && variance > 0.02f -> {
                        EmotionType.EXCITED to (avgAmplitude.coerceIn(0.3f, 0.8f))
                    }
                    // High amplitude + stable → HAPPY
                    avgAmplitude > 0.25f && variance < 0.015f -> {
                        EmotionType.HAPPY to (avgAmplitude.coerceIn(0.4f, 0.75f))
                    }
                    // Medium amplitude → NEUTRAL
                    avgAmplitude in 0.08f..0.25f -> {
                        EmotionType.NEUTRAL to 0.5f
                    }
                    // Low amplitude → CALM
                    avgAmplitude in 0.02f..0.08f -> {
                        EmotionType.CALM to ((0.08f - avgAmplitude) / 0.06f).coerceIn(0.3f, 0.7f)
                    }
                    // Very low amplitude (near silence) → keep current emotion
                    else -> currentEmotion to (emotionConfidence * 0.9f) // Decay confidence
                }

                currentEmotion = emotion
                emotionConfidence = confidence.coerceIn(0.2f, 0.9f)
            }
    }

    // React to the service's processing result.
    // When the service finishes save + analysis, it publishes the session ID (or -1 for failure).
    // This replaces the unreliable hardcoded delay(1500) approach.
    LaunchedEffect(processingResult) {
        if (isStopping && processingResult != null) {
            val id = processingResult!!
            if (id >= 0) {
                onFinish(id)
            } else {
                onCancel()
            }
        }
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

                        // Send STOP intent to RecordingService.
                        // The service handles save + analysis in its own scope and
                        // signals completion via processingResult StateFlow.
                        val stopIntent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_STOP
                        }
                        ContextCompat.startForegroundService(context, stopIntent)
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