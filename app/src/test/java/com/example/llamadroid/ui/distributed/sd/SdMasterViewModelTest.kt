package com.example.llamadroid.ui.distributed.sd

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.normalizedForSave
import com.example.llamadroid.service.withVideoOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSdMasterData(initial: SdDistributedMasterSettingsEntity? = null) : SdMasterData {
    val settingsFlow = MutableStateFlow(initial)
    val workersFlow = MutableStateFlow<List<SdDistributedWorkerEntity>>(emptyList())
    val templatesFlow = MutableStateFlow<List<SdDistributedTemplateEntity>>(emptyList())
    val writes = mutableListOf<SdDistributedMasterSettingsEntity>()
    val templateWrites = mutableListOf<SdDistributedTemplateEntity>()
    val workerWrites = mutableListOf<SdDistributedWorkerEntity>()

    override fun observeSettings() = settingsFlow
    override fun observeWorkers() = workersFlow
    override fun observeTemplates() = templatesFlow
    override suspend fun getSettings() = settingsFlow.value
    override suspend fun getEnabledWorkers() = workersFlow.value.filter { it.isEnabled }
    override suspend fun upsertSettings(settings: SdDistributedMasterSettingsEntity): Long {
        writes += settings
        settingsFlow.value = settings // Room echoes our own write back
        return 1L
    }
    override suspend fun maxWorkerSortOrder() = workersFlow.value.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun upsertWorker(worker: SdDistributedWorkerEntity): Long {
        workerWrites += worker
        return 1L
    }
    override suspend fun setWorkerEnabled(id: Long, enabled: Boolean) = Unit
    override suspend fun deleteWorker(id: Long) = Unit
    override suspend fun upsertTemplate(template: SdDistributedTemplateEntity): Long {
        templateWrites += template
        return 1L
    }
    override suspend fun deleteTemplate(id: Long) = Unit
}

class SdMasterViewModelTest {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val gates = ArrayDeque<CompletableDeferred<Unit>>()
    private val delays = mutableListOf<Long>()
    private val runtimes = mutableListOf<SdMasterRuntime>()
    private val clock = 1_000L

    @After
    fun tearDown() = job.cancel()

    private fun store(
        data: FakeSdMasterData,
        mode: SdMasterMode = SdMasterMode.IMAGES,
        savedStep: SdMasterStep? = null
    ) = SdMasterStore(
        scope = scope,
        data = data,
        sink = SdMasterRuntimeSink { runtimes += it },
        defaultMasterName = "This device",
        initialMode = mode,
        savedStep = savedStep,
        now = { clock },
        debounceMs = 600L,
        delayFn = { ms ->
            delays += ms
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }
    ).also { it.start() }

    private fun release() = gates.last().complete(Unit)

    @Test
    fun autosaveWritesOnlyAfterDebounceAndCoalescesEdits() {
        val data = FakeSdMasterData()
        val s = store(data)
        s.update { it.copy(imagePrompt = "a") }
        s.update { it.copy(imagePrompt = "ab") }
        assertTrue(data.writes.isEmpty())
        assertEquals(listOf(600L, 600L), delays)
        release()
        assertEquals(1, data.writes.size)
        assertEquals("ab", data.writes.single().imagePrompt)
        assertEquals("ab", data.writes.single().prompt)
        assertEquals(clock, data.writes.single().updatedAt)
    }

    @Test
    fun saveNowFlushesWithoutWaitingForDebounce() = runBlocking {
        val data = FakeSdMasterData()
        val s = store(data)
        s.update { it.copy(videoPrompt = "v") }
        s.saveNow()
        assertEquals(1, data.writes.size)
        assertEquals("v", data.writes.single().videoPrompt)
        assertTrue(runtimes.isNotEmpty())
    }

    @Test
    fun echoOfOwnWriteAndOlderRowsAreIgnored() {
        val data = FakeSdMasterData()
        val s = store(data)
        s.update { it.copy(imagePrompt = "mine") }
        release()
        val written = data.writes.single()
        data.settingsFlow.value = written.copy(imagePrompt = "stale", updatedAt = written.updatedAt)
        assertEquals("mine", s.state.value.settings.imagePrompt)
        data.settingsFlow.value = written.copy(imagePrompt = "older", updatedAt = written.updatedAt - 1)
        assertEquals("mine", s.state.value.settings.imagePrompt)
        data.settingsFlow.value = written.copy(imagePrompt = "external", updatedAt = written.updatedAt + 5)
        assertEquals("external", s.state.value.settings.imagePrompt)
    }

    @Test
    fun enabledIsDerivedFromHelpers() {
        val data = FakeSdMasterData()
        val s = store(data)
        assertFalse(s.state.value.settings.enabled)
        data.workersFlow.value = listOf(SdDistributedWorkerEntity(id = 1, host = "10.0.0.2", isEnabled = true))
        assertTrue(s.state.value.settings.enabled)
        release()
        assertTrue(data.writes.last().enabled)
        data.workersFlow.value = listOf(SdDistributedWorkerEntity(id = 1, host = "10.0.0.2", isEnabled = false))
        assertFalse(s.state.value.settings.enabled)
        release()
        assertFalse(data.writes.last().enabled)
    }

    @Test
    fun initialStepRule() {
        val empty = SdDistributedMasterSettingsEntity()
        val withModel = empty.copy(imageModelPath = "/m.gguf")
        val helper = listOf(SdDistributedWorkerEntity(host = "h", isEnabled = true))
        val off = listOf(SdDistributedWorkerEntity(host = "h", isEnabled = false))
        val opts = videoOptionsOf(empty)
        assertEquals(SdMasterStep.MODEL, initialSdMasterStep(SdMasterMode.IMAGES, empty, helper, opts))
        assertEquals(SdMasterStep.DEVICES, initialSdMasterStep(SdMasterMode.IMAGES, withModel, off, opts))
        assertEquals(SdMasterStep.RUN, initialSdMasterStep(SdMasterMode.IMAGES, withModel, helper, opts))
        val videoOpts = VideoRuntimeOptions(videoComponents = SdVideoComponentPaths(diffusionModelPath = "/v.gguf"))
        assertEquals(SdMasterStep.RUN, initialSdMasterStep(SdMasterMode.VIDEO, empty, helper, videoOpts))
        assertEquals(SdMasterStep.MODEL, initialSdMasterStep(SdMasterMode.VIDEO, withModel, helper, opts))
    }

    @Test
    fun storeComputesInitialStepOnceLoadedAndHonoursSavedStep() {
        val row = SdDistributedMasterSettingsEntity(imageModelPath = "/m.gguf", updatedAt = 5)
        val data = FakeSdMasterData(row)
        data.workersFlow.value = listOf(SdDistributedWorkerEntity(id = 1, host = "h", isEnabled = true))
        val computed = store(data)
        assertTrue(computed.state.value.loaded)
        assertEquals(SdMasterStep.RUN, computed.state.value.step)
        val saved = store(data, savedStep = SdMasterStep.DEVICES)
        assertEquals(SdMasterStep.DEVICES, saved.state.value.step)
    }

    @Test
    fun withVideoOptionsMirrorsLegacyColumns() {
        val options = VideoRuntimeOptions(
            workflow = SdVideoWorkflow.IMAGE_TO_VIDEO,
            videoComponents = SdVideoComponentPaths(
                diffusionModelPath = "/m/wan.gguf",
                vaePath = "/m/vae",
                t5xxlPath = "/m/umt5"
            ),
            videoInputs = SdVideoInputs("/in.png"),
            seed = 7L,
            useTae = false
        )
        val row = SdDistributedMasterSettingsEntity().withVideoOptions(options)
        assertEquals("/m/wan.gguf", row.videoModelPath)
        assertEquals("/m/vae", row.videoVaePath)
        assertTrue(row.videoUseVae)
        assertEquals("/m/umt5", row.videoT5xxlPath)
        assertTrue(row.videoUseT5xxl)
        assertEquals("/in.png", row.videoInputPath)
        assertEquals("7", row.videoSeed)
        assertEquals("IMG2VID", row.videoWorkflowMode)
        assertNotEquals("{}", row.videoAdvancedJson)

        val tae = SdDistributedMasterSettingsEntity().withVideoOptions(options.copy(useTae = true, seed = -1L, workflow = SdVideoWorkflow.TEXT_TO_VIDEO))
        assertFalse(tae.videoUseVae)
        assertEquals("", tae.videoSeed)
        assertEquals("TXT2VID", tae.videoWorkflowMode)
    }

    @Test
    fun updateVideoOptionsUpdatesStateAndSchedulesSave() {
        val data = FakeSdMasterData()
        val s = store(data, SdMasterMode.VIDEO)
        val options = VideoRuntimeOptions(videoComponents = SdVideoComponentPaths(diffusionModelPath = "/m/wan.gguf"))
        s.updateVideoOptions(options)
        assertEquals(options, s.state.value.videoOptions)
        assertEquals("/m/wan.gguf", s.state.value.settings.videoModelPath)
        release()
        assertEquals("/m/wan.gguf", data.writes.single().videoModelPath)
    }

    @Test
    fun normalizedForSaveMirrorsLegacyColumns() {
        val row = SdDistributedMasterSettingsEntity(
            imagePrompt = "p",
            imageNegativePrompt = "n",
            imageWidth = "",
            imageHeight = "704",
            masterDisplayName = "",
            masterBackendDevice = "",
            masterDiffusionSharePercent = "5x0"
        ).normalizedForSave("This device")
        assertEquals("p", row.prompt)
        assertEquals("n", row.negativePrompt)
        assertEquals("512 x 704", row.dimensions)
        assertEquals("This device", row.masterDisplayName)
        assertEquals("cpu", row.masterBackendDevice)
        assertEquals("50", row.masterDiffusionSharePercent)
        assertEquals("[]", row.imageLorasJson)
    }

    @Test
    fun workerAndTemplateActionsReachTheDao() = runBlocking {
        val data = FakeSdMasterData()
        val s = store(data, SdMasterMode.VIDEO)
        assertFalse(s.saveWorker(null, "  ", "", "", "", "", ""))
        assertTrue(s.saveWorker(null, " 10.0.0.9 ", "abc", "", "", "", ""))
        val worker = data.workerWrites.single()
        assertEquals("10.0.0.9", worker.host)
        assertEquals("10.0.0.9", worker.deviceName)
        assertEquals(0, worker.sortOrder)
        assertTrue(s.saveTemplate(" T1 "))
        val template = data.templateWrites.single()
        assertEquals("T1", template.name)
        assertEquals("VIDEO", template.workflowType)
        s.loadTemplate(template)
        assertEquals(1, s.state.value.settings.id)
    }
}
