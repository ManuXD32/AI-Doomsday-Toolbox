package com.example.llamadroid.tama.world.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Context
import android.widget.Toast
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaCommerceCatalog
import com.example.llamadroid.tama.data.WorldResourceCatalog
import com.example.llamadroid.tama.data.cropDisplayName
import com.example.llamadroid.tama.data.seedDisplayText
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.Direction
import com.example.llamadroid.tama.world.core.EventImportance
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.NeedType
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldObjectType
import com.example.llamadroid.tama.world.core.WorldNpcCatalog
import com.example.llamadroid.tama.world.memory.AdventureMemoryPreferences
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity
import com.example.llamadroid.tama.world.runtime.TamaWorldController
import com.example.llamadroid.tama.world.training.BrainRuntimeCheckpoint
import com.example.llamadroid.tama.world.training.TamaBrainController
import com.example.llamadroid.tama.world.training.CurriculumLevel
import com.example.llamadroid.tama.world.training.TrainerProfile
import com.example.llamadroid.tama.world.ui.BrainTrainingCallbacks
import com.example.llamadroid.tama.world.ui.BrainTrainingProfileUi
import com.example.llamadroid.tama.world.ui.BrainTrainingScreen
import com.example.llamadroid.tama.world.ui.WorldCameraMode
import com.example.llamadroid.tama.world.ui.WorldCameraUi
import com.example.llamadroid.tama.world.ui.WorldBiome
import com.example.llamadroid.tama.world.ui.WorldInspectTarget
import com.example.llamadroid.tama.world.ui.WorldJournalCallbacks
import com.example.llamadroid.tama.world.ui.WorldJournalFilter
import com.example.llamadroid.tama.world.ui.WorldJournalScreen
import com.example.llamadroid.tama.world.ui.WorldRelationshipUi
import com.example.llamadroid.tama.world.ui.WorldStructureType
import com.example.llamadroid.tama.world.ui.WorldUiCallbacks
import com.example.llamadroid.tama.world.ui.WorldUiCommand
import com.example.llamadroid.tama.world.ui.WorldUiState
import com.example.llamadroid.tama.world.ui.WorldScreen
import com.example.llamadroid.tama.world.ui.SafeAutonomyUi
import com.example.llamadroid.tama.world.ui.formatBrainTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private enum class WorldRoute { WORLD, JOURNAL, BRAIN }

private fun String.toWorldRoute(): WorldRoute = runCatching {
    WorldRoute.valueOf(trim().uppercase())
}.getOrDefault(WorldRoute.WORLD)

private val WORLD_CROP_ASSET_ALIASES: Map<String, String> =
    CropDefinitions.CROPS.keys.associateWith { cropId -> "crop_$cropId" }

/**
 * Thin app-shell adapter for the world routes. It owns only navigation and
 * presentation state; world and training mutations continue through their
 * controllers. The brain provider stays lazy so opening the home room never
 * starts a synthetic trainer.
 */
@Composable
fun TamaWorldRouteHost(
    world: TamaWorldController,
    brainProvider: () -> TamaBrainController,
    database: TamaDatabase,
    petId: String,
    petName: String,
    petSpeciesId: String,
    petStage: String,
    initialRoute: String = "WORLD",
    farmTiles: List<FarmTile> = emptyList(),
    worldLabels: WorldUiLabels,
    brainLabels: BrainUiLabels,
    relationshipByNpc: Map<String, WorldRelationshipUi> = emptyMap(),
    onNonWorldRouteChanged: (Boolean) -> Unit = {},
    onActivityArrival: (WorldActivityArrival) -> Unit = {},
    exitShortcutLabelRes: Int? = R.string.tama_world_shortcut_room,
    onCloseWorld: () -> Unit = {},
    onReturnHome: (() -> Unit)? = null,
    onOpenInventory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val coreState by world.state.collectAsState()
    val worldError by world.error.collectAsState()
    val adventureActive by world.adventureActive.collectAsState()
    var routeName by remember(petId, initialRoute) {
        mutableStateOf(initialRoute.toWorldRoute().name)
    }
    var camera by remember(coreState?.worldId, petId) {
        mutableStateOf(
            WorldCameraUi(
                centerX = coreState?.actor?.preciseX?.toFloat() ?: 0f,
                centerY = coreState?.actor?.preciseY?.toFloat() ?: 0f
            )
        )
    }
    var inspectorTarget by remember(petId) { mutableStateOf<WorldInspectTarget?>(null) }
    var activeCommand by remember(petId) { mutableStateOf<com.example.llamadroid.tama.world.ui.WorldPetCommandKind?>(null) }
    var showMinimap by remember(petId) { mutableStateOf(true) }
    var selectedEpisodeId by remember(petId) { mutableStateOf<String?>(null) }
    var journalFilter by remember(petId) { mutableStateOf(WorldJournalFilter.ALL) }
    var savedAutonomyForPresentation by remember(petId) { mutableStateOf<AutonomyPolicy?>(null) }
    val autonomyMutationMutex = remember(world, petId) { Mutex() }
    val route = runCatching { WorldRoute.valueOf(routeName) }.getOrDefault(WorldRoute.WORLD)

    // Compare locations across published snapshots so a restored interior does
    // not reopen an activity merely because the host was recomposed. The
    // callback fires only for a real WORLD/HOME -> INTERIOR transition while
    // the world route is visible; contextual routes use TamaWorldArrivalGate.
    var previousActorLocation by remember(petId) {
        mutableStateOf<Pair<PresenceMode, String?>?>(null)
    }
    LaunchedEffect(
        coreState?.actor?.presence,
        coreState?.actor?.structureId,
        coreState?.actor?.action,
        route
    ) {
        val snapshot = coreState ?: return@LaunchedEffect
        val location = snapshot.actor.presence to snapshot.actor.structureId
        val previous = previousActorLocation
        previousActorLocation = location
        if (route == WorldRoute.WORLD &&
            previous != null && previous != location &&
            snapshot.actor.presence == PresenceMode.INTERIOR
        ) {
            snapshot.activityArrivalOrNull()?.let(onActivityArrival)
        }
    }

    LaunchedEffect(route, world, adventureActive) {
        // Brain/Journal may be opened from the classic room, but the living
        // simulation must not tick or catch up while it is not opted in.
        world.setVisible(route == WorldRoute.WORLD && adventureActive)
    }
    LaunchedEffect(route) {
        onNonWorldRouteChanged(route != WorldRoute.WORLD)
    }
    DisposableEffect(world) {
        onDispose { world.setVisible(false) }
    }

    val returnHomeAction = if (adventureActive) {
        onReturnHome ?: onCloseWorld
    } else {
        onCloseWorld
    }
    val effectiveExitShortcutLabelRes = if (adventureActive) {
        R.string.tama_classic_map_return_home
    } else {
        exitShortcutLabelRes
    }

    // Route-level Back remains active while world state is loading or has
    // failed. Contextual routes first return to WORLD only for an active
    // session; a Brain/Journal opened from the classic menu closes directly
    // back to the classic room. WORLD itself always exits the active session
    // through the immediate root callback.
    BackHandler {
        if (route != WorldRoute.WORLD) {
            if (adventureActive) {
                routeName = WorldRoute.WORLD.name
            } else {
                onCloseWorld()
            }
        } else if (adventureActive) {
            returnHomeAction()
        } else {
            onCloseWorld()
        }
    }

    val current = coreState
    if (route == WorldRoute.BRAIN) {
        val brain = remember(petId) { brainProvider() }
        val runtime by brain.state.collectAsState()
        LaunchedEffect(brain, petId, world) {
            try {
                savedAutonomyForPresentation = world.loadSavedAutonomyForPresentation(petId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showTransientCommandRejection(context, "world_unavailable")
            }
            brain.initialize()
        }
        val profiles = listOf(
            profileUi(TrainerProfile.ECO, runtime),
            profileUi(TrainerProfile.BALANCED, runtime),
            profileUi(TrainerProfile.FAST, runtime),
            profileUi(TrainerProfile.CUSTOM, runtime)
        )
        val displayedAutonomy = coreState?.autonomy ?: savedAutonomyForPresentation ?: AutonomyPolicy()
        val brainUi = projectBrainRuntimeState(
            runtime = runtime,
            labels = brainLabels,
            profiles = profiles,
            autonomyPolicy = displayedAutonomy
        )
        BrainTrainingScreen(
            state = brainUi,
            callbacks = BrainTrainingCallbacks(
                onBack = {
                    if (adventureActive) routeName = WorldRoute.WORLD.name else onCloseWorld()
                },
                onStart = brain::start,
                onPause = brain::pause,
                onResume = brain::resume,
                onSpeedChanged = brain::setSpeed,
                onProfileSelected = { id ->
                    runCatching { TrainerProfile.valueOf(id) }.getOrNull()?.let(brain::selectProfile)
                },
                onCheckpointSaved = brain::saveCheckpoint,
                onEvaluate = brain::evaluate,
                onAdoptCandidate = brain::adoptCandidate,
                // Keeping the current candidate leaves the active artifact unchanged. Pausing
                // also freezes the comparison until the user explicitly starts again.
                onKeepCurrent = brain::pause,
                onRestoreCheckpoint = brain::restoreCheckpoint,
                onRestoreAdoptedPolicy = brain::restoreAdoptedPolicy,
                onSafeAutonomyChanged = { settings ->
                    val requested = settings.toCorePolicy()
                    // Each control emits a copy of the policy rendered in this composition.
                    // Diff against that exact copy, even if an earlier click is still saving.
                    val previous = displayedAutonomy
                    scope.launch {
                        autonomyMutationMutex.withLock {
                            val live = world.state.value?.autonomy ?: previous
                            world.setAutonomy(live.mergeAutonomyChanges(previous, requested))
                        }
                    }
                },
                onOpenJournal = { routeName = WorldRoute.JOURNAL.name },
                onCurriculumSelected = brain::selectCurriculum,
                onResourcesChanged = { settings ->
                    brain.setResourceLimits(
                        chargingOnly = settings.chargingOnly,
                        minimumBatteryPercent = settings.minimumBatteryPercent,
                        maxThreads = settings.maxThreads,
                        environmentCount = settings.environmentCount
                    )
                }
            ),
            modifier = modifier
        )
        return
    }

    val worldDao = remember(database) { database.worldDao() }
    val storedRelationships by produceState<Map<String, WorldRelationshipUi>>(
        initialValue = emptyMap(),
        petId,
        database
    ) {
        value = withContext(Dispatchers.IO) {
            worldDao.relationships(petId).associate { it.npcId to it.toUiRelationship() }
        }
    }
    // Stored world relationship rows carry trust/familiarity; the legacy map
    // only fills NPCs that have not acquired a world row yet.
    val relationships = relationshipByNpc + storedRelationships
    val episodeFlow = remember(petId) { worldDao.observeEpisodes(petId) }
    val eventFlow = remember(petId) { worldDao.observeEvents(petId) }
    val episodes by episodeFlow.collectAsState(initial = emptyList())
    val events by eventFlow.collectAsState(initial = emptyList())
    val memoryPreferences = remember(context) { AdventureMemoryPreferences(context) }
    val journalLabels = remember(context, petId, petName, coreState?.timezoneOffsetMinutes) {
        localizedJournalLabels(
            context = context,
            petId = petId,
            petName = petName,
            worldTimezoneOffsetMinutes = coreState?.timezoneOffsetMinutes
        )
    }
    var memoryPolicy by remember(petId) {
        mutableStateOf(memoryPreferences.policy(petId).toUiPolicy())
    }
    if (route == WorldRoute.JOURNAL) {
        val eventsByEpisode = remember(events) {
            events.asSequence()
                .filter { it.episodeId != null }
                .groupBy { it.episodeId!! }
        }
        val journalState = remember(
            episodes, eventsByEpisode, events, journalLabels, memoryPolicy, selectedEpisodeId, journalFilter
        ) {
            projectWorldJournal(
                episodes = episodes,
                eventsByEpisode = eventsByEpisode,
                allEvents = events,
                policy = memoryPolicy.toDomainPolicy(),
                labels = journalLabels,
                selectedEpisodeId = selectedEpisodeId,
                filter = journalFilter
            )
        }
        WorldJournalScreen(
            state = journalState,
            callbacks = WorldJournalCallbacks(
                onBack = {
                    if (adventureActive) routeName = WorldRoute.WORLD.name else onCloseWorld()
                },
                onSelectEpisode = { selectedEpisodeId = it },
                onFilterChanged = { journalFilter = it },
                onCommand = { command ->
                    handleRouteCommand(
                        command,
                        setRoute = { routeName = it.name },
                        onCloseWorld = onCloseWorld,
                        onReturnHome = returnHomeAction,
                        isSimulationActive = adventureActive,
                        onCommandRejected = { reason ->
                            showTransientCommandRejection(context, reason)
                        }
                    )
                },
                onMemoryPolicyChanged = {
                    memoryPolicy = it
                    memoryPreferences.setPolicy(petId, it.toDomainPolicy())
                },
                onSaveMemory = { episodeId ->
                    scope.launch { memoryPreferences.approve(database, petId, episodeId) }
                }
            ),
            modifier = modifier
        )
        return
    }

    // A persisted WORLD snapshot is not permission to render or catch up the
    // optional simulation. The root normally removes this host as soon as the
    // session exits; this bounded placeholder also covers the one-frame
    // transition while that route state is being disposed.
    if (!adventureActive) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.tama_classic_map_simulated_world_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.tama_classic_map_simulated_world_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (onReturnHome != null) {
                    Button(
                        onClick = returnHomeAction,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.tama_classic_map_return_home))
                    }
                }
            }
        }
        return
    }

    if (current == null) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (worldError == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.tama_world_assets_loading))
                }
            } else {
                WorldRuntimeErrorPanel(worldError!!, onRetry = { scope.launch { world.retryFromUi() } })
            }
        }
        return
    }

    val worldLocale = context.resources.configuration.locales
        .takeIf { !it.isEmpty }
        ?.get(0)
        ?: Locale.getDefault()
    val savedWorldOffsetMinutes = current.timezoneOffsetMinutes
    val frameWorldLabels = remember(worldLabels, worldLocale, savedWorldOffsetMinutes) {
        worldLabels.copy(
            timeName = { timestamp ->
                formatWorldTime(timestamp, savedWorldOffsetMinutes, worldLocale)
            }
        )
    }

    val cameraForFrame = remember(current, camera) {
        val followed = when (camera.mode) {
            WorldCameraMode.FOLLOW_PET -> current.actor.preciseX.toFloat() to current.actor.preciseY.toFloat()
            WorldCameraMode.FOLLOW_NPC -> camera.followActorId?.let { id ->
                current.npcs.firstOrNull { it.id == id }?.let { npc -> npc.preciseX.toFloat() to npc.preciseY.toFloat() }
            }
            WorldCameraMode.FREE -> null
        }
        followed?.let { (x, y) -> camera.copy(centerX = x, centerY = y) } ?: camera
    }
    val staticMinimap = produceState<com.example.llamadroid.tama.world.ui.WorldMiniMapUi?>(
        initialValue = null,
        current.seed,
        current.explored
    ) {
        value = withContext(Dispatchers.Default) {
            projectWorldState(
                state = current,
                camera = cameraForFrame,
                labels = frameWorldLabels,
                petSpeciesId = petSpeciesId,
                petStage = petStage,
                relationshipByNpc = relationships,
                observedFarmTiles = farmTiles,
                cropAssetIdByCropId = WORLD_CROP_ASSET_ALIASES
            ).minimap
        }
    }.value
    val projectedState = produceState<WorldUiState?>(
        initialValue = null,
        current,
        cameraForFrame,
        inspectorTarget,
        activeCommand,
        showMinimap,
        farmTiles,
        staticMinimap,
        worldLocale,
        savedWorldOffsetMinutes
    ) {
        value = withContext(Dispatchers.Default) {
            projectWorldState(
                state = current,
                camera = cameraForFrame,
                labels = frameWorldLabels,
                petSpeciesId = petSpeciesId,
                petStage = petStage,
                relationshipByNpc = relationships,
                inspectorTarget = inspectorTarget,
                activeCommand = activeCommand,
                cachedMinimap = staticMinimap,
                observedFarmTiles = farmTiles,
                cropAssetIdByCropId = WORLD_CROP_ASSET_ALIASES
            ).let { state -> if (showMinimap) state else state.copy(minimap = null) }
        }
    }.value

    val projected = projectedState
    if (projected == null) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tama_world_assets_loading))
            }
        }
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        WorldScreen(
            state = projected,
            callbacks = WorldUiCallbacks { command ->
                handleRouteCommand(
                    command,
                    current,
                    projected,
                    world,
                    scope,
                    onOpenInventory = onOpenInventory,
                    setRoute = { routeName = it.name },
                    onCloseWorld = onCloseWorld,
                    onReturnHome = returnHomeAction,
                    isSimulationActive = adventureActive,
                    onCommandRejected = { reason ->
                        showTransientCommandRejection(context, reason)
                    },
                    setCamera = { camera = it },
                    setInspector = { inspectorTarget = it },
                    setActiveCommand = { activeCommand = it },
                    toggleMinimap = { showMinimap = !showMinimap }
                )
            },
            modifier = Modifier.fillMaxSize(),
            exitShortcutLabelRes = effectiveExitShortcutLabelRes,
            isSimulationActive = adventureActive,
            homeActionLabelRes = if (adventureActive) {
                R.string.tama_classic_map_return_home
            } else {
                R.string.tama_world_open_home
            },
            onSystemBack = if (adventureActive) returnHomeAction else null
        )
        worldError?.let { message ->
            WorldRuntimeErrorPanel(message, onRetry = { scope.launch { world.retryFromUi() } })
        }
    }
}

/**
 * Keeps a contextual activity behind the same physical arrival boundary as the
 * world screen. The destination key is part of the remembered state, so Farm
 * stays mounted while the pet tends a plot and a newly selected destination
 * starts a fresh approach. The caller queues the travel command through its
 * existing controller before composing this gate.
 */
@Composable
fun TamaWorldArrivalGate(
    world: TamaWorldController,
    brainProvider: () -> TamaBrainController,
    database: TamaDatabase,
    petId: String,
    destinationStructureId: String,
    petName: String,
    petSpeciesId: String,
    petStage: String,
    initialRoute: String = "WORLD",
    farmTiles: List<FarmTile> = emptyList(),
    worldLabels: WorldUiLabels,
    brainLabels: BrainUiLabels,
    relationshipByNpc: Map<String, WorldRelationshipUi> = emptyMap(),
    onNonWorldRouteChanged: (Boolean) -> Unit = {},
    exitShortcutLabelRes: Int? = R.string.action_back,
    onCloseWorld: () -> Unit = {},
    onReturnHome: (() -> Unit)? = null,
    onOpenInventory: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state by world.state.collectAsState()
    var arrived by remember(petId, destinationStructureId) {
        mutableStateOf(
            state?.actor?.presence == PresenceMode.INTERIOR &&
                state?.actor?.structureId == destinationStructureId
        )
    }
    LaunchedEffect(
        state?.actor?.presence,
        state?.actor?.structureId,
        state?.actor?.pendingStructureId,
        state?.actor?.pendingActivity?.destinationId,
        destinationStructureId
    ) {
        val actor = state?.actor ?: return@LaunchedEffect
        val pendingDestination = actor.pendingActivity?.destinationId ?: actor.pendingStructureId
        when {
            actor.presence == PresenceMode.INTERIOR && actor.structureId == destinationStructureId -> {
                arrived = true
            }
            // A contextual route may be latched while a farm action walks to a
            // plot. Keep it mounted for that same farm destination, but release
            // it as soon as an explicit Home or different structure command is
            // persisted so the world view can show the new route.
            arrived && pendingDestination != null && pendingDestination != destinationStructureId -> {
                arrived = false
            }
            // A world action may approach a farm plot after the farm screen has
            // opened. Keep that screen mounted while the actor is in WORLD;
            // release it only after a deliberate structure/home transition.
            arrived && actor.presence == PresenceMode.HOME -> arrived = false
            arrived && actor.presence == PresenceMode.INTERIOR &&
                actor.structureId != destinationStructureId -> arrived = false
        }
    }
    if (arrived) {
        content()
    } else {
        TamaWorldRouteHost(
            world = world,
            brainProvider = brainProvider,
            database = database,
            petId = petId,
            petName = petName,
            petSpeciesId = petSpeciesId,
            petStage = petStage,
            initialRoute = initialRoute,
            farmTiles = farmTiles,
            worldLabels = worldLabels,
            brainLabels = brainLabels,
            relationshipByNpc = relationshipByNpc,
            onNonWorldRouteChanged = onNonWorldRouteChanged,
            exitShortcutLabelRes = exitShortcutLabelRes,
            onCloseWorld = onCloseWorld,
            onReturnHome = onReturnHome,
            onOpenInventory = onOpenInventory,
            modifier = modifier
        )
    }
}

@Composable
private fun WorldRuntimeErrorPanel(message: String, onRetry: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.tama_world_runtime_error_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = localizedWorldRuntimeError(context, message),
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tama_world_runtime_error_continue))
            }
        }
    }
}

/** Maps controller rejection codes to the localized, user-safe route message. */
fun localizedWorldRuntimeError(context: Context, reason: String): String {
    val key = reason.substringBefore(':').trim().lowercase()
    val resource = when (key) {
        "pet_missing" -> R.string.tama_world_runtime_error_pet_missing
        "world_missing" -> R.string.tama_world_runtime_error_world_missing
        "world_unavailable" -> R.string.tama_world_runtime_error_unavailable
        "action_unavailable", "invalid_action" -> R.string.tama_world_runtime_action_unavailable
        "arcade_session_active", "arcade_recovery_invalid", "arcade_lease_missing" ->
            R.string.tama_arcade_session_recovery
        "target_unavailable", "target_type_not_allowed", "walkable_target_required" ->
            R.string.tama_world_runtime_error_target
        "autonomy_forbidden" -> R.string.tama_world_runtime_error_autonomy
        "item_required", "item_required_kind", "tool_required", "money_required" ->
            R.string.tama_world_runtime_error_requirements
        "home_required", "structure_required", "farm_required", "world_presence_required" ->
            R.string.tama_world_runtime_error_destination
        else -> R.string.tama_world_runtime_error_generic
    }
    return context.getString(resource)
}

private fun TamaWorldRelationshipEntity.toUiRelationship(): WorldRelationshipUi = WorldRelationshipUi(
    familiarity = familiarity.roundToInt().coerceIn(0, 100),
    friendship = friendship.roundToInt().coerceIn(0, 100),
    trust = trust.roundToInt().coerceIn(0, 100),
    recentInteraction = lastInteraction.toString(),
    lastMetAt = lastInteraction.toString(),
    helpCount = 0,
    sharedEvents = sharedEventCount.coerceAtLeast(0)
)

@Composable
private fun profileUi(
    profile: TrainerProfile,
    runtime: com.example.llamadroid.tama.world.training.BrainRuntimeState
): BrainTrainingProfileUi {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val (nameRes, descriptionRes) = when (profile) {
        TrainerProfile.ECO -> R.string.tama_world_brain_profile_eco to R.string.tama_world_brain_profile_eco_description
        TrainerProfile.BALANCED -> R.string.tama_world_brain_profile_balanced to R.string.tama_world_brain_profile_balanced_description
        TrainerProfile.FAST -> R.string.tama_world_brain_profile_fast to R.string.tama_world_brain_profile_fast_description
        TrainerProfile.CUSTOM -> R.string.tama_world_brain_profile_custom to R.string.tama_world_brain_profile_custom_description
    }
    val environments = when (profile) {
        TrainerProfile.ECO -> 2
        TrainerProfile.BALANCED -> 4
        TrainerProfile.FAST -> 8
        TrainerProfile.CUSTOM -> runtime.environmentCount
    }
    val threads = when (profile) {
        TrainerProfile.ECO -> 1
        TrainerProfile.BALANCED -> 2
        TrainerProfile.FAST -> 4
        TrainerProfile.CUSTOM -> runtime.maxThreads
    }
    return BrainTrainingProfileUi(
        id = profile.name,
        name = resources.getString(nameRes),
        description = resources.getString(descriptionRes),
        parallelEnvironments = environments,
        cpuThreads = threads,
        maxThermalLevel = android.os.PowerManager.THERMAL_STATUS_SEVERE,
        pauseOnLowBattery = true,
        chargingOnly = runtime.chargingOnly,
        selected = runtime.profile == profile
    )
}

private fun handleRouteCommand(
    command: WorldUiCommand,
    state: com.example.llamadroid.tama.world.core.WorldState? = null,
    projected: WorldUiState? = null,
    world: TamaWorldController? = null,
    scope: kotlinx.coroutines.CoroutineScope? = null,
    onOpenInventory: () -> Unit = {},
    onCloseWorld: () -> Unit = {},
    onReturnHome: (() -> Unit)? = null,
    isSimulationActive: Boolean = false,
    onCommandRejected: (String) -> Unit = {},
    setRoute: (WorldRoute) -> Unit = {},
    setCamera: (WorldCameraUi) -> Unit = {},
    setInspector: (WorldInspectTarget?) -> Unit = {},
    setActiveCommand: (com.example.llamadroid.tama.world.ui.WorldPetCommandKind?) -> Unit = {},
    toggleMinimap: () -> Unit = {}
) {
    fun returnHome() {
        if (isSimulationActive) {
            (onReturnHome ?: onCloseWorld)()
        } else {
            onCloseWorld()
        }
    }

    when (command) {
        WorldUiCommand.CloseWorld -> returnHome()
        WorldUiCommand.OpenWorld -> setRoute(WorldRoute.WORLD)
        WorldUiCommand.OpenJournal -> setRoute(WorldRoute.JOURNAL)
        WorldUiCommand.OpenBrainTraining -> setRoute(WorldRoute.BRAIN)
        WorldUiCommand.OpenInventory -> onOpenInventory()
        // The optional development session has a process-local exit boundary.
        // It must not enqueue the slower autonomous ReturnHome command, which
        // can leave the user waiting in WORLD.
        WorldUiCommand.OpenHome -> returnHome()
        WorldUiCommand.Recenter -> state?.let { current ->
            setCamera(
                WorldCameraUi(
                    current.actor.preciseX.toFloat(),
                    current.actor.preciseY.toFloat(),
                    mode = WorldCameraMode.FOLLOW_PET,
                    followActorId = current.actor.actorId
                )
            )
        }
        WorldUiCommand.DismissInspector -> setInspector(null)
        WorldUiCommand.CancelTargetedCommand -> setActiveCommand(null)
        WorldUiCommand.ToggleMinimap -> toggleMinimap()
        is WorldUiCommand.SetCameraMode -> {
            val followId = when (command.mode) {
                WorldCameraMode.FOLLOW_PET -> state?.actor?.actorId
                WorldCameraMode.FOLLOW_NPC -> command.actorId
                WorldCameraMode.FREE -> null
            }
            val position = when (command.mode) {
                WorldCameraMode.FOLLOW_PET -> state?.actor?.let { it.preciseX.toFloat() to it.preciseY.toFloat() }
                WorldCameraMode.FOLLOW_NPC -> state?.npcs?.firstOrNull { it.id == command.actorId }
                    ?.let { it.preciseX.toFloat() to it.preciseY.toFloat() }
                WorldCameraMode.FREE -> null
            }
            setCamera(
                WorldCameraUi(
                    centerX = position?.first ?: state?.actor?.preciseX?.toFloat() ?: 0f,
                    centerY = position?.second ?: state?.actor?.preciseY?.toFloat() ?: 0f,
                    mode = command.mode,
                    followActorId = followId
                )
            )
        }
        is WorldUiCommand.PanCamera -> setCamera(
            (projected?.camera ?: WorldCameraUi(0f, 0f)).copy(
                centerX = (projected?.camera?.centerX ?: 0f) + command.deltaX,
                centerY = (projected?.camera?.centerY ?: 0f) + command.deltaY,
                mode = WorldCameraMode.FREE,
                followActorId = null
            )
        )
        is WorldUiCommand.ZoomCamera -> {
            val base = projected?.camera ?: WorldCameraUi(command.focusX, command.focusY)
            val nextZoom = (base.zoom * command.factor).coerceIn(0.5f, 4f)
            val scale = if (nextZoom == 0f) 1f else base.zoom / nextZoom
            setCamera(
                base.copy(
                    centerX = command.focusX + (base.centerX - command.focusX) * scale,
                    centerY = command.focusY + (base.centerY - command.focusY) * scale,
                    zoom = nextZoom,
                    mode = WorldCameraMode.FREE,
                    followActorId = null
                )
            )
        }
        is WorldUiCommand.Inspect -> setInspector(command.target)
        is WorldUiCommand.BeginTargetedCommand -> setActiveCommand(command.kind)
        is WorldUiCommand.IssuePetCommand,
        is WorldUiCommand.InspectorAction,
        is WorldUiCommand.FollowActor -> {
            val current = state ?: run {
                onCommandRejected("world_unavailable")
                return
            }
            val core = command.toCoreCommand(current, projected?.inspector) ?: run {
                onCommandRejected(
                    if (command is WorldUiCommand.IssuePetCommand) {
                        "target_unavailable"
                    } else {
                        "action_unavailable"
                    }
                )
                return
            }
            scope?.launch {
                val controller = world
                if (controller == null) {
                    onCommandRejected("world_unavailable")
                    return@launch
                }
                val result = try {
                    controller.command(core)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    onCommandRejected(error.message ?: "world_unavailable")
                    return@launch
                }
                if (!result.acceptedCommand) {
                    onCommandRejected(result.rejectionReason ?: "world_unavailable")
                }
            }
        }
        WorldUiCommand.ShowCommandPalette -> Unit
    }
}

private fun showTransientCommandRejection(context: Context, reason: String) {
    Toast.makeText(context, localizedWorldRuntimeError(context, reason), Toast.LENGTH_SHORT).show()
}

private fun SafeAutonomyUi.toCorePolicy(): AutonomyPolicy = AutonomyPolicy(
    level = when (level) {
        com.example.llamadroid.tama.world.ui.WorldAutonomyLevel.OFF -> AutonomyLevel.OFF
        com.example.llamadroid.tama.world.ui.WorldAutonomyLevel.SAFE -> AutonomyLevel.SAFE
        com.example.llamadroid.tama.world.ui.WorldAutonomyLevel.NORMAL -> AutonomyLevel.NORMAL
        com.example.llamadroid.tama.world.ui.WorldAutonomyLevel.FULL -> AutonomyLevel.FULL
    },
    allowPurchases = allowPurchases,
    maximumAutonomousPurchase = maximumAutonomousPurchase,
    allowSellingItems = allowSellingItems,
    allowUsingRareItems = allowUsingRareItems,
    allowDungeonEntry = allowDungeonEntry,
    allowAdventureGate = allowAdventureGate,
    allowOvernightExploration = allowOvernightExploration,
    allowWork = allowWork,
    allowStudy = allowStudy,
    allowFarming = allowFarming,
    allowHarvestingUserCrops = allowHarvestingUserCrops
)

/** Apply only fields changed by this UI event to the latest canonical policy. */
internal fun AutonomyPolicy.mergeAutonomyChanges(
    previous: AutonomyPolicy,
    requested: AutonomyPolicy
): AutonomyPolicy {
    var merged = this
    if (requested.level != previous.level) merged = merged.copy(level = requested.level)
    if (requested.allowPurchases != previous.allowPurchases) merged = merged.copy(allowPurchases = requested.allowPurchases)
    if (requested.maximumAutonomousPurchase != previous.maximumAutonomousPurchase) {
        merged = merged.copy(maximumAutonomousPurchase = requested.maximumAutonomousPurchase)
    }
    if (requested.allowSellingItems != previous.allowSellingItems) merged = merged.copy(allowSellingItems = requested.allowSellingItems)
    if (requested.allowUsingRareItems != previous.allowUsingRareItems) merged = merged.copy(allowUsingRareItems = requested.allowUsingRareItems)
    if (requested.allowDungeonEntry != previous.allowDungeonEntry) merged = merged.copy(allowDungeonEntry = requested.allowDungeonEntry)
    if (requested.allowAdventureGate != previous.allowAdventureGate) merged = merged.copy(allowAdventureGate = requested.allowAdventureGate)
    if (requested.allowOvernightExploration != previous.allowOvernightExploration) merged = merged.copy(allowOvernightExploration = requested.allowOvernightExploration)
    if (requested.allowWork != previous.allowWork) merged = merged.copy(allowWork = requested.allowWork)
    if (requested.allowStudy != previous.allowStudy) merged = merged.copy(allowStudy = requested.allowStudy)
    if (requested.allowFarming != previous.allowFarming) merged = merged.copy(allowFarming = requested.allowFarming)
    if (requested.allowHarvestingUserCrops != previous.allowHarvestingUserCrops) {
        merged = merged.copy(allowHarvestingUserCrops = requested.allowHarvestingUserCrops)
    }
    return merged
}

internal fun localizedJournalLabels(
    context: Context,
    petId: String,
    petName: String,
    worldTimezoneOffsetMinutes: Int? = null
): WorldJournalLabels {
    val spanish = context.resources.configuration.locales[0].language == "es"
    val locale = context.resources.configuration.locales[0]
    fun worldOffsetAt(timestamp: Long): Int = worldTimezoneOffsetMinutes
        ?: TimeZone.getDefault().getOffset(timestamp) / 60_000
    val stageLabels = mapOf(
        GrowthStage.EGG.name to context.getString(R.string.tama_world_journal_stage_egg),
        GrowthStage.BABY.name to context.getString(R.string.tama_world_journal_stage_baby),
        GrowthStage.CHILD.name to context.getString(R.string.tama_world_journal_stage_child),
        GrowthStage.TEEN.name to context.getString(R.string.tama_world_journal_stage_teen),
        GrowthStage.ADULT.name to context.getString(R.string.tama_world_journal_stage_adult),
        GrowthStage.SENIOR.name to context.getString(R.string.tama_world_journal_stage_senior)
    )
    val titles = mapOf(
        "FED" to context.getString(R.string.tama_world_event_fed),
        "CLEANED" to context.getString(R.string.tama_world_event_cleaned),
        "PLAYED" to context.getString(R.string.tama_world_event_played),
        "SLEPT" to context.getString(R.string.tama_world_event_slept),
        "WOKE_UP" to context.getString(R.string.tama_world_event_woke_up),
        "HEALED" to context.getString(R.string.tama_world_event_healed),
        "STUDIED" to context.getString(R.string.tama_world_event_studied),
        "RELAXED" to context.getString(R.string.tama_world_event_relaxed),
        "POOPED" to context.getString(R.string.tama_world_event_pooped),
        "POOP_CLEANED" to context.getString(R.string.tama_world_event_poop_cleaned),
        "POOP_NEGLECTED" to context.getString(R.string.tama_world_event_poop_neglected),
        "HATCHED" to context.getString(R.string.tama_world_event_hatched),
        "BIOME_DISCOVERED" to context.getString(R.string.tama_world_event_biome_discovered),
        "PLACE_DISCOVERED" to context.getString(R.string.tama_world_event_place_discovered),
        "ADVENTURE_GATE_DISCOVERED" to context.getString(R.string.tama_world_event_gate_discovered),
        "FIRST_MEETING" to context.getString(R.string.tama_world_event_first_meeting),
        "CLOSE_FRIENDSHIP" to context.getString(R.string.tama_world_event_close_friendship),
        "QUEST_COMPLETED" to context.getString(R.string.tama_world_event_quest_completed),
        "BATTLE_WON" to context.getString(R.string.tama_world_event_battle_won),
        "BATTLE_LOST" to context.getString(R.string.tama_world_event_battle_lost),
        "MADE_FRIEND" to context.getString(R.string.tama_world_event_made_friend),
        "MET_NPC" to context.getString(R.string.tama_world_event_met_npc),
        "GAVE_GIFT" to context.getString(R.string.tama_world_event_gave_gift),
        "RECEIVED_GIFT" to context.getString(R.string.tama_world_event_received_gift),
        "MARRIED" to context.getString(R.string.tama_world_event_married),
        "HAD_CHILD" to context.getString(R.string.tama_world_event_had_child),
        "VISITED" to context.getString(R.string.tama_world_event_visited),
        "TRAVELED" to context.getString(R.string.tama_world_event_traveled),
        "DISCOVERED" to context.getString(R.string.tama_world_event_discovered),
        "ENTERED_BUILDING" to context.getString(R.string.tama_world_event_entered_building),
        "WENT_TO_SCHOOL" to context.getString(R.string.tama_world_event_went_to_school),
        "GRADUATED" to context.getString(R.string.tama_world_event_graduated),
        "STARTED_WORK" to context.getString(R.string.tama_world_event_started_work),
        "FINISHED_WORK" to context.getString(R.string.tama_world_event_finished_work),
        "GOT_PAID" to context.getString(R.string.tama_world_event_got_paid),
        "PLANTED" to context.getString(R.string.tama_world_event_planted),
        "WATERED" to context.getString(R.string.tama_world_event_watered),
        "HARVESTED" to context.getString(R.string.tama_world_event_harvested),
        "SOLD_CROPS" to context.getString(R.string.tama_world_event_sold_crops),
        "BATTLE_START" to context.getString(R.string.tama_world_event_battle_start),
        "EQUIPPED" to context.getString(R.string.tama_world_event_equipped),
        "FOUND_ITEM" to context.getString(R.string.tama_world_event_found_item),
        "LEVEL_UP" to context.getString(R.string.tama_world_event_level_up),
        "EVOLVED" to context.getString(R.string.tama_world_event_evolved),
        "QUEST_STARTED" to context.getString(R.string.tama_world_event_quest_started),
        "BOUGHT" to context.getString(R.string.tama_world_event_bought),
        "SOLD" to context.getString(R.string.tama_world_event_sold),
        "GOT_SICK" to context.getString(R.string.tama_world_event_got_sick),
        "NEGLECTED" to context.getString(R.string.tama_world_event_neglected),
        "OVERWORKED" to context.getString(R.string.tama_world_event_overworked),
        "OWNER_RETURNED" to context.getString(R.string.tama_world_event_owner_returned),
        "OWNER_TALKED" to context.getString(R.string.tama_world_event_owner_talked),
        "SUMMARY" to context.getString(R.string.tama_world_event_summary),
        "OTHER" to context.getString(R.string.tama_world_event_other),
        "NPC_SOCIAL" to context.getString(R.string.tama_world_event_npc_social),
        "ITEM_DROPPED" to context.getString(R.string.tama_world_event_item_dropped),
        "OBJECT_HELPED" to context.getString(R.string.tama_world_event_object_helped),
        "ITEM_BOUGHT" to context.getString(R.string.tama_world_event_item_bought),
        "ITEM_SOLD" to context.getString(R.string.tama_world_event_item_sold),
        "ENTERED_STRUCTURE_ACTION" to context.getString(R.string.tama_world_event_entered_structure_action),
        "LEFT_STRUCTURE_ACTION" to context.getString(R.string.tama_world_event_left_structure_action),
        "ENTERED_DUNGEON" to context.getString(R.string.tama_world_event_entered_dungeon),
        "ENTERED_ADVENTURE_GATE" to context.getString(R.string.tama_world_event_entered_adventure_gate),
        "ENTERED_STRUCTURE" to context.getString(R.string.tama_world_event_entered_structure),
        "LEFT_STRUCTURE" to context.getString(R.string.tama_world_event_left_structure)
    )
    val resultLabels = mapOf(
        "legacyEventId" to context.getString(R.string.tama_world_journal_result_earlier_event),
        "npcId" to context.getString(R.string.tama_world_journal_result_neighbor),
        "recipientId" to context.getString(R.string.tama_world_journal_result_recipient),
        "actorId" to context.getString(R.string.tama_world_journal_result_actor),
        "locationId" to context.getString(R.string.tama_world_journal_result_place),
        "structureId" to context.getString(R.string.tama_world_journal_result_place),
        "structureType" to context.getString(R.string.tama_world_journal_result_place_type),
        "itemId" to context.getString(R.string.tama_world_journal_result_item),
        "cropId" to context.getString(R.string.tama_world_journal_result_crop),
        "quantity" to context.getString(R.string.tama_world_journal_result_quantity),
        "total" to context.getString(R.string.tama_world_journal_result_total),
        "hungerBefore" to context.getString(R.string.tama_world_journal_result_hunger_before),
        "hydrationBefore" to context.getString(R.string.tama_world_journal_result_hydration_before),
        "energyBefore" to context.getString(R.string.tama_world_journal_result_energy_before),
        "friendship" to context.getString(R.string.tama_world_journal_result_friendship),
        "familiarity" to context.getString(R.string.tama_world_journal_result_familiarity),
        "trust" to context.getString(R.string.tama_world_journal_result_trust),
        "biome" to context.getString(R.string.tama_world_journal_result_biome),
        "discoveredBiome" to context.getString(R.string.tama_world_journal_result_biome),
        "objectId" to context.getString(R.string.tama_world_journal_result_object),
        "objectType" to context.getString(R.string.tama_world_journal_result_object_type),
        "stage" to context.getString(R.string.tama_world_journal_result_stage),
        "level" to context.getString(R.string.tama_world_journal_result_level),
        "amount" to context.getString(R.string.tama_world_journal_result_amount),
        "delta" to context.getString(R.string.tama_world_journal_result_change),
        "health" to context.getString(R.string.tama_world_journal_result_health),
        "reward" to context.getString(R.string.tama_world_journal_result_reward)
    )
    val locationLabels = mapOf(
        LegacyLocationAliases.HOME to context.getString(R.string.tama_world_structure_home),
        LegacyLocationAliases.SHOP to context.getString(R.string.tama_world_structure_shop),
        LegacyLocationAliases.PARK to context.getString(R.string.tama_world_structure_park),
        LegacyLocationAliases.HOSPITAL to context.getString(R.string.tama_world_structure_hospital),
        LegacyLocationAliases.ARCADE to context.getString(R.string.tama_world_structure_arcade),
        LegacyLocationAliases.ALCHEMIST to context.getString(R.string.tama_world_structure_alchemist),
        LegacyLocationAliases.SCHOOL to context.getString(R.string.tama_world_structure_school),
        LegacyLocationAliases.WORKPLACE to context.getString(R.string.tama_world_structure_workplace),
        LegacyLocationAliases.FARM to context.getString(R.string.tama_world_structure_farm),
        LegacyLocationAliases.BOXING_RING to context.getString(R.string.tama_world_structure_boxing_gym),
        LegacyLocationAliases.DUNGEON_A to context.getString(R.string.tama_world_structure_dungeon),
        LegacyLocationAliases.DUNGEON_B to context.getString(R.string.tama_world_structure_dungeon),
        LegacyLocationAliases.ADVENTURE_GATE to context.getString(R.string.tama_world_structure_adventure_gate),
        "farm_barn" to context.getString(R.string.tama_world_structure_farm_barn),
        "npc_home" to context.getString(R.string.tama_world_structure_npc_home),
        "npc_home_a" to context.getString(R.string.tama_world_structure_npc_home_a),
        "npc_home_b" to context.getString(R.string.tama_world_structure_npc_home_b),
        "npc_home_c" to context.getString(R.string.tama_world_structure_npc_home_c),
        "market_stall" to context.getString(R.string.tama_world_structure_market_stall)
    )

    fun normalized(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    fun knownLocationName(raw: String): String? {
        val normalized = normalized(raw)
        locationLabels[normalized]?.let { return it }
        LegacyLocationAliases.normalize(normalized)?.let { fixedId ->
            locationLabels[fixedId]?.let { return it }
        }
        return null
    }

    fun locationName(raw: String): String = knownLocationName(raw)
        ?: context.getString(R.string.tama_world_journal_unknown_place)

    fun actorName(raw: String): String? {
        // The generated world uses the stable actor token "pet", while legacy
        // rows may carry the persisted pet ID. Both identify the active pet.
        if (raw == petId || raw.equals("pet", ignoreCase = true)) {
            return petName.ifBlank { context.getString(R.string.tama_world_pet_unknown) }
        }
        return WorldNpcCatalog.find(normalized(raw))?.name
    }

    fun itemName(raw: String): String? {
        val id = normalized(raw)
        if (id == "rotten_crop") return context.getString(R.string.tama_item_rotten_crop)
        if (id in CropDefinitions.CROPS) return cropDisplayName(context, id)
        if (id.startsWith("crop_")) {
            val cropId = id.removePrefix("crop_")
            if (cropId in CropDefinitions.CROPS) return cropDisplayName(context, cropId)
        }
        if (id.startsWith("seed_")) {
            val cropId = id.removePrefix("seed_")
            if (cropId in CropDefinitions.CROPS) return seedDisplayText(cropId).resolve(locale)
        }
        WorldResourceCatalog.displayName(id, locale)?.let { return it }
        FarmTradeItemCatalog.displayText(id)?.resolve(locale)?.let { return it }
        for (vendorId in listOf(
            LegacyLocationAliases.SHOP,
            LegacyLocationAliases.HOSPITAL,
            LegacyLocationAliases.ALCHEMIST
        )) {
            val offer = runCatching { TamaCommerceCatalog.offer(context, id, vendorId) }.getOrNull()
            if (offer != null) return offer.item.name
        }
        return null
    }

    fun structureName(raw: String): String? {
        val type = runCatching { StructureType.valueOf(raw.trim().uppercase(Locale.ROOT)) }.getOrNull()
            ?: LegacyLocationAliases.structureType(raw)
        return type?.let { context.getString(structureTypeUi(it).labelRes) } ?: knownLocationName(raw)
    }

    fun biomeName(raw: String): String? = WorldBiome.entries
        .firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
        ?.let { context.getString(it.labelRes) }

    fun objectName(raw: String): String? = runCatching {
        WorldObjectType.valueOf(raw.trim().uppercase(Locale.ROOT))
    }.getOrNull()?.let { context.getString(objectLabelRes(it)) }

    fun tokenValue(raw: String): String? {
        val token = raw.trim().uppercase(Locale.ROOT)
        if (token.isBlank() || token !in JOURNAL_ENUM_VALUES) return null
        return stageLabels[token] ?: localizedToken(token, spanish)
    }

    fun numericOrTokenValue(raw: String): String? =
        raw.toDoubleOrNull()?.let { raw } ?: tokenValue(raw)

    fun builtInTitle(raw: String): String? {
        val key = raw.trim().uppercase(Locale.ROOT)
        titles[key]?.let { return it }
        return when {
            key.startsWith("NPC_") -> context.getString(
                R.string.tama_world_event_npc_action,
                localizedToken(key.removePrefix("NPC_"), spanish)
            )
            key.startsWith("TARGET_") -> context.getString(
                R.string.tama_world_event_target_action,
                localizedToken(key.removePrefix("TARGET_"), spanish)
            )
            key.startsWith("OBSERVATION_") -> context.getString(
                R.string.tama_world_event_observation,
                localizedToken(key.removePrefix("OBSERVATION_"), spanish)
            )
            else -> null
        }
    }

    fun resultValue(key: String, value: String): String? = when (normalized(key)) {
        "itemid", "cropid" -> itemName(value)
        "crop" -> itemName(value)
        "npcid", "recipientid", "actorid" -> actorName(value)
        "locationid", "structureid" -> knownLocationName(value)
        "structuretype" -> structureName(value)
        "biome", "discoveredbiome" -> biomeName(value)
        "objecttype" -> objectName(value)
        "stage" -> stageLabels[value.trim().uppercase(Locale.ROOT)]
            ?: numericOrTokenValue(value)
        "cropstage" -> numericOrTokenValue(value)
        "state", "status", "owner", "presence", "action", "goal", "role",
        "direction", "importance", "npcrole" -> tokenValue(value)
        "kind" -> structureName(value) ?: objectName(value) ?: tokenValue(value)
        "type" -> structureName(value) ?: objectName(value) ?: tokenValue(value)
        "objectid", "legacyeventid", "eventid", "requestid", "receiptid",
        "transitionid", "worldid", "petid", "targetid", "id" -> null
        "available", "ready", "walkable", "userowned", "isready", "decayed" -> tokenValue(value)
        else -> if (normalized(key).endsWith("id")) {
            null
        } else {
            value.toDoubleOrNull()?.let { value }
        }
    }

    return WorldJournalLabels(
        eventTitle = { raw -> builtInTitle(raw) ?: localizedToken(raw, spanish) },
        resultLabel = { raw ->
            resultLabels[raw]
                ?: resultLabels.entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }?.value
                ?: localizedToken(raw.camelToJournalKey(), spanish)
        },
        locationName = ::locationName,
        worldName = context.getString(R.string.tama_world_source_living),
        openEpisodeSummary = context.getString(R.string.tama_world_journal_episode_summary_pending),
        timeRange = { start, end ->
            context.getString(
                R.string.tama_world_journal_time_range,
                formatWorldTime(start, worldOffsetAt(start), locale),
                formatWorldTime(end, worldOffsetAt(end), locale)
            )
        },
        time = { timestamp -> formatWorldTime(timestamp, worldOffsetAt(timestamp), locale) },
        episodeTitle = { raw -> builtInTitle(raw) ?: raw },
        actorName = ::actorName,
        resultValue = ::resultValue
    )
}

/** Builds the complete locale-aware label set from app resources. */
fun localizedWorldUiLabels(context: Context, petName: String): WorldUiLabels {
    val displayLocale = context.resources.configuration.locales
        .takeIf { !it.isEmpty }
        ?.get(0)
        ?: Locale.getDefault()
    val spanish = displayLocale.language == "es"
    val deviceTimeZone = TimeZone.getDefault()
    return WorldUiLabels(
        petName = petName.ifBlank { context.getString(R.string.tama_world_pet_unknown) },
        biomeName = { biome -> context.getString(WorldBiome.valueOf(biome.name).labelRes) },
        goalName = { goal -> localizedToken(goal.name, spanish) },
        actionName = { action -> localizedToken(action.name, spanish) },
        roleName = { role -> localizedToken(role.name, spanish) },
        structureName = { structure ->
            context.getString(structureTypeUi(structure).labelRes)
        },
        objectName = { objectKind -> context.getString(objectLabelRes(objectKind)) },
        needName = { need -> context.getString(needLabelRes(need)) },
        presenceName = { presence -> localizedToken(presence.name, spanish) },
        stateName = { value ->
            val cropId = value.lowercase().removePrefix("crop_")
            if (cropId in CropDefinitions.CROPS) cropDisplayName(context, cropId)
            else localizedToken(value, spanish)
        },
        inspectorFieldName = { value -> localizedToken(value, spanish) },
        timeName = { timestamp ->
            formatWorldTime(
                epochMillis = timestamp,
                timezoneOffsetMinutes = deviceTimeZone.getOffset(timestamp) / 60_000,
                locale = displayLocale
            )
        }
    )
}

/** Builds trainer labels with the selected pet identity and current locale. */
fun localizedBrainUiLabels(context: Context, petName: String): BrainUiLabels {
    val spanish = context.resources.configuration.locales[0]?.language == "es"
    fun profileLabel(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
        TrainerProfile.ECO.name -> context.getString(R.string.tama_world_brain_profile_eco)
        TrainerProfile.BALANCED.name -> context.getString(R.string.tama_world_brain_profile_balanced)
        TrainerProfile.FAST.name -> context.getString(R.string.tama_world_brain_profile_fast)
        else -> context.getString(R.string.tama_world_brain_profile_custom)
    }
    fun checkpointVersionLabel(checkpoint: BrainRuntimeCheckpoint): String =
        checkpoint.policyVersion
            ?.substringAfterLast('-')
            ?.toLongOrNull()
            ?.let { context.getString(R.string.tama_world_brain_checkpoint_version, it) }
            ?: profileLabel(checkpoint.profile)
    fun rewardConfigurationLabel(raw: String): String {
        val rewardVersion = Regex("""(?:^|;)decomposed-v([0-9]+)(?:;|$)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val curriculumId = Regex("""(?:^|;)curriculum=(-?[0-9]+)(?:;|$)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val cooldownSteps = Regex("""(?:^|;)guard[.]socialCooldownSteps=([0-9]+)(?:;|$)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val curriculumLabel = curriculumId
            ?.let { runCatching { CurriculumLevel.fromId(it) }.getOrNull() }
            ?.let { context.getString(R.string.tama_world_brain_curriculum_level, it.id) }
        return if (rewardVersion != null && curriculumLabel != null && cooldownSteps != null) {
            context.getString(
                R.string.tama_world_brain_reward_configuration_summary,
                rewardVersion,
                curriculumLabel,
                cooldownSteps
            )
        } else {
            context.getString(R.string.tama_world_brain_reward_configuration_saved)
        }
    }
    return BrainUiLabels(
        currentBrainName = context.getString(R.string.tama_world_brain_name, petName),
        curriculumName = { level ->
            context.getString(R.string.tama_world_brain_curriculum_level, level.id)
        },
        checkpointName = { checkpoint ->
            context.getString(
                R.string.tama_world_brain_checkpoint_label,
                checkpointVersionLabel(checkpoint)
            )
        },
        unavailable = context.getString(R.string.tama_world_brain_unavailable),
        actionName = { action -> localizedToken(action.name, spanish) },
        rewardMetricName = context.getString(R.string.tama_world_brain_metric_reward),
        successMetricName = context.getString(R.string.tama_world_brain_metric_success),
        lossMetricName = context.getString(R.string.tama_world_brain_chart_loss),
        objectiveStepsMetricName = context.getString(R.string.tama_world_brain_metric_steps),
        pathEfficiencyMetricName = context.getString(R.string.tama_world_brain_metric_efficiency),
        stuckMetricName = context.getString(R.string.tama_world_brain_metric_stuck),
        criticalNeedsMetricName = context.getString(R.string.tama_world_brain_metric_critical),
        explorationMetricName = context.getString(R.string.tama_world_brain_metric_exploration),
        stepsUnit = context.getString(R.string.tama_world_brain_unit_steps),
        returnUnit = context.getString(R.string.tama_world_brain_unit_return),
        percentageUnit = context.getString(R.string.tama_world_brain_unit_percent),
        lossUnit = context.getString(R.string.tama_world_brain_unit_loss),
        checkpointMetrics = { episodes ->
            context.getString(R.string.tama_world_brain_checkpoint_episodes, episodes)
        },
        checkpointCreatedAt = { timestamp ->
            context.getString(
                R.string.tama_world_brain_checkpoint_created,
                formatBrainTimestamp(context, timestamp)
            )
        },
        checkpointMeasurements = { checkpoint ->
            context.getString(
                R.string.tama_world_brain_checkpoint_measurements,
                checkpoint.objectiveSteps,
                checkpoint.pathEfficiency * 100f,
                checkpoint.explorationScore * 100f
            )
        },
        checkpointRewardConfiguration = ::rewardConfigurationLabel
    )
}

private fun structureTypeUi(type: StructureType): WorldStructureType = when (type) {
    StructureType.HOME -> WorldStructureType.HOME
    StructureType.SHOP -> WorldStructureType.SHOP
    StructureType.PARK -> WorldStructureType.PARK
    StructureType.HOSPITAL -> WorldStructureType.HOSPITAL
    StructureType.ARCADE -> WorldStructureType.ARCADE
    StructureType.ALCHEMIST -> WorldStructureType.ALCHEMIST
    StructureType.SCHOOL -> WorldStructureType.SCHOOL
    StructureType.WORKPLACE -> WorldStructureType.WORKPLACE
    StructureType.FARM -> WorldStructureType.FARM
    StructureType.BOXING_RING -> WorldStructureType.BOXING_GYM
    StructureType.DUNGEON_A, StructureType.DUNGEON_B -> WorldStructureType.DUNGEON_ENTRANCE
    StructureType.ADVENTURE_GATE -> WorldStructureType.ADVENTURE_GATE
    StructureType.FARM_BARN -> WorldStructureType.FARM_BARN
    StructureType.NPC_HOME_A -> WorldStructureType.NPC_HOME_A
    StructureType.NPC_HOME_B -> WorldStructureType.NPC_HOME_B
    StructureType.NPC_HOME_C -> WorldStructureType.NPC_HOME_C
    StructureType.MARKET_STALL -> WorldStructureType.MARKET_STALL
}

private fun objectLabelRes(type: WorldObjectType): Int = when (type) {
    WorldObjectType.TREE -> R.string.tama_world_resource_tree
    WorldObjectType.FALLEN_LOG -> R.string.tama_world_object_fallen_log
    WorldObjectType.BUSH, WorldObjectType.BERRY_PATCH -> R.string.tama_world_resource_berry_bush
    WorldObjectType.FLOWER_MEADOW -> R.string.tama_world_resource_flower
    WorldObjectType.MUSHROOM -> R.string.tama_world_resource_mushroom
    WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT -> R.string.tama_world_resource_herb
    WorldObjectType.STONE, WorldObjectType.CACTUS -> R.string.tama_world_resource_rock
    WorldObjectType.REED -> R.string.tama_world_object_reeds
    WorldObjectType.SHELL -> R.string.tama_world_resource_shell
    WorldObjectType.POND -> R.string.tama_world_object_pond
    WorldObjectType.DROPPED_ITEM -> R.string.tama_world_object_dropped_item
    WorldObjectType.BENCH -> R.string.tama_world_object_bench
    WorldObjectType.MARKET_STALL -> R.string.tama_world_structure_market_stall
    WorldObjectType.CUSTOM -> R.string.tama_world_resource_other
}

private fun needLabelRes(need: NeedType): Int = when (need) {
    NeedType.HUNGER -> R.string.tama_world_stat_hunger
    NeedType.HYDRATION -> R.string.tama_world_stat_hydration
    NeedType.ENERGY -> R.string.tama_world_stat_energy
    NeedType.HEALTH -> R.string.tama_world_stat_health
    NeedType.HYGIENE -> R.string.tama_world_stat_hygiene
    NeedType.HAPPINESS -> R.string.tama_world_stat_happiness
    NeedType.SOCIAL -> R.string.tama_world_stat_social
    NeedType.CURIOSITY -> R.string.tama_world_stat_curiosity
}

private fun localizedToken(value: String, spanish: Boolean): String {
    if (!spanish) return humanizeToken(value)
    val exact = SPANISH_LABELS[value.uppercase()]
    if (exact != null) return exact
    return value.split('_').joinToString(" ") { word ->
        SPANISH_WORDS[word.uppercase()] ?: humanizeToken(word)
    }
}

private fun humanizeToken(value: String): String = value
    .lowercase()
    .split('_', '-', ' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun String.camelToJournalKey(): String = replace(
    Regex("([a-z0-9])([A-Z])"), "$1_$2"
)

private val SPANISH_LABELS = mapOf(
    "HOME" to "Casa", "WORLD" to "Mundo", "INTERIOR" to "Interior",
    "IDLE" to "En espera", "FIND_FOOD" to "Buscar comida", "FIND_WATER" to "Buscar agua",
    "REST" to "Descansar", "SLEEP" to "Dormir", "SOCIALIZE" to "Socializar",
    "PLAY" to "Jugar", "EXPLORE" to "Explorar", "RETURN_HOME" to "Volver a casa",
    "VISIT_FRIEND" to "Visitar a un amigo", "GATHER_WOOD" to "Recoger madera",
    "GATHER_HERBS" to "Recoger hierbas", "FARM" to "Cuidar la granja",
    "STUDY" to "Estudiar", "WORK" to "Trabajar", "TRAIN" to "Entrenar",
    "INVESTIGATE" to "Investigar", "WANDER" to "Pasear", "ENTER_DUNGEON" to "Entrar en la mazmorra",
    "FOLLOW_NPC" to "Seguir al vecino", "HELP_NPC" to "Ayudar al vecino",
    "RECOVER_STUCK" to "Recuperarse",
    "WAIT" to "Esperar", "WALK" to "Caminar", "RUN" to "Correr", "FOLLOW" to "Seguir",
    "APPROACH" to "Acercarse", "FLEE" to "Huir", "RETURN_HOME" to "Volver a casa",
    "ENTER_STRUCTURE" to "Entrar", "EXIT_STRUCTURE" to "Salir", "LOOK" to "Mirar",
    "INSPECT" to "Inspeccionar", "SEARCH_AREA" to "Buscar en la zona", "OBSERVE_NPC" to "Observar vecino",
    "OBSERVE_OBJECT" to "Observar objeto", "SIT" to "Sentarse", "WAKE" to "Despertar",
    "EAT" to "Comer", "DRINK" to "Beber", "WASH" to "Lavarse", "USE_MEDICINE" to "Usar medicina",
    "PICK_UP" to "Recoger", "DROP" to "Soltar", "FORAGE" to "Recolectar", "HARVEST_WILD_PLANT" to "Cosechar planta",
    "CHOP_TREE" to "Talar árbol", "GATHER_STONE" to "Recoger piedra", "GATHER_HERB" to "Recoger hierba",
    "TILL_SOIL" to "Arar suelo", "PLANT" to "Plantar", "WATER" to "Regar", "POUR_WATER" to "Echar agua",
    "FERTILIZE" to "Abonar", "HARVEST_CROP" to "Cosechar cultivo", "REMOVE_DEAD_CROP" to "Retirar cultivo muerto",
    "STORE_PRODUCE" to "Guardar cosecha", "GREET" to "Saludar", "TALK" to "Hablar",
    "PLAY_WITH" to "Jugar juntos", "FOLLOW_NPC" to "Seguir vecino", "GIVE_ITEM" to "Dar objeto",
    "RECEIVE_ITEM" to "Recibir objeto", "THANK" to "Dar las gracias", "SAY_GOODBYE" to "Despedirse",
    "BUY" to "Comprar", "SELL" to "Vender", "TRADE" to "Intercambiar", "USE_ARCADE" to "Usar arcade",
    "RELAX" to "Relajarse", "TRAIN_BOXING" to "Entrenar boxeo",
    "VISIT_HOSPITAL" to "Visitar el hospital", "USE_ALCHEMY" to "Usar alquimia",
    "VISIT_INTERESTING_PLACE" to "Visitar un lugar interesante",
    "OPEN" to "Abrir", "CLOSE" to "Cerrar", "USE" to "Usar", "ACTIVATE" to "Activar",
    "FARMER" to "Granjero", "BOXING_COACH" to "Entrenador de boxeo", "SHOP_SELLER" to "Vendedor",
    "TEACHER" to "Profesor", "DOCTOR" to "Médico", "ADVENTURER" to "Aventurero",
    "ARCADE_HOST" to "Encargado del arcade", "ALCHEMIST" to "Alquimista", "PARK_RESIDENT" to "Residente del parque",
    "RECYCLER" to "Reciclador", "MARKET_SELLER" to "Vendedor del mercado", "WORKPLACE_RESIDENT" to "Trabajador",
    "ACTIVE" to "Activo", "AVAILABLE" to "Disponible", "EMPTY" to "Vacío", "DEPLETED" to "Agotado",
    "REGROWING" to "Regenerándose", "DECAYED" to "Marchito", "DRY" to "Seco", "WET" to "Húmedo",
    "TRUE" to "Sí", "FALSE" to "No", "PLAYER" to "Jugador", "PATCH" to "Patch", "UNASSIGNED" to "Sin asignar",
    "STUMP" to "Tocón", "FULL" to "Lleno", "LOCKED" to "Bloqueado", "UNLOCKED" to "Desbloqueado",
    "PENDING" to "Pendiente", "RUNNING" to "En ejecución", "COMPLETED" to "Completado",
    "BLOCKED" to "Bloqueado", "INTERRUPTED" to "Interrumpido", "TRACE" to "Traza",
    "ROUTINE" to "Rutina", "NOTABLE" to "Destacable", "MEMORABLE" to "Memorable", "MAJOR" to "Importante",
    "EGG" to "Huevo", "BABY" to "Bebé", "CHILD" to "Cría", "TEEN" to "Adolescente",
    "ADULT" to "Adulto", "SENIOR" to "Anciano",
    "CUSTOM" to "Personalizado", "NONE" to "Ninguno",
    "NORTH" to "Norte", "NORTH_EAST" to "Noreste", "EAST" to "Este", "SOUTH_EAST" to "Sureste",
    "SOUTH" to "Sur", "SOUTH_WEST" to "Suroeste", "WEST" to "Oeste", "NORTH_WEST" to "Noroeste",
    "GOAL" to "Objetivo", "ACTION" to "Acción", "SCHEDULE" to "Rutina", "FRIENDSHIP" to "Amistad",
    "TRUST" to "Confianza", "LOCATION" to "Ubicación", "TYPE" to "Tipo", "STATE" to "Estado",
    "QUANTITY" to "Cantidad", "COORDINATES" to "Coordenadas", "TERRAIN" to "Terreno", "BIOME" to "Bioma",
    "WALKABLE" to "Transitable", "MOVEMENT_COST" to "Coste de movimiento", "ENTRANCE" to "Entrada",
    "SIZE" to "Tamaño", "OWNER" to "Propietario", "SOIL" to "Suelo", "CROP" to "Cultivo",
    "CROP_STAGE" to "Etapa del cultivo", "READY" to "Listo", "DEAD" to "Muerto", "PLANTED_AT" to "Plantado en",
    "WATERED_AT" to "Regado en", "STAGE" to "Etapa"
)

private val SPANISH_WORDS = mapOf(
    "MOVE_N" to "Norte", "MOVE_NE" to "Noreste", "MOVE_E" to "Este", "MOVE_SE" to "Sureste",
    "MOVE_S" to "Sur", "MOVE_SW" to "Suroeste", "MOVE_W" to "Oeste", "MOVE_NW" to "Noroeste",
    "INTERACT" to "Interactuar", "SEARCH" to "Buscar", "CURRENT" to "Actual", "CANDIDATE" to "Candidato",
    "NEED" to "Necesidad", "FAILURE" to "fallo", "REASON" to "motivo", "UNKNOWN" to "desconocido"
)

/**
 * Values emitted by the living-world event payloads that are safe to render as
 * localized tokens. Unknown strings stay hidden so internal IDs or future enum
 * values never leak through the journal's generic fact formatter.
 */
private val JOURNAL_ENUM_VALUES: Set<String> = buildSet {
    addAll(SPANISH_LABELS.keys)
    addAll(ActionId.entries.map { it.name })
    addAll(ActionState.entries.map { it.name })
    addAll(Direction.entries.map { it.name })
    addAll(EventImportance.entries.map { it.name })
    addAll(GoalId.entries.map { it.name })
    addAll(GrowthStage.entries.map { it.name })
    addAll(NpcRole.entries.map { it.name })
    addAll(PresenceMode.entries.map { it.name })
    addAll(StructureType.entries.map { it.name })
    addAll(WorldObjectType.entries.map { it.name })
    addAll(WorldBiome.entries.map { it.name })
}
