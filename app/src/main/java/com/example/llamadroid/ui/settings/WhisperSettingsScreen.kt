package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.components.AppScreenScaffold

/**
 * Whisper Settings - Thread controls for transcription
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    AppScreenScaffold(
        title = stringResource(R.string.whisper_settings_title),
        subtitle = stringResource(R.string.settings_whisper_desc),
        onBack = { navController.popBackStack() }
    ) { _ ->
        WhisperSettingsContent(
            settingsRepo = settingsRepo,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun WhisperSettingsContent(
    settingsRepo: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val whisperThreads by settingsRepo.whisperThreads.collectAsState()

    LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Whisper Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎤 " + stringResource(R.string.whisper_threads_title), fontWeight = FontWeight.Bold)
                            Text("$whisperThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = whisperThreads.toFloat(),
                            onValueChange = { settingsRepo.setWhisperThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.whisper_threads_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Info card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "ℹ️ " + stringResource(R.string.whisper_tips_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.whisper_tip_1) + "\n" +
                            stringResource(R.string.whisper_tip_2) + "\n" +
                            stringResource(R.string.whisper_tip_3),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
}
