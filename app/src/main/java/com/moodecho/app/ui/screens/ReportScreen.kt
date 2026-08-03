package com.moodecho.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.domain.model.EmotionType
import com.moodecho.app.ui.components.getEmotionColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Report tab screen: shown as a bottom navigation tab.
 * Displays today's report with a generate button and recent reports list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportTabScreen(
    onReportClick: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val viewModel: DailyReportViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val todayDate = remember { DailyReportViewModel.getTodayDate() }

    // Load data on first composition
    LaunchedEffect(todayDate) {
        viewModel.loadForDate(todayDate)
        viewModel.loadRecentReports()
    }

    // Show error as snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Daily Report",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Date header
            item {
                val displayDate = formatDateForDisplay(todayDate)
                Text(
                    text = displayDate,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // API key warning or generate button
            item {
                if (!uiState.hasApiKey) {
                    ApiKeyWarningCard(onNavigateToSettings = onNavigateToSettings)
                } else {
                    GenerateReportButton(
                        isLoading = uiState.isLoading,
                        sessionCount = uiState.sessionCount,
                        onClick = { viewModel.generateReport(todayDate) }
                    )
                }
            }

            // Loading indicator
            if (uiState.isLoading) {
                item {
                    LoadingCard()
                }
            }

            // Today's report content
            uiState.report?.let { report ->
                item {
                    ReportContent(report = report)
                }
            }

            // Recent reports section
            if (uiState.recentReports.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recent Reports",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(uiState.recentReports) { report ->
                    RecentReportCard(
                        report = report,
                        onClick = { onReportClick(report.date) }
                    )
                }
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * Report screen for a specific date.
 * Used when navigating from other screens with a date parameter.
 *
 * @param date The date string (yyyy-MM-dd) for the report
 * @param onBack Callback to navigate back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    date: String,
    onBack: () -> Unit
) {
    val viewModel: DailyReportViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Load data for this date
    LaunchedEffect(date) {
        viewModel.loadForDate(date)
    }

    // Show error as snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Date header
            Text(
                text = formatDateForDisplay(date),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Generate button or API key warning
            if (!uiState.hasApiKey) {
                ApiKeyWarningCard(onNavigateToSettings = onBack)
            } else {
                GenerateReportButton(
                    isLoading = uiState.isLoading,
                    sessionCount = uiState.sessionCount,
                    onClick = { viewModel.generateReport(date) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading indicator
            if (uiState.isLoading) {
                LoadingCard()
            }

            // Report content
            uiState.report?.let { report ->
                Spacer(modifier = Modifier.height(16.dp))
                ReportContent(report = report)
            }
        }
    }
}

/**
 * Warning card shown when no DeepSeek API key is configured.
 */
@Composable
private fun ApiKeyWarningCard(
    onNavigateToSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "DeepSeek API Key Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Configure your DeepSeek API key in Settings to enable " +
                        "AI-powered daily emotion reports.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onNavigateToSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Go to Settings")
            }
        }
    }
}

/**
 * Button to generate the daily report.
 */
@Composable
private fun GenerateReportButton(
    isLoading: Boolean,
    sessionCount: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onClick,
            enabled = !isLoading && sessionCount > 0,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isLoading) "Generating..." else "Generate Today's Report",
                fontWeight = FontWeight.SemiBold
            )
        }
        if (sessionCount == 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No recordings today. Start recording to generate a report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$sessionCount session${if (sessionCount > 1) "s" else ""} recorded today",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Loading card shown while the report is being generated.
 */
@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                strokeWidth = 3.dp,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Analyzing your emotions and conversations...\n" +
                        "This may take a few seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Full report content display with multiple cards.
 */
@Composable
private fun ReportContent(report: DailyReport) {
    // Overall mood card
    OverallMoodCard(
        mood = report.overallMood,
        date = report.date
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Emotion overview card
    ReportSectionCard(
        title = "Emotion Overview",
        icon = Icons.Filled.Mood,
        content = report.emotionOverview,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Key conversations summary card
    ReportSectionCard(
        title = "Key Conversations & Trends",
        icon = Icons.Filled.Insights,
        content = report.summary,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Suggestions card
    ReportSectionCard(
        title = "Suggestions",
        icon = Icons.Filled.Lightbulb,
        content = report.suggestions,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

/**
 * Overall mood card with emotion icon and label.
 */
@Composable
private fun OverallMoodCard(
    mood: EmotionType,
    date: String
) {
    val moodColor = getEmotionColor(mood)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emotion icon circle
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mood.emoji,
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = "Overall Mood",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = mood.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = moodColor
                )
            }
        }
    }
}

/**
 * A generic report section card with title, icon, and content text.
 */
@Composable
private fun ReportSectionCard(
    title: String,
    icon: ImageVector,
    content: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.9f)
            )
        }
    }
}

/**
 * Recent report card for the history list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentReportCard(
    report: DailyReport,
    onClick: () -> Unit
) {
    val moodColor = getEmotionColor(report.overallMood)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mood emoji
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = report.overallMood.emoji,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDateForDisplay(report.date),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = report.overallMood.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = moodColor
                )
                // Show truncated summary
                if (report.summary.isNotBlank()) {
                    Text(
                        text = report.summary.take(80) + if (report.summary.length > 80) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }

            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = "View report",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Format a date string (yyyy-MM-dd) for display.
 */
private fun formatDateForDisplay(dateStr: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outputFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        val date = inputFormat.parse(dateStr)
        outputFormat.format(date ?: Date())
    } catch (e: Exception) {
        dateStr
    }
}
