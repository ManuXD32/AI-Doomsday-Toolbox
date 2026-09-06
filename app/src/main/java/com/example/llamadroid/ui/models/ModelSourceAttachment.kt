package com.example.llamadroid.ui.models

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelArtifactReference
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelLibraryRepositoryFactory
import com.example.llamadroid.data.model.library.ModelSourceDraft
import com.example.llamadroid.data.model.library.ModelSourceRepository
import com.example.llamadroid.data.model.library.ModelSourceUrlValidator
import com.example.llamadroid.data.model.library.ModelSourceKind
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal val LocalModelSourceRequest = compositionLocalOf<(ModelEntity) -> Unit> { {} }

/**
 * A model import can be local and runnable without a network source. When a
 * source is supplied, this small form deliberately accepts only a link that
 * can be persisted safely; a SAF content URI must never be copied here.
 */
@Composable
internal fun OptionalModelSourceFields(
    family: ModelFamily,
    url: String,
    onUrlChange: (String) -> Unit,
    label: String,
    onLabelChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.model_source_import_heading),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(R.string.model_source_import_help, familyLabel(family)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.model_source_url_label)) },
            placeholder = { Text(stringResource(R.string.model_source_url_hint)) },
            isError = error != null
        )
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.model_source_label)) },
            placeholder = { Text(stringResource(R.string.model_source_label_hint)) }
        )
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** A blank value is valid for local imports; a nonblank value must be safe. */
internal fun optionalModelSourceDraft(
    family: ModelFamily,
    url: String,
    label: String
): Result<ModelSourceDraft?> {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return Result.success(null)
    val draft = ModelSourceDraft(
        family = family,
        url = trimmed,
        label = label.trim().takeIf { it.isNotBlank() }
    )
    val validation = ModelSourceUrlValidator.validate(draft)
    return if (validation.isValid && validation.source?.kind in setOf(
            ModelSourceKind.HUGGING_FACE_FILE,
            ModelSourceKind.HTTPS
        )) {
        Result.success(draft)
    } else {
        Result.failure(IllegalArgumentException(validation.error ?: "Invalid source URL"))
    }
}

internal fun isDownloadableModelSourceUrl(family: ModelFamily, url: String): Boolean =
    optionalModelSourceDraft(family, url, "").isSuccess

internal data class ModelSourceAttachmentRequest(
    val asset: InstalledModelAsset,
    val sourceId: String? = null,
    val newSource: ModelSourceDraft? = null,
    val role: String? = null
)

/** Finds the most specific current link without collapsing multipart siblings. */
internal fun findProvenanceForAsset(
    asset: InstalledModelAsset,
    provenance: List<ModelProvenanceEntity>,
    requireExactPath: Boolean = false,
    canonicalPath: String? = null
): ModelProvenanceEntity? {
    val exactPaths = buildSet {
        add(asset.path)
        canonicalPath?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return provenance
    .filter { row ->
        row.family.equals(asset.family.storedValue, ignoreCase = true) &&
            (row.modelKey == asset.stableId || row.localPath in exactPaths)
    }
    .filter { row ->
        !requireExactPath || row.localPath in exactPaths
    }
    .sortedWith(
        compareByDescending<ModelProvenanceEntity> {
            it.localPath == canonicalPath || it.localPath == asset.path
        }
            .thenByDescending { it.modelKey == asset.stableId }
            .thenByDescending { it.updatedAt }
    )
    .firstOrNull()
}

/**
 * Saves a new source when needed and replaces exactly this runtime edge. The
 * repository owns the canonical replacement semantics; this helper never
 * deletes a companion provenance row and never changes a runtime payload.
 */
internal suspend fun attachModelSource(
    repository: ModelSourceRepository,
    request: ModelSourceAttachmentRequest
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val sourceId = request.sourceId ?: request.newSource?.let { draft ->
            repository.saveSource(draft).getOrThrow().id
        } ?: error("A source is required")
        val asset = request.asset
        val sizeBytes = File(asset.path).takeIf { it.isFile }?.length()
            ?: asset.model?.sizeBytes
            ?: asset.liteRt?.sizeBytes
        repository.recordProvenance(
            sourceId = sourceId,
            reference = ModelArtifactReference(
                family = asset.family,
                localPath = asset.path,
                displayName = asset.displayName,
                modelKey = asset.stableId
            ),
            role = request.role?.trim()?.takeIf { it.isNotBlank() } ?: asset.role,
            sizeBytes = sizeBytes
        ).getOrThrow()
        Unit
    }
}

@Composable
internal fun rememberModelSourceRepository(context: Context): ModelSourceRepository = remember(context) {
    ModelLibraryRepositoryFactory.create(context.applicationContext)
}

@Composable
private fun familyLabel(family: ModelFamily): String = when (family) {
    ModelFamily.LLM -> stringResource(R.string.model_library_family_llm)
    ModelFamily.SD -> stringResource(R.string.model_library_family_sd)
    ModelFamily.ONNX -> stringResource(R.string.model_library_family_onnx)
    ModelFamily.LITERT -> stringResource(R.string.model_library_family_litert)
    ModelFamily.WHISPER -> stringResource(R.string.model_library_family_whisper)
}

/**
 * Shared source chooser used by every model manager. Existing links remain
 * reusable, while editing an installed model's source creates a new source
 * identity instead of mutating a link used by another model or bundle.
 */
@Composable
internal fun ModelSourceAttachmentDialog(
    asset: InstalledModelAsset,
    sources: List<ModelSourceEntity>,
    provenance: List<ModelProvenanceEntity>,
    onDismiss: () -> Unit,
    onSave: (ModelSourceAttachmentRequest) -> Unit
) {
    var isDirectoryAsset by remember(asset.path) { mutableStateOf(false) }
    var membersResolved by remember(asset.path) { mutableStateOf(false) }
    var directoryMembers by remember(asset.path) { mutableStateOf<List<File>>(emptyList()) }
    var directoryMembersTruncated by remember(asset.path) { mutableStateOf(false) }
    var selectedMemberPath by rememberSaveable(asset.stableId) { mutableStateOf<String?>(null) }
    LaunchedEffect(asset.path) {
        val (directory, members, truncated) = withContext(Dispatchers.IO) {
            val root = File(asset.path)
            if (root.isDirectory) {
                val sample = root.walkTopDown().filter { it.isFile }.take(513).toList()
                Triple(true, sample.take(512), sample.size > 512)
            } else {
                Triple(false, emptyList(), false)
            }
        }
        isDirectoryAsset = directory
        directoryMembers = members
        directoryMembersTruncated = truncated
        membersResolved = true
        if (selectedMemberPath !in members.map { it.absolutePath }) {
            selectedMemberPath = members.firstOrNull()?.absolutePath
        }
    }
    val selectedMember = directoryMembers.firstOrNull { it.absolutePath == selectedMemberPath }
    val sourceAsset = remember(asset, selectedMember?.absolutePath, isDirectoryAsset) {
        if (isDirectoryAsset && selectedMember != null) {
            asset.copy(
                displayName = "${asset.displayName}/${selectedMember.name}",
                path = selectedMember.absolutePath,
                filename = selectedMember.name
            )
        } else {
            asset
        }
    }
    val canonicalSourcePath by produceState<String?>(null, sourceAsset.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(sourceAsset.path).canonicalPath }.getOrNull()
        }
    }
    val current = remember(sourceAsset, provenance, isDirectoryAsset, selectedMemberPath, canonicalSourcePath) {
        findProvenanceForAsset(
            asset = sourceAsset,
            provenance = provenance,
            requireExactPath = isDirectoryAsset && selectedMember != null,
            canonicalPath = canonicalSourcePath
        )
    }
    val compatibleSources = remember(sourceAsset.family, sources) {
        sources.filter {
            (it.family.equals(sourceAsset.family.storedValue, ignoreCase = true) ||
                (sourceAsset.family == ModelFamily.SD &&
                    it.family.equals(ModelFamily.LLM.storedValue, ignoreCase = true))) &&
                ModelSourceKind.fromStoredValue(it.kind) in setOf(
                    ModelSourceKind.HUGGING_FACE_FILE,
                    ModelSourceKind.HTTPS
                )
        }
            .sortedBy { it.label.lowercase() }
    }
    val directoryMappingText = stringResource(R.string.onnx_directory_mapping_required)
    var selectedSourceId by remember(current?.sourceId, compatibleSources) {
        mutableStateOf(current?.sourceId?.takeIf { id -> compatibleSources.any { it.id == id } })
    }
    var useNewSource by remember(sourceAsset.path, current?.sourceId) {
        mutableStateOf(selectedSourceId == null)
    }
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var role by remember(asset.role) { mutableStateOf(asset.role.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val invalidLinkText = stringResource(R.string.model_source_invalid_link)
    val chooseLinkText = stringResource(R.string.model_source_choose_link)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_source_attach_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    asset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                current?.let { link ->
                    val existing = sources.firstOrNull { it.id == link.sourceId }
                    Text(
                        stringResource(
                            if (existing == null) R.string.model_source_current_missing
                            else R.string.model_source_current,
                            existing?.label ?: link.sourceId
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (existing == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isDirectoryAsset) {
                    Text(
                        directoryMappingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (directoryMembers.isEmpty()) {
                        Text(
                            stringResource(R.string.model_source_directory_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            stringResource(R.string.model_source_directory_choose_file),
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (directoryMembersTruncated) {
                            Text(
                                stringResource(R.string.model_source_directory_truncated),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        directoryMembers.forEach { member ->
                            val relative = member.relativeToOrNull(File(asset.path))?.path ?: member.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.RadioButton) { selectedMemberPath = member.absolutePath }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedMemberPath == member.absolutePath,
                                    onClick = { selectedMemberPath = member.absolutePath }
                                )
                                Text(
                                    relative,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (compatibleSources.isNotEmpty()) {
                    Text(
                        stringResource(R.string.model_source_saved_heading),
                        style = MaterialTheme.typography.labelLarge
                    )
                    compatibleSources.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.RadioButton) {
                                    selectedSourceId = source.id
                                    useNewSource = false
                                    validationError = null
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !useNewSource && selectedSourceId == source.id,
                                onClick = {
                                    selectedSourceId = source.id
                                    useNewSource = false
                                    validationError = null
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    source.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.model_source_no_saved_links),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.RadioButton) {
                            useNewSource = true
                            selectedSourceId = null
                            validationError = null
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = useNewSource,
                        onClick = {
                            useNewSource = true
                            selectedSourceId = null
                            validationError = null
                        }
                    )
                    Text(stringResource(R.string.model_source_new_link))
                }

                if (useNewSource) {
                    OptionalModelSourceFields(
                        family = asset.family,
                        url = url,
                        onUrlChange = {
                            url = it
                            validationError = null
                        },
                        label = label,
                        onLabelChange = { label = it },
                        error = validationError
                    )
                }

                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.model_source_role_label)) },
                    placeholder = { Text(stringResource(R.string.model_source_role_hint)) }
                )
                Text(
                    stringResource(R.string.model_source_local_file_unchanged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = membersResolved && (!isDirectoryAsset || selectedMember != null),
                onClick = {
                    if (useNewSource) {
                        val draft = optionalModelSourceDraft(sourceAsset.family, url, label)
                        if (draft.isFailure || draft.getOrNull() == null) {
                            validationError = invalidLinkText
                        } else {
                            onSave(
                                ModelSourceAttachmentRequest(
                                    asset = sourceAsset,
                                    newSource = draft.getOrThrow(),
                                    role = role
                                )
                            )
                        }
                    } else if (selectedSourceId != null) {
                        onSave(
                            ModelSourceAttachmentRequest(
                                asset = sourceAsset,
                                sourceId = selectedSourceId,
                                role = role
                            )
                        )
                    } else {
                        validationError = chooseLinkText
                    }
                }
            ) {
                Text(stringResource(R.string.model_source_save_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Maps a runtime row to the stable family/role used by source provenance. */
internal fun installedAssetForModel(model: ModelEntity): InstalledModelAsset {
    val family = when {
        model.type.name.startsWith("SD_") -> ModelFamily.SD
        model.type.name.startsWith("ONNX_") -> ModelFamily.ONNX
        model.type.name == "WHISPER" -> ModelFamily.WHISPER
        else -> ModelFamily.LLM
    }
    val role = when (model.type.name) {
        "LLM", "VISION" -> "llm"
        "LLM_DRAFT" -> "draft"
        "LORA" -> "lora"
        "EMBEDDING" -> "embedding"
        "VISION_PROJECTOR", "MMPROJ" -> "llm_vision"
        "SD_CHECKPOINT" -> "checkpoint"
        "SD_DIFFUSION" -> "diffusion"
        "SD_LORA" -> "lora"
        "SD_TAE" -> "tae"
        "SD_VAE" -> "vae"
        "SD_T5XXL" -> "t5xxl"
        "SD_CLIP_L" -> "clip_l"
        "SD_CLIP_G" -> "clip_g"
        "SD_CLIP_VISION" -> "clip_vision"
        "SD_CONTROLNET" -> "controlnet"
        "SD_IP_ADAPTER" -> "ip_adapter"
        "SD_PHOTOMAKER" -> "photomaker"
        "SD_ADETAILER" -> "adetailer"
        "SD_TEXTUAL_INVERSION" -> "textual_inversion"
        "SD_UPSCALER" -> "upscaler"
        "SD_AUDIO_VAE" -> "audioVAE"
        "SD_EMBEDDINGS_CONNECTORS" -> "connectors"
        "SD_MOTION_MODULE" -> "motionmodule"
        "ONNX_TTS" -> "tts"
        "ONNX_BACKGROUND_REMOVAL" -> "background_removal"
        "ONNX_IMAGE_UPSCALER" -> "upscaler"
        "ONNX_IMAGE_GEN" -> "image_generation"
        "WHISPER" -> "whisper"
        else -> null
    }
    return InstalledModelAsset.fromModel(model, family, role)
}

internal fun installedAssetForLiteRtModel(model: LiteRtModelEntity): InstalledModelAsset =
    InstalledModelAsset.fromLiteRt(model)
