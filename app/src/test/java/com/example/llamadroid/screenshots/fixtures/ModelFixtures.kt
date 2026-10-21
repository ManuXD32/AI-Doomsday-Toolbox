package com.example.llamadroid.screenshots.fixtures

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdArtifactFormat
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdInspectionCache
import com.example.llamadroid.sd.SdInspectionConfidence
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.withSdArtifactInspection
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile

/** Sparse placeholder model files plus their `models` rows (no real weights are stored). */
object ModelFixtures {
    const val MIB = 1024L * 1024L
    const val GIB = 1024L * MIB

    /** Qwen3 4B, the model of the demo running server. */
    const val QWEN_FILE = "Qwen3-4B-Q4_K_M.gguf"

    data class DemoModel(
        val filename: String,
        val type: ModelType,
        val repo: String,
        val bytes: Long,
        val vision: Boolean = false,
        val layers: Int = 0,
        val sdFamily: String? = null,
        val sdCaps: String? = null,
        /** Persisted artifact layout ("full_model"); without it the image screen blocks Generate. */
        val sdLayout: String? = null
    )

    val llms = listOf(
        DemoModel(QWEN_FILE, ModelType.LLM, "unsloth/Qwen3-4B-Instruct-2507-GGUF", 2_497L * MIB, layers = 36),
        DemoModel("gemma-3-4b-it-Q4_K_M.gguf", ModelType.LLM, "unsloth/gemma-3-4b-it-GGUF", 2_489L * MIB, vision = true, layers = 34),
        DemoModel("Qwen3-8B-Q4_K_M.gguf", ModelType.LLM, "Qwen/Qwen3-8B-GGUF", 5_027L * MIB, layers = 36),
        DemoModel("Llama-3.2-3B-Instruct-Q4_K_M.gguf", ModelType.LLM, "bartowski/Llama-3.2-3B-Instruct-GGUF", 1_925L * MIB, layers = 28),
        DemoModel("Phi-4-mini-instruct-Q4_K_M.gguf", ModelType.LLM, "unsloth/Phi-4-mini-instruct-GGUF", 2_376L * MIB, layers = 32),
        DemoModel("SmolLM3-3B-Q8_0.gguf", ModelType.LLM, "ggml-org/SmolLM3-3B-GGUF", 3_120L * MIB, layers = 36),
        DemoModel("Qwen3-Coder-30B-A3B-Instruct-Q3_K_S.gguf", ModelType.LLM, "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF", 13_184L * MIB, layers = 48),
        DemoModel("nomic-embed-text-v1.5.f16.gguf", ModelType.EMBEDDING, "nomic-ai/nomic-embed-text-v1.5-GGUF", 262L * MIB)
    )
    val diffusion = listOf(
        DemoModel("sd-v1-5-pruned-emaonly-Q8_0.gguf", ModelType.SD_CHECKPOINT, "gpustack/stable-diffusion-v1-5-GGUF", 1_720L * MIB,
            sdFamily = "SD1", sdCaps = "txt2img,img2img", sdLayout = "full_model"),
        DemoModel("sdxl-turbo-Q8_0.gguf", ModelType.SD_CHECKPOINT, "gpustack/sdxl-turbo-GGUF", 3_800L * MIB,
            sdFamily = "SDXL", sdCaps = "txt2img,img2img", sdLayout = "full_model"),
        DemoModel("realesrgan-x4plus-anime.bin", ModelType.SD_UPSCALER, "xinntao/Real-ESRGAN", 17L * MIB, sdCaps = "upscale")
    )
    val speech = listOf(
        DemoModel("ggml-whisper-small-q5_1.bin", ModelType.WHISPER, "ggerganov/whisper.cpp", 181L * MIB),
        DemoModel("ggml-whisper-base-q5_1.bin", ModelType.WHISPER, "ggerganov/whisper.cpp", 57L * MIB),
        DemoModel("OuteTTS-0.3-500M-Q8_0.gguf", ModelType.LLAMA_TTS, "OuteAI/OuteTTS-0.3-500M-GGUF", 540L * MIB)
    )

    /** Creates a sparse file `<files>/models/<name>` of [bytes] bytes and returns it. */
    fun sparseFile(context: Context, name: String, bytes: Long): File {
        val file = File(context.filesDir, "models/$name").apply { parentFile?.mkdirs() }
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    fun insert(context: Context, models: List<DemoModel>) = runBlocking {
        val dao = AppDatabase.getDatabase(context).modelDao()
        models.forEach { m ->
            // Inspected SD checkpoints live where the app's reconcile step would move them (managed external
            // storage), otherwise the move changes path and mtime and invalidates the primed inspection cache.
            val file = if (m.sdLayout != null) {
                File(context.getExternalFilesDir(null), "models/sd/checkpoints/${m.filename}").apply { parentFile?.mkdirs() }
                    .also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(m.bytes) } }
            } else {
                sparseFile(context, m.filename, m.bytes)
            }
            dao.insertModel(
                withInspection(m, file,
                ModelEntity(
                    filename = m.filename, path = file.absolutePath, sizeBytes = m.bytes, type = m.type, repoId = m.repo,
                    isDownloaded = true, isVision = m.vision, layerCount = m.layers,
                    sdCapabilities = m.sdCaps, sdFamily = m.sdFamily, sdArtifactLayout = m.sdLayout
                ))
            )
        }
    }

    /**
     * The sparse placeholder has no tensors, so the lazy header inspection would flag it as an unusable
     * artifact and block Generate. Persist a structurally valid inspection and prime the in-process cache
     * with it so the screen treats the checkpoint as an inspected full model.
     */
    private fun withInspection(m: DemoModel, file: File, entity: ModelEntity): ModelEntity {
        if (m.sdLayout == null) return entity
        val inspection = SdArtifactInspection(
            format = SdArtifactFormat.GGUF,
            detectedFamily = SdModelFamily.CHECKPOINT,
            detectedRole = SdArtifactRole.FULL_MODEL,
            containsDiffusion = true, containsVae = true, containsClipL = true,
            tensorCount = 1_100L,
            confidence = SdInspectionConfidence.HIGH,
            mainLayout = SdMainLayout.FULL_MODEL,
            headerFingerprint = "demo-${m.filename}",
            fileSizeBytes = file.length(),
            modifiedAtMillis = file.lastModified()
        )
        SdInspectionCache.put(file, inspection)
        return entity.withSdArtifactInspection(inspection)
    }

    /**
     * Rewrites the stored path of the SD checkpoints to what a phone shows (the model card prints `model.path`),
     * instead of the host `/tmp/robolectric-...` directory. Only for screens that merely display the row; the
     * file no longer exists at that path.
     */
    fun showDeviceStylePaths(context: Context) = runBlocking {
        val dao = AppDatabase.getDatabase(context).modelDao()
        dao.getModelsByTypesSync(listOf(ModelType.SD_CHECKPOINT)).forEach { m ->
            dao.insertModel(m.copy(path = "/storage/emulated/0/Android/data/com.manuxd32.aidoomsdaytoolbox/files/models/sd/checkpoints/${m.filename}"))
        }
    }

    /** Fake 256 GB device with 118 GB free for the storage summary card. */
    fun registerDeviceStorage() {
        val block = 4096L
        org.robolectric.shadows.ShadowStatFs.registerStats(
            android.os.Environment.getDataDirectory(),
            (256 * GIB / block).toInt(), (130 * GIB / block).toInt(), (118 * GIB / block).toInt()
        )
    }
}
