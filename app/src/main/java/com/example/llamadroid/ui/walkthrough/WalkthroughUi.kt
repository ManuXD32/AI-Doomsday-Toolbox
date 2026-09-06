package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppScreenScaffold
import kotlinx.coroutines.delay

@Composable
internal fun WalkthroughGuide(state: WalkthroughState, onBack: () -> Unit, onStart: (String, Boolean) -> Unit) {
    state.revision // Observe preference writes made by the activity-owned controller.
    AppScreenScaffold(title = stringResource(R.string.tour_title), onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize().testTag("tour_guide"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.tour_intro)) }
            item {
                GuideChapterCard(CoreTour.ID, R.string.tour_core_title, R.string.tour_core_description,
                    state.preferences.progress(CoreTour.ID) != null, state.preferences.isCompleted(CoreTour.ID), onStart)
            }
            item { Text(stringResource(R.string.tour_chapters), style = MaterialTheme.typography.titleLarge) }
            items(WalkthroughCatalog.chapters, key = { it.id }) { chapter ->
                GuideChapterCard(chapter.id, chapter.titleRes, chapter.descriptionRes,
                    state.preferences.progress(chapter.id) != null, state.preferences.isCompleted(chapter.id), onStart)
            }
            item { IconGuide() }
            item { Text(stringResource(R.string.tour_no_setup_required), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideChapterCard(id: String, title: Int, description: Int, hasProgress: Boolean, completed: Boolean,
    onStart: (String, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("tour_chapter_$id"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(description))
            if (completed) Text(stringResource(R.string.tour_completed), color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasProgress && !completed) FilledTonalButton(onClick = { onStart(id, true) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tour_resume_$id")) {
                    Text(stringResource(R.string.tour_resume))
                }
                Button(onClick = { onStart(id, false) }, modifier = Modifier.heightIn(min = 48.dp).testTag("tour_start_$id")) {
                    Text(stringResource(if (hasProgress || completed) R.string.tour_replay
                        else if (id == CoreTour.ID) R.string.tour_start else R.string.tour_start_chapter))
                }
            }
        }
    }
}

/** The scaffold reserves this space. Guidance never floats over a task action or its target. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WalkthroughCoach(state: WalkthroughState, registry: WalkthroughTargets, currentRoute: String?,
    onOpen: (String) -> Unit) {
    val session = state.session ?: return
    val step = state.step ?: return
    val progressText = stringResource(R.string.tour_progress, session.index + 1, state.steps(session.chapterId).size)
    val arrived = tourHasArrived(step, currentRoute)
    val targetId = registry.requestedId
    val bounds = targetId?.let { registry.targets[it]?.bounds }
    var unavailable by remember(step.id, targetId, currentRoute) { mutableStateOf(targetId == null && !arrived) }
    var retry by remember { mutableIntStateOf(0) }
    var preview by remember(step.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(step.id, targetId, bounds, retry) {
        if (targetId == null) return@LaunchedEffect
        unavailable = false
        if (bounds == null || bounds.width <= 0f || bounds.height <= 0f) {
            delay(2000)
            unavailable = true
        }
    }
    if (WindowInsets.ime.getBottom(density) > 0) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("tour_coach"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(step.titleRes), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { focus.clearFocus(); keyboard?.hide() }, modifier = Modifier.testTag("tour_show_guide")) {
                    Icon(Icons.Default.Info, stringResource(R.string.tour_show_guide))
                }
                IconButton(onClick = state::dismiss, modifier = Modifier.testTag("tour_close")) {
                    Icon(Icons.Default.Close, stringResource(R.string.tour_close))
                }
            }
        }
        return
    }
    val heightDp = LocalWindowInfo.current.containerSize.height / density.density
    val shortWindow = heightDp < 480f
    val limit = (heightDp * if (density.fontScale >= 1.3f) .44f else .37f).coerceIn(140f, 340f).dp
    val decisionLabelRes = when {
        !arrived -> R.string.tour_skip_step
        session.index == state.steps(session.chapterId).lastIndex -> R.string.tour_finish
        else -> R.string.tour_next
    }
    if (shortWindow) {
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 56.dp).testTag("tour_coach"),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 0.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(step.titleRes),
                    Modifier.weight(1f)
                        .semantics {
                            heading()
                            stateDescription = progressText
                            liveRegion = LiveRegionMode.Polite
                        }
                        .testTag("tour_step_${step.id}"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (session.index > 0) {
                    IconButton(
                        onClick = { state.move(-1) },
                        modifier = Modifier.size(48.dp).testTag("tour_previous")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tour_previous))
                    }
                }
                IconButton(
                    onClick = { preview = true },
                    modifier = Modifier.size(48.dp).testTag("tour_preview")
                ) {
                    Icon(Icons.Default.Info, stringResource(R.string.tour_show_guide))
                }
                IconButton(
                    onClick = { state.move(1) },
                    modifier = Modifier.size(48.dp).testTag("tour_next")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(decisionLabelRes))
                }
                IconButton(
                    onClick = state::dismiss,
                    modifier = Modifier.size(48.dp).testTag("tour_close")
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.tour_close))
                }
            }
        }
        if (preview) {
            TourPreview(
                step = step,
                onDismiss = { preview = false },
                showFallback = !arrived && unavailable,
                onOpen = { preview = false; onOpen(step.route) },
                onRetry = { preview = false; registry.retryKey++; retry++ }
            )
        }
        return
    }
    Surface(Modifier.fillMaxWidth().heightIn(max = limit).testTag("tour_coach"),
        color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (!shortWindow) Text(stringResource(R.string.tour_progress, session.index + 1, state.steps(session.chapterId).size),
                        style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(step.titleRes), maxLines = if (shortWindow) 1 else 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading(); stateDescription = progressText; liveRegion = LiveRegionMode.Polite }.testTag("tour_step_${step.id}"))
                }
                IconButton(onClick = state::dismiss, modifier = Modifier.testTag("tour_close")) {
                    Icon(Icons.Default.Close, stringResource(R.string.tour_close))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("tour_explanation"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!arrived && !unavailable) Text(stringResource(R.string.tour_tap_target), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(step.bodyRes), style = MaterialTheme.typography.bodyMedium)
                if (unavailable) {
                    Text(stringResource(R.string.tour_missing), style = MaterialTheme.typography.bodySmall)
                    if (!arrived) OutlinedButton(onClick = { onOpen(step.route) }, modifier = Modifier.testTag("tour_open_tool")) {
                        Text(stringResource(R.string.tour_open_tool))
                    }
                    TextButton(onClick = { registry.retryKey++; retry++ }, modifier = Modifier.testTag("tour_retry")) {
                        Text(stringResource(R.string.tour_retry))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { preview = true }.padding(vertical = 6.dp)
                    .semantics { role = Role.Button }.testTag("tour_preview"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(lessonPreviewResource(step.id) ?: previewResource(step.previewKey)), null,
                        Modifier.size(width = 48.dp, height = 72.dp), contentScale = ContentScale.Fit)
                    Text(stringResource(R.string.tour_preview), color = MaterialTheme.colorScheme.primary)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.index > 0) TextButton(onClick = { state.move(-1) }, modifier = Modifier.heightIn(min = 48.dp).testTag("tour_previous")) {
                    Text(stringResource(R.string.tour_previous))
                }
                Button(onClick = { state.move(1) }, modifier = Modifier.heightIn(min = 48.dp).testTag("tour_next")) {
                    Text(stringResource(decisionLabelRes))
                }
            }
        }
    }
    if (preview) TourPreview(step, onDismiss = { preview = false })
}

@Composable
private fun TourPreview(
    step: TourStep,
    onDismiss: () -> Unit,
    showFallback: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tour_preview), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("tour_preview_close")) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(stringResource(step.bodyRes))
                    if (showFallback) {
                        Text(stringResource(R.string.tour_missing), style = MaterialTheme.typography.bodySmall)
                        onOpen?.let {
                            OutlinedButton(
                                onClick = it,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tour_open_tool")
                            ) {
                                Text(stringResource(R.string.tour_open_tool))
                            }
                        }
                        onRetry?.let {
                            TextButton(
                                onClick = it,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("tour_retry")
                            ) {
                                Text(stringResource(R.string.tour_retry))
                            }
                        }
                    }
                    Text(stringResource(R.string.tour_preview_caption), style = MaterialTheme.typography.bodySmall)
                    Image(painterResource(lessonPreviewResource(step.id) ?: previewResource(step.previewKey)), stringResource(step.titleRes),
                        Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit)
                    IconGuide()
                }
            }
        }
    }
}

internal fun previewResource(key: String) = when (key) {
    "tools" -> R.drawable.tour_preview_tools
    "library" -> R.drawable.tour_preview_library
    "create" -> R.drawable.tour_preview_create
    "tama" -> R.drawable.tour_preview_tama
    "farm" -> R.drawable.tour_preview_farm
    else -> R.drawable.tour_preview_home
}

@Composable
private fun IconGuide() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tour_icons), style = MaterialTheme.typography.titleMedium)
        listOf<Pair<ImageVector, Int>>(Icons.Default.PushPin to R.string.tour_icon_pin,
            Icons.Default.Settings to R.string.tour_icon_settings, Icons.Default.MoreVert to R.string.tour_icon_more,
            Icons.AutoMirrored.Filled.ArrowBack to R.string.tour_icon_back, Icons.Default.Collections to R.string.tour_icon_gallery,
            Icons.Default.Stop to R.string.tour_icon_stop).forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(24.dp))
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
