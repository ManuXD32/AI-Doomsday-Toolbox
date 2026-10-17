package com.example.llamadroid.ui.library

import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.WalkthroughScrollOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppHubRowStyle
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.theme.LocalAppVisualStyle
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen

/** Standalone Library page (kept for direct use); Tools hosts the same content in its Library tab. */
@Composable
fun LibraryScreen(navController: NavController) {
    AppPageBackground {
        LibraryContent(navController)
    }
}

/**
 * Library content for model assets, retrieval knowledge, offline reading, and generated outputs;
 * embedded in the Library tab of the Tools page. Navigation stays on the existing manager routes
 * so storage/progress/recovery behavior remains owned by the feature that already implements it.
 */
@Composable
fun LibraryContent(navController: NavController) {
    val scrollState = rememberScrollState()
    val walkthroughTargets = LocalWalkthroughTargets.current
    WalkthroughScrollOwner(setOf("library.resources")) { scrollState.animateScrollTo(0) }

    AppContentColumn(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppSectionCard {
            LibrarySectionHeader(
                icon = Icons.Default.Storage,
                sprite = AppSpriteKey.MODEL_LLM,
                title = stringResource(R.string.ui_library_models_title)
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.AutoMirrored.Filled.Chat,
                sprite = AppSpriteKey.MODEL_LLM,
                title = stringResource(R.string.ui_library_task_chat),
                subtitle = stringResource(R.string.ui_library_task_chat_desc),
                modifier = Modifier.walkthroughTarget("library.resources"),
                onClick = {
                    walkthroughTargets?.recordEvent("library.resources")
                    navController.navigate(Screen.LLMModels.route)
                }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Image,
                sprite = AppSpriteKey.MODEL_IMAGE,
                title = stringResource(R.string.ui_library_task_images),
                subtitle = stringResource(R.string.ui_library_task_images_desc),
                onClick = { navController.navigate(Screen.SDModels.route) },
                extra = {
                    LibraryChipRow(
                        listOf(stringResource(R.string.models_onnx) to { navController.navigate(Screen.OnnxModels.route) })
                    )
                }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.GraphicEq,
                sprite = AppSpriteKey.MODEL_AUDIO,
                title = stringResource(R.string.ui_library_task_voice),
                subtitle = stringResource(R.string.ui_library_task_voice_desc),
                onClick = { navController.navigate(Screen.AudioModels.route) },
                extra = {
                    LibraryChipRow(
                        listOf(
                            stringResource(R.string.models_whisper) to { navController.navigate(Screen.WhisperModels.route) },
                            stringResource(R.string.models_litert) to { navController.navigate(Screen.LiteRtModels.route) }
                        )
                    )
                }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Search,
                sprite = AppSpriteKey.TOOL_KNOWLEDGE,
                title = stringResource(R.string.ui_library_task_documents),
                subtitle = stringResource(R.string.ui_library_task_documents_desc),
                onClick = { navController.navigate(Screen.LLMModels.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Share,
                sprite = AppSpriteKey.TOOL_DISTRIBUTED,
                title = stringResource(R.string.models_share),
                subtitle = stringResource(R.string.models_share_desc),
                modifier = Modifier.walkthroughTarget("models.share"),
                onClick = {
                    walkthroughTargets?.recordEvent("models.share")
                    navController.navigate("model_share")
                }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Folder,
                sprite = AppSpriteKey.MODEL_BUNDLE,
                title = stringResource(R.string.ui_library_all_managers),
                subtitle = stringResource(R.string.soft_studio_library_models_desc),
                onClick = { navController.navigate(Screen.ModelHub.route) }
            )
        }

        AppSectionCard {
            LibrarySectionHeader(
                icon = Icons.Default.Collections,
                sprite = AppSpriteKey.TOOL_KNOWLEDGE,
                title = stringResource(R.string.ui_library_knowledge_title)
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Collections,
                sprite = AppSpriteKey.TOOL_KNOWLEDGE,
                title = stringResource(R.string.ui_library_knowledge_bases),
                subtitle = stringResource(R.string.soft_studio_library_knowledge_desc),
                onClick = { navController.navigate(Screen.KnowledgeBase.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Description,
                sprite = AppSpriteKey.TOOL_KIWIX,
                title = stringResource(R.string.soft_studio_library_open_offline),
                subtitle = stringResource(R.string.soft_studio_library_offline_desc),
                onClick = { navController.navigate(Screen.ZimManager.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Edit,
                sprite = AppSpriteKey.TOOL_NOTES,
                title = stringResource(R.string.ui_library_notes),
                onClick = { navController.navigate(Screen.NotesManager.route) }
            )
        }

        AppSectionCard {
            LibrarySectionHeader(
                icon = Icons.Default.Collections,
                sprite = AppSpriteKey.TOOL_IMAGE,
                title = stringResource(R.string.ui_library_creations_title)
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Collections,
                sprite = AppSpriteKey.TOOL_IMAGE,
                title = stringResource(R.string.soft_studio_library_open_all_media),
                subtitle = stringResource(R.string.media_gallery_subtitle),
                onClick = { navController.navigate(Screen.AllMediaGallery.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Image,
                sprite = AppSpriteKey.MODEL_IMAGE,
                title = stringResource(R.string.soft_studio_library_open_images),
                onClick = {
                    navController.navigate(Screen.ImageGen.createRoute(startMode = 0, tab = "gallery"))
                }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Movie,
                sprite = AppSpriteKey.TOOL_VIDEO,
                title = stringResource(R.string.soft_studio_library_open_videos),
                onClick = { navController.navigate(Screen.VideoGen.createRoute(tab = "gallery")) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.GraphicEq,
                sprite = AppSpriteKey.MODEL_AUDIO,
                title = stringResource(R.string.soft_studio_library_open_audio),
                onClick = { navController.navigate(Screen.AudioWorkspace.createRoute("history")) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.GraphicEq,
                sprite = AppSpriteKey.MODEL_AUDIO,
                title = stringResource(R.string.soft_studio_library_open_onnx_voice),
                onClick = { navController.navigate(Screen.OnnxTtsGallery.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Speed,
                sprite = AppSpriteKey.MODEL_IMAGE,
                title = stringResource(R.string.soft_studio_library_open_fast_sd),
                onClick = { navController.navigate(Screen.FastsdGallery.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Hub,
                sprite = AppSpriteKey.TOOL_DISTRIBUTED,
                title = stringResource(R.string.soft_studio_library_open_distributed_media),
                onClick = { navController.navigate(Screen.SdDistributedGallery.route) }
            )
            AppHubRow(
                style = AppHubRowStyle.Plain,
                icon = Icons.Default.Speed,
                sprite = AppSpriteKey.STATE_RUNNING,
                title = stringResource(R.string.ui_sweepb_generation_queue),
                subtitle = stringResource(R.string.ui_sweepb_generation_queue_desc),
                onClick = { navController.navigate(Screen.GenerationQueue.route) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { navController.navigate(Screen.ModelSources.route) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(R.string.ui_library_link_storage),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LibrarySectionHeader(
    icon: ImageVector,
    title: String,
    sprite: AppSpriteKey? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        ) {
            AppIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.padding(if (LocalAppVisualStyle.current.isPixel) 6.dp else 10.dp),
                tint = MaterialTheme.colorScheme.primary,
                sprite = sprite
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryChipRow(chips: List<Pair<String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, action) ->
            AssistChip(
                onClick = action,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}
