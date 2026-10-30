package com.example.llamadroid.data.easy

import com.example.llamadroid.data.db.ModelDao
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.LlamaCuratedBundleCatalog
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.installedSdCuratedModel
import com.example.llamadroid.onnx.OnnxCatalog
import com.example.llamadroid.onnx.OnnxCatalogEntry
import com.example.llamadroid.onnx.OnnxCatalogProvider
import com.example.llamadroid.service.WhisperModel

enum class EasyModelKind { CHAT, IMAGE, VOICE }

enum class EasyImageEngine { SDCPP, ONNX }

/** Where an Easy entry lives in the existing catalogs. Nothing is copied from them. */
sealed interface EasyModelSource {
    data class LlamaBundle(val bundleId: String) : EasyModelSource
    data class SdBundle(val bundleId: String) : EasyModelSource
    data class Onnx(val provider: OnnxCatalogProvider, val bundleId: String) : EasyModelSource
    data class Whisper(val model: WhisperModel) : EasyModelSource
}

/**
 * One curated Easy mode choice. [recommendedMinRamBytes] is a suggestion only: nothing here
 * filters entries out.
 */
data class EasyModelEntry(
    val id: String,
    val kind: EasyModelKind,
    val source: EasyModelSource,
    val recommendedMinRamBytes: Long,
    val engine: EasyImageEngine? = null,
    val imageKind: EasyImageKind? = null
)

object EasyModelCatalog {
    private const val GIB = 1024L * 1024L * 1024L

    /** Listed from lowest to highest quality within each kind; [recommendFor] relies on it. */
    val entries: List<EasyModelEntry> = listOf(
        // Chat
        EasyModelEntry("chat-lfm25-12b", EasyModelKind.CHAT, EasyModelSource.LlamaBundle("lfm25-12b-instruct"), 3 * GIB),
        EasyModelEntry("chat-qwen35-2b", EasyModelKind.CHAT, EasyModelSource.LlamaBundle("qwen35-2b"), 4 * GIB),
        EasyModelEntry("chat-gemma4-e2b", EasyModelKind.CHAT, EasyModelSource.LlamaBundle("gemma4-e2b-complete"), 6 * GIB),
        EasyModelEntry("chat-qwen35-4b", EasyModelKind.CHAT, EasyModelSource.LlamaBundle("qwen35-4b"), 8 * GIB),
        // Image
        EasyModelEntry(
            "image-onnx-dreamshaper", EasyModelKind.IMAGE,
            EasyModelSource.Onnx(OnnxCatalogProvider.MANUXD32, "dreamshaper"), 4 * GIB,
            EasyImageEngine.ONNX, EasyImageKind.ONNX_SD15
        ),
        EasyModelEntry(
            "image-onnx-deliberate", EasyModelKind.IMAGE,
            EasyModelSource.Onnx(OnnxCatalogProvider.MANUXD32, "deliberate"), 4 * GIB,
            EasyImageEngine.ONNX, EasyImageKind.ONNX_SD15
        ),
        EasyModelEntry(
            "image-sd15-q4", EasyModelKind.IMAGE, EasyModelSource.SdBundle("low-end"), 4 * GIB,
            EasyImageEngine.SDCPP, EasyImageKind.SD15_SDCPP
        ),
        EasyModelEntry(
            "image-sdxl-turbo", EasyModelKind.IMAGE, EasyModelSource.SdBundle("midrange"), 6 * GIB,
            EasyImageEngine.SDCPP, EasyImageKind.SDXL_TURBO
        ),
        EasyModelEntry(
            "image-flux-schnell", EasyModelKind.IMAGE, EasyModelSource.SdBundle("high-end"), 12 * GIB,
            EasyImageEngine.SDCPP, EasyImageKind.FLUX_SCHNELL
        ),
        // Voice (Whisper, chat mic only)
        EasyModelEntry("voice-whisper-base", EasyModelKind.VOICE, EasyModelSource.Whisper(WhisperModel.BASE_Q5_1), 3 * GIB),
        EasyModelEntry("voice-whisper-small", EasyModelKind.VOICE, EasyModelSource.Whisper(WhisperModel.SMALL_Q5_1), 4 * GIB)
    )

    fun entriesFor(kind: EasyModelKind): List<EasyModelEntry> = entries.filter { it.kind == kind }

    fun byId(id: String): EasyModelEntry? = entries.firstOrNull { it.id == id }

    // --- Resolution against the existing catalogs ---

    fun llamaBundle(entry: EasyModelEntry): CuratedModelBundle? =
        (entry.source as? EasyModelSource.LlamaBundle)?.let { s ->
            LlamaCuratedBundleCatalog.bundles.firstOrNull { it.id == s.bundleId }
        }

    fun sdBundle(entry: EasyModelEntry): SdCuratedBundle? =
        (entry.source as? EasyModelSource.SdBundle)?.let { SdCuratedBundleCatalog.byId(it.bundleId) }

    fun onnxEntry(entry: EasyModelEntry): OnnxCatalogEntry? =
        (entry.source as? EasyModelSource.Onnx)?.let { s ->
            OnnxCatalog.entriesFor(s.provider).firstOrNull { it.bundleId == s.bundleId }
        }

    /** True when the entry still points at a real catalog item. */
    fun resolves(entry: EasyModelEntry): Boolean = when (entry.source) {
        is EasyModelSource.LlamaBundle -> llamaBundle(entry) != null
        is EasyModelSource.SdBundle -> sdBundle(entry) != null
        is EasyModelSource.Onnx -> onnxEntry(entry) != null
        is EasyModelSource.Whisper -> true
    }

    /** Approximate download size, read from the source catalog so it cannot drift. */
    fun approxBytes(entry: EasyModelEntry): Long = when (val s = entry.source) {
        is EasyModelSource.LlamaBundle -> llamaBundle(entry)?.totalBytes ?: 0L
        is EasyModelSource.SdBundle -> sdBundle(entry)?.totalSizeBytes ?: 0L
        is EasyModelSource.Onnx -> onnxEntry(entry)?.archiveSizeBytes ?: 0L
        is EasyModelSource.Whisper -> s.model.sizeBytes
    }

    // --- Install state (shared with the canonical model manager through ModelEntity) ---

    /**
     * Returns the primary installed [ModelEntity] (main LLM / checkpoint or diffusion model /
     * ONNX bundle / Whisper file) when every file of the entry is installed, else null.
     */
    fun installedModel(entry: EasyModelEntry, installed: List<ModelEntity>): ModelEntity? =
        when (val s = entry.source) {
            is EasyModelSource.LlamaBundle -> llamaBundle(entry)?.let { bundle ->
                val prefix = bundle.defaultPrefix
                val matches = bundle.files.map { file ->
                    val expected = file.installedFilename(prefix)
                    installed.firstOrNull { file.matchesVerifiedInstalledModel(expected, it) }
                }
                if (matches.all { it != null }) matches.first() else null
            }
            is EasyModelSource.SdBundle -> sdBundle(entry)?.let { bundle ->
                val matches = bundle.files.map { it.installedSdCuratedModel(bundle, installed) }
                if (matches.all { it != null }) matches.first() else null
            }
            is EasyModelSource.Onnx -> onnxEntry(entry)?.let { onnx ->
                installed.firstOrNull {
                    it.isDownloaded && it.type == onnx.modelType && it.repoId == onnx.repoId
                }
            }
            is EasyModelSource.Whisper ->
                installed.firstOrNull {
                    it.isDownloaded && it.type == ModelType.WHISPER && it.filename == s.model.filename
                }
        }

    suspend fun installedModel(entry: EasyModelEntry, dao: ModelDao): ModelEntity? {
        val types = when (entry.kind) {
            EasyModelKind.CHAT -> listOf(ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.LLM_DRAFT)
            EasyModelKind.VOICE -> listOf(ModelType.WHISPER)
            EasyModelKind.IMAGE -> when (entry.engine) {
                EasyImageEngine.ONNX -> listOf(ModelType.ONNX_IMAGE_GEN)
                else -> ModelType.entries.filter { it.name.startsWith("SD_") }
            }
        }
        return installedModel(entry, dao.getModelsByTypesSync(types))
    }

    // --- Recommendations (suggest, never filter) ---

    /**
     * Best entry of [kind] whose recommended minimum fits [totalRamBytes] (the last fitting one
     * in list order = highest quality). If none fits, the entry with the lowest minimum.
     */
    fun recommendFor(kind: EasyModelKind, totalRamBytes: Long): EasyModelEntry? {
        val list = entriesFor(kind)
        val ram = advertisedRamBytes(totalRamBytes)
        return list.lastOrNull { ram >= it.recommendedMinRamBytes }
            ?: list.minByOrNull { it.recommendedMinRamBytes }
    }

    /** Drives the "may not run well on this phone" warning; the entry stays selectable. */
    fun exceedsRecommendation(entry: EasyModelEntry, totalRamBytes: Long): Boolean =
        advertisedRamBytes(totalRamBytes) < entry.recommendedMinRamBytes

    /**
     * `ActivityManager.MemoryInfo.totalMem` excludes kernel/firmware reservations (an 8 GB phone
     * reports about 7.3 GiB), so round up to whole GiB before comparing with the tiers above.
     */
    fun advertisedRamBytes(totalRamBytes: Long): Long =
        if (totalRamBytes <= 0L) 0L else ((totalRamBytes + GIB - 1) / GIB) * GIB
}
