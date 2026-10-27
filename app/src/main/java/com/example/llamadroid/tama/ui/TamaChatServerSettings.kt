package com.example.llamadroid.tama.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import coil.compose.AsyncImage
import androidx.navigation.NavController
import com.example.llamadroid.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.tama.game.TamaPetCacheDecision
import com.example.llamadroid.tama.game.decideTamaPetCache
import com.example.llamadroid.ui.ai.llama.servers.ServerPickerSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.WhisperLanguages
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.TamaTranscriptionStatus
import com.example.llamadroid.tama.db.TamaSummaryEntity
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.settings.VisionHelperSettingsCard
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import java.util.UUID
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamaChatSettingsDialog(
    navController: NavController?,
    settingsRepo: SettingsRepository,
    agentService: TamaAgentService,
    petId: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val backend by settingsRepo.tamaBackend.collectAsState()
    val serverSource by settingsRepo.tamaServerSource.collectAsState()
    val useCard = serverSource == SettingsRepository.TAMA_SERVER_SOURCE_CARD
    val thinkingEnabled by settingsRepo.tamaThinkingEnabled.collectAsState()
    val petModel by settingsRepo.tamaPetModel.collectAsState()
    val summarizerModel by settingsRepo.tamaSummarizerModel.collectAsState()
    val tamaWhisperModelPath by settingsRepo.tamaWhisperModelPath.collectAsState()
    val tamaWhisperLanguage by settingsRepo.tamaWhisperLanguage.collectAsState()
    val imageInputEnabled by settingsRepo.tamaChatImageInputEnabled.collectAsState()
    val petPrompt by settingsRepo.tamaPetPrompt.collectAsState()
    val summarizerPrompt by settingsRepo.tamaSummarizerPrompt.collectAsState()
    val ollamaUrl by settingsRepo.tamaOllamaUrl.collectAsState()
    val ollamaMmap by settingsRepo.tamaOllamaMmap.collectAsState()
    val ollamaThreads by settingsRepo.tamaOllamaThreads.collectAsState()
    val ollamaNumCtx by settingsRepo.tamaOllamaNumCtx.collectAsState()
    val llamaServerUrl by settingsRepo.tamaLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepo.tamaLlamaSwapUrl.collectAsState()
    val llamaServerModelLabel by settingsRepo.tamaLlamaServerModelLabel.collectAsState()
    val llamaServerContextTokens by settingsRepo.tamaLlamaServerContextTokens.collectAsState()
    val llamaServerContextLabel by settingsRepo.tamaLlamaServerContextLabel.collectAsState()
    val tamaLiteRtModelId by settingsRepo.tamaLiteRtModelId.collectAsState()
    val tamaLiteRtBackend by settingsRepo.tamaLiteRtBackend.collectAsState()
    val tamaLiteRtMtpEnabled by settingsRepo.tamaLiteRtMtpEnabled.collectAsState()
    val availableModels by OllamaService.availableModels.collectAsState()
    val modelNames = remember(availableModels) { availableModels.map { it.name } }
    val whisperModelsFlow = remember(appContext) {
        runCatching {
            AppDatabase.getDatabase(appContext).modelDao().getModelsByType(ModelType.WHISPER)
        }.getOrElse {
            flowOf(emptyList())
        }
    }
    val whisperModels by whisperModelsFlow.collectAsState(initial = emptyList())
    val liteRtModels by remember(appContext) {
        AppDatabase.getDatabase(appContext).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val thinkingStatusLabel = stringResource(
        if (thinkingEnabled) R.string.action_enabled else R.string.action_disabled
    )

    var tempBackend by remember(backend) { mutableStateOf(backend) }
    var tempPetPrompt by remember { mutableStateOf(petPrompt) }
    var tempSummarizerPrompt by remember { mutableStateOf(summarizerPrompt) }
    var tempOllamaUrl by remember { mutableStateOf(ollamaUrl) }
    var tempLlamaServerUrl by remember { mutableStateOf(llamaServerUrl) }
    var tempLlamaSwapUrl by remember { mutableStateOf(llamaSwapUrl) }
    var tempNumCtx by remember { mutableStateOf(ollamaNumCtx.toString()) }
    var availableLlamaSwapModels by remember(petModel, summarizerModel) {
        mutableStateOf(listOf(petModel, summarizerModel).filter { it.isNotBlank() }.distinct())
    }
    var metadataMessage by remember { mutableStateOf<String?>(null) }
    var showVoiceLanguageMenu by remember { mutableStateOf(false) }
    var showLiteRtModelMenu by remember { mutableStateOf(false) }

        TamaPopupDialog(
        title = stringResource(R.string.tama_chat_settings_title),
        backgroundAsset = "tama/backgrounds/library_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TamaBackground)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                Text(stringResource(R.string.tama_chat_reasoning_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = TamaLight.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.tama_chat_thinking_setting_title),
                                fontSize = 12.sp,
                                color = TamaDark,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.tama_chat_thinking_setting_desc),
                                fontSize = 11.sp,
                                color = TamaMutedText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            thinkingStatusLabel,
                            fontSize = 12.sp,
                            color = TamaDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = { settingsRepo.setTamaThinkingEnabled(it) },
                            colors = tamaSwitchColors()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.tama_chat_media_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = TamaLight.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.tama_chat_image_support_title),
                                fontSize = 12.sp,
                                color = TamaDark,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.tama_chat_image_support_desc),
                                fontSize = 11.sp,
                                color = TamaMutedText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = imageInputEnabled,
                            onCheckedChange = settingsRepo::setTamaChatImageInputEnabled,
                            colors = tamaSwitchColors()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TamaServerSourceSection(
                    settingsRepo = settingsRepo,
                    petId = petId,
                    serverSource = serverSource
                )

                if (!useCard) {
                    Text(stringResource(R.string.tama_chat_engine_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.tama_backend_ollama),
                                selected = tempBackend == SettingsRepository.PDF_BACKEND_OLLAMA,
                                onClick = { tempBackend = SettingsRepository.PDF_BACKEND_OLLAMA },
                                modifier = Modifier.weight(1f)
                            )
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.tama_backend_llama_server),
                                selected = tempBackend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
                                onClick = {
                                    tempBackend = SettingsRepository.PDF_BACKEND_LLAMA_SERVER
                                    if (tempLlamaServerUrl.isBlank() || tempLlamaServerUrl == SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL) {
                                        tempLlamaServerUrl = SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.tama_backend_llama_swap),
                                selected = tempBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
                                onClick = {
                                    tempBackend = SettingsRepository.PDF_BACKEND_LLAMA_SWAP
                                    if (tempLlamaSwapUrl.isBlank() || tempLlamaSwapUrl == SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL) {
                                        tempLlamaSwapUrl = SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.tama_backend_litert),
                                selected = SettingsRepository.isLiteRtBackend(tempBackend),
                                onClick = { tempBackend = SettingsRepository.PDF_BACKEND_LITERT },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!SettingsRepository.isLiteRtBackend(tempBackend)) {
                        Text(stringResource(R.string.tama_chat_connection_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = when (tempBackend) {
                                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> tempLlamaServerUrl
                                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> tempLlamaSwapUrl
                                else -> tempOllamaUrl
                            },
                            onValueChange = {
                                when (tempBackend) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> tempLlamaServerUrl = it
                                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> tempLlamaSwapUrl = it
                                    else -> tempOllamaUrl = it
                                }
                            },
                            label = {
                                Text(
                                    when (tempBackend) {
                                        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> stringResource(R.string.pdf_llama_server_url_label)
                                        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> stringResource(R.string.pdf_llama_swap_url_label)
                                        else -> stringResource(R.string.pdf_ollama_url_label)
                                    },
                                    fontSize = 10.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            colors = tamaOutlinedFieldColors()
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (SettingsRepository.isLiteRtBackend(tempBackend)) {
                        Text(stringResource(R.string.tama_chat_models_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                        Spacer(modifier = Modifier.height(8.dp))
                        val selectedLiteRtModel = liteRtModels.firstOrNull { it.id == tamaLiteRtModelId }
                            ?: liteRtModels.firstOrNull()
                        ExposedDropdownMenuBox(
                            expanded = showLiteRtModelMenu,
                            onExpandedChange = { showLiteRtModelMenu = it }
                        ) {
                            OutlinedTextField(
                                value = selectedLiteRtModel?.displayName.orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                enabled = liteRtModels.isNotEmpty(),
                                label = { Text(stringResource(R.string.litert_model_label), fontSize = 10.sp) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLiteRtModelMenu) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                colors = tamaOutlinedFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = showLiteRtModelMenu,
                                onDismissRequest = { showLiteRtModelMenu = false }
                            ) {
                                liteRtModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.displayName) },
                                        onClick = {
                                            settingsRepo.setTamaLiteRtModelId(model.id)
                                            showLiteRtModelMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(stringResource(R.string.litert_gallery_accelerator), fontSize = 12.sp, color = TamaDark, fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TamaBackendChoiceButton(
                                    label = stringResource(R.string.general_acceleration_mode_auto),
                                    selected = normalizeLiteRtBackend(tamaLiteRtBackend) == LITERT_BACKEND_AUTO,
                                    onClick = { settingsRepo.setTamaLiteRtBackend(LITERT_BACKEND_AUTO) },
                                    modifier = Modifier.weight(1f)
                                )
                                TamaBackendChoiceButton(
                                    label = stringResource(R.string.general_acceleration_mode_cpu),
                                    selected = normalizeLiteRtBackend(tamaLiteRtBackend) == LITERT_BACKEND_CPU,
                                    onClick = { settingsRepo.setTamaLiteRtBackend(LITERT_BACKEND_CPU) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            TamaBackendChoiceButton(
                                label = stringResource(R.string.litert_backend_gpu),
                                selected = normalizeLiteRtBackend(tamaLiteRtBackend) == LITERT_BACKEND_GPU,
                                onClick = { settingsRepo.setTamaLiteRtBackend(LITERT_BACKEND_GPU) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.litert_gallery_mtp_title), fontSize = 12.sp, color = TamaDark, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.litert_gallery_mtp_desc),
                                    fontSize = 11.sp,
                                    color = TamaMutedText,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Switch(
                                checked = tamaLiteRtMtpEnabled,
                                onCheckedChange = settingsRepo::setTamaLiteRtMtpEnabled,
                                colors = tamaSwitchColors()
                            )
                        }
                    } else if (SettingsRepository.usesOpenAiChatBackend(tempBackend)) {
                        OutlinedButton(
                            onClick = {
                                settingsRepo.setTamaBackend(tempBackend)
                                if (SettingsRepository.isLlamaSwapBackend(tempBackend)) {
                                    settingsRepo.setTamaLlamaSwapUrl(tempLlamaSwapUrl)
                                } else {
                                    settingsRepo.setTamaLlamaServerUrl(tempLlamaServerUrl)
                                }
                                scope.launch {
                                    agentService.refreshRemoteBackendMetadata()
                                        .fold(
                                            onSuccess = { metadata ->
                                                if (SettingsRepository.isLlamaSwapBackend(metadata.backend)) {
                                                    availableLlamaSwapModels = metadata.availableModels
                                                    resources.getString(R.string.pdf_metadata_llama_swap_loaded, metadata.availableModels.size)
                                                } else {
                                                    resources.getString(R.string.tama_chat_backend_info_loaded)
                                                }
                                            },
                                            onFailure = {
                                                resources.getString(
                                                    R.string.tama_chat_backend_info_failed,
                                                    it.message ?: resources.getString(R.string.error_generic)
                                                )
                                            }
                                        )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pdf_refresh_backend_info), color = TamaDark)
                        }

                        metadataMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, fontSize = 11.sp, color = TamaMutedText, fontFamily = FontFamily.Monospace)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.tama_chat_models_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (SettingsRepository.isLlamaSwapBackend(tempBackend)) {
                            TamaModelSelector(
                                label = stringResource(R.string.tama_chat_pet_model_label),
                                selectedModel = petModel,
                                availableModels = availableLlamaSwapModels,
                                onModelChange = { settingsRepo.setTamaPetModel(it) }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            TamaModelSelector(
                                label = stringResource(R.string.tama_chat_summarizer_model_label),
                                selectedModel = summarizerModel,
                                availableModels = availableLlamaSwapModels,
                                onModelChange = { settingsRepo.setTamaSummarizerModel(it) }
                            )
                        } else {
                            Text(
                                stringResource(R.string.pdf_llama_server_model_label),
                                fontSize = 12.sp,
                                color = TamaDark,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                llamaServerModelLabel ?: stringResource(R.string.pdf_server_value_unavailable),
                                fontSize = 12.sp,
                                color = TamaDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.pdf_llama_server_context_label),
                                fontSize = 12.sp,
                                color = TamaDark,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                llamaServerContextLabel
                                    ?: llamaServerContextTokens.takeIf { it > 0 }?.toString()
                                    ?: stringResource(R.string.pdf_server_value_unavailable),
                                fontSize = 12.sp,
                                color = TamaDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.tama_chat_llama_shared_model_hint),
                                fontSize = 11.sp,
                                color = TamaMutedText,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(stringResource(R.string.tama_chat_models_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                        Spacer(modifier = Modifier.height(8.dp))

                        TamaModelSelector(
                            label = stringResource(R.string.tama_chat_pet_model_label),
                            selectedModel = petModel,
                            availableModels = modelNames,
                            onModelChange = { settingsRepo.setTamaPetModel(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TamaModelSelector(
                            label = stringResource(R.string.tama_chat_summarizer_model_label),
                            selectedModel = summarizerModel,
                            availableModels = modelNames,
                            onModelChange = { settingsRepo.setTamaSummarizerModel(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tama_chat_use_mmap), modifier = Modifier.weight(1f), fontSize = 12.sp, color = TamaDark)
                            Switch(
                                checked = ollamaMmap,
                                onCheckedChange = {
                                    settingsRepo.setTamaOllamaMmap(it)
                                    agentService.ollamaService.setUseMmap(it)
                                },
                                colors = tamaSwitchColors()
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(stringResource(R.string.tama_chat_threads_label, ollamaThreads), fontSize = 12.sp, color = TamaDark)
                        Slider(
                            value = ollamaThreads.toFloat(),
                            onValueChange = {
                                val newVal = it.toInt()
                                settingsRepo.setTamaOllamaThreads(newVal)
                                agentService.ollamaService.setNumThreads(newVal)
                            },
                            valueRange = 1f..16f,
                            steps = 14,
                            colors = SliderDefaults.colors(thumbColor = TamaDark, activeTrackColor = TamaDark)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = tempNumCtx,
                            onValueChange = { tempNumCtx = it },
                            label = { Text(stringResource(R.string.tama_chat_context_size_label), fontSize = 10.sp) },
                            modifier = Modifier.width(140.dp),
                            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            colors = tamaOutlinedFieldColors()
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = TamaAccent.copy(alpha = 0.3f))

                Text(stringResource(R.string.tama_chat_voice_section_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tama_chat_voice_section_desc),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText
                )
                Spacer(modifier = Modifier.height(8.dp))

                TamaModelSelector(
                    label = stringResource(R.string.tama_chat_voice_model_label),
                    selectedModel = tamaWhisperModelPath.orEmpty(),
                    availableModels = whisperModels.map { it.path },
                    onModelChange = settingsRepo::setTamaWhisperModelPath,
                    displayValue = { File(it).name }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = showVoiceLanguageMenu,
                    onExpandedChange = { showVoiceLanguageMenu = !showVoiceLanguageMenu }
                ) {
                    OutlinedTextField(
                        value = WhisperLanguages.languages.firstOrNull { it.first == tamaWhisperLanguage }?.second
                            ?: stringResource(R.string.whisper_auto_detect),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.tama_chat_voice_language_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVoiceLanguageMenu) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        colors = tamaOutlinedFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = showVoiceLanguageMenu,
                        onDismissRequest = { showVoiceLanguageMenu = false }
                    ) {
                        WhisperLanguages.languages.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    settingsRepo.setTamaWhisperLanguage(code)
                                    showVoiceLanguageMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (navController != null) {
                    TextButton(onClick = { navController.navigate(Screen.WhisperModels.route) }) {
                        Text(stringResource(R.string.tama_chat_voice_open_models))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = TamaAccent.copy(alpha = 0.3f))

                Text(stringResource(R.string.tama_chat_identity_memory_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.tama_chat_pet_prompt_label), fontSize = 12.sp, color = TamaDark)
                OutlinedTextField(
                    value = tempPetPrompt,
                    onValueChange = { tempPetPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = tamaOutlinedFieldColors()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.tama_chat_summarizer_prompt_label), fontSize = 12.sp, color = TamaDark)
                OutlinedTextField(
                    value = tempSummarizerPrompt,
                    onValueChange = { tempSummarizerPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = tamaOutlinedFieldColors()
                )

                Spacer(modifier = Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_cancel), color = TamaDark)
                        }
                        Button(
                            onClick = {
                                settingsRepo.setTamaBackend(tempBackend)
                                settingsRepo.setTamaPetPrompt(tempPetPrompt)
                                settingsRepo.setTamaSummarizerPrompt(tempSummarizerPrompt)
                                settingsRepo.setTamaOllamaUrl(tempOllamaUrl)
                                settingsRepo.setTamaLlamaServerUrl(tempLlamaServerUrl)
                                settingsRepo.setTamaLlamaSwapUrl(tempLlamaSwapUrl)
                                agentService.ollamaService.setBaseUrl(tempOllamaUrl)
                                tempNumCtx.toIntOrNull()?.let {
                                    settingsRepo.setTamaOllamaNumCtx(it)
                                    agentService.ollamaService.setNumCtx(it)
                                }
                                agentService.retryConnection()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TamaDark),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_save), color = TamaLight)
                        }
                    }
                }
            }
        },
        footerContent = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TamaModelSelector(
    label: String,
    selectedModel: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit,
    displayValue: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayValue(selectedModel),
            onValueChange = { },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            readOnly = true,
            colors = tamaOutlinedFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(TamaBackground)
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            displayValue(model),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TamaDark
                        )
                    },
                    onClick = {
                        onModelChange(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Cache decision for the current pet chat, shared by the settings section and the chat header.
 * Resolves the picked server card row; custom URLs never resolve one.
 */
@Composable
internal fun rememberTamaPetCacheDecision(settingsRepo: SettingsRepository, petId: String?): TamaPetCacheDecision {
    val appContext = LocalContext.current.applicationContext
    val source by settingsRepo.tamaServerSource.collectAsState()
    val cardId by settingsRepo.tamaServerCardId.collectAsState()
    val disabledIds by settingsRepo.tamaPetKvCacheDisabledIds.collectAsState()
    var card by remember { mutableStateOf<LlamaServerEntity?>(null) }
    LaunchedEffect(source, cardId) {
        card = if (source == SettingsRepository.TAMA_SERVER_SOURCE_CARD && cardId > 0L) {
            runCatching { AppDatabase.getDatabase(appContext).llamaServerDao().getServerById(cardId) }.getOrNull()
        } else {
            null
        }
    }
    val toggleOn = petId == null || petId !in disabledIds
    return decideTamaPetCache(source, card, toggleOn)
}

/** Small warning line shown in the chat header and the settings when no disk cache can be kept. */
@Composable
internal fun TamaPetCacheWarningLine(
    decision: TamaPetCacheDecision,
    modifier: Modifier = Modifier
) {
    if (!decision.showWarning) return
    Text(
        text = stringResource(R.string.tama_chat_no_disk_cache_warning),
        modifier = modifier,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        color = TamaMutedText,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** Server source choice (server card or custom URL), card picker and the per-pet disk-cache switch. */
@Composable
private fun TamaServerSourceSection(
    settingsRepo: SettingsRepository,
    petId: String?,
    serverSource: String
) {
    val useCard = serverSource == SettingsRepository.TAMA_SERVER_SOURCE_CARD
    val cardId by settingsRepo.tamaServerCardId.collectAsState()
    val disabledIds by settingsRepo.tamaPetKvCacheDisabledIds.collectAsState()
    val decision = rememberTamaPetCacheDecision(settingsRepo, petId)

    Text(stringResource(R.string.tama_chat_server_source_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TamaBackendChoiceButton(
            label = stringResource(R.string.tama_chat_server_source_card),
            selected = useCard,
            onClick = { settingsRepo.setTamaServerSource(SettingsRepository.TAMA_SERVER_SOURCE_CARD) },
            modifier = Modifier.weight(1f)
        )
        TamaBackendChoiceButton(
            label = stringResource(R.string.tama_chat_server_source_custom),
            selected = !useCard,
            onClick = { settingsRepo.setTamaServerSource(SettingsRepository.TAMA_SERVER_SOURCE_CUSTOM) },
            modifier = Modifier.weight(1f)
        )
    }

    if (useCard) {
        val serversViewModel: ServerRegistryViewModel = viewModel()
        val servers by serversViewModel.servers.collectAsState()
        var showPicker by remember { mutableStateOf(false) }
        val selected = servers.firstOrNull { it.rowId != null && it.rowId == cardId }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selected?.let { stringResource(R.string.tama_chat_server_card_selected, it.name) }
                    ?: stringResource(R.string.tama_chat_server_card_none),
                color = TamaDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showPicker) {
            ServerPickerSheet(
                viewModel = serversViewModel,
                selectedRowId = cardId.takeIf { it > 0 },
                onDismiss = { showPicker = false },
                onPick = { rowId ->
                    settingsRepo.setTamaServerCardId(rowId)
                    showPicker = false
                },
                onCreateNew = null
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.tama_chat_server_card_hint),
            fontSize = 11.sp,
            color = TamaMutedText,
            fontFamily = FontFamily.Monospace
        )

        if (petId != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tama_chat_keep_cache_title),
                        fontSize = 12.sp,
                        color = TamaDark,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.tama_chat_keep_cache_desc),
                        fontSize = 11.sp,
                        color = TamaMutedText,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = petId !in disabledIds,
                    onCheckedChange = { settingsRepo.setTamaPetKvCacheEnabled(petId, it) },
                    colors = tamaSwitchColors()
                )
            }
        }
    }

    if (!useCard) {
        val cantSee by settingsRepo.tamaServerCantSeeImages.collectAsState()
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    stringResource(R.string.tama_chat_server_cant_see_title),
                    fontSize = 12.sp,
                    color = TamaDark,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.tama_chat_server_cant_see_desc),
                    fontSize = 11.sp,
                    color = TamaMutedText,
                    fontFamily = FontFamily.Monospace
                )
            }
            Switch(
                checked = cantSee,
                onCheckedChange = settingsRepo::setTamaServerCantSeeImages,
                colors = tamaSwitchColors()
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Text(stringResource(R.string.tama_chat_vision_helper_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
    Text(
        stringResource(R.string.tama_chat_vision_helper_note),
        fontSize = 11.sp,
        color = TamaMutedText,
        fontFamily = FontFamily.Monospace
    )
    Spacer(modifier = Modifier.height(6.dp))
    VisionHelperSettingsCard()

    if (petId != null) {
        TamaPetCacheSection(petId)
    }

    if (decision.showWarning) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(R.string.tama_chat_no_disk_cache_warning),
            fontSize = 11.sp,
            color = TamaDark,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}
