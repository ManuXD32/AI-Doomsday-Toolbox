package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedMode
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.RpcWorkerStatus
import com.example.llamadroid.service.SdDistributedPlacementMode
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdDistributedWorkerAssignment
import com.example.llamadroid.service.SdDistributedWorkerRuntime
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.model.SavedWorker
import com.example.llamadroid.service.DistributedLaunchResolution
import com.example.llamadroid.service.LlamaModelMetadataProbe
import com.example.llamadroid.service.LlamaModelProbeResult
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SdProgressSnapshot
import com.example.llamadroid.screenshots.harness.ShotLocale
import io.mockk.coEvery
import io.mockk.mockkObject
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Fakes the distributed LLM (llama.cpp RPC) and distributed media (stable-diffusion RPC) runtime state.
 * The services keep their state in private `MutableStateFlow`s of companion objects, so the fixtures set
 * them by reflection; no sockets, wake locks or connection monitors are started.
 */
object DistributedFixtures {
    data class DemoDevice(val name: String, val ip: String, val ramMB: Int, val layers: IntRange, val usedMB: Float)

    /** Coordinator is the phone running the screenshot; three workers share the model. */
    val llmDevices = listOf(
        DemoDevice("Pixel 8 Pro", "192.168.1.21", 8192, 12..22, 3480.5f),
        DemoDevice("Galaxy S24", "192.168.1.34", 6144, 23..30, 2512.0f),
        DemoDevice("Desktop RTX", "192.168.1.50", 24576, 31..63, 13890.5f)
    )

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Class<*>, name: String, instance: Any? = null): MutableStateFlow<T> =
        field(owner, name).get(instance) as MutableStateFlow<T>

    private fun distributedCompanionFlow(name: String): MutableStateFlow<Any?> =
        flow(DistributedService::class.java, name)

    /** Seeds [DistributedService] / [DistributedMasterRuntimeState] so the topology screen shows a running cluster. */
    fun seedLlm(@Suppress("UNUSED_PARAMETER") app: Application, nowMs: Long = System.currentTimeMillis()) {
        distributedCompanionFlow("_mode").value = DistributedMode.MASTER
        distributedCompanionFlow("_isRunning").value = true
        distributedCompanionFlow("_masterRamMB").value = 8192
        val workers = llmDevices.mapIndexed { index, d ->
            WorkerInfo(
                ip = d.ip, port = 50052, deviceName = d.name, availableRamMB = d.ramMB,
                assignedLayers = d.layers, isConnected = true, isEnabled = true,
                savedWorkerId = (index + 1).toLong(), realRamUsageMB = d.usedMB,
                rpcStatus = RpcWorkerStatus.ONLINE, lastConfirmedAtMs = nowMs - 20_000L * (index + 1),
                statusDetail = null
            )
        }
        distributedCompanionFlow("_workers").value = workers
        DistributedService.setModelInfo(layers = 64, sizeMB = 19_456L, rpcLayers = 52)
        DistributedService.setInferenceRunning(true)
        distributedCompanionFlow("_rpcLogs").value = listOf(
            "[10:41:02.118] rpc-server: accepted connection from 192.168.1.10",
            "[10:41:02.944] Pixel 8 Pro: offloaded 11/64 layers, 3.4 GiB",
            "[10:41:03.071] Galaxy S24: offloaded 8/64 layers, 2.5 GiB",
            "[10:41:03.552] Desktop RTX: offloaded 33/64 layers, 13.6 GiB",
            "[10:41:19.403] load_tensors: model loaded across 4 devices",
            "[10:41:31.220] decode: 512 prompt tokens in 3.1 s (165.2 t/s)",
            "[10:41:38.906] decode: generated 128 tokens at 14.6 tokens/s"
        )
        DistributedService.setTransferProgress(0)
        DistributedMasterRuntimeState::class.java.getDeclaredField("_state").apply { isAccessible = true }.let { f ->
            @Suppress("UNCHECKED_CAST")
            (f.get(DistributedMasterRuntimeState) as MutableStateFlow<ServerState>).value = ServerState.Running(8080)
        }
    }

    /** The master screen reads saved workers from Room. */
    fun seedLlmSavedWorkers(app: Application) {
        runBlocking {
            val dao = AppDatabase.getDatabase(app).savedWorkerDao()
            llmDevices.forEach { d ->
                dao.insertWorker(SavedWorker(ip = d.ip, port = 50052, deviceName = d.name, ramMB = d.ramMB))
            }
        }
    }

    private fun sparseModel(app: Application, name: String, sizeBytes: Long): File {
        val dir = File(app.getExternalFilesDir(null), "models").apply { mkdirs() }
        return File(dir, name).also { RandomAccessFile(it, "rw").use { f -> f.setLength(sizeBytes) } }
    }

    /** Master screen: model chosen, workers saved, layer probe answered without running llama-server. */
    fun seedLlmMaster(app: Application) {
        seedLlm(app)
        seedLlmSavedWorkers(app)
        val file = sparseModel(app, "Qwen3-32B-Q4_K_M.gguf", 19_456L * 1024 * 1024)
        val model = ModelEntity(
            filename = file.name, path = file.absolutePath, sizeBytes = file.length(), type = ModelType.LLM,
            repoId = "unsloth/Qwen3-32B-GGUF", isDownloaded = true, layerCount = 64
        )
        runBlocking { AppDatabase.getDatabase(app).modelDao().insertModel(model) }
        DistributedService.setMasterSelectedModel(model)
        DistributedService.setMasterRam(8192)
        // Robolectric reports 0 bytes of RAM, which makes the master RAM slider read "0 MB".
        val memory = android.app.ActivityManager.MemoryInfo().apply { totalMem = 12L * 1024 * 1024 * 1024; availMem = 7L * 1024 * 1024 * 1024 }
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.ActivityManager::class.java)).setMemoryInfo(memory)
        mockkObject(DistributedLaunchResolution)
        coEvery { DistributedLaunchResolution.selectBinary(any(), any(), any()) } returns File(app.filesDir, "llama-server")
        mockkObject(LlamaModelMetadataProbe)
        coEvery { LlamaModelMetadataProbe.probe(any(), any(), any(), any()) } returns LlamaModelProbeResult(
            transformerBlocks = 64, offloadableLayers = 65, architecture = "qwen3",
            binaryPath = File(app.filesDir, "llama-server").absolutePath, modelFingerprint = "demo-qwen3-32b"
        )
    }

    /** Media master: saved workers, an image checkpoint, master settings and a running distributed job. */
    fun seedMediaMaster(app: Application, l: ShotLocale, running: Boolean = true) {
        seedMedia(app)
        val file = sparseModel(app, "flux1-schnell-Q4_K_S.gguf", 6_800L * 1024 * 1024)
        val model = ModelEntity(
            filename = file.name, path = file.absolutePath, sizeBytes = file.length(), type = ModelType.SD_DIFFUSION,
            repoId = "city96/FLUX.1-schnell-gguf", isDownloaded = true, sdCapabilities = "txt2img,img2img", sdFamily = "flux"
        )
        runBlocking {
            val db = AppDatabase.getDatabase(app)
            db.modelDao().insertModel(model)
            db.sdDistributedDao().upsertMasterSettings(
                SdDistributedMasterSettingsEntity(
                    enabled = true, masterContributes = true, masterDisplayName = "This phone", masterRamMB = 8192, masterThreads = 6,
                    imageModelPath = model.path,
                    imagePrompt = l.pick(
                        "flat illustration of a cozy cabin library with warm lantern light, rich colors",
                        "ilustración plana de una acogedora biblioteca en una cabaña con luz cálida de farol, colores intensos"
                    ),
                    imageSteps = "20", imageWidth = "1024", imageHeight = "1024"
                )
            )
        }
        if (running) {
            SDModeStateHolder.distributedTxt2img.updateState(
                SDGenerationState.Generating(
                    SdProgressSnapshot(
                        currentStep = 14, totalSteps = 20, progress = 0.68f, iterationSeconds = 1.9, etaSeconds = 11.4,
                        statusText = l.pick("Sampling across 3 workers", "Muestreando en 3 trabajadores")
                    )
                )
            )
        }
    }

    val mediaDevices = listOf(
        DemoDevice("Pixel 8 Pro", "192.168.1.21", 8192, 0..0, 0f),
        DemoDevice("Galaxy S24", "192.168.1.34", 6144, 0..0, 0f),
        DemoDevice("Desktop RTX", "192.168.1.50", 24576, 0..0, 0f)
    )

    fun seedMedia(app: Application, nowMs: Long = System.currentTimeMillis()) {
        val config = SdDistributedRuntimeConfig(
            enabled = true,
            rpcServers = mediaDevices.joinToString(",") { "${it.ip}:50062" },
            placementMode = SdDistributedPlacementMode.AUTO_RAM,
            backendSpec = "CPU,Vulkan0",
            maxVramSpec = "auto"
        )
        SdDistributedService.setRuntimeConfig(config)
        val assignments = listOf(
            listOf("diffusion" to 22, "vae" to 0),
            listOf("diffusion" to 18, "te" to 0),
            listOf("diffusion" to 60, "upscaler" to 0)
        )
        val runtime = mediaDevices.mapIndexed { index, d ->
            SdDistributedWorkerRuntime(
                host = d.ip, port = 50062, deviceName = d.name, ramMB = d.ramMB, threads = listOf(6, 6, 16)[index],
                backendDevice = "", isConnected = true,
                assignedModules = assignments[index].map { it.first },
                rpcName = "RPC${index}",
                plannedAssignments = assignments[index].map { (module, share) ->
                    SdDistributedWorkerAssignment(module, module, isSplit = share > 0, estimatedLayerShare = share)
                },
                lastSeenAt = nowMs - 15_000L
            )
        }
        flow<List<SdDistributedWorkerRuntime>>(SdDistributedService::class.java, "_activeWorkers").value = runtime
        flow<List<String>>(SdDistributedService::class.java, "_logs").value = listOf(
            "[10:52:10] Connected to 3 RPC workers",
            "[10:52:12] diffusion model split across Pixel 8 Pro, Galaxy S24 and Desktop RTX",
            "[10:52:40] sampling step 14/20 (1.9 s/step)"
        )
        runBlocking {
            val dao = AppDatabase.getDatabase(app).sdDistributedDao()
            mediaDevices.forEachIndexed { index, d ->
                dao.upsertWorker(
                    SdDistributedWorkerEntity(
                        host = d.ip, port = 50062, deviceName = d.name, ramMB = d.ramMB,
                        threads = listOf(6, 6, 16)[index], lastSeenAt = nowMs - 15_000L, sortOrder = index
                    )
                )
            }
        }
    }

    fun reset() {
        runCatching { SDModeStateHolder.distributedTxt2img.reset() }
        runCatching { DistributedService.setMasterSelectedModel(null) }
        runCatching {
            distributedCompanionFlow("_mode").value = DistributedMode.NONE
            distributedCompanionFlow("_isRunning").value = false
            distributedCompanionFlow("_workers").value = emptyList<WorkerInfo>()
            DistributedService.setModelInfo(0, 0L, 0)
            DistributedService.setInferenceRunning(false)
            distributedCompanionFlow("_rpcLogs").value = emptyList<String>()
        }
        runCatching {
            flow<List<SdDistributedWorkerRuntime>>(SdDistributedService::class.java, "_activeWorkers").value = emptyList()
            flow<List<String>>(SdDistributedService::class.java, "_logs").value = emptyList()
            flow<SdDistributedRuntimeConfig>(SdDistributedService::class.java, "_runtimeConfig").value = SdDistributedRuntimeConfig()
        }
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (DistributedMasterRuntimeState::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(DistributedMasterRuntimeState) as MutableStateFlow<ServerState>).value = ServerState.Stopped
        }
    }
}
