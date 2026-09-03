package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.llamadroid.R

/**
 * A destination that can be rendered in either the direct or overflow navigation surface.
 *
 * [isSelected] deliberately receives the current route instead of storing a boolean. This lets
 * callers keep parent destinations selected while a nested route is visible (for example, the
 * Models destination while viewing a specific model manager).
 */
data class AppNavigationDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val contentDescription: String = label,
    val isSelected: (String?) -> Boolean = { currentRoute -> currentRoute == route }
)

/** A simple action model used by the list overload of [ResponsiveActionGroup]. */
data class ResponsiveAction(
    val label: String,
    val onClick: () -> Unit,
    val modifier: Modifier = Modifier,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val contentDescription: String? = null,
    val style: ResponsiveActionStyle = ResponsiveActionStyle.Primary
)

enum class ResponsiveActionStyle {
    Primary,
    Secondary,
    Text
}

/**
 * Returns whether the compact navigation treatment should be used.
 *
 * The comparison is intentionally strict at both boundaries: 360 dp with a font scale below
 * 1.3 keeps the six direct destinations; every smaller width or 1.3-and-larger font scale uses
 * the five-item compact treatment.
 */
fun isCompactAppNavigation(
    widthDp: Int,
    fontScale: Float,
    expandedMinimumWidthDp: Int = 360,
    expandedMaximumFontScale: Float = 1.3f
): Boolean {
    return widthDp < expandedMinimumWidthDp || !(fontScale < expandedMaximumFontScale)
}

/**
 * A scrollable tab row with the app's default surface colors and edge inset.
 *
 * The API mirrors the part of Material 3's [ScrollableTabRow] most screens need while keeping
 * the shared defaults in one place. Callers can still provide custom colors when a themed
 * subsystem needs them.
 */
@Composable
fun AppScrollableTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    edgePadding: Dp = AppChromeDefaults.ScreenPadding,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tabs: @Composable () -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier.fillMaxWidth(),
        edgePadding = edgePadding,
        containerColor = containerColor,
        contentColor = contentColor,
        tabs = tabs
    )
}

/**
 * A full-width, selectable choice row that remains readable on narrow phones.
 *
 * The whole row exposes one selectable accessibility node. Optional supporting content is
 * ellipsized rather than allowed to force a narrow button/card into character-by-character
 * wrapping.
 */
@Composable
fun AppChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    contentDescription: String? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription ?: title
            },
        shape = AppChromeDefaults.InnerCardShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            leadingContent?.invoke()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

/**
 * Adaptive bottom navigation for the app shell.
 *
 * On roomy screens the supplied [destinations] are shown directly. On compact screens the
 * caller supplies four [compactDestinations] (or the first four direct destinations by default)
 * and [overflowDestinations] are placed behind a More item. The overflow sheet is a bounded
 * [LazyColumn] with safe-drawing insets so its contents remain reachable above navigation bars,
 * IME, and display cutouts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveAppNavigation(
    currentRoute: String?,
    destinations: List<AppNavigationDestination>,
    overflowDestinations: List<AppNavigationDestination> = emptyList(),
    compactDestinations: List<AppNavigationDestination> = emptyList(),
    modifier: Modifier = Modifier,
    compactWidthDp: Int = 360,
    compactFontScale: Float = 1.3f,
    moreLabel: String? = null,
    moreContentDescription: String? = null,
    moreSheetTitle: String? = null,
    moreSheetSubtitle: String? = null,
    moreSheetDismissLabel: String? = null,
    widthDp: Int? = null,
    fontScale: Float? = null
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val compact = isCompactAppNavigation(
        widthDp = widthDp ?: configuration.screenWidthDp,
        fontScale = fontScale ?: density.fontScale,
        expandedMinimumWidthDp = compactWidthDp,
        expandedMaximumFontScale = compactFontScale
    )
    val resolvedCompactDestinations = remember(destinations, compactDestinations) {
        (compactDestinations.ifEmpty { destinations.take(4) }).take(4)
    }
    val resolvedMoreLabel = moreLabel ?: androidx.compose.ui.res.stringResource(R.string.responsive_nav_more)
    val resolvedMoreContentDescription = moreContentDescription
        ?: androidx.compose.ui.res.stringResource(R.string.responsive_nav_more_accessibility)
    val resolvedSheetTitle = moreSheetTitle
        ?: androidx.compose.ui.res.stringResource(R.string.responsive_nav_more_title)
    val resolvedSheetSubtitle = moreSheetSubtitle
        ?: androidx.compose.ui.res.stringResource(R.string.responsive_nav_more_subtitle)
    val resolvedDismissLabel = moreSheetDismissLabel
        ?: androidx.compose.ui.res.stringResource(R.string.responsive_nav_more_close)
    var showMore by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visibleDestinations = if (compact) resolvedCompactDestinations else destinations
    val overflowIsSelected = overflowDestinations.any { destination ->
        destination.isSelected(currentRoute)
    }

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        visibleDestinations.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier
                    .testTag("adaptive_navigation_${destination.route}")
                    .semantics {
                        this.contentDescription = destination.contentDescription
                    },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                selected = destination.isSelected(currentRoute),
                onClick = destination.onClick,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
        if (compact && overflowDestinations.isNotEmpty()) {
            NavigationBarItem(
                modifier = Modifier
                    .testTag("adaptive_navigation_more")
                    .semantics {
                        this.contentDescription = resolvedMoreContentDescription
                    },
                icon = {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = resolvedMoreLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                selected = showMore || overflowIsSelected,
                onClick = { showMore = true },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }

    if (showMore && overflowDestinations.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets.safeDrawing }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .imePadding()
                    .testTag("adaptive_navigation_more_sheet"),
                contentPadding = PaddingValues(
                    start = AppChromeDefaults.ScreenPadding,
                    top = 8.dp,
                    end = AppChromeDefaults.ScreenPadding,
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = resolvedSheetTitle,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = resolvedSheetSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showMore = false },
                            modifier = Modifier.semantics {
                                this.contentDescription = resolvedDismissLabel
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null
                            )
                        }
                    }
                }
                items(
                    items = overflowDestinations,
                    key = { destination -> destination.route }
                ) { destination ->
                    AppChoiceRow(
                        title = destination.label,
                        selected = destination.isSelected(currentRoute),
                        onClick = {
                            showMore = false
                            destination.onClick()
                        },
                        modifier = Modifier.testTag("adaptive_navigation_more_${destination.route}"),
                        leadingContent = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        contentDescription = destination.contentDescription
                    )
                }
            }
        }
    }
}

/**
 * A content-slot overload for callers that already own Material buttons or custom action cards.
 * The FlowRow keeps actions from being forced into a narrow equal-width row.
 */
@Composable
fun ResponsiveActionGroup(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/**
 * Renders simple actions with a full-width column on compact surfaces and a wrapping row on
 * larger ones. The column treatment gives translated labels enough horizontal room when the
 * system font is enlarged.
 */
@Composable
fun ResponsiveActionGroup(
    actions: List<ResponsiveAction>,
    modifier: Modifier = Modifier,
    compactWidthDp: Int = 360,
    compactFontScale: Float = 1.3f,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    if (actions.isEmpty()) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val compact = maxWidth < compactWidthDp.dp || !(density.fontScale < compactFontScale)
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                actions.forEach { action ->
                    ResponsiveActionButton(
                        action = action,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            val availableWidth = maxWidth
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                actions.forEach { action ->
                    ResponsiveActionButton(
                        action = action,
                        modifier = Modifier.widthIn(max = availableWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponsiveActionButton(
    action: ResponsiveAction,
    modifier: Modifier = Modifier
) {
    val buttonModifier = action.modifier
        .then(modifier)
        .semantics {
            this.contentDescription = action.contentDescription ?: action.label
        }
    val buttonContent: @Composable RowScope.() -> Unit = {
        if (action.icon != null) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(
            text = action.label,
            maxLines = 2
        )
    }
    when (action.style) {
        ResponsiveActionStyle.Primary -> Button(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = buttonModifier,
            content = buttonContent
        )
        ResponsiveActionStyle.Secondary -> OutlinedButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = buttonModifier,
            content = buttonContent
        )
        ResponsiveActionStyle.Text -> TextButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = buttonModifier,
            content = buttonContent
        )
    }
}
