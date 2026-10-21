package com.example.llamadroid.screenshots.render

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageKind
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotResult
import com.example.llamadroid.screenshots.harness.ShotSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spike for the real-screen screenshot renderer: opt-in (`-Pscreenshots=true`), see
 * docs/screenshot-renderer.md. Renders the real LlamaApp on the JVM and checks the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SpikeShotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private lateinit var harness: ShotHarness

    @Before fun setUp() {
        harness = ShotHarness(compose, environment)
    }

    @After fun tearDown() {
        harness.close()
    }

    @Test fun dashboardPhoneEnglish() {
        val spec = ShotSpec(
            key = "dashboard", route = "dashboard", profile = ShotProfile.PHONE, locale = ShotLocale.EN,
            ready = { ShotHarness.textVisible(it, string(R.string.soft_studio_home_new_chat)) }
        )
        val result = harness.shoot(spec, alsoDecor = true)
        val context = RuntimeEnvironment.getApplication()
        assertEquals("en", context.resources.configuration.locales[0].language)
        assertShot(result, 1080, 1920)
        assertTrue("new-chat label present", ShotHarness.textVisible(compose, string(R.string.soft_studio_home_new_chat)))
        assertWelcomeAbsent()
        save(result)
    }

    @Test fun nativeChatTabletTenSpanish() {
        var chatId = 0L
        var serverId = 0L
        val spec = ShotSpec(
            key = "native_chat_delegation", route = "llama_chat/{chat}/{server}", profile = ShotProfile.TABLET10,
            locale = ShotLocale.ES,
            seed = { app ->
                runBlocking {
                    val db = AppDatabase.getDatabase(app)
                    serverId = db.llamaServerDao().insertServer(
                        LlamaServerEntity(name = "Pocket server", host = "127.0.0.1", port = 8080, modelName = "Qwen3-4B-Q4_K_M")
                    )
                    chatId = db.llamaChatDao().insertChat(LlamaChatEntity(title = USER_TITLE, serverId = serverId))
                    val t0 = System.currentTimeMillis() - 600_000
                    db.llamaMessageDao().insertMessage(LlamaMessageEntity(chatId = chatId, role = "user", content = USER_TEXT, timestamp = t0))
                    db.llamaMessageDao().insertMessage(
                        LlamaMessageEntity(chatId = chatId, role = "assistant", content = ASSISTANT_TEXT, timestamp = t0 + 20_000, completionTokens = 64, tps = 11.5)
                    )
                    db.llamaMessageDao().insertMessage(
                        LlamaMessageEntity(
                            chatId = chatId, role = "user", content = "[DeepSeek task finished] Tarea terminada: informe de agua listo.",
                            timestamp = t0 + 120_000, kind = LlamaMessageKind.DELEGATION_NOTICE
                        )
                    )
                }
            },
            ready = { ShotHarness.textVisible(it, ASSISTANT_TEXT.take(24)) }
        )
        // The route needs the seeded ids: build it lazily via a seed-first spec.
        val result = harness.shoot(spec.copy(route = "llama_chat/1/1"), alsoDecor = true)
        assertEquals(1L, chatId); assertEquals(1L, serverId)
        assertEquals("es", RuntimeEnvironment.getApplication().resources.configuration.locales[0].language)
        assertShot(result, 1600, 2560)
        val notice = string(R.string.native_chat_compaction_notice_finished)
        assertTrue("localized notice '$notice' visible", ShotHarness.textVisible(compose, notice))
        assertNotEquals("Spanish differs from English", englishString(R.string.native_chat_compaction_notice_finished), notice)
        assertTrue("user text visible", ShotHarness.textVisible(compose, USER_TEXT.take(24)))
        assertTrue("assistant text visible", ShotHarness.textVisible(compose, ASSISTANT_TEXT.take(24)))
        assertWelcomeAbsent()
        save(result)
    }

    private fun string(res: Int): String = RuntimeEnvironment.getApplication().getString(res)

    private fun englishString(res: Int): String {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
        return app.createConfigurationContext(config).getString(res)
    }

    private fun assertWelcomeAbsent() {
        assertFalse(ShotHarness.textVisible(compose, string(R.string.welcome_title)))
        assertFalse(ShotHarness.textVisible(compose, string(R.string.welcome_get_started)))
    }

    private fun assertShot(result: ShotResult, width: Int, height: Int) {
        val bitmap = result.bitmap
        assertEquals("width", width, bitmap.width)
        assertEquals("height", height, bitmap.height)
        assertTrue("settled before deadline", result.settled)
        val colors = HashSet<Int>()
        val stepX = maxOf(1, width / 64)
        val stepY = maxOf(1, height / 128)
        for (y in 0 until height step stepY) for (x in 0 until width step stepX) colors += bitmap.getPixel(x, y)
        assertTrue("not blank: ${colors.size} distinct sampled colors", colors.size >= 64)
        val png = ShotOutput.pngBytes(bitmap)
        val webp = ShotOutput.webpBytes(bitmap)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(webp, 0, webp.size)
        assertEquals(width, decoded.width); assertEquals(height, decoded.height)
        assertTrue("webp (${webp.size}) smaller than png (${png.size})", webp.size < png.size)
        println("SPIKE ${result.spec.key} ${result.spec.profile.key}/${result.spec.locale.tag}: ${result.millis} ms, " +
            "png=${png.size} webp=${webp.size} colors>=${colors.size} capture=${result.captureMethod}")
        result.decorBitmap?.let { decor ->
            val decorColors = HashSet<Int>()
            for (y in 0 until minOf(height, decor.height) step stepY) for (x in 0 until minOf(width, decor.width) step stepX) decorColors += decor.getPixel(x, y)
            println("SPIKE decor-view capture ${decor.width}x${decor.height} colors>=${decorColors.size} " +
                "(captureToImage colors>=${colors.size})")
            val sameOut = ShotHarness.outRoot().resolve("spike-decor").apply { mkdirs() }
            sameOut.resolve("${result.spec.key}_${result.spec.profile.key}_${result.spec.locale.tag}_decor.png")
                .writeBytes(ShotOutput.pngBytes(decor))
        }
    }

    private fun save(result: ShotResult) {
        val saved = ShotOutput.save(
            ShotHarness.outRoot(), result.spec.profile, result.spec.locale, result.spec.key, result.spec.route,
            result.bitmap, result.spec.substitute, result.captureMethod, result.millis
        )
        println("SPIKE saved ${saved.png.absolutePath}")
    }

    private companion object {
        const val USER_TITLE = "Agua potable en una emergencia"
        const val USER_TEXT = "Como puedo purificar agua de un rio si no tengo filtro?"
        const val ASSISTANT_TEXT = "Hierve el agua durante al menos un minuto antes de beberla, y filtrala antes con una tela limpia."
    }
}
