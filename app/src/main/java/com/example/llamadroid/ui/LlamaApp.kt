package com.example.llamadroid.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import com.example.llamadroid.ui.walkthrough.*
import com.example.llamadroid.ui.navigation.AppNavigationLayout
import com.example.llamadroid.ui.navigation.appNavigationLayout
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavType

import androidx.navigation.navArgument

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.example.llamadroid.ui.navigation.AppRootDestination
import com.example.llamadroid.ui.navigation.AppRoutePresentations
import com.example.llamadroid.ui.navigation.SoftStudioAppScaffold
import com.example.llamadroid.ui.library.LibraryScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.llamadroid.ui.dashboard.DashboardScreen
import com.example.llamadroid.ui.models.ModelManagerScreen
import com.example.llamadroid.ui.models.ModelHubScreen
import com.example.llamadroid.ui.models.ModelLibraryScreen
import com.example.llamadroid.ui.chat.ChatScreen
import com.example.llamadroid.ui.chat.ChatWebViewHolder
import com.example.llamadroid.ui.settings.DailySupportPrompt
import com.example.llamadroid.ui.settings.SettingsHubScreen
import com.example.llamadroid.ui.settings.GeneralSettingsScreen
import com.example.llamadroid.ui.settings.LLMSettingsScreen
import com.example.llamadroid.ui.settings.ImageGenSettingsScreen
import com.example.llamadroid.ui.settings.WhisperSettingsScreen
import com.example.llamadroid.ui.settings.VideoUpscalerSettingsScreen
import com.example.llamadroid.ui.settings.SystemPromptsSettingsScreen
import com.example.llamadroid.ui.settings.PDFSettingsScreen
import com.example.llamadroid.ui.logs.LogsScreen
import com.example.llamadroid.ui.pdf.PDFToolboxScreen
import com.example.llamadroid.ui.pdf.PDFSummaryScreen
import com.example.llamadroid.ui.ai.AIHubScreen
import com.example.llamadroid.ui.ai.AiServersHubScreen
import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.ai.ImageGenScreen
import com.example.llamadroid.ui.ai.OnnxImageGenScreen
import com.example.llamadroid.ui.ai.OnnxBackgroundRemovalScreen
import com.example.llamadroid.ui.ai.OnnxTtsScreen
import com.example.llamadroid.ui.ai.OnnxTtsGalleryScreen
import com.example.llamadroid.ui.ai.LiveTranslatorScreen
import com.example.llamadroid.ui.ai.SDModelsScreen
import com.example.llamadroid.ui.ai.VideoGenScreen
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import com.example.llamadroid.ui.navigation.ImageGenUpscaleCompatibilityRedirect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R

import com.example.llamadroid.ui.ai.AudioTranscriptionScreen
import com.example.llamadroid.ui.ai.VideoInterpolationScreen
import com.example.llamadroid.ui.ai.VideoUpscalerScreen
import com.example.llamadroid.ui.models.WhisperModelsScreen
import com.example.llamadroid.ui.models.OnnxModelsScreen
import com.example.llamadroid.ui.models.ModelShareScreen
import com.example.llamadroid.ui.models.LiteRtModelsScreen
import com.example.llamadroid.ui.notes.NotesManagerScreen
import com.example.llamadroid.ui.knowledge.KnowledgeBaseScreen
import com.example.llamadroid.ui.knowledge.KnowledgeChunkReaderScreen
import com.example.llamadroid.ui.ai.VideoSumupScreen
import com.example.llamadroid.ui.ai.SubtitleBurnScreen
import com.example.llamadroid.ui.ai.WorkflowsScreen
import com.example.llamadroid.ui.kiwix.ZimManagerScreen
import com.example.llamadroid.ui.kiwix.KiwixViewerScreen
import com.example.llamadroid.ui.distributed.DistributedScreen
import com.example.llamadroid.ui.distributed.WorkerModeScreen
import com.example.llamadroid.ui.distributed.MasterModeScreen
import com.example.llamadroid.ui.distributed.NetworkVisualizationScreen
import com.example.llamadroid.ui.distributed.SdDistributedHubScreen
import com.example.llamadroid.ui.distributed.SdDistributedGalleryScreen
import com.example.llamadroid.ui.distributed.SdDistributedMasterScreen
import com.example.llamadroid.ui.distributed.SdDistributedNetworkScreen
import com.example.llamadroid.ui.distributed.SdDistributedRunConfigScreen
import com.example.llamadroid.ui.distributed.SdDistributedWorkerScreen
import com.example.llamadroid.ui.settings.WelcomeScreen
import com.example.llamadroid.ui.settings.AboutScreen
import com.example.llamadroid.ui.settings.StatsScreen
import com.example.llamadroid.ui.settings.BenchmarkHistoryScreen
import com.example.llamadroid.ui.settings.BenchmarkScreen
import com.example.llamadroid.ui.ai.DatasetScreen
import com.example.llamadroid.ui.ai.QuadtrixTrainerScreen
import com.example.llamadroid.ui.ai.QuadtrixWebUiScreen
import com.example.llamadroid.ui.ai.TermuxScreen
import com.example.llamadroid.ui.ai.TermuxWebViewScreen
import com.example.llamadroid.ui.ai.TermuxFileManagerScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.SharedFileData
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.FarmLivestockType
import com.example.llamadroid.tama.data.FARM_FUEL_BUCKET_ID
import com.example.llamadroid.tama.data.FARMLAND_UPGRADE_ID
import com.example.llamadroid.tama.data.FARM_HARVESTING_DRONE_FUEL_UPGRADE_ID
import com.example.llamadroid.tama.data.FARM_HARVESTING_DRONE_ID
import com.example.llamadroid.tama.data.FARM_PLANTING_DRONE_FUEL_UPGRADE_ID
import com.example.llamadroid.tama.data.FARM_PLANTING_DRONE_ID
import com.example.llamadroid.tama.data.FarmShopCatalog
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.farmDroneFuelUpgradeCostForLevel
import com.example.llamadroid.tama.data.farmDroneIdForFuelUpgradeId
import com.example.llamadroid.tama.ui.TamaChatScreen
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.ui.components.AssetDownloadDialog
import com.example.llamadroid.ui.components.AdaptiveAppNavigation
import com.example.llamadroid.ui.components.AppNavigationDestination
import com.example.llamadroid.util.AssetPackManagerUtil
import kotlinx.coroutines.launch

private data class SharedFileDestination(
    val label: String,
    val route: String,
    val target: SharedFileTarget,
    val sourceTag: String = target.legacyId
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaApp(
    sharedFileData: SharedFileData? = null,
    onSharedFileHandled: () -> Unit = {},
    pendingNavigationRoute: ExternalRouteResolution = ExternalRouteResolution.NoRoute,
    onNavigationHandled: () -> Unit = {},
    allowDailySupportPrompt: Boolean = false,
    allowAutomaticWalkthrough: Boolean = allowDailySupportPrompt,
    normalLaunchId: Int = 0,
    externalLaunchId: Int = 0
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // Check for first run
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedbackScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val settingsRepo = remember { SettingsRepository(context) }
    val tour: WalkthroughState = viewModel(factory = remember(settingsRepo) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WalkthroughState(settingsRepo.walkthrough) as T
        }
    })
    val tourTargets = remember { WalkthroughTargets() }
    LaunchedEffect(normalLaunchId) { tour.beginLaunch(normalLaunchId) }
    LaunchedEffect(externalLaunchId) { tour.interruptForExternalLaunch(externalLaunchId) }

    val hasCompletedWelcome by settingsRepo.hasCompletedWelcome.collectAsState()
    var showWelcome by remember { mutableStateOf(!hasCompletedWelcome) }
    
    // Shared Tama State
    // These services used to be created for every app route. Keep them lazy so scrolling an
    // unrelated settings or server screen does not compete with Tama database/game work.
    val tamaDatabaseHolder = remember { lazy { TamaDatabase.getInstance(context) } }
    val tamaDatabase by tamaDatabaseHolder
    val farmRepositoryHolder = remember { lazy { FarmRepository(tamaDatabase.farmDao(), context) } }
    val farmRepository by farmRepositoryHolder
    val farmEngineHolder = remember { lazy { FarmEngine(farmRepository) } }
    val farmEngine by farmEngineHolder
    val tamaGameEngineHolder = remember {
        lazy {
            TamaGameEngine(
                context = context,
                dao = tamaDatabase.tamaDao(),
                farmEngine = farmEngine,
                farmRepository = farmRepository,
                settingsRepo = settingsRepo
            )
        }
    }
    val tamaGameEngine by tamaGameEngineHolder
    DisposableEffect(Unit) {
        onDispose {
            if (tamaGameEngineHolder.isInitialized()) tamaGameEngine.close()
        }
    }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    DisposableEffect(scope) {
        onDispose {
            // TamaAgentService is lazily shared by the root composition. Cancelling this
            // scope when the app leaves composition releases its background jobs and prevents
            // a stale agent coroutine from retaining the root UI after activity recreation.
            scope.cancel()
        }
    }
    val tamaAgentServiceHolder = remember {
        lazy {
            TamaAgentService(
                context = context,
                dao = tamaDatabase.tamaDao(),
                settingsRepo = settingsRepo,
                ollamaService = OllamaService(context),
                scope = scope
            )
        }
    }
    val tamaAgentService by tamaAgentServiceHolder
    
    // Share intent chooser dialog
    var showShareChooser by remember { mutableStateOf(false) }
    var shareOptions by remember { mutableStateOf<List<SharedFileDestination>>(emptyList()) }
    var pendingShareData by remember { mutableStateOf<SharedFileData?>(null) }
    
    // Handle shared file
    LaunchedEffect(sharedFileData) {
        // A new launch supersedes a pending chooser. Do not clear the holder here: a chosen
        // destination may still be consuming the file after onSharedFileHandled clears input.
        showShareChooser = false
        pendingShareData = null
        shareOptions = emptyList()
        sharedFileData?.let { data ->
            pendingShareData = data  // Store for later use by chooser
            val mimeType = data.mimeType
            when {
                // Audio -> User chooses Whisper or Workflow
                mimeType.startsWith("audio/") -> {
                    shareOptions = listOf(
                        SharedFileDestination(
                            resources.getString(R.string.share_transcribe),
                            Screen.AudioTranscription.route,
                            SharedFileTarget.AUDIO_TRANSCRIPTION
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_workflow),
                            Screen.Workflows.route,
                            SharedFileTarget.WORKFLOWS
                        )
                    )
                    showShareChooser = true
                }
                // Video -> User chooses Whisper, Video Upscaler, or Workflow
                mimeType.startsWith("video/") -> {
                    shareOptions = listOf(
                        SharedFileDestination(
                            resources.getString(R.string.share_interpolation),
                            Screen.VideoInterpolation.route,
                            SharedFileTarget.VIDEO_INTERPOLATION
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_upscaler),
                            Screen.VideoUpscaler.route,
                            SharedFileTarget.VIDEO_UPSCALER
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_transcribe),
                            Screen.AudioTranscription.route,
                            SharedFileTarget.AUDIO_TRANSCRIPTION
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_workflow),
                            Screen.Workflows.route,
                            SharedFileTarget.WORKFLOWS
                        )
                    )
                    showShareChooser = true
                }
                // Image -> User chooses SD img2img or upscale
                mimeType.startsWith("image/") -> {
                    shareOptions = listOf(
                        SharedFileDestination(
                            resources.getString(R.string.share_img2img),
                            Screen.ImageGen.createRoute(startMode = 1),
                            SharedFileTarget.IMAGE_GENERATION,
                            sourceTag = SharedFileHolder.Target.IMAGE_GEN_IMG2IMG
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_img2vid),
                            Screen.VideoGen.route,
                            SharedFileTarget.VIDEO_GENERATION,
                            sourceTag = SharedFileHolder.Target.VIDEO_GEN_IMG2VID
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_upscale_sd),
                            Screen.ImageGen.createRoute(startMode = 2),
                            SharedFileTarget.IMAGE_GENERATION,
                            sourceTag = SharedFileHolder.Target.IMAGE_GEN_UPSCALE
                        )
                    )
                    showShareChooser = true
                }
                // PDF -> choose the document tool instead of silently consuming the share.
                mimeType == "application/pdf" -> {
                    shareOptions = listOf(
                        SharedFileDestination(
                            resources.getString(R.string.share_pdf_toolbox),
                            Screen.PDFToolbox.route,
                            SharedFileTarget.PDF_TOOLBOX
                        ),
                        SharedFileDestination(
                            resources.getString(R.string.share_pdf_summary),
                            Screen.PDFSummary.route,
                            SharedFileTarget.PDF_SUMMARY
                        )
                    )
                    showShareChooser = true
                }
            }
        }
    }
    
    // Share chooser dialog
    if (showShareChooser && pendingShareData != null) {
        AlertDialog(
            onDismissRequest = { 
                showShareChooser = false
                pendingShareData = null
                SharedFileHolder.clear()
                onSharedFileHandled()
            },
            title = { Text(stringResource(R.string.action_open_with)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    shareOptions.forEach { destination ->
                        TextButton(
                            onClick = {
                                showShareChooser = false
                                pendingShareData?.let { data: SharedFileData ->
                                    SharedFileHolder.setPendingFile(
                                        uri = data.uri,
                                        mimeType = data.mimeType,
                                        target = destination.target,
                                        sourceTag = destination.sourceTag
                                    )
                                    try {
                                        navController.navigate(destination.route)
                                    } catch (_: IllegalArgumentException) {
                                        SharedFileHolder.clear()
                                        feedbackScope.launch {
                                            snackbarHostState.showSnackbar(
                                                resources.getString(R.string.navigation_destination_unavailable)
                                            )
                                        }
                                    }
                                }
                                pendingShareData = null
                                onSharedFileHandled()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(destination.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    showShareChooser = false
                    pendingShareData = null
                    SharedFileHolder.clear()
                    onSharedFileHandled()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    LaunchedEffect(pendingNavigationRoute, currentRoute) {
        when (val resolution = pendingNavigationRoute) {
            ExternalRouteResolution.NoRoute -> Unit
            ExternalRouteResolution.Rejected -> {
                feedbackScope.launch {
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.navigation_destination_unavailable)
                    )
                }
                onNavigationHandled()
            }
            is ExternalRouteResolution.Navigate -> {
                // The external intent is available before NavHost has installed its graph on a
                // cold launch. Wait for the first back-stack entry instead of reading graph early.
                if (currentRoute == null) return@LaunchedEffect
                if (currentRoute != resolution.route) {
                    try {
                        navController.navigate(resolution.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    } catch (_: IllegalArgumentException) {
                        feedbackScope.launch {
                            snackbarHostState.showSnackbar(
                                resources.getString(R.string.navigation_destination_unavailable)
                            )
                        }
                        return@LaunchedEffect
                    }
                }
                onNavigationHandled()
            }
        }
    }
    
    fun navigateFromAppNavigation(root: AppRootDestination) {
        navController.navigate(root.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val directNavigationDestinations = listOf(
        Triple(AppRootDestination.Home, R.string.studio_nav_home, Icons.Default.Home),
        Triple(AppRootDestination.Tools, R.string.studio_nav_tools, Icons.Default.GridView),
        Triple(AppRootDestination.Library, R.string.studio_nav_library, Icons.Default.FolderOpen),
        Triple(AppRootDestination.Tama, R.string.studio_nav_tama, Icons.Default.FavoriteBorder)
    ).map { (root, labelRes, icon) ->
        AppNavigationDestination(
            route = root.route,
            label = stringResource(labelRes),
            icon = icon,
            isSelected = { route -> AppRoutePresentations.forRoute(route).parent == root },
            onClick = { navigateFromAppNavigation(root) }
        )
    }

    // Show welcome screen on first run
    if (showWelcome && !hasCompletedWelcome) {
        WelcomeScreen(
            onComplete = {
                showWelcome = false
            }
        )
        return
    }

    val tourEligible = allowAutomaticWalkthrough && currentRoute == Screen.Dashboard.route &&
        sharedFileData == null && !showShareChooser && pendingNavigationRoute == ExternalRouteResolution.NoRoute
    LaunchedEffect(tourEligible) { tour.observeEligibility(tourEligible) }
    LaunchedEffect(sharedFileData, pendingNavigationRoute) {
        if (sharedFileData != null || pendingNavigationRoute != ExternalRouteResolution.NoRoute) tour.dismiss()
    }
    val tourDensity = LocalDensity.current
    val tourWindow = LocalWindowInfo.current.containerSize
    val tourNavigationLayout = appNavigationLayout((tourWindow.width / tourDensity.density).toInt(),
        (tourWindow.height / tourDensity.density).toInt(), tourDensity.fontScale)
    val tourSession = tour.session
    val tourRequestedTarget = tour.step?.let {
        tourTarget(it, currentRoute, tourNavigationLayout == AppNavigationLayout.Drawer, tourTargets.drawerOpen)
    }
    SideEffect {
        tourTargets.active = tourSession != null
        tourTargets.requestedId = tourRequestedTarget
    }

    DailySupportPrompt(
        settings = settingsRepo,
        launchId = normalLaunchId,
        eligible = allowDailySupportPrompt && !tour.awaitingAutomaticPresentation &&
            (tour.automaticCheckFinished || !settingsRepo.walkthrough.automaticEligible) &&
            !tour.suppressSupportForLaunch && currentRoute != null &&
            AppRoutePresentations.forRoute(currentRoute).isRoot &&
            sharedFileData == null && !showShareChooser &&
            pendingNavigationRoute == ExternalRouteResolution.NoRoute
    )

    val featureGuide = FeatureGuideCatalog.forRoute(currentRoute)
    val openTourRoute: (String) -> Unit = { route ->
        val root = AppRootDestination.entries.firstOrNull { it.route == route }
        if (root != null) navigateFromAppNavigation(root)
        else if ('{' !in route && '}' !in route) {
            navController.navigate(if (route == Screen.Chat.route) Screen.LlamaServers.route else route) { launchSingleTop = true }
        }
    }
    CompositionLocalProvider(LocalWalkthroughTargets provides tourTargets,
        LocalWalkthroughActive provides (tourSession != null),
        LocalWalkthroughPresentation provides WalkthroughPresentation(tour, tourTargets, currentRoute, openTourRoute),
        LocalFeatureGuideEntry provides featureGuide?.let { FeatureGuideEntry(it.id, tour::openFeatureGuide) }) {
    FeatureGuideChooser(tour, currentRoute)
    WalkthroughHighlight(tourTargets) {
    SoftStudioAppScaffold(
        currentRoute = currentRoute,
        destinations = directNavigationDestinations,
        snackbarHostState = snackbarHostState,
        onSettings = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
        onTour = {
            tour.openGuide()
            navController.navigate(Screen.Walkthrough.route) { launchSingleTop = true }
        },
        onCloseTour = if (tour.session != null) ({ tour.dismiss() }) else null,
        walkthroughBar = {
            if (tourTargets.modalOwners.isEmpty()) WalkthroughCoach(tour, tourTargets, currentRoute, onOpen = openTourRoute)
        }
    ) { innerPadding ->
        NavHost(
            navController = navController, 
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(240)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(240)) },
            popExitTransition = { fadeOut(tween(180)) }
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(navController) }
            composable(Screen.Walkthrough.route) {
                WalkthroughGuide(tour, onBack = { navController.popBackStack() }, onStart = { chapterId, resume ->
                    val root = when (chapterId) {
                        CoreTour.ID, "settings_help" -> AppRootDestination.Home
                        "tama" -> AppRootDestination.Tama
                        else -> AppRootDestination.Tools
                    }
                    // The guide belongs to Home. Remove it before saving/restoring a root,
                    // otherwise navigating Home restores the guide we just saved above it.
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                    if (root != AppRootDestination.Home) {
                        navigateFromAppNavigation(root)
                        navController.popBackStack(root.route, inclusive = false)
                    }
                    tour.start(chapterId, resume)
                })
            }
            composable(Screen.Settings.route) { SettingsHubScreen(navController) }
            composable(Screen.Stats.route) { StatsScreen(navController) }
            composable(Screen.Logs.route) { LogsScreen(navController) }
            // AI screens
            composable(Screen.AIHub.route) { AIHubScreen(navController) }
            composable(Screen.Library.route) { LibraryScreen(navController) }
            composable(Screen.AiServersHub.route) { AiServersHubScreen(navController) }
            composable(Screen.FileServer.route) {
                com.example.llamadroid.ui.components.AppScreenScaffold(
                    title = stringResource(R.string.dashboard_file_server),
                    onBack = { navController.popBackStack() }
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        com.example.llamadroid.ui.dashboard.DashboardFileServerCard()
                    }
                }
            }
            composable(
                route = "${Screen.Chat.route}?port={serverPort}",
                arguments = listOf(
                    androidx.navigation.navArgument("serverPort") {
                        type = androidx.navigation.NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { backStackEntry ->
                val serverPort = backStackEntry.arguments?.getInt("serverPort")?.takeIf { it in 1..65535 }
                ChatScreen(navController, serverPortOverride = serverPort)
            }
            composable(Screen.LlamaServers.route) {
                com.example.llamadroid.ui.ai.llama.LlamaServerCardsScreen(navController)
            }
            composable(
                route = "${Screen.ImageGen.route}?startMode={startMode}&tab={tab}",
                arguments = listOf(
                    androidx.navigation.navArgument("startMode") {
                        type = androidx.navigation.NavType.IntType
                        defaultValue = 0
                    },
                    androidx.navigation.navArgument("tab") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "create"
                    }
                )
            ) { backStackEntry ->
                val startMode = backStackEntry.arguments?.getInt("startMode") ?: 0
                ImageGenScreen(navController, initialMode = startMode,
                    initialTab = backStackEntry.arguments?.getString("tab") ?: "create")
            }
            // Keep the historical route for shortcuts and saved navigation state, but render the
            // same curated workspace and task selector as every other image operation.
            composable(Screen.ImageGenUpscale.route) {
                ImageGenUpscaleCompatibilityRedirect(navController)
            }
            composable(Screen.OnnxImageGen.route) { OnnxImageGenScreen(navController) }
            composable(Screen.OnnxBackgroundRemoval.route) { OnnxBackgroundRemovalScreen(navController) }
            composable(Screen.OnnxTts.route) { OnnxTtsScreen(navController) }
            composable(Screen.OnnxTtsGallery.route) { OnnxTtsGalleryScreen(navController) }
            composable(Screen.LiveTranslator.route) { LiveTranslatorScreen(navController) }
            composable(
                route = "${Screen.VideoGen.route}?tab={tab}",
                arguments = listOf(androidx.navigation.navArgument("tab") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "create"
                })
            ) { entry ->
                VideoGenScreen(navController, initialTab = entry.arguments?.getString("tab") ?: "create")
            }
            composable(Screen.AudioTranscription.route) { AudioTranscriptionScreen(navController) }
            composable(Screen.VideoUpscaler.route) { VideoUpscalerScreen(navController) }
            composable(Screen.VideoInterpolation.route) { VideoInterpolationScreen(navController) }
            composable(Screen.SubtitleBurn.route) { SubtitleBurnScreen(navController) }
            composable(Screen.NotesManager.route) { NotesManagerScreen(navController) }
            composable(Screen.KnowledgeBase.route) { KnowledgeBaseScreen(navController) }
            composable(
                Screen.KnowledgeChunkReader.route,
                arguments = listOf(
                    androidx.navigation.navArgument("chunkId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStackEntry ->
                val chunkId = backStackEntry.arguments?.getLong("chunkId") ?: -1L
                KnowledgeChunkReaderScreen(navController, chunkId)
            }
            composable(Screen.Workflows.route) { WorkflowsScreen(navController) }
            // Model screens
            composable(Screen.ModelHub.route) { ModelHubScreen(navController) }
            composable("${Screen.ModelSources.route}?family={family}&tab={tab}", arguments = listOf(
                navArgument("family") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }
            )) { entry -> ModelLibraryScreen(navController, entry.arguments?.getString("family"), entry.arguments?.getString("tab")) }
            composable(Screen.LLMModels.route) { ModelManagerScreen(navController) }
            composable(Screen.SDModels.route) { SDModelsScreen(navController) }
            composable(Screen.OnnxModels.route) { OnnxModelsScreen(navController) }
            composable(Screen.WhisperModels.route) { WhisperModelsScreen(navController) }
            composable(Screen.LiteRtModels.route) { LiteRtModelsScreen(navController) }
            composable("model_share") { ModelShareScreen(navController) }
            // Settings sub-screens
            composable("settings_general") { GeneralSettingsScreen(navController) }
            composable("settings_llm") { LLMSettingsScreen(navController) }
            composable("settings_imagegen") { ImageGenSettingsScreen(navController) }
            composable("settings_whisper") { WhisperSettingsScreen(navController) }
            composable("settings_upscaler") { VideoUpscalerSettingsScreen(navController) }
            composable("settings_prompts") { SystemPromptsSettingsScreen(navController) }
            composable("settings_logs") { LogsScreen(navController) }
            // PDF screens
            composable(Screen.PDFToolbox.route) { PDFToolboxScreen(navController) }
            composable(Screen.PDFSummary.route) { PDFSummaryScreen(navController) }
            composable(Screen.PDFSettings.route) { PDFSettingsScreen(navController) }
            composable("video_sumup") { VideoSumupScreen(navController) }
            composable("about") { AboutScreen(navController) }
            // Kiwix screens
            composable(Screen.ZimManager.route) { ZimManagerScreen(navController) }
            composable(
                route = "kiwix_viewer?zimPath={zimPath}",
                arguments = listOf(
                    androidx.navigation.navArgument("zimPath") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val zimPath = backStackEntry.arguments?.getString("zimPath")
                KiwixViewerScreen(navController, zimPath)
            }
            // Distributed inference screens
            composable(Screen.DistributedHub.route) { DistributedScreen(navController) }
            composable(Screen.WorkerMode.route) { WorkerModeScreen(navController) }
            composable(Screen.MasterMode.route) { MasterModeScreen(navController) }
            composable(Screen.NetworkVisualization.route) { NetworkVisualizationScreen(navController) }
            composable(Screen.SdDistributedHub.route) { SdDistributedHubScreen(navController) }
            composable(Screen.SdDistributedWorker.route) { SdDistributedWorkerScreen(navController) }
            composable(Screen.SdDistributedMaster.route) { SdDistributedMasterScreen(navController) }
            composable(Screen.SdDistributedNetwork.route) { SdDistributedNetworkScreen(navController) }
            composable(Screen.SdDistributedRunConfig.route) { SdDistributedRunConfigScreen(navController) }
            composable(Screen.SdDistributedGallery.route) { SdDistributedGalleryScreen(navController) }
            // Benchmark
            composable(Screen.Benchmark.route) { BenchmarkScreen(navController) }
            composable(Screen.BenchmarkHistory.route) { BenchmarkHistoryScreen(navController) }
            // Dataset Creator
            composable(Screen.Dataset.route) { DatasetScreen(navController) }
            composable(Screen.QuadtrixTrainer.route) { QuadtrixTrainerScreen(navController) }
            composable(
                Screen.QuadtrixWebUi.route,
                arguments = listOf(
                    androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val url = backStackEntry.arguments?.getString("url") ?: ""
                QuadtrixWebUiScreen(navController, url)
            }
            composable(
                Screen.DatasetProject.route,
                arguments = listOf(
                    androidx.navigation.navArgument("projectId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getLong("projectId") ?: 0L
                com.example.llamadroid.ui.dataset.DatasetProjectScreen(navController, projectId)
            }
            // Termux SSH
            composable(Screen.Termux.route) { TermuxScreen(navController) }
            // Termux WebView for server UIs
            composable(
                Screen.TermuxWebView.route,
                arguments = listOf(
                    androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("title") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("toolId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val url = backStackEntry.arguments?.getString("url") ?: ""
                val title = backStackEntry.arguments?.getString("title") ?: stringResource(R.string.nav_title_server)
                val toolId = backStackEntry.arguments?.getString("toolId") ?: "none"
                TermuxWebViewScreen(navController, url, title, toolId)
            }
            
            // Termux File Manager
            composable(Screen.TermuxFileManager.route) {
                TermuxFileManagerScreen(navController)
            }
            
            // FastSD Gallery
            composable(Screen.FastsdGallery.route) {
                com.example.llamadroid.ui.ai.FastsdGalleryScreen(navController)
            }
            
            // AI Agent
            composable(
                "${Screen.Agent.route}?conversationId={conversationId}",
                arguments = listOf(navArgument("conversationId") {
                    type = NavType.LongType
                    defaultValue = -1L
                })
            ) { backStackEntry ->
                com.example.llamadroid.ui.agent.AgentScreen(
                    navController,
                    initialConversationId = backStackEntry.arguments?.getLong("conversationId")?.takeIf { it > 0L }
                )
            }
            
            // Tama Farming
            composable(Screen.Farm.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                
                // A missing pet must offer a usable way back and setup, including during a tour.
                if (pet == null) {
                    com.example.llamadroid.ui.walkthrough.TamaSetupState(
                        onBack = { navController.popBackStack() },
                        onOpenTama = { navigateFromAppNavigation(AppRootDestination.Tama) }
                    )
                    return@composable
                }
                
                val currentPet = pet!!  // Safe: already checked pet != null above
                com.example.llamadroid.tama.ui.FarmScreen(
                    pet = currentPet,
                    gameEngine = tamaGameEngine,
                    farmRepository = farmRepository,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Barn.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                pet?.let { currentPet ->
                    com.example.llamadroid.tama.ui.BarnScreen(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        farmRepository = farmRepository,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(Screen.Coop.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                pet?.let { currentPet ->
                    com.example.llamadroid.tama.ui.ChickenCoopScreen(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        farmRepository = farmRepository,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            
            // Ollama Manager
            composable(Screen.OllamaManager.route) {
                com.example.llamadroid.ui.ai.ollama.OllamaManagerScreen(navController)
            }
            
            // Native Llama Client
            composable(Screen.LlamaServerList.route) {
                com.example.llamadroid.ui.ai.llama.LlamaServerListScreen(navController)
            }
            composable(Screen.LlamaChatList.route) {
                com.example.llamadroid.ui.ai.llama.LlamaChatListScreen(navController)
            }
            composable(
                route = Screen.LlamaChatList.folderRoute,
                arguments = listOf(
                    androidx.navigation.navArgument("folderId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStackEntry ->
                val folderId = backStackEntry.arguments?.getLong("folderId")
                com.example.llamadroid.ui.ai.llama.LlamaChatListScreen(
                    navController = navController,
                    initialFolderId = folderId
                )
            }
            composable(Screen.LlamaScheduler.route) {
                com.example.llamadroid.ui.ai.llama.LlamaSchedulerScreen(navController)
            }
            composable(
                route = Screen.LlamaChat.route,
                arguments = listOf(
                    androidx.navigation.navArgument("chatId") { type = androidx.navigation.NavType.LongType },
                    androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getLong("chatId") ?: -1L
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: -1L
                com.example.llamadroid.ui.ai.llama.LlamaChatScreen(navController, chatId, serverId)
            }
            
            composable(Screen.Store.route) {
                val petState by tamaGameEngine.pet.collectAsState()
                if (petState == null) {
                    com.example.llamadroid.ui.walkthrough.TamaSetupState(
                        onBack = { navController.popBackStack() },
                        onOpenTama = { navigateFromAppNavigation(AppRootDestination.Tama) }
                    )
                }
                petState?.let { activePet ->
                    val farmUpgrades by farmRepository.observeUpgrades(activePet.id).collectAsState(initial = emptyList())
                    val livestock by farmRepository.observeLivestock(activePet.id).collectAsState(initial = emptyList())
                    com.example.llamadroid.tama.ui.StoreScreen(
                        pet = activePet,
                        farmRepository = farmRepository,
                        upgrades = farmUpgrades,
                        livestock = livestock,
                        onBuy = { item, qty ->
                            val baseId = item.id.replace("seed_", "").replace("hoe", "wheat").replace("watering_can", "wheat") // Simple price lookup
                            val price = when {
                                item.id.startsWith("seed_") -> CropDefinitions.CROPS[baseId]?.seedPrice?.toLong() ?: 10L
                                item.id == "hoe" -> 100L
                                item.id == "watering_can" -> 150L
                                item.id == "fertilizer" -> FarmShopCatalog.materialBuyPrice(item.id).toLong()
                                item.id == FARM_FUEL_BUCKET_ID -> FarmShopCatalog.materialBuyPrice(item.id).toLong()
                                else -> 5L
                            }
                            tamaGameEngine.buyItem(item, qty, price.toInt())
                        },
                        onSell = { item, qty ->
                            val price = FarmTradeItemCatalog.sellPrice(item.id).toLong().coerceAtLeast(5L)
                            tamaGameEngine.sellItem(item, qty, price)
                        },
                        onBuyUpgrade = { type, price ->
                            val existingUpgrade = farmRepository.getUpgrade(activePet.id, type)
                            val isFarmland = type == FARMLAND_UPGRADE_ID
                            val droneFuelTarget = farmDroneIdForFuelUpgradeId(type)
                            val displayName = when (type) {
                                FARMLAND_UPGRADE_ID -> resources.getString(R.string.tama_farm_upgrade_farmland)
                                "well" -> resources.getString(R.string.tama_farm_upgrade_well)
                                "composter" -> resources.getString(R.string.tama_farm_upgrade_composter)
                                FARM_PLANTING_DRONE_FUEL_UPGRADE_ID -> resources.getString(R.string.tama_farm_drone_fuel_upgrade_name, resources.getString(R.string.tama_farm_planting_drone))
                                FARM_HARVESTING_DRONE_FUEL_UPGRADE_ID -> resources.getString(R.string.tama_farm_drone_fuel_upgrade_name, resources.getString(R.string.tama_farm_harvesting_drone))
                                else -> type.replaceFirstChar { it.uppercase() }
                            }
                            if (droneFuelTarget != null) {
                                val droneUpgrade = farmRepository.getUpgrade(activePet.id, droneFuelTarget)
                                if (droneUpgrade?.isPurchased != true) {
                                    TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_upgrade_already_owned))
                                } else {
                                    val now = System.currentTimeMillis()
                                    val cost = if (droneFuelTarget == FARM_PLANTING_DRONE_ID) {
                                        val state = farmRepository.decodePlantingDroneState(droneUpgrade, now)
                                        farmDroneFuelUpgradeCostForLevel(state.fuelUpgradeLevel)
                                    } else {
                                        val state = farmRepository.decodeHarvesterDroneState(droneUpgrade, now)
                                        farmDroneFuelUpgradeCostForLevel(state.fuelUpgradeLevel)
                                    }
                                    if (cost == null) {
                                        TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_farm_upgrade_maxed))
                                    } else if (!tamaGameEngine.spendMoney(cost.toLong())) {
                                        TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_action_not_enough_money))
                                    } else {
                                        if (droneFuelTarget == FARM_PLANTING_DRONE_ID) {
                                            val state = farmRepository.decodePlantingDroneState(droneUpgrade, now)
                                            farmRepository.savePlantingDroneState(
                                                activePet.id,
                                                state.copy(fuelUpgradeLevel = state.fuelUpgradeLevel + 1, lastUpdatedAt = now)
                                            )
                                        } else {
                                            val state = farmRepository.decodeHarvesterDroneState(droneUpgrade, now)
                                            farmRepository.saveHarvesterDroneState(
                                                activePet.id,
                                                state.copy(fuelUpgradeLevel = state.fuelUpgradeLevel + 1, lastUpdatedAt = now)
                                            )
                                        }
                                        tamaGameEngine.logEvent(activePet.id, EventType.OTHER, resources.getString(R.string.event_purchased_upgrade, displayName))
                                        TamaGameEngine.ActionResult(true, resources.getString(R.string.tama_action_bought_item, 1, displayName))
                                    }
                                }
                            } else if (!isFarmland && existingUpgrade?.isPurchased == true) {
                                TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_upgrade_already_owned))
                            } else if (tamaGameEngine.spendMoney(price.toLong())) {
                                val upgraded = if (isFarmland) {
                                    farmRepository.upgradeFarmland(activePet.id)
                                } else {
                                    farmRepository.buyUpgrade(activePet.id, type, price)
                                    true
                                }
                                if (upgraded) {
                                    tamaGameEngine.logEvent(activePet.id, EventType.OTHER, resources.getString(R.string.event_purchased_upgrade, displayName))
                                    TamaGameEngine.ActionResult(true, resources.getString(R.string.tama_action_bought_item, 1, displayName))
                                } else {
                                    tamaGameEngine.awardMoney(price.toLong())
                                    TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_farm_upgrade_maxed))
                                }
                            } else {
                                TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_action_not_enough_money))
                            }
                        },
                        onBuyDrone = { type, price ->
                            val displayName = resources.getString(
                                if (type == FARM_PLANTING_DRONE_ID) R.string.tama_farm_planting_drone else R.string.tama_farm_harvesting_drone
                            )
                            val existingUpgrade = farmRepository.getUpgrade(activePet.id, type)
                            val alreadyInInventory = activePet.inventory.any { it.id == type }
                            if (existingUpgrade?.isPurchased == true || alreadyInInventory) {
                                TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_upgrade_already_owned))
                            } else {
                                val result = tamaGameEngine.buyItem(
                                    InventoryItem(
                                        id = type,
                                        name = displayName,
                                        type = ItemType.TOOL
                                    ),
                                    1,
                                    price
                                )
                                if (result.success) {
                                    farmRepository.buyUpgrade(activePet.id, type, price)
                                    tamaGameEngine.logEvent(
                                        activePet.id,
                                        EventType.OTHER,
                                        resources.getString(R.string.event_purchased_upgrade, displayName)
                                    )
                                }
                                result
                            }
                        },
                        onBuyLivestock = { type ->
                            val occupied = farmRepository.decodeLivestockSlots(
                                livestock.firstOrNull { it.type == type.id },
                                type
                            ).count { it.occupied }
                            if (occupied >= type.maxAnimals) {
                                TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_farm_livestock_limit_reached))
                            } else if (!tamaGameEngine.spendMoney(type.buyPrice.toLong())) {
                                TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_action_not_enough_money))
                            } else if (farmRepository.buyLivestockAnimal(activePet.id, type)) {
                                tamaGameEngine.logEvent(
                                    activePet.id,
                                    EventType.OTHER,
                                    resources.getString(
                                        if (type == FarmLivestockType.BARN) R.string.tama_event_bought_cow else R.string.tama_event_bought_chicken
                                    )
                                )
                                TamaGameEngine.ActionResult(
                                    true,
                                    resources.getString(
                                        if (type == FarmLivestockType.BARN) R.string.tama_farm_livestock_bought_cow else R.string.tama_farm_livestock_bought_chicken
                                    )
                                )
                            } else {
                                TamaGameEngine.ActionResult(false, resources.getString(R.string.tama_farm_livestock_limit_reached))
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            
            // Agent Workspace File Manager
            composable(Screen.AgentWorkspace.route) {
                com.example.llamadroid.ui.agent.AgentWorkspaceScreen(navController)
            }
            composable(
                Screen.AgentInvocation.route,
                arguments = listOf(
                    androidx.navigation.navArgument("invocationId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                com.example.llamadroid.ui.agent.AgentInvocationDetailScreen(
                    navController = navController,
                    invocationId = backStackEntry.arguments?.getString("invocationId").orEmpty()
                )
            }
            
            // Tama virtual pet
            composable(Screen.Tama.route) {
                com.example.llamadroid.tama.ui.TamaScreen(
                    navController = navController,
                    gameEngine = tamaGameEngine,
                    settingsRepo = settingsRepo,
                    agentService = tamaAgentService,
                    onChat = { navController.navigate(Screen.TamaChat.route) }
                )
            }

            composable(Screen.TamaGallery.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                if (pet == null) {
                    com.example.llamadroid.ui.walkthrough.TamaSetupState(
                        onBack = { navController.popBackStack() },
                        onOpenTama = { navigateFromAppNavigation(AppRootDestination.Tama) }
                    )
                    return@composable
                }
                com.example.llamadroid.tama.ui.TamaGalleryScreen(
                    navController = navController,
                    gameEngine = tamaGameEngine,
                    pet = pet!!
                )
            }

            composable(Screen.Arcade.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                if (pet == null) {
                    com.example.llamadroid.ui.walkthrough.TamaSetupState(
                        onBack = { navController.popBackStack() },
                        onOpenTama = { navigateFromAppNavigation(AppRootDestination.Tama) }
                    )
                    return@composable
                }
                com.example.llamadroid.tama.ui.ArcadeScreen(
                    navController = navController,
                    gameEngine = tamaGameEngine,
                    pet = pet!!
                )
            }
            
            composable(Screen.TamaChat.route) {
                TamaChatScreen(
                    navController = navController,
                    gameEngine = tamaGameEngine,
                    agentService = tamaAgentService,
                    settingsRepo = settingsRepo
                )
            }
            
            // Tama Dungeon/Adventure
            composable(Screen.Dungeon.route) {
                com.example.llamadroid.tama.ui.DungeonScreen(
                    navController = navController,
                    database = tamaDatabase,
                    settingsRepository = settingsRepo
                )
            }
            
            composable(
                Screen.Adventure.route,
                arguments = listOf(
                    androidx.navigation.navArgument("dungeonType") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val dungeonTypeName = backStackEntry.arguments?.getString("dungeonType") ?: "CHAOS_REALM"
                com.example.llamadroid.tama.ui.AdventureScreen(
                    navController = navController,
                    dungeonTypeName = dungeonTypeName,
                    database = tamaDatabase,
                    settingsRepository = settingsRepo
                )
            }

            composable(Screen.AdventureGate.route) {
                com.example.llamadroid.tama.ui.AdventureGateScreen(
                    navController = navController,
                    database = tamaDatabase
                )
            }

            composable(Screen.NightArena.route) {
                com.example.llamadroid.tama.ui.AdventureGateScreen(
                    navController = navController,
                    database = tamaDatabase,
                    mode = com.example.llamadroid.tama.ui.AdventureGateScreenMode.NIGHT_ARENA
                )
            }
        }
    }
    }
    }
}
