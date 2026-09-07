package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.WalkthroughScrollOwner

private data class ModelManagerShortcut(
    val id: String,
    val labelRes: Int,
    val icon: ImageVector,
    val tab: String,
    val walkthroughEvent: String,
    val walkthroughTarget: String?
)

private val modelManagerShortcuts = listOf(
    ModelManagerShortcut(
        id = "custom_download",
        labelRes = R.string.model_manager_shortcut_custom_download,
        icon = Icons.Default.CloudDownload,
        tab = "download",
        walkthroughEvent = "models.download",
        walkthroughTarget = "models.download"
    ),
    ModelManagerShortcut(
        id = "saved_links",
        labelRes = R.string.model_manager_shortcut_saved_links,
        icon = Icons.Default.Link,
        tab = "sources",
        walkthroughEvent = "models.sources",
        walkthroughTarget = "models.sources"
    ),
    ModelManagerShortcut(
        id = "my_bundles",
        labelRes = R.string.model_manager_shortcut_my_bundles,
        icon = Icons.Default.Inventory2,
        tab = "bundles",
        walkthroughEvent = "models.bundles",
        walkthroughTarget = "models.bundles"
    ),
    ModelManagerShortcut(
        id = "create_bundle",
        labelRes = R.string.model_manager_shortcut_create_bundle,
        icon = Icons.Default.Add,
        tab = "new_bundle",
        walkthroughEvent = "models.bundles",
        walkthroughTarget = null
    ),
    ModelManagerShortcut(
        id = "unknown",
        labelRes = R.string.model_manager_shortcut_unknown,
        icon = Icons.Default.PendingActions,
        tab = "unknown",
        walkthroughEvent = "models.unknown",
        walkthroughTarget = "models.unknown"
    )
)

/** Compact model-library entry points shared by every family manager. */
@Composable
internal fun ModelManagerShortcutRow(
    navController: NavController,
    family: ModelFamily,
    modifier: Modifier = Modifier
) {
    val targets = LocalWalkthroughTargets.current
    val listState = rememberLazyListState()
    WalkthroughScrollOwner(modelManagerShortcuts.mapNotNull { it.walkthroughTarget }.toSet()) { target ->
        val index = modelManagerShortcuts.indexOfFirst { it.walkthroughTarget == target }
        if (index >= 0) listState.animateScrollToItem(index)
    }
        LazyRow(
            state = listState,
            modifier = modifier.testTag("model_manager_shortcuts")
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(modelManagerShortcuts, key = { it.id }) { shortcut ->
                val targetModifier = shortcut.walkthroughTarget?.let { Modifier.walkthroughTarget(it) }
                    ?: Modifier
                OutlinedButton(
                    onClick = {
                        targets?.recordEvent(shortcut.walkthroughEvent)
                        navController.navigate(
                            "${Screen.ModelSources.route}?family=${family.storedValue}&tab=${shortcut.tab}"
                        )
                    },
                    modifier = targetModifier
                        .heightIn(min = 48.dp)
                        .testTag("model_manager_shortcut_${shortcut.id}"),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(
                        imageVector = shortcut.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(shortcut.labelRes),
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
}
