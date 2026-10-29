package com.example.llamadroid.ui.easy

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.easy.EasyModelCatalog
import com.example.llamadroid.data.easy.EasyModelEntry
import com.example.llamadroid.data.easy.EasyModelSource
import com.example.llamadroid.data.easy.easyLocalImageModelId
import com.example.llamadroid.data.easy.easyModeCardModelId
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.LlamaCuratedBundleCatalog
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.data.model.library.ModelDeletionStatus
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.data.repository.LocalLlamaServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** One pending removal: the display [name], the installed rows to delete and the curated [entryId] (null for imported rows). */
internal data class EasyRemovalRequest(
    val name: String,
    val models: List<ModelEntity>,
    val entryId: String?
) {
    val freedBytes: Long get() = models.sumOf { it.sizeBytes }
}

internal sealed interface EasyRemovalResult {
    data class Removed(val reclaimedBytes: Long) : EasyRemovalResult
    /** An Easy chat bound to the model is generating; nothing was touched. */
    object Busy : EasyRemovalResult
    /** The canonical deletion did not complete (blocked, failed or recoverable). */
    object Failed : EasyRemovalResult
}

/**
 * Rows of [own] that may be deleted: files whose path is also used by another installed curated
 * bundle ([protectedPaths]) are kept, and duplicate paths are collapsed.
 */
internal fun selectEasyRemovalModels(own: List<ModelEntity>, protectedPaths: Set<String>): List<ModelEntity> =
    own.filter { it.path !in protectedPaths }.distinctBy { it.path }

private fun llamaBundleRows(bundle: CuratedModelBundle, installed: List<ModelEntity>): List<ModelEntity>? {
    val rows = bundle.files.map { file ->
        val expected = file.installedFilename(bundle.defaultPrefix)
        installed.firstOrNull { file.matchesVerifiedInstalledModel(expected, it) }
    }
    return if (rows.all { it != null }) rows.filterNotNull() else null
}

private fun sdBundleRows(bundle: SdCuratedBundle, installed: List<ModelEntity>): List<ModelEntity>? {
    val rows = bundle.files.map { it.installedSdCuratedModel(bundle, installed) }
    return if (rows.all { it != null }) rows.filterNotNull() else null
}

/** Every installed row of [entry] (all bundle files for curated bundles), unfiltered. */
internal fun easyEntryInstalledRows(entry: EasyModelEntry, installed: List<ModelEntity>): List<ModelEntity> =
    when (entry.source) {
        is EasyModelSource.LlamaBundle ->
            EasyModelCatalog.llamaBundle(entry)?.let { llamaBundleRows(it, installed) }.orEmpty()
        is EasyModelSource.SdBundle ->
            EasyModelCatalog.sdBundle(entry)?.let { sdBundleRows(it, installed) }.orEmpty()
        is EasyModelSource.Onnx, is EasyModelSource.Whisper ->
            listOfNotNull(EasyModelCatalog.installedModel(entry, installed))
    }

/** Rows to delete for [entry]: its installed rows minus files another fully installed curated bundle uses. */
internal fun planEasyEntryRemoval(entry: EasyModelEntry, installed: List<ModelEntity>): List<ModelEntity> {
    val own = easyEntryInstalledRows(entry, installed)
    val ownLlamaId = EasyModelCatalog.llamaBundle(entry)?.id
    val ownSdId = EasyModelCatalog.sdBundle(entry)?.id
    val protectedPaths = HashSet<String>()
    LlamaCuratedBundleCatalog.bundles.filter { it.id != ownLlamaId }.forEach { bundle ->
        llamaBundleRows(bundle, installed)?.forEach { protectedPaths += it.path }
    }
    SdCuratedBundleCatalog.bundles.filter { it.id != ownSdId }.forEach { bundle ->
        sdBundleRows(bundle, installed)?.forEach { protectedPaths += it.path }
    }
    return selectEasyRemovalModels(own, protectedPaths)
}

/**
 * Removes [request] through the canonical journaled [ModelRepository.deleteModelWithResult] only.
 * Stops the Easy server of each model first (refusing while a bound Easy chat is generating) and
 * clears the Easy selection that pointed at a removed model.
 */
internal suspend fun removeEasyModels(
    context: Context,
    repository: ModelRepository,
    settings: EasyModeSettings,
    request: EasyRemovalRequest
): EasyRemovalResult {
    val db = AppDatabase.getDatabase(context)
    val cards = request.models.mapNotNull { db.llamaServerCardDao().getEasyCard(easyModeCardModelId(it.filename)) }
    val busyChats = NativeChatGenerationStates.busyChatIds()
    if (cards.isNotEmpty() && busyChats.isNotEmpty()) {
        val serverIds = cards.mapNotNull { db.llamaServerDao().getManagedServer(it.id)?.id }.toSet()
        val bound = db.llamaChatDao().getEasyChats().first()
            .any { chat -> chat.id in busyChats && chat.serverId?.let { it in serverIds } == true }
        if (bound) return EasyRemovalResult.Busy
    }
    cards.forEach { LlamaServerLauncher.stopSession(context, LlamaServerCardEntity.sessionIdForCard(it.id)) }

    val removed = ArrayList<ModelEntity>()
    var reclaimed = 0L
    var failed = false
    for (model in request.models) {
        val result = try {
            repository.deleteModelWithResult(model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (result == null || result.status != ModelDeletionStatus.COMPLETED) {
            failed = true
            break
        }
        removed += model
        db.llamaServerCardDao().getEasyCard(easyModeCardModelId(model.filename))?.let { card ->
            runCatching { LocalLlamaServerRepository(context, db).deleteLocalServer(card.id) }
                .onFailure { if (it is CancellationException) throw it }
        }
        reclaimed += result.reclaimedBytes
    }
    clearEasySelection(settings, request.entryId, removed)
    return if (failed) EasyRemovalResult.Failed else EasyRemovalResult.Removed(reclaimed)
}

/** Empties the Easy chat / image / voice selection when it pointed at a removed model. */
private fun clearEasySelection(settings: EasyModeSettings, entryId: String?, removed: List<ModelEntity>) {
    if (removed.isEmpty()) return
    val paths = removed.map { it.path }.toSet()
    val imageIds = removed.map { easyLocalImageModelId(it.filename) }.toSet() + listOfNotNull(entryId)
    if (settings.chatModelPath in paths) settings.chatModelPath = ""
    if (settings.imageModelId in imageIds) settings.imageModelId = ""
    if (settings.whisperModelPath in paths) settings.whisperModelPath = ""
}
