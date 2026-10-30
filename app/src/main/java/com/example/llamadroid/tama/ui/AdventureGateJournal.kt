package com.example.llamadroid.tama.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.adventure.AdventureChronicleLink
import com.example.llamadroid.tama.adventure.AdventureChronicleStore
import com.example.llamadroid.tama.adventure.AdventureLore
import com.example.llamadroid.tama.adventure.AdventureOutcome
import com.example.llamadroid.tama.adventure.ChronicleLog
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.adventure.ResolvedLore
import com.example.llamadroid.tama.data.TamaDialogTextCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateStory
import com.example.llamadroid.tama.rpg.AdventureGateWorldProgress
import com.example.llamadroid.tama.rpg.StorySceneKind
import com.example.llamadroid.tama.rpg.StorySpeaker
import java.text.DateFormat
import java.util.Date
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

/** One Journal row. A null [body] means the page is still locked. */
private data class JournalEntry(
    @StringRes val titleRes: Int,
    val titleArg: Int? = null,
    val body: String?,
    /** Free-form title (Dream Chronicle rows); wins over [titleRes] when set. */
    val titleText: String? = null
)

private data class JournalSection(val title: String, val entries: List<JournalEntry>)

private data class JournalContent(
    val sections: List<JournalSection>,
    val unlocked: Int,
    val total: Int,
    val hasChronicle: Boolean = false
)

private fun speakerLabelRes(speaker: StorySpeaker): Int = when (speaker) {
    StorySpeaker.LUMO -> R.string.adventure_story_speaker_lumo
    StorySpeaker.ASTRA -> R.string.adventure_story_speaker_astra
    StorySpeaker.NARRATOR -> R.string.adventure_story_speaker_narrator
}

private fun sceneText(context: Context, kind: StorySceneKind, worldId: String): String =
    buildStoryScene(context, kind, worldId).beats.joinToString("\n\n") { beat ->
        if (kind == StorySceneKind.EPILOGUE) beat.journalLine(context.getString(speakerLabelRes(beat.speaker))) else beat.text
    }

private fun buildJournal(
    context: Context,
    progressRows: List<AdventureGateWorldProgress>,
    seen: Set<String>,
    chronicle: ChronicleLog = ChronicleLog()
): JournalContent {
    val lore = AdventureLore.loreFor(storyLanguage(context))
    val byWorld = progressRows.associateBy { it.worldId }
    var unlocked = 0
    var total = 0
    val sections = ArrayList<JournalSection>()

    fun entry(
        @StringRes titleRes: Int,
        titleArg: Int? = null,
        open: Boolean,
        body: () -> String?
    ): JournalEntry {
        total += 1
        val text = if (open) body()?.takeIf { it.isNotBlank() } else null
        if (text != null) unlocked += 1
        return JournalEntry(titleRes, titleArg, text)
    }

    for (worldId in AdventureGateStory.worldIds) {
        val world = AdventureGateCatalog.world(worldId)
        val unlocks = AdventureGateStory.worldUnlocks(worldId, byWorld[worldId], seen)
        val fragments = lore.world(worldId)?.journalFragments.orEmpty()
        fun reveal(phaseNumber: Int): String? = world.phases.getOrNull(phaseNumber - 1)?.bossRevealRes
            ?.let { runCatching { TamaDialogTextCatalog.localizedResource(context, it) }.getOrNull() }

        val entries = listOf(
            entry(R.string.adventure_story_entry_intro, open = unlocks.introUnlocked) {
                sceneText(context, StorySceneKind.INTRO, worldId)
            },
            entry(R.string.adventure_story_entry_mid_boss, open = unlocks.midBossRevealed) {
                reveal(AdventureGateStory.MID_BOSS_PHASE)
            },
            entry(R.string.adventure_story_entry_fragment, 1, open = unlocks.fragmentCount >= 1) { fragments.getOrNull(0) },
            entry(R.string.adventure_story_entry_final_boss, open = unlocks.finalBossRevealed) {
                reveal(AdventureGateStory.FINAL_BOSS_PHASE)
            },
            entry(R.string.adventure_story_entry_fragment, 2, open = unlocks.fragmentCount >= 2) { fragments.getOrNull(1) },
            entry(R.string.adventure_story_entry_outro, open = unlocks.outroUnlocked) {
                sceneText(context, StorySceneKind.OUTRO, worldId)
            }
        )
        sections += JournalSection(
            title = runCatching { TamaDialogTextCatalog.localizedResource(context, world.nameRes) }.getOrDefault(worldId),
            entries = entries
        )
    }

    val epilogueOpen = AdventureGateStory.epilogueUnlocked(progressRows)
    sections += JournalSection(
        title = context.getString(R.string.adventure_story_kind_epilogue),
        entries = listOf(
            entry(R.string.adventure_story_entry_epilogue, open = epilogueOpen) {
                sceneText(context, StorySceneKind.EPILOGUE, AdventureGateStory.FINAL_WORLD_ID)
            },
            entry(R.string.adventure_story_entry_hook, open = epilogueOpen) {
                storyText(context, AdventureGateStory.POSTGAME_KEY) ?: lore.regent.postGameHook
            }
        )
    )
    buildChronicleSection(context, lore, chronicle, AdventureGateStory.epilogueUnlocked(progressRows))?.let { sections += it }
    return JournalContent(sections, unlocked, total, hasChronicle = chronicle.entries.isNotEmpty())
}

/** Dream Chronicle: finished LLM runs (newest first), the lantern spark tally and unlocked memory fragments. */
private fun buildChronicleSection(
    context: Context,
    lore: ResolvedLore,
    log: ChronicleLog,
    regentBeaten: Boolean
): JournalSection? {
    if (log.entries.isEmpty()) return null
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val entries = ArrayList<JournalEntry>()
    for (run in log.entries.asReversed().take(MAX_CHRONICLE_RUNS)) {
        val dungeon = runCatching { DungeonType.valueOf(run.dungeon) }.getOrNull()
        val dungeonTitle = dungeon?.let { lore.dungeon(it)?.title } ?: run.dungeon
        val outcome = context.getString(
            if (run.outcome == AdventureOutcome.VICTORY) R.string.adventure_result_victory else R.string.adventure_result_carried_back
        )
        val rewards = buildList {
            if (run.coins > 0) add(context.getString(R.string.adventure_result_coins, run.coins))
            if (run.xp > 0) add(context.getString(R.string.adventure_result_xp, run.xp))
            if (run.bond > 0) add(context.getString(R.string.adventure_result_bond, run.bond))
            run.grants.forEach { grant ->
                AdventureChronicleLink.supplyIdOf(grant)?.let { id ->
                    AdventureGateCatalog.supply(id)?.let { add(runCatching { TamaDialogTextCatalog.localizedResource(context, it.nameRes) }.getOrDefault(id)) }
                }
                AdventureChronicleLink.fragmentIdOf(grant)?.let { add(context.getString(R.string.adventure_chronicle_fragment_short)) }
                if (AdventureChronicleLink.isSpark(grant)) add(context.getString(R.string.adventure_result_granted_spark))
                AdventureChronicleLink.fallbackCoinsOf(grant).takeIf { it > 0 }
                    ?.let { add(context.getString(R.string.adventure_result_granted_coins, it)) }
            }
        }
        val body = buildString {
            append(dungeonTitle).append(" - ").append(dateFormat.format(Date(run.finishedAt)))
            append('\n').append(outcome)
            if (rewards.isNotEmpty()) append('\n').append(rewards.joinToString(", "))
        }
        entries += JournalEntry(R.string.adventure_chronicle_journal_title, body = body, titleText = run.title)
    }
    val sparks = log.sparks
    if (sparks > 0) {
        val corners = if (regentBeaten) AdventureChronicleLink.relitCornerLines(lore.language, sparks) else emptyList()
        val body = if (corners.isEmpty()) context.getString(R.string.adventure_chronicle_sparks_hint) else corners.joinToString("\n")
        entries += JournalEntry(
            R.string.adventure_chronicle_journal_title,
            body = body,
            titleText = context.getString(R.string.adventure_chronicle_sparks_title, sparks)
        )
    }
    for (fragmentId in log.fragmentIds) {
        val (world, text) = AdventureChronicleLink.fragmentText(lore.language, fragmentId) ?: continue
        entries += JournalEntry(
            R.string.adventure_chronicle_journal_title,
            body = text,
            titleText = context.getString(R.string.adventure_chronicle_fragment_title, world)
        )
    }
    return JournalSection(context.getString(R.string.adventure_chronicle_journal_title), entries)
}

private const val MAX_CHRONICLE_RUNS = 20

/** Everything Lumo has written down so far. Locked pages collapse into a single hint line per world. */
@Composable
internal fun AdventureGateJournalDialog(
    progressRows: List<AdventureGateWorldProgress>,
    seen: Set<String>,
    petId: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val content = remember(progressRows, seen, petId) {
        val log = petId?.let { AdventureChronicleStore(context).load(it) } ?: ChronicleLog()
        buildJournal(context, progressRows, seen, log)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GateColorPanel,
        titleContentColor = GateColorAccent,
        textContentColor = GateColorText,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.adventure_story_journal_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.adventure_story_journal_pages, content.unlocked, content.total),
                    color = GateColorText.copy(alpha = 0.78f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            if (content.unlocked == 0 && !content.hasChronicle) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LumoSpeechBubble(
                        text = stringResource(R.string.adventure_story_journal_empty),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(content.sections) { section ->
                        JournalSectionView(section)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(colors = gateTextButtonColors(), onClick = onDismiss) {
                Text(stringResource(R.string.action_close), fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun JournalSectionView(section: JournalSection) {
    val opened = section.entries.filter { it.body != null }
    val hasLocked = section.entries.any { it.body == null }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = section.title,
            color = GateColorAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        opened.forEach { entry ->
            Column(modifier = Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (entry.titleText != null) {
                        entry.titleText
                    } else if (entry.titleArg != null) {
                        stringResource(entry.titleRes, entry.titleArg)
                    } else {
                        stringResource(entry.titleRes)
                    },
                    color = GateColorText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.body.orEmpty(),
                    color = GateColorText.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
        if (hasLocked) {
            Text(
                text = stringResource(R.string.adventure_story_journal_locked),
                color = GateColorText.copy(alpha = 0.62f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
