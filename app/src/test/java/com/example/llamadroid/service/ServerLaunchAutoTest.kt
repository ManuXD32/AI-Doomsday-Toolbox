package com.example.llamadroid.service

import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.repository.LocalLlamaServerRepository
import com.example.llamadroid.data.repository.easyChatAllowedTools
import com.example.llamadroid.data.repository.markedDefaultsJson
import com.example.llamadroid.data.repository.normalizedManagedToolDefaults
import com.example.llamadroid.ui.ai.llama.chat.ChatSettingsState
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Server editor round trip, Auto launch temperature and the "Keep reasoning" launch flags. */
@RunWith(RobolectricTestRunner::class)
class ServerLaunchAutoTest {
    private lateinit var database: AppDatabase
    private lateinit var repo: LocalLlamaServerRepository

    private val rich = LlamaServerLaunchProfile(
        modelPath = "/m/qwen.gguf",
        mmprojPath = "/m/mmproj.gguf",
        visionEnabled = true,
        contextSize = 65536,
        threads = 6,
        batchSize = 256,
        temperature = 0.6f,
        speculativeEnabled = true,
        speculativeMode = LlamaSpeculativeMode.DRAFT_SIMPLE.flagValue,
        draftModelPath = "/m/draft.gguf",
        reasoningPreserve = false
    )
    private val chatDefaults =
        """{"temperature":0.6,"top_k":20,"top_p":0.95,"enable_thinking":false,"tools_enabled":true}"""

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = LocalLlamaServerRepository(context, database)
    }

    @After fun tearDown() { database.close() }

    private fun assertRich(p: LlamaServerLaunchProfile) {
        assertEquals("/m/qwen.gguf", p.modelPath)
        assertEquals("/m/mmproj.gguf", p.mmprojPath)
        assertTrue(p.visionEnabled)
        assertEquals(65536, p.contextSize)
        assertEquals(6, p.threads)
        assertEquals(256, p.batchSize)
        assertEquals(0.6f, p.temperature)
        assertTrue(p.speculativeEnabled)
        assertEquals("/m/draft.gguf", p.draftModelPath)
        assertFalse(p.reasoningPreserve)
    }

    @Test fun `editor round trip keeps launch options and chat defaults`() = runBlocking {
        val ids = repo.createLocalServer("S", rich, port = 51101, defaultApiParams = markedDefaultsJson(chatDefaults))
        repo.updateLocalServerProfile(ids.cardId, rich, defaultApiParams = markedDefaultsJson(chatDefaults))

        // Reopen: saved command -> draft repository -> capture (what the editor saves again).
        val card = database.llamaServerCardDao().getCard(ids.cardId)!!
        val seed = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)!!.launchProfile()
        assertRich(seed)
        val draft = SettingsRepository(RuntimeEnvironment.getApplication(), "test_server_draft", isProfileDraft = true)
        LlamaServerLaunchProfile.restore(seed, draft)
        assertRich(LlamaServerLaunchProfile.capture(draft))

        val stored = database.llamaServerDao().getManagedServer(ids.cardId)!!.defaultApiParams
        val state = ChatSettingsState()
        state.loadFromDefaults(normalizedManagedToolDefaults(stored))
        val json = JsonParser.parseString(state.buildApiParamsJson()).asJsonObject
        assertEquals(0.6, json["temperature"].asDouble, 1e-4)
        assertEquals(20, json["top_k"].asInt)
        assertEquals(0.95, json["top_p"].asDouble, 1e-4)
        assertFalse(json["enable_thinking"].asBoolean)
        assertTrue(json["tools_enabled"].asBoolean)
    }

    @Test fun `legacy unmarked defaults only reset tool keys`() {
        // Unmarked JSON whose tool block is the legacy all-true ceiling must keep the samplers.
        val legacy = JsonParser.parseString(chatDefaults).asJsonObject
        easyChatAllowedTools().toParamMap().forEach { (k, v) ->
            when (v) {
                is Boolean -> legacy.addProperty(k, v)
                is Number -> legacy.addProperty(k, v)
                is String -> legacy.addProperty(k, v)
                else -> Unit
            }
        }
        val out = JsonParser.parseString(normalizedManagedToolDefaults(legacy.toString())).asJsonObject
        assertEquals(0.6, out["temperature"].asDouble, 1e-4)
        assertEquals(20, out["top_k"].asInt)
    }

    @Test fun `auto temperature omits --temp and a set value is clean`() {
        val controller = ProcessController()
        val auto = controller.getCommand("/bin/llama-server", LlamaConfig(modelPath = "/m/a.gguf"))
        assertFalse(auto.contains("--temp"))
        val set = controller.getCommand("/bin/llama-server", LlamaConfig(modelPath = "/m/a.gguf", temperature = 0.6000001f))
        assertEquals("0.6", set[set.indexOf("--temp") + 1])

        val draft = SettingsRepository(RuntimeEnvironment.getApplication(), "test_temp_draft", isProfileDraft = true)
        draft.setTemperature(-1f)
        assertNull(LlamaServerLaunchProfile.capture(draft).temperature)
        draft.setTemperature(0.6f)
        assertEquals(0.6f, LlamaServerLaunchProfile.capture(draft).temperature)
        // Auto survives encode/decode as Auto, an explicit value as itself.
        assertNull(LlamaServerLaunchProfile.decode(LlamaServerLaunchProfile.encode(LlamaServerLaunchProfile()))!!.temperature)
        assertEquals(0.6f, LlamaServerLaunchProfile.decode(LlamaServerLaunchProfile.encode(rich))!!.temperature)
    }

    @Test fun `temperature template placeholder is dropped with its flag when auto`() {
        val controller = ProcessController()
        val auto = controller.renderCommandTemplate(
            "{binary} -m {model} --temp {temperature} --port {port}", "/bin/llama-server",
            LlamaConfig(modelPath = "/m/a.gguf", port = 8080)
        )
        assertFalse(auto.contains("--temp"))
        assertTrue(auto.contains("--port"))
        val set = controller.renderCommandTemplate(
            "{binary} -m {model} --temp {temperature}", "/bin/llama-server",
            LlamaConfig(modelPath = "/m/a.gguf", temperature = 0.6f)
        )
        assertEquals("0.6", set[set.indexOf("--temp") + 1])
    }

    private val caps = LlamaBinaryCapabilities(
        setOf("--reasoning-preserve", "--no-reasoning-preserve", "--chat-template-file")
    )

    @Test fun `reasoning flags follow the option and binary support`() {
        val controller = ProcessController()
        fun cmd(c: LlamaConfig, k: LlamaBinaryCapabilities? = caps) = controller.getCommand("/bin/llama-server", c, k)
        val base = LlamaConfig(modelPath = "/m/a.gguf")
        assertTrue(cmd(base.copy(reasoningPreserve = true)).contains("--reasoning-preserve"))
        assertTrue(cmd(base.copy(reasoningPreserve = false)).contains("--no-reasoning-preserve"))
        assertFalse(cmd(base).contains("--reasoning-preserve"))
        assertFalse(cmd(base.copy(reasoningPreserve = true), LlamaBinaryCapabilities(emptySet()))
            .contains("--reasoning-preserve"))
        assertFalse(cmd(base.copy(reasoningPreserve = true), null).contains("--reasoning-preserve"))
        assertFalse(cmd(base.copy(reasoningPreserve = true, customFlags = "--no-reasoning-preserve"))
            .contains("--reasoning-preserve"))
    }

    @Test fun `chat template file is skipped when custom flags set a template`() {
        val controller = ProcessController()
        val base = LlamaConfig(modelPath = "/m/a.gguf", reasoningPreserve = true, chatTemplateFile = "/t/patched.jinja")
        val with = controller.getCommand("/bin/llama-server", base, caps)
        assertEquals("/t/patched.jinja", with[with.indexOf("--chat-template-file") + 1])
        for (flags in listOf("--chat-template chatml", "--chat-template-file /x.jinja")) {
            val args = controller.getCommand("/bin/llama-server", base.copy(customFlags = flags), caps)
            assertFalse(flags, args.contains("/t/patched.jinja"))
        }
        val off = controller.getCommand("/bin/llama-server", base.copy(reasoningPreserve = false), caps)
        assertFalse(off.contains("/t/patched.jinja"))
    }
}
