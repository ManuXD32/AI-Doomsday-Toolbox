package com.example.llamadroid.screenshots.catalog

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.easy.EasyModelKind
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.screenshots.fixtures.ChatFixtures
import com.example.llamadroid.screenshots.fixtures.DemoText
import com.example.llamadroid.screenshots.fixtures.ModelFixtures
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.fixtures.pick
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.service.bubble.ChatBubbleView
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.shimeji.ShimejiClip
import com.example.llamadroid.tama.shimeji.ShimejiSpriteSheet
import com.example.llamadroid.tama.shimeji.ShimejiView
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.bubble.ChatBubbleWindowContent
import com.example.llamadroid.ui.easy.EasyModelsScreen
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * README-only renders: Easy mode (chat tab, models screen), the floating bubble chat and the Shimeji
 * overlay. Easy mode state is global (shared flow), so [reset] must run after every test.
 */
object ReadmeShotCatalog {
    val keys = listOf("readme_easy_chat", "readme_easy_models", "readme_bubble", "readme_shimeji")

    private const val HOUR = 3_600_000L

    private fun visible(text: String): (androidx.compose.ui.test.junit4.ComposeTestRule) -> Boolean =
        { ShotHarness.textVisible(it, text) }

    /** Easy mode on, intro seen, the Qwen chat model chosen (its file is inserted by [ModelFixtures.insert]). */
    private fun enableEasy(app: Application) {
        ModelFixtures.insert(app, ModelFixtures.llms)
        val settings = EasyModeSettings(app)
        settings.introSeen = true
        settings.enabled = true
        val qwen = File(app.filesDir, "models/${ModelFixtures.QWEN_FILE}")
        settings.chatModelPath = qwen.absolutePath
    }

    /** Easy chats (`easyMode = true`) with a short exchange each, so the recent list looks used. */
    private fun seedEasyChats(app: Application, l: ShotLocale) = runBlocking {
        val t = DemoText.ChatTexts(l)
        val db = AppDatabase.getDatabase(app)
        val now = System.currentTimeMillis()
        val titles = listOf(t.title) + t.otherChats.take(2)
        titles.forEachIndexed { index, title ->
            val at = now - (index * 7 + 1) * HOUR
            val id = db.llamaChatDao().insertChat(LlamaChatEntity(title = title, lastModified = at, easyMode = true))
            db.llamaMessageDao().insertMessage(LlamaMessageEntity(chatId = id, role = "user", content = if (index == 0) t.userWater else title, timestamp = at - 60_000))
            db.llamaMessageDao().insertMessage(LlamaMessageEntity(chatId = id, role = "assistant", content = if (index == 0) t.waterAnswer else "…", timestamp = at))
        }
    }

    /** Resets the process-wide Easy mode state so later tests see the advanced shell. */
    fun reset(app: Application) {
        runCatching {
            val settings = EasyModeSettings(app)
            settings.enabled = false
            settings.chatModelPath = ""
        }
    }

    fun spec(key: String, profile: ShotProfile, locale: ShotLocale, ctx: GuideShotContext): ShotSpec {
        val t = { DemoText.ChatTexts(locale) }
        return when (key) {
            "readme_easy_chat" -> ShotSpec(
                key = key, route = "dashboard", profile = profile, locale = locale,
                seed = { app -> enableEasy(app); seedEasyChats(app, locale) },
                ready = { rule -> visible(t().title)(rule) && visible(ModelFixtures.QWEN_FILE.substringBefore('-'))(rule) }
            )
            "readme_easy_models" -> ShotSpec(
                key = key, route = "easy_models", profile = profile, locale = locale,
                seed = { app -> enableEasy(app) },
                ready = visible("Qwen3"),
                content = {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        EasyModelsScreen(initialKind = EasyModelKind.CHAT, onBack = {})
                    }
                }
            )
            "readme_bubble" -> ShotSpec(
                key = key, route = "dashboard", profile = profile, locale = locale,
                seed = { app -> homeSeed(app, locale, ctx) },
                ready = visible("213 L"),
                content = { BubbleContent(ctx) }
            )
            "readme_shimeji" -> ShotSpec(
                key = key, route = "dashboard", profile = profile, locale = locale,
                seed = { app -> homeSeed(app, locale, ctx); TamaFixtures.seedHealthyPet(app) },
                ready = visible("Qwen3 4B"),
                content = { ShimejiContent(locale) }
            )
            else -> error("No README shot for '$key'")
        }
    }

    /** The advanced Home exactly as `preview_home` seeds it (running server, pinned chat, models, gallery). */
    private fun homeSeed(app: Application, locale: ShotLocale, ctx: GuideShotContext) {
        checkNotNull(GuideShotCatalog.entries["preview_home"]).seed(app, locale, ctx)
    }

    @Composable
    private fun HomeBackdrop() {
        LlamaApp(
            pendingNavigationRoute = ExternalRouteResolution.Navigate("dashboard"),
            allowDailySupportPrompt = false,
            allowAutomaticWalkthrough = false
        )
    }

    @Composable
    private fun BubbleContent(ctx: GuideShotContext) {
        val chat = checkNotNull(ctx.chat) { "bubble shot needs the seeded native chat" }
        Box(Modifier.fillMaxSize()) {
            HomeBackdrop()
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)))
            val shape = RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.9f).fillMaxHeight(0.6f)
                    .shadow(16.dp, shape).clip(shape),
                color = MaterialTheme.colorScheme.surface
            ) {
                ChatBubbleWindowContent(chatId = chat.chatId, serverId = chat.serverRowId, onClose = {}, onCloseBubble = {})
            }
            val accent = MaterialTheme.colorScheme.primary.toArgb()
            AndroidView(
                factory = { context -> ChatBubbleView(context).apply { accentColor = accent; showDot = true } },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 8.dp).size(56.dp)
            )
        }
    }

    @Composable
    private fun ShimejiContent(locale: ShotLocale) {
        val context = LocalContext.current
        val art = remember {
            ShimejiSpriteSheet.load(context, PetSpeciesLine.DRAGON, GrowthStage.ADULT).let {
                it.clip(ShimejiClip.WALK) ?: it.clip(ShimejiClip.IDLE)
            }
        }
        val line = remember(locale) { locale.pick("Hi! Want to chat?", "¡Hola! ¿Charlamos?") }
        Box(Modifier.fillMaxSize()) {
            HomeBackdrop()
            AndroidView(
                factory = { ctx ->
                    ShimejiView(ctx).apply {
                        setSpriteSizePx((128 * ctx.resources.displayMetrics.density).toInt())
                        setFrame(art, 1, false)
                        setBubble(line)
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 120.dp, bottom = 100.dp)
            )
        }
    }
}
