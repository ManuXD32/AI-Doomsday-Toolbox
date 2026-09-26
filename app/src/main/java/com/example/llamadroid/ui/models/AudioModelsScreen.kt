package com.example.llamadroid.ui.models

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.AudioCuratedBundleCatalog
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.onnx.OnnxCatalogProvider
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.CuratedModelBundleSection
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Audio-specific model management with curated and custom-compatible paths. */
@Composable
fun AudioModelsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val installed by database.modelDao().getModelsByTypes(
        listOf(ModelType.LLAMA_TTS, ModelType.LLAMA_TTS_COMPANION, ModelType.ONNX_TTS)
    ).collectAsState(initial = emptyList())
    val prefixes = remember {
        mutableStateMapOf<String, String>().apply {
            AudioCuratedBundleCatalog.bundles.forEach { put(it.id, it.defaultPrefix) }
        }
    }
    var bundleUseError by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    AppScreenScaffold(
        title = stringResource(R.string.audio_models_title),
        subtitle = stringResource(R.string.audio_models_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Text(stringResource(R.string.audio_models_installed_title), style = MaterialTheme.typography.titleMedium)
                    }
                    if (installed.isEmpty()) {
                        Text(
                            stringResource(R.string.audio_models_no_installed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        installed.forEach { model ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    model.filename,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(
                                        R.string.audio_models_installed_detail,
                                        when (model.type) {
                                            ModelType.LLAMA_TTS -> stringResource(R.string.model_promote_audio_tts)
                                            ModelType.LLAMA_TTS_COMPANION -> stringResource(R.string.model_promote_audio_tts_companion)
                                            ModelType.ONNX_TTS -> stringResource(R.string.model_promote_onnx_tts)
                                            else -> stringResource(R.string.model_promote_audio_tts)
                                        },
                                        model.repoId
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            CuratedModelBundleSection(
                title = stringResource(R.string.audio_models_curated_title),
                description = stringResource(R.string.audio_models_curated_description),
                bundles = AudioCuratedBundleCatalog.bundles,
                prefixForBundle = { bundle -> prefixes[bundle.id].orEmpty().ifBlank { bundle.defaultPrefix } },
                onUseBundle = { bundle, models, _ ->
                    val mainFile = bundle.files.firstOrNull { it.type == ModelType.LLAMA_TTS }
                    val companionFile = bundle.files.firstOrNull { it.type == ModelType.LLAMA_TTS_COMPANION }
                    val main = mainFile?.let { file ->
                        models.firstOrNull { model ->
                            model.type == ModelType.LLAMA_TTS &&
                                model.audioArtifactIdentity == file.artifactIdentity
                        }
                    }
                    val companion = companionFile?.let { file ->
                        models.firstOrNull { model ->
                            model.type == ModelType.LLAMA_TTS_COMPANION &&
                                model.audioArtifactIdentity == file.artifactIdentity
                        }
                    }
                    if (main == null || companion == null) {
                        bundleUseError = true
                    } else {
                        scope.launch {
                            val linked = withContext(Dispatchers.IO) {
                                database.modelDao().updateAudioCompanionPath(main.path, companion.path)
                            }
                            if (linked > 0) {
                                navController.navigate(Screen.AudioWorkspace.createRoute("speech")) {
                                    popUpTo(Screen.AudioModels.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                bundleUseError = true
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (bundleUseError) {
                AppStatePanel(
                    kind = AppStateKind.Error,
                    title = stringResource(R.string.audio_models_bundle_use_error),
                    message = stringResource(R.string.audio_models_bundle_use_error_hint),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(stringResource(R.string.audio_models_prefix_heading), style = MaterialTheme.typography.titleMedium)
            AudioCuratedBundleCatalog.bundles.forEach { bundle ->
                androidx.compose.material3.OutlinedTextField(
                    value = prefixes[bundle.id].orEmpty(),
                    onValueChange = { prefixes[bundle.id] = it },
                    label = { Text(stringResource(R.string.audio_models_prefix_label, bundle.defaultPrefix)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedButton(
                onClick = { navController.navigate("${Screen.ModelSources.route}?family=AUDIO&tab=download") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.audio_models_custom_button), modifier = Modifier.padding(start = 8.dp))
            }

            OutlinedButton(
                onClick = {
                    settingsRepository.setOnnxCatalogProvider(OnnxCatalogProvider.SUPERTONIC)
                    navController.navigate(Screen.OnnxModels.createRoute("catalog"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AudioFile, contentDescription = null)
                Text(stringResource(R.string.audio_models_supertonic_button), modifier = Modifier.padding(start = 8.dp))
            }

            Button(
                onClick = { navController.navigate(Screen.ModelHub.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Memory, contentDescription = null)
                Text(stringResource(R.string.audio_models_all_models_button), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
