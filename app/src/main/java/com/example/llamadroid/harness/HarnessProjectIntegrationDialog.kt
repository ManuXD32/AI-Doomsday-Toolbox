package com.example.llamadroid.harness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.service.AgentSkillRepository
import com.example.llamadroid.service.supportsSdTxt2Img
import com.example.llamadroid.ui.agent.AgentKnowledgeBaseSelector
import com.example.llamadroid.ui.agent.AgentLiteRtBackendCard
import com.example.llamadroid.ui.agent.AgentSettingsDialog
import com.example.llamadroid.ui.agent.AgentSettingsSection
import com.example.llamadroid.ui.agent.AgentSkillManagerDialog
import com.example.llamadroid.ui.agent.CustomToolsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Uses the app's existing managers and preferences with an immutable session scope. */
@Composable
fun HarnessProjectIntegrationDialog(
    runtime: HarnessAppRuntime,
    session: HarnessSessionScope,
    onManageKnowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = runtime.applicationContext
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val skills = remember { AgentSkillRepository(context, runtime.database) }
    val conversation by runtime.database.agentChatDao().observeConversation(session.conversation.id)
        .collectAsState(initial = session.conversation)
    val knowledgeBases by runtime.database.knowledgeBaseDao().observeKnowledgeBases().collectAsState(initial = emptyList())
    val selectedIds = conversation?.knowledgeBaseIds.orEmpty().split(',').mapNotNull(String::toLongOrNull)
    var manager by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val importSkill = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("harness-skill-", ".zip", context.cacheDir)
                    try {
                        temporary.writeBytes(requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBounded(5 * 1024 * 1024) })
                        skills.installZip(temporary, sourceUri = uri.toString())
                    } finally { temporary.delete() }
                }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { failed = true }
        }
    }
    val changeKnowledge: (List<Long>) -> Unit = { ids -> scope.launch {
        try { runtime.database.agentChatDao().updateKnowledgeBaseIds(session.conversation.id, ids.distinct().joinToString(",")) }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { failed = true }
    } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_app_tools_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentKnowledgeBaseSelector(knowledgeBases, selectedIds, changeKnowledge, onManageKnowledge)
            TextButton({ manager = "tools" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_tool_settings_title)) }
            TextButton({ manager = "custom" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_custom_tools_title)) }
            TextButton({ manager = "skills" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.harness_app_skills_title)) }
            TextButton({ manager = "litert" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_litert_settings_title)) }
            if (session.workspace.backend == "REMOTE_SSH") TextButton({ manager = "ssh" }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.harness_workspace_ssh))
            }
            if (failed) Text(stringResource(R.string.harness_app_tools_failed))
        } },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.harness_runtime_close)) } }
    )
    when (manager) {
        "custom" -> Dialog({ manager = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { CustomToolsScreen { manager = null } }
        }
        "skills" -> AgentSkillManagerDialog(skills, session.conversation.id, "harness",
            onImportZip = { importSkill.launch(arrayOf("application/zip", "application/octet-stream")) }, onDismiss = { manager = null })
        "ssh" -> HarnessSshWorkspaceDialog(runtime, session.workspace, onReady = { manager = null }, onDismiss = { manager = null })
        "litert" -> HarnessLiteRtSettings(settings, runtime) { manager = null }
        "tools" -> HarnessSharedToolSettings(settings, runtime) { manager = null }
    }
}

@Composable
internal fun HarnessSharedToolSettings(settings: SettingsRepository, runtime: HarnessAppRuntime, onDismiss: () -> Unit) {
    val models by runtime.database.modelDao().getModelsByTypes(listOf(ModelType.ONNX_IMAGE_GEN, ModelType.ONNX_BACKGROUND_REMOVAL,
        ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION, ModelType.SD_VAE, ModelType.SD_TAE, ModelType.SD_CLIP_L, ModelType.SD_CLIP_G,
        ModelType.SD_T5XXL, ModelType.LLM, ModelType.SD_LLM, ModelType.VISION_PROJECTOR, ModelType.MMPROJ, ModelType.SD_PHOTOMAKER)).collectAsState(initial = emptyList())
    AgentSettingsDialog(
        settingsRepository = settings, availableModels = emptyList(), knowledgeBases = emptyList(), selectedKnowledgeBaseIds = emptyList(),
        availableImageGenerationModels = models.filter { it.isOnnxTxt2ImgBundle() }.map { it.filename },
        availableBackgroundRemovalModels = models.filter { it.isOnnxBackgroundRemovalModel() }.map { it.filename },
        availableSdImageMainModels = models.filter { it.isSdImageMainModel() && it.supportsSdTxt2Img() },
        availableSdImageSupportModels = models.filter { it.type !in setOf(ModelType.ONNX_IMAGE_GEN, ModelType.ONNX_BACKGROUND_REMOVAL, ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION) },
        onKnowledgeBaseSelectionChange = {}, onManageKnowledgeBases = {}, section = AgentSettingsSection.TOOLS,
        integrationOnly = true, onDismiss = onDismiss
    )
}

@Composable
private fun HarnessLiteRtSettings(settings: SettingsRepository, runtime: HarnessAppRuntime, onDismiss: () -> Unit) {
    val models by runtime.database.liteRtModelDao().observeAll().collectAsState(initial = emptyList())
    val selected by settings.agentLiteRtModelId.collectAsState()
    val backend by settings.agentLiteRtBackend.collectAsState()
    val context by settings.agentLiteRtContextTokens.collectAsState()
    val output by settings.agentLiteRtMaxOutputTokens.collectAsState()
    val mtp by settings.agentLiteRtMtpEnabled.collectAsState()
    val thinking by settings.agentLiteRtThinkingEnabled.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            AgentLiteRtBackendCard(models, models.firstOrNull { it.id == selected }, selected,
                settings::setAgentLiteRtModelId, showModelPicker = false, selectedBackend = backend,
                onBackendSelected = settings::setAgentLiteRtBackend, savedContextTokens = context,
                onContextTokensChange = settings::setAgentLiteRtContextTokens, savedMaxOutputTokens = output,
                onMaxOutputTokensChange = settings::setAgentLiteRtMaxOutputTokens, mtpEnabled = mtp,
                onMtpEnabledChange = settings::setAgentLiteRtMtpEnabled, thinkingEnabled = thinking,
                onThinkingEnabledChange = settings::setAgentLiteRtThinkingEnabled)
        }
    }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.harness_runtime_close)) } })
}
