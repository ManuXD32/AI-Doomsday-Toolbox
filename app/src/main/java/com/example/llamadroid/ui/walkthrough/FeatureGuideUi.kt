package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R

internal data class FeatureGuideEntry(val guideId: String, val open: (String) -> Unit)
internal val LocalFeatureGuideEntry = staticCompositionLocalOf<FeatureGuideEntry?> { null }

/** Shared header action. The current destination owns the guide, including internal tabs/editors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureGuideAction(modifier: Modifier = Modifier) {
    val entry = LocalFeatureGuideEntry.current ?: return
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(R.string.feature_guide_title)) } },
        state = rememberTooltipState()) {
        FilledTonalIconButton(onClick = { entry.open(entry.guideId) },
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("feature_guide_open").walkthroughTarget("feature.explore"),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
            Icon(Icons.Default.Explore, stringResource(R.string.feature_guide_title))
        }
    }
}

/** The chooser is bounded, scrollable and dismissible without changing the underlying destination. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeatureGuideChooser(state: WalkthroughState, currentRoute: String?) {
    val guide = FeatureGuideCatalog.guides.firstOrNull { it.id == state.featureChooserId } ?: return
    state.revision
    Dialog(onDismissRequest = state::closeFeatureChooser,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp)
            .fillMaxHeight(.9f).testTag("feature_guide_chooser"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(guide.titleRes), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = state::closeFeatureChooser, modifier = Modifier.testTag("feature_guide_close")) {
                        Icon(Icons.Default.Close, stringResource(R.string.tour_close))
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.feature_guide_intro)) }
                    items(guide.recipes, key = { it.id }) { recipe ->
                        val chapterId = "feature:${recipe.id}"
                        val completed = state.preferences.isCompleted(chapterId)
                        val hasProgress = state.preferences.progress(chapterId) != null
                        Card(Modifier.fillMaxWidth().testTag("feature_recipe_${recipe.id}")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(recipe.titleRes), style = MaterialTheme.typography.titleMedium)
                                recipe.steps.firstOrNull()?.let { Text(stringResource(it.bodyRes)) }
                                if (completed) Text(stringResource(R.string.tour_completed), color = MaterialTheme.colorScheme.primary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (hasProgress && !completed) FilledTonalButton(onClick = {
                                        state.startFeature(recipe.id, true, currentRoute)
                                    }) { Text(stringResource(R.string.tour_resume)) }
                                    Button(onClick = { state.startFeature(recipe.id, false, currentRoute) }) {
                                        Text(stringResource(if (hasProgress || completed) R.string.tour_replay else R.string.tour_start))
                                    }
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.tour_no_setup_required), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
