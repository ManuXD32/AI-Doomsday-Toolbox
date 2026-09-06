package com.example.llamadroid.ui.library

import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen

/**
 * Library root for model assets, retrieval knowledge, offline reading, and generated outputs.
 * Navigation stays on the existing manager routes so storage/progress/recovery behavior remains
 * owned by the feature that already implements it.
 */
@Composable
fun LibraryScreen(navController: NavController) {
    val scrollState = rememberScrollState()

    AppPageBackground {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppPageHeader(
                title = stringResource(R.string.soft_studio_library_title),
                modifier = Modifier.walkthroughTarget("library.resources")
            )

            AppSectionCard {
                LibrarySectionHeader(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.soft_studio_library_resources_title)
                )
                LibraryActionRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.soft_studio_library_open_models),
                    supporting = stringResource(R.string.soft_studio_library_models_desc),
                    onClick = { navController.navigate(Screen.ModelHub.route) }
                )
                LibraryActionRow(
                    icon = Icons.Default.Collections,
                    title = stringResource(R.string.soft_studio_library_open_knowledge),
                    supporting = stringResource(R.string.soft_studio_library_knowledge_desc),
                    onClick = { navController.navigate(Screen.KnowledgeBase.route) }
                )
                LibraryActionRow(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.soft_studio_library_open_offline),
                    supporting = stringResource(R.string.soft_studio_library_offline_desc),
                    onClick = { navController.navigate(Screen.ZimManager.route) }
                )
            }

            AppSectionCard {
                LibrarySectionHeader(
                    icon = Icons.Default.Collections,
                    title = stringResource(R.string.soft_studio_library_outputs_title)
                )
                LibraryActionRow(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.soft_studio_library_open_images),
                    onClick = {
                        navController.navigate(Screen.ImageGen.createRoute(startMode = 0, tab = "gallery"))
                    }
                )
                LibraryActionRow(
                    icon = Icons.Default.Movie,
                    title = stringResource(R.string.soft_studio_library_open_videos),
                    onClick = { navController.navigate(Screen.VideoGen.createRoute(tab = "gallery")) }
                )
                LibraryActionRow(
                    icon = Icons.Default.GraphicEq,
                    title = stringResource(R.string.soft_studio_library_open_audio),
                    onClick = { navController.navigate(Screen.OnnxTtsGallery.route) }
                )
                LibraryActionRow(
                    icon = Icons.Default.Speed,
                    title = stringResource(R.string.soft_studio_library_open_fast_sd),
                    onClick = { navController.navigate(Screen.FastsdGallery.route) }
                )
                LibraryActionRow(
                    icon = Icons.Default.Hub,
                    title = stringResource(R.string.soft_studio_library_open_distributed_media),
                    onClick = { navController.navigate(Screen.SdDistributedGallery.route) }
                )
                LibraryActionRow(
                    icon = Icons.Default.Edit,
                    title = stringResource(R.string.soft_studio_library_open_notes),
                    onClick = { navController.navigate(Screen.NotesManager.route) }
                )
            }
        }
    }
}

@Composable
private fun LibrarySectionHeader(
    icon: ImageVector,
    title: String
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(10.dp),
                tint = MaterialTheme.colorScheme.primary
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

@Composable
private fun LibraryActionRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
