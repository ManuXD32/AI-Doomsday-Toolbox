package com.example.llamadroid.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * The five main screens of the single app-wide navigation bar/rail/drawer (Home, Chats, Tools, Tama,
 * Organizer), identical on root and deep screens. Declaration order is display order. Settings is a gear
 * on every main top bar (and an explicit drawer entry), Library is a tab inside Tools. [barLabel] is the
 * shorter text used under the icons of the bottom bar; the rail and drawer use [label].
 * Icon lookup lives here so themes can swap it once.
 */
enum class UniversalKey(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val sprite: AppSpriteKey,
    @StringRes val barLabel: Int = label
) {
    DASHBOARD("dashboard", R.string.studio_nav_home, Icons.Default.Home, AppSpriteKey.HOME),
    CHATS("chats", R.string.studio_nav_chats, Icons.Default.Forum, AppSpriteKey.CHATS),
    TOOLS("ai_hub", R.string.studio_nav_tools, Icons.Default.GridView, AppSpriteKey.TOOLS, R.string.studio_bar_tools),
    TAMA("tama", R.string.studio_nav_tama, Icons.Default.FavoriteBorder, AppSpriteKey.TAMA),
    ORGANIZER("notes_manager", R.string.soft_studio_home_organizer, Icons.Default.CalendarMonth, AppSpriteKey.ORGANIZER, R.string.studio_bar_organizer);

    /** True when [baseRoute] (query/arguments already stripped) is exactly this key's root route. */
    fun isCurrent(baseRoute: String?): Boolean = baseRoute == route
}

enum class UniversalKeysPlacement { STRIP, RAIL, NONE }

/** The bar is hidden only on first run; it stays visible during the tour so tour targets resolve. */
fun universalKeysPlacement(layout: AppNavigationLayout, isFirstRun: Boolean): UniversalKeysPlacement = when {
    isFirstRun -> UniversalKeysPlacement.NONE
    layout == AppNavigationLayout.Rail -> UniversalKeysPlacement.RAIL
    else -> UniversalKeysPlacement.STRIP
}

/** Which key to highlight for [baseRoute] (query/arguments stripped); Settings, About and Logs highlight none. */
fun selectedUniversalKey(baseRoute: String?): UniversalKey? {
    if (baseRoute == null) return null
    return when (AppRoutePresentations.forRoute(baseRoute).parent) {
        AppRootDestination.Home -> UniversalKey.DASHBOARD
        AppRootDestination.Chats -> UniversalKey.CHATS
        AppRootDestination.Tools -> UniversalKey.TOOLS
        AppRootDestination.Tama -> UniversalKey.TAMA
        AppRootDestination.Organizer -> UniversalKey.ORGANIZER
        null -> null
    }
}

/** Labels fit under the icons only on roomy windows at normal font scale. */
fun universalKeyLabelsVisible(widthDp: Int, fontScale: Float): Boolean = widthDp >= 360 && fontScale < 1.3f

fun NavController.navigateToUniversalKey(key: UniversalKey) = navigateToRoot(key.route)

/** Opens Settings from the gear/drawer entry: single top, reusing the entry already on the stack. */
fun NavController.navigateToSettings() {
    val onStack = runCatching { getBackStackEntry(Screen.Settings.route) }.getOrNull() != null
    if (!(onStack && popBackStack(Screen.Settings.route, inclusive = false))) {
        navigate(Screen.Settings.route) { launchSingleTop = true }
    }
}

@Composable
fun UniversalKeyStrip(currentBaseRoute: String?, onKey: (UniversalKey) -> Unit) {
    val density = LocalDensity.current
    val widthDp = (LocalWindowInfo.current.containerSize.width / density.density).toInt()
    val showLabels = universalKeyLabelsVisible(widthDp, density.fontScale)
    val selectedKey = selectedUniversalKey(currentBaseRoute)
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    AnimatedVisibility(
        visible = !keyboardOpen,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().testTag("universal_keys_strip")
        ) {
            Row(
                Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UniversalKey.entries.forEach { key ->
                    val selected = key == selectedKey
                    val label = stringResource(key.barLabel)
                    val description = stringResource(R.string.universal_key_open, stringResource(key.label))
                    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onKey(key) })
                            .semantics { contentDescription = description }
                            .testTag("studio_bar_${key.route}")
                            .walkthroughTarget(universalKeyTourTarget(key)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AppIcon(key.icon, null, tint = tint, sprite = key.sprite)
                        if (showLabels) Text(label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Walkthrough anchor of a key: roots keep the historical `root.<route>` ids. */
fun universalKeyTourTarget(key: UniversalKey): String = "root.${key.route}"
