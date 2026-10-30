package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import android.content.ComponentName
import android.app.Service
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.data.db.DatasetProjectEntity
import com.example.llamadroid.data.db.DatasetSourceEntity
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeBaseSourceType
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.db.ModelBundleEntity
import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceKind
import com.example.llamadroid.data.model.library.PendingArtifactStatus
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.OllamaServerEntity
import com.example.llamadroid.data.db.SourceType
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogStatus
import com.example.llamadroid.data.model.LlamaScheduledTaskScheduleType
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.screenshots.catalog.GuideShotContext
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.db.FarmTileEntity
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile

/** Extra seeds used only by the guide renderer (screens the store fixtures do not cover). */
object GuideFixtures {
    private const val HOUR = 3_600_000L
    private const val MIN = 60_000L

    /** Real service instance answering `bindService` (Robolectric otherwise delivers a null binder). */
    fun bindable(app: Application, service: Class<out Service>) {
        val instance = Robolectric.buildService(service).create().get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, service), instance.onBind(null))
    }

    /** Agent project list (conversations grouped by project) with a few finished conversations. */
    fun agentProjects(app: Application, l: ShotLocale) = runBlocking {
        val dao = AppDatabase.getDatabase(app).agentChatDao()
        val now = System.currentTimeMillis()
        val projects = listOf(
            Triple("field_guide", l.pick("Offline field guide", "Guía de campo sin conexión"), l.pick("Build the field guide site and zip it", "Crea el sitio de la guía de campo y comprímelo")),
            Triple("water_calc", l.pick("Water storage calculator", "Calculadora de almacenamiento de agua"), l.pick("Add a liters-per-person calculator page", "Añade una página para calcular litros por persona")),
            Triple("seed_log", l.pick("Seed saving log", "Registro de semillas"), l.pick("Create a printable seed inventory", "Crea un inventario de semillas imprimible"))
        )
        projects.forEachIndexed { index, (folder, title, task) ->
            val id = dao.insertConversationDirect(
                AgentConversationEntity(
                    title = title, projectFolder = folder, sortOrder = index, lastTask = task,
                    createdAt = now - (index + 1) * 26 * HOUR, updatedAt = now - (index * 7 + 1) * HOUR
                )
            )
            dao.insertMessage(AgentMessageEntity(originalId = "demo-$folder-1", conversationId = id, role = "user", content = task, timestamp = now - (index * 7 + 2) * HOUR))
            dao.insertMessage(
                AgentMessageEntity(
                    originalId = "demo-$folder-2", conversationId = id, role = "assistant",
                    content = l.pick("Done. The project files are in the workspace.", "Listo. Los archivos del proyecto están en el espacio de trabajo."),
                    timestamp = now - (index * 7 + 1) * HOUR
                )
            )
        }
    }

    /** A chat pinned to the home screen's Continue row. */
    fun pinnedChat(app: Application, l: ShotLocale, server: ServerFixtures.Running) = runBlocking {
        val now = System.currentTimeMillis()
        val db = AppDatabase.getDatabase(app)
        val chatId = db.llamaChatDao().insertChat(
            LlamaChatEntity(
                title = l.pick("Trip checklist", "Lista del viaje"), serverId = server.rowId, lastModified = now - 20 * MIN,
                pinnedToAiHub = true, pinnedServerId = server.rowId, pinnedAt = now - 30 * MIN
            )
        )
        db.llamaMessageDao().insertMessage(LlamaMessageEntity(chatId = chatId, role = "user", content = l.pick("What should I pack?", "¿Qué debería llevar?"), timestamp = now - 25 * MIN))
        db.llamaMessageDao().insertMessage(LlamaMessageEntity(chatId = chatId, role = "assistant", content = l.pick("Water, a map and a first-aid kit.", "Agua, un mapa y un botiquín."), timestamp = now - 24 * MIN))
    }

    fun scheduledTasks(app: Application, l: ShotLocale, server: ServerFixtures.Running) = runBlocking {
        val dao = AppDatabase.getDatabase(app).llamaScheduledTaskDao()
        val now = System.currentTimeMillis()
        val morning = dao.insertTask(
            LlamaScheduledTaskEntity(
                name = l.pick("Morning water report", "Informe matinal del agua"), serverId = server.rowId,
                taskPrompt = l.pick("Summarize my water storage notes and flag what to refill.", "Resume mis notas de agua y señala qué hay que reponer."),
                scheduleType = LlamaScheduledTaskScheduleType.DAILY, timeOfDayMinutes = 7 * 60 + 30,
                nextRunAtMillis = now + 11 * HOUR, lastRunAtMillis = now - 13 * HOUR
            )
        )
        dao.insertTask(
            LlamaScheduledTaskEntity(
                name = l.pick("Weekly seed inventory", "Inventario semanal de semillas"), serverId = server.rowId,
                taskPrompt = l.pick("List the seeds I should start this week.", "Lista las semillas que debería sembrar esta semana."),
                scheduleType = LlamaScheduledTaskScheduleType.WEEKLY, weekdaysMask = 0b0000010, timeOfDayMinutes = 9 * 60,
                nextRunAtMillis = now + 3 * 24 * HOUR, lastRunAtMillis = now - 4 * 24 * HOUR
            )
        )
        dao.insertTask(
            LlamaScheduledTaskEntity(
                name = l.pick("Monthly backup reminder", "Recordatorio mensual de copia"), serverId = server.rowId, enabled = false,
                taskPrompt = l.pick("Remind me to back up the field guide.", "Recuérdame hacer una copia de la guía de campo."),
                scheduleType = LlamaScheduledTaskScheduleType.MONTHLY, dayOfMonth = 1, timeOfDayMinutes = 20 * 60
            )
        )
        dao.insertLog(
            LlamaScheduledTaskLogEntity(
                taskId = morning, taskName = l.pick("Morning water report", "Informe matinal del agua"),
                scheduledAtMillis = now - 13 * HOUR, startedAtMillis = now - 13 * HOUR, finishedAtMillis = now - 13 * HOUR + 42_000,
                durationMs = 42_000, status = LlamaScheduledTaskLogStatus.SUCCESS, serverId = server.rowId, serverName = "Qwen3 4B",
                finalOutput = l.pick("Water storage: 212 L. Refill the two oldest jugs.", "Agua almacenada: 212 L. Rellena los dos bidones más antiguos.")
            )
        )
    }

    fun datasetProjects(app: Application, l: ShotLocale) = runBlocking {
        val dao = AppDatabase.getDatabase(app).datasetDao()
        listOf(
            l.pick("Survival field guide Q&A", "P&R de la guía de supervivencia") to listOf("water_purification.pdf", "first_aid_notes.txt"),
            l.pick("Gardening almanac", "Almanaque de jardinería") to listOf("seed_saving.pdf")
        ).forEach { (name, sources) ->
            val id = dao.insertProject(DatasetProjectEntity(name = name, createdAt = System.currentTimeMillis() - 2 * 24 * HOUR))
            sources.forEach {
                dao.insertSource(DatasetSourceEntity(projectId = id, type = if (it.endsWith("pdf")) SourceType.PDF else SourceType.TXT, uri = "content://demo/$it", name = it))
            }
        }
    }

    fun knowledgeBases(app: Application, l: ShotLocale) = runBlocking {
        val dao = AppDatabase.getDatabase(app).knowledgeBaseDao()
        val now = System.currentTimeMillis()
        listOf(
            Triple(l.pick("Field guide library", "Biblioteca de la guía de campo"), l.pick("Water, shelter and first-aid references", "Referencias de agua, refugio y primeros auxilios"), listOf("water_purification.pdf" to 412, "first_aid_notes.txt" to 96)),
            Triple(l.pick("Garden notes", "Notas del huerto"), l.pick("Seed saving and companion planting", "Conservación de semillas y asociación de cultivos"), listOf("seed_saving.pdf" to 188))
        ).forEach { (name, summary, sources) ->
            val baseId = dao.insertKnowledgeBase(KnowledgeBaseEntity(name = name, description = summary, contentSummary = summary, createdAt = now - 3 * 24 * HOUR))
            sources.forEach { (title, chunks) ->
                dao.insertSource(
                    KnowledgeSourceEntity(
                        knowledgeBaseId = baseId, type = KnowledgeBaseSourceType.FILE, sourceRef = "content://demo/$title", title = title,
                        status = KnowledgeBaseSourceStatus.INDEXED, processingStage = KnowledgeBaseSourceStatus.INDEXED,
                        chunkCount = chunks, embeddedChunkCount = chunks, indexedAt = now - 2 * 24 * HOUR
                    )
                )
            }
        }
    }

    /** Model library: saved source links, one staged unknown artifact and one bundle definition. */
    fun modelSources(app: Application, l: ShotLocale) = runBlocking {
        val dao = AppDatabase.getDatabase(app).modelLibraryDao()
        val now = System.currentTimeMillis()
        val sources = listOf(
            ModelSourceEntity(
                kind = ModelSourceKind.HUGGING_FACE_REPOSITORY.storedValue, family = ModelFamily.LLM.storedValue,
                label = "unsloth/Qwen3-4B-Instruct-2507-GGUF", url = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF",
                normalizedKey = "hf:unsloth/qwen3-4b-instruct-2507-gguf", repositoryId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                verified = true, checkedAt = now - 2 * HOUR
            ),
            ModelSourceEntity(
                kind = ModelSourceKind.HUGGING_FACE_REPOSITORY.storedValue, family = ModelFamily.SD.storedValue,
                label = "gpustack/stable-diffusion-v1-5-GGUF", url = "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF",
                normalizedKey = "hf:gpustack/stable-diffusion-v1-5-gguf", repositoryId = "gpustack/stable-diffusion-v1-5-GGUF",
                verified = true, checkedAt = now - 3 * HOUR
            ),
            ModelSourceEntity(
                kind = ModelSourceKind.HTTPS.storedValue, family = ModelFamily.WHISPER.storedValue,
                label = l.pick("Whisper small (mirror)", "Whisper small (espejo)"), url = "https://models.example.org/ggml-whisper-small-q5_1.bin",
                normalizedKey = "https:models.example.org/ggml-whisper-small-q5_1.bin", verified = false
            )
        )
        dao.upsertSources(sources)
        dao.upsertPendingArtifacts(
            listOf(
                PendingModelArtifactEntity(
                    filename = "mystery-model-q4.gguf", stagingPath = "/data/user/0/com.example.llamadroid/files/model_staging/mystery-model-q4.gguf",
                    detectedFamily = ModelFamily.LLM.storedValue, detectedType = "LLM", status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                    validationMessage = l.pick("Detected a GGUF language model; confirm the role before promoting.", "Se detectó un modelo de lenguaje GGUF; confirma el rol antes de promoverlo.")
                )
            )
        )
        val bundle = ModelBundleEntity(name = l.pick("Image studio starter", "Estudio de imagen inicial"), family = ModelFamily.SD.storedValue, description = l.pick("Checkpoint plus upscaler", "Checkpoint más ampliador"))
        dao.upsert(bundle)
        dao.upsertBundleItems(
            listOf(
                ModelBundleItemEntity(bundleId = bundle.id, itemKey = "checkpoint", family = ModelFamily.SD.storedValue, role = "checkpoint", sourceId = sources[1].id, localFilename = "sd-v1-5-pruned-emaonly-Q8_0.gguf"),
                ModelBundleItemEntity(bundleId = bundle.id, itemKey = "upscaler", family = ModelFamily.SD.storedValue, role = "upscaler", required = false, localFilename = "realesrgan-x4plus-anime.bin")
            )
        )
    }

    fun ollamaServer(app: Application) = runBlocking {
        AppDatabase.getDatabase(app).ollamaServerDao().insertServer(
            OllamaServerEntity(name = "Desktop Ollama", url = "http://192.168.1.50:11434", lastConnected = System.currentTimeMillis() - 10 * MIN)
        )
    }

    /** Benchmark screen: a selected LLM and two finished runs in the history. */
    fun benchmark(app: Application) = runBlocking {
        val model = ModelFixtures.llms.first()
        val path = ModelFixtures.sparseFile(app, model.filename, model.bytes).absolutePath
        SettingsRepository(app).setSelectedModelPath(path)
        val dao = AppDatabase.getDatabase(app).benchmarkDao()
        val started = System.currentTimeMillis() - 5 * HOUR
        listOf(2 to (38.2f to 7.1f), 4 to (71.5f to 11.4f), 6 to (96.3f to 13.2f), 8 to (104.8f to 12.6f)).forEach { (threads, speed) ->
            dao.insert(
                BenchmarkResult(
                    modelPath = path, modelName = model.filename, threads = threads, promptTokensPerSecond = speed.first,
                    genTokensPerSecond = speed.second, promptTokens = 512, genTokens = 128, timestamp = started + threads * MIN, runStartedAt = started
                )
            )
        }
    }

    /** Debug log: a short readable history instead of whatever the host test wrote. */
    fun logs(l: ShotLocale) {
        DebugLog.clear()
        listOf(
            l.pick("Llama server Qwen3 4B started on port 8080", "Servidor llama Qwen3 4B iniciado en el puerto 8080"),
            l.pick("Loaded model Qwen3-4B-Q4_K_M.gguf (36 layers, 2.4 GB)", "Modelo cargado Qwen3-4B-Q4_K_M.gguf (36 capas, 2,4 GB)"),
            l.pick("Native chat: tool kiwix_search finished in 0.8 s", "Chat nativo: la herramienta kiwix_search terminó en 0,8 s"),
            l.pick("Image generation queued: txt2img 512x512, 20 steps", "Generación de imagen en cola: txt2img 512x512, 20 pasos"),
            l.pick("Image generation finished in 41 s", "Generación de imagen terminada en 41 s"),
            l.pick("File Station started on http://192.168.1.10:8787", "File Station iniciado en http://192.168.1.10:8787"),
            l.pick("Download complete: gemma-3-4b-it-Q4_K_M.gguf", "Descarga completa: gemma-3-4b-it-Q4_K_M.gguf")
        ).forEach { DebugLog.log(it) }
        DebugLog.flushNow()
    }

    private fun sparse(file: File, bytes: Long): File {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    /** ONNX image, background-removal and TTS bundles with the on-disk layout the validators require. */
    fun onnxModels(app: Application) = runBlocking {
        val dao = AppDatabase.getDatabase(app).modelDao()
        val mib = ModelFixtures.MIB
        val sd = File(OnnxStorage.managedModelsRoot(app), "sd15-lcm-onnx-fp16")
        listOf("text_encoder/model.ort" to 246L, "unet/model.ort" to 820L, "vae_decoder/model.ort" to 95L, "tokenizer/vocab.json" to 1L, "tokenizer/merges.txt" to 1L)
            .forEach { (rel, size) -> sparse(File(sd, rel), size * mib) }
        dao.insertModel(
            ModelEntity(
                filename = "sd15-lcm-onnx-fp16", path = sd.absolutePath, sizeBytes = 1_163L * mib, type = ModelType.ONNX_IMAGE_GEN,
                repoId = "ManuXD32/sd15-lcm-onnx", isDownloaded = true, onnxCapabilities = "txt2img",
                onnxPipelineFamily = "sdai_local_diffusion", onnxAssetKind = "sdai_catalog_bundle"
            )
        )
        val bgr = File(OnnxStorage.managedModelsRoot(app), "rmbg_1_4_quantized")
        sparse(File(bgr, "model_quantized.onnx"), 44L * mib)
        dao.insertModel(
            ModelEntity(
                filename = "rmbg_1_4_quantized", path = File(bgr, "model_quantized.onnx").absolutePath, sizeBytes = 44L * mib, type = ModelType.ONNX_BACKGROUND_REMOVAL,
                repoId = "briaai/RMBG-1.4", isDownloaded = true, onnxCapabilities = "background_removal",
                onnxPipelineFamily = "background_removal", onnxAssetKind = "background_removal_file"
            )
        )
        val tts = File(OnnxStorage.managedModelsRoot(app), "supertonic-3-onnx")
        listOf("onnx/duration_predictor.onnx" to 2L, "onnx/text_encoder.onnx" to 27L, "onnx/vector_estimator.onnx" to 130L, "onnx/vocoder.onnx" to 100L,
            "onnx/tts.json" to 1L, "onnx/unicode_indexer.json" to 1L, "voice_styles/F1.json" to 1L)
            .forEach { (rel, size) -> sparse(File(tts, rel), size * mib) }
        dao.insertModel(
            ModelEntity(
                filename = "supertonic-3-onnx", path = tts.absolutePath, sizeBytes = 262L * mib, type = ModelType.ONNX_TTS,
                repoId = "Supertone/supertonic-3", isDownloaded = true, onnxCapabilities = "tts",
                onnxPipelineFamily = "supertonic_tts", onnxAssetKind = "supertonic_catalog_bundle"
            )
        )
    }

    fun liteRtModels(app: Application) = runBlocking {
        val dao = AppDatabase.getDatabase(app).liteRtModelDao()
        val root = File(app.noBackupFilesDir, "litert_models")
        listOf("gemma-3n-E2B-it-int4.litertlm" to 3_390L, "Qwen3-0.6B.litertlm" to 586L).forEachIndexed { index, (file, mib) ->
            val f = sparse(File(root, file), mib * ModelFixtures.MIB)
            dao.insert(
                LiteRtModelEntity(
                    displayName = file.substringBeforeLast('.'), path = f.absolutePath, repoId = "litert-community/${file.substringBeforeLast('.')}",
                    filename = file, sizeBytes = mib * ModelFixtures.MIB, supportsGpu = index == 0, supportsVision = index == 0, maxContextTokens = 4096
                )
            )
        }
    }

    /** Standalone SD diffusion component (the "Standalone diffusion model" dropdown of the image screen). */
    fun diffusionComponent(app: Application) {
        ModelFixtures.insert(
            app, listOf(ModelFixtures.DemoModel("flux1-schnell-Q4_K_S.gguf", ModelType.SD_DIFFUSION, "city96/FLUX.1-schnell-gguf", 6_800L * ModelFixtures.MIB, sdFamily = "FLUX"))
        )
    }

    /** Tama art gallery: three completed paintings/dreams from the demo images. */
    fun tamaGallery(app: Application, petId: String = "demo-tama-pet", l: ShotLocale) = runBlocking {
        val dao = TamaDatabase.getInstance(app).tamaDao()
        val now = System.currentTimeMillis()
        listOf("aurora_igloo", "firefly_porch", "blossom_tea", "starlit_toys").forEachIndexed { index, name ->
            val id = "demo-art-$index"
            val file = File(app.filesDir, "tama_gallery/$petId/$id.png").apply { parentFile?.mkdirs() }
            file.writeBytes(MediaFixtures.demoBytes(name))
            dao.saveArtwork(
                TamaArtworkEntity(
                    id = id, petId = petId, kind = if (index % 2 == 0) "DREAM" else "PAINTING", status = "COMPLETED",
                    title = if (index % 2 == 0) l.pick("Dream", "Sueño") else l.pick("Painting", "Pintura"),
                    prompt = name.replace('_', ' '), negativePrompt = "", modelFilename = "sd15-lcm-onnx-fp16", modelLabel = "SD 1.5 LCM",
                    width = 512, height = 512, steps = 4, cfgScale = 1.5f, seed = 1000L + index, sourceActivity = null,
                    albumId = null, albumIndex = 0, albumDate = null, albumSummary = null, filePath = file.absolutePath,
                    errorMessage = null, createdAt = now - (index + 1) * 5 * HOUR, startedAt = now - (index + 1) * 5 * HOUR, completedAt = now - (index + 1) * 5 * HOUR + 40_000
                )
            )
        }
    }

    /** Farm with growing crops (stage 0..3) on all nine tiles. */
    fun farm(app: Application, petId: String = "demo-tama-pet") = runBlocking {
        val dao = TamaDatabase.getInstance(app).farmDao()
        val now = System.currentTimeMillis()
        val crops = listOf("wheat", "carrot", "rice", "wheat", "carrot", null, "rice", "carrot", "wheat")
        val tiles = crops.mapIndexed { id, type ->
            val tile = FarmTile(
                id = id, status = if (type == null) TileStatus.FARMLAND else TileStatus.WET_FARMLAND,
                crop = type?.let { PlantedCrop(type = it, stage = id % 4, plantedTime = now - (id + 1) * HOUR, lastStageUpdateTime = now - id * 20 * MIN) },
                lastWateredTime = now - 10 * MIN
            )
            FarmTileEntity(tile.id, petId, tile.status.name, tile.crop?.let { Json.encodeToString(it) }, tile.lastWateredTime)
        }
        dao.saveTiles(tiles)
    }

    /** Delegation chat in the middle of a task: assistant reply plus a RUNNING task (task bar + viewer icon). */
    fun delegationRunning(app: Application, l: ShotLocale, ctx: GuideShotContext) = runBlocking {
        val seeded = ctx.delegation ?: return@runBlocking
        val db = AppDatabase.getDatabase(app)
        val now = System.currentTimeMillis()
        db.llamaMessageDao().insertMessage(
            LlamaMessageEntity(
                chatId = seeded.chatId, role = "assistant", timestamp = now - 27 * MIN, promptTokens = 1_204, completionTokens = 96, tps = 9.9, generationTimeMs = 9_700,
                content = l.pick(
                    "I handed this to DeepSeek: it will build the pages, check the links and zip the site. You can follow it from the task bar.",
                    "Se lo he pasado a DeepSeek: creará las páginas, comprobará los enlaces y comprimirá el sitio. Puedes seguirlo desde la barra de tareas."
                )
            )
        )
    }

    fun makeDelegationRunning(app: Application, ctx: GuideShotContext) = runBlocking {
        val seeded = ctx.delegation ?: return@runBlocking
        // The "task finished" notice belongs to the completed variant; a running task shows the task bar instead.
        val messages = AppDatabase.getDatabase(app).llamaMessageDao()
        messages.getMessagesOnce(seeded.chatId).filter { it.kind == com.example.llamadroid.data.model.LlamaMessageKind.DELEGATION_NOTICE }
            .forEach { messages.deleteMessage(it) }
        val dao = AppDatabase.getDatabase(app).nativeChatDelegateTaskDao()
        val task = dao.getById(seeded.taskId) ?: return@runBlocking
        dao.update(
            task.copy(
                state = NativeChatDelegateTaskState.RUNNING, finishedAt = null, resultStatus = null,
                resultSummary = null, resultFilesJson = null, updatedAt = System.currentTimeMillis() - MIN
            )
        )
    }
}
