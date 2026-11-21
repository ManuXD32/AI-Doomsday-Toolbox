package com.example.llamadroid.ui.distributed.sd

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In-memory [SdMasterData]: worker toggles feed back through the workers flow like Room does. */
private class InMemorySdMasterData : SdMasterData {
    val settingsFlow = MutableStateFlow<SdDistributedMasterSettingsEntity?>(null)
    val workersFlow = MutableStateFlow<List<SdDistributedWorkerEntity>>(emptyList())
    private val templatesFlow = MutableStateFlow<List<SdDistributedTemplateEntity>>(emptyList())

    override fun observeSettings() = settingsFlow
    override fun observeWorkers() = workersFlow
    override fun observeTemplates() = templatesFlow
    override suspend fun getSettings() = settingsFlow.value
    override suspend fun getEnabledWorkers() = workersFlow.value.filter { it.isEnabled }
    override suspend fun upsertSettings(settings: SdDistributedMasterSettingsEntity): Long {
        settingsFlow.value = settings
        return 1L
    }
    override suspend fun maxWorkerSortOrder() = workersFlow.value.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun upsertWorker(worker: SdDistributedWorkerEntity): Long = 1L
    override suspend fun setWorkerEnabled(id: Long, enabled: Boolean) {
        workersFlow.value = workersFlow.value.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
    }
    override suspend fun deleteWorker(id: Long) {
        workersFlow.value = workersFlow.value.filterNot { it.id == id }
    }
    override suspend fun upsertTemplate(template: SdDistributedTemplateEntity): Long = 1L
    override suspend fun deleteTemplate(id: Long) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SdMasterStepDevicesTest {
    @get:Rule
    val rule = createComposeRule()

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() = job.cancel()

    private fun store(data: InMemorySdMasterData) = SdMasterStore(
        scope = scope,
        data = data,
        sink = SdMasterRuntimeSink { },
        defaultMasterName = "This phone",
        initialMode = SdMasterMode.IMAGES,
        savedStep = SdMasterStep.DEVICES,
        debounceMs = 600L,
        delayFn = { kotlinx.coroutines.awaitCancellation() }
    ).also { it.start() }

    @Test
    fun togglingAHelperFlipsDerivedEnabledAndTheSummary() {
        val data = InMemorySdMasterData()
        data.workersFlow.value = listOf(
            SdDistributedWorkerEntity(id = 1, host = "10.0.0.2", deviceName = "Pixel 8", ramMB = 5120, isEnabled = false)
        )
        val store = store(data)
        val noop: SdSaveHelper = { _, _, _, _, _, _, _, _ -> }
        rule.setContent {
            val state by store.state.collectAsState()
            SdMasterSplitSummaryCard(buildSdMasterRuntime(state.settings, state.workers))
            SdMasterHelpersCard(
                workers = state.workers,
                online = { null },
                onToggle = { worker -> runBlocking { store.setWorkerEnabled(worker) } },
                onDelete = { },
                onSave = noop,
                onOpenHelperGuide = { }
            )
        }
        rule.onNodeWithText("Runs on this phone only").assertExists()
        assertFalse(store.state.value.settings.enabled)

        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()

        assertTrue(store.state.value.settings.enabled)
        rule.onNodeWithText("1 Helper turned on").assertExists()
        rule.onNodeWithText("Memory shared: 5.0 GB on Pixel 8").assertExists()

        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()
        assertFalse(store.state.value.settings.enabled)
        rule.onNodeWithText("Runs on this phone only").assertExists()
    }

    @Test
    fun manualAddDisclosureOpensTheFormWithCancel() {
        val workers = listOf(SdDistributedWorkerEntity(id = 1, host = "10.0.0.2", deviceName = "Pixel 8"))
        rule.setContent {
            SdMasterHelpersCard(
                workers = workers,
                online = { null },
                onToggle = { },
                onDelete = { },
                onSave = { _, _, _, _, _, _, _, _ -> },
                onOpenHelperGuide = { }
            )
        }
        rule.onNodeWithText("Add Helper").assertDoesNotExist()
        rule.onNodeWithText("Add a Helper manually").performClick()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("Add Helper").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Add Helper").assertDoesNotExist()
    }
}
