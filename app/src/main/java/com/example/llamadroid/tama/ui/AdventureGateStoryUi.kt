package com.example.llamadroid.tama.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.example.llamadroid.R
import com.example.llamadroid.tama.adventure.AdventureLore
import com.example.llamadroid.tama.data.TamaDialogTextCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateRepository
import com.example.llamadroid.tama.rpg.AdventureGateStory
import com.example.llamadroid.tama.rpg.AdventureGateWorldProgress
import com.example.llamadroid.tama.rpg.StoryBeat
import com.example.llamadroid.tama.rpg.StoryScene
import com.example.llamadroid.tama.rpg.StorySceneKind
import com.example.llamadroid.tama.rpg.StorySpeaker
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.random.Random

private val LumoGold = Color(0xFFFFC857)
private val LumoBlue = Color(0xFF77DDE7)

/** Persists which story scenes a pet has already watched (unlocks themselves are derived from progress). */
internal class AdventureGateStoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("adventure_gate_story", Context.MODE_PRIVATE)

    fun load(petId: String): Set<String> = prefs.getStringSet("seen_$petId", null)?.toSet().orEmpty()

    fun save(petId: String, ids: Set<String>) {
        prefs.edit { putStringSet("seen_$petId", ids.toSet()) }
    }
}

internal fun storyText(context: Context, key: String): String? =
    TamaDialogTextCatalog.localizedText(context, key, "").trim().takeIf { it.isNotEmpty() }

internal fun storyLanguage(context: Context): String =
    context.resources.configuration.locales[0]?.language ?: "en"

/** Builds a scene from the dialog workbook, falling back to the AdventureLore canon when a row is missing. */
internal fun buildStoryScene(context: Context, kind: StorySceneKind, worldId: String): StoryScene {
    val lore = AdventureLore.loreFor(storyLanguage(context))
    val fallback = when (kind) {
        StorySceneKind.INTRO -> listOfNotNull(lore.world(worldId)?.introBeat)
        StorySceneKind.OUTRO -> listOfNotNull(lore.world(worldId)?.outroBeat)
        StorySceneKind.EPILOGUE -> listOf(lore.regent.epilogue, lore.regent.postGameHook)
    }
    return StoryScene(kind, worldId, AdventureGateStory.resolveBeats(kind, worldId, { storyText(context, it) }, fallback))
}

// ---------------------------------------------------------------------------
// Lumo: a small pixel dream lantern drawn in Compose (no bitmap asset needed)
// ---------------------------------------------------------------------------

private val LumoPixels = listOf(
    "......f......",
    ".....fyf.....",
    "......o......",
    "....ooooo....",
    "...oggyggo...",
    "ww.ogkgkgo.ww",
    "ww.ogggggo.ww",
    ".w.ogggggo.w.",
    "...ogggggo...",
    "....ooooo....",
    "......o......"
)

private fun lumoPixelColor(code: Char): Color? = when (code) {
    'o' -> Color(0xFF4A2C0F)
    'g' -> Color(0xFFFFC857)
    'y' -> Color(0xFFFFF0B0)
    'w' -> Color(0xFF77DDE7)
    'f' -> Color(0xFFFF9F40)
    'k' -> Color(0xFF14101F)
    else -> null
}

@Composable
internal fun LumoLantern(modifier: Modifier = Modifier, width: Dp = 40.dp) {
    val description = stringResource(R.string.adventure_story_lumo_description)
    val glow by rememberInfiniteTransition(label = "lumoGlow").animateFloat(
        initialValue = 0.16f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "lumoGlowAlpha"
    )
    val columns = LumoPixels.first().length
    Canvas(
        modifier = modifier
            .size(width, width * LumoPixels.size / columns)
            .semantics { contentDescription = description }
    ) {
        val cell = size.width / columns
        drawCircle(
            color = LumoGold.copy(alpha = glow),
            radius = size.width * 0.5f,
            center = Offset(size.width / 2f, size.height * 0.5f)
        )
        LumoPixels.forEachIndexed { row, line ->
            line.forEachIndexed colLoop@{ col, code ->
                val color = lumoPixelColor(code) ?: return@colLoop
                drawRect(
                    color = color,
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell + 0.6f, cell + 0.6f)
                )
            }
        }
    }
}

private fun speakerColor(speaker: StorySpeaker): Color = when (speaker) {
    StorySpeaker.LUMO -> LumoGold
    StorySpeaker.ASTRA -> LumoBlue
    StorySpeaker.NARRATOR -> GateColorText.copy(alpha = 0.82f)
}

@Composable
private fun speakerName(speaker: StorySpeaker): String = stringResource(
    when (speaker) {
        StorySpeaker.LUMO -> R.string.adventure_story_speaker_lumo
        StorySpeaker.ASTRA -> R.string.adventure_story_speaker_astra
        StorySpeaker.NARRATOR -> R.string.adventure_story_speaker_narrator
    }
)

/** Lantern portrait (Lumo only) plus a bordered speech bubble; the text wraps and never clips. */
@Composable
internal fun LumoSpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    speaker: StorySpeaker = StorySpeaker.LUMO,
    showPortrait: Boolean = true
) {
    val tint = speakerColor(speaker)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (speaker == StorySpeaker.LUMO && showPortrait) {
            LumoLantern(modifier = Modifier.padding(top = 4.dp), width = 34.dp)
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = GateColorPanel,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, tint.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = speakerName(speaker),
                    color = tint,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = text,
                    color = GateColorText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Story scene (world intro / outro / epilogue)
// ---------------------------------------------------------------------------

@Composable
internal fun StorySceneDialog(scene: StoryScene, worldName: String?, onFinished: () -> Unit) {
    val total = scene.beats.size
    var shown by rememberSaveable(scene.id) { mutableIntStateOf(1) }
    val scroll = rememberScrollState()
    LaunchedEffect(shown) { scroll.animateScrollBy(100_000f) }
    val last = shown >= total
    AlertDialog(
        onDismissRequest = onFinished,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = GateColorPanel,
        titleContentColor = GateColorAccent,
        textContentColor = GateColorText,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        when (scene.kind) {
                            StorySceneKind.INTRO -> R.string.adventure_story_kind_intro
                            StorySceneKind.OUTRO -> R.string.adventure_story_kind_outro
                            StorySceneKind.EPILOGUE -> R.string.adventure_story_kind_epilogue
                        }
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (scene.kind != StorySceneKind.EPILOGUE && worldName != null) {
                    Text(
                        text = worldName,
                        color = GateColorText.copy(alpha = 0.78f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(R.string.adventure_story_progress, shown, total),
                    color = GateColorText.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scene.beats.take(shown).forEachIndexed { index, beat ->
                    LumoSpeechBubble(
                        text = beat.text,
                        speaker = beat.speaker,
                        showPortrait = index == 0 || scene.beats[index - 1].speaker != beat.speaker,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(colors = gateTextButtonColors(), onClick = { if (last) onFinished() else shown += 1 }) {
                Text(stringResource(if (last) R.string.action_done else R.string.action_next), fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = if (last) null else ({
            TextButton(colors = gateTextButtonColors(), onClick = onFinished) {
                Text(stringResource(R.string.action_skip), fontFamily = FontFamily.Monospace)
            }
        })
    )
}

// ---------------------------------------------------------------------------
// Overlay: scenes, idle quips and the journal, hosted inside the Gate hub Box
// ---------------------------------------------------------------------------

/**
 * Shows pending story scenes, Lumo's idle quips on the world map, and (when asked) the journal.
 * [hubVisible] must be false while a battle, shop or any other dialog owns the screen.
 */
@Composable
internal fun BoxScope.AdventureGateStoryOverlay(
    petId: String?,
    repository: AdventureGateRepository,
    selectedWorldId: String,
    hubVisible: Boolean,
    showJournal: Boolean,
    onDismissJournal: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { AdventureGateStoryStore(context) }
    val progressFlow: Flow<List<AdventureGateWorldProgress>?> = remember(petId, repository) {
        if (petId == null) flowOf(null) else repository.observeProgress(petId)
    }
    val progress by progressFlow.collectAsState(initial = null)
    var seen by remember(petId) { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(petId, progress != null) {
        val id = petId
        val rows = progress
        if (id != null && rows != null && seen == null) {
            var stored = store.load(id)
            if (!AdventureGateStory.isBaselined(stored)) {
                stored = AdventureGateStory.markBaselined(stored + AdventureGateStory.baselineSeen(rows))
                store.save(id, stored)
            }
            seen = AdventureGateStory.visibleSeen(stored)
        }
    }

    fun markSeen(sceneId: String) {
        val id = petId ?: return
        val updated = (seen ?: emptySet()) + sceneId
        seen = updated
        store.save(id, AdventureGateStory.markBaselined(updated))
    }

    val rows = progress
    val seenNow = seen
    val pending = if (hubVisible && !showJournal && rows != null && seenNow != null) {
        AdventureGateStory.nextPendingScene(selectedWorldId, rows, seenNow)
    } else {
        null
    }

    pending?.let { scene ->
        val built = remember(scene.id) { buildStoryScene(context, scene.kind, scene.worldId) }
        if (built.beats.isEmpty()) {
            LaunchedEffect(scene.id) { markSeen(scene.id) }
        } else {
            val worldName = remember(scene.worldId) {
                runCatching {
                    TamaDialogTextCatalog.localizedResource(context, AdventureGateCatalog.world(scene.worldId).nameRes)
                }.getOrNull()
            }
            StorySceneDialog(scene = built, worldName = worldName, onFinished = { markSeen(scene.id) })
        }
    }

    // Idle quip on the map: appears shortly after a world is shown, tap to dismiss.
    var quip by remember { mutableStateOf<String?>(null) }
    val quipReady = hubVisible && !showJournal && pending == null && seenNow != null
    val postgame = rows?.let(AdventureGateStory::epilogueUnlocked) == true
    LaunchedEffect(selectedWorldId, quipReady) {
        if (quipReady) {
            delay(2_000)
            val key = if (postgame && Random.nextInt(4) == 0) {
                AdventureGateStory.POSTGAME_KEY
            } else {
                AdventureGateStory.idleKey(Random.nextInt(AdventureGateStory.IDLE_QUIP_COUNT))
            }
            quip = storyText(context, key)
            delay(6_500)
        }
        quip = null
    }
    quip?.let { text ->
        LumoSpeechBubble(
            text = text,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clickable { quip = null }
        )
    }

    if (showJournal) {
        AdventureGateJournalDialog(
            progressRows = rows.orEmpty(),
            seen = seenNow.orEmpty(),
            petId = petId,
            onDismiss = onDismissJournal
        )
    }
}

// ---------------------------------------------------------------------------
// Short Lumo lines used by the existing phase / reward dialogs
// ---------------------------------------------------------------------------

/** Lumo's bubble before a battle: the boss line on phases 7 and 15, an idle quip otherwise. */
@Composable
internal fun LumoBeforeBattleLine(worldId: String, phase: Int, modifier: Modifier = Modifier) {
    if (AdventureGateStory.prefix(worldId) == null) return
    val context = LocalContext.current
    val text = remember(worldId, phase) { storyText(context, AdventureGateStory.beforeBattleKey(worldId, phase)) } ?: return
    LumoSpeechBubble(text = text, modifier = modifier.fillMaxWidth())
}

/** Lumo's comment on the reward screen: boss victory lines on phases 7 and 15, comfort lines after a defeat. */
@Composable
internal fun LumoBattleEndLine(victory: Boolean, worldId: String, phase: Int, modifier: Modifier = Modifier) {
    if (AdventureGateStory.prefix(worldId) == null) return
    val context = LocalContext.current
    val defeatIndex = remember(worldId, phase, victory) { Random.nextInt(AdventureGateStory.DEFEAT_LINE_COUNT) }
    val key = if (victory) AdventureGateStory.afterVictoryKey(worldId, phase) else AdventureGateStory.defeatKey(defeatIndex)
    val text = remember(key) { key?.let { storyText(context, it) } } ?: return
    LumoSpeechBubble(text = text, modifier = modifier.fillMaxWidth())
}

internal fun StoryBeat.journalLine(speakerLabel: String): String =
    if (speaker == StorySpeaker.NARRATOR) text else "$speakerLabel: $text"
