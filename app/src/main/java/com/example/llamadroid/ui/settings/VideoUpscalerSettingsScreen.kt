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
 * Video Upscaler Settings - Thread controls for load/process/save
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoUpscalerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    AppScreenScaffold(
        title = stringResource(R.string.video_upscaler_settings_title),
        subtitle = stringResource(R.string.settings_upscaler_desc),
        onBack = { navController.popBackStack() }
    ) { _ ->
        VideoUpscalerSettingsContent(
            settingsRepo = settingsRepo,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun VideoUpscalerSettingsContent(
    settingsRepo: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val loadThreads by settingsRepo.upscalerLoadThreads.collectAsState()
    val procThreads by settingsRepo.upscalerProcThreads.collectAsState()
    val saveThreads by settingsRepo.upscalerSaveThreads.collectAsState()

    LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Load Threads
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
                            Text(stringResource(R.string.video_upscaler_load_threads), fontWeight = FontWeight.Bold)
                            Text("$loadThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = loadThreads.toFloat(),
                            onValueChange = { settingsRepo.setUpscalerLoadThreads(it.toInt()) },
                            valueRange = 1f..4f,
                            steps = 2
                        )
                        Text(
                            stringResource(R.string.video_upscaler_load_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Process Threads
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
                            Text(stringResource(R.string.video_upscaler_proc_threads), fontWeight = FontWeight.Bold)
                            Text("$procThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = procThreads.toFloat(),
                            onValueChange = { settingsRepo.setUpscalerProcThreads(it.toInt()) },
                            valueRange = 1f..4f,
                            steps = 2
                        )
                        Text(
                            stringResource(R.string.video_upscaler_proc_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Save Threads
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
                            Text(stringResource(R.string.video_upscaler_save_threads), fontWeight = FontWeight.Bold)
                            Text("$saveThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = saveThreads.toFloat(),
                            onValueChange = { settingsRepo.setUpscalerSaveThreads(it.toInt()) },
                            valueRange = 1f..4f,
                            steps = 2
                        )
                        Text(
                            stringResource(R.string.video_upscaler_save_desc),
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
                            stringResource(R.string.video_upscaler_opt_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.video_upscaler_opt_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
}
