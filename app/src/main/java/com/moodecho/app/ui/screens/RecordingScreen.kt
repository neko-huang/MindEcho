package com.moodecho.app.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.service.RecordingService
import com.moodecho.app.ui.components.EmotionChip
import com.moodecho.app.ui.components.WaveformAnimation

/**
 * Recording screen: displays live waveform, current emotion, and recording controls.
 * Observes the RecordingService state via shared StateFlows.
 */
@Composable
fun RecordingScreen(
    onFinish: (Long) -> Unit,
    onCancel: () -> Unit
) {
    // Observe recording state from the service
    val isRecording by RecordingService.isRecording.collectAsState()
    val duration by RecordingService.recordingDuration.collectAsState()
    val amplitude by RecordingService.currentAmplitude.collectAsState()

    // Local UI state
    var isPaused by remember { mutableStateOf(false) }
    var currentEmotion by remember { mutableStateOf(EmotionType.NEUTRAL) }
    var emotionConfidence by remember { mutableStateOf(0.5f) }

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
            text = if (isPaused) "Paused" else if (isRecording) "Recording..." else "Ready",
            style = MaterialTheme.typography.labelLarge,
            color = if (isRecording && !isPaused)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.onSurfaceVariant
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
            amplitude = if (isRecording && !isPaused) amplitude else 0f,
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
                    isPaused = !isPaused
                    // In a real app, this would send an intent to RecordingService
                },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
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
                    // In a real app, this would stop the recording and navigate to session detail
                    onCancel()
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
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
            text = "Tap stop to finish recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
