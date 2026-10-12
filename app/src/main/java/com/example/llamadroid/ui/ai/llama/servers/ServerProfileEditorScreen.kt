package com.example.llamadroid.ui.ai.llama.servers

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.edit
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.settings.LlmSettingsContent
import com.example.llamadroid.ui.settings.LlmSettingsMode
import com.example.llamadroid.ui.settings.validSavedLlamaArguments
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

private fun profileEditorErrorRes(error: ProfileEditorError): Int = when (error) {
    ProfileEditorError.MODEL_NOT_SELECTED -> R.string.srv_error_model_not_selected
    ProfileEditorError.MODEL_FILE_MISSING -> R.string.srv_error_model_file_missing
    ProfileEditorError.MMPROJ_FILE_MISSING -> R.string.srv_error_mmproj_missing
    ProfileEditorError.DRAFT_FILE_MISSING -> R.string.srv_error_draft_missing
    ProfileEditorError.PORT_INVALID -> R.string.srv_error_port_invalid
    ProfileEditorError.PORT_IN_USE -> R.string.srv_error_port_in_use
    ProfileEditorError.FLAGS_INVALID -> R.string.srv_error_flags_invalid
}

/**
 * Full-screen editor for a local managed server. It hosts the original LLM Settings content over an
 * isolated draft [SettingsRepository], so every launch option (model, vision, speculative/MTP, KV
 * cache, context, threads, batch, load mode, LoRA, video, flash attention...) is available and
 * nothing leaks into the global LLM settings or other servers.
 *
 * @param cardId the managed server to edit, or -1 for a new one
 * @param modelPath optional model that a new server starts with
 */
@Composable
fun ServerProfileEditorScreen(navController: NavController, cardId: Long, modelPath: String?) {
    val context = LocalContext.current
    // Activity scope: a "Save and start" must outlive this destination being popped.
    val owner = remember(context) { context.findComponentActivity() }
    val viewModel: ServerRegistryViewModel = if (owner != null) {
        viewModel(viewModelStoreOwner = owner)
    } else {
        viewModel()
    }
    val scope = rememberCoroutineScope()
    val servers by viewModel.servers.collectAsState()
    val isEdit = cardId > 0L

    var seeded by rememberSaveable { mutableStateOf(false) }
    val draft = remember {
        // Fresh entry: wipe any previous draft before the repository reads its preferences.
        if (!seeded) {
            // apply() updates the shared in-memory preferences at once, which the draft reads below.
            context.getSharedPreferences(SERVER_PROFILE_DRAFT_PREFS, Context.MODE_PRIVATE).edit { clear() }
        }
        SettingsRepository(context, SERVER_PROFILE_DRAFT_PREFS, isProfileDraft = true)
    }
    var name by rememberSaveable { mutableStateOf("") }
    var nameTouched by rememberSaveable { mutableStateOf(isEdit) }
    var portText by rememberSaveable { mutableStateOf("") }
    var allowWear by rememberSaveable { mutableStateOf(false) }
    var baselineProfile by rememberSaveable { mutableStateOf("") }
    var baselineMeta by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Set<ProfileEditorError>>(emptySet()) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }

    fun metaOf() = "$name|$portText|$allowWear"

    LaunchedEffect(Unit) {
        if (seeded) return@LaunchedEffect
        val seed = withContext(Dispatchers.IO) {
            if (isEdit) viewModel.loadManagedSeed(cardId) else null
        }
        val profile: LlamaServerLaunchProfile
        if (seed != null) {
            profile = seed.profile
            name = seed.name
            portText = seed.port.toString()
            allowWear = seed.allowWearStart
        } else {
            // New server (or a card whose preset vanished): start from the user's current LLM settings.
            var base = LlamaServerLaunchProfile.capture(SettingsRepository(context))
            if (!modelPath.isNullOrBlank()) base = viewModel.profileForModelPath(base, modelPath)
            profile = base
            if (isEdit) servers.firstOrNull { it.cardId == cardId }?.let { name = it.name; portText = it.port.toString() }
        }
        if (!isEdit && !nameTouched) name = suggestedServerName(name, false, profile.modelPath)
        LlamaServerLaunchProfile.restore(profile, draft)
        baselineProfile = profileFingerprint(LlamaServerLaunchProfile.capture(draft))
        baselineMeta = metaOf()
        seeded = true
    }

    val selectedModelPath by draft.selectedModelPath.collectAsState()
    LaunchedEffect(seeded, selectedModelPath) {
        if (seeded && !nameTouched) name = suggestedServerName(name, false, selectedModelPath.orEmpty())
    }

    val editing = if (isEdit) servers.firstOrNull { it.cardId == cardId } else null
    val running = editing?.status == UnifiedServerStatus.RUNNING
    val usedPorts = remember(servers, editing) { usedLocalPorts(servers, editing) }

    fun isDirty(): Boolean = seeded && (
        profileFingerprint(LlamaServerLaunchProfile.capture(draft)) != baselineProfile || metaOf() != baselineMeta
    )

    fun requestExit() {
        if (saving) return
        if (isDirty()) showDiscard = true else navController.popBackStack()
    }

    fun save(restart: Boolean) {
        if (saving || !seeded) return
        val captured = LlamaServerLaunchProfile.capture(draft)
        val found = validateProfileEditor(
            captured, portText, usedPorts, viewModel::fileReadable, ::validSavedLlamaArguments
        )
        errors = found
        if (found.isNotEmpty()) return
        saving = true
        saveError = null
        val port = portText.trim().toIntOrNull()
        val profile = profileForSave(captured, port)
        val finalName = name.trim().ifBlank { defaultServerName(profile.modelPath) }
        scope.launch {
            val result = if (isEdit) {
                viewModel.updateLocal(cardId, profile, finalName, port, allowWear)
            } else {
                viewModel.createLocal(finalName, profile, port, allowWear, startAfterCreate = restart)
                    .map { }
            }
            result.onSuccess {
                if (isEdit && restart) editing?.let(viewModel::restart)
                navController.popBackStack()
            }.onFailure { saveError = it.message }
            saving = false
        }
    }

    BackHandler(enabled = !showDiscard) { requestExit() }

    AppScreenScaffold(
        title = stringResource(if (isEdit) R.string.spe_title_edit else R.string.spe_title_new),
        subtitle = stringResource(R.string.spe_subtitle),
        onBack = ::requestExit,
        bottomBar = {
            ProfileEditorBottomBar(
                isEdit = isEdit,
                running = running,
                saving = saving || !seeded,
                errors = errors,
                saveError = saveError,
                onCancel = ::requestExit,
                onSave = { save(restart = false) },
                onSaveAndRun = { save(restart = true) }
            )
        }
    ) { padding ->
        if (!seeded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.spe_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LlmSettingsContent(
                settingsRepo = draft,
                mode = LlmSettingsMode.SERVER_PROFILE,
                modifier = Modifier.fillMaxSize().padding(padding),
                header = {
                    item(key = "server_basics") {
                        ServerBasicsCard(
                            name = name,
                            onNameChange = { name = it; nameTouched = true },
                            portText = portText,
                            onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                            allowWear = allowWear,
                            onAllowWearChange = { allowWear = it },
                            errors = errors
                        )
                    }
                },
                onModelSelected = { model: ModelEntity ->
                    scope.launch {
                        // Same sensible defaults the old editor applied when a model was picked.
                        val base = viewModel.baseProfileFor(model)
                        draft.setSelectedMmprojPath(base.mmprojPath)
                        draft.setEnableVision(base.visionEnabled)
                        draft.setContextSize(base.contextSize)
                    }
                }
            )
        }
    }

    if (showDiscard) {
        WalkthroughAlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.spe_discard_title)) },
            text = { Text(stringResource(R.string.spe_discard_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; navController.popBackStack() }) {
                    Text(stringResource(R.string.spe_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.spe_discard_keep), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }
}

@Composable
private fun ServerBasicsCard(
    name: String,
    onNameChange: (String) -> Unit,
    portText: String,
    onPortChange: (String) -> Unit,
    allowWear: Boolean,
    onAllowWearChange: (Boolean) -> Unit,
    errors: Set<ProfileEditorError>
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.spe_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.spe_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.llama_card_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                placeholder = { Text(stringResource(R.string.llama_card_name_hint), maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val portError = ProfileEditorError.PORT_INVALID in errors || ProfileEditorError.PORT_IN_USE in errors
            OutlinedTextField(
                value = portText,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.llama_card_port), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                placeholder = { Text(stringResource(R.string.srv_port_auto), maxLines = 1) },
                supportingText = { Text(stringResource(R.string.srv_port_hint)) },
                isError = portError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.llama_card_allow_wear_start), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.llama_card_allow_wear_start_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = allowWear, onCheckedChange = onAllowWearChange)
            }
        }
    }
}

@Composable
private fun ProfileEditorBottomBar(
    isEdit: Boolean,
    running: Boolean,
    saving: Boolean,
    errors: Set<ProfileEditorError>,
    saveError: String?,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRun: () -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (errors.isNotEmpty() || saveError != null) {
                Column(
                    modifier = Modifier.heightIn(max = 88.dp).verticalScroll(rememberScrollState())
                ) {
                    errors.forEach {
                        Text(
                            stringResource(profileEditorErrorRes(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    saveError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isEdit) {
                    Button(onClick = onSave, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Button(onClick = onSaveAndRun, enabled = !saving, modifier = Modifier.weight(2f)) {
                        Text(stringResource(R.string.srv_save_and_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (isEdit && running) {
                Button(onClick = onSaveAndRun, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.spe_save_and_restart), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
