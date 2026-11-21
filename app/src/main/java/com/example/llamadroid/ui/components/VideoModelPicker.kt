package com.example.llamadroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.VideoModelSelection
import com.example.llamadroid.sd.VideoPresetCard
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.pathFor
import com.example.llamadroid.sd.resolveVideoModelSelection
import com.example.llamadroid.sd.videoPresetCards
import com.example.llamadroid.sd.withSelection
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

private val summaryRoles = listOf(
    SdVideoComponentRole.DIFFUSION_MODEL, SdVideoComponentRole.FULL_MODEL,
    SdVideoComponentRole.VAE, SdVideoComponentRole.TAE, SdVideoComponentRole.T5XXL,
    SdVideoComponentRole.LLM, SdVideoComponentRole.LLM_VISION
)

/**
 * Shared video model picker: one tile per catalog bundle or imported video model, plus a
 * read-only summary of what the selection filled in. Raw paths and the family override live
 * under Advanced in [VideoRuntimeOptionsEditor]. Not scrollable itself; place it inside the
 * screen's scroll container.
 */
@Composable
fun VideoModelPicker(
    options: VideoRuntimeOptions,
    installedModels: List<ModelEntity>,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    onOpenModelManager: (bundleId: String?) -> Unit,
    modifier: Modifier = Modifier,
    onPresetApplied: (VideoPresetCard) -> Unit = {}
) {
    val cards = remember(installedModels) { videoPresetCards(installedModels) }
    VideoModelPickerContent(
        options = options,
        cards = cards,
        resolve = { path -> resolveVideoModelSelection(path, installedModels) },
        onOptionsChange = onOptionsChange,
        onOpenModelManager = onOpenModelManager,
        modifier = modifier,
        onPresetApplied = onPresetApplied
    )
}

/** Stateless body of [VideoModelPicker]; the resolver and cards are injectable for tests. */
@Composable
internal fun VideoModelPickerContent(
    options: VideoRuntimeOptions,
    cards: List<VideoPresetCard>,
    resolve: (String) -> VideoModelSelection?,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    onOpenModelManager: (bundleId: String?) -> Unit,
    modifier: Modifier = Modifier,
    onPresetApplied: (VideoPresetCard) -> Unit = {}
) {
    val currentMain = options.videoComponents.diffusionModelPath?.takeIf { it.isNotBlank() }
        ?: options.videoComponents.fullModelPath?.takeIf { it.isNotBlank() }
    val walkthroughTargets = LocalWalkthroughTargets.current
    Column(
        modifier = modifier.fillMaxWidth().walkthroughTarget("video.profile"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.sd_r8_w06_picker_title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cards.none { it.mainPath != null }) {
            Text(
                text = stringResource(R.string.sd_r8_w06_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { onOpenModelManager(null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.sd_r8_w06_get_models), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        cards.forEach { card ->
            VideoPresetTile(
                card = card,
                selected = card.mainPath != null && card.mainPath == currentMain,
                onClick = {
                    walkthroughTargets?.recordEvent("video.profile")
                    val path = card.mainPath
                    if (!card.installed || path == null) {
                        onOpenModelManager(card.bundleId)
                    } else {
                        val selection = resolve(path)
                        if (selection != null) {
                            val next = options.withSelection(selection)
                            if (next !== options) {
                                onOptionsChange(next)
                                onPresetApplied(card)
                            }
                        }
                    }
                }
            )
        }
        if (currentMain != null) {
            VideoSelectionSummary(
                options = options,
                missingRoles = resolve(currentMain)?.missingRoles.orEmpty(),
                onGetModels = { onOpenModelManager(null) }
            )
        }
    }
}

@Composable
private fun VideoPresetTile(card: VideoPresetCard, selected: Boolean, onClick: () -> Unit) {
    val title = card.titleRes?.let { stringResource(it) } ?: card.title
    val state = when {
        selected -> stringResource(R.string.sd_r8_w06_selected)
        card.installed -> stringResource(R.string.sd_r8_w06_ready)
        card.missingFiles > 0 && card.mainPath != null ->
            pluralStringResource(R.plurals.sd_r8_w06_files_missing, card.missingFiles, card.missingFiles)
        else -> stringResource(R.string.sd_r8_w06_not_installed)
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected || card.installed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VideoSelectionSummary(
    options: VideoRuntimeOptions,
    missingRoles: List<SdVideoComponentRole>,
    onGetModels: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val family = options.videoFamily
        Text(
            text = if (family != null) {
                stringResource(R.string.sd_r8_w06_family_line, videoFamilyLabel(family))
            } else {
                stringResource(R.string.sd_r8_w06_unknown_family)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (family != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        summaryRoles.mapNotNull { role ->
            options.videoComponents.pathFor(role)?.takeIf { it.isNotBlank() }?.let { role to it }
        }.forEach { (role, path) ->
            Text(
                text = stringResource(
                    R.string.sd_r8_w06_component_line,
                    videoComponentLabel(role),
                    path.substringAfterLast('/')
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (missingRoles.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.sd_r8_w06_missing_line,
                    missingRoles.map { videoComponentLabel(it) }.joinToString()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                onClick = onGetModels,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.sd_r8_w06_get_models), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
