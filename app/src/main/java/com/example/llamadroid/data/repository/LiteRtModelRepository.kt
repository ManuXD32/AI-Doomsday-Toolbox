package com.example.llamadroid.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.dao.LiteRtModelDao
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_KB_EMBED_RUNTIME_BERT_WORDPIECE
import com.example.llamadroid.data.model.LITERT_KB_EMBED_RUNTIME_EMBEDDING_GEMMA
import com.example.llamadroid.data.model.LITERT_KB_EMBED_RUNTIME_STRING_TFLITE
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.liteRtAudioSupportFromText
import com.example.llamadroid.data.model.liteRtEmbeddingSupportFromText
import com.example.llamadroid.data.model.liteRtEngineMaxTokensFromText
import com.example.llamadroid.data.model.liteRtKbEmbeddingRuntimeFromText
import com.example.llamadroid.data.model.liteRtPackageTargetFromText
import com.example.llamadroid.data.model.liteRtVisionSupportFromText
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.util.DebugLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class LiteRtCatalogEntry(
    val repoId: String,
    val title: String,
    val description: String,
    val preferredFileName: String? = null,
    val directDownloadUrl: String? = null,
    val sizeBytes: Long? = null,
    val defaultBackend: String = LITERT_BACKEND_AUTO,
    val supportsCpu: Boolean = true,
    val supportsGpu: Boolean = true,
    val supportsNpu: Boolean = false,
    val supportsVision: Boolean = liteRtVisionSupportFromText(
        listOf(title, preferredFileName.orEmpty(), repoId).joinToString(" ")
    ),
    val supportsAudio: Boolean = liteRtAudioSupportFromText(
        listOf(title, preferredFileName.orEmpty(), repoId).joinToString(" ")
    ),
    val supportsEmbedding: Boolean = listOf(title, preferredFileName.orEmpty(), repoId)
        .joinToString(" ")
        .let { text -> liteRtEmbeddingSupportFromText(text) },
    val maxContextTokens: Int? = liteRtEngineMaxTokensFromText(
        listOf(title, preferredFileName.orEmpty(), repoId).joinToString(" ")
    ),
    val gated: Boolean = isLikelyGatedLiteRtRepo(repoId),
    val category: LiteRtCatalogCategory = LiteRtCatalogCategory.GPU,
    val catalogId: String = "${category.name.lowercase(Locale.US)}|$repoId|${preferredFileName ?: "default"}"
)

enum class LiteRtCatalogCategory {
    GPU,
    CPU
}

data class LiteRtKbEmbeddingCompatibility(
    val embeddingLike: Boolean,
    val runnable: Boolean,
    val runtime: String?,
    val status: String?
)

object LiteRtModelCatalog {
    val defaultEntries = gpuEntries()
    val embeddingEntries = emptyList<LiteRtCatalogEntry>()
    val allEntries = defaultEntries

    fun entriesFor(category: LiteRtCatalogCategory): List<LiteRtCatalogEntry> =
        defaultEntries.filter { it.category == category }

    private fun gpuEntries() = listOf(
        cpu(
            repoId = "litert-community/Qwen3-0.6B",
            title = "Qwen3 0.6B",
            description = "Small Apache-2.0 chat model with a conservative CPU default in ADT.",
            preferredFileName = "Qwen3-0.6B.litertlm",
            maxContextTokens = 4096,
            sizeBytes = mb(790)
        ),
        cpu(
            repoId = "litert-community/Qwen3-4B",
            title = "Qwen3 4B",
            description = "Modern Qwen3 instruct model packaged as channelwise int8 LiteRT-LM with a conservative CPU default.",
            preferredFileName = "qwen3_4b_channelwise_int8_float32kv.litertlm",
            sizeBytes = gb(4.6)
        ),
        cpu(
            repoId = "litert-community/Qwen3-8B",
            title = "Qwen3 8B",
            description = "Larger Qwen3 LiteRT-LM option for high-memory devices, defaulting to CPU in ADT.",
            preferredFileName = "qwen3_8b_channelwise_int8_float32kv.litertlm",
            sizeBytes = gb(8.9)
        ),
        cpu(
            repoId = "litert-community/Qwen3-14B",
            title = "Qwen3 14B",
            description = "Large modern Qwen3 LiteRT-LM package for high-memory devices, defaulting to CPU in ADT.",
            preferredFileName = "qwen3_14b_channelwise_int8_float32kv.litertlm",
            sizeBytes = gb(15.2)
        ),
        gpu(
            repoId = "litert-community/Gemma3-1B-IT",
            title = "Gemma 3 1B IT",
            description = "Compact instruction model with published LiteRT Android variants.",
            preferredFileName = "gemma3-1b-it-int4.litertlm",
            maxContextTokens = 2048,
            sizeBytes = mb(720)
        ),
        gpu(
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            title = "Gemma 4 E2B IT LiteRT-LM",
            description = "Closest LiteRT-LM path to the current Gemma 4 chat workflow.",
            preferredFileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = gb(2.8)
        ),
        gpu(
            repoId = "litert-community/gemma-4-E4B-it-litert-lm",
            title = "Gemma 4 E4B IT LiteRT-LM",
            description = "Larger Gemma 4 LiteRT-LM package for quality-first testing.",
            preferredFileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = gb(5.4)
        ),
        gpu(
            repoId = "google/gemma-3n-E2B-it-litert-lm",
            title = "Gemma 3n E2B IT LiteRT-LM",
            description = "Google Gemma 3n LiteRT-LM package with efficient on-device chat variants.",
            preferredFileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = gb(2.9)
        ),
        gpu(
            repoId = "google/gemma-3n-E4B-it-litert-lm",
            title = "Gemma 3n E4B IT LiteRT-LM",
            description = "Larger Gemma 3n LiteRT-LM package for capable phones and tablets.",
            preferredFileName = "gemma-3n-E4B-it-int4.litertlm",
            sizeBytes = gb(5.6)
        ),
        cpu(
            repoId = "litert-community/Qwen2.5-1.5B-Instruct",
            title = "Qwen2.5 1.5B Instruct",
            description = "Reliable compact Qwen instruct model with LiteRT-LM packaging and a conservative CPU default.",
            preferredFileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = gb(1.8)
        ),
        cpu(
            repoId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            title = "DeepSeek R1 Distill Qwen 1.5B",
            description = "Reasoning-focused distilled Qwen model in LiteRT-LM format with a conservative CPU default.",
            preferredFileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = gb(1.8)
        ),
        cpu(
            repoId = "litert-community/Phi-4-mini-instruct",
            title = "Phi-4 Mini Instruct",
            description = "Microsoft Phi-family compact instruct model packaged for LiteRT-LM with a conservative CPU default.",
            preferredFileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = gb(4.3)
        ),
        cpu(
            repoId = "litert-community/SmolLM2-360M-Instruct",
            title = "SmolLM2 360M Instruct",
            description = "Tiny fast chat model for quick CPU smoke tests and low-memory devices.",
            preferredFileName = "SmolLM2_360M_instruct.litertlm",
            sizeBytes = mb(430)
        ),
        cpu(
            repoId = "litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm",
            title = "FunctionGemma Mobile Actions",
            description = "Small action-oriented LiteRT-LM package for mobile tool/action experiments, defaulting to CPU in ADT.",
            preferredFileName = "mobile-actions_q8_ekv1024.litertlm",
            maxContextTokens = 1024,
            sizeBytes = mb(780)
        )
    )

    private fun gpu(
        repoId: String,
        title: String,
        description: String,
        preferredFileName: String,
        maxContextTokens: Int? = null,
        sizeBytes: Long? = null
    ) = LiteRtCatalogEntry(
        repoId = repoId,
        title = title,
        description = description,
        preferredFileName = preferredFileName,
        sizeBytes = sizeBytes,
        maxContextTokens = maxContextTokens
            ?: liteRtEngineMaxTokensFromText(listOf(title, preferredFileName, repoId).joinToString(" ")),
        category = LiteRtCatalogCategory.GPU
    )

    private fun cpu(
        repoId: String,
        title: String,
        description: String,
        preferredFileName: String,
        maxContextTokens: Int? = null,
        sizeBytes: Long? = null
    ) = LiteRtCatalogEntry(
        repoId = repoId,
        title = title,
        description = description,
        preferredFileName = preferredFileName,
        defaultBackend = com.example.llamadroid.data.model.LITERT_BACKEND_CPU,
        supportsGpu = false,
        sizeBytes = sizeBytes,
        maxContextTokens = maxContextTokens
            ?: liteRtEngineMaxTokensFromText(listOf(title, preferredFileName, repoId).joinToString(" ")),
        category = LiteRtCatalogCategory.CPU
    )

    private fun mb(value: Long): Long = value * 1024L * 1024L

    private fun gb(value: Double): Long = (value * 1024.0 * 1024.0 * 1024.0).toLong()

}

class LiteRtModelRepository(
    private val context: Context,
    private val modelDao: LiteRtModelDao
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val token = huggingFaceToken().trim()
            val request = if (token.isNotBlank()) {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .build()
    private val hfService = Retrofit.Builder()
        .baseUrl("https://huggingface.co/api/")
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HuggingFaceService::class.java)

    fun observeModels(): Flow<List<LiteRtModelEntity>> =
        modelDao.observeAll()
            .onStart { pruneManagedRowsIfNeeded() }
            .map(::sanitizeVisibleModels)

    fun observeEmbeddingCapableModels(): Flow<List<LiteRtModelEntity>> =
        modelDao.observeAll()
            .onStart { pruneManagedRowsIfNeeded() }
            .map(::sanitizeVisibleModels)
            .map { models ->
                models.filter { model -> model.supportsEmbedding && File(model.path).exists() }
                    .sortedWith(
                        compareByDescending<LiteRtModelEntity> { it.kbEmbeddingRunnable }
                            .thenByDescending { it.updatedAt }
                            .thenBy { it.displayName.lowercase(Locale.US) }
                    )
            }

    fun huggingFaceToken(): String = preferences.getString(KEY_HF_TOKEN, "").orEmpty()

    fun saveHuggingFaceToken(token: String) {
        preferences.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    fun managedRoot(): File {
        return File(context.applicationContext.noBackupFilesDir, "litert_models").apply { mkdirs() }
    }

    suspend fun startCatalogDownload(entry: LiteRtCatalogEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = huggingFaceToken().takeIf { it.isNotBlank() }
            if (entry.gated && token == null) {
                error(context.getString(R.string.litert_hf_token_required_error, 403))
            }
            val selected = resolveCatalogPackage(entry)
            val target = uniqueManagedFile(File(selected.path).name)
            val progressKey = "litert:${entry.catalogId}"
            val sourceUri = entry.directDownloadUrl?.substringBeforeLast('/', missingDelimiterValue = entry.directDownloadUrl)
                ?: "https://huggingface.co/${entry.repoId}"
            val downloadUrl = entry.directDownloadUrl ?: "$sourceUri/resolve/main/${selected.path}"

            DownloadProgressHolder.updateProgress(progressKey, target.name, 0f)
            DownloadProgressHolder.updateStatus(progressKey, entry.title)
            PendingDownloadHolder.addPending(
                downloadId = progressKey,
                filename = target.name,
                repoId = entry.repoId,
                progressKey = progressKey,
                type = ModelType.LLM,
                destPath = target.absolutePath,
                huggingFaceToken = token,
                liteRtDisplayName = entry.title,
                liteRtSourceUri = sourceUri,
                liteRtBackendPreference = normalizeLiteRtBackend(entry.defaultBackend),
                liteRtSupportsCpu = entry.supportsCpu,
                liteRtSupportsGpu = entry.supportsGpu,
                liteRtSupportsVision = entry.supportsVision,
                liteRtSupportsAudio = entry.supportsAudio,
                liteRtSupportsEmbedding = entry.supportsEmbedding,
                liteRtMaxContextTokens = entry.maxContextTokens
            )
            DownloadService.startDownload(
                context = context,
                url = downloadUrl,
                destPath = target.absolutePath,
                filename = target.name,
                downloadId = progressKey
            )
        }.onFailure {
            DownloadProgressHolder.removeProgress("litert:${entry.catalogId}")
            DebugLog.log("LiteRtModelRepository: failed to start download for ${entry.repoId}: ${it.message}")
        }
    }

    suspend fun searchLiveCatalog(query: String): Result<List<LiteRtCatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            if (trimmed.length < 2) return@runCatching emptyList()
            val repoIds = linkedSetOf<String>()
            repoIds += seededLiteRtReposForQuery(trimmed)
            layeredSearchTerms(trimmed).forEach { searchText ->
                repoIds += hfService.searchModels(searchText, filter = null, limit = 48)
                    .map { it.id }
            }
            val exactRepoId = exactRepoIdFromQuery(trimmed)
            exactRepoId?.let { repoId ->
                repoIds += repoId
            }
            val results = mutableListOf<LiteRtCatalogEntry>()
            for (repoId in repoIds) {
                if (results.size >= 12) break
                if (LiteRtModelCatalog.allEntries.any { curated -> curated.repoId == repoId }) continue
                val entry = runCatching {
                    val packageFile = resolveCatalogPackage(
                        LiteRtCatalogEntry(
                            repoId = repoId,
                            title = repoId.substringAfterLast('/').replace('-', ' '),
                            description = context.getString(R.string.litert_catalog_live_entry_desc),
                            preferredFileName = null,
                            catalogId = "live|$repoId"
                        )
                    )
                    val fileName = File(packageFile.path).name
                    LiteRtCatalogEntry(
                        repoId = repoId,
                        title = repoId.substringAfterLast('/').replace('-', ' '),
                        description = context.getString(R.string.litert_catalog_live_entry_desc),
                        preferredFileName = packageFile.path,
                        supportsVision = liteRtVisionSupportFromText(listOf(repoId, fileName).joinToString(" ")),
                        supportsAudio = liteRtAudioSupportFromText(listOf(repoId, fileName).joinToString(" ")),
                        supportsEmbedding = liteRtEmbeddingSupportFromText(listOf(repoId, fileName).joinToString(" ")),
                        maxContextTokens = liteRtEngineMaxTokensFromText(listOf(repoId, fileName).joinToString(" ")),
                        sizeBytes = packageFile.size.takeIf { it > 0L },
                        gated = isLikelyGatedLiteRtRepo(repoId),
                        catalogId = "live|$repoId|${packageFile.path}"
                    )
                }.getOrNull()
                if (entry != null) results += entry
            }
            if (repoIds.isNotEmpty() && results.isEmpty()) {
                error(context.getString(R.string.litert_catalog_live_no_packages))
            }
            results.sortedWith(liveResultComparator(exactRepoId))
        }.onFailure {
            DebugLog.log("LiteRtModelRepository: live catalog search failed for '$query': ${it.message}")
        }
    }

    suspend fun downloadCatalog(entry: LiteRtCatalogEntry): Result<LiteRtModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val selected = resolveCatalogPackage(entry)

            val filename = File(selected.path).name
            val target = uniqueManagedFile(filename)
            val progressKey = "litert:${entry.catalogId}"
            DownloadProgressHolder.updateProgress(progressKey, filename, 0f)
            downloadToFile(
                url = entry.directDownloadUrl ?: "https://huggingface.co/${entry.repoId}/resolve/main/${selected.path}",
                target = target,
                progressKey = progressKey,
                label = filename
            )
            DownloadProgressHolder.removeProgress(progressKey)

            val installedPath = installDownloadedPackage(
                downloadedFile = target,
                repoId = entry.repoId,
                supportsEmbedding = entry.supportsEmbedding
            )
            saveModelRecord(
                displayName = entry.title,
                path = installedPath,
                sourceUri = entry.directDownloadUrl?.substringBeforeLast('/', missingDelimiterValue = entry.directDownloadUrl)
                    ?: "https://huggingface.co/${entry.repoId}",
                repoId = entry.repoId,
                backendPreference = normalizeLiteRtBackend(entry.defaultBackend),
                supportsCpu = entry.supportsCpu,
                supportsGpu = entry.supportsGpu,
                supportsNpu = entry.supportsNpu,
                supportsVision = entry.supportsVision,
                supportsAudio = entry.supportsAudio,
                supportsEmbedding = entry.supportsEmbedding,
                maxContextTokens = entry.maxContextTokens
            )
        }.onFailure {
            DownloadProgressHolder.removeProgress("litert:${entry.catalogId}")
            DebugLog.log("LiteRtModelRepository: download failed for ${entry.repoId}: ${it.message}")
        }
    }

    suspend fun finalizeServiceDownload(
        pending: PendingDownload,
        downloadedFile: File,
        onProgress: (Float, String) -> Unit
    ): LiteRtModelEntity = withContext(Dispatchers.IO) {
        val displayName = pending.liteRtDisplayName ?: downloadedFile.nameWithoutExtension
        onProgress(0.92f, displayName)
        val installedPath = installDownloadedPackage(
            downloadedFile = downloadedFile,
            repoId = pending.repoId,
            supportsEmbedding = pending.liteRtSupportsEmbedding == true
        )
        onProgress(0.98f, displayName)
        saveModelRecord(
            displayName = displayName,
            path = installedPath,
            sourceUri = pending.liteRtSourceUri,
            repoId = pending.repoId,
            backendPreference = normalizeLiteRtBackend(pending.liteRtBackendPreference ?: LITERT_BACKEND_AUTO),
            supportsCpu = pending.liteRtSupportsCpu ?: inferCpuSupport(installedPath),
            supportsGpu = pending.liteRtSupportsGpu ?: inferGpuSupport(installedPath),
            supportsNpu = false,
            supportsVision = pending.liteRtSupportsVision
                ?: inferLiteRtVisionSupport(displayName, installedPath, pending.repoId),
            supportsAudio = pending.liteRtSupportsAudio
                ?: inferLiteRtAudioSupport(displayName, installedPath, pending.repoId),
            supportsEmbedding = pending.liteRtSupportsEmbedding
                ?: inferLiteRtEmbeddingSupport(installedPath),
            maxContextTokens = pending.liteRtMaxContextTokens
                ?: inferLiteRtMaxContextTokens(displayName, installedPath, pending.repoId)
        ).also {
            onProgress(1f, displayName)
        }
    }

    suspend fun reconcileManagedModels(): Int = withContext(Dispatchers.IO) {
        val root = managedRoot()
        if (!root.exists()) return@withContext 0
        val candidates = root
            .walkTopDown()
            .filter { it.isFile && it.isLiteRtManagedRuntimeFile() }
            .toList()
            .sortedBy { it.absolutePath.lowercase(Locale.US) }
        var inserted = 0
        candidates.forEach { candidate ->
            if (modelDao.getByPath(candidate.absolutePath) == null) {
                val entry = LiteRtModelCatalog.findByFilename(candidate.name)
                saveModelRecord(
                    displayName = entry?.title ?: candidate.nameWithoutExtension,
                    path = candidate,
                    sourceUri = entry?.repoId?.let { "https://huggingface.co/$it" },
                    repoId = entry?.repoId,
                    backendPreference = normalizeLiteRtBackend(entry?.defaultBackend ?: LITERT_BACKEND_AUTO),
                    supportsCpu = entry?.supportsCpu ?: inferCpuSupport(candidate),
                    supportsGpu = entry?.supportsGpu ?: inferGpuSupport(candidate),
                    supportsNpu = false,
                    supportsVision = entry?.supportsVision
                        ?: inferLiteRtVisionSupport(entry?.title ?: candidate.nameWithoutExtension, candidate, entry?.repoId),
                    supportsAudio = entry?.supportsAudio
                        ?: inferLiteRtAudioSupport(entry?.title ?: candidate.nameWithoutExtension, candidate, entry?.repoId),
                    supportsEmbedding = entry?.supportsEmbedding
                        ?: inferLiteRtEmbeddingSupport(candidate),
                    maxContextTokens = entry?.maxContextTokens
                        ?: inferLiteRtMaxContextTokens(entry?.title ?: candidate.nameWithoutExtension, candidate, entry?.repoId)
                )
                inserted += 1
            }
        }
        if (inserted > 0) {
            DebugLog.log("LiteRtModelRepository: reconciled $inserted LiteRT model file(s) from ${root.absolutePath}")
        }
        inserted
    }

    suspend fun importFromUri(
        uri: Uri,
        supportsVisionOverride: Boolean? = null,
        supportsAudioOverride: Boolean? = null,
        supportsEmbeddingOverride: Boolean? = null
    ): Result<LiteRtModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val document = DocumentFile.fromSingleUri(context, uri)
            val sourceName = document?.name?.takeIf { it.isNotBlank() } ?: "imported_litert_model.litertlm"
            val target = uniqueManagedFile(safeFileName(sourceName))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: error("Unable to open selected file")

            val installedPath = installDownloadedPackage(
                downloadedFile = target,
                repoId = null,
                supportsEmbedding = supportsEmbeddingOverride ?: liteRtEmbeddingSupportFromText(sourceName)
            )
            saveModelRecord(
                displayName = installedPath.nameWithoutExtension.ifBlank { sourceName },
                path = installedPath,
                sourceUri = uri.toString(),
                repoId = null,
                backendPreference = LITERT_BACKEND_AUTO,
                supportsCpu = inferCpuSupport(installedPath),
                supportsGpu = inferGpuSupport(installedPath),
                supportsNpu = false,
                supportsVision = supportsVisionOverride
                    ?: inferLiteRtVisionSupport(installedPath.nameWithoutExtension.ifBlank { sourceName }, installedPath, null),
                supportsAudio = supportsAudioOverride
                    ?: inferLiteRtAudioSupport(installedPath.nameWithoutExtension.ifBlank { sourceName }, installedPath, null),
                supportsEmbedding = supportsEmbeddingOverride
                    ?: inferLiteRtEmbeddingSupport(installedPath),
                maxContextTokens = inferLiteRtMaxContextTokens(
                    displayName = installedPath.nameWithoutExtension.ifBlank { sourceName },
                    file = installedPath,
                    repoId = null
                )
            )
        }
    }

    suspend fun exportModel(model: LiteRtModelEntity, destinationUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(model.path)
            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                if (source.isDirectory) {
                    ZipOutputStream(output).use { zip -> zipDirectory(source, source, zip) }
                } else {
                    source.inputStream().use { input -> input.copyTo(output) }
                }
            } ?: error("Unable to open export destination")
            Unit
        }
    }

    suspend fun renameModel(model: LiteRtModelEntity, displayName: String) {
        modelDao.updateDisplayName(model.id, displayName.trim().ifBlank { model.displayName })
    }

    suspend fun updateBackendPreference(model: LiteRtModelEntity, backend: String) {
        modelDao.updateBackendPreference(model.id, normalizeLiteRtBackend(backend))
    }

    suspend fun updateMaxContextTokens(model: LiteRtModelEntity, maxContextTokens: Int?) {
        modelDao.updateMaxContextTokens(model.id, maxContextTokens?.takeIf { it > 0 })
    }

    suspend fun updateCapabilitySupport(
        model: LiteRtModelEntity,
        supportsVision: Boolean,
        supportsAudio: Boolean,
        supportsEmbedding: Boolean
    ) {
        modelDao.updateCapabilitySupport(model.id, supportsVision, supportsAudio, supportsEmbedding)
    }

    suspend fun removeModel(model: LiteRtModelEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(model.path)
            if (file.exists()) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
            modelDao.delete(model)
        }
    }

    private suspend fun saveModelRecord(
        displayName: String,
        path: File,
        sourceUri: String?,
        repoId: String?,
        backendPreference: String,
        supportsCpu: Boolean,
        supportsGpu: Boolean,
        supportsNpu: Boolean,
        supportsVision: Boolean,
        supportsAudio: Boolean,
        supportsEmbedding: Boolean,
        maxContextTokens: Int?
    ): LiteRtModelEntity {
        val now = System.currentTimeMillis()
        val size = if (path.isDirectory) path.walkTopDown().filter { it.isFile }.sumOf { it.length() } else path.length()
        val compatibility = evaluateKbEmbeddingCompatibility(path, repoId, supportsEmbedding)
        val normalizedSupportsEmbedding = supportsEmbedding || compatibility.embeddingLike
        val allModels = modelDao.getAllOnce()
        allModels.firstOrNull { it.path == path.absolutePath }?.let { existing ->
            val updated = existing.copy(
                displayName = displayName.ifBlank { existing.displayName },
                sourceUri = sourceUri ?: existing.sourceUri,
                repoId = repoId ?: existing.repoId,
                filename = path.name,
                sizeBytes = size,
                backendPreference = backendPreference,
                supportsCpu = supportsCpu,
                supportsGpu = supportsGpu,
                supportsNpu = supportsNpu,
                supportsVision = supportsVision,
                supportsAudio = supportsAudio,
                supportsEmbedding = normalizedSupportsEmbedding,
                kbEmbeddingRunnable = compatibility.runnable,
                kbEmbeddingRuntime = compatibility.runtime,
                kbEmbeddingStatus = compatibility.status,
                maxContextTokens = maxContextTokens ?: existing.maxContextTokens,
                updatedAt = now
            )
            modelDao.update(updated)
            return updated
        }
        findEquivalentExistingModel(
            models = allModels,
            path = path,
            repoId = repoId,
            supportsEmbedding = supportsEmbedding
        )?.let { existing ->
            if (existing.path != path.absolutePath) {
                val oldFile = File(existing.path)
                if (oldFile.exists()) {
                    if (oldFile.isDirectory) oldFile.deleteRecursively() else oldFile.delete()
                }
            }
            val updated = existing.copy(
                displayName = displayName.ifBlank { existing.displayName },
                path = path.absolutePath,
                sourceUri = sourceUri ?: existing.sourceUri,
                repoId = repoId ?: existing.repoId,
                filename = path.name,
                sizeBytes = size,
                backendPreference = backendPreference,
                supportsCpu = supportsCpu,
                supportsGpu = supportsGpu,
                supportsNpu = supportsNpu,
                supportsVision = supportsVision,
                supportsAudio = supportsAudio,
                supportsEmbedding = normalizedSupportsEmbedding,
                kbEmbeddingRunnable = compatibility.runnable,
                kbEmbeddingRuntime = compatibility.runtime,
                kbEmbeddingStatus = compatibility.status,
                maxContextTokens = maxContextTokens ?: existing.maxContextTokens,
                updatedAt = now
            )
            modelDao.update(updated)
            return updated
        }
        val record = LiteRtModelEntity(
            displayName = displayName,
            path = path.absolutePath,
            sourceUri = sourceUri,
            repoId = repoId,
            filename = path.name,
            sizeBytes = size,
            backendPreference = backendPreference,
            supportsCpu = supportsCpu,
            supportsGpu = supportsGpu,
            supportsNpu = supportsNpu,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            supportsEmbedding = normalizedSupportsEmbedding,
            kbEmbeddingRunnable = compatibility.runnable,
            kbEmbeddingRuntime = compatibility.runtime,
            kbEmbeddingStatus = compatibility.status,
            maxContextTokens = maxContextTokens,
            createdAt = now,
            updatedAt = now
        )
        val id = modelDao.insert(record)
        return record.copy(id = id)
    }

    private fun findEquivalentExistingModel(
        models: List<LiteRtModelEntity>,
        path: File,
        repoId: String?,
        supportsEmbedding: Boolean
    ): LiteRtModelEntity? {
        val normalizedRepo = repoId?.trim()?.lowercase(Locale.US)
        val canonicalFilename = path.name.canonicalLiteRtFilename()
        return models.firstOrNull { existing ->
            when {
                existing.path == path.absolutePath -> true
                normalizedRepo != null &&
                    supportsEmbedding &&
                    existing.repoId?.trim()?.lowercase(Locale.US) == normalizedRepo &&
                    existing.isEmbeddingFamilyModel() -> true
                normalizedRepo != null && existing.repoId?.trim()?.lowercase(Locale.US) == normalizedRepo &&
                    existing.filename.canonicalLiteRtFilename() == canonicalFilename -> true
                else -> false
            }
        }
    }

    private fun downloadToFile(url: String, target: File, progressKey: String, label: String) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = when (response.code) {
                    401, 403 -> context.getString(R.string.litert_hf_token_required_error, response.code)
                    else -> "Download failed: HTTP ${response.code}"
                }
                error(detail)
            }
            val body = response.body ?: error("Empty response body")
            val total = body.contentLength().coerceAtLeast(0L)
            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0L) {
                            DownloadProgressHolder.updateProgress(progressKey, label, read.toFloat() / total.toFloat())
                        }
                    }
                }
            }
            DownloadProgressHolder.updateProgress(progressKey, label, 1f)
        }
    }

    private suspend fun resolveCatalogPackage(entry: LiteRtCatalogEntry): com.example.llamadroid.data.api.HfTreeItemDto {
        entry.directDownloadUrl?.let { url ->
            return com.example.llamadroid.data.api.HfTreeItemDto(
                type = "file",
                path = entry.preferredFileName ?: url.substringAfterLast('/'),
                size = entry.sizeBytes ?: 0L
            )
        }
        val files = try {
            hfService.getRepoTree(entry.repoId, recursive = true)
        } catch (e: HttpException) {
            throw IllegalStateException(huggingFaceHttpError(e.code()), e)
        }
            .filter { it.type == "file" }
            .filter { item ->
                val lower = item.path.lowercase(Locale.US)
                lower.endsWith(".litertlm") ||
                    lower.endsWith(".zip") ||
                    lower.endsWith(".tflite") ||
                    lower.endsWith(".task")
            }
            .sortedWith(compareBy<com.example.llamadroid.data.api.HfTreeItemDto> {
                packagePreferenceRank(it.path, preferEmbeddingRuntime = entry.supportsEmbedding)
            }.thenByDescending { it.size })
        return entry.preferredFileName
            ?.let { preferred -> files.firstOrNull { it.path == preferred } }
            ?: files.firstOrNull()
            ?: error("No LiteRT package found in ${entry.repoId}")
    }

    private fun resolveInstalledPath(extractedDir: File): File =
        extractedDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("litertlm", true) }
            ?: extractedDir.walkTopDown().firstOrNull { it.isFile && isLiteRtEmbeddingFile(it) }
            ?: extractedDir

    private suspend fun installDownloadedPackage(
        downloadedFile: File,
        repoId: String?,
        supportsEmbedding: Boolean
    ): File {
        if (downloadedFile.extension.equals("zip", ignoreCase = true)) {
            val extracted = uniqueManagedDirectory(downloadedFile.nameWithoutExtension)
            extractZip(downloadedFile, extracted)
            downloadedFile.delete()
            return if (supportsEmbedding && extracted.walkTopDown().any { it.isFile && isLiteRtTokenizerSidecar(it) }) {
                extracted
            } else {
                resolveInstalledPath(extracted)
            }
        }

        if (!supportsEmbedding || repoId.isNullOrBlank() || !isLiteRtEmbeddingFile(downloadedFile)) {
            return downloadedFile
        }

        val sidecars = resolveEmbeddingSidecars(repoId, downloadedFile.name)
        if (sidecars.isEmpty()) return downloadedFile

        val packageDir = uniqueManagedDirectory(downloadedFile.nameWithoutExtension)
        packageDir.mkdirs()
        val movedModel = File(packageDir, downloadedFile.name)
        moveFile(downloadedFile, movedModel)
        sidecars.forEach { sidecar ->
            val target = File(packageDir, File(sidecar.path).name)
            downloadSidecarToFile(
                url = "https://huggingface.co/$repoId/resolve/main/${sidecar.path}",
                target = target
            )
        }
        return packageDir
    }

    private suspend fun resolveEmbeddingSidecars(
        repoId: String,
        modelFilename: String
    ): List<com.example.llamadroid.data.api.HfTreeItemDto> =
        runCatching {
            hfService.getRepoTree(repoId, recursive = true)
                .filter { it.type == "file" }
                .filter { item -> isLiteRtTokenizerSidecar(File(item.path)) }
                .sortedWith(compareBy<com.example.llamadroid.data.api.HfTreeItemDto> { sidecarPreferenceRank(it.path, modelFilename) }
                    .thenBy { it.path.lowercase(Locale.US) })
                .take(MAX_EMBEDDING_SIDECARS)
        }.getOrElse { error ->
            DebugLog.log("LiteRtModelRepository: could not resolve embedding sidecars for $repoId: ${error.message}")
            emptyList()
        }

    private fun downloadSidecarToFile(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                DebugLog.log("LiteRtModelRepository: sidecar download failed for ${target.name}: HTTP ${response.code}")
                return
            }
            val body = response.body ?: return
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        source.delete()
    }

    private fun extractZip(zipFile: File, destination: File) {
        destination.mkdirs()
        val canonicalDestination = destination.canonicalFile
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(destination, entry.name).canonicalFile
                if (!out.path.startsWith(canonicalDestination.path)) {
                    error("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun zipDirectory(root: File, current: File, zip: ZipOutputStream) {
        current.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                zipDirectory(root, child, zip)
            } else {
                val entryName = child.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(entryName))
                child.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun uniqueManagedFile(filename: String): File {
        val root = managedRoot()
        return uniqueFile(root, filename)
    }

    private fun uniqueManagedDirectory(name: String): File {
        val root = managedRoot()
        val clean = safeFileName(name).substringBeforeLast('.', safeFileName(name))
        var candidate = File(root, clean.ifBlank { "litert_embedding_package" })
        var index = 1
        while (candidate.exists()) {
            candidate = File(root, "${clean.ifBlank { "litert_embedding_package" }}-$index")
            index += 1
        }
        return candidate
    }

    private suspend fun pruneManagedRowsIfNeeded() {
        withContext(Dispatchers.IO) {
            val root = managedRoot()
            val allModels = modelDao.getAllOnce()
            val survivors = linkedMapOf<String, LiteRtModelEntity>()
            allModels.forEach { model ->
                val modelFile = File(model.path)
                val insideRoot = isWithinRoot(modelFile, root)
                val exists = modelFile.exists()
                if (!insideRoot || !exists) {
                    modelDao.deleteById(model.id)
                    DebugLog.log(
                        "LiteRtModelRepository: removed stale LiteRT row for ${model.displayName}; insideRoot=$insideRoot exists=$exists"
                    )
                } else {
                    val key = model.identityKey()
                    val current = survivors[key]
                    if (current == null) {
                        survivors[key] = model
                    } else {
                        val keep = if (model.updatedAt >= current.updatedAt) model else current
                        val remove = if (keep.id == model.id) current else model
                        if (remove.id != keep.id) {
                            modelDao.deleteById(remove.id)
                            DebugLog.log(
                                "LiteRtModelRepository: removed duplicate LiteRT row for ${remove.displayName}; kept ${keep.displayName}"
                            )
                            val removeFile = File(remove.path)
                            if (removeFile.exists() && removeFile.absolutePath != keep.path) {
                                if (removeFile.isDirectory) removeFile.deleteRecursively() else removeFile.delete()
                            }
                            survivors[key] = keep
                        }
                    }
                }
            }
        }
    }

    private fun uniqueFile(dir: File, filename: String): File {
        val clean = safeFileName(filename)
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "")
        var candidate = File(dir, clean)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "-$index" else "-$index.$ext"
            candidate = File(dir, base + suffix)
            index += 1
        }
        return candidate
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "litert_model.litertlm" }

    private fun sanitizeVisibleModels(models: List<LiteRtModelEntity>): List<LiteRtModelEntity> {
        val root = managedRoot()
        return models
            .filter { model ->
                val file = File(model.path)
                file.exists() && isWithinRoot(file, root)
            }
            .groupBy { it.identityKey() }
            .values
            .map { group -> group.maxByOrNull { it.updatedAt } ?: group.first() }
            .sortedWith(compareByDescending<LiteRtModelEntity> { it.updatedAt }.thenBy { it.displayName.lowercase(Locale.US) })
    }

    private fun inferGpuSupport(path: File): Boolean {
        val lower = path.name.lowercase(Locale.US)
        return !lower.contains(".qualcomm.") &&
            !lower.contains("_qualcomm_") &&
            !lower.contains(".mediatek.") &&
            !lower.contains("_mediatek_")
    }

    private fun inferCpuSupport(path: File): Boolean =
        inferGpuSupport(path)

    private fun inferLiteRtMaxContextTokens(displayName: String, file: File, repoId: String?): Int? =
        liteRtEngineMaxTokensFromText(listOf(displayName, file.name, repoId.orEmpty()).joinToString(" "))

    private fun inferLiteRtVisionSupport(displayName: String, file: File, repoId: String?): Boolean =
        liteRtVisionSupportFromText(listOf(displayName, file.name, repoId.orEmpty()).joinToString(" "))

    private fun inferLiteRtAudioSupport(displayName: String, file: File, repoId: String?): Boolean =
        liteRtAudioSupportFromText(listOf(displayName, file.name, repoId.orEmpty()).joinToString(" "))

    private fun inferLiteRtEmbeddingSupport(file: File): Boolean =
        containsLiteRtEmbeddingModelFile(file) &&
            liteRtEmbeddingSupportFromText(
                file.walkForLiteRtPackageText()
            )

    private fun containsLiteRtEmbeddingModelFile(file: File): Boolean =
        when {
            file.isDirectory -> file.walkTopDown().any { child -> child.isFile && isLiteRtEmbeddingFile(child) }
            else -> isLiteRtEmbeddingFile(file)
        }

    private fun isLiteRtEmbeddingFile(file: File): Boolean {
        val lower = file.name.lowercase(Locale.US)
        return lower.endsWith(".tflite") ||
            lower.endsWith(".task") ||
            lower.endsWith(".lite") ||
            lower.endsWith(".litert")
    }

    private fun isLiteRtTokenizerSidecar(file: File): Boolean {
        val lower = file.name.lowercase(Locale.US)
        return lower == "tokenizer.model" ||
            lower == "vocab.txt" ||
            lower == "tokenizer.json" ||
            lower == "tokenizer_config.json" ||
            lower == "special_tokens_map.json" ||
            lower.endsWith(".spm") ||
            lower.endsWith(".sentencepiece") ||
            lower.endsWith(".bpe.model")
    }

    private fun sidecarPreferenceRank(path: String, modelFilename: String): Int {
        val lower = path.lowercase(Locale.US)
        val modelLower = modelFilename.lowercase(Locale.US)
        return when {
            lower.endsWith("tokenizer.model") && ("gemma" in modelLower || "sentencepiece" in modelLower) -> 0
            lower.endsWith("vocab.txt") && ("bert" in modelLower || "wordpiece" in modelLower) -> 1
            lower.endsWith("tokenizer.json") -> 2
            lower.endsWith("tokenizer.model") -> 3
            lower.endsWith("vocab.txt") -> 4
            else -> 9
        }
    }

    private fun exactRepoIdFromQuery(query: String): String? =
        query.trim().takeIf { it.count { ch -> ch == '/' } == 1 && !it.contains(' ') }

    private fun layeredSearchTerms(query: String): List<String> {
        val trimmed = query.trim()
        val terms = linkedSetOf<String>()
        terms += trimmed
        if (!trimmed.contains("litert", ignoreCase = true)) {
            terms += "$trimmed litert"
        }
        if (!trimmed.contains("litertlm", ignoreCase = true)) {
            terms += "$trimmed litertlm"
        }
        return terms.toList()
    }

    private fun huggingFaceHttpError(code: Int): String =
        if (code == 401 || code == 403) {
            if (huggingFaceToken().isBlank()) {
                context.getString(R.string.litert_hf_token_required_error, code)
            } else {
                context.getString(R.string.litert_hf_access_denied_error, code)
            }
        } else {
            "Hugging Face request failed: HTTP $code"
        }

    private fun packagePreferenceRank(path: String, preferEmbeddingRuntime: Boolean): Int {
        val lower = path.lowercase(Locale.US)
        val isEmbeddingNamed = liteRtEmbeddingSupportFromText(lower)
        val isDeviceSpecific = isDeviceSpecificLiteRtPackage(lower)
        return when {
            preferEmbeddingRuntime && lower.endsWith(".task") && isEmbeddingNamed && !isDeviceSpecific -> 0
            preferEmbeddingRuntime && lower.endsWith(".tflite") && isEmbeddingNamed && !isDeviceSpecific -> 1
            preferEmbeddingRuntime && lower.endsWith(".task") && !isDeviceSpecific -> 2
            preferEmbeddingRuntime && lower.endsWith(".tflite") && !isDeviceSpecific -> 3
            preferEmbeddingRuntime && lower.endsWith(".task") && isEmbeddingNamed -> 4
            preferEmbeddingRuntime && lower.endsWith(".tflite") && isEmbeddingNamed -> 5
            preferEmbeddingRuntime && lower.endsWith(".task") -> 6
            preferEmbeddingRuntime && lower.endsWith(".tflite") -> 7
            !preferEmbeddingRuntime && lower.endsWith(".litertlm") -> 0
            !preferEmbeddingRuntime && lower.endsWith(".zip") -> 1
            !preferEmbeddingRuntime && lower.endsWith(".task") -> 2
            !preferEmbeddingRuntime && lower.endsWith(".tflite") -> 3
            lower.endsWith(".litertlm") -> 8
            lower.endsWith(".zip") -> 9
            lower.endsWith(".task") -> 10
            lower.endsWith(".tflite") -> 11
            else -> 99
        }
    }

    private fun isDeviceSpecificLiteRtPackage(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return "qualcomm." in lower ||
            "_qualcomm_" in lower ||
            "mediatek." in lower ||
            "_mediatek_" in lower ||
            "google.tensor" in lower ||
            "tensor_g" in lower ||
            liteRtPackageTargetFromText(lower) != null
    }

    private fun supportsUsableEmbeddingAsset(model: LiteRtModelEntity): Boolean =
        model.supportsEmbedding && model.kbEmbeddingRunnable && File(model.path).exists()

    private fun supportsUsableEmbeddingAsset(file: File, repoId: String? = null): Boolean =
        evaluateKbEmbeddingCompatibility(file, repoId, supportsEmbedding = true).runnable

    private fun evaluateKbEmbeddingCompatibility(
        file: File,
        repoId: String?,
        supportsEmbedding: Boolean
    ): LiteRtKbEmbeddingCompatibility {
        val packageText = listOf(file.walkForLiteRtPackageText(), repoId.orEmpty()).joinToString(" ")
        val embeddingLike = supportsEmbedding ||
            (containsLiteRtEmbeddingModelFile(file) && liteRtEmbeddingSupportFromText(packageText))
        if (!embeddingLike) {
            return LiteRtKbEmbeddingCompatibility(
                embeddingLike = false,
                runnable = false,
                runtime = null,
                status = null
            )
        }
        val runtime = liteRtKbEmbeddingRuntimeFromText(packageText)
            ?: if (hasStringInputEmbeddingPackage(file)) LITERT_KB_EMBED_RUNTIME_STRING_TFLITE else null
        return when (runtime) {
            LITERT_KB_EMBED_RUNTIME_STRING_TFLITE -> LiteRtKbEmbeddingCompatibility(
                embeddingLike = true,
                runnable = true,
                runtime = runtime,
                status = "ready"
            )
            LITERT_KB_EMBED_RUNTIME_BERT_WORDPIECE -> {
                val hasTokenizer = file.findLiteRtEmbeddingFile() != null && file.findSidecar("vocab.txt") != null
                LiteRtKbEmbeddingCompatibility(
                    embeddingLike = true,
                    runnable = hasTokenizer,
                    runtime = runtime,
                    status = if (hasTokenizer) "ready" else "missing_wordpiece_tokenizer"
                )
            }
            LITERT_KB_EMBED_RUNTIME_EMBEDDING_GEMMA -> {
                val hasTokenizer = file.findLiteRtEmbeddingFile() != null && file.findSidecar("tokenizer.model") != null
                LiteRtKbEmbeddingCompatibility(
                    embeddingLike = true,
                    runnable = false,
                    runtime = runtime,
                    status = if (hasTokenizer) "sentencepiece_runtime_pending" else "missing_sentencepiece_tokenizer"
                )
            }
            else -> LiteRtKbEmbeddingCompatibility(
                embeddingLike = true,
                runnable = false,
                runtime = null,
                status = "unsupported_tensor_contract"
            )
        }
    }

    private fun hasStringInputEmbeddingPackage(file: File): Boolean {
        val lower = file.walkForLiteRtPackageText().lowercase(Locale.US)
        return lower.endsWith(".task") ||
            "textembedder" in lower ||
            "text-embedder" in lower ||
            "_embedder" in lower ||
            " text embedder" in lower ||
            "string-input" in lower
    }

    private fun File.walkForLiteRtPackageText(): String =
        if (isDirectory) {
            walkTopDown()
                .filter { it.isFile }
                .take(24)
                .joinToString(" ") { it.name }
        } else {
            name
        }

    private fun File.findLiteRtEmbeddingFile(): File? =
        when {
            isDirectory -> walkTopDown().firstOrNull { it.isFile && isLiteRtEmbeddingFile(it) }
            isFile && isLiteRtEmbeddingFile(this) -> this
            else -> null
        }

    private fun File.findSidecar(name: String): File? =
        if (isDirectory) {
            walkTopDown().firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
        } else {
            parentFile?.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
        }

    private fun isWithinRoot(file: File, root: File): Boolean {
        val filePath = file.canonicalFile.absolutePath
        val rootPath = root.canonicalFile.absolutePath
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }

    private companion object {
        const val PREFS_NAME = "litert_model_repository"
        const val KEY_HF_TOKEN = "hugging_face_token"
        const val MAX_EMBEDDING_SIDECARS = 6
    }
}

private fun seededLiteRtReposForQuery(query: String): List<String> {
    val lower = query.lowercase(Locale.US)
    return buildList {
        if ("embeddinggemma" in lower || ("embedding" in lower && "gemma" in lower)) {
            add("kontextdev/embeddinggemma-300m-litertlm")
        }
    }
}

private fun liveResultComparator(exactRepoId: String?): Comparator<LiteRtCatalogEntry> =
    compareBy<LiteRtCatalogEntry> { entry ->
        when {
            exactRepoId != null && entry.repoId.equals(exactRepoId, ignoreCase = true) -> 0
            !entry.gated -> 1
            else -> 2
        }
    }.thenBy { entry -> entry.title.lowercase(Locale.US) }

private fun LiteRtModelCatalog.findByFilename(filename: String): LiteRtCatalogEntry? {
    val canonical = filename.canonicalLiteRtFilename()
    return allEntries.firstOrNull { entry ->
        entry.preferredFileName?.canonicalLiteRtFilename() == canonical
    }
}

private fun isLikelyGatedLiteRtRepo(repoId: String): Boolean {
    val lower = repoId.trim().lowercase(Locale.US)
    return lower == "litert-community/embeddinggemma-300m" ||
        lower == "google/embeddinggemma-300m"
}

private fun LiteRtModelEntity.identityKey(): String {
    val normalizedRepo = repoId?.trim()?.lowercase(Locale.US)
    return when {
        !normalizedRepo.isNullOrBlank() && isEmbeddingFamilyModel() ->
            "embedding|$normalizedRepo"
        !normalizedRepo.isNullOrBlank() ->
            "$normalizedRepo|${filename.canonicalLiteRtFilename()}"
        else ->
            "path|${path.lowercase(Locale.US)}"
    }
}

private fun LiteRtModelEntity.isEmbeddingFamilyModel(): Boolean =
    supportsEmbedding || liteRtEmbeddingSupportFromText(listOf(displayName, filename, repoId.orEmpty()).joinToString(" "))

private fun File.isLiteRtManagedRuntimeFile(): Boolean {
    val lower = name.lowercase(Locale.US)
    return lower.endsWith(".litertlm") ||
        lower.endsWith(".tflite") ||
        lower.endsWith(".task") ||
        lower.endsWith(".lite") ||
        lower.endsWith(".litert")
}

private fun String.canonicalLiteRtFilename(): String =
    replace(Regex("""-\d+(\.(?:litertlm|zip|tflite|task|lite|litert))$""", RegexOption.IGNORE_CASE), "\$1")
        .lowercase(Locale.US)
