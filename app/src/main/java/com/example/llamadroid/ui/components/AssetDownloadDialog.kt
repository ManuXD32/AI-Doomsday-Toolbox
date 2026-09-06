package com.example.llamadroid.ui.components

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.util.DynamicFeatureManager
import com.example.llamadroid.util.UpscalerAssetPackSupport
import com.example.llamadroid.util.UpscalerAssetPackSupport.PreparationState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog shown when the upscaler runtime needs its transient Play-delivered assets.
 */
@Composable
fun AssetDownloadDialog(
    onDismiss: () -> Unit,
    onDownloadAll: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var currentState by remember { mutableStateOf<PreparationState>(PreparationState.Pending) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val featureInstalled = remember(isDownloading) {
        DynamicFeatureManager.isModuleInstalled(context, DynamicFeatureManager.MODULE_UPSCALER)
    }
    val modelsReady = remember(isDownloading) {
        UpscalerAssetPackSupport.areModelsReady(context)
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.feature_upscaler_download_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isDownloading) {
                    val statusText = when (currentState) {
                        is PreparationState.Pending -> stringResource(R.string.feature_status_pending)
                        is PreparationState.InstallingFeature -> stringResource(R.string.feature_status_installing_feature)
                        is PreparationState.Downloading -> stringResource(R.string.feature_downloading)
                        is PreparationState.Extracting -> stringResource(R.string.feature_status_extracting)
                        is PreparationState.RemovingPack -> stringResource(R.string.feature_status_removing_pack)
                        is PreparationState.Completed -> stringResource(R.string.feature_status_completed)
                        is PreparationState.Failed -> stringResource(R.string.feature_status_failed)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (currentState is PreparationState.Downloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "$downloadProgress%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = when (currentState) {
                                is PreparationState.InstallingFeature -> stringResource(R.string.feature_status_installing_feature_desc)
                                is PreparationState.Extracting -> stringResource(R.string.feature_status_extracting_desc)
                                is PreparationState.RemovingPack -> stringResource(R.string.feature_status_removing_pack_desc)
                                else -> stringResource(R.string.feature_status_pending_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = stringResource(R.string.feature_upscaler_download_description),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    AssetPackItem(
                        title = stringResource(R.string.feature_upscaler_runtime_module),
                        description = stringResource(R.string.feature_upscaler_runtime_module_desc),
                        isInstalled = featureInstalled
                    )
                    AssetPackItem(
                        title = stringResource(R.string.feature_upscaler_runtime_models),
                        description = stringResource(R.string.feature_upscaler_runtime_models_desc),
                        isInstalled = modelsReady
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(
                            R.string.feature_download_size_warning,
                            UpscalerAssetPackSupport.estimatedDownloadSizeMb()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.feature_upscaler_download_required_force))
                }
            }
        },
        confirmButton = {
            if (!isDownloading) {
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        isDownloading = true
                        errorMessage = null
                        scope.launch {
                            UpscalerAssetPackSupport.prepareForUse(context).collectLatest { state ->
                                currentState = state
                                when (state) {
                                    is PreparationState.Downloading -> {
                                        downloadProgress = state.progress
                                    }
                                    is PreparationState.Completed -> {
                                        isDownloading = false
                                        onDownloadAll()
                                    }
                                    is PreparationState.Failed -> {
                                        errorMessage = state.error
                                        isDownloading = false
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.feature_download_all))
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
}

@Composable
private fun AssetPackItem(
    title: String,
    description: String,
    isInstalled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Extension,
            contentDescription = null,
            tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isInstalled) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
