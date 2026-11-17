package com.example.llamadroid.ui.knowledge

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.EmbeddingCuratedBundleCatalog
import java.io.File

/** One row of the embedding chooser: an installed embedding model or a not-yet-installed curated bundle. */
sealed interface EmbeddingChooserRow {
    data class Installed(val model: ModelEntity, val selected: Boolean) : EmbeddingChooserRow
    data class Catalog(val bundle: CuratedModelBundle, val recommended: Boolean) : EmbeddingChooserRow
}

/**
 * Pure list building: installed (downloaded) embedding models first, then the curated bundles that are not installed
 * yet. A bundle whose files are all installed never appears twice (only as an installed row); the recommended bundle
 * sorts first among the catalog rows.
 */
fun buildEmbeddingChooserRows(
    installed: List<ModelEntity>,
    bundles: List<CuratedModelBundle>,
    selectedPath: String?,
    recommendedId: String = EmbeddingCuratedBundleCatalog.RECOMMENDED_ID
): List<EmbeddingChooserRow> {
    val models = installed
        .filter { it.type == ModelType.EMBEDDING && it.isDownloaded }
        .distinctBy { it.path }
    val installedRows = models
        .map { EmbeddingChooserRow.Installed(it, selected = !selectedPath.isNullOrBlank() && it.path == selectedPath) }
        .sortedByDescending { it.selected }
    val installedNames = models.map { it.filename }.toSet()
    val catalogRows = bundles
        .filterNot { bundle ->
            bundle.files.all { file ->
                installedNames.any { name -> file.matchesInstalledFilename(name) }
            }
        }
        .map { EmbeddingChooserRow.Catalog(it, recommended = it.id == recommendedId) }
        .sortedByDescending { it.recommended }
    return installedRows + catalogRows
}

/**
 * "Pick default after download" rule: a freshly downloaded embedding model becomes the KB embedding model only when
 * no embedding is configured yet; an existing choice is never overridden.
 */
fun shouldAutoSelectEmbedding(configured: Boolean, model: ModelEntity): Boolean =
    !configured && model.type == ModelType.EMBEDDING && model.isDownloaded && model.path.isNotBlank()

/** True when the KB embedding setting points at something usable (a local model must still exist on disk). */
fun isKnowledgeEmbeddingConfigured(settings: SettingsRepository): Boolean {
    val backend = SettingsRepository.normalizeKnowledgeEmbeddingBackend(settings.knowledgeEmbeddingBackend.value)
    return when (backend) {
        SettingsRepository.KB_EMBED_BACKEND_LOCAL ->
            settings.selectedEmbeddingModelPath.value?.let { it.isNotBlank() && File(it).isFile } == true
        SettingsRepository.KB_EMBED_BACKEND_LLAMA_SERVER -> settings.knowledgeEmbeddingLlamaServerUrl.value.isNotBlank()
        SettingsRepository.KB_EMBED_BACKEND_LITERT -> settings.knowledgeEmbeddingLiteRtModelId.value > 0L
        SettingsRepository.KB_EMBED_BACKEND_OLLAMA ->
            settings.knowledgeEmbeddingOllamaUrl.value.isNotBlank() && !settings.knowledgeEmbeddingOllamaModel.value.isNullOrBlank()
        SettingsRepository.KB_EMBED_BACKEND_LLAMA_SWAP ->
            settings.knowledgeEmbeddingLlamaSwapUrl.value.isNotBlank() && !settings.knowledgeEmbeddingLlamaSwapModel.value.isNullOrBlank()
        else -> false
    }
}

/** Same writes the KB screen's local-model chip does, plus switching to the local backend. */
fun applyKnowledgeEmbeddingModel(settings: SettingsRepository, model: ModelEntity) {
    settings.setKnowledgeEmbeddingBackend(SettingsRepository.KB_EMBED_BACKEND_LOCAL)
    settings.setSelectedEmbeddingModelPath(model.path)
}

/**
 * Called by the download pipeline for every finished model, wherever the download started (model manager included).
 * Makes an embedding model the default only when none is configured.
 */
fun autoSelectEmbeddingAfterDownload(context: Context, model: ModelEntity) {
    if (model.type != ModelType.EMBEDDING) return
    runCatching {
        val settings = SettingsRepository(context.applicationContext)
        if (shouldAutoSelectEmbedding(isKnowledgeEmbeddingConfigured(settings), model)) {
            applyKnowledgeEmbeddingModel(settings, model)
        }
    }
}
