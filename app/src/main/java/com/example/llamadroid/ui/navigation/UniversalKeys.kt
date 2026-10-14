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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
 * The five destinations of the single app-wide navigation bar/rail/drawer (Home, Chats, Tama,
 * Organizer, Settings), identical on root and deep screens. Declaration order is display order.
 * Tools and Library are reached from Home cards. Icon lookup lives here so themes can swap it once.
 */
enum class UniversalKey(val route: String, @StringRes val label: Int, val icon: ImageVector, val sprite: AppSpriteKey) {
    DASHBOARD("dashboard", R.string.studio_nav_home, Icons.Default.Home, AppSpriteKey.HOME),
    CHATS("chats", R.string.studio_nav_chats, Icons.Default.Forum, AppSpriteKey.CHATS),
    TAMA("tama", R.string.studio_nav_tama, Icons.Default.FavoriteBorder, AppSpriteKey.TAMA),
    ORGANIZER("notes_manager", R.string.soft_studio_home_organizer, Icons.Default.CalendarMonth, AppSpriteKey.ORGANIZER),
    SETTINGS("settings", R.string.settings_title, Icons.Default.Settings, AppSpriteKey.SETTINGS);

    /** True when [baseRoute] (query/arguments already stripped) belongs to this key. */
    fun isCurrent(baseRoute: String?): Boolean = when (this) {
        SETTINGS -> baseRoute == "settings" || baseRoute?.startsWith("settings_") == true
        else -> baseRoute == route
    }
}

enum class UniversalKeysPlacement { STRIP, RAIL, NONE }

/** The bar is hidden only on first run; it stays visible during the tour so tour targets resolve. */
fun universalKeysPlacement(layout: AppNavigationLayout, isFirstRun: Boolean): UniversalKeysPlacement = when {
    isFirstRun -> UniversalKeysPlacement.NONE
    layout == AppNavigationLayout.Rail -> UniversalKeysPlacement.RAIL
    else -> UniversalKeysPlacement.STRIP
}

/** Which key to highlight for [baseRoute] (query/arguments stripped); Tools/Library highlight Home. */
fun selectedUniversalKey(baseRoute: String?): UniversalKey? {
    if (baseRoute == null) return null
    if (UniversalKey.SETTINGS.isCurrent(baseRoute) || baseRoute == "about" || baseRoute == "logs") return UniversalKey.SETTINGS
    if (baseRoute == UniversalKey.ORGANIZER.route) return UniversalKey.ORGANIZER
    return when (AppRoutePresentations.forRoute(baseRoute).parent) {
        AppRootDestination.Chats -> UniversalKey.CHATS
        AppRootDestination.Tama -> UniversalKey.TAMA
        AppRootDestination.Home, AppRootDestination.Tools, AppRootDestination.Library -> UniversalKey.DASHBOARD
        null -> null
    }
}

/** Labels fit under the icons only on roomy windows at normal font scale. */
fun universalKeyLabelsVisible(widthDp: Int, fontScale: Float): Boolean = widthDp >= 360 && fontScale < 1.3f

fun NavController.navigateToUniversalKey(key: UniversalKey) {
    when (key) {
        UniversalKey.DASHBOARD, UniversalKey.CHATS, UniversalKey.TAMA -> navigateToRoot(key.route)
        UniversalKey.ORGANIZER, UniversalKey.SETTINGS -> {
            val onStack = runCatching { getBackStackEntry(key.route) }.getOrNull() != null
            if (!(onStack && popBackStack(key.route, inclusive = false))) {
                navigate(key.route) { launchSingleTop = true }
            }
        }
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
                    val label = stringResource(key.label)
                    val description = stringResource(R.string.universal_key_open, label)
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

/** Walkthrough anchor of a key: roots keep the historical `root.<route>` ids, Settings keeps `shell.settings`. */
fun universalKeyTourTarget(key: UniversalKey): String = when (key) {
    UniversalKey.SETTINGS -> "shell.settings"
    else -> "root.${key.route}"
}
