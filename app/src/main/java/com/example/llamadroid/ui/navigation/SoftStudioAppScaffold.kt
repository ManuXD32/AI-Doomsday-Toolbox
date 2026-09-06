package com.example.llamadroid.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import com.example.llamadroid.ui.components.AppNavigationDestination
import kotlinx.coroutines.launch

/** Root chrome owns system insets once; deep screens own their contextual top bars and actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SoftStudioAppScaffold(
    currentRoute: String?,
    destinations: List<AppNavigationDestination>,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit,
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
    val isDrawer = presentation.isRoot && layout == AppNavigationLayout.Drawer

    ModalNavigationDrawer(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        drawerState = drawerState,
        gesturesEnabled = isDrawer,
        drawerContent = {
            ModalDrawerSheet {
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(12.dp)
                        .testTag("soft_studio_navigation_drawer")
                ) {
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, stringResource(R.string.studio_nav_close))
                    }
                    Text(stringResource(R.string.studio_nav_title),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleLarge)
                    destinations.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.label) },
                            selected = destination.isSelected(currentRoute),
                            icon = { Icon(destination.icon, null) },
                            modifier = Modifier.testTag("studio_drawer_${destination.route}"),
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
                        icon = { Icon(Icons.Default.Settings, null) },
                        onClick = { scope.launch { drawerState.close(); onSettings() } }
                    )
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
                        title = { Text(stringResource(R.string.studio_nav_title)) },
                        navigationIcon = {
                            if (isDrawer) IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("soft_studio_menu")
                            ) { Icon(Icons.Default.Menu, stringResource(R.string.studio_nav_menu)) }
                        },
                        actions = {
                            IconButton(onClick = onSettings, modifier = Modifier.testTag("soft_studio_settings")) {
                                Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            },
            bottomBar = {
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
                                icon = { Icon(destination.icon, null) },
                                label = { Text(destination.label, maxLines = 1) },
                                modifier = Modifier.testTag("studio_bar_${destination.route}")
                            )
                        }
                    }
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
                                icon = { Icon(destination.icon, destination.contentDescription) },
                                label = { Text(destination.label, maxLines = 2) },
                                alwaysShowLabel = true,
                                modifier = Modifier.testTag("studio_rail_${destination.route}")
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()
                    .testTag("studio_route_${currentRoute?.substringBefore('?')?.substringBefore('/') ?: "loading"}")) {
                    content(PaddingValues())
                }
            }
        }
    }
}
