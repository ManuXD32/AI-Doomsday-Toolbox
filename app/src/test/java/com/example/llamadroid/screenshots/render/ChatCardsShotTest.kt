package com.example.llamadroid.screenshots.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import android.app.Application
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCardState
import com.example.llamadroid.chat.cards.map.ChatMapServices
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotRunOptions
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.ui.ai.llama.chat.ChatSettingsState
import com.example.llamadroid.ui.ai.llama.chat.ChatCardsSettingsRow
import com.example.llamadroid.ui.ai.llama.chat.LlamaMessageItem
import com.example.llamadroid.ui.ai.llama.chat.cards.ChatCardTypesGallery
import com.example.llamadroid.ui.ai.llama.chat.cards.ChatCardsUi
import com.example.llamadroid.ui.knowledge.NoEmbeddingModelDialogContent
import com.example.llamadroid.ui.settings.chat.ChatGlobalSettingsScreen
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.llamadroid.chat.cards.media.CardWeather
import com.example.llamadroid.chat.cards.media.CardWeatherClient
import com.example.llamadroid.chat.cards.media.CardWeatherDay
import com.example.llamadroid.chat.cards.media.ChatCardMedia
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Interactive chat cards in a real assistant bubble (and the settings row). Opt-in (`-Pscreenshots=true`);
 * review shots: `-Padt.shots.out=.agents/cards-shots -Padt.shots.profile=phone -Padt.shots.locale=en`
 * (PNG lands in `<out>/phone/en/<key>.png`). Skipped: gallery details (the dialog is private and needs a
 * ~90-field SdGeneratedImageMetadata). Gallery, chat settings and no-embedding dialog render their content composables inline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChatCardsShotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private lateinit var harness: ShotHarness
    private val profile: ShotProfile get() = ShotRunOptions.profileFilter ?: ShotProfile.PHONE
    private val locale: ShotLocale get() = ShotRunOptions.localeFilter ?: ShotLocale.EN

    @Before fun setUp() { harness = ShotHarness(compose, environment) }

    @After fun tearDown() { harness.close() }

    private fun card(json: String) = "```card\n$json\n```\n"

    private fun message(content: String) = LlamaMessageEntity(id = 9L, chatId = 5L, role = "assistant", content = content)

    @Composable
    private fun Bubble(content: String, states: Map<Long, Map<Int, String>> = emptyMap()) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            LlamaMessageItem(
                message = message(content),
                onRegenerate = {}, onEdit = {}, onRetry = {}, retryEnabled = true,
                onRetryTranscription = {}, onDiscardFailedMessage = {}, onDelete = {},
                cards = ChatCardsUi(states, true) { _, _, _, _ -> }
            )
        }
    }

    private fun shoot(
        key: String,
        seed: (Application) -> Unit = {},
        ready: (ComposeTestRule) -> Boolean = { true },
        body: @Composable () -> Unit
    ) {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(
            ShotSpec(key = key, route = "chat_cards/$key", profile = profile, locale = locale, seed = seed, ready = ready, content = body)
        )
        ShotOutput.save(
            ShotHarness.outRoot(), result.spec.profile, result.spec.locale, key, result.spec.route,
            trimBottom(result.bitmap), result.spec.substitute, result.captureMethod, result.millis
        )
    }

    /** Drops the empty rows under the last content row (keeps a 48 px margin); full-height content is untouched. */
    private fun trimBottom(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = bitmap.width
        val background = bitmap.getPixel(w - 1, bitmap.height - 1)
        val row = IntArray(w)
        var last = bitmap.height - 1
        while (last > 0) {
            bitmap.getPixels(row, 0, w, 0, last, w, 1)
            if (row.any { it != background }) break
            last--
        }
        val height = (last + 1 + 48).coerceAtMost(bitmap.height)
        return if (height >= bitmap.height) bitmap else android.graphics.Bitmap.createBitmap(bitmap, 0, 0, w, height)
    }

    private val choicesJson = card(
        """{"type":"choices","question":"Where should we go this weekend?","options":["Beach","Mountains","City break"],"allow_other":true}"""
    )
    private val choicesReply = "Happy to help plan it. Pick one:\n$choicesJson"

    @Test fun choices() = shoot("choices") { Bubble(choicesReply) }

    @Test fun form() = shoot("form") {
        Bubble(
            "Fill in the details:\n" + card(
                """{"type":"form","title":"Trip details","fields":[
                {"label":"Date","kind":"date"},
                {"label":"People","kind":"number"},
                {"label":"Transport","kind":"select","options":["Car","Train","Plane"]},
                {"label":"Pets","kind":"toggle"}]}"""
            )
        )
    }

    @Test fun answered() = shoot("answered") {
        Bubble(choicesReply, mapOf(9L to mapOf(0 to ChatCardState.encodeChoices(listOf("Mountains"), null))))
    }

    @Test fun display() = shoot("display") {
        Bubble(
            "Here is the summary.\n" +
                card("""{"type":"stats","items":[{"label":"Distance","value":"320 km","delta":"+12"},{"label":"Budget","value":"450 EUR"},{"label":"Days","value":"3"}]}""") +
                card("""{"type":"timeline","items":[{"time":"Fri 18:00","title":"Depart","detail":"Leave after work"},{"time":"Sat 10:00","title":"Hike","detail":"Easy trail"},{"time":"Sun 16:00","title":"Return"}]}""") +
                card("""{"type":"callout","tone":"warning","title":"Heads up","text":"Mountain roads may need chains in winter."}""")
        )
    }

    @Test fun chartLinks() = shoot("chart_links") {
        Bubble(
            "Costs and sources.\n" +
                card("""{"type":"chart","kind":"bar","labels":["Fuel","Hotel","Food","Tickets"],"series":[{"name":"Cost","data":[80,150,1200,60]}],"unit":"EUR"}""") +
                card("""{"type":"links","items":[{"url":"https://example.com/trails","title":"Trail guide","note":"Routes and maps"},{"url":"https://example.com/weather","title":"Weather forecast"}]}""") +
                card("""{"type":"followups","items":["Plan the budget","Suggest hotels","Pack list"]}""")
        )
    }

    @Test fun chartLine() = shoot("chart_line") {
        Bubble(
            "Monthly trend.\n" +
                card("""{"type":"chart","kind":"line","labels":["Jan","Feb","Mar","Apr","May"],"series":[{"name":"Income","data":[900,1100,1000,1400,1250]},{"name":"Costs","data":[700,750,900,800,1000]}],"unit":"EUR"}""")
        )
    }

    /**
     * Unit tests block the network on purpose (dead proxy in app/build.gradle.kts), so the real OSM tiles stored in
     * `.agents/cards-shots/tiles/{z}/{x}/{y}.png` are copied into the directory the map tile cache reads
     * (`<cacheDir>/chat_map_tiles/{z}_{x}_{y}.png`). Geocoding fails offline, so the places use their lat/lng.
     */
    private fun seedMapTiles(app: Application) {
        val src = File(ShotRunOptions.repoRoot, ".agents/cards-shots/tiles")
        assumeTrue("tile fixtures missing: $src", src.isDirectory)
        // The cache object is process-wide and remembers the first test's cacheDir; drop it so it re-reads this one.
        ChatMapServices::class.java.getDeclaredField("tiles").apply { isAccessible = true }.set(ChatMapServices, null)
        val dst = File(app.cacheDir, "chat_map_tiles").apply { mkdirs() }
        src.listFiles { f -> f.isDirectory }.orEmpty().forEach { z ->
            z.listFiles { f -> f.isDirectory }.orEmpty().forEach { x ->
                x.listFiles { f -> f.extension == "png" }.orEmpty().forEach { y ->
                    y.copyTo(File(dst, "${z.name}_${x.name}_${y.nameWithoutExtension}.png"), overwrite = true)
                }
            }
        }
    }

    @Test fun map() {
        val preview = RuntimeEnvironment.getApplication().getString(R.string.chat_card_map_preview_description)
        shoot("map", seed = ::seedMapTiles, ready = { it.onAllNodes(hasContentDescription(preview)).fetchSemanticsNodes().isNotEmpty() }) {
            Bubble(
                "Stops on the route.\n" + card(
                    """{"type":"map","title":"Castilla route","route":true,"places":[
                    {"name":"Madrid","lat":40.4168,"lng":-3.7038,"note":"Start","icon":"🏙️"},
                    {"name":"Toledo","lat":39.8628,"lng":-4.0273,"icon":"🏰"},
                    {"name":"Segovia","lat":40.9429,"lng":-4.1088,"icon":"🏛️"}]}"""
                )
            )
        }
    }

    // New rich types: no network in unit tests, so photos show the placeholder and weather its unavailable/loading state.
    @Test fun places() = shoot("places") {
        Bubble(
            "**Where to eat in Madrid**\n" + card(
                """{"type":"places","title":"Top restaurants","items":[
                {"name":"Casa Lucio","subtitle":"La Latina","description":"Classic huevos rotos and Castilian stews in a historic tavern.","tags":["Traditional","Eggs","Wine"],"price":"EUR 35","rating":4.5,"image":"Casa Lucio Madrid"},
                {"name":"Sobrino de Botin","subtitle":"Centro","description":"Oldest restaurant in the world, famous for roast suckling pig.","tags":["Historic","Roast"],"price":"EUR 55","rating":4.4},
                {"name":"Mercado San Miguel","subtitle":"Sol","description":"Covered market with dozens of tapas stalls.","tags":["Tapas","Market","Casual","Vegan"],"price":"EUR 20","rating":4.2},
                {"name":"Taberna La Bola","subtitle":"Opera","description":"Cocido madrileno cooked in clay pots since 1870.","tags":["Cocido"],"price":"EUR 30","rating":4.6}]}"""
            )
        )
    }

    /** Puts a realistic Cancun forecast into the weather client's in-memory cache (the network is blocked in unit tests). */
    private fun seedWeather(app: Application) {
        val days = listOf(
            Triple("2026-11-16", "⛅", 31.0 to 24.0), Triple("2026-11-17", "☀️", 32.0 to 25.0),
            Triple("2026-11-18", "🌦️", 30.0 to 24.0), Triple("2026-11-19", "☀️", 32.0 to 24.0),
            Triple("2026-11-20", "⛅", 31.0 to 23.0)
        ).mapIndexed { i, (d, e, t) -> CardWeatherDay(d, e, t.first, t.second, intArrayOf(10, 5, 40, 5, 15)[i]) }
        val weather = CardWeather(
            place = "Cancun", currentTempC = 29.0, feelsLikeC = 33.0, description = "Partly cloudy", emoji = "⛅",
            humidity = 74, windKmh = 14.0,
            hourlyTempC = listOf(25.0, 25.0, 24.0, 24.0, 24.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0, 30.0,
                31.0, 31.0, 31.0, 30.0, 30.0, 29.0, 28.0, 27.0, 26.0, 26.0, 25.0, 25.0),
            days = days
        )
        val client = CardWeatherClient(okhttp3.OkHttpClient(), "adt-shots", { null })
        val cache = CardWeatherClient::class.java.getDeclaredField("cache").apply { isAccessible = true }
            .get(client) as java.util.concurrent.ConcurrentHashMap<String, Any>
        val entryClass = Class.forName("com.example.llamadroid.chat.cards.media.CardWeatherClient\$Entry")
        val entry = entryClass.declaredConstructors.first().apply { isAccessible = true }
            .newInstance(System.currentTimeMillis(), weather)
        cache["cancun|5"] = entry
        ChatCardMedia::class.java.getDeclaredField("weather").apply { isAccessible = true }.set(ChatCardMedia, client)
    }

    @Test fun weatherBudget() = shoot("weather_budget", seed = ::seedWeather) {
        Bubble(
            "Trip estimate and forecast.\n" + card(
                """{"type":"budget","title":"Cancun week","currency":"USD","people":2,"cap":5000,"items":[
                {"label":"Flights","icon":"✈️","amount":760,"min":400,"max":1500,"note":"380 / person x 2"},
                {"label":"Hotel","icon":"🏨","amount":1190,"min":700,"max":2500,"note":"170 / night x 7"},
                {"label":"Food and drink","icon":"🍽️","amount":595,"min":300,"max":1200,"note":"85 / day x 7"},
                {"label":"Tours","icon":"🎟️","amount":500,"min":0,"max":900,"note":"500 / trip"},
                {"label":"Transfers","icon":"🚕","amount":220,"min":100,"max":400}]}"""
            ) + card("""{"type":"weather","location":"Cancun","days":5}""")
        )
    }

    @Test fun richReply() {
        val preview = RuntimeEnvironment.getApplication().getString(R.string.chat_card_map_preview_description)
        shoot("rich_reply", seed = ::seedMapTiles, ready = { it.onAllNodes(hasContentDescription(preview)).fetchSemanticsNodes().isNotEmpty() }) { RichReplyBubble() }
    }

    @Composable
    private fun RichReplyBubble() {
        Bubble(
            "**A weekend in Castilla**\nThree historic cities within two hours of each other.\n" +
                card("""{"type":"stats","items":[{"label":"Distance","value":"320 km","icon":"🚗"},{"label":"Budget","value":"450 EUR","icon":"💶"},{"label":"Days","value":"3","icon":"📅"}]}""") +
                card(
                    """{"type":"map","title":"Castilla route","route":true,"places":[
                    {"name":"Madrid","lat":40.4168,"lng":-3.7038,"note":"Start","icon":"🏙️"},
                    {"name":"Toledo","lat":39.8628,"lng":-4.0273,"icon":"🏰"},
                    {"name":"Segovia","lat":40.9429,"lng":-4.1088,"icon":"🏛️"}]}"""
                ) +
                card("""{"type":"callout","tone":"tip","title":"Tip","text":"Book Toledo on a weekday to avoid the crowds."}""") +
                card("""{"type":"followups","items":["Plan the budget","Suggest hotels"]}""")
        )
    }

    @Test fun multiQuestions() = shoot("multi_questions") {
        Bubble(
            "A few quick questions so I can plan it:\n" +
                card("""{"type":"choices","question":"Where should we go?","options":["Beach","Mountains","City break"]}""") +
                card("""{"type":"choices","question":"Which activities?","multi":true,"options":["Hiking","Museums","Food tours"]}""") +
                card("""{"type":"form","title":"Trip details","fields":[{"id":"d","label":"Date","kind":"date","required":true},{"id":"t","label":"Transport","kind":"select","options":["Car","Train","Plane"]},{"id":"p","label":"Pets","kind":"toggle"}]}""")
        )
    }

    @Test fun inputAndOmni() = shoot("input_omni") {
        Bubble(
            "Two quick things before I write the plan:\n" +
                card("""{"type":"choices","question":"If you had to pick one long weekend this autumn to travel with the whole family, including the grandparents who prefer short car trips and quiet places, which option fits best?","options":["A calm coastal town two hours away by car, with easy walks along the promenade","A mountain village with a spa hotel"]}""") +
                card("""{"type":"input","question":"What should the cover letter highlight about you?","placeholder":"Skills, achievements, the role you want","multiline":true}""") +
                card("""{"type":"omni","title":"Tip calculator","blocks":[{"kind":"number","id":"bill","label":"Bill","value":50,"unit":"€"},{"kind":"slider","id":"tip","label":"Tip","min":0,"max":30,"value":15,"unit":"%"},{"kind":"result","label":"Each pays","expr":"bill*(1+tip/100)","decimals":2,"unit":"€"}]}""") +
                card("""{"type":"omni","blocks":[{"kind":"keypad"},{"kind":"checklist","title":"Packing","items":["Passport","Charger","Snacks"]},{"kind":"timer","seconds":90},{"kind":"dice","count":2}]}""")
        )
    }

    @Test fun settingsToggle() = shoot("settings_toggle") {
        val state = remember { ChatSettingsState().apply { cardsEnabled = true } }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            with(state) { ChatCardsSettingsRow() }
        }
    }

    @Test fun chartKinds() = shoot("chart_kinds") {
        Bubble(
            "Four more ways to look at the same trip.\n" +
                card("""{"type":"chart","kind":"pie","labels":["Flights","Hotel","Food","Tours"],"series":[{"name":"Share","data":[760,1190,595,500]}],"unit":"USD"}""") +
                card("""{"type":"chart","kind":"hbar","labels":["Cancun","Tulum","Merida","Cozumel"],"series":[{"name":"Hotel / night","data":[170,140,90,155]}],"unit":"USD"}""") +
                card("""{"type":"chart","kind":"area","labels":["Mon","Tue","Wed","Thu","Fri","Sat","Sun"],"series":[{"name":"Steps","data":[6200,8400,7100,9800,5600,12100,10400]}]}""") +
                card("""{"type":"chart","kind":"scatter","x_label":"Price (USD)","y_label":"Rating","series":[{"name":"Stays","points":[[25,3.8],[60,4.0],[85,4.3],[140,4.4],[260,4.6],[420,4.8]]}]}""")
        )
    }

    @Test fun cardTypesGallery() = shoot("card_types_gallery") {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) { ChatCardTypesGallery(onClose = {}) }
    }

    @Test fun chatSettings() = shoot("chat_settings") {
        ChatGlobalSettingsScreen(rememberNavController())
    }

    @Test fun noEmbeddingDialog() = shoot("no_embedding_dialog") {
        Surface(Modifier.fillMaxWidth().padding(24.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(R.string.embchooser_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                NoEmbeddingModelDialogContent(onConfigured = {}, onOpenModelManager = {})
            }
        }
    }
}
