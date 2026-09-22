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
import com.example.llamadroid.ui.library.AllMediaGalleryScreen
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
import com.example.llamadroid.ui.audio.AudioWorkspaceScreen
import com.example.llamadroid.ui.audio.AudioWorkspaceSection
import com.example.llamadroid.ui.audio.AudioWorkspaceRuntimeController
import com.example.llamadroid.ui.models.AudioModelsScreen
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
import android.widget.Toast
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
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.farmDroneFuelUpgradeCostForLevel
import com.example.llamadroid.tama.data.farmDroneIdForFuelUpgradeId
import com.example.llamadroid.tama.ui.TamaChatScreen
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.ui.components.AssetDownloadDialog
import com.example.llamadroid.ui.components.AdaptiveAppNavigation
import com.example.llamadroid.ui.components.AppNavigationDestination
import com.example.llamadroid.util.AssetPackManagerUtil
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.PendingActivityIntent
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.persistence.WorldInitializer
import com.example.llamadroid.tama.world.presentation.TamaWorldArrivalGate
import com.example.llamadroid.tama.world.presentation.ArcadeWorldActionBridge
import com.example.llamadroid.tama.world.presentation.localizedBrainUiLabels
import com.example.llamadroid.tama.world.presentation.localizedWorldRuntimeError
import com.example.llamadroid.tama.world.presentation.localizedWorldUiLabels
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
    externalLaunchId: Int = 0,
    arcadeWorldActionBridge: ArcadeWorldActionBridge = ArcadeWorldActionBridge.Unavailable,
    arcadeWorldActionBridgeFactory: ((TamaGameEngine) -> ArcadeWorldActionBridge)? = null
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
    val activeArcadeWorldActionBridge = remember(
        tamaGameEngine,
        arcadeWorldActionBridge,
        arcadeWorldActionBridgeFactory
    ) {
        arcadeWorldActionBridgeFactory?.invoke(tamaGameEngine)
            ?: arcadeWorldActionBridge.takeUnless { it === ArcadeWorldActionBridge.Unavailable }
            ?: ArcadeWorldActionBridge.from(
                beginSession = { request ->
                    // Classic play shares the durable reward receipt, without physical travel.
                    val admitted = tamaGameEngine.isSimulatedWorldActive ||
                        tamaGameEngine.travelToId(LegacyLocationAliases.ARCADE).success
                    if (admitted) tamaGameEngine.world.beginArcadeSession(request)
                    else com.example.llamadroid.tama.world.presentation.ArcadeSessionLease(
                        request.petId, request.sessionId, request.gameId,
                        com.example.llamadroid.tama.world.presentation.ArcadeSessionLeaseStatus.UNAVAILABLE
                    )
                },
                submitSession = { tamaGameEngine.world.submitArcadeSession(it) },
                cancelSession = { tamaGameEngine.world.cancelArcadeSession(it) },
                reconcileSession = { tamaGameEngine.world.reconcileArcadeSession(it) },
                recoverSession = { tamaGameEngine.world.recoverArcadeSession(it) },
                acknowledgeSession = { tamaGameEngine.world.acknowledgeArcadeSession(it) }
            )
    }
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

    fun returnToPetHome() {
        if (!navController.popBackStack(Screen.Tama.route, inclusive = false)) {
            navigateFromAppNavigation(AppRootDestination.Tama)
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
            composable(Screen.AllMediaGallery.route) { AllMediaGalleryScreen(navController) }
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
            composable(
                route = "${Screen.AudioWorkspace.route}?section={section}",
                arguments = listOf(navArgument("section") {
                    type = NavType.StringType
                    defaultValue = "speech"
                })
            ) { entry ->
                val audioWorkspaceController = remember(entry) { AudioWorkspaceRuntimeController(context) }
                DisposableEffect(audioWorkspaceController) {
                    onDispose { audioWorkspaceController.close() }
                }
                AudioWorkspaceScreen(
                    navController = navController,
                    initialSection = AudioWorkspaceSection.fromRoute(entry.arguments?.getString("section")),
                    controller = audioWorkspaceController
                )
            }
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
            composable(Screen.AudioModels.route) { AudioModelsScreen(navController) }
            composable("${Screen.ModelSources.route}?family={family}&tab={tab}", arguments = listOf(
                navArgument("family") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }
            )) { entry -> ModelLibraryScreen(navController, entry.arguments?.getString("family"), entry.arguments?.getString("tab")) }
            composable(Screen.LLMModels.route) { ModelManagerScreen(navController) }
            composable(Screen.SDModels.route) { SDModelsScreen(navController) }
            composable(
                route = "${Screen.OnnxModels.route}?tab={tab}",
                arguments = listOf(navArgument("tab") {
                    type = NavType.StringType
                    defaultValue = "installed"
                })
            ) { entry ->
                OnnxModelsScreen(
                    navController,
                    initialTab = entry.arguments?.getString("tab")
                )
            }
            composable(Screen.WhisperModels.route) { WhisperModelsScreen(navController) }
            composable("${Screen.LiteRtModels.route}?tab={tab}",
                arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "installed" })
            ) { entry -> LiteRtModelsScreen(navController, initialTab = entry.arguments?.getString("tab")) }
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
                "${Screen.Agent.route}?conversationId={conversationId}&harnessTab={harnessTab}",
                arguments = listOf(navArgument("conversationId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }, navArgument("harnessTab") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { backStackEntry ->
                com.example.llamadroid.ui.agent.AgentScreen(
                    navController,
                    initialConversationId = backStackEntry.arguments?.getLong("conversationId")?.takeIf { it > 0L },
                    initialAttentionTab = backStackEntry.arguments?.getString("harnessTab")
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
                TamaWorldArrivalContent(
                    pet = currentPet,
                    gameEngine = tamaGameEngine,
                    database = tamaDatabase,
                    farmRepository = farmRepository,
                    destinationStructureId = LegacyLocationAliases.FARM,
                    onReturnToHome = ::returnToPetHome,
                    onBack = { navController.popBackStack() }
                ) {
                    com.example.llamadroid.tama.ui.FarmScreen(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        farmRepository = farmRepository,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(Screen.Barn.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                pet?.let { currentPet ->
                    TamaWorldArrivalContent(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        database = tamaDatabase,
                        farmRepository = farmRepository,
                        destinationStructureId = "farm_barn",
                        onReturnToHome = ::returnToPetHome,
                        onBack = { navController.popBackStack() }
                    ) {
                        com.example.llamadroid.tama.ui.BarnScreen(
                            pet = currentPet,
                            gameEngine = tamaGameEngine,
                            farmRepository = farmRepository,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }

            composable(Screen.Coop.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                pet?.let { currentPet ->
                    TamaWorldArrivalContent(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        database = tamaDatabase,
                        farmRepository = farmRepository,
                        destinationStructureId = "farm_barn",
                        onReturnToHome = ::returnToPetHome,
                        onBack = { navController.popBackStack() }
                    ) {
                        com.example.llamadroid.tama.ui.ChickenCoopScreen(
                            pet = currentPet,
                            gameEngine = tamaGameEngine,
                            farmRepository = farmRepository,
                            onBack = { navController.popBackStack() }
                        )
                    }
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
                    TamaWorldArrivalContent(
                        pet = activePet,
                        gameEngine = tamaGameEngine,
                        database = tamaDatabase,
                        farmRepository = farmRepository,
                        destinationStructureId = LegacyLocationAliases.SHOP,
                        onReturnToHome = ::returnToPetHome,
                        onBack = { navController.popBackStack() }
                    ) {
                    com.example.llamadroid.tama.ui.StoreScreen(
                        pet = activePet,
                        farmRepository = farmRepository,
                        upgrades = farmUpgrades,
                        livestock = livestock,
                        onBuy = { item, qty ->
                            val offer = com.example.llamadroid.tama.data.TamaCommerceCatalog.offer(
                                context, item.id, LegacyLocationAliases.SHOP
                            )
                            if (offer == null) {
                                TamaGameEngine.ActionResult(false, context.getString(R.string.tama_world_runtime_action_unavailable))
                            } else {
                                tamaGameEngine.buyItem(offer.item, qty, offer.price)
                            }
                        },
                        onSell = { item, qty ->
                            val price = com.example.llamadroid.tama.data.TamaCommerceCatalog.sellPrice(item.id)
                            if (price == null) {
                                TamaGameEngine.ActionResult(false, context.getString(R.string.tama_world_runtime_action_unavailable))
                            } else {
                                tamaGameEngine.sellItem(item, qty, price.toLong())
                            }
                        },
                        onBuyUpgrade = { type, _ ->
                            com.example.llamadroid.tama.game.WorldFarmMaintenance.request(context, tamaGameEngine, "buy_upgrade", mapOf("type" to type))
                        },
                        onBuyDrone = { type, _ ->
                            com.example.llamadroid.tama.game.WorldFarmMaintenance.request(context, tamaGameEngine, "buy_drone", mapOf("type" to type))
                        },
                        onBuyLivestock = { type ->
                            com.example.llamadroid.tama.game.WorldFarmMaintenance.request(context, tamaGameEngine, "buy_livestock", mapOf("type" to type.id))
                        },
                        onBack = { navController.popBackStack() }
                    )
                    }
                }
            }
            
            // Agent Workspace File Manager
            composable(Screen.AgentWorkspace.route) {
                com.example.llamadroid.ui.agent.AgentWorkspaceScreen(navController)
            }
            composable(Screen.AgentProotTerminal.route) {
                com.example.llamadroid.ui.agent.AgentProotTerminalScreen(navController)
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
                    farmRepository = farmRepository,
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
                val currentPet = pet!!
                TamaWorldArrivalContent(
                    pet = currentPet,
                    gameEngine = tamaGameEngine,
                    database = tamaDatabase,
                    farmRepository = farmRepository,
                    destinationStructureId = LegacyLocationAliases.ARCADE,
                    onReturnToHome = ::returnToPetHome,
                    onBack = { navController.popBackStack() }
                ) {
                    com.example.llamadroid.tama.ui.ArcadeScreen(
                        navController = navController,
                        pet = currentPet,
                        worldActionBridge = activeArcadeWorldActionBridge
                    )
                }
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
            composable(
                route = "${Screen.Dungeon.route}?worldStructureId={worldStructureId}",
                arguments = listOf(
                    androidx.navigation.navArgument("worldStructureId") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val pet by tamaGameEngine.pet.collectAsState()
                val adventureActive by tamaGameEngine.world.adventureActive.collectAsState()
                val worldState by tamaGameEngine.world.state.collectAsState()
                val requestedDungeonId = backStackEntry.arguments
                    ?.getString("worldStructureId")
                    ?.takeIf { it == LegacyLocationAliases.DUNGEON_A || it == LegacyLocationAliases.DUNGEON_B }
                val destinationDungeonId = requestedDungeonId
                    ?: worldState?.actor?.takeIf { adventureActive && it.presence == PresenceMode.INTERIOR }
                        ?.structureId
                        ?.takeIf { it == LegacyLocationAliases.DUNGEON_A || it == LegacyLocationAliases.DUNGEON_B }
                    ?: LegacyLocationAliases.DUNGEON_A
                val currentPet = pet
                if (currentPet == null) {
                    com.example.llamadroid.tama.ui.DungeonScreen(
                        navController = navController,
                        database = tamaDatabase,
                        settingsRepository = settingsRepo
                    )
                } else {
                    TamaWorldArrivalContent(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        database = tamaDatabase,
                        farmRepository = farmRepository,
                        destinationStructureId = destinationDungeonId,
                        arrivalAction = ActionId.ENTER_DUNGEON,
                        onReturnToHome = ::returnToPetHome,
                        onBack = { navController.popBackStack() }
                    ) {
                        com.example.llamadroid.tama.ui.DungeonScreen(
                            navController = navController,
                            database = tamaDatabase,
                            settingsRepository = settingsRepo
                        )
                    }
                }
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
                val pet by tamaGameEngine.pet.collectAsState()
                val currentPet = pet
                if (currentPet == null) {
                    com.example.llamadroid.tama.ui.AdventureGateScreen(
                        navController = navController,
                        database = tamaDatabase
                    )
                } else {
                    TamaWorldArrivalContent(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        database = tamaDatabase,
                        farmRepository = farmRepository,
                        destinationStructureId = LegacyLocationAliases.ADVENTURE_GATE,
                        arrivalAction = ActionId.ENTER_ADVENTURE_GATE,
                        onReturnToHome = ::returnToPetHome,
                        onBack = { navController.popBackStack() }
                    ) {
                        com.example.llamadroid.tama.ui.AdventureGateScreen(
                            navController = navController,
                            database = tamaDatabase
                        )
                    }
                }
            }

            composable(Screen.NightArena.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                val currentPet = pet
                val arenaContent: @Composable () -> Unit = {
                    com.example.llamadroid.tama.ui.AdventureGateScreen(
                        navController = navController,
                        database = tamaDatabase,
                        mode = com.example.llamadroid.tama.ui.AdventureGateScreenMode.NIGHT_ARENA
                    )
                }
                if (currentPet == null) {
                    arenaContent()
                } else {
                    TamaWorldArrivalContent(
                        pet = currentPet,
                        gameEngine = tamaGameEngine,
                        database = tamaDatabase,
                        farmRepository = farmRepository,
                        destinationStructureId = LegacyLocationAliases.ADVENTURE_GATE,
                        arrivalAction = ActionId.ENTER_ADVENTURE_GATE,
                        onReturnToHome = ::returnToPetHome,
                        onBack = { navController.popBackStack() },
                        content = arenaContent
                    )
                }
            }
        }
    }
    }
    }
}

/**
 * Classic features open directly. Only an explicitly started development
 * adventure waits for its physical actor to reach the matching structure.
 */
@Composable
private fun TamaWorldArrivalContent(
    pet: TamaPet,
    gameEngine: TamaGameEngine,
    database: TamaDatabase,
    farmRepository: FarmRepository,
    destinationStructureId: String,
    arrivalAction: ActionId? = null,
    onBack: () -> Unit,
    onReturnToHome: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val world = remember(gameEngine) { gameEngine.world }
    val adventureActive by world.adventureActive.collectAsState()
    val exitScope = rememberCoroutineScope()
    var exiting by remember(pet.id) { mutableStateOf(false) }
    val returnHome: () -> Unit = {
        if (!exiting) {
            exiting = true
            exitScope.launch {
                try {
                    val result = gameEngine.exitSimulatedWorld()
                    if (result.success) onReturnToHome()
                    else Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.tama_world_runtime_action_unavailable, Toast.LENGTH_SHORT).show()
                } finally {
                    exiting = false
                }
            }
        }
    }
    if (!adventureActive) {
        content()
        return
    }
    val farmTiles by remember(pet.id, farmRepository) {
        farmRepository.observeTiles(pet.id)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(gameEngine, pet.id, destinationStructureId, arrivalAction) {
        val actor = world.state.value?.actor
        val persistedLocation = WorldInitializer.normalizeLocation(pet.currentLocationId)
        val alreadyAtDestination = actor?.presence == PresenceMode.INTERIOR &&
            actor.structureId == destinationStructureId
        val alreadyHeadingToDestination = actor?.presence == PresenceMode.WORLD &&
            (actor.pendingStructureId == destinationStructureId ||
                actor.pendingActivity?.destinationId == destinationStructureId)
        // A null actor snapshot can briefly occur while the persistent world is
        // being restored. Trust the pet row only during that gap; once a world
        // snapshot exists, its actor state is authoritative and can recover from
        // a stale legacy location value by issuing the route.
        val persistedAtDestination = actor == null && (
            pet.currentLocationId.equals(destinationStructureId, ignoreCase = true) ||
                persistedLocation == destinationStructureId
            )
        if (alreadyAtDestination) {
            // A generic map EnterStructure command may have put the pet inside
            // a dungeon or gate before Navigation was requested. Start the
            // destination-specific action in place, without making the pet
            // leave and walk back to the same structure.
            if (arrivalAction != null && actor != null &&
                actor.action != arrivalAction &&
                actor.pendingActivity?.arguments?.get("actionId") != arrivalAction.name
            ) {
                val started = world.command(
                    com.example.llamadroid.tama.world.core.WorldCommand.PerformAction(
                        action = arrivalAction,
                        targetId = destinationStructureId
                    )
                )
                if (!started.acceptedCommand) {
                    Toast.makeText(
                        context,
                        localizedWorldRuntimeError(
                            context,
                            started.rejectionReason ?: "action_unavailable"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return@LaunchedEffect
        }
        if (alreadyHeadingToDestination || persistedAtDestination) {
            return@LaunchedEffect
        }
        val accepted = try {
            if (arrivalAction != null) {
                world.queueActivity(
                    PendingActivityIntent(
                        action = "WORLD_ACTION",
                        destinationId = destinationStructureId,
                        arguments = mapOf(
                            "actionId" to arrivalAction.name,
                            "targetId" to destinationStructureId
                        )
                    )
                ).also { queued ->
                    if (!queued.acceptedCommand) {
                        Toast.makeText(
                            context,
                            localizedWorldRuntimeError(
                                context,
                                queued.rejectionReason ?: "action_unavailable"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.acceptedCommand
            } else {
                gameEngine.travelToId(destinationStructureId).also { travel ->
                    if (!travel.success) {
                        Toast.makeText(
                            context,
                            localizedWorldRuntimeError(context, travel.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.success
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.tama_world_runtime_action_unavailable),
                Toast.LENGTH_SHORT
            ).show()
            onBack()
            return@LaunchedEffect
        }
        if (!accepted) {
            onBack()
        }
    }

    TamaWorldArrivalGate(
        world = world,
        brainProvider = { gameEngine.brain },
        database = database,
        petId = pet.id,
        destinationStructureId = destinationStructureId,
        petName = pet.name,
        petSpeciesId = pet.species,
        petStage = pet.stage.name.lowercase(),
        farmTiles = farmTiles,
        worldLabels = localizedWorldUiLabels(context, pet.name),
        brainLabels = localizedBrainUiLabels(context, pet.name),
        onCloseWorld = returnHome,
        onReturnHome = returnHome,
        content = content
    )
}
