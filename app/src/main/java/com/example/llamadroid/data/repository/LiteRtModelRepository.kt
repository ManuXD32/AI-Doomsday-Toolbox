package com.example.llamadroid.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.dao.LiteRtModelDao
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.util.DebugLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    val defaultBackend: String = LITERT_BACKEND_AUTO,
    val supportsCpu: Boolean = true,
    val supportsGpu: Boolean = true,
    val supportsNpu: Boolean = false,
    val category: LiteRtCatalogCategory = LiteRtCatalogCategory.GPU,
    val catalogId: String = "${category.name.lowercase(Locale.US)}|$repoId|${preferredFileName ?: "default"}"
)

enum class LiteRtCatalogCategory {
    GPU,
    CPU
}

object LiteRtModelCatalog {
    val defaultEntries = gpuEntries() + cpuEntries()

    fun entriesFor(category: LiteRtCatalogCategory): List<LiteRtCatalogEntry> =
        defaultEntries.filter { it.category == category }

    private fun gpuEntries() = listOf(
        gpu(
            repoId = "litert-community/Qwen3-0.6B",
            title = "Qwen3 0.6B",
            description = "Small Apache-2.0 chat model with a fast CPU/GPU LiteRT-LM package.",
            preferredFileName = "Qwen3-0.6B.litertlm"
        ),
        gpu(
            repoId = "litert-community/Qwen3-4B",
            title = "Qwen3 4B",
            description = "Modern Qwen3 instruct model packaged as channelwise int8 LiteRT-LM.",
            preferredFileName = "qwen3_4b_channelwise_int8_float32kv.litertlm"
        ),
        gpu(
            repoId = "litert-community/Qwen3-8B",
            title = "Qwen3 8B",
            description = "Larger Qwen3 LiteRT-LM option for higher quality on high-memory devices.",
            preferredFileName = "qwen3_8b_channelwise_int8_float32kv.litertlm"
        ),
        gpu(
            repoId = "litert-community/Qwen3-14B",
            title = "Qwen3 14B",
            description = "Large modern Qwen3 LiteRT-LM package for high-memory devices.",
            preferredFileName = "qwen3_14b_channelwise_int8_float32kv.litertlm"
        ),
        gpu(
            repoId = "litert-community/Gemma3-1B-IT",
            title = "Gemma 3 1B IT",
            description = "Compact instruction model with published LiteRT Android variants.",
            preferredFileName = "gemma3-1b-it-int4.litertlm"
        ),
        gpu(
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            title = "Gemma 4 E2B IT LiteRT-LM",
            description = "Closest LiteRT-LM path to the current Gemma 4 chat workflow.",
            preferredFileName = "gemma-4-E2B-it.litertlm"
        ),
        gpu(
            repoId = "litert-community/gemma-4-E4B-it-litert-lm",
            title = "Gemma 4 E4B IT LiteRT-LM",
            description = "Larger Gemma 4 LiteRT-LM package for quality-first testing.",
            preferredFileName = "gemma-4-E4B-it.litertlm"
        ),
        gpu(
            repoId = "google/gemma-3n-E2B-it-litert-lm",
            title = "Gemma 3n E2B IT LiteRT-LM",
            description = "Google Gemma 3n LiteRT-LM package with efficient on-device chat variants.",
            preferredFileName = "gemma-3n-E2B-it-int4.litertlm"
        ),
        gpu(
            repoId = "google/gemma-3n-E4B-it-litert-lm",
            title = "Gemma 3n E4B IT LiteRT-LM",
            description = "Larger Gemma 3n LiteRT-LM package for capable phones and tablets.",
            preferredFileName = "gemma-3n-E4B-it-int4.litertlm"
        ),
        gpu(
            repoId = "litert-community/Qwen2.5-1.5B-Instruct",
            title = "Qwen2.5 1.5B Instruct",
            description = "Reliable compact Qwen instruct model with LiteRT-LM packaging.",
            preferredFileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
        ),
        gpu(
            repoId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            title = "DeepSeek R1 Distill Qwen 1.5B",
            description = "Reasoning-focused distilled Qwen model in LiteRT-LM format.",
            preferredFileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"
        ),
        gpu(
            repoId = "litert-community/Phi-4-mini-instruct",
            title = "Phi-4 Mini Instruct",
            description = "Microsoft Phi-family compact instruct model packaged for LiteRT-LM.",
            preferredFileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"
        ),
        gpu(
            repoId = "litert-community/SmolLM2-360M-Instruct",
            title = "SmolLM2 360M Instruct",
            description = "Tiny fast chat model for quick smoke tests and low-memory devices.",
            preferredFileName = "SmolLM2_360M_instruct.litertlm"
        )
    )

    private fun cpuEntries() = listOf(
        cpu("litert-community/Qwen3-0.6B", "Qwen3 0.6B CPU", "Qwen3-0.6B.litertlm"),
        cpu("litert-community/Gemma3-1B-IT", "Gemma 3 1B IT CPU", "gemma3-1b-it-int4.litertlm"),
        cpu("litert-community/gemma-4-E2B-it-litert-lm", "Gemma 4 E2B IT CPU", "gemma-4-E2B-it.litertlm"),
        cpu("litert-community/gemma-4-E4B-it-litert-lm", "Gemma 4 E4B IT CPU", "gemma-4-E4B-it.litertlm"),
        cpu("google/gemma-3n-E2B-it-litert-lm", "Gemma 3n E2B IT CPU", "gemma-3n-E2B-it-int4.litertlm"),
        cpu("litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5 1.5B CPU", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"),
        cpu("litert-community/DeepSeek-R1-Distill-Qwen-1.5B", "DeepSeek R1 Distill Qwen CPU", "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"),
        cpu("litert-community/Phi-4-mini-instruct", "Phi-4 Mini CPU", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"),
        cpu("litert-community/SmolLM2-360M-Instruct", "SmolLM2 360M CPU", "SmolLM2_360M_instruct.litertlm"),
        cpu("litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm", "FunctionGemma Mobile Actions CPU", "mobile-actions_q8_ekv1024.litertlm")
    )

    private fun gpu(
        repoId: String,
        title: String,
        description: String,
        preferredFileName: String
    ) = LiteRtCatalogEntry(
        repoId = repoId,
        title = title,
        description = description,
        preferredFileName = preferredFileName,
        category = LiteRtCatalogCategory.GPU
    )

    private fun cpu(
        repoId: String,
        title: String,
        preferredFileName: String
    ) = LiteRtCatalogEntry(
        repoId = repoId,
        title = title,
        description = "Conservative LiteRT-LM package profile that skips GPU attempts.",
        preferredFileName = preferredFileName,
        defaultBackend = LITERT_BACKEND_CPU,
        supportsGpu = false,
        supportsNpu = false,
        category = LiteRtCatalogCategory.CPU
    )
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

    fun observeModels(): Flow<List<LiteRtModelEntity>> = modelDao.observeAll()

    fun huggingFaceToken(): String = preferences.getString(KEY_HF_TOKEN, "").orEmpty()

    fun saveHuggingFaceToken(token: String) {
        preferences.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    fun managedRoot(): File {
        val publicRoot = File(Environment.getExternalStorageDirectory(), "miapp/modelsprov/LiteRT/LLM")
        if (publicRoot.exists() || publicRoot.mkdirs()) return publicRoot
        return File(context.getExternalFilesDir(null), "models/LiteRT/LLM").apply { mkdirs() }
    }

    suspend fun startCatalogDownload(entry: LiteRtCatalogEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val selected = resolveCatalogPackage(entry)
            val target = uniqueFile(managedRoot(), File(selected.path).name)
            val progressKey = "litert:${entry.catalogId}"
            val sourceUri = "https://huggingface.co/${entry.repoId}"
            val downloadUrl = "$sourceUri/resolve/main/${selected.path}"

            DownloadProgressHolder.updateProgress(progressKey, target.name, 0f)
            DownloadProgressHolder.updateStatus(progressKey, entry.title)
            PendingDownloadHolder.addPending(
                filename = target.name,
                repoId = entry.repoId,
                progressKey = progressKey,
                type = ModelType.LLM,
                destPath = target.absolutePath,
                liteRtDisplayName = entry.title,
                liteRtSourceUri = sourceUri,
                liteRtBackendPreference = normalizeLiteRtBackend(entry.defaultBackend),
                liteRtSupportsCpu = entry.supportsCpu,
                liteRtSupportsGpu = entry.supportsGpu
            )
            DownloadService.startDownload(
                context = context,
                url = downloadUrl,
                destPath = target.absolutePath,
                filename = target.name
            )
        }.onFailure {
            DownloadProgressHolder.removeProgress("litert:${entry.catalogId}")
            DebugLog.log("LiteRtModelRepository: failed to start download for ${entry.repoId}: ${it.message}")
        }
    }

    suspend fun downloadCatalog(entry: LiteRtCatalogEntry): Result<LiteRtModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val selected = resolveCatalogPackage(entry)

            val filename = File(selected.path).name
            val target = uniqueFile(managedRoot(), filename)
            val progressKey = "litert:${entry.catalogId}"
            DownloadProgressHolder.updateProgress(progressKey, filename, 0f)
            downloadToFile(
                url = "https://huggingface.co/${entry.repoId}/resolve/main/${selected.path}",
                target = target,
                progressKey = progressKey,
                label = filename
            )
            DownloadProgressHolder.removeProgress(progressKey)

            val installedPath = if (target.extension.equals("zip", ignoreCase = true)) {
                val extracted = File(target.parentFile, target.nameWithoutExtension)
                extractZip(target, extracted)
                target.delete()
                extracted.walkTopDown().firstOrNull { it.isFile && it.extension.equals("litertlm", true) }
                    ?: extracted
            } else {
                target
            }
            saveModelRecord(
                displayName = entry.title,
                path = installedPath,
                sourceUri = "https://huggingface.co/${entry.repoId}",
                repoId = entry.repoId,
                backendPreference = normalizeLiteRtBackend(entry.defaultBackend),
                supportsCpu = entry.supportsCpu,
                supportsGpu = entry.supportsGpu,
                supportsNpu = entry.supportsNpu
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
        val installedPath = if (downloadedFile.extension.equals("zip", ignoreCase = true)) {
            val extracted = File(downloadedFile.parentFile, downloadedFile.nameWithoutExtension)
            extractZip(downloadedFile, extracted)
            downloadedFile.delete()
            extracted.walkTopDown().firstOrNull { it.isFile && it.extension.equals("litertlm", true) }
                ?: extracted
        } else {
            downloadedFile
        }
        onProgress(0.98f, displayName)
        saveModelRecord(
            displayName = displayName,
            path = installedPath,
            sourceUri = pending.liteRtSourceUri,
            repoId = pending.repoId,
            backendPreference = normalizeLiteRtBackend(pending.liteRtBackendPreference ?: LITERT_BACKEND_AUTO),
            supportsCpu = pending.liteRtSupportsCpu ?: inferCpuSupport(installedPath),
            supportsGpu = pending.liteRtSupportsGpu ?: inferGpuSupport(installedPath),
            supportsNpu = false
        ).also {
            onProgress(1f, displayName)
        }
    }

    suspend fun reconcileManagedModels(): Int = withContext(Dispatchers.IO) {
        val root = managedRoot()
        if (!root.exists()) return@withContext 0
        val candidates = root
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("litertlm", ignoreCase = true) }
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
                    supportsNpu = false
                )
                inserted += 1
            }
        }
        if (inserted > 0) {
            DebugLog.log("LiteRtModelRepository: reconciled $inserted LiteRT model file(s) from ${root.absolutePath}")
        }
        inserted
    }

    suspend fun importFromUri(uri: Uri): Result<LiteRtModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val document = DocumentFile.fromSingleUri(context, uri)
            val sourceName = document?.name?.takeIf { it.isNotBlank() } ?: "imported_litert_model.litertlm"
            val root = managedRoot()
            val target = uniqueFile(root, safeFileName(sourceName))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: error("Unable to open selected file")

            val installedPath = if (target.extension.equals("zip", ignoreCase = true)) {
                val extracted = File(target.parentFile, target.nameWithoutExtension)
                extractZip(target, extracted)
                target.delete()
                extracted.walkTopDown().firstOrNull { it.isFile && it.extension.equals("litertlm", true) }
                    ?: extracted
            } else {
                target
            }
            saveModelRecord(
                displayName = installedPath.nameWithoutExtension.ifBlank { sourceName },
                path = installedPath,
                sourceUri = uri.toString(),
                repoId = null,
                backendPreference = LITERT_BACKEND_AUTO,
                supportsCpu = inferCpuSupport(installedPath),
                supportsGpu = inferGpuSupport(installedPath),
                supportsNpu = false
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
        supportsNpu: Boolean
    ): LiteRtModelEntity {
        val now = System.currentTimeMillis()
        val size = if (path.isDirectory) path.walkTopDown().filter { it.isFile }.sumOf { it.length() } else path.length()
        modelDao.getByPath(path.absolutePath)?.let { existing ->
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
            createdAt = now,
            updatedAt = now
        )
        val id = modelDao.insert(record)
        return record.copy(id = id)
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
        val files = try {
            hfService.getRepoTree(entry.repoId, recursive = true)
        } catch (e: HttpException) {
            throw IllegalStateException(huggingFaceHttpError(e.code()), e)
        }
            .filter { it.type == "file" }
            .filter { item ->
                val lower = item.path.lowercase(Locale.US)
                lower.endsWith(".litertlm") || lower.endsWith(".zip")
            }
            .sortedWith(compareByDescending<com.example.llamadroid.data.api.HfTreeItemDto> {
                it.path.lowercase(Locale.US).endsWith(".litertlm")
            }.thenByDescending { it.size })
        return entry.preferredFileName
            ?.let { preferred -> files.firstOrNull { it.path == preferred } }
            ?: files.firstOrNull()
            ?: error("No .litertlm or zip package found in ${entry.repoId}")
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

    private fun inferGpuSupport(path: File): Boolean {
        val lower = path.name.lowercase(Locale.US)
        return !lower.contains(".qualcomm.") &&
            !lower.contains("_qualcomm_") &&
            !lower.contains(".mediatek.") &&
            !lower.contains("_mediatek_")
    }

    private fun inferCpuSupport(path: File): Boolean =
        inferGpuSupport(path)

    private fun huggingFaceHttpError(code: Int): String =
        if (code == 401 || code == 403) {
            context.getString(R.string.litert_hf_token_required_error, code)
        } else {
            "Hugging Face request failed: HTTP $code"
        }

    private companion object {
        const val PREFS_NAME = "litert_model_repository"
        const val KEY_HF_TOKEN = "hugging_face_token"
    }
}

private fun LiteRtModelCatalog.findByFilename(filename: String): LiteRtCatalogEntry? {
    val canonical = filename.canonicalLiteRtFilename()
    return defaultEntries.firstOrNull { entry ->
        entry.preferredFileName?.canonicalLiteRtFilename() == canonical
    }
}

private fun String.canonicalLiteRtFilename(): String =
    replace(Regex("""-\d+(\.litertlm|\.zip)$""", RegexOption.IGNORE_CASE), "\$1")
        .lowercase(Locale.US)
