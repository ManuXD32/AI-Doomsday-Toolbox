package com.example.llamadroid.ui.audio.music

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.audio.music.StableAudio3Kind
import com.example.llamadroid.audio.music.StableAudio3ManifestEntry
import com.example.llamadroid.audio.music.StableAudio3ManifestLoader
import com.example.llamadroid.ui.audio.audioComponentLabel
import com.example.llamadroid.ui.audio.AudioComponentDetails
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.CuratedModelBundlePicker
import com.example.llamadroid.data.model.CuratedModelBundleRegistry
import com.example.llamadroid.data.model.StableAudioModelSupport

/** Guided and advanced views edit exactly the same draft. */
@Composable
fun MusicWorkspace(
    draft: MusicWorkspaceDraft,
    choices: List<MusicComponentChoice>,
    onChange: (MusicWorkspaceDraft) -> Unit,
    onManageModels: () -> Unit,
    onPickInput: () -> Unit,
    onPickLora: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    busy: Boolean,
    stage: String,
    completed: Int,
    total: Int,
    error: String?,
    ready: Boolean,
    canRetry: Boolean
) {
    var advanced by rememberSaveable(draft.kind) { mutableStateOf(false) }
    var step by rememberSaveable(draft.kind) { mutableIntStateOf(0) }
    var tab by rememberSaveable(draft.kind) { mutableIntStateOf(0) }
    fun value(key: String, value: String) = onChange(draft.withValue(key, value))
    val context = LocalContext.current
    val stableKind = if (draft.kind == "sfx") StableAudio3Kind.SFX else StableAudio3Kind.MUSIC
    val precisionEntries = remember(context, stableKind) {
        runCatching {
            StableAudio3ManifestLoader.load(context).verifiedEntries().filter { it.kind == stableKind }
        }.getOrDefault(emptyList())
    }
    val ditPrecisionOptions = precisionEntries.map { it.ditPrecision.wireValue }.distinct()
    val decoderPrecisionOptions = precisionEntries.map { it.decoderPrecision.wireValue }.distinct()
    val encoderPrecisionOptions = precisionEntries.map { it.encoderPrecision.wireValue }.distinct()
    fun applyPrecision(entry: StableAudio3ManifestEntry) {
        onChange(draft.copy(values = draft.values + mapOf(
            "ditPrecision" to entry.ditPrecision.wireValue,
            "decoderPrecision" to entry.decoderPrecision.wireValue,
            "encoderPrecision" to entry.encoderPrecision.wireValue
        )))
    }
    fun selectPrecisionProfile(predicate: (StableAudio3ManifestEntry) -> Boolean): StableAudio3ManifestEntry? {
        val matching = precisionEntries.filter(predicate)
        return matching.firstOrNull { entry ->
            entry.ditPrecision.wireValue == draft["ditPrecision"] &&
                entry.decoderPrecision.wireValue == draft["decoderPrecision"] &&
                entry.encoderPrecision.wireValue == draft["encoderPrecision"]
        } ?: matching.firstOrNull()
    }
    val audioBundleFamily = if (draft.kind == "sfx") {
        StableAudioModelSupport.FAMILY_SFX
    } else {
        StableAudioModelSupport.FAMILY_MUSIC
    }
    val audioBundles = remember(context, audioBundleFamily) {
        runCatching { CuratedModelBundleRegistry.bundles(context) }.getOrDefault(emptyList())
    }
    val guided = listOf(R.string.audio_music_step_model, R.string.audio_music_step_prompt,
        R.string.audio_music_step_input, R.string.audio_music_step_generate)
    val advancedTabs = listOf(R.string.audio_music_components, R.string.audio_music_generation,
        R.string.audio_music_input, R.string.audio_music_runtime, R.string.audio_music_output)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!advanced, { advanced = false }, label = { Text(stringResource(R.string.audio_music_guided)) })
            FilterChip(advanced, { advanced = true }, label = { Text(stringResource(R.string.audio_music_advanced)) })
        }
        ScrollableTabRow(selectedTabIndex = if (advanced) tab else step, edgePadding = 16.dp) {
            (if (advanced) advancedTabs else guided).forEachIndexed { index, label ->
                Tab(selected = index == if (advanced) tab else step,
                    onClick = { if (advanced) tab = index else step = index },
                    text = { Text(stringResource(label), maxLines = 1) })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppSectionCard {
                    when {
                        (!advanced && step == 0) || (advanced && tab == 0) -> {
                            if (!advanced) {
                                Text(stringResource(R.string.audio_music_empty_models), style = MaterialTheme.typography.bodyMedium)
                                CuratedModelBundlePicker(
                                    bundles = audioBundles,
                                    family = audioBundleFamily,
                                    selectedComponents = draft.components,
                                    onUseBundle = { _, components ->
                                        // Bundle selection only replaces compatible model paths;
                                        // prompts, operation, input, and advanced values stay intact.
                                        onChange(draft.copy(components = components))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(onClick = onManageModels, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.audio_music_manage), maxLines = 2)
                                }
                            } else {
                                Text(stringResource(R.string.audio_music_empty_models), style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = onManageModels, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.audio_music_manage), maxLines = 2)
                                }
                                listOf("dit", "textEncoder", "tokenizer", "codecEncoder", "codecDecoder").forEach { role ->
                                    val candidates = choices.filter { it.role == role }
                                    val label = when (role) {
                                        "dit" -> R.string.audio_music_role_dit
                                        "textEncoder" -> R.string.audio_music_role_textEncoder
                                        "tokenizer" -> R.string.audio_music_role_tokenizer
                                        "codecEncoder" -> R.string.audio_music_role_codecEncoder
                                        else -> R.string.audio_music_role_codecDecoder
                                    }
                                    MusicChoice(stringResource(label), draft.components[role].orEmpty(),
                                        candidates.map { it.path }, { path ->
                                            candidates.firstOrNull { it.path == path }?.name ?: audioComponentLabel(path)
                                        }) { selected -> onChange(draft.copy(components = draft.components + (role to selected))) }
                                    draft.components[role]?.takeIf { it.isNotBlank() }?.let { AudioComponentDetails(it) }
                                }
                                MusicChoice(stringResource(R.string.audio_music_dit_precision), draft["ditPrecision"],
                                    ditPrecisionOptions) { selected ->
                                        selectPrecisionProfile { it.ditPrecision.wireValue == selected }?.let(::applyPrecision)
                                    }
                                MusicChoice(stringResource(R.string.audio_music_decoder_precision), draft["decoderPrecision"],
                                    decoderPrecisionOptions) { selected ->
                                        selectPrecisionProfile { it.decoderPrecision.wireValue == selected }?.let(::applyPrecision)
                                    }
                                MusicChoice(stringResource(R.string.audio_music_encoder_precision), draft["encoderPrecision"],
                                    encoderPrecisionOptions) { selected ->
                                        selectPrecisionProfile { it.encoderPrecision.wireValue == selected }?.let(::applyPrecision)
                                    }
                            }
                        }
                        (!advanced && step == 1) || (advanced && tab == 1) -> {
                            MusicChoice(stringResource(R.string.audio_music_generation), draft["operation"],
                                listOf("generate", "remix", "inpaint", "extend"), { operation ->
                                    when (operation) {
                                        "remix" -> stringResource(R.string.audio_music_remix)
                                        "inpaint" -> stringResource(R.string.audio_music_inpaint)
                                        "extend" -> stringResource(R.string.audio_music_extend)
                                        else -> stringResource(R.string.audio_music_generate)
                                    }
                                }) { value("operation", it) }
                            MusicField(R.string.audio_music_prompt, draft["prompt"], { value("prompt", it) }, multiline = true)
                            if (advanced) {
                                MusicField(R.string.audio_music_negative, draft["negativePrompt"], { value("negativePrompt", it) }, multiline = true)
                                MusicField(R.string.audio_music_steps, draft["steps"], { value("steps", it) })
                                MusicField(R.string.audio_music_cfg, draft["cfg"], { value("cfg", it) })
                                MusicField(R.string.audio_music_apg, draft["apg"], { value("apg", it) })
                                MusicToggle(R.string.audio_music_cfg_batch, draft["cfgBatched"] == "true") { value("cfgBatched", it.toString()) }
                                Text(stringResource(R.string.audio_music_loras), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.audio_music_lora_hint), style = MaterialTheme.typography.bodySmall)
                                draft.loras.forEachIndexed { index, lora ->
                                    Text(audioComponentLabel(lora.path), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    MusicField(R.string.audio_music_strength, lora.strength, { strength ->
                                        onChange(draft.copy(loras = draft.loras.mapIndexed { i, old -> if (i == index) old.copy(strength = strength) else old }))
                                    })
                                    TextButton(onClick = { onChange(draft.copy(loras = draft.loras.filterIndexed { i, _ -> i != index })) }) {
                                        Text(stringResource(R.string.action_delete))
                                    }
                                }
                                OutlinedButton(onClick = onPickLora, enabled = draft["ditPrecision"] in setOf("fp32", "w16a32")) {
                                    Text(stringResource(R.string.audio_music_add_lora))
                                }
                            }
                        }
                        (!advanced && step == 2) || (advanced && tab == 2) -> {
                            MusicField(R.string.audio_music_duration, draft["durationSeconds"], { value("durationSeconds", it) })
                            if (draft["operation"] != "generate") {
                                Text(stringResource(R.string.audio_music_input_hint), style = MaterialTheme.typography.bodySmall)
                                Text(draft["initAudio"].takeIf { it.isNotBlank() }?.let { audioComponentLabel(it) }
                                    ?: stringResource(R.string.audio_music_no_input), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                OutlinedButton(onClick = onPickInput) { Text(stringResource(R.string.audio_music_pick_input)) }
                                if (draft["operation"] == "extend") Text(stringResource(R.string.audio_music_extend_hint))
                                if (draft["operation"] == "inpaint") {
                                    MusicField(R.string.audio_music_mask_start, draft["maskStartSeconds"], { value("maskStartSeconds", it) })
                                    MusicField(R.string.audio_music_mask_end, draft["maskEndSeconds"], { value("maskEndSeconds", it) })
                                }
                                if (advanced) MusicField(R.string.audio_music_noise, draft["initNoiseLevel"], { value("initNoiseLevel", it) })
                            }
                        }
                        advanced && tab == 3 -> {
                            MusicField(R.string.audio_music_threads, draft["threads"], { value("threads", it) })
                            MusicField(R.string.audio_music_max_rung, draft["maxRung"], { value("maxRung", it) })
                            MusicToggle(R.string.audio_music_free_models, draft["freeModels"] == "true") { value("freeModels", it.toString()) }
                        }
                        else -> {
                            MusicField(R.string.audio_music_seed, draft["seed"], { value("seed", it) })
                            MusicChoice(stringResource(R.string.audio_music_format), draft["outputFormat"], listOf("wav", "mp3")) { value("outputFormat", it) }
                            MusicToggle(R.string.audio_music_metadata, draft["includeMetadata"] == "true") { value("includeMetadata", it.toString()) }
                        }
                    }
                }
            }
            item {
                if (stage.isNotBlank()) Text(stage, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    if (total > 0) {
                        LinearProgressIndicator(progress = { completed.toFloat().div(total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.audio_music_progress, completed, total))
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGenerate, enabled = ready && !busy) { Text(stringResource(R.string.audio_music_generate)) }
                    if (busy) OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    if (canRetry) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

@Composable
private fun MusicField(label: Int, value: String, onValue: (String) -> Unit, multiline: Boolean = false) {
    OutlinedTextField(value, onValue, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1)
}

@Composable
private fun MusicToggle(label: Int, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(value, onValue)
    }
}

@Composable
private fun MusicChoice(label: String, value: String, options: List<String>,
    name: @Composable (String) -> String = { it }, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (value.isBlank()) stringResource(R.string.audio_music_no_component) else name(value), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(name(option), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}
