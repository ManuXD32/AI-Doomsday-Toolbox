package com.example.llamadroid.tama.ui.adventure

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.adventure.AdventureChronicleLink
import com.example.llamadroid.tama.adventure.AdventureLore
import com.example.llamadroid.tama.adventure.AdventureOutcome
import com.example.llamadroid.tama.adventure.AdventureRunState
import com.example.llamadroid.tama.adventure.ChronicleGrant
import com.example.llamadroid.tama.adventure.DreamChroniclePage
import com.example.llamadroid.tama.adventure.ResolvedLore
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.ui.AdventureAccent
import com.example.llamadroid.tama.ui.AdventureInput
import com.example.llamadroid.tama.ui.AdventureText
import com.example.llamadroid.tama.ui.StoryBubble

private val HopeColor = Color(0xFFFFC857)

/** Compact run-state row: hope meter and found-item chips (horizontally scrollable). */
@Composable
fun AdventureRunStateRow(state: AdventureRunState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AdventureInput)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.adventure_hope_meter, state.hope, AdventureRunState.MAX_HOPE),
                color = HopeColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { state.hope / AdventureRunState.MAX_HOPE.toFloat() },
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = HopeColor,
                trackColor = Color(0xFF3A3A4A)
            )
        }
        if (state.items.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.adventure_items_label),
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                state.items.forEach { item ->
                    Text(
                        text = item,
                        color = AdventureText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(StoryBubble)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/** Tappable choice buttons (2-4). Scrolls when the choices are taller than [maxHeight] on small phones. */
@Composable
fun AdventureChoiceChips(
    choices: List<String>,
    enabled: Boolean,
    onChoice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (choices.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AdventureInput)
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.adventure_choices_title),
            color = AdventureAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        choices.forEachIndexed { index, choice ->
            OutlinedButton(
                onClick = { onChoice(choice) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "${index + 1}. $choice",
                    color = if (enabled) AdventureText else Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Results card shown when a run ends: Chronicle page, rewards and the next-step buttons. */
@Composable
fun DreamChronicleCard(
    page: DreamChroniclePage,
    languageCode: String,
    onContinue: () -> Unit,
    onNewAdventure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lore = AdventureLore.loreFor(languageCode)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StoryBubble)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.adventure_result_page_label),
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Text(
            text = page.title,
            color = AdventureAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            text = stringResource(
                if (page.outcome == AdventureOutcome.VICTORY) R.string.adventure_result_victory
                else R.string.adventure_result_carried_back
            ),
            color = HopeColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = page.summary,
            color = AdventureText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Text(
            text = stringResource(R.string.adventure_result_final_hope, page.finalHope, AdventureRunState.MAX_HOPE),
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        if (page.coins > 0 || page.xp > 0 || page.bond > 0 || page.grants.isNotEmpty()) {
            Text(
                text = stringResource(R.string.adventure_result_rewards_title),
                color = AdventureAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RewardPill(stringResource(R.string.adventure_result_coins, page.coins), page.coins > 0)
                RewardPill(stringResource(R.string.adventure_result_xp, page.xp), page.xp > 0)
                RewardPill(stringResource(R.string.adventure_result_bond, page.bond), page.bond > 0)
            }
            page.grants.forEach { grant ->
                Text(
                    text = grantLabel(grant, lore, page.grantsApplied),
                    color = AdventureText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                text = stringResource(R.string.adventure_result_no_rewards),
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onContinue, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_continue), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = onNewAdventure,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AdventureAccent)
            ) {
                Text(stringResource(R.string.adventure_result_new), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One line per Chronicle grant: the concrete Gate item, Nexum fragment or lantern spark once granted. */
@Composable
private fun grantLabel(grant: ChronicleGrant, lore: ResolvedLore, applied: Boolean): String {
    val kind = lore.chronicleRewards.firstOrNull { it.id == grant.rewardId }?.title ?: grant.rewardId
    if (!applied) return grant.detail?.takeIf { it.isNotBlank() }?.let { "$kind: $it" } ?: kind
    AdventureChronicleLink.supplyIdOf(grant)?.let { id ->
        AdventureGateCatalog.supply(id)?.let { return stringResource(R.string.adventure_result_granted_item, stringResource(it.nameRes)) }
    }
    AdventureChronicleLink.fragmentIdOf(grant)?.let { id ->
        AdventureChronicleLink.fragmentText(lore.language, id)?.let { (world, _) ->
            return stringResource(R.string.adventure_result_granted_fragment, world)
        }
    }
    if (AdventureChronicleLink.isSpark(grant)) return stringResource(R.string.adventure_result_granted_spark)
    val coins = AdventureChronicleLink.fallbackCoinsOf(grant)
    return if (coins > 0) stringResource(R.string.adventure_result_granted_coins, coins) else kind
}

@Composable
private fun RewardPill(text: String, active: Boolean) {
    Text(
        text = text,
        color = if (active) Color.Black else Color.Gray,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) AdventureAccent else Color(0xFF2A2A3A))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
