package com.example.llamadroid.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.importSDModel
import com.example.llamadroid.sd.buildSdCompatProfiles
import com.example.llamadroid.sd.defaultCapabilitiesForFamily
import com.example.llamadroid.ui.easy.easySdImportType
import com.example.llamadroid.ui.models.resolveDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingSdImport(val type: ModelType, val family: String?, val variant: String?)

/**
 * "Add file" for the SD component pickers: opens the document picker and imports through the
 * canonical [importSDModel]. A component import carries the main model's `family[:variant]` as its
 * compat profile, so the new file counts as a MATCH. Main-file imports (SD_DIFFUSION/SD_CHECKPOINT)
 * are inspected first to pick the right type and family. [onImported] receives the saved row.
 */
@Composable
fun rememberSdComponentImporter(
    onImported: (ModelEntity) -> Unit
): (type: ModelType, family: String?, variant: String?) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onImported)
    var pending by remember { mutableStateOf<PendingSdImport?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val request = pending
        pending = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult
        Toast.makeText(context, R.string.media_picker_import_started, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { importPickedSdFile(context, uri, request) }.mapCatching { it.getOrThrow() }
            }
            val saved = result.getOrNull()
            if (saved != null) {
                Toast.makeText(context, resources.getString(R.string.media_picker_import_done, saved.filename), Toast.LENGTH_SHORT).show()
                latest(saved)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "?"
                Toast.makeText(context, resources.getString(R.string.media_picker_import_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }
    return remember(launcher) {
        { type, family, variant ->
            pending = PendingSdImport(type, family, variant)
            launcher.launch(arrayOf("*/*"))
        }
    }
}

private suspend fun importPickedSdFile(
    context: android.content.Context,
    uri: Uri,
    request: PendingSdImport
): Result<ModelEntity> {
    val name = resolveDisplayName(context, uri)
    val repository = ModelRepository(context, AppDatabase.getDatabase(context).modelDao())
    val isMain = request.type == ModelType.SD_DIFFUSION || request.type == ModelType.SD_CHECKPOINT
    if (!isMain) {
        return importSDModel(
            context = context,
            repository = repository,
            uri = uri,
            filename = name,
            type = request.type,
            capabilities = null,
            sdFamily = null,
            sdCompatProfiles = buildSdCompatProfiles(
                request.family?.let { f -> request.variant?.takeIf { it.isNotBlank() }?.let { "$f:$it" } ?: f }
            )
        )
    }
    val inspection = runCatching { repository.inspectSdArtifact(uri, name, ModelType.SD_CHECKPOINT) }.getOrNull()
    val type = inspection?.let { easySdImportType(it.detectedRole, it.mainLayout) } ?: request.type
    val family = inspection?.detectedFamily
    val variant = inspection?.detectedVariant
    return importSDModel(
        context = context,
        repository = repository,
        uri = uri,
        filename = name,
        type = type,
        capabilities = defaultCapabilitiesForFamily(family, type, variant),
        sdFamily = family?.storedValue,
        sdVariant = variant
    )
}
