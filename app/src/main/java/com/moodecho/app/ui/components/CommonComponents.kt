package com.moodecho.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moodecho.app.domain.model.EmotionResult
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.ui.theme.EmotionAngryColor
import com.moodecho.app.ui.theme.EmotionAnxiousColor
import com.moodecho.app.ui.theme.EmotionCalmColor
import com.moodecho.app.ui.theme.EmotionExcitedColor
import com.moodecho.app.ui.theme.EmotionHappyColor
import com.moodecho.app.ui.theme.EmotionNeutralColor
import com.moodecho.app.ui.theme.EmotionSadColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Get the color associated with an emotion type.
 */
fun getEmotionColor(emotionType: EmotionType): Color = when (emotionType) {
    EmotionType.HAPPY -> EmotionHappyColor
    EmotionType.SAD -> EmotionSadColor
    EmotionType.ANGRY -> EmotionAngryColor
    EmotionType.ANXIOUS -> EmotionAnxiousColor
    EmotionType.CALM -> EmotionCalmColor
    EmotionType.EXCITED -> EmotionExcitedColor
    EmotionType.NEUTRAL -> EmotionNeutralColor
}

/**
 * Real-time waveform animation component.
 * Draws an animated waveform based on the current amplitude level.
 */
@Composable
fun WaveformAnimation(
    amplitude: Float,
    barCount: Int = 40,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveform_phase"
    )

    // Maintain a history of amplitude values for smooth animation
    val amplitudeHistory = remember { mutableStateListOf(*Array(barCount) { 0f }) }

    // Shift history and add current amplitude
    if (amplitudeHistory.size > barCount) {
        amplitudeHistory.removeAt(0)
    }
    amplitudeHistory.add(amplitude)

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        amplitudeHistory.forEachIndexed { index, amp ->
            val animatedAmp = amp.coerceIn(0f, 1f)
            // Add sine wave variation for visual interest
            val waveFactor = 0.3f + 0.7f * kotlin.math.abs(
                kotlin.math.sin(phase + index * 0.3f)
            ).toFloat()
            val barHeight = animatedAmp * waveFactor * 80f

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight.dp.coerceAtLeast(4.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
            )
            if (index < amplitudeHistory.size - 1) {
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    }
}

/**
 * Emotion chip component displaying an emotion label with color coding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotionChip(
    emotionType: EmotionType,
    confidence: Float? = null,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val color = getEmotionColor(emotionType)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) color.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        onClick = onClick ?: {}
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Color indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${emotionType.emoji} ${emotionType.displayName}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (confidence != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${(confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Session card component for displaying a recording session summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionCard(
    title: String,
    startTime: Long,
    duration: Long,
    dominantEmotion: EmotionType?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val timeStr = dateFormat.format(Date(startTime))

    val durationMinutes = (duration / 1000) / 60
    val durationSeconds = (duration / 1000) % 60
    val durationStr = "%d:%02d".format(durationMinutes, durationSeconds)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emotion indicator
            if (dominantEmotion != null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(getEmotionColor(dominantEmotion).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = dominantEmotion.emoji,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎙",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Session info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$timeStr · $durationStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (dominantEmotion != null) {
                    Text(
                        text = dominantEmotion.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = getEmotionColor(dominantEmotion)
                    )
                }
            }
        }
    }
}

/**
 * Emotion timeline visualization.
 * Displays a horizontal bar showing emotion changes over time.
 */
@Composable
fun EmotionTimeline(
    emotionResults: List<EmotionResult>,
    modifier: Modifier = Modifier
) {
    if (emotionResults.isEmpty()) {
        Text(
            text = "No emotion data available",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(8.dp)
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        emotionResults.forEachIndexed { index, result ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(getEmotionColor(result.emotionType))
            )
        }
    }
}
