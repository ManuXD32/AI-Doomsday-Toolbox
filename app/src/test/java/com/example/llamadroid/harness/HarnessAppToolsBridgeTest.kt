package com.example.llamadroid.harness

import android.content.Context
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.sd.SdModelFamily
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HarnessAppToolsBridgeTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var operations: HarnessBridgeOperations

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(context).also {
            it.setAgentImageGenerationToolEnabled(true)
            it.setAgentImageGenerationEngine("ONNX")
            it.setAgentImageGenerationModel(null)
            it.setAgentWebSearchEnabled(true)
        }
        val credentials = HarnessCredentialStore(context)
        val workspaces = HarnessWorkspaceRepository(context, database, credentials)
        operations = HarnessBridgeOperations(
            context = context,
            database = database,
            credentials = credentials,
            workspaces = workspaces,
            files = HarnessWorkspaceAccess(context, credentials),
            models = HarnessLocalModels(context, database),
            diagnostics = HarnessDiagnostics(database, scope),
            executeLocal = { _, _, _ -> Unit },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun globalStatusWorksWithoutSessionAndReportsReadinessWithoutModelPaths() = runBlocking {
        val response = operations.invoke("appTools.status", sessionId = null, args = JSONObject()) as JSONObject
        val tools = response.getJSONObject("tools")

        assertEquals("needs_model", tools.getJSONObject("images_generate").getString("status"))
        assertEquals("ONNX", tools.getJSONObject("images_generate").getString("engine"))
        assertTrue(tools.getJSONObject("images_generate").getBoolean("enabled"))
        assertEquals("enabled", tools.getJSONObject("app_web_search").getString("status"))
        assertFalse(response.toString().contains("/data/user/"))
        assertFalse(response.toString().contains("apiKey"))
    }

    @Test
    fun authenticatedWebToggleWritesTheSharedSettingAndReturnsUpdatedStatus() = runBlocking {
        val response = operations.invoke(
            "appTools.setEnabled",
            sessionId = null,
            args = JSONObject().put("tool", "app_web_search").put("enabled", false),
        ) as JSONObject

        // Bridge calls reconstruct SettingsRepository; assert the persisted preference
        // through a fresh instance instead of the setup fixture's local StateFlow.
        assertFalse(SettingsRepository(context).agentWebSearchEnabled.value)
        assertEquals("off", response.getJSONObject("tools").getJSONObject("app_web_search").getString("status"))
        assertFalse(response.getJSONObject("tools").getJSONObject("app_web_search").getBoolean("ready"))
    }

    @Test
    fun sdImageStatusRequiresFamilyComponentsBeforeReportingReady() = runBlocking {
        val modelFile = File.createTempFile("qwen-image-2.1", ".gguf", context.cacheDir)
        try {
            settings.setAgentImageGenerationEngine("SD")
            settings.setAgentSdImageGenerationModel(modelFile.name)
            database.modelDao().insertModel(
                ModelEntity(
                    filename = modelFile.name,
                    path = modelFile.absolutePath,
                    sizeBytes = modelFile.length(),
                    type = ModelType.SD_DIFFUSION,
                    repoId = "local/qwen-image",
                    sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
                    sdFamily = SdModelFamily.QWEN_IMAGE.storedValue,
                    sdVariant = "2.1",
                ),
            )

            val response = operations.invoke("appTools.status", null, JSONObject()) as JSONObject
            val imageTool = response.getJSONObject("tools").getJSONObject("images_generate")
            assertEquals("needs_components", imageTool.getString("status"))
            assertFalse(imageTool.getBoolean("ready"))
            assertEquals(setOf("LLM", "VAE"), imageTool.getJSONArray("missingComponents").let { values ->
                (0 until values.length()).map { values.getString(it) }.toSet()
            })
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun settingsRpcRejectsUnknownToolsAndNonBooleanState() = runBlocking {
        val unknown = runCatching {
            operations.invoke("appTools.setEnabled", null, JSONObject().put("tool", "credentials").put("enabled", true))
        }.exceptionOrNull()
        val invalid = runCatching {
            operations.invoke("appTools.setEnabled", null, JSONObject().put("tool", "images_generate").put("enabled", "yes"))
        }.exceptionOrNull()

        assertEquals("APP_TOOL_UNKNOWN", unknown?.message)
        assertEquals("APP_TOOL_ENABLED_INVALID", invalid?.message)
        assertTrue(settings.agentImageGenerationToolEnabled.value)
    }

    @Test
    fun disabledModelToolsReturnExplicitRejectionsBeforeSessionDispatch() = runBlocking {
        settings.setAgentImageGenerationToolEnabled(false)
        settings.setAgentWebSearchEnabled(false)

        val image = runCatching { operations.invoke("images.generate", null, JSONObject()) }.exceptionOrNull()
        val search = runCatching { operations.invoke("research.webSearch", null, JSONObject()) }.exceptionOrNull()

        assertEquals("IMAGE_GENERATION_TOOL_DISABLED", image?.message)
        assertEquals("WEB_SEARCH_DISABLED", search?.message)
    }
}
