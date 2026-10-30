package com.example.llamadroid.ui.easy

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.data.easy.EasyImageEngine
import com.example.llamadroid.data.easy.EasyModelKind
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.importSDModel
import com.example.llamadroid.onnx.OnnxBundleValidator
import com.example.llamadroid.onnx.OnnxImportSupport
import com.example.llamadroid.onnx.OnnxInstallSource
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.buildOnnxImageGenModelEntity
import com.example.llamadroid.sd.defaultCapabilitiesForFamily
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.models.ModelManagerViewModel
import com.example.llamadroid.ui.models.importModelWithProgress
import com.example.llamadroid.ui.models.importOnnxBundleFromTree
import com.example.llamadroid.ui.models.importWhisperModel
import com.example.llamadroid.ui.models.resolveDisplayName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why an Easy import was refused or failed; each maps to one plain-language string. */
enum class EasyImportFailure {
    WRONG_CHAT_FILE, PROJECTOR_AS_MODEL, WRONG_PROJECTOR_FILE, WRONG_IMAGE_FILE, NOT_IMAGE_MODEL,
    WRONG_ZIP_FILE, ONNX_INVALID, WRONG_VOICE_FILE, COPY_FAILED
}

sealed interface EasyImportOutcome {
    data class Done(val model: ModelEntity) : EasyImportOutcome
    data class Failed(val reason: EasyImportFailure) : EasyImportOutcome
}

private fun failed(reason: EasyImportFailure) = EasyImportOutcome.Failed(reason)

private fun readHeader(context: Context, uri: Uri): ByteArray = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(16)
        val read = input.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    } ?: ByteArray(0)
}.getOrDefault(ByteArray(0))

/** Copies a picked `.gguf` LLM into the shared model folder (same backend as the advanced manager). */
internal suspend fun easyImportChatModel(context: Context, uri: Uri, onProgress: (Float) -> Unit): EasyImportOutcome =
    withContext(Dispatchers.IO) {
        val name = resolveDisplayName(context, uri)
        when (easyImportVerdict(EasyImportSlot.CHAT_MODEL, name, readHeader(context, uri))) {
            EasyImportVerdict.WRONG_FILE -> return@withContext failed(EasyImportFailure.WRONG_CHAT_FILE)
            EasyImportVerdict.PROJECTOR_AS_MODEL -> return@withContext failed(EasyImportFailure.PROJECTOR_AS_MODEL)
            EasyImportVerdict.OK -> Unit
        }
        copyWithAdvancedBackend(context, uri, name, ModelType.LLM, onProgress)
    }

/** Imports an mmproj file and links it to [llm] through `ModelEntity.mmprojPath`. */
internal suspend fun easyImportProjector(
    context: Context,
    uri: Uri,
    llm: ModelEntity,
    onProgress: (Float) -> Unit
): EasyImportOutcome = withContext(Dispatchers.IO) {
    val name = resolveDisplayName(context, uri)
    if (easyImportVerdict(EasyImportSlot.CHAT_PROJECTOR, name, readHeader(context, uri)) != EasyImportVerdict.OK) {
        return@withContext failed(EasyImportFailure.WRONG_PROJECTOR_FILE)
    }
    val outcome = copyWithAdvancedBackend(context, uri, name, ModelType.VISION_PROJECTOR, onProgress)
    if (outcome is EasyImportOutcome.Done) {
        val dao = AppDatabase.getDatabase(context).modelDao()
        runCatching {
            dao.updateAudioCompanionPath(llm.path, outcome.model.path)
            dao.updateVisionSupport(llm.filename, true)
        }.onFailure { return@withContext failed(EasyImportFailure.COPY_FAILED) }
        return@withContext EasyImportOutcome.Done(dao.getModelByFilename(llm.filename) ?: llm)
    }
    outcome
}

private suspend fun copyWithAdvancedBackend(
    context: Context,
    uri: Uri,
    name: String,
    type: ModelType,
    onProgress: (Float) -> Unit
): EasyImportOutcome {
    val db = AppDatabase.getDatabase(context)
    val viewModel = ModelManagerViewModel(ModelRepository(context, db.modelDao()))
    // importModelWithProgress logs and swallows its own errors, so success is checked on the saved row.
    importModelWithProgress(
        context = context,
        viewModel = viewModel,
        uri = uri,
        filename = name,
        type = type,
        isVision = false,
        sdCaps = null,
        onProgress = onProgress,
        onComplete = {}
    )
    val saved = db.modelDao().getModelByFilename(ModelLibraryManager.canonicalFilename(name))
    return if (saved != null && File(saved.path).isFile) EasyImportOutcome.Done(saved) else failed(EasyImportFailure.COPY_FAILED)
}

/** Imports a stable-diffusion.cpp file; type and family come from the artifact inspection. */
internal suspend fun easyImportSdModel(context: Context, uri: Uri, onProgress: (Float) -> Unit): EasyImportOutcome =
    withContext(Dispatchers.IO) {
        val name = resolveDisplayName(context, uri)
        if (easyImportVerdict(EasyImportSlot.SD_MODEL, name, readHeader(context, uri)) != EasyImportVerdict.OK) {
            return@withContext failed(EasyImportFailure.WRONG_IMAGE_FILE)
        }
        val repository = ModelRepository(context, AppDatabase.getDatabase(context).modelDao())
        val inspection = runCatching {
            repository.inspectSdArtifact(uri, name, ModelType.SD_CHECKPOINT)
        }.getOrElse { return@withContext failed(EasyImportFailure.WRONG_IMAGE_FILE) }
        val type = easySdImportType(inspection.detectedRole, inspection.mainLayout)
            ?: return@withContext failed(EasyImportFailure.NOT_IMAGE_MODEL)
        val inferred = inferSdFamily(type, "custom-import/$name", name)
        val family = inspection.detectedFamily ?: inferred.first
        if (!isEasyImageFamily(family)) return@withContext failed(EasyImportFailure.NOT_IMAGE_MODEL)
        val variant = inspection.detectedVariant ?: inferred.second
        val capabilities = if (type == ModelType.SD_CHECKPOINT) {
            buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG)
        } else {
            defaultCapabilitiesForFamily(family, type, variant)
                ?: buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG)
        }
        val result = importSDModel(
            context = context,
            repository = repository,
            uri = uri,
            filename = name,
            type = type,
            capabilities = capabilities,
            sdFamily = family?.storedValue,
            sdVariant = variant,
            onProgress = onProgress
        )
        val imported = result.getOrNull() ?: return@withContext failed(EasyImportFailure.WRONG_IMAGE_FILE)
        EasyImportOutcome.Done(imported)
    }

/** Imports an ONNX image bundle from a picked folder (same backend as the advanced ONNX screen). */
internal suspend fun easyImportOnnxFolder(context: Context, treeUri: Uri, onProgress: (Float) -> Unit): EasyImportOutcome =
    withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val repository = ModelRepository(context, db.modelDao())
        val existing = db.modelDao().getAllModels().first().map { it.filename }.toSet()
        val result = importOnnxBundleFromTree(context, repository, treeUri, existing) { progress, _ ->
            onProgress(progress)
        }
        val bundleId = result.getOrNull()?.bundleId ?: return@withContext failed(EasyImportFailure.ONNX_INVALID)
        val saved = db.modelDao().getModelByFilename(bundleId) ?: return@withContext failed(EasyImportFailure.ONNX_INVALID)
        if (saved.type == ModelType.ONNX_IMAGE_GEN) EasyImportOutcome.Done(saved) else failed(EasyImportFailure.NOT_IMAGE_MODEL)
    }

/** Imports an ONNX image bundle from a `.zip` archive. */
internal suspend fun easyImportOnnxZip(context: Context, uri: Uri, onProgress: (Float) -> Unit): EasyImportOutcome =
    withContext(Dispatchers.IO) {
        val name = resolveDisplayName(context, uri)
        if (easyImportVerdict(EasyImportSlot.ONNX_ARCHIVE, name, readHeader(context, uri)) != EasyImportVerdict.OK) {
            return@withContext failed(EasyImportFailure.WRONG_ZIP_FILE)
        }
        val db = AppDatabase.getDatabase(context)
        val repository = ModelRepository(context, db.modelDao())
        val existing = db.modelDao().getAllModels().first().map { it.filename }
        val bundleId = OnnxImportSupport.makeUniqueBundleId(OnnxImportSupport.sanitizeBundleId(name), existing)
        val archive = File(context.cacheDir, "easy_import_$bundleId.zip")
        try {
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        copied += read
                        // The copy is the first half of the progress bar, the extraction the second.
                        if (total > 0L) onProgress(0.5f * (copied.toFloat() / total).coerceIn(0f, 1f))
                        read = input.read(buffer)
                    }
                }
            } ?: return@withContext failed(EasyImportFailure.COPY_FAILED)
            OnnxStorage.ensureManagedRootsReady(context)
            val installDir = OnnxStorage.managedBundleDir(context, bundleId)
            val extracted = runCatching {
                OnnxImportSupport.extractBundleArchive(archive, installDir) { onProgress(0.5f + 0.5f * it) }
            }.getOrElse {
                OnnxImportSupport.deleteRecursively(installDir)
                if (it is CancellationException) throw it
                return@withContext failed(EasyImportFailure.ONNX_INVALID)
            }
            val validation = OnnxBundleValidator.validateDirectory(installDir)
            if (!validation.isValid) {
                OnnxImportSupport.deleteRecursively(installDir)
                return@withContext failed(EasyImportFailure.NOT_IMAGE_MODEL)
            }
            val entity = buildOnnxImageGenModelEntity(
                filename = bundleId,
                path = installDir.absolutePath,
                sizeBytes = extracted.takeIf { it > 0L } ?: OnnxImportSupport.recursiveSize(installDir),
                repoId = "custom-import/$bundleId",
                installSource = OnnxInstallSource.CUSTOM_IMPORT,
                supportedCapabilities = validation.supportedCapabilities,
                referenceUri = uri.toString(),
                referencePath = null
            )
            repository.insertModel(entity)
            EasyImportOutcome.Done(entity)
        } finally {
            archive.delete()
        }
    }

/** Imports a Whisper `.bin` (same backend as the advanced Whisper screen). */
internal suspend fun easyImportWhisper(context: Context, uri: Uri, onProgress: (Float) -> Unit): EasyImportOutcome =
    withContext(Dispatchers.IO) {
        val name = resolveDisplayName(context, uri)
        if (easyImportVerdict(EasyImportSlot.WHISPER, name, readHeader(context, uri)) != EasyImportVerdict.OK) {
            return@withContext failed(EasyImportFailure.WRONG_VOICE_FILE)
        }
        val db = AppDatabase.getDatabase(context)
        importWhisperModel(context, db, uri, name, onProgress).fold(
            onSuccess = { EasyImportOutcome.Done(it.model) },
            onFailure = { failed(EasyImportFailure.COPY_FAILED) }
        )
    }

@Composable
private fun easyImportFailureText(reason: EasyImportFailure): String = stringResource(
    when (reason) {
        EasyImportFailure.WRONG_CHAT_FILE -> R.string.easy_import_err_chat_file
        EasyImportFailure.PROJECTOR_AS_MODEL -> R.string.easy_import_err_projector_as_model
        EasyImportFailure.WRONG_PROJECTOR_FILE -> R.string.easy_import_err_projector_file
        EasyImportFailure.WRONG_IMAGE_FILE -> R.string.easy_import_err_image_file
        EasyImportFailure.NOT_IMAGE_MODEL -> R.string.easy_import_err_not_image
        EasyImportFailure.WRONG_ZIP_FILE -> R.string.easy_import_err_zip_file
        EasyImportFailure.ONNX_INVALID -> R.string.easy_import_err_onnx_invalid
        EasyImportFailure.WRONG_VOICE_FILE -> R.string.easy_import_err_voice_file
        EasyImportFailure.COPY_FAILED -> R.string.easy_import_err_copy
    }
)

/**
 * Import buttons of one Easy Models tab. Lives inside the screen's scrolling column, so progress,
 * the optional vision step and result messages scroll with it. [onImported] receives the model to
 * select automatically.
 */
@Composable
internal fun EasyModelImportSection(
    kind: EasyModelKind,
    engine: EasyImageEngine,
    onImported: (ModelEntity) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf<EasyImportFailure?>(null) }
    var successName by remember { mutableStateOf<String?>(null) }
    var linkedProjector by remember { mutableStateOf(false) }
    // Set after a chat model import so the optional vision/audio step can link to it.
    var pendingLlm by remember { mutableStateOf<ModelEntity?>(null) }

    fun startImport(block: suspend () -> EasyImportOutcome, afterSuccess: (ModelEntity) -> Unit = onImported) {
        if (busy) return
        busy = true
        progress = 0f
        failure = null
        successName = null
        linkedProjector = false
        scope.launch {
            val outcome = try {
                block()
            } catch (cancelled: CancellationException) {
                busy = false
                throw cancelled
            } catch (_: Exception) {
                EasyImportOutcome.Failed(EasyImportFailure.COPY_FAILED)
            }
            busy = false
            when (outcome) {
                is EasyImportOutcome.Done -> { successName = outcome.model.filename; afterSuccess(outcome.model) }
                is EasyImportOutcome.Failed -> failure = outcome.reason
            }
        }
    }

    // Progress callbacks may arrive from IO or Main; a Float state write is safe from both.
    val report: (Float) -> Unit = { progress = it.coerceIn(0f, 1f) }

    val chatPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport({ easyImportChatModel(context, uri, report) }) { model ->
            pendingLlm = model
            onImported(model)
        }
    }
    val projectorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val llm = pendingLlm
        if (uri != null && llm != null) startImport({ easyImportProjector(context, uri, llm, report) }) { model ->
            pendingLlm = null
            linkedProjector = true
            onImported(model)
        }
    }
    val sdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport({ easyImportSdModel(context, uri, report) })
    }
    val onnxFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startImport({ easyImportOnnxFolder(context, uri, report) })
    }
    val onnxZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport({ easyImportOnnxZip(context, uri, report) })
    }
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport({ easyImportWhisper(context, uri, report) })
    }
    val anyFile = arrayOf("*/*")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                when {
                    kind == EasyModelKind.CHAT -> R.string.easy_import_hint_chat
                    kind == EasyModelKind.VOICE -> R.string.easy_import_hint_voice
                    engine == EasyImageEngine.ONNX -> R.string.easy_import_hint_onnx
                    else -> R.string.easy_import_hint_sd
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        @Composable
        fun importButton(labelRes: Int, onClick: () -> Unit) {
            OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                AppIcon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(labelRes),
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when {
            kind == EasyModelKind.CHAT -> importButton(R.string.easy_import_chat_button) { chatPicker.launch(anyFile) }
            kind == EasyModelKind.VOICE -> importButton(R.string.easy_import_voice_button) { voicePicker.launch(anyFile) }
            engine == EasyImageEngine.ONNX -> {
                importButton(R.string.easy_import_onnx_folder_button) { onnxFolderPicker.launch(null) }
                importButton(R.string.easy_import_onnx_zip_button) { onnxZipPicker.launch(anyFile) }
            }
            else -> importButton(R.string.easy_import_sd_button) { sdPicker.launch(anyFile) }
        }

        if (busy) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.easy_import_copying), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        failure?.let {
            Text(easyImportFailureText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        successName?.let {
            Text(
                stringResource(if (linkedProjector) R.string.easy_import_linked else R.string.easy_import_done, it),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (kind == EasyModelKind.CHAT && pendingLlm != null && !busy) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.easy_import_projector_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.easy_import_projector_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { projectorPicker.launch(anyFile) }) {
                            Text(stringResource(R.string.easy_import_projector_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { pendingLlm = null }) {
                            Text(stringResource(R.string.easy_import_projector_skip), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
