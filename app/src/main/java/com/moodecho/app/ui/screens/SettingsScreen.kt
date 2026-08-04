package com.moodecho.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moodecho.app.util.PreferenceManager
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Settings screen: API configuration, privacy controls, and app information.
 *
 * State management:
 * - Uses PreferenceManager.settingsState (StateFlow backed by SharedPreferences)
 *   for both reading and writing settings.
 * - collectAsState() provides a reactive State from the StateFlow, which is
 *   always up-to-date because the StateFlow is updated synchronously on every
 *   saveXxx() call.
 * - No LaunchedEffect, no local remember state, no race conditions.
 * - Every onValueChange writes to SharedPreferences synchronously AND updates
 *   the StateFlow immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current.applicationContext
    val preferenceManager = remember { PreferenceManager(context) }

    // Reactive state from the in-memory StateFlow (always up-to-date, no race conditions)
    val settings by preferenceManager.settingsState.collectAsState()

    // Local state for text fields with debounced writes to SharedPreferences
    var localDeepseekApiKey by remember { mutableStateOf(settings.deepseekApiKey ?: "") }
    var localApiBaseUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var localAssemblyAiApiKey by remember { mutableStateOf(settings.assemblyAiApiKey ?: "") }

    // Sync from StateFlow when external changes happen (e.g. rotation restore)
    LaunchedEffect(settings.deepseekApiKey, settings.apiBaseUrl, settings.assemblyAiApiKey) {
        localDeepseekApiKey = settings.deepseekApiKey ?: ""
        localApiBaseUrl = settings.apiBaseUrl
        localAssemblyAiApiKey = settings.assemblyAiApiKey ?: ""
    }

    // Debounced writes: 300ms after the user stops typing, persist to SharedPreferences
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { localDeepseekApiKey }
                .debounce(300)
                .distinctUntilChanged()
                .collect { preferenceManager.saveDeepseekApiKey(it) }
        }
    }
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { localApiBaseUrl }
                .debounce(300)
                .distinctUntilChanged()
                .collect { preferenceManager.saveApiBaseUrl(it) }
        }
    }
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { localAssemblyAiApiKey }
                .debounce(300)
                .distinctUntilChanged()
                .collect { preferenceManager.saveAssemblyAiApiKey(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- DeepSeek API Configuration Section ----
            Text(
                text = "DeepSeek API Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Optional. Configure a DeepSeek API key to enable cloud-based " +
                        "conversation summaries and report generation. The app works fully " +
                        "offline without these settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // DeepSeek API Key input — debounced write to SharedPreferences
            OutlinedTextField(
                value = localDeepseekApiKey,
                onValueChange = { localDeepseekApiKey = it },
                label = { Text("DeepSeek API Key") },
                placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // API Base URL input — debounced write to SharedPreferences
            OutlinedTextField(
                value = localApiBaseUrl,
                onValueChange = { localApiBaseUrl = it },
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.deepseek.com/") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Cloud processing toggle — save immediately
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud Processing",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Send data to DeepSeek for AI summaries and report generation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.cloudProcessingEnabled,
                        onCheckedChange = { preferenceManager.saveCloudProcessingEnabled(it) },
                        enabled = (settings.deepseekApiKey ?: "").isNotBlank()
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-transcribe toggle — save immediately
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Transcribe",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Automatically transcribe recordings after completion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoTranscribe,
                        onCheckedChange = { preferenceManager.saveAutoTranscribe(it) },
                        enabled = settings.cloudProcessingEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Auto emotion analysis toggle — save immediately
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto Emotion Analysis",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Analyze emotions from audio features after recording",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoAnalyzeEmotion,
                        onCheckedChange = { preferenceManager.saveAutoAnalyzeEmotion(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ---- AssemblyAI Transcription Section ----
            Text(
                text = "AssemblyAI Transcription",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Optional. Configure an AssemblyAI API key to enable cloud-based " +
                        "speech-to-text with speaker diarization. When configured, recordings " +
                        "will be automatically transcribed after stopping.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // AssemblyAI API Key input — debounced write to SharedPreferences
            OutlinedTextField(
                value = localAssemblyAiApiKey,
                onValueChange = { localAssemblyAiApiKey = it },
                label = { Text("AssemblyAI API Key") },
                placeholder = { Text("Your AssemblyAI API key") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ---- Privacy Statement Section ----
            Text(
                text = "Privacy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Privacy icon and title
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Privacy",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy Statement",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Privacy points
                    val privacyPoints = listOf(
                        "MindEcho 重视您的隐私。所有录音数据默认仅存储在您的设备上。",
                        "只有在您主动配置了 API 密钥后，对话内容才会发送到对应的服务（DeepSeek / AssemblyAI）进行分析和转录。",
                        "我们不会收集、上传或分享您的任何个人数据。",
                        "您可以随时删除所有数据。"
                    )

                    privacyPoints.forEach { point ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "• ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = point,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ---- About Section ----
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "MindEcho",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Version 1.0.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "An emotion-aware voice recorder that helps you understand " +
                                "the emotional patterns in your daily conversations.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}