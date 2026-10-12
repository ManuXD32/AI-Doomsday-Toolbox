package com.example.llamadroid.ui.navigation

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.WalkthroughInsetHost
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.FeatureGuideAction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.components.AppNavigationDestination
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import kotlinx.coroutines.launch

/** Root chrome owns system insets once; deep screens own their contextual top bars and actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SoftStudioAppScaffold(
    currentRoute: String?,
    destinations: List<AppNavigationDestination>,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit,
    onUniversalKey: (UniversalKey) -> Unit = {},
    isFirstRun: Boolean = false,
    onTour: (() -> Unit)? = null,
    onCloseTour: (() -> Unit)? = null,
    walkthroughBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val layout = appNavigationLayout(
        (windowSize.width / density.density).toInt(),
        (windowSize.height / density.density).toInt(),
        density.fontScale
    )
    val presentation = AppRoutePresentations.forRoute(currentRoute)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tourTargets = LocalWalkthroughTargets.current
    val drawerOpen = drawerState.isOpen
    SideEffect { tourTargets?.drawerOpen = drawerOpen }
    val isDrawer = presentation.isRoot && layout == AppNavigationLayout.Drawer
    val keysPlacement = universalKeysPlacement(presentation.isRoot, layout, onCloseTour != null, isFirstRun)
    val baseRoute = currentRoute?.substringBefore('?')?.substringBefore('/')
    LaunchedEffect(isDrawer) { if (!isDrawer) drawerState.close() }

    ModalNavigationDrawer(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        drawerState = drawerState,
        gesturesEnabled = isDrawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            AppIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.studio_nav_close), sprite = AppSpriteKey.BACK)
                        }
                        Text(stringResource(R.string.studio_nav_title), Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        if (onCloseTour != null) IconButton(onClick = onCloseTour, modifier = Modifier.testTag("tour_drawer_close")) {
                            Icon(Icons.Default.Close, stringResource(R.string.tour_close))
                        }
                    }
                    if (onCloseTour != null) Text(stringResource(R.string.tour_tap_target),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("soft_studio_navigation_drawer")) {
                    destinations.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.label) },
                            selected = destination.isSelected(currentRoute),
                            icon = { AppIcon(destination.icon, null, sprite = rootSprite(destination.route)) },
                            modifier = Modifier.testTag("studio_drawer_${destination.route}").walkthroughTarget("drawer.root.${destination.route}"),
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    destination.onClick()
                                }
                            }
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.settings_title)) },
                        selected = false,
                        icon = { AppIcon(Icons.Default.Settings, null, sprite = AppSpriteKey.SETTINGS) },
                        modifier = Modifier.walkthroughTarget("drawer.settings"),
                        onClick = { scope.launch { drawerState.close(); onSettings() } }
                    )
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (presentation.isRoot) {
                    TopAppBar(
                        title = { Text(presentation.parent?.let { rootLabel(destinations, it) } ?: stringResource(R.string.studio_nav_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (isDrawer) IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("soft_studio_menu").walkthroughTarget("shell.menu")
                            ) { Icon(Icons.Default.Menu, stringResource(R.string.studio_nav_menu)) }
                        },
                        actions = {
                            if (currentRoute == AppRootDestination.Home.route && onTour != null) {
                                TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text(stringResource(R.string.tour_title)) } },
                                    state = rememberTooltipState()) {
                                FilledTonalIconButton(onClick = onTour, colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer), modifier = Modifier.testTag("soft_studio_tour").walkthroughTarget("shell.tour")) {
                                    Icon(Icons.Default.Explore, stringResource(R.string.tour_title))
                                }
                                }
                            }
                            if (currentRoute != AppRootDestination.Home.route) FeatureGuideAction()
                            if (keysPlacement == UniversalKeysPlacement.TOP_BAR_ORGANIZER) {
                                IconButton(onClick = { onUniversalKey(UniversalKey.ORGANIZER) },
                                    modifier = Modifier.testTag("soft_studio_organizer").walkthroughTarget("universal.organizer")) {
                                    AppIcon(UniversalKey.ORGANIZER.icon, stringResource(R.string.universal_key_open, stringResource(UniversalKey.ORGANIZER.label)), sprite = UniversalKey.ORGANIZER.sprite)
                                }
                            }
                            IconButton(onClick = onSettings, modifier = Modifier.testTag("soft_studio_settings").walkthroughTarget("shell.settings")) {
                                AppIcon(Icons.Default.Settings, stringResource(R.string.settings_title), sprite = AppSpriteKey.SETTINGS)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            },
            bottomBar = {
                WalkthroughInsetHost(active = onCloseTour != null) {
                    if (onCloseTour != null) Box(if (presentation.isRoot && layout == AppNavigationLayout.Bar) Modifier else Modifier.navigationBarsPadding()) { walkthroughBar() }
                if (presentation.isRoot && layout == AppNavigationLayout.Bar) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        modifier = Modifier.testTag("soft_studio_navigation_bar")
                    ) {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = destination.isSelected(currentRoute),
                                onClick = destination.onClick,
                                icon = { AppIcon(destination.icon, null, sprite = rootSprite(destination.route)) },
                                label = { Text(destination.label, maxLines = 1) },
                                modifier = Modifier.testTag("studio_bar_${destination.route}").walkthroughTarget("root.${destination.route}")
                            )
                        }
                    }
                }
                if (keysPlacement == UniversalKeysPlacement.STRIP) UniversalKeyStrip(baseRoute, onUniversalKey)
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (presentation.isRoot && layout == AppNavigationLayout.Rail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("soft_studio_navigation_rail")
                    ) {
                        destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = destination.isSelected(currentRoute),
                                onClick = destination.onClick,
                                icon = { AppIcon(destination.icon, destination.contentDescription, sprite = rootSprite(destination.route)) },
                                label = { Text(destination.label, maxLines = 2) },
                                alwaysShowLabel = true,
                                modifier = Modifier.testTag("studio_rail_${destination.route}").walkthroughTarget("root.${destination.route}")
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()
                    .then(if (onCloseTour != null) Modifier.consumeWindowInsets(WindowInsets.ime) else Modifier)
                    .testTag("studio_route_${currentRoute?.substringBefore('?')?.substringBefore('/') ?: "loading"}")) {
                    content(PaddingValues())
                }
            }
        }
    }
}

private fun rootLabel(destinations: List<AppNavigationDestination>, root: AppRootDestination): String? =
    destinations.firstOrNull { it.route == root.route }?.label

/** Pixel sprite for each root destination; unknown routes fall back to the vector-name lookup. */
private fun rootSprite(route: String): AppSpriteKey? = when (route) {
    AppRootDestination.Home.route -> AppSpriteKey.HOME
    AppRootDestination.Chats.route -> AppSpriteKey.CHATS
    AppRootDestination.Tools.route -> AppSpriteKey.TOOLS
    AppRootDestination.Library.route -> AppSpriteKey.LIBRARY
    AppRootDestination.Tama.route -> AppSpriteKey.TAMA
    else -> null
}
