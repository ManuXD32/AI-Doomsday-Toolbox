package com.example.llamadroid.tama.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import com.example.llamadroid.ui.walkthrough.LocalWalkthroughActive
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxCatalogProvider
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.isTamaDefaultPicGenModel
import com.example.llamadroid.onnx.resolveOnnxCatalogEntry
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaQuestChecklistItemEntity
import com.example.llamadroid.tama.db.TamaStudyLabelEntity
import com.example.llamadroid.tama.db.TamaStudySessionEntity
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaArtworkManager
import com.example.llamadroid.tama.game.TamaDailyDreamManager
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.TamaStudySessionSupport
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateProfile
import com.example.llamadroid.tama.rpg.AdventureGateRepository
import com.example.llamadroid.tama.rpg.AdventureGateSupplyDefinition
import com.example.llamadroid.tama.rpg.AdventureGateSupplyKind
import com.example.llamadroid.tama.rpg.NightArenaGenerator
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.pressAndHoldRepeat
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.rememberPressAndHoldRepeatState
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt

private const val STUDY_ACTION_ICON_ASSET = "tama/actions/study.png"
private const val WORK_ACTION_ICON_ASSET = "tama/actions/work.png"
private const val PLAY_ACTION_ICON_ASSET = "tama/actions/play.png"
private const val SHOWER_ACTION_ICON_ASSET = "tama/actions/shower.png"
private const val MOP_ACTION_ICON_ASSET = "tama/actions/mop.png"
private const val POOP_PROP_ASSET = "tama/decor/poop.png"
private const val PARK_GIFT_PRESENT_ASSET = "tama/props/park_present.png"
private const val QUEST_REWARD_MONEY_SACK_ASSET = "tama/props/quest_money_sack.png"
private const val TRANSFORMATION_CLOUD_ASSET = "tama/actions/transformation_cloud.png"
private const val PARK_MARKET_BACKGROUND_ASSET = "tama/backgrounds/street_market.png"
private const val PARK_QUEST_BOARD_BACKGROUND_ASSET = "tama/backgrounds/quest_board_dialog.png"
private const val TAMA_HEADER_EMOJI = "💟"
private const val TAMA_PET_VIEW_EMOJI = "🐾"
private const val TAMA_MAP_VIEW_EMOJI = "🗺️"
private const val TAMA_HUNGER_EMOJI = "🍖"
private const val TAMA_HAPPINESS_EMOJI = "😊"
private const val TAMA_HEALTH_EMOJI = "❤️"
private const val TAMA_ENERGY_EMOJI = "🌙"
private const val TAMA_HYGIENE_EMOJI = "🧼"
private const val TAMA_MONEY_EMOJI = "💰"
private const val TAMA_EDUCATION_EMOJI = "📚"
private const val TAMA_INTROSPECTION_EMOJI = "🪞"
private const val TAMA_EXERCISE_ICON_ASSET = "tama/icons/ui/exercise_glove.png"
private const val TAMA_CHAT_EMOJI = "💬"
private const val TAMA_INVENTORY_EMOJI = "🎒"
private const val TAMA_CHECKLIST_EMOJI = "✅"
private const val TAMA_SLEEP_EMOJI = "🌙"
private const val TAMA_WAKE_EMOJI = "☀️"
private const val TAMA_FEED_EMOJI = "🍖"
private const val TAMA_BUY_EMOJI = "🛒"
private const val TAMA_ARCADE_EMOJI = "🕹️"
private const val TAMA_RELAX_EMOJI = "🌳"
private const val TAMA_CHANGE_EMOJI = "⚗️"
private const val TAMA_HEAL_EMOJI = "💊"
private const val TAMA_MENU_EMOJI = "⭐"
private const val TAMA_STOP_EMOJI = "✋"

// Retro color palette (like classic Tamagotchi)
val TamaBackground = Color(0xFFD4D4AA)  // Cream/greenish LCD background
val TamaDark = Color(0xFF2C2C2C)         // Dark for pixels
val TamaLight = Color(0xFFE8E8D0)        // Light for highlights
val TamaAccent = Color(0xFF5A5A5A)       // Mid-gray
val TamaMutedText = Color(0xFF434339)    // Darker secondary text on light surfaces
val TamaHelperText = Color(0xFFB8B8B0)   // Lighter helper text for dark dialog surfaces

/**
 * Main Tama Tab screen.
 */
@Composable
fun TamaScreen(
    navController: NavController,
    gameEngine: TamaGameEngine,
    settingsRepo: SettingsRepository,
    agentService: TamaAgentService,
    onChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pet by gameEngine.pet.collectAsState()
    val events by gameEngine.events.collectAsState()
    val schoolPaintingEnabled by settingsRepo.tamaSchoolPaintingEnabled.collectAsState()
    val artworkFeed = remember(pet?.id) {
        pet?.let { currentPet -> gameEngine.observeArtworks(currentPet.id) } ?: flowOf(emptyList())
    }
    val artworks by artworkFeed.collectAsState(initial = emptyList())
    val studyLabelsFeed = remember(pet?.id) {
        pet?.let { currentPet -> gameEngine.observeStudyLabels(currentPet.id) } ?: flowOf(emptyList())
    }
    val studyLabels by studyLabelsFeed.collectAsState(initial = emptyList())
    val studySessionsFeed = remember(pet?.id) {
        pet?.let { currentPet -> gameEngine.observeStudySessions(currentPet.id) } ?: flowOf(emptyList())
    }
    val studySessions by studySessionsFeed.collectAsState(initial = emptyList())
    val activeStudySessionFeed = remember(pet?.id) {
        pet?.let { currentPet -> gameEngine.observeActiveStudySession(currentPet.id) } ?: flowOf(null)
    }
    val activeStudySession by activeStudySessionFeed.collectAsState(initial = null)
    val questChecklistFeed = remember(pet?.id) {
        pet?.let { currentPet -> gameEngine.observeQuestChecklist(currentPet.id) }
            ?: flowOf<List<TamaQuestChecklistItemEntity>>(emptyList())
    }
    val questChecklist by questChecklistFeed.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var queuedPaintingArtworkId by rememberSaveable { mutableStateOf<String?>(null) }
    var artworkAwaitingRevealId by rememberSaveable { mutableStateOf<String?>(null) }
    var dreamAlbumAwaitingRevealId by rememberSaveable { mutableStateOf<String?>(null) }
    var artworkDialog by remember { mutableStateOf<TamaArtworkEntity?>(null) }
    var dreamAlbumDialog by remember { mutableStateOf<TamaDreamAlbumPreview?>(null) }
    var showInventoryDialog by remember { mutableStateOf(false) }
    var pendingDecorationId by remember { mutableStateOf<String?>(null) }
    val artworkAwaitingReveal by if (artworkAwaitingRevealId != null) {
        gameEngine.observeArtwork(artworkAwaitingRevealId!!).collectAsState(initial = null)
    } else {
        remember { mutableStateOf<TamaArtworkEntity?>(null) }
    }

    var showNameDialog by remember { mutableStateOf(false) }
    val activeWalkthrough = LocalWalkthroughActive.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val guidedVisit = remember { activeWalkthrough }
    var showMenu by remember { mutableStateOf(false) }

    // File picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                    if (outputStream == null) {
                        Toast.makeText(context, resources.getString(R.string.tama_export_failed, resources.getString(R.string.error_generic)), Toast.LENGTH_SHORT).show()
                    } else {
                        outputStream.use { stream ->
                            if (gameEngine.exportToBackupZip(stream)) {
                                Toast.makeText(context, resources.getString(R.string.tama_export_success), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, resources.getString(R.string.tama_export_failed, resources.getString(R.string.error_generic)), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, resources.getString(R.string.tama_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                scope.launch {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        Toast.makeText(context, resources.getString(R.string.tama_import_failed, resources.getString(R.string.error_generic)), Toast.LENGTH_SHORT).show()
                    } else {
                        inputStream.use { stream ->
                            val success = gameEngine.importFromBackup(stream)
                            if (success) {
                                Toast.makeText(context, resources.getString(R.string.tama_import_success), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, resources.getString(R.string.tama_import_invalid), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.tama_import_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Load pet on first composition
    LaunchedEffect(Unit) {
        val loadedPet = gameEngine.loadPet()
        if (loadedPet == null && !guidedVisit) {
            showNameDialog = true
        }
    }

    // UI time refresh - update every second to show current age/time
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)  // Update every second
            currentTime = System.currentTimeMillis()
        }
    }

    LaunchedEffect(pet?.id) {
        if (pet?.id == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5000)
            gameEngine.updateForTimePassed()
            gameEngine.refreshActiveStudySession()
        }
    }

    // Animation cooldown state (for care actions)
    var currentAction by remember { mutableStateOf<String?>(null) }
    var actionCooldown by remember { mutableStateOf(false) }

    // Computed action for display
    val displayAction = remember(currentAction, pet?.currentActivity, activeStudySession?.currentPhase) {
        currentAction ?: when (pet?.currentActivity) {
            ActivityType.WORKING -> "working"
            ActivityType.STUDYING -> if (TamaStudySessionSupport.isRestPhase(activeStudySession)) "sleeping" else "studying"
            ActivityType.TRAINING -> "training"
            ActivityType.RELAXING -> "sunbathing"
            else -> "idle"
        }
    }

    // Dialogs
    var showFeedDialog by remember { mutableStateOf(false) }
    var showShopDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showSecondResetDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showWorkDialog by remember { mutableStateOf(false) }
    var showTrainingDialog by remember { mutableStateOf(false) }
    var showAlchemistDialog by remember { mutableStateOf(false) }
    var showHospitalDialog by remember { mutableStateOf(false) }
    var showAdventureGateWarningDialog by remember { mutableStateOf(false) }
    var showStudyDialog by remember { mutableStateOf(false) }
    var showQuestBoardDialog by remember { mutableStateOf(false) }
    var showMarketBoardDialog by remember { mutableStateOf(false) }
    var showQuestChecklistDialog by remember { mutableStateOf(false) }
    var questBoard by remember { mutableStateOf<TamaQuestBoard?>(null) }
    var marketBoard by remember { mutableStateOf<TamaMarketBoard?>(null) }
    val requestAdventureGateEntry = {
        showAdventureGateWarningDialog = true
    }
    var questCompletionPresentation by remember { mutableStateOf<TamaQuestCompletionPresentation?>(null) }
    var sleepyFairyReminder by remember { mutableStateOf<TamaSleepyFairyReminder?>(null) }
    var wasInPrincipalHomeRoom by remember { mutableStateOf(false) }

    LaunchedEffect(showQuestBoardDialog, pet?.id, currentTime) {
        if (!showQuestBoardDialog || pet?.id == null) return@LaunchedEffect
        val board = questBoard
        val needsRefresh = board == null ||
            currentTime >= board.nextRefreshAt ||
            board.accepted.any { quest -> (quest.expiresAt ?: Long.MAX_VALUE) <= currentTime }
        if (needsRefresh) {
            questBoard = gameEngine.getParkQuestBoard(currentTime)
        }
    }

    LaunchedEffect(pet?.currentParkEncounter?.npcId, pet?.currentParkEncounter?.phase) {
        val encounter = pet?.currentParkEncounter
        if (encounter?.type == TamaParkEncounterType.SELLER) {
            marketBoard = gameEngine.getParkMarketBoard()
        }
    }

    // View mode: Pet or Map
    var showMap by remember { mutableStateOf(false) }
    val currentLocation by gameEngine.currentLocation.collectAsState()
    val isPrincipalHomeRoomVisible = !showMap &&
        pet != null &&
        currentLocation?.type == LocationType.HOME &&
        pet?.homeRoomId == TamaRoomCatalog.PRINCIPAL_ROOM_ID
    val configuration = LocalConfiguration.current
    val localeTag = remember(configuration) {
        configuration.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
    }

    LaunchedEffect(isPrincipalHomeRoomVisible, pet?.id) {
        if (pet?.id == null) {
            sleepyFairyReminder = null
            wasInPrincipalHomeRoom = false
            return@LaunchedEffect
        }
        if (!isPrincipalHomeRoomVisible) {
            sleepyFairyReminder = null
            wasInPrincipalHomeRoom = false
            return@LaunchedEffect
        }
        if (!wasInPrincipalHomeRoom) {
            sleepyFairyReminder = gameEngine.maybeCreateSleepyFairyReminder(currentTime)
            wasInPrincipalHomeRoom = true
        }
    }

    LaunchedEffect(sleepyFairyReminder?.shownAt) {
        val reminder = sleepyFairyReminder ?: return@LaunchedEffect
        kotlinx.coroutines.delay(TAMA_SLEEPY_FAIRY_AUTO_HIDE_MS)
        if (sleepyFairyReminder?.shownAt == reminder.shownAt) {
            sleepyFairyReminder = null
        }
    }

    // Fixed city and location state (no city generation)
    val cityName = stringResource(R.string.tama_city_hometown)
    val cityLocations = remember(localeTag) {
        val coreLocations = listOf(
            Triple(0, 0, com.example.llamadroid.tama.data.LocationType.HOME),
            Triple(1, 0, com.example.llamadroid.tama.data.LocationType.SHOP),
            Triple(2, 0, com.example.llamadroid.tama.data.LocationType.PARK),
            Triple(3, 0, com.example.llamadroid.tama.data.LocationType.HOSPITAL),
            Triple(4, 0, com.example.llamadroid.tama.data.LocationType.ARCADE),
            Triple(0, 1, com.example.llamadroid.tama.data.LocationType.ALCHEMIST),
            Triple(1, 1, com.example.llamadroid.tama.data.LocationType.SCHOOL),
            Triple(2, 1, com.example.llamadroid.tama.data.LocationType.WORKPLACE),
            Triple(3, 1, com.example.llamadroid.tama.data.LocationType.FARM),
            Triple(4, 1, com.example.llamadroid.tama.data.LocationType.BOXING_RING),
            Triple(0, 2, com.example.llamadroid.tama.data.LocationType.DUNGEON),
            Triple(2, 2, com.example.llamadroid.tama.data.LocationType.ADVENTURE_GATE),
            Triple(4, 2, com.example.llamadroid.tama.data.LocationType.DUNGEON),
        )

        coreLocations.map { (x, y, type) ->
            com.example.llamadroid.tama.data.TamaLocation(
                id = "fixed_${x}_${y}",
                name = type.localizedName(context),
                type = type,
                description = type.localizedDescription(context),
                cityId = "hometown",
                x = x, y = y,
                isDiscovered = type == com.example.llamadroid.tama.data.LocationType.HOME
            )
        }
    }
    var selectedLocation by remember { mutableStateOf<TamaLocation?>(null) }

    // Helper to perform action with cooldown and Toast feedback
    fun actionDisplayDuration(actionName: String?): Long = when (actionName?.lowercase()) {
        "eating" -> 2200L
        "sleeping" -> 1800L
        "walking" -> 1700L
        "poop_cleaning" -> 1700L
        "transforming" -> 2300L
        else -> 1500L
    }

    fun shouldShowResultToast(result: TamaGameEngine.ActionResult): Boolean {
        if (!result.success) return true
        return result.action in setOf("harvesting", "buying", "selling", "transforming", "sleeping")
    }

    fun showResultToast(result: TamaGameEngine.ActionResult, force: Boolean = false) {
        if (force || shouldShowResultToast(result)) {
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun performAction(action: suspend () -> TamaGameEngine.ActionResult) {
        if (actionCooldown) return
        actionCooldown = true
        scope.launch {
            val result = action()
            showResultToast(result)
            if (result.success) {
                currentAction = result.action
                kotlinx.coroutines.delay(actionDisplayDuration(result.action))
                currentAction = null
            }
            actionCooldown = false
        }
    }

    LaunchedEffect(artworkAwaitingReveal?.id, artworkAwaitingReveal?.status) {
        val artwork = artworkAwaitingReveal ?: return@LaunchedEffect
        if (artwork.id != artworkAwaitingRevealId) return@LaunchedEffect
        when (artwork.status) {
            TamaArtworkStatus.COMPLETED.name -> {
                artworkDialog = artwork
                artworkAwaitingRevealId = null
            }
            TamaArtworkStatus.FAILED.name -> {
                Toast.makeText(
                    context,
                    artwork.errorMessage ?: resources.getString(R.string.error_generic),
                    Toast.LENGTH_SHORT
                ).show()
                artworkAwaitingRevealId = null
            }
        }
    }

    suspend fun queuePaintingIfEnabled(): String? {
        val currentPet = gameEngine.pet.value ?: return null
        if (!schoolPaintingEnabled) return null
        return TamaArtworkManager.queuePainting(context, currentPet, settingsRepo)
            .onSuccess { queuedPaintingArtworkId = it.id }
            .onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: resources.getString(R.string.error_generic),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .getOrNull()
            ?.id
    }

    LaunchedEffect(dreamAlbumAwaitingRevealId, artworks) {
        val albumId = dreamAlbumAwaitingRevealId ?: return@LaunchedEffect
        val albumArtworks = artworks
            .filter { it.albumId == albumId }
            .sortedBy { it.albumIndex }
        if (albumArtworks.isEmpty()) return@LaunchedEffect
        when {
            albumArtworks.any { it.status == TamaArtworkStatus.FAILED.name } -> {
                Toast.makeText(
                    context,
                    albumArtworks.firstOrNull { it.status == TamaArtworkStatus.FAILED.name }?.errorMessage
                        ?: resources.getString(R.string.error_generic),
                    Toast.LENGTH_SHORT
                ).show()
                gameEngine.clearPendingDreamAlbum(albumId)
                dreamAlbumAwaitingRevealId = null
            }
            albumArtworks.size >= 4 && albumArtworks.all { it.status == TamaArtworkStatus.COMPLETED.name } -> {
                val isDeepDream = albumArtworks.firstOrNull()?.sourceActivity == "deep_sleeping"
                dreamAlbumDialog = TamaDreamAlbumPreview(
                    albumId = albumId,
                    story = albumArtworks.firstNotNullOfOrNull { it.albumSummary?.takeIf(String::isNotBlank) }.orEmpty(),
                    dreamDate = albumArtworks.firstNotNullOfOrNull { it.albumDate?.takeIf(String::isNotBlank) },
                    artworks = albumArtworks
                )
                if (isDeepDream && pet != null) {
                    agentService.scheduleSummary(pet!!, force = false)
                }
                dreamAlbumAwaitingRevealId = null
            }
        }
    }

    LaunchedEffect(pet?.pendingDreamAlbumId, pet?.isSleeping, dreamAlbumDialog?.albumId) {
        val pendingAlbumId = pet?.pendingDreamAlbumId ?: return@LaunchedEffect
        if (pet?.isSleeping == true) return@LaunchedEffect
        if (dreamAlbumDialog?.albumId == pendingAlbumId) return@LaunchedEffect
        if (dreamAlbumAwaitingRevealId != pendingAlbumId) {
            dreamAlbumAwaitingRevealId = pendingAlbumId
        }
    }

    fun handleActivityStart(activity: ActivityType) {
        scope.launch {
            val result = gameEngine.startActivity(activity)
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            if (result.success && activity == ActivityType.STUDYING) {
                queuePaintingIfEnabled()
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(TamaBackground)) {
        // Reserve the pet header before sizing the scene on short tablet windows.
        val roomMaxSize = minOf(560.dp, (maxHeight - 64.dp).coerceAtLeast(240.dp))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (pet == null && guidedVisit) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tour_tama_missing_body))
                Button(onClick = { showNameDialog = true }) { Text(stringResource(R.string.tour_tama_setup)) }
            }
        }
        // Header with view toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TamaDark)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pet name and info
            if (pet != null) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TamaEmojiIcon(TAMA_HEADER_EMOJI, fontSize = 16.sp)
                    Text(
                        text = pet!!.name,
                        color = TamaLight,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val roomLabel = stringResource(R.string.soft_studio_tama_room)
                    val mapLabel = stringResource(R.string.soft_studio_tama_map)
                    IconButton(
                        onClick = {
                            showMap = false
                            walkthroughTargets?.recordEvent("tama.room")
                        },
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = roomLabel
                            selected = !showMap
                        }
                    ) {
                        TamaEmojiIcon(TAMA_PET_VIEW_EMOJI, fontSize = 16.sp)
                    }
                    IconButton(
                        onClick = {
                            showMap = true
                            walkthroughTargets?.recordEvent("tama.room")
                        },
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = mapLabel
                            selected = showMap
                        }
                    ) {
                        TamaEmojiIcon(TAMA_MAP_VIEW_EMOJI, fontSize = 16.sp)
                    }
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.tama_btn_menu), tint = TamaLight)
                    }
                }
            } else {
                Text(
                    text = "Tama",
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        // Keep the original square room proportions; surrounding chrome must not crop the art.
        Box(
            modifier = Modifier
                .widthIn(max = roomMaxSize)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .aspectRatio(1f)
                .padding(16.dp)
                .testTag("soft_studio_tama_room_viewport")
                .walkthroughTarget("tama.room")
                .clip(RoundedCornerShape(8.dp))
                .background(TamaLight)
                .border(4.dp, TamaDark, RoundedCornerShape(8.dp))
        ) {
            val currentPet = pet
            if (currentPet != null) {
                if (showMap) {
                    // Show map view
                    TamaMapView(
                        cityName = cityName,
                        locations = cityLocations,
                        currentLocation = currentLocation ?: cityLocations.firstOrNull(),
                        discoveredLocationIds = currentPet.discoveredLocationIds,
                        onLocationClick = { loc -> selectedLocation = loc }
                    )
                } else {
                    // Show pet view
                    TamaPetDisplay(
                        pet = currentPet,
                        currentAction = displayAction,
                        locationTypeName = currentLocation?.type?.name?.lowercase(),
                        homeRoomId = currentPet.homeRoomId,
                        sleepyFairyReminder = sleepyFairyReminder,
                        activeStudySession = activeStudySession,
                        currentTime = currentTime,
                        onQuestBoard = if (currentLocation?.type == LocationType.PARK) {
                            {
                                scope.launch {
                                    questBoard = gameEngine.getParkQuestBoard(currentTime)
                                    showQuestBoardDialog = true
                                }
                            }
                        } else null,
                        onMarketBoard = if (currentLocation?.type == LocationType.PARK) {
                            {
                                scope.launch {
                                    marketBoard = gameEngine.getParkMarketBoard(currentTime)
                                    showMarketBoardDialog = true
                                }
                            }
                        } else null
                    )
                }
            } else {
                // No pet yet
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    TamaEmojiIcon("🥚", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.tama_no_pet_yet),
                        fontFamily = FontFamily.Monospace,
                        color = TamaDark
                    )
                }
            }
        }

        // Stats display
        if (pet != null) {
            TamaStatsBar(pet = pet!!)
        }

        // Control buttons - location-aware
        TamaControls(
            pet = pet,
            isSleeping = pet?.isSleeping == true,
            isBusy = actionCooldown,
            currentLocationId = currentLocation?.type?.name?.lowercase()
                ?: currentLocation?.name?.lowercase()
                ?: "home",
            currentTime = currentTime,
            onFeed = {
                showFeedDialog = true
            },
            onClean = { performAction { gameEngine.clean() } },
            onPlay = { performAction { gameEngine.play() } },
            onSleepOrWake = {
                if (pet?.isSleeping == true) {
                    val sleepStartTime = pet?.sleepStartTime
                    val petId = pet?.id
                    scope.launch {
                        gameEngine.wakeUp()
                        dreamAlbumAwaitingRevealId = gameEngine.pet.value?.pendingDreamAlbumId
                        if (dreamAlbumAwaitingRevealId == null && petId != null && sleepStartTime != null) {
                            artworkAwaitingRevealId = gameEngine.getLatestSleepDreamArtwork(petId, sleepStartTime)?.id
                        }
                        Toast.makeText(context, resources.getString(R.string.tama_woke_up, pet?.name ?: ""), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (actionCooldown) return@TamaControls
                    actionCooldown = true
                    scope.launch {
                        val result = gameEngine.goToBed()
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        if (result.success) {
                            currentAction = result.action
                            kotlinx.coroutines.delay(actionDisplayDuration(result.action))
                            currentAction = null
                        }
                        actionCooldown = false
                    }
                }
            },
            onGoHome = {
                cityLocations.find { it.type == LocationType.HOME }?.let { homeLocation ->
                    performAction { gameEngine.travelTo(homeLocation) }
                }
            },
            onWork = { showWorkDialog = true },
            onTrain = { showTrainingDialog = true },
            onChange = { showAlchemistDialog = true },
            onStudy = { showStudyDialog = true },
            onRelax = {
                scope.launch {
                    val result = gameEngine.startActivity(ActivityType.RELAXING)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onQuestBoard = {
                scope.launch {
                    questBoard = gameEngine.getParkQuestBoard(currentTime)
                    showQuestBoardDialog = true
                }
            },
            onMarketBoard = {
                scope.launch {
                    marketBoard = gameEngine.getParkMarketBoard(currentTime)
                    showMarketBoardDialog = true
                }
            },
            onDebugDeepDream = {
                scope.launch {
                    val result = gameEngine.triggerDeepDreamDebug()
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        currentAction = result.action
                        kotlinx.coroutines.delay(actionDisplayDuration(result.action))
                        currentAction = null
                    }
                }
            },
            onFarm = { navController.navigate(Screen.Farm.route) },
            onBarn = { navController.navigate(Screen.Barn.route) },
            onCoop = { navController.navigate(Screen.Coop.route) },
            onStore = { navController.navigate(Screen.Store.route) },
            onHeal = { showHospitalDialog = true },
            onStopActivity = {
                scope.launch {
                    val returningFromStudy = pet?.currentActivity == ActivityType.STUDYING
                    val result = gameEngine.stopActivity()
                    if (result.success && returningFromStudy) {
                        artworkAwaitingRevealId = queuedPaintingArtworkId ?: pet?.let { currentPet ->
                            gameEngine.getLatestArtwork(currentPet.id)
                                ?.takeIf {
                                    it.kind == TamaArtworkKind.PAINTING.name && it.sourceActivity == "studying"
                                }
                                ?.id
                        }
                    }
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onBuy = { showShopDialog = true },
            onChat = onChat,
            onDungeon = { navController.navigate(Screen.Dungeon.route) },
            onAdventureGate = requestAdventureGateEntry,
            onNightArena = { navController.navigate(Screen.NightArena.route) },
            onInventory = { showInventoryDialog = true },
            onChecklist = { showQuestChecklistDialog = true },
            onArcade = { navController.navigate(Screen.Arcade.route) },
            onFreezeToggle = {
                if (actionCooldown) return@TamaControls
                actionCooldown = true
                scope.launch {
                    val result = if (pet?.cycleFrozen == true) {
                        gameEngine.unfreezeCycle()
                    } else {
                        gameEngine.freezeCycle()
                    }
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    actionCooldown = false
                }
            },
            onMenu = { showMenu = true }
        )

        // Event log
        TamaEventLog(events = events.take(5))
        }
    }

    // Name dialog for new pet
    if (showNameDialog) {
        NewPetDialog(
            onConfirm = { name, speciesLine ->
                scope.launch {
                    try {
                        gameEngine.createPet(name, speciesLine.id)
                        Toast.makeText(context, resources.getString(R.string.tama_welcome_new, name), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, resources.getString(R.string.tama_hatch_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false }
        )
    }

    // Menu dialog
    if (showMenu) {
        TamaMenuDialog(
            onDismiss = { showMenu = false },
            onStatus = {
                showMenu = false
                showStatusDialog = true
            },
            onSettings = {
                showMenu = false
                showSettingsDialog = true
            },
            onGallery = {
                showMenu = false
                navController.navigate(Screen.TamaGallery.route)
            },
            onStore = {
                showMenu = false
                navController.navigate(Screen.Store.route)
            },
            onExport = {
                val petName = pet?.name ?: "tama"
                val fileName = "tama_${petName}.zip"
                exportLauncher.launch(fileName)
                showMenu = false
            },
            onImport = {
                showMenu = false
                importLauncher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*"))
            },
            onReset = {
                showMenu = false
                showResetDialog = true
            }
        )
    }

    // Reset Confirmations
    if (showResetDialog) {
        ResetConfirmationDialog(
            title = stringResource(R.string.tama_danger_zone),
            message = stringResource(R.string.tama_reset_warning, pet?.name ?: ""),
            onConfirm = {
                showResetDialog = false
                showSecondResetDialog = true
            },
            onDismiss = { showResetDialog = false }
        )
    }

    if (showSecondResetDialog) {
        ResetConfirmationDialog(
            title = stringResource(R.string.tama_final_warning),
            message = stringResource(R.string.tama_reset_final_msg, pet?.name ?: ""),
            onConfirm = {
                scope.launch {
                    gameEngine.resetPet()
                    Toast.makeText(context, resources.getString(R.string.tama_deleted), Toast.LENGTH_SHORT).show()
                }
                showSecondResetDialog = false
            },
            onDismiss = { showSecondResetDialog = false }
        )
    }

    // Status Dialog
    if (showStatusDialog && pet != null) {
        PetStatusDialog(
            pet = pet!!,
            onDismiss = { showStatusDialog = false }
        )
    }

    if (showSettingsDialog) {
        TamaSettingsDialog(
            navController = navController,
            settingsRepo = settingsRepo,
            onDismiss = { showSettingsDialog = false }
        )
    }

    artworkDialog?.let { artwork ->
        TamaArtworkRevealDialog(
            artwork = artwork,
            onOpenGallery = {
                if (queuedPaintingArtworkId == artwork.id) queuedPaintingArtworkId = null
                artworkDialog = null
                navController.navigate(Screen.TamaGallery.route)
            },
            onDismiss = {
                if (queuedPaintingArtworkId == artwork.id) queuedPaintingArtworkId = null
                artworkDialog = null
            }
        )
    }

    // Location details dialog
    if (selectedLocation != null && pet != null) {
        val loc = selectedLocation!!
        val isDiscovered = pet!!.discoveredLocationIds.contains(loc.id) || loc.type == LocationType.HOME
        val isHere = currentLocation?.id == loc.id || (currentLocation == null && loc.x == 0 && loc.y == 0)
        val travelCost = if (isHere) 0 else gameEngine.previewTravelEnergyCost(currentLocation, loc)

        TamaPopupDialog(
            title = if (isDiscovered) loc.type.localizedName(context) else stringResource(R.string.tama_unknown_place),
            backgroundAsset = when (loc.type) {
                LocationType.HOME -> "tama/backgrounds/bedroom.png"
                LocationType.SHOP -> "tama/backgrounds/shop.png"
                LocationType.SCHOOL -> "tama/backgrounds/classroom.png"
                LocationType.WORKPLACE -> "tama/backgrounds/workplace.png"
                LocationType.BOXING_RING -> "tama/backgrounds/boxing_ring.png"
                LocationType.PARK -> "tama/backgrounds/park.png"
                LocationType.HOSPITAL -> "tama/backgrounds/hospital.png"
                LocationType.ARCADE -> "tama/backgrounds/arcade_location.png"
                LocationType.ALCHEMIST -> "tama/backgrounds/alchemist.png"
                LocationType.FARM -> "tama/backgrounds/farm.png"
                LocationType.DUNGEON -> "tama/backgrounds/dungeon.png"
                LocationType.ADVENTURE_GATE -> "tama/backgrounds/adventure_gate.png"
                else -> "tama/backgrounds/principal_room.png"
            },
            compact = true,
            onDismissRequest = { selectedLocation = null },
            bodyContent = {
                if (isDiscovered) {
                    Text(
                        loc.type.localizedDescription(context),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = TamaDark
                    )
                } else {
                    Text(
                        stringResource(R.string.tama_unknown_warning),
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (isHere) {
                    Text(stringResource(R.string.tama_you_are_here), color = Color(0xFF4CAF50), fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(8.dp))

                    when (loc.type) {
                        LocationType.HOME -> {
                            Text(stringResource(R.string.tama_rest_home), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        LocationType.SHOP -> {
                            Text(stringResource(R.string.tama_items_available), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(stringResource(R.string.tama_apple_price), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(stringResource(R.string.tama_bread_price), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(stringResource(R.string.tama_cake_price), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LocationType.SCHOOL -> {
                            Text(stringResource(R.string.tama_study_gain), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(stringResource(R.string.tama_current_edu, pet!!.educationLevel.toInt()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LocationType.WORKPLACE -> {
                            Text(stringResource(R.string.tama_avail_jobs), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            TamaWorkCatalog.jobs.take(3).forEach { job ->
                                Text(
                                    resources.getString(
                                        R.string.tama_work_job_summary,
                                        resources.getString(job.titleRes),
                                        job.requiredEducation,
                                        job.hourlyPay.toInt()
                                    ),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        LocationType.BOXING_RING -> {
                            Text(stringResource(R.string.tama_training_gain), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(stringResource(R.string.tama_current_exercise, pet!!.exerciseLevel.toInt()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LocationType.PARK -> {
                            Text(stringResource(R.string.tama_park_relax), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(stringResource(R.string.tama_quest_board_desc), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LocationType.HOSPITAL -> {
                            Text(stringResource(R.string.tama_hospital_heal), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        LocationType.ARCADE -> {
                            Text(stringResource(R.string.tama_arcade_location_hint), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        LocationType.ALCHEMIST -> {
                            Text(stringResource(R.string.tama_alchemist_location_hint), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        LocationType.ADVENTURE_GATE -> {
                            Text(stringResource(R.string.adventure_gate_location_hint), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        else -> {}
                    }
                } else {
                    Text(
                        stringResource(R.string.tama_travel_cost, travelCost),
                        color = if (pet!!.stats.energy >= travelCost) TamaAccent else Color.Red,
                        fontFamily = FontFamily.Monospace
                    )
                    if (pet!!.stats.energy < travelCost) {
                        Text(stringResource(R.string.tama_not_enough_energy), color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            },
            footerContent = {
                if (!isHere) {
                    TextButton(
                        onClick = {
                            val alreadyDiscovered = pet!!.discoveredLocationIds.contains(loc.id)
                            scope.launch {
                                val result = gameEngine.travelTo(loc)
                                if (result.success) {
                                    if (!alreadyDiscovered) {
                                        Toast.makeText(context, resources.getString(R.string.tama_discovered, loc.name, loc.description), Toast.LENGTH_LONG).show()
                                    }
                                    showMap = false  // Switch to pet view
                                } else {
                                    showResultToast(result)
                                }
                            }
                            selectedLocation = null
                        },
                        enabled = pet!!.stats.energy >= travelCost
                    ) {
                        Text(if (isDiscovered) stringResource(R.string.tama_btn_travel) else stringResource(R.string.tama_btn_explore))
                    }
                } else {
                    when (loc.type) {
                        LocationType.SHOP -> {
                            TextButton(onClick = {
                                if (pet!!.money >= 10) {
                                    scope.launch {
                                        Toast.makeText(context, resources.getString(R.string.tama_bought_apple), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                selectedLocation = null
                            }) { Text(stringResource(R.string.tama_btn_buy_apple)) }
                        }
                        LocationType.SCHOOL -> {
                            TextButton(onClick = {
                                showStudyDialog = true
                                showMap = false
                                selectedLocation = null
                            }) { Text(stringResource(R.string.tama_btn_study)) }
                        }
                        LocationType.WORKPLACE -> {
                            TextButton(onClick = {
                                showWorkDialog = true
                                selectedLocation = null
                                showMap = false
                            }) { Text(stringResource(R.string.tama_btn_work)) }
                        }
                        LocationType.BOXING_RING -> {
                            TextButton(onClick = {
                                showTrainingDialog = true
                                selectedLocation = null
                                showMap = false
                            }) { Text(stringResource(R.string.tama_btn_train)) }
                        }
                        LocationType.ARCADE -> {
                            TextButton(onClick = {
                                selectedLocation = null
                                navController.navigate(Screen.Arcade.route)
                            }) { Text(stringResource(R.string.tama_btn_arcade)) }
                        }
                        LocationType.PARK -> {
                            TextButton(onClick = {
                                scope.launch {
                                    questBoard = gameEngine.getParkQuestBoard(currentTime)
                                    showQuestBoardDialog = true
                                }
                                selectedLocation = null
                                showMap = false
                            }) { Text(stringResource(R.string.tama_btn_quests)) }
                        }
                        LocationType.ALCHEMIST -> {
                            TextButton(onClick = {
                                showAlchemistDialog = true
                                selectedLocation = null
                                showMap = false
                            }) { Text(stringResource(R.string.tama_btn_change)) }
                        }
                        LocationType.HOSPITAL -> {
                            TextButton(onClick = {
                                showHospitalDialog = true
                                selectedLocation = null
                                showMap = false
                            }) { Text(stringResource(R.string.tama_btn_heal)) }
                        }
                        LocationType.ADVENTURE_GATE -> {
                            TextButton(onClick = {
                                selectedLocation = null
                                showMap = false
                                requestAdventureGateEntry()
                            }) { Text(stringResource(R.string.tama_btn_adventure_gate)) }
                        }
                        else -> {
                            TextButton(onClick = { selectedLocation = null }) { Text(stringResource(R.string.action_ok)) }
                        }
                    }
                    TextButton(onClick = { selectedLocation = null }) { Text(stringResource(R.string.action_close)) }
                }
            }
        )
    }

    if (showStudyDialog && pet != null) {
        StudySetupDialog(
            labels = studyLabels,
            sessions = studySessions,
            onStartNormal = { selectedIds, newNames ->
                scope.launch {
                    val result = gameEngine.startNormalStudySession(selectedIds, newNames)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        showStudyDialog = false
                        queuePaintingIfEnabled()
                    }
                }
            },
            onStartPomodoro = { selectedIds, newNames, settings ->
                scope.launch {
                    val result = gameEngine.startPomodoroStudySession(selectedIds, newNames, settings)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        showStudyDialog = false
                        queuePaintingIfEnabled()
                    }
                }
            },
            onDismiss = { showStudyDialog = false }
        )
    }

    // Feeding dialog with food selection
    if (showFeedDialog && pet != null) {
        FeedingDialog(
            pet = pet!!,
            onFeed = { foodType, hungerGain, happinessGain ->
                performAction { gameEngine.feedWithFood(foodType, hungerGain, happinessGain) }
            },
            onUsePotion = { potionId ->
                performAction { gameEngine.usePotion(potionId) }
            },
            onUseAdventureGateSupply = { supplyId ->
                performAction { gameEngine.useAdventureGateSupply(supplyId) }
            },
            onDismiss = { showFeedDialog = false }
        )
    }

    // Shop dialog for buying items
    if (showShopDialog && pet != null) {
        ShopDialog(
            pet = pet!!,
            onBuy = { item, cost ->
                scope.launch {
                    val result = gameEngine.buyItem(item, 1, cost)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showShopDialog = false }
        )
    }

    if (showInventoryDialog && pet != null) {
        TamaInventoryDialog(
            pet = pet!!,
            onUseRoom = { roomId ->
                scope.launch {
                    val result = gameEngine.setHomeRoom(roomId)
                    showResultToast(result)
                    if (result.success) {
                        showInventoryDialog = false
                    }
                }
            },
            onPlaceDecor = { decorId -> pendingDecorationId = decorId },
            onRemoveDecor = { slot ->
                scope.launch {
                    val result = gameEngine.removeDecoration(slot)
                    showResultToast(result)
                }
            },
            onDismiss = { showInventoryDialog = false }
        )
    }

    if (showQuestChecklistDialog && pet != null) {
        TamaQuestChecklistDialog(
            pet = pet!!,
            checklist = questChecklist,
            onAddItem = { itemId ->
                scope.launch {
                    val result = gameEngine.addQuestChecklistItem(itemId)
                    showResultToast(result)
                }
            },
            onUpdateItem = { itemId, quantity, checked ->
                scope.launch {
                    val result = gameEngine.updateQuestChecklistItem(itemId, quantity, checked)
                    showResultToast(result)
                }
            },
            onDeleteItem = { itemId ->
                scope.launch {
                    val result = gameEngine.deleteQuestChecklistItem(itemId)
                    showResultToast(result)
                }
            },
            onClearChecked = {
                scope.launch {
                    val result = gameEngine.clearCheckedQuestChecklistItems()
                    showResultToast(result)
                }
            },
            onDismiss = { showQuestChecklistDialog = false }
        )
    }

    if (showWorkDialog && pet != null) {
        TamaWorkDialog(
            pet = pet!!,
            onDismiss = { showWorkDialog = false },
            onStartJob = { jobId ->
                scope.launch {
                    val result = gameEngine.startWork(jobId)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        showWorkDialog = false
                    }
                }
            }
        )
    }

    if (showTrainingDialog && pet != null) {
        TamaTrainingDialog(
            pet = pet!!,
            onDismiss = { showTrainingDialog = false },
            onStartTier = { tierId ->
                scope.launch {
                    val result = gameEngine.startTraining(tierId)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        showTrainingDialog = false
                    }
                }
            }
        )
    }

    if (showAdventureGateWarningDialog) {
        AlertDialog(
            onDismissRequest = { showAdventureGateWarningDialog = false },
            title = {
                Text(
                    stringResource(R.string.adventure_gate_placeholder_warning_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.adventure_gate_placeholder_warning_body),
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAdventureGateWarningDialog = false
                        navController.navigate(Screen.AdventureGate.route)
                    }
                ) {
                    Text(stringResource(R.string.adventure_gate_placeholder_warning_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdventureGateWarningDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showAlchemistDialog && pet != null) {
        AlchemistDialog(
            pet = pet!!,
            onBuy = { item, cost ->
                scope.launch {
                    val result = gameEngine.buyItem(item, 1, cost)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onBrew = { ingredients -> gameEngine.brewAdventureGatePotion(ingredients) },
            onDismiss = { showAlchemistDialog = false }
        )
    }

    if (showHospitalDialog && pet != null) {
        HospitalDialog(
            pet = pet!!,
            onBuy = { item, cost ->
                scope.launch {
                    val result = gameEngine.buyItem(item, 1, cost)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showHospitalDialog = false }
        )
    }

    if (showQuestBoardDialog && pet != null) {
        ParkQuestBoardDialog(
            pet = pet!!,
            board = questBoard,
            checklist = questChecklist,
            currentTime = currentTime,
            onRefresh = {
                scope.launch {
                    questBoard = gameEngine.getParkQuestBoard(currentTime)
                }
            },
            onAccept = { questId ->
                scope.launch {
                    val result = gameEngine.acceptParkQuest(questId, currentTime)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    questBoard = gameEngine.getParkQuestBoard(currentTime)
                }
            },
            onFinish = { questId ->
                scope.launch {
                    val result = gameEngine.finishParkQuest(questId, currentTime)
                    if (!result.success) {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    } else {
                        questCompletionPresentation = result.presentation
                    }
                    questBoard = gameEngine.getParkQuestBoard(currentTime)
                }
            },
            onAddToChecklist = { questId ->
                scope.launch {
                    val result = gameEngine.addQuestToChecklist(questId, currentTime)
                    showResultToast(result)
                }
            },
            onDismiss = { showQuestBoardDialog = false }
        )
    }

    if (showMarketBoardDialog) {
        ParkMarketBoardDialog(
            board = marketBoard,
            onDismiss = { showMarketBoardDialog = false }
        )
    }

    questCompletionPresentation?.let { presentation ->
        pet?.let { currentPet ->
            TamaQuestRewardDialog(
                pet = currentPet,
                presentation = presentation,
                onDismiss = { questCompletionPresentation = null }
            )
        }
    }

    pendingDecorationId?.let { decorId ->
        val decor = TamaDecorCatalog.decorById(decorId)
        if (decor != null && pet != null) {
            DecorPlacementDialog(
                decorName = stringResource(decor.titleRes),
                onPlace = { slot ->
                    scope.launch {
                        val result = gameEngine.placeDecoration(decorId, slot)
                        showResultToast(result)
                        if (result.success) {
                            pendingDecorationId = null
                        }
                    }
                },
                onDismiss = { pendingDecorationId = null }
            )
        } else {
            pendingDecorationId = null
        }
    }

    pet?.currentParkEncounter?.takeIf { currentLocation?.type == com.example.llamadroid.tama.data.LocationType.PARK }?.let { encounter ->
        TamaParkEncounterDialog(
            pet = pet!!,
            encounter = encounter,
            onDismissRegular = {
                scope.launch {
                    val result = gameEngine.dismissParkEncounter()
                    showResultToast(result)
                }
            },
            onAcceptRecycler = {
                scope.launch {
                    val result = gameEngine.acceptRecyclerEncounter()
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onDeclineRecycler = {
                scope.launch {
                    val result = gameEngine.declineRecyclerEncounter()
                    showResultToast(result)
                }
            },
            onFinishRecycler = {
                scope.launch {
                    val result = gameEngine.finishRecyclerEncounter()
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onAcceptSeller = {
                scope.launch {
                    val result = gameEngine.acceptSellerEncounter()
                    showResultToast(result)
                }
            },
            onDeclineSeller = {
                scope.launch {
                    val result = gameEngine.declineSellerEncounter()
                    showResultToast(result)
                }
            },
            onFinishSeller = {
                scope.launch {
                    val result = gameEngine.finishSellerEncounter()
                    showResultToast(result)
                }
            },
            onSellCrop = { item, quantity ->
                gameEngine.sellToParkSeller(item, quantity)
            },
            marketQuotes = marketBoard?.quotes.orEmpty()
        )
    }

    dreamAlbumDialog?.let { album ->
        TamaDreamAlbumRevealDialog(
            album = album,
            onOpenGallery = {
                scope.launch { gameEngine.clearPendingDreamAlbum(album.albumId) }
                dreamAlbumDialog = null
                navController.navigate(Screen.TamaGallery.route)
            },
            onDismiss = {
                scope.launch { gameEngine.clearPendingDreamAlbum(album.albumId) }
                dreamAlbumDialog = null
            }
        )
    }
}

@Composable
private fun TamaParkEncounterDialog(
    pet: TamaPet,
    encounter: TamaParkEncounter,
    onDismissRegular: () -> Unit,
    onAcceptRecycler: () -> Unit,
    onDeclineRecycler: () -> Unit,
    onFinishRecycler: () -> Unit,
    onAcceptSeller: () -> Unit,
    onDeclineSeller: () -> Unit,
    onFinishSeller: () -> Unit,
    onSellCrop: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult,
    marketQuotes: List<TamaMarketQuote> = emptyList()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val npc = remember(encounter.npcId) { TamaParkSocialCatalog.npcById(encounter.npcId) }
    val sellableItems = remember(pet.inventory) {
        pet.inventory.filter { item ->
            item.quantity > 0 &&
                FarmTradeItemCatalog.isTradeItem(item.id)
        }
    }
    val marketMode = encounter.type == TamaParkEncounterType.SELLER && encounter.phase == TamaParkEncounterPhase.SELLER_MARKET
    val marketQuoteByItemId = remember(marketQuotes) { marketQuotes.associateBy { it.itemId } }
    val backgroundAsset = if (marketMode) {
        "file:///android_asset/$PARK_MARKET_BACKGROUND_ASSET"
    } else {
        "file:///android_asset/tama/backgrounds/park.png"
    }
    val giftLabel = remember(encounter.giftItemId, locale) {
        encounter.giftItemId
            ?.takeIf { encounter.type == TamaParkEncounterType.REGULAR && encounter.phase == TamaParkEncounterPhase.INTRO }
            ?.takeIf { it.startsWith("seed_") }
            ?.let { seedDisplayText(it.removePrefix("seed_")).resolve(locale) }
    }
    val speechText = remember(encounter, npc, locale) {
        when {
            encounter.type == TamaParkEncounterType.RECYCLER && encounter.phase == TamaParkEncounterPhase.CLEANUP ->
                resources.getString(R.string.tama_park_recycler_cleanup_body, pet.name)
            marketMode ->
                resources.getString(R.string.tama_park_seller_market_body)
            else -> TamaParkSocialCatalog.localizedLine(context, encounter)
        }
    }

    TamaPopupDialog(
        title = npc?.name?.resolve(locale) ?: stringResource(R.string.tama_park_unknown_friend),
        backgroundAsset = if (marketMode) "tama/backgrounds/street_market.png" else "tama/backgrounds/park.png",
        onDismissRequest = {
            if (encounter.type == TamaParkEncounterType.REGULAR) onDismissRegular()
        },
        bodyContent = {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = TamaBackground)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = backgroundAsset,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (marketMode) 0.42f else 0.28f))
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (marketMode) 220.dp else 240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(TamaDark.copy(alpha = 0.18f))
                    ) {
                        TamaPetSprite(
                            pet = pet,
                            action = if (encounter.type == TamaParkEncounterType.RECYCLER && encounter.phase == TamaParkEncounterPhase.CLEANUP) "poop_cleaning" else "idle",
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = (-68).dp, y = (-6).dp)
                        )
                        if (encounter.type == TamaParkEncounterType.RECYCLER && encounter.phase == TamaParkEncounterPhase.CLEANUP) {
                            listOf(
                                Pair((-34).dp, 22.dp),
                                Pair(4.dp, 8.dp),
                                Pair(54.dp, 28.dp),
                                Pair(96.dp, 2.dp),
                                Pair(132.dp, 34.dp)
                            ).forEach { (x, y) ->
                                TamaEmojiIcon(
                                    emoji = "🗑️",
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .offset(x = x, y = y),
                                    fontSize = 20.sp
                                )
                            }
                        }
                        npc?.let {
                            AsyncImage(
                                model = "file:///android_asset/${it.assetPath}",
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(132.dp)
                                    .offset(x = 42.dp, y = (-4).dp),
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.None
                            )
                        }
                        if (giftLabel != null) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset(y = (-10).dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AsyncImage(
                                    model = "file:///android_asset/$PARK_GIFT_PRESENT_ASSET",
                                    contentDescription = null,
                                    modifier = Modifier.size(76.dp),
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.None
                                )
                                Surface(
                                    color = Color(0xFFE9F7D7),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.18f))
                                ) {
                                    Text(
                                        text = stringResource(R.string.tama_park_gift_label, giftLabel),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = TamaDark,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = TamaLight.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (marketMode) 260.dp else 180.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                speechText,
                                color = TamaDark,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            if (marketMode) {
                                if (sellableItems.isEmpty()) {
                                    Text(
                                        stringResource(R.string.tama_park_seller_no_crops),
                                        color = TamaMutedText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        sellableItems.forEach { item ->
                                            val quotePrice = marketQuoteByItemId[item.id]?.currentPrice
                                                ?: FarmTradeItemCatalog.sellPrice(item.id).coerceAtLeast(1)
                                            ParkSellerCropRow(
                                                item = item,
                                                pricePerUnit = quotePrice,
                                                onSell = onSellCrop
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    when {
                        encounter.type == TamaParkEncounterType.REGULAR -> {
                            FilledTonalButton(
                                onClick = onDismissRegular,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.action_continue))
                            }
                        }
                        encounter.type == TamaParkEncounterType.RECYCLER && encounter.phase == TamaParkEncounterPhase.CLEANUP -> {
                            FilledTonalButton(
                                onClick = onFinishRecycler,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.tama_park_recycler_finish))
                            }
                        }
                        encounter.type == TamaParkEncounterType.RECYCLER -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(onClick = onDeclineRecycler, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.action_no))
                                }
                                Button(onClick = onAcceptRecycler, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.action_yes))
                                }
                            }
                        }
                        marketMode -> {
                            FilledTonalButton(
                                onClick = onFinishSeller,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.tama_park_seller_done))
                            }
                        }
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(onClick = onDeclineSeller, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.action_no))
                                }
                                Button(onClick = onAcceptSeller, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.action_yes))
                                }
                            }
                        }
                    }
                }
            }
        }
        },
        footerContent = {}
    )
}

@Composable
private fun TamaQuestRewardDialog(
    pet: TamaPet,
    presentation: TamaQuestCompletionPresentation,
    onDismiss: () -> Unit
) {
    val npc = remember(presentation.npcId) { TamaParkSocialCatalog.npcById(presentation.npcId) }
    TamaPopupDialog(
        title = presentation.npcName,
        backgroundAsset = "tama/backgrounds/park.png",
        onDismissRequest = onDismiss,
        compact = true,
        bodyContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(248.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TamaDark.copy(alpha = 0.10f))
            ) {
                TamaPetSprite(
                    pet = pet,
                    action = "idle",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-68).dp, y = (-8).dp)
                )
                npc?.let {
                    AsyncImage(
                        model = "file:///android_asset/${it.assetPath}",
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(132.dp)
                            .offset(x = 42.dp, y = (-4).dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-8).dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "+${presentation.rewardCoins}",
                        color = Color(0xFF33C759),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )
                    AsyncImage(
                        model = "file:///android_asset/$QUEST_REWARD_MONEY_SACK_ASSET",
                        contentDescription = null,
                        modifier = Modifier.size(84.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = TamaLight.copy(alpha = 0.98f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = presentation.thanksLine,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = TamaDark,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        },
        footerContent = {
            FilledTonalButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_continue))
            }
        }
    )
}

@Composable
private fun ParkSellerCropRow(
    item: InventoryItem,
    pricePerUnit: Int,
    onSell: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quantity by remember(item.id) { mutableIntStateOf(1) }
    val decrementRepeatState = rememberPressAndHoldRepeatState()
    val incrementRepeatState = rememberPressAndHoldRepeatState()
    val assetPath = remember(item.id) { FarmTradeItemCatalog.assetPath(item.id) }
    val ownedLabel = stringResource(R.string.tama_park_seller_owned_chip, item.quantity)
    val eachLabel = stringResource(R.string.tama_park_seller_each_chip, pricePerUnit)
    val totalLabel = stringResource(R.string.tama_park_seller_total_chip, pricePerUnit * quantity)

    fun decrementQuantity(): Boolean {
        if (quantity <= 1) return false
        quantity -= 1
        return true
    }

    fun incrementQuantity(): Boolean {
        if (quantity >= item.quantity) return false
        quantity += 1
        return true
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = assetPath?.let { rememberFarmAssetModel("file:///android_asset/$it") },
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        inventoryItemDisplayName(context, item),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TamaDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        ownedLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TamaMutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        eachLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TamaMutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF7F1DE),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.10f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { decrementRepeatState.handleClick { decrementQuantity() } },
                        enabled = quantity > 1,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 40.dp)
                            .pressAndHoldRepeat(
                                state = decrementRepeatState,
                                enabled = quantity > 1
                            ) { decrementQuantity() },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("-") }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            quantity.toString(),
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TamaDark
                        )
                    }
                    OutlinedButton(
                        onClick = { incrementRepeatState.handleClick { incrementQuantity() } },
                        enabled = quantity < item.quantity,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 40.dp)
                            .pressAndHoldRepeat(
                                state = incrementRepeatState,
                                enabled = quantity < item.quantity
                            ) { incrementQuantity() },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("+") }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = totalLabel,
                        color = Color(0xFF2E7D32),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TamaEmojiIcon("🪙", fontSize = 13.sp)
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        val result = onSell(item, quantity)
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        if (result.success) {
                            quantity = 1
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text(
                    stringResource(R.string.tama_farm_store_sell),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ParkQuestBoardDialog(
    pet: TamaPet,
    board: TamaQuestBoard?,
    checklist: List<TamaQuestChecklistItemEntity>,
    currentTime: Long,
    onRefresh: () -> Unit,
    onAccept: (String) -> Unit,
    onFinish: (String) -> Unit,
    onAddToChecklist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val availableQuests = board?.available.orEmpty()
    val acceptedQuests = board?.accepted.orEmpty()
    TamaPopupDialog(
        title = stringResource(R.string.tama_quest_board_title),
        backgroundAsset = PARK_QUEST_BOARD_BACKGROUND_ASSET,
        onDismissRequest = onDismiss,
        bodyContent = {
            if (board == null) {
                Text(
                    stringResource(R.string.status_loading),
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText
                )
            } else {
                Text(
                    stringResource(
                        R.string.tama_quest_refreshes_in,
                        formatQuestCountdown(board.nextRefreshAt - currentTime)
                    ),
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText,
                    fontSize = 12.sp
                )

                Text(
                    stringResource(R.string.tama_quest_section_available),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                if (availableQuests.isEmpty()) {
                    Text(
                        stringResource(R.string.tama_quest_none_available),
                        fontFamily = FontFamily.Monospace,
                        color = TamaMutedText,
                        fontSize = 12.sp
                    )
                } else {
                    availableQuests.forEach { quest ->
                        ParkQuestCard(
                            quest = quest,
                            isAccepted = false,
                            currentTime = currentTime,
                            inventory = pet.inventory,
                            checklist = checklist,
                            onAddToChecklist = { onAddToChecklist(quest.id) },
                            onPrimaryAction = { onAccept(quest.id) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tama_quest_section_accepted),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (acceptedQuests.isEmpty()) {
                    Text(
                        stringResource(R.string.tama_quest_none_accepted),
                        fontFamily = FontFamily.Monospace,
                        color = TamaMutedText,
                        fontSize = 12.sp
                    )
                } else {
                    acceptedQuests.forEach { quest ->
                        ParkQuestCard(
                            quest = quest,
                            isAccepted = true,
                            currentTime = currentTime,
                            inventory = pet.inventory,
                            checklist = checklist,
                            onAddToChecklist = { onAddToChecklist(quest.id) },
                            onPrimaryAction = { onFinish(quest.id) }
                        )
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.action_refresh))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun ParkQuestCard(
    quest: TamaQuest,
    isAccepted: Boolean,
    currentTime: Long,
    inventory: List<InventoryItem>,
    checklist: List<TamaQuestChecklistItemEntity>,
    onAddToChecklist: () -> Unit,
    onPrimaryAction: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val npc = remember(quest.npcId) { TamaParkSocialCatalog.npcById(quest.npcId) }
    val cropLines = remember(quest.requests, locale) {
        quest.requests.joinToString(separator = "\n") { request ->
            val cropName = FarmTradeItemCatalog.displayName(request.itemId, locale)
            "• ${request.quantity}x $cropName"
        }
    }
    val enabled = if (isAccepted) canFinishQuestFromInventory(inventory, quest) else true
    val inChecklist = remember(checklist, quest.id) {
        checklist.any { item ->
            decodeChecklistQuestIdsForUi(item.sourceQuestIdsJson).contains(quest.id)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.14f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    npc?.let {
                        AsyncImage(
                            model = "file:///android_asset/${it.assetPath}",
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            TamaParkSocialCatalog.localizedName(context, quest.npcId),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TamaDark
                        )
                        Text(
                            stringResource(R.string.tama_quest_reward, quest.rewardCoins),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
                if (isAccepted) {
                    Text(
                        stringResource(
                            R.string.tama_quest_time_left,
                            formatQuestCountdown((quest.expiresAt ?: currentTime) - currentTime)
                        ),
                        modifier = Modifier.widthIn(max = 110.dp),
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TamaMutedText
                    )
                }
            }

            Text(
                quest.summary.resolve(locale),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = TamaDark,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                cropLines,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = TamaMutedText
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onAddToChecklist,
                    enabled = !inChecklist,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (inChecklist) stringResource(R.string.tama_quest_in_checklist) else stringResource(R.string.tama_quest_add_to_checklist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = onPrimaryAction,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isAccepted) stringResource(R.string.tama_quest_finish) else stringResource(R.string.tama_quest_accept),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ParkMarketBoardDialog(
    board: TamaMarketBoard?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    TamaPopupDialog(
        title = stringResource(R.string.tama_market_board_title),
        backgroundAsset = PARK_MARKET_BACKGROUND_ASSET,
        onDismissRequest = onDismiss,
        compact = true,
        bodyContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(
                        R.string.tama_market_board_refresh,
                        board?.nextRefreshAt?.let { formatMarketRefreshTime(context, it) }.orEmpty()
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
                val quotes = board?.quotes.orEmpty()
                if (quotes.isEmpty()) {
                    Text(
                        stringResource(R.string.tama_market_board_empty),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TamaMutedText
                    )
                } else {
                    quotes.forEach { quote ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = TamaLight.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.14f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = rememberFarmAssetModel("file:///android_asset/${quote.assetPath}"),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.None
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        quote.displayText.resolve(locale),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = TamaDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.tama_market_board_range, quote.minPrice, quote.maxPrice),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = TamaMutedText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    stringResource(R.string.tama_market_board_price, quote.currentPrice),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = marketBoardPriceColor(quote),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private fun formatMarketRefreshTime(context: Context, timestamp: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, context.resources.configuration.locales[0])
        .format(java.util.Date(timestamp))

private fun marketBoardPriceColor(quote: TamaMarketQuote): Color {
    if (quote.currentPrice >= quote.maxPrice) return Color(0xFF2E7D32)
    val dangerThreshold = if (quote.itemId.startsWith("crop_")) {
        CropDefinitions.CROPS[quote.itemId.removePrefix("crop_")]?.seedPrice ?: quote.minPrice
    } else {
        quote.minPrice
    }
    return if (quote.currentPrice <= dangerThreshold) {
        Color(0xFFC62828)
    } else {
        Color(0xFFEF6C00)
    }
}

@Composable
private fun TamaQuestChecklistDialog(
    pet: TamaPet,
    checklist: List<TamaQuestChecklistItemEntity>,
    onAddItem: (String) -> Unit,
    onUpdateItem: (String, Int, Boolean) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearChecked: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    var showPicker by remember { mutableStateOf(false) }
    val requestableItems = remember {
        FarmTradeItemCatalog.allDefinitions()
    }

    TamaPopupDialog(
        title = stringResource(R.string.tama_quest_checklist_title),
        backgroundAsset = PARK_QUEST_BOARD_BACKGROUND_ASSET,
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_quest_checklist_desc),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TamaMutedText
            )

            if (checklist.isEmpty()) {
                Text(
                    stringResource(R.string.tama_quest_checklist_empty),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
            } else {
                checklist.forEach { item ->
                    QuestChecklistItemRow(
                        item = item,
                        ownedQuantity = pet.inventory.firstOrNull { it.id == item.itemId }?.quantity ?: 0,
                        locale = locale,
                        onUpdate = onUpdateItem,
                        onDelete = onDeleteItem
                    )
                }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { showPicker = !showPicker },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (showPicker) stringResource(R.string.action_close) else stringResource(R.string.tama_quest_checklist_add_item),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showPicker) {
                requestableItems.forEach { definition ->
                    QuestChecklistPickerRow(
                        definition = definition,
                        locale = locale,
                        onAdd = { onAddItem(definition.inventoryId) }
                    )
                }
            }
        },
        footerContent = {
            TextButton(onClick = onClearChecked) {
                Text(stringResource(R.string.tama_quest_checklist_clear_checked))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun QuestChecklistItemRow(
    item: TamaQuestChecklistItemEntity,
    ownedQuantity: Int,
    locale: java.util.Locale,
    onUpdate: (String, Int, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    val displayName = remember(item.itemId, locale) {
        FarmTradeItemCatalog.displayName(item.itemId, locale)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InventoryMiniIcon(
                    itemId = item.itemId,
                    fallbackEmoji = ItemType.CROP.emoji,
                    size = 42.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TamaDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.tama_quest_checklist_owned_needed, ownedQuantity, item.quantity),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TamaMutedText
                    )
                }
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { checked -> onUpdate(item.itemId, item.quantity, checked) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onUpdate(item.itemId, item.quantity - 1, item.checked) },
                    enabled = item.quantity > 1,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("-")
                }
                Text(
                    stringResource(R.string.tama_quest_checklist_needed_count, item.quantity),
                    modifier = Modifier.weight(1.4f),
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TamaDark
                )
                OutlinedButton(
                    onClick = { onUpdate(item.itemId, item.quantity + 1, false) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+")
                }
                IconButton(onClick = { onDelete(item.itemId) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = Color(0xFFB71C1C)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestChecklistPickerRow(
    definition: FarmTradeItemDefinition,
    locale: java.util.Locale,
    onAdd: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = TamaLight.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InventoryMiniIcon(
                itemId = definition.inventoryId,
                fallbackEmoji = ItemType.CROP.emoji,
                size = 38.dp
            )
            Text(
                definition.displayText.resolve(locale),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TamaDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onAdd) {
                Text(stringResource(R.string.tama_quest_checklist_add_one))
            }
        }
    }
}

private fun canFinishQuestFromInventory(inventory: List<InventoryItem>, quest: TamaQuest): Boolean {
    return quest.requests.all { request ->
        val quantity = inventory.firstOrNull { it.id == request.itemId }?.quantity ?: 0
        quantity >= request.quantity
    }
}

private fun decodeChecklistQuestIdsForUi(sourceQuestIdsJson: String): Set<String> {
    return runCatching {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(sourceQuestIdsJson).toSet()
    }.getOrDefault(emptySet())
}

private fun formatQuestCountdown(remainingMs: Long): String {
    val safeRemaining = remainingMs.coerceAtLeast(0L)
    val totalSeconds = safeRemaining / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

// Food items data
data class FoodItem(
    val id: String,
    val emoji: String,
    val name: String,
    val hungerGain: Int,
    val happinessGain: Int,
    val cost: Int?,  // null = infinite/free
    val isShopItem: Boolean = false
)

private data class TamaDreamAlbumPreview(
    val albumId: String,
    val story: String,
    val dreamDate: String?,
    val artworks: List<TamaArtworkEntity>
)

private data class TamaDreamSlide(
    val title: String,
    val body: String,
    val artwork: TamaArtworkEntity? = null
)

@Composable
fun ShopDialog(
    pet: TamaPet,
    onBuy: (InventoryItem, Int) -> Unit,
    onDismiss: () -> Unit,
    currentSeason: TamaSeason = TamaSeason.current()
) {
    val context = LocalContext.current
    val shopItems = listOf(
        FoodItem("apple", "🍎", stringResource(R.string.tama_food_apple), 15, 5, 10, true),
        FoodItem("bread", "🍞", stringResource(R.string.tama_food_bread), 25, 3, 15, true),
        FoodItem("cake", "🎂", stringResource(R.string.tama_food_cake), 10, 25, 25, true),
        FoodItem("pizza", "🍕", stringResource(R.string.tama_food_pizza), 30, 10, 30, true),
        FoodItem("burger", "🍔", stringResource(R.string.tama_food_burger), 35, 8, 35, true),
        FoodItem("sushi", "🍣", stringResource(R.string.tama_food_sushi), 20, 15, 40, true),
        FoodItem("donut", "🍩", stringResource(R.string.tama_food_donut), 5, 20, 20, true),
        FoodItem("salad", "🥗", stringResource(R.string.tama_food_salad), 20, 2, 12, true)
    )
    var selectedCategory by rememberSaveable { mutableStateOf<TamaShopCategory?>(null) }
    val roomItems = remember { TamaRoomCatalog.shopRooms() }
    val seasonalRoomItems = remember(currentSeason) { TamaRoomCatalog.seasonalRoomsForSeason(currentSeason) }
    val toyItems = remember { TamaDecorCatalog.shopDecor() }
    val seasonalToyItems = remember(currentSeason) { TamaDecorCatalog.seasonalDecorForSeason(currentSeason) }

    TamaPopupDialog(
        title = stringResource(R.string.tama_shop_title),
        backgroundAsset = "tama/backgrounds/popup_generic.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_money_label, pet.money),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            val category = selectedCategory
            if (category == null) {
                ShopCategoryMenu(
                    currentSeason = currentSeason,
                    seasonalDecorCount = seasonalToyItems.size,
                    seasonalRoomCount = seasonalRoomItems.size,
                    onSelectCategory = { selectedCategory = it }
                )
            } else {
                ShopCategoryHeader(
                    title = stringResource(category.titleRes),
                    onBack = { selectedCategory = null }
                )
                when (category) {
                    TamaShopCategory.FOOD -> ShopFoodList(
                        items = shopItems,
                        pet = pet,
                        onBuy = onBuy
                    )
                    TamaShopCategory.TOYS -> ShopDecorList(
                        decorItems = toyItems,
                        pet = pet,
                        context = context,
                        onBuy = onBuy
                    )
                    TamaShopCategory.ROOMS -> ShopRoomList(
                        roomItems = roomItems,
                        pet = pet,
                        context = context,
                        onBuy = onBuy
                    )
                    TamaShopCategory.SEASONAL_DECOR -> ShopDecorList(
                        decorItems = seasonalToyItems,
                        pet = pet,
                        context = context,
                        onBuy = onBuy
                    )
                    TamaShopCategory.SEASONAL_ROOMS -> ShopRoomList(
                        roomItems = seasonalRoomItems,
                        pet = pet,
                        context = context,
                        onBuy = onBuy
                    )
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private enum class TamaShopCategory(
    val iconAssetPath: String,
    val titleRes: Int
) {
    FOOD("tama/icons/shop_categories/food.webp", R.string.tama_shop_category_food),
    TOYS("tama/icons/shop_categories/toys.webp", R.string.tama_shop_category_toys),
    ROOMS("tama/icons/shop_categories/rooms.webp", R.string.tama_shop_category_rooms),
    SEASONAL_DECOR("tama/icons/shop_categories/seasonal_decor.webp", R.string.tama_shop_category_seasonal_decor),
    SEASONAL_ROOMS("tama/icons/shop_categories/seasonal_rooms.webp", R.string.tama_shop_category_seasonal_rooms)
}

@Composable
private fun ShopCategoryMenu(
    currentSeason: TamaSeason,
    seasonalDecorCount: Int,
    seasonalRoomCount: Int,
    onSelectCategory: (TamaShopCategory) -> Unit
) {
    ShopCategoryRow(
        category = TamaShopCategory.FOOD,
        summary = stringResource(R.string.tama_shop_category_food_desc),
        onClick = { onSelectCategory(TamaShopCategory.FOOD) }
    )
    ShopCategoryRow(
        category = TamaShopCategory.TOYS,
        summary = stringResource(R.string.tama_shop_category_toys_desc),
        onClick = { onSelectCategory(TamaShopCategory.TOYS) }
    )
    ShopCategoryRow(
        category = TamaShopCategory.ROOMS,
        summary = stringResource(R.string.tama_shop_category_rooms_desc),
        onClick = { onSelectCategory(TamaShopCategory.ROOMS) }
    )
    ShopCategoryRow(
        category = TamaShopCategory.SEASONAL_DECOR,
        summary = stringResource(
            R.string.tama_shop_category_seasonal_summary,
            stringResource(currentSeason.titleRes()),
            seasonalDecorCount
        ),
        onClick = { onSelectCategory(TamaShopCategory.SEASONAL_DECOR) }
    )
    ShopCategoryRow(
        category = TamaShopCategory.SEASONAL_ROOMS,
        summary = stringResource(
            R.string.tama_shop_category_seasonal_summary,
            stringResource(currentSeason.titleRes()),
            seasonalRoomCount
        ),
        onClick = { onSelectCategory(TamaShopCategory.SEASONAL_ROOMS) }
    )
}

@Composable
private fun ShopCategoryRow(
    category: TamaShopCategory,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TamaLight.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TamaDark.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/${category.iconAssetPath}",
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                stringResource(category.titleRes),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Visible
            )
            Text(
                summary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TamaMutedText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = TamaDark
        )
    }
}

@Composable
private fun ShopCategoryHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.tama_shop_back_to_categories))
        }
        Text(
            title,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TamaDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ShopFoodList(
    items: List<FoodItem>,
    pet: TamaPet,
    onBuy: (InventoryItem, Int) -> Unit
) {
    ShopSectionTitle(stringResource(R.string.tama_shop_category_food))
    items.forEach { item ->
        val cost = item.cost ?: 0
        val canAfford = pet.money >= cost
        val inventoryItem = item.toInventoryItem()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (canAfford) TamaLight.copy(alpha = 0.35f) else Color.Gray.copy(alpha = 0.2f))
                .clickable(enabled = canAfford) { onBuy(inventoryItem, cost) }
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TamaEmojiIcon(item.emoji, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp)
                ) {
                    Text(
                        item.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "+${item.hungerGain} +${item.happinessGain}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TamaMutedText
                    )
                }
            }
            Text(
                stringResource(R.string.tama_shop_price_coins, cost),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (canAfford) TamaAccent else Color.Red,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShopDecorList(
    decorItems: List<TamaDecorDefinition>,
    pet: TamaPet,
    context: Context,
    onBuy: (InventoryItem, Int) -> Unit
) {
    decorItems.forEach { decor ->
        val inventoryItem = TamaDecorCatalog.decorInventoryItem(context, decor.id) ?: return@forEach
        val alreadyOwned = pet.inventory.any { it.id.equals(decor.id, ignoreCase = true) } ||
            pet.leftDecorationId.equals(decor.id, ignoreCase = true) ||
            pet.rightDecorationId.equals(decor.id, ignoreCase = true)
        DecorShopRow(
            decor = decor,
            canBuy = pet.money >= decor.price && !alreadyOwned,
            owned = alreadyOwned,
            onBuy = { onBuy(inventoryItem, decor.price) }
        )
    }
}

@Composable
private fun ShopRoomList(
    roomItems: List<TamaRoomDefinition>,
    pet: TamaPet,
    context: Context,
    onBuy: (InventoryItem, Int) -> Unit
) {
    roomItems.forEach { room ->
        val inventoryItem = TamaRoomCatalog.roomInventoryItem(context, room.id) ?: return@forEach
        val alreadyOwned = pet.homeRoomId.equals(room.id, ignoreCase = true) ||
            pet.inventory.any { it.id.equals(room.id, ignoreCase = true) }
        RoomShopRow(
            room = room,
            price = room.price,
            canBuy = pet.money >= room.price && !alreadyOwned,
            owned = alreadyOwned,
            onBuy = { onBuy(inventoryItem, room.price) }
        )
    }
}

private fun TamaSeason.titleRes(): Int = when (this) {
    TamaSeason.SPRING -> R.string.tama_season_spring
    TamaSeason.SUMMER -> R.string.tama_season_summer
    TamaSeason.AUTUMN -> R.string.tama_season_autumn
    TamaSeason.WINTER -> R.string.tama_season_winter
}

@Composable
private fun AlchemistDialog(
    pet: TamaPet,
    onBuy: (InventoryItem, Int) -> Unit,
    onBrew: suspend (List<String>) -> TamaGameEngine.ActionResult,
    onDismiss: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var selectedIngredients by remember { mutableStateOf<List<String>>(emptyList()) }
    var brewResult by remember { mutableStateOf<TamaGameEngine.ActionResult?>(null) }
    var pendingPotion by remember { mutableStateOf<TamaPotionDefinition?>(null) }
    val scope = rememberCoroutineScope()
    TamaPopupDialog(
        title = stringResource(R.string.tama_alchemist_dialog_title),
        backgroundAsset = "tama/backgrounds/alchemist_dialog.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    R.string.tama_alchemist_tab_shop,
                    R.string.tama_alchemist_tab_kitchen
                ).forEachIndexed { index, labelRes ->
                    Button(
                        onClick = { selectedTab = index },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == index) TamaDark else TamaLight
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            color = if (selectedTab == index) TamaLight else TamaDark,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (selectedTab == 0) {
                AlchemistPotionShopTab(
                    pet = pet,
                    onRequestBuy = { pendingPotion = it }
                )
            } else {
                AlchemistPotionKitchenTab(
                    pet = pet,
                    selectedIngredients = selectedIngredients,
                    onAddIngredient = { selectedIngredients = selectedIngredients + it },
                    onRemoveIngredient = { index ->
                        selectedIngredients = selectedIngredients.filterIndexed { itemIndex, _ -> itemIndex != index }
                    },
                    onClearIngredients = { selectedIngredients = emptyList() },
                    onBrew = {
                        scope.launch {
                            val result = onBrew(selectedIngredients)
                            brewResult = result
                            if (result.success) {
                                selectedIngredients = emptyList()
                            }
                        }
                    }
                )
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
    brewResult?.let { result ->
        AlertDialog(
            onDismissRequest = { brewResult = null },
            title = {
                Text(
                    stringResource(if (result.action == "alchemy_success") R.string.tama_alchemist_kitchen_success_title else R.string.tama_alchemist_kitchen_failure_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = "file:///android_asset/tama/npcs/alchemist_keeper.png",
                        contentDescription = null,
                        modifier = Modifier.size(70.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                    Text(result.message, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { brewResult = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
    pendingPotion?.let { potion ->
        val context = LocalContext.current
        val potionName = stringResource(potion.titleRes)
        AlertDialog(
            onDismissRequest = { pendingPotion = null },
            title = {
                Text(
                    stringResource(R.string.tama_alchemist_buy_confirm_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.tama_alchemist_buy_confirm_body, potionName, potion.price),
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onBuy(potion.toInventoryItem(context), potion.price)
                    pendingPotion = null
                }) {
                    Text(stringResource(R.string.tama_farm_store_buy))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPotion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun AlchemistPotionShopTab(
    pet: TamaPet,
    onRequestBuy: (TamaPotionDefinition) -> Unit
) {
    val potions = remember { TamaPotionCatalog.byVendor(TamaPotionVendor.ALCHEMIST) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.tama_alchemist_dialog_body),
            fontFamily = FontFamily.Monospace,
            color = TamaDark,
            fontSize = 12.sp,
            lineHeight = 15.sp
        )
        Text(
            stringResource(R.string.tama_money_label, pet.money),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TamaDark
        )
        potions.forEach { potion ->
            AlchemistPotionRow(pet = pet, potion = potion, onRequestBuy = onRequestBuy)
        }
    }
}

@Composable
private fun AlchemistPotionRow(
    pet: TamaPet,
    potion: TamaPotionDefinition,
    onRequestBuy: (TamaPotionDefinition) -> Unit
) {
    val canAfford = pet.money >= potion.price
    val redundant = when (potion.kind) {
        TamaPotionKind.STAGE -> pet.stage == potion.targetStage
        TamaPotionKind.SPECIES -> pet.species.equals(potion.targetSpecies?.id, ignoreCase = true)
        TamaPotionKind.GROWTH_LOCK -> pet.growthLocked
        TamaPotionKind.GROWTH_UNLOCK -> !pet.growthLocked
        TamaPotionKind.HEALING -> false
    }
    val enabled = canAfford && !redundant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) TamaLight.copy(alpha = 0.86f) else Color.Gray.copy(alpha = 0.28f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(R.string.tama_shop_price_coins, potion.price),
            modifier = Modifier.align(Alignment.End),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (canAfford) TamaDark else Color.Red,
            fontSize = 11.sp,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(
                model = "file:///android_asset/${potion.assetPath}",
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(potion.titleRes),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TamaDark,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(potion.descriptionRes),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = TamaMutedText
                )
                if (redundant) {
                    Text(
                        text = when (potion.kind) {
                            TamaPotionKind.STAGE -> stringResource(R.string.tama_potion_stage_already_current)
                            TamaPotionKind.SPECIES -> stringResource(R.string.tama_potion_species_already_current)
                            TamaPotionKind.GROWTH_LOCK -> stringResource(R.string.tama_potion_growth_lock_already)
                            TamaPotionKind.GROWTH_UNLOCK -> stringResource(R.string.tama_potion_growth_unlock_already)
                            TamaPotionKind.HEALING -> ""
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TamaMutedText
                    )
                }
            }
        }
        Button(
            onClick = { onRequestBuy(potion) },
            enabled = enabled,
            modifier = Modifier.align(Alignment.End),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(stringResource(R.string.tama_farm_store_buy), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlchemistPotionKitchenTab(
    pet: TamaPet,
    selectedIngredients: List<String>,
    onAddIngredient: (String) -> Unit,
    onRemoveIngredient: (Int) -> Unit,
    onClearIngredients: () -> Unit,
    onBrew: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val cropItems = pet.inventory
        .filter { it.id.startsWith("crop_") && FarmTradeItemCatalog.isTradeItem(it.id) && it.quantity > 0 }
        .sortedBy { FarmTradeItemCatalog.displayName(it.id, locale) }
    val selectedCounts = selectedIngredients.groupingBy { it }.eachCount()
    val ownedRecipes = pet.inventory
        .filter { it.type == ItemType.RECIPE }
        .mapNotNull { AdventureGateCatalog.recipe(it.id) }
        .sortedBy { AdventureGateCatalog.supply(it.supplyId)?.nameRes ?: 0 }
    var expandedRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.tama_alchemist_kitchen_body),
            fontFamily = FontFamily.Monospace,
            color = TamaDark,
            lineHeight = 16.sp
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(TamaDark.copy(alpha = 0.12f))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/tama/potions/alchemy_cauldron.png",
                contentDescription = null,
                modifier = Modifier.size(116.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        }
        Text(stringResource(R.string.tama_alchemist_kitchen_selected), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TamaDark)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedIngredients.isEmpty()) {
                Text(stringResource(R.string.tama_alchemist_kitchen_selected_empty), fontFamily = FontFamily.Monospace, color = TamaMutedText, fontSize = 12.sp)
            }
            selectedIngredients.forEachIndexed { index, itemId ->
                AssistChip(
                    onClick = { onRemoveIngredient(index) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = TamaLight,
                        labelColor = TamaDark,
                        leadingIconContentColor = TamaDark,
                        disabledContainerColor = TamaLight.copy(alpha = 0.72f),
                        disabledLabelColor = TamaDark
                    ),
                    label = {
                        Text(
                            FarmTradeItemCatalog.displayName(itemId, locale),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        InventoryMiniIcon(itemId = itemId, fallbackEmoji = "🌾", size = 22.dp)
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBrew, enabled = selectedIngredients.isNotEmpty()) {
                Text(stringResource(R.string.tama_alchemist_kitchen_brew), fontFamily = FontFamily.Monospace)
            }
            OutlinedButton(onClick = onClearIngredients, enabled = selectedIngredients.isNotEmpty()) {
                Text(stringResource(R.string.tama_alchemist_kitchen_clear), fontFamily = FontFamily.Monospace)
            }
        }
        Text(stringResource(R.string.tama_alchemist_kitchen_ingredients), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TamaDark)
        if (cropItems.isEmpty()) {
            Text(stringResource(R.string.tama_alchemist_kitchen_no_crops), fontFamily = FontFamily.Monospace, color = TamaMutedText)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cropItems.forEach { item ->
                    val selected = selectedCounts[item.id] ?: 0
                    FilterChip(
                        selected = selected > 0,
                        onClick = { if (selected < item.quantity) onAddIngredient(item.id) },
                        enabled = selected < item.quantity,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TamaLight,
                            labelColor = TamaDark,
                            iconColor = TamaDark,
                            selectedContainerColor = Color(0xFFFFF4B8),
                            selectedLabelColor = TamaDark,
                            selectedLeadingIconColor = TamaDark,
                            disabledContainerColor = TamaLight.copy(alpha = 0.72f),
                            disabledLabelColor = TamaDark.copy(alpha = 0.72f),
                            disabledLeadingIconColor = TamaDark.copy(alpha = 0.72f)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = selected < item.quantity,
                            selected = selected > 0,
                            borderColor = TamaDark.copy(alpha = 0.58f),
                            selectedBorderColor = TamaDark
                        ),
                        label = {
                            Text(
                                "${FarmTradeItemCatalog.displayName(item.id, locale)} ${item.quantity - selected}/${item.quantity}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = { InventoryMiniIcon(itemId = item.id, fallbackEmoji = "🌾", size = 22.dp) }
                    )
                }
            }
        }
        Text(stringResource(R.string.tama_alchemist_kitchen_recipes), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TamaDark)
        if (ownedRecipes.isEmpty()) {
            Text(stringResource(R.string.tama_alchemist_kitchen_no_recipes), fontFamily = FontFamily.Monospace, color = TamaMutedText, lineHeight = 16.sp)
        } else {
            ownedRecipes.forEach { recipe ->
                val supply = AdventureGateCatalog.supply(recipe.supplyId)
                val expanded = expandedRecipeId == recipe.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TamaLight.copy(alpha = 0.82f))
                        .clickable { expandedRecipeId = if (expanded) null else recipe.id }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsyncImage(
                            model = "file:///android_asset/tama/potions/recipe_scroll.png",
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                        Text(
                            stringResource(R.string.adventure_gate_recipe_title, supply?.let { stringResource(it.nameRes) } ?: recipe.supplyId),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TamaDark,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (expanded) {
                        Text(
                            recipeIngredientText(context, recipe),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = TamaMutedText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HospitalDialog(
    pet: TamaPet,
    onBuy: (InventoryItem, Int) -> Unit,
    onDismiss: () -> Unit
) {
    PotionMerchantDialog(
        pet = pet,
        vendor = TamaPotionVendor.HOSPITAL,
        title = stringResource(R.string.tama_hospital_dialog_title),
        body = stringResource(R.string.tama_hospital_dialog_body),
        backgroundAsset = "tama/backgrounds/hospital_dialog.png",
        onBuy = onBuy,
        onDismiss = onDismiss
    )
}

@Composable
private fun PotionMerchantDialog(
    pet: TamaPet,
    vendor: TamaPotionVendor,
    title: String,
    body: String,
    backgroundAsset: String,
    onBuy: (InventoryItem, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val potions = remember(vendor) { TamaPotionCatalog.byVendor(vendor) }
    TamaPopupDialog(
        title = title,
        backgroundAsset = backgroundAsset,
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(body, fontFamily = FontFamily.Monospace, color = TamaDark)
            Text(
                stringResource(R.string.tama_money_label, pet.money),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            potions.forEach { potion ->
                val canAfford = pet.money >= potion.price
                val redundant = when (potion.kind) {
                    TamaPotionKind.STAGE -> pet.stage == potion.targetStage
                    TamaPotionKind.SPECIES -> pet.species.equals(potion.targetSpecies?.id, ignoreCase = true)
                    TamaPotionKind.GROWTH_LOCK -> pet.growthLocked
                    TamaPotionKind.GROWTH_UNLOCK -> !pet.growthLocked
                    TamaPotionKind.HEALING -> false
                }
                val enabled = canAfford && !redundant
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (enabled) TamaLight.copy(alpha = 0.82f) else Color.Gray.copy(alpha = 0.26f))
                        .clickable(enabled = enabled) {
                            onBuy(potion.toInventoryItem(context), potion.price)
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = "file:///android_asset/${potion.assetPath}",
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                        Column(
                            modifier = Modifier.widthIn(min = 88.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                stringResource(R.string.tama_shop_price_coins, potion.price),
                                fontFamily = FontFamily.Monospace,
                                color = if (enabled) TamaDark else Color.Red,
                                fontSize = 12.sp,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (redundant) {
                                Text(
                                    text = when (potion.kind) {
                                        TamaPotionKind.STAGE -> stringResource(R.string.tama_potion_stage_already_current)
                                        TamaPotionKind.SPECIES -> stringResource(R.string.tama_potion_species_already_current)
                                        TamaPotionKind.GROWTH_LOCK -> stringResource(R.string.tama_potion_growth_lock_already)
                                        TamaPotionKind.GROWTH_UNLOCK -> stringResource(R.string.tama_potion_growth_unlock_already)
                                        TamaPotionKind.HEALING -> ""
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TamaMutedText,
                                    textAlign = TextAlign.End,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(potion.titleRes),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = TamaDark,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(potion.descriptionRes),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = TamaMutedText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private fun FoodItem.toInventoryItem(): InventoryItem {
    return InventoryItem(
        id = id,
        name = name,
        type = ItemType.FOOD,
        quantity = 1
    )
}

private fun recipeIngredientText(context: Context, recipe: com.example.llamadroid.tama.rpg.AdventureGateRecipeDefinition): String {
    val locale = context.resources.configuration.locales[0]
    return recipe.ingredientCounts.entries
        .sortedBy { FarmTradeItemCatalog.displayName(it.key, locale) }
        .joinToString(", ") { (itemId, count) ->
            val name = FarmTradeItemCatalog.displayName(itemId, locale)
            if (count > 1) "${count}x $name" else name
        }
}

private fun List<InventoryItem>.quantityByName(): Map<String, Int> {
    return groupingBy { it.name }.fold(0) { acc, item -> acc + item.quantity }
}

private fun List<InventoryItem>.quantityById(): Map<String, Int> {
    return groupingBy { it.id }.fold(0) { acc, item -> acc + item.quantity }
}

private fun List<InventoryItem>.totalQuantity(): Int {
    return sumOf { it.quantity }
}

@Composable
fun TamaPopupDialog(
    title: String,
    backgroundAsset: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showFooter: Boolean = true,
    bodyContent: @Composable ColumnScope.() -> Unit,
    footerContent: @Composable RowScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints {
            val dialogMaxHeight = if (compact) maxHeight * 0.72f else maxHeight * 0.9f
            val compactBodyMaxHeight = maxHeight * 0.46f
            Card(
                modifier = modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.96f)
                    .heightIn(max = dialogMaxHeight),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.55f))
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = "file:///android_asset/$backgroundAsset",
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.None
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color(0xFF111111).copy(alpha = 0.76f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = TamaDark.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, TamaLight.copy(alpha = 0.14f))
                        ) {
                            Text(
                                title,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TamaLight,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (compact) {
                                        Modifier.heightIn(max = compactBodyMaxHeight)
                                    } else {
                                        Modifier.weight(1f, fill = false)
                                    }
                                ),
                            shape = RoundedCornerShape(18.dp),
                            color = TamaLight.copy(alpha = 0.985f),
                            contentColor = TamaDark,
                            border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.18f))
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides TamaDark,
                                LocalTextStyle provides LocalTextStyle.current.copy(color = TamaDark)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    bodyContent()
                                }
                            }
                        }
                        if (showFooter) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = TamaDark.copy(alpha = 0.55f),
                                contentColor = TamaLight,
                                border = BorderStroke(1.dp, TamaLight.copy(alpha = 0.12f))
                            ) {
                                CompositionLocalProvider(
                                    LocalContentColor provides TamaLight,
                                    LocalTextStyle provides LocalTextStyle.current.copy(color = TamaLight)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        footerContent()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudySetupDialog(
    labels: List<TamaStudyLabelEntity>,
    sessions: List<TamaStudySessionEntity>,
    onStartNormal: (Set<String>, List<String>) -> Unit,
    onStartPomodoro: (Set<String>, List<String>, TamaPomodoroSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var usePomodoro by rememberSaveable { mutableStateOf(false) }
    var selectedLabelIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    val newLabels = remember { mutableStateListOf<String>() }
    var newLabelName by rememberSaveable { mutableStateOf("") }
    var focusMinutes by rememberSaveable { mutableStateOf("25") }
    var shortBreakMinutes by rememberSaveable { mutableStateOf("5") }
    var longBreakMinutes by rememberSaveable { mutableStateOf("15") }
    var rounds by rememberSaveable { mutableStateOf("4") }
    val now = remember { System.currentTimeMillis() }
    val weekStart = remember(now) { startOfCurrentWeekMs(now) }
    val monthStart = remember(now) { startOfCurrentMonthMs(now) }

    TamaPopupDialog(
        title = stringResource(R.string.tama_study_setup_title),
        backgroundAsset = "tama/backgrounds/classroom.png",
        compact = false,
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_study_setup_desc),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TamaMutedText
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StudyModeCard(
                    modifier = Modifier.weight(1f),
                    selected = !usePomodoro,
                    title = stringResource(R.string.tama_study_mode_normal),
                    description = stringResource(R.string.tama_study_mode_normal_desc),
                    onClick = { usePomodoro = false }
                )
                StudyModeCard(
                    modifier = Modifier.weight(1f),
                    selected = usePomodoro,
                    title = stringResource(R.string.tama_study_mode_pomodoro),
                    description = stringResource(R.string.tama_study_mode_pomodoro_desc),
                    onClick = { usePomodoro = true }
                )
            }

            Text(
                stringResource(R.string.tama_study_labels_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            if (labels.isEmpty()) {
                Text(
                    stringResource(R.string.tama_study_no_saved_labels),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(labels, key = { it.id }) { label ->
                        FilterChip(
                            selected = label.id in selectedLabelIds,
                            onClick = {
                                selectedLabelIds = if (label.id in selectedLabelIds) {
                                    selectedLabelIds - label.id
                                } else {
                                    selectedLabelIds + label.id
                                }
                            },
                            label = {
                                Text(
                                    label.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newLabelName,
                    onValueChange = { newLabelName = it.take(48) },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.tama_study_new_label)) },
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = {
                        val cleaned = newLabelName.trim()
                        if (cleaned.isNotBlank() && newLabels.none { it.equals(cleaned, ignoreCase = true) }) {
                            newLabels += cleaned
                            newLabelName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.tama_study_add_label))
                }
            }
            if (newLabels.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(newLabels, key = { it }) { label ->
                        AssistChip(
                            onClick = { newLabels.remove(label) },
                            label = { Text("$label ×", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            if (usePomodoro) {
                Text(
                    stringResource(R.string.tama_study_timer_settings),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                StudyDurationField(
                    value = focusMinutes,
                    onValueChange = { focusMinutes = it },
                    label = stringResource(R.string.tama_study_focus_minutes)
                )
                StudyDurationField(
                    value = shortBreakMinutes,
                    onValueChange = { shortBreakMinutes = it },
                    label = stringResource(R.string.tama_study_short_break_minutes)
                )
                StudyDurationField(
                    value = longBreakMinutes,
                    onValueChange = { longBreakMinutes = it },
                    label = stringResource(R.string.tama_study_long_break_minutes)
                )
                StudyDurationField(
                    value = rounds,
                    onValueChange = { rounds = it },
                    label = stringResource(R.string.tama_study_rounds)
                )
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.tama_study_summaries_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            StudySummaryCard(
                title = stringResource(R.string.tama_study_week_summary),
                sessions = sessions,
                startInclusive = weekStart,
                endExclusive = now + 1L
            )
            StudySummaryCard(
                title = stringResource(R.string.tama_study_month_summary),
                sessions = sessions,
                startInclusive = monthStart,
                endExclusive = now + 1L
            )
        },
        footerContent = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    val pendingLabels = newLabels.toList() + listOf(newLabelName.trim()).filter { it.isNotBlank() }
                    if (usePomodoro) {
                        onStartPomodoro(
                            selectedLabelIds,
                            pendingLabels,
                            TamaPomodoroSettings(
                                focusMinutes = focusMinutes.toIntOrNull() ?: 25,
                                shortBreakMinutes = shortBreakMinutes.toIntOrNull() ?: 5,
                                longBreakMinutes = longBreakMinutes.toIntOrNull() ?: 15,
                                rounds = rounds.toIntOrNull() ?: 4
                            )
                        )
                    } else {
                        onStartNormal(selectedLabelIds, pendingLabels)
                    }
                }
            ) {
                Text(
                    if (usePomodoro) {
                        stringResource(R.string.tama_study_start_pomodoro)
                    } else {
                        stringResource(R.string.tama_study_start_normal)
                    }
                )
            }
        }
    )
}

@Composable
private fun StudyModeCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFFEAF2FF) else Color.White.copy(alpha = 0.82f),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Color(0xFF5B7CE3) else TamaDark.copy(alpha = 0.18f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TamaDark
            )
            Text(
                description,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = TamaMutedText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StudyDurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit).take(3)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun StudySummaryCard(
    title: String,
    sessions: List<TamaStudySessionEntity>,
    startInclusive: Long,
    endExclusive: Long
) {
    val context = LocalContext.current
    val noLabel = stringResource(R.string.tama_study_no_labels_short)
    val stats = remember(sessions, startInclusive, endExclusive, noLabel) {
        buildStudySummaryStats(sessions, startInclusive, endExclusive, noLabel)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.84f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (stats.sessionCount == 0) {
                Text(
                    stringResource(R.string.tama_study_summary_empty),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText
                )
            } else {
                Text(
                    stringResource(
                        R.string.tama_study_summary_totals,
                        formatStudyDurationForUi(context, stats.totalMs),
                        formatStudyDurationForUi(context, stats.focusMs),
                        formatStudyDurationForUi(context, stats.restMs),
                        stats.education.roundToInt(),
                        stats.completedCount,
                        stats.stoppedCount
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TamaMutedText
                )
                Text(
                    stringResource(R.string.tama_study_summary_label_breakdown),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
                stats.labelTotals.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .forEach { (label, totalMs) ->
                        Text(
                            "• $label: ${formatStudyDurationForUi(context, totalMs)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TamaMutedText
                        )
                    }
            }
        }
    }
}

private data class StudySummaryStats(
    val sessionCount: Int,
    val completedCount: Int,
    val stoppedCount: Int,
    val totalMs: Long,
    val focusMs: Long,
    val restMs: Long,
    val education: Float,
    val labelTotals: Map<String, Long>
)

private fun buildStudySummaryStats(
    sessions: List<TamaStudySessionEntity>,
    startInclusive: Long,
    endExclusive: Long,
    noLabel: String
): StudySummaryStats {
    val finished = sessions.filter { session ->
        session.status != TamaStudyStatus.ACTIVE.name &&
            ((session.completedAt ?: session.stoppedAt ?: session.lastUpdatedAt) in startInclusive until endExclusive)
    }
    val labelTotals = linkedMapOf<String, Long>()
    finished.forEach { session ->
        val total = session.focusAccumulatedMs + session.restAccumulatedMs
        val labels = TamaStudySessionSupport.decodeLabelNames(session).ifEmpty { listOf(noLabel) }
        labels.forEach { label ->
            labelTotals[label] = (labelTotals[label] ?: 0L) + total
        }
    }
    return StudySummaryStats(
        sessionCount = finished.size,
        completedCount = finished.count { it.status == TamaStudyStatus.COMPLETED.name },
        stoppedCount = finished.count { it.status == TamaStudyStatus.STOPPED.name },
        totalMs = finished.sumOf { it.focusAccumulatedMs + it.restAccumulatedMs },
        focusMs = finished.sumOf { it.focusAccumulatedMs },
        restMs = finished.sumOf { it.restAccumulatedMs },
        education = finished.sumOf { it.educationAwarded.toDouble() }.toFloat(),
        labelTotals = labelTotals
    )
}

private fun inventoryDisplayName(context: Context, item: InventoryItem): String {
    return inventoryDisplayNameForItemId(context, item.id, item.name)
}

private fun inventoryDisplayNameForItemId(context: Context, itemId: String, fallback: String = itemId): String {
    val locale = context.resources.configuration.locales[0]
    return when {
        itemId == "water" -> context.getString(R.string.tama_item_water)
        itemId == "fertilizer" -> context.getString(R.string.tama_item_fertilizer)
        itemId == "rotten_crop" -> context.getString(R.string.tama_item_rotten_crop)
        itemId.startsWith("seed_") -> seedDisplayText(itemId.removePrefix("seed_")).resolve(locale)
        FarmTradeItemCatalog.isTradeItem(itemId) -> FarmTradeItemCatalog.displayName(itemId, locale)
        TamaPotionCatalog.byId(itemId) != null -> context.getString(checkNotNull(TamaPotionCatalog.byId(itemId)).titleRes)
        AdventureGateCatalog.supply(itemId) != null -> context.getString(checkNotNull(AdventureGateCatalog.supply(itemId)).nameRes)
        AdventureGateCatalog.recipe(itemId) != null -> {
            val recipe = checkNotNull(AdventureGateCatalog.recipe(itemId))
            val supply = AdventureGateCatalog.supply(recipe.supplyId)
            context.getString(R.string.adventure_gate_recipe_title, supply?.let { context.getString(it.nameRes) } ?: recipe.supplyId)
        }
        AdventureGateCatalog.equipment(itemId) != null -> context.getString(checkNotNull(AdventureGateCatalog.equipment(itemId)).nameRes)
        TamaDecorCatalog.decorById(itemId) != null -> context.getString(checkNotNull(TamaDecorCatalog.decorById(itemId)).titleRes)
        TamaRoomCatalog.roomById(itemId) != null -> context.getString(checkNotNull(TamaRoomCatalog.roomById(itemId)).titleRes)
        itemId.contains("watering_can", ignoreCase = true) -> context.getString(R.string.tama_inventory_watering_can)
        itemId.contains("hoe", ignoreCase = true) -> context.getString(R.string.tama_inventory_hoe)
        else -> fallback
    }
}

private fun inventoryAssetPathForItemId(itemId: String): String? {
    return when {
        TamaUiIconCatalog.assetPathForFoodItemId(itemId) != null -> TamaUiIconCatalog.assetPathForFoodItemId(itemId)
        itemId == "water" -> "farm/Others/water.png"
        itemId == "fertilizer" -> "farm/Others/fertilizer.png"
        itemId == "rotten_crop" -> "farm/Others/rotten_crop.png"
        itemId.startsWith("seed_") -> "farm/Crops/seed/${itemId.removePrefix("seed_")}.png"
        FarmTradeItemCatalog.assetPath(itemId) != null -> FarmTradeItemCatalog.assetPath(itemId)
        TamaPotionCatalog.byId(itemId) != null -> TamaPotionCatalog.byId(itemId)?.assetPath
        AdventureGateCatalog.supply(itemId) != null -> AdventureGateCatalog.supply(itemId)?.assetPath
        AdventureGateCatalog.recipe(itemId) != null -> "tama/potions/recipe_scroll.png"
        AdventureGateCatalog.equipment(itemId) != null -> AdventureGateCatalog.equipment(itemId)?.assetPath
        TamaDecorCatalog.decorById(itemId) != null -> TamaDecorCatalog.decorById(itemId)?.assetPath
        TamaRoomCatalog.roomById(itemId) != null -> TamaRoomCatalog.roomById(itemId)?.assetPath
        itemId.contains("watering_can", ignoreCase = true) -> "farm/Others/watering_can.png"
        itemId.contains("hoe", ignoreCase = true) -> "farm/Others/hoe.png"
        else -> null
    }
}

@Composable
private fun InventoryMiniIcon(
    itemId: String,
    fallbackEmoji: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    val assetPath = remember(itemId) { inventoryAssetPathForItemId(itemId) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        if (assetPath != null) {
            AsyncImage(
                model = "file:///android_asset/$assetPath",
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        } else {
            TamaUiIcon(fallbackEmoji, fontSize = 20.sp)
        }
    }
}

@Composable
private fun InventoryListRow(
    item: InventoryItem,
    displayName: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.78f))
            .border(1.dp, TamaDark.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InventoryMiniIcon(
            itemId = item.id,
            fallbackEmoji = item.type.emoji,
            size = 42.dp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TamaDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.tama_inventory_quantity, item.quantity),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaMutedText
            )
        }
    }
}

private fun startOfCurrentWeekMs(now: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    while (calendar.get(Calendar.DAY_OF_WEEK) != calendar.firstDayOfWeek) {
        calendar.add(Calendar.DAY_OF_MONTH, -1)
    }
    return calendar.timeInMillis
}

private fun startOfCurrentMonthMs(now: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun formatStudyDurationForUi(context: Context, durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        context.getString(R.string.tama_study_duration_hours_minutes, hours, minutes)
    } else {
        context.getString(R.string.tama_study_duration_minutes, minutes)
    }
}

@Composable
private fun TamaDialogBackdrop(
    assetPath: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        AsyncImage(
            model = "file:///android_asset/$assetPath",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.48f))
        )
    }
}

@Composable
private fun ShopSectionTitle(title: String) {
    Text(
        title,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp
    )
}

@Composable
private fun RoomShopRow(
    room: TamaRoomDefinition,
    price: Int,
    canBuy: Boolean,
    owned: Boolean,
    onBuy: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (canBuy) TamaLight.copy(alpha = 0.35f) else Color.Gray.copy(alpha = 0.2f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = "file:///android_asset/${room.assetPath}",
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                TamaRoomCatalog.localizedTitle(context, room),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TamaDark,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                price.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (canBuy) TamaAccent else Color.Red
            )
            TextButton(
                onClick = onBuy,
                enabled = canBuy
            ) {
                Text(
                    if (owned) stringResource(R.string.tama_shop_room_owned) else stringResource(R.string.tama_shop_room_buy)
                )
            }
        }
    }
}

@Composable
private fun DecorShopRow(
    decor: TamaDecorDefinition,
    canBuy: Boolean,
    owned: Boolean,
    onBuy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (canBuy) TamaLight.copy(alpha = 0.35f) else Color.Gray.copy(alpha = 0.2f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "file:///android_asset/${decor.assetPath}",
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(decor.titleRes),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.tama_shop_price_coins, decor.price),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (canBuy) TamaAccent else Color.Red
                )
            }
        }
        TextButton(
            onClick = onBuy,
            enabled = canBuy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (owned) stringResource(R.string.tama_shop_toy_owned) else stringResource(R.string.tama_shop_toy_buy)
            )
        }
    }
}

@Composable
fun TamaInventoryDialog(
    pet: TamaPet,
    onUseRoom: (String) -> Unit,
    onPlaceDecor: (String) -> Unit,
    onRemoveDecor: (TamaDecorSlot) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val currentRoom = remember(pet.homeRoomId) {
        TamaRoomCatalog.roomById(pet.homeRoomId) ?: TamaRoomCatalog.roomById(TamaRoomCatalog.PRINCIPAL_ROOM_ID)
    }
    val ownedRooms = remember(pet.inventory, pet.homeRoomId) {
        pet.inventory.filter { TamaRoomCatalog.isRoomId(it.id) }
    }
    val otherItems = remember(pet.inventory, configuration.locales[0]) {
        pet.inventory.filterNot { TamaRoomCatalog.isRoomId(it.id) || TamaDecorCatalog.isDecorId(it.id) }
            .groupBy { it.id }
            .map { (_, items) ->
                val first = items.first()
                first.copy(
                    name = inventoryDisplayName(context, first),
                    quantity = items.sumOf { it.quantity }
                )
            }
            .sortedBy { it.name.lowercase() }
    }
    val ownedDecor = remember(pet.inventory) {
        pet.inventory.filter { TamaDecorCatalog.isDecorId(it.id) }
            .sortedBy { it.name.lowercase() }
    }

    TamaPopupDialog(
        title = stringResource(R.string.tama_inventory_title),
        backgroundAsset = "tama/backgrounds/popup_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_inventory_current_room),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            currentRoom?.let { room ->
                CurrentRoomCard(room)
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.tama_inventory_rooms),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            if (ownedRooms.isEmpty()) {
                Text(
                    stringResource(R.string.tama_inventory_no_rooms),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
            } else {
                ownedRooms.forEach { item ->
                    val room = TamaRoomCatalog.roomById(item.id) ?: return@forEach
                    RoomInventoryRow(
                        room = room,
                        owned = true,
                        current = room.id.equals(pet.homeRoomId, ignoreCase = true),
                        onUse = { onUseRoom(room.id) }
                    )
                }
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.tama_inventory_decor_slots),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            DecorationSlotsCard(
                pet = pet,
                onRemoveDecor = onRemoveDecor
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.tama_inventory_toys),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            if (ownedDecor.isEmpty()) {
                Text(
                    stringResource(R.string.tama_inventory_no_toys),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
            } else {
                ownedDecor.forEach { item ->
                    val decor = TamaDecorCatalog.decorById(item.id) ?: return@forEach
                    DecorInventoryRow(
                        decor = decor,
                        onPlace = { onPlaceDecor(decor.id) }
                    )
                }
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.tama_inventory_other_items),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            if (otherItems.isEmpty()) {
                Text(
                    stringResource(R.string.tama_inventory_no_items),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
            } else {
                otherItems.forEach { item ->
                    InventoryListRow(
                        item = item,
                        displayName = item.name
                    )
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun DecorationSlotsCard(
    pet: TamaPet,
    onRemoveDecor: (TamaDecorSlot) -> Unit
) {
    val leftDecor = TamaDecorCatalog.decorById(pet.leftDecorationId)
    val rightDecor = TamaDecorCatalog.decorById(pet.rightDecorationId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TamaLight.copy(alpha = 0.35f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlotSummaryRow(
            slotLabel = stringResource(R.string.tama_slot_left),
            decorName = leftDecor?.let { stringResource(it.titleRes) } ?: stringResource(R.string.tama_inventory_slot_empty),
            enabled = leftDecor != null,
            onRemove = { onRemoveDecor(TamaDecorSlot.LEFT) }
        )
        SlotSummaryRow(
            slotLabel = stringResource(R.string.tama_slot_right),
            decorName = rightDecor?.let { stringResource(it.titleRes) } ?: stringResource(R.string.tama_inventory_slot_empty),
            enabled = rightDecor != null,
            onRemove = { onRemoveDecor(TamaDecorSlot.RIGHT) }
        )
    }
}

@Composable
private fun SlotSummaryRow(
    slotLabel: String,
    decorName: String,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.tama_inventory_slot_summary, slotLabel, decorName),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = onRemove,
            enabled = enabled
        ) {
            Text(
                stringResource(R.string.tama_inventory_remove_from_room),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DecorInventoryRow(
    decor: TamaDecorDefinition,
    onPlace: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Gray.copy(alpha = 0.16f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "file:///android_asset/${decor.assetPath}",
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Text(
                stringResource(decor.titleRes),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onPlace) {
                Text(
                    stringResource(R.string.tama_inventory_place),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            stringResource(R.string.tama_inventory_toy_hint),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = TamaMutedText,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CurrentRoomCard(room: TamaRoomDefinition) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TamaLight.copy(alpha = 0.35f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = "file:///android_asset/${room.assetPath}",
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
        Text(
            TamaRoomCatalog.localizedTitle(context, room),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TamaDark,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(R.string.tama_inventory_room_active),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TamaMutedText
        )
    }
}

@Composable
private fun RoomInventoryRow(
    room: TamaRoomDefinition,
    owned: Boolean,
    current: Boolean,
    onUse: () -> Unit
) {
    val context = LocalContext.current
    val clickableModifier = if (current) Modifier else Modifier.clickable(enabled = owned) { onUse() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (current) TamaLight.copy(alpha = 0.35f) else Color.Gray.copy(alpha = 0.18f))
            .then(clickableModifier)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = "file:///android_asset/${room.assetPath}",
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    TamaRoomCatalog.localizedTitle(context, room),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TamaDark,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    TamaRoomCatalog.localizedDescription(context, room),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = onUse,
                enabled = owned && !current
            ) {
                Text(
                    if (current) stringResource(R.string.tama_inventory_room_in_use) else stringResource(R.string.tama_inventory_room_use)
                )
            }
        }
    }
}

@Composable
fun FeedingDialog(
    pet: TamaPet,
    onFeed: (String, Int, Int) -> Unit,
    onUsePotion: (String) -> Unit,
    onUseAdventureGateSupply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Free foods always available
    val freeFoods = listOf(
        FoodItem("lettuce", "🥬", stringResource(R.string.tama_food_lettuce), 5, 0, null),
        FoodItem("candy", "🍬", stringResource(R.string.tama_food_candy), 0, 1, null)
    )

    // Foods from inventory
    val allShopFoods = listOf(
        FoodItem("apple", "🍎", stringResource(R.string.tama_food_apple), 15, 5, 10, true),
        FoodItem("bread", "🍞", stringResource(R.string.tama_food_bread), 25, 3, 15, true),
        FoodItem("cake", "🎂", stringResource(R.string.tama_food_cake), 10, 25, 25, true),
        FoodItem("pizza", "🍕", stringResource(R.string.tama_food_pizza), 30, 10, 30, true),
        FoodItem("burger", "🍔", stringResource(R.string.tama_food_burger), 35, 8, 35, true),
        FoodItem("sushi", "🍣", stringResource(R.string.tama_food_sushi), 20, 15, 40, true),
        FoodItem("donut", "🍩", stringResource(R.string.tama_food_donut), 5, 20, 20, true),
        FoodItem("salad", "🥗", stringResource(R.string.tama_food_salad), 20, 2, 12, true)
    )

    // Count items in inventory
    val inventoryCount = pet.inventory.quantityById()
    val ownedFoods = allShopFoods.filter { food ->
        inventoryCount.containsKey(food.id)
    }
    val ownedPotions = remember(pet.inventory) {
        pet.inventory
            .filter { it.type == ItemType.POTION }
            .mapNotNull { item -> TamaPotionCatalog.byId(item.id)?.let { potion -> potion to item.quantity } }
    }
    val ownedAdventureGateSupplies = remember(pet.inventory) {
        pet.inventory
            .filter { it.type == ItemType.POTION || it.type == ItemType.TREASURE }
            .mapNotNull { item -> AdventureGateCatalog.supply(item.id)?.let { supply -> supply to item.quantity } }
            .filter { (supply, _) -> supply.kind != AdventureGateSupplyKind.CLEANSE }
    }

    TamaPopupDialog(
        title = stringResource(R.string.tama_feed_title, pet.name),
        backgroundAsset = "tama/backgrounds/popup_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_hunger_label, pet.stats.hunger.toInt()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(R.string.tama_always_avail),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaMutedText
            )
            freeFoods.forEach { food ->
                FoodItemRow(food, null) {
                    onFeed(food.id, food.hungerGain, food.happinessGain)
                    onDismiss()
                }
            }

            if (ownedFoods.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tama_from_inventory),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText
                )
                ownedFoods.forEach { food ->
                    val count = inventoryCount[food.id] ?: 0
                    FoodItemRow(food, count) {
                        onFeed(food.id, food.hungerGain, food.happinessGain)
                        onDismiss()
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tama_visit_shop),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText
                )
            }

            if (ownedPotions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tama_potion_section_title),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText
                )
                ownedPotions.forEach { (potion, count) ->
                    PotionItemRow(
                        potion = potion,
                        count = count,
                        onClick = {
                            onUsePotion(potion.id)
                            onDismiss()
                        }
                    )
                }
            }
            if (ownedAdventureGateSupplies.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tama_feed_adventure_gate_supplies),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaMutedText
                )
                ownedAdventureGateSupplies.forEach { (supply, count) ->
                    AdventureGateSupplyFeedRow(
                        title = stringResource(supply.nameRes),
                        effect = stringResource(
                            R.string.tama_feed_adventure_gate_supply_effect,
                            adventureGateSupplyEffectText(supply)
                        ),
                        assetPath = supply.assetPath,
                        count = count,
                        onClick = {
                            onUseAdventureGateSupply(supply.id)
                            onDismiss()
                        }
                    )
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PotionItemRow(
    potion: TamaPotionDefinition,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TamaLight.copy(alpha = 0.35f))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "file:///android_asset/${potion.assetPath}",
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    stringResource(potion.titleRes),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TamaDark,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(potion.descriptionRes),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TamaMutedText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            "x$count",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun AdventureGateSupplyFeedRow(
    title: String,
    effect: String,
    assetPath: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TamaLight.copy(alpha = 0.35f))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "file:///android_asset/$assetPath",
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    title,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TamaDark,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    effect,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TamaMutedText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            "x$count",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun adventureGateSupplyEffectText(supply: AdventureGateSupplyDefinition): String =
    when (supply.kind) {
        AdventureGateSupplyKind.HP -> stringResource(R.string.adventure_gate_shop_restore_hp, supply.amount)
        AdventureGateSupplyKind.MANA -> stringResource(R.string.adventure_gate_shop_restore_mana, supply.amount)
        AdventureGateSupplyKind.CLEANSE -> stringResource(R.string.adventure_gate_shop_cleanse_bad_statuses)
        AdventureGateSupplyKind.SKILL_POINT -> stringResource(R.string.adventure_gate_shop_grants_skill_point)
    }

@Composable
fun FoodItemRow(food: FoodItem, count: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TamaEmojiIcon(food.emoji, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(food.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TamaDark)
                Text(
                    "+${food.hungerGain} +${food.happinessGain}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TamaMutedText
                )
            }
        }
        Text(
            if (count != null) "x$count" else "∞",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = TamaDark
        )
    }
}

@Composable
fun TamaHeader(pet: TamaPet?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TamaDark)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TamaEmojiIcon(TAMA_CHAT_EMOJI, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "TAMA",
                color = TamaLight,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp
            )
        }

        if (pet != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TamaEmojiIcon(TAMA_MONEY_EMOJI, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = pet.money.toString(),
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = pet.stage.displayName,
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun TamaPetDisplay(
    pet: TamaPet,
    currentAction: String? = null,
    locationTypeName: String? = null,
    homeRoomId: String? = null,
    sleepyFairyReminder: TamaSleepyFairyReminder? = null,
    activeStudySession: TamaStudySessionEntity? = null,
    currentTime: Long = System.currentTimeMillis(),
    onQuestBoard: (() -> Unit)? = null,
    onMarketBoard: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val speciesName = remember(pet.species, pet.genetics.bodyStyle) {
        speciesDisplayName(context, pet.species, pet.genetics.bodyStyle)
    }
    val workBackdropAssetPath = remember(pet.currentWorkJobId, currentAction) {
        when {
            currentAction?.equals("working", ignoreCase = true) == true ->
                TamaWorkCatalog.jobById(pet.currentWorkJobId)?.backgroundAssetPath
            currentAction?.equals("training", ignoreCase = true) == true ->
                TamaTrainingCatalog.tierById(pet.currentWorkJobId)?.backgroundAssetPath
            else -> null
        }
    }
    val backdropLocationType = when {
        pet.isSleeping -> "bedroom"
        currentAction == "cleaning" && pet.poopCreatedAt == null -> "bathroom"
        else -> locationTypeName
    }
    val showHomeDecor = (locationTypeName == null || locationTypeName.contains("home", ignoreCase = true)) &&
        !pet.isSleeping &&
        currentAction != "cleaning" &&
        currentAction != "poop_cleaning"
    val leftDecor = remember(pet.leftDecorationId) { TamaDecorCatalog.decorById(pet.leftDecorationId) }
    val rightDecor = remember(pet.rightDecorationId) { TamaDecorCatalog.decorById(pet.rightDecorationId) }
    val ambientNpc = remember(pet.currentAmbientNpc?.npcId) {
        TamaAmbientNpcCatalog.byId(pet.currentAmbientNpc?.npcId)
    }
    val ambientNpcLine = remember(pet.currentAmbientNpc?.npcId, pet.currentAmbientNpc?.lineIndex) {
        pet.currentAmbientNpc?.let { TamaAmbientNpcCatalog.resolveLine(context, it) }.orEmpty()
    }
    val sleepyFairyLine = remember(sleepyFairyReminder?.lineIndex) {
        sleepyFairyReminder?.let {
            TamaSleepyFairyCatalog.resolveLine(context, it)
        }.orEmpty()
    }
    val showAmbientNpcLine = pet.currentAmbientNpc != null &&
        ambientNpc != null &&
        (currentTime - pet.currentAmbientNpc.shownAt) <= 5_000L
    val petSceneOffsetX = if (ambientNpc != null) (-68).dp else 0.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Background scene - larger pixels to cover more area
        Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
            TamaLocationBackdrop(
                locationType = backdropLocationType,
                homeRoomId = homeRoomId,
                assetPathOverride = workBackdropAssetPath
            )
        }

        // Activity timer overlay at top-right
        if (pet.currentActivity != com.example.llamadroid.tama.data.ActivityType.NONE && pet.activityStartTime != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0xFF0B0B0B).copy(alpha = 0.97f), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFFF2F2DB).copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                val activityLabel = when (pet.currentActivity) {
                    com.example.llamadroid.tama.data.ActivityType.WORKING -> stringResource(R.string.tama_action_work)
                    com.example.llamadroid.tama.data.ActivityType.STUDYING -> stringResource(R.string.tama_action_study)
                    com.example.llamadroid.tama.data.ActivityType.TRAINING -> stringResource(R.string.tama_action_train)
                    com.example.llamadroid.tama.data.ActivityType.RELAXING -> stringResource(R.string.tama_action_relax)
                    else -> stringResource(R.string.tama_action_busy)
                }
                val durationMs = currentTime - pet.activityStartTime
                val durationSec = (durationMs / 1000).toInt()
                val displayHours = durationSec / 3600
                val displayMin = (durationSec % 3600) / 60
                val displaySecRem = durationSec % 60

                val timeText = if (displayHours > 0) {
                    String.format(
                        java.util.Locale.getDefault(),
                        "%d:%02d:%02d",
                        displayHours,
                        displayMin,
                        displaySecRem
                    )
                } else {
                    String.format(java.util.Locale.getDefault(), "%02d:%02d", displayMin, displaySecRem)
                }

                val activePomodoro = activeStudySession
                    ?.takeIf { it.mode == TamaStudyMode.POMODORO.name && it.status == TamaStudyStatus.ACTIVE.name }
                Text(
                    if (activePomodoro != null) {
                        resources.getString(
                            R.string.tama_study_round_status,
                            activePomodoro.currentRound,
                            activePomodoro.roundsPlanned.coerceAtLeast(1)
                        )
                    } else {
                        "$activityLabel • $timeText"
                    },
                    color = Color(0xFFFFF9E3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val hoursPassed = durationMs / (1000 * 60 * 60f)
                val gainText = if (activePomodoro != null) {
                    resources.getString(
                        R.string.tama_study_timer_remaining,
                        TamaStudySessionSupport.localizedPhase(context, activePomodoro.currentPhase),
                        formatStudyDurationForUi(context, TamaStudySessionSupport.currentPhaseRemainingMs(activePomodoro, currentTime))
                    )
                } else when (pet.currentActivity) {
                    com.example.llamadroid.tama.data.ActivityType.WORKING -> {
                        val hourlyPay = TamaWorkCatalog.jobById(pet.currentWorkJobId)?.hourlyPay ?: 4
                        resources.getString(
                            R.string.tama_activity_gain_money,
                            (hoursPassed * hourlyPay).toInt()
                        )
                    }
                    com.example.llamadroid.tama.data.ActivityType.STUDYING -> resources.getString(
                        R.string.tama_activity_gain_education,
                        (hoursPassed * 5).toInt()
                    )
                    com.example.llamadroid.tama.data.ActivityType.TRAINING -> {
                        val hourlyPay = TamaTrainingCatalog.tierById(pet.currentWorkJobId)?.hourlyPay ?: 8
                        resources.getString(
                            R.string.tama_activity_gain_training,
                            (hoursPassed * TAMA_TRAINING_EXERCISE_PER_HOUR).toInt(),
                            (hoursPassed * TAMA_TRAINING_HAPPINESS_PER_HOUR).toInt(),
                            (hoursPassed * hourlyPay).toInt()
                        )
                    }
                    com.example.llamadroid.tama.data.ActivityType.RELAXING -> resources.getString(
                        R.string.tama_activity_gain_happiness,
                        (hoursPassed * 40).toInt(),
                        (hoursPassed * TAMA_RELAX_INTROSPECTION_PER_HOUR).toInt()
                    )
                    else -> ""
                }
                Text(
                    gainText,
                    color = Color(0xFFEFEFCF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )

                val maxDurationMs = 8 * 60 * 60 * 1000L
                LinearProgressIndicator(
                    progress = {
                        activePomodoro?.let { TamaStudySessionSupport.currentPhaseProgress(it, currentTime) }
                            ?: (durationMs.toFloat() / maxDurationMs).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.width(92.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFFFFD966),
                    trackColor = Color.White.copy(alpha = 0.28f)
                )
            }
        }

        // Pet sprite area centered
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val actionTransition = rememberInfiniteTransition(label = "tama_action_overlay")
            val propFloatY by actionTransition.animateFloat(
                initialValue = -5f,
                targetValue = 7f,
                animationSpec = infiniteRepeatable(animation = tween(1100, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "prop_float_y"
            )
            val playBallX by actionTransition.animateFloat(
                initialValue = -66f,
                targetValue = 66f,
                animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                label = "play_ball_x"
            )
            val playBallY by actionTransition.animateFloat(
                initialValue = -22f,
                targetValue = 16f,
                animationSpec = infiniteRepeatable(animation = tween(520, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "play_ball_y"
            )
            val showerWaterOffset by actionTransition.animateFloat(
                initialValue = -10f,
                targetValue = 18f,
                animationSpec = infiniteRepeatable(animation = tween(480, easing = LinearEasing), repeatMode = RepeatMode.Restart),
                label = "shower_water_y"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-2).dp, y = (-34).dp),
                    color = TamaLight.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.35f))
                ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TamaEmojiIcon(if (pet.isEffectivelyMad()) "😠" else pet.mood.emoji, fontSize = 18.sp)
                    Text(
                        text = pet.name,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TamaDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showHomeDecor) {
                    leftDecor?.let { decor ->
                        TamaActionAsset(
                            assetPath = decor.assetPath,
                            modifier = Modifier.offset(x = (-112).dp, y = 56.dp),
                            size = 128.dp
                        )
                    }
                    rightDecor?.let { decor ->
                        TamaActionAsset(
                            assetPath = decor.assetPath,
                            modifier = Modifier.offset(x = 112.dp, y = 56.dp),
                            size = 128.dp
                        )
                    }
                }

                TamaPetSprite(
                    pet = pet,
                    action = currentAction ?: "idle",
                    modifier = Modifier.offset(x = petSceneOffsetX, y = (-8).dp)
                )

                ambientNpc?.let { npc ->
                    TamaActionAsset(
                        assetPath = npc.assetPath,
                        modifier = Modifier.offset(x = 112.dp, y = 8.dp),
                        size = 132.dp
                    )
                }

                if (sleepyFairyReminder != null && sleepyFairyLine.isNotBlank()) {
                    SleepyFairyOverlay(
                        fairyAssetPath = TamaSleepyFairyCatalog.definition.assetPath,
                        panelBackgroundAssetPath = TamaSleepyFairyCatalog.definition.panelBackgroundAssetPath,
                        line = sleepyFairyLine,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 16.dp)
                    )
                }

                if (locationTypeName?.contains("park", ignoreCase = true) == true && onQuestBoard != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-112).dp, y = (-10).dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onQuestBoard() },
                        color = Color(0xFF805126).copy(alpha = 0.96f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(3.dp, Color(0xFFE7D099))
                    ) {
                        Column(
                            modifier = Modifier
                                .width(86.dp)
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TamaEmojiIcon("📜", fontSize = 22.sp)
                            Text(
                                text = stringResource(R.string.tama_quest_board_title),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                textAlign = TextAlign.Center,
                                color = Color(0xFFFFF7DF),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (locationTypeName?.contains("park", ignoreCase = true) == true && onMarketBoard != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-112).dp, y = 88.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onMarketBoard() },
                        color = Color(0xFF5D6B2F).copy(alpha = 0.96f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(3.dp, Color(0xFFE3D99C))
                    ) {
                        Column(
                            modifier = Modifier
                                .width(86.dp)
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TamaEmojiIcon("💹", fontSize = 22.sp)
                            Text(
                                text = stringResource(R.string.tama_market_board_title),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                textAlign = TextAlign.Center,
                                color = Color(0xFFFFF7DF),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (showAmbientNpcLine && ambientNpcLine.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-8).dp)
                            .widthIn(max = 164.dp),
                        color = TamaLight.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = ambientNpcLine,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TamaDark
                        )
                    }
                }

                if (pet.poopCount > 0 && (locationTypeName == null || locationTypeName.contains("home", ignoreCase = true))) {
                    val poopPositions = listOf(
                        Pair((-38).dp, 110.dp),
                        Pair(0.dp, 110.dp),
                        Pair((-38).dp, 146.dp),
                        Pair(0.dp, 146.dp)
                    )
                    repeat(pet.poopCount.coerceAtMost(4)) { index ->
                        val (x, y) = poopPositions[index]
                        TamaActionAsset(
                            assetPath = POOP_PROP_ASSET,
                            modifier = Modifier.offset(x = x, y = y),
                            size = 44.dp
                        )
                    }
                }

                when (currentAction) {
                    "playing" -> {
                        TamaActionAsset(
                            assetPath = PLAY_ACTION_ICON_ASSET,
                            modifier = Modifier.offset(x = playBallX.dp, y = playBallY.dp),
                            size = 92.dp
                        )
                    }
                    "sunbathing" -> {
                        TamaEmojiIcon(TAMA_WAKE_EMOJI, modifier = Modifier.offset(x = 52.dp, y = (-48).dp), fontSize = 28.sp)
                        TamaEmojiIcon(TAMA_WAKE_EMOJI, modifier = Modifier.offset(x = (-34).dp, y = (-18).dp), fontSize = 14.sp)
                        TamaEmojiIcon(TAMA_WAKE_EMOJI, modifier = Modifier.offset(x = 26.dp, y = (-10).dp), fontSize = 12.sp)
                    }
                    "studying" -> {
                        TamaActionAsset(
                            assetPath = STUDY_ACTION_ICON_ASSET,
                            modifier = Modifier.offset(x = 72.dp, y = (28 + propFloatY).dp),
                            size = 96.dp
                        )
                    }
                    "working" -> {
                        TamaActionAsset(
                            assetPath = WORK_ACTION_ICON_ASSET,
                            modifier = Modifier.offset(x = (-76).dp, y = (30 + propFloatY).dp),
                            size = 100.dp
                        )
                    }
                    "training" -> {
                        TamaEmojiIcon("🥊", modifier = Modifier.offset(x = (-70).dp, y = (26 + propFloatY).dp), fontSize = 34.sp)
                        TamaEmojiIcon("💥", modifier = Modifier.offset(x = 70.dp, y = (-18 + propFloatY).dp), fontSize = 26.sp)
                    }
                    "cleaning" -> {
                        TamaActionAsset(
                            assetPath = SHOWER_ACTION_ICON_ASSET,
                            modifier = Modifier.offset(x = 76.dp, y = (-46).dp),
                            size = 108.dp
                        )
                        repeat(4) { index ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = (30 + (index * 14)).dp,
                                        y = (-8 + showerWaterOffset + (index * 5)).dp
                                    )
                                    .size(width = 5.dp, height = 20.dp)
                                    .background(Color(0xFF8ED6FF), RoundedCornerShape(999.dp))
                            )
                        }
                    }
                    "poop_cleaning" -> {
                        TamaActionAsset(
                            assetPath = MOP_ACTION_ICON_ASSET,
                            modifier = Modifier.offset(x = 68.dp, y = 40.dp),
                            size = 104.dp
                        )
                    }
                    "transforming" -> {
                        TamaActionAsset(
                            assetPath = TRANSFORMATION_CLOUD_ASSET,
                            modifier = Modifier.offset(x = petSceneOffsetX, y = (-2).dp),
                            size = 216.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepyFairyOverlay(
    fairyAssetPath: String,
    panelBackgroundAssetPath: String,
    line: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .wrapContentSize()
            .widthIn(max = 318.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.28f))
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
        ) {
            AsyncImage(
                model = "file:///android_asset/$panelBackgroundAssetPath",
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xFFF9F2FF).copy(alpha = 0.88f))
            )
            Row(
                modifier = Modifier
                    .widthIn(max = 318.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsyncImage(
                    model = "file:///android_asset/$fairyAssetPath",
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .padding(2.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color.White.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFB89BDB).copy(alpha = 0.55f))
                ) {
                    Text(
                        text = line,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = TamaDark,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun TamaStatsBar(pet: TamaPet) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatIndicator(TAMA_HUNGER_EMOJI, stringResource(R.string.tama_status_hunger), pet.stats.hunger, Modifier.weight(1f))
        StatIndicator(TAMA_HAPPINESS_EMOJI, stringResource(R.string.tama_status_happy), pet.stats.happiness, Modifier.weight(1f))
        StatIndicator(TAMA_HEALTH_EMOJI, stringResource(R.string.tama_status_health), pet.stats.health, Modifier.weight(1f))
        StatIndicator(TAMA_ENERGY_EMOJI, stringResource(R.string.tama_status_energy), pet.stats.energy, Modifier.weight(1f))
        StatIndicator(TAMA_HYGIENE_EMOJI, stringResource(R.string.tama_status_hygiene), pet.stats.hygiene, Modifier.weight(1f))
    }
}

@Composable
private fun StatIndicator(icon: String, label: String, value: Float, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.soft_studio_tama_stat_value, label, value.toInt())
    Column(
        modifier = modifier.heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TamaEmojiIcon(icon, modifier = Modifier.size(28.dp), fontSize = 18.sp)
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (value < 20f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
fun TamaControls(
    pet: TamaPet?,
    isSleeping: Boolean,
    isBusy: Boolean = false,
    currentLocationId: String = "home",
    currentTime: Long = System.currentTimeMillis(),
    onFeed: () -> Unit,
    onClean: () -> Unit,
    onPlay: () -> Unit,
    onSleepOrWake: () -> Unit,
    onGoHome: () -> Unit = {},
    onWork: () -> Unit = {},
    onTrain: () -> Unit = {},
    onChange: () -> Unit = {},
    onStudy: () -> Unit = {},
    onRelax: () -> Unit = {},
    onQuestBoard: () -> Unit = {},
    onMarketBoard: () -> Unit = {},
    onDebugDeepDream: () -> Unit = {},
    onStopActivity: () -> Unit = {},
    onBuy: () -> Unit = {},
    onFarm: () -> Unit = {},
    onBarn: () -> Unit = {},
    onCoop: () -> Unit = {},
    onStore: () -> Unit = {},
    onHeal: () -> Unit = {},
    onChat: () -> Unit = {},
    onDungeon: () -> Unit = {},
    onAdventureGate: () -> Unit = {},
    onNightArena: () -> Unit = {},
    onInventory: () -> Unit = {},
    onChecklist: () -> Unit = {},
    onArcade: () -> Unit = {},
    onFreezeToggle: () -> Unit = {},
    onMenu: () -> Unit
) {
    val walkthroughTargets = LocalWalkthroughTargets.current
    val isFrozen = pet?.cycleFrozen == true
    val canAct = pet != null && pet.stage != GrowthStage.EGG && !isSleeping && !isBusy && !isFrozen
    val isDoingActivity = pet?.currentActivity != ActivityType.NONE
    val isHome = currentLocationId == "home" || currentLocationId.startsWith("home")
    val nightArenaAvailable = isSleeping && NightArenaGenerator.isActiveWindow(currentTime)
    val actions = buildList {
        if (isFrozen) {
            add(TamaControlConfig(icon = "❄", label = stringResource(R.string.tama_btn_unfreeze), enabled = !isBusy, onClick = onFreezeToggle))
        } else if (pet != null) {
            add(TamaControlConfig(icon = TAMA_INVENTORY_EMOJI, label = stringResource(R.string.tama_btn_inventory), enabled = true, onClick = onInventory))
            add(TamaControlConfig(icon = TAMA_CHECKLIST_EMOJI, label = stringResource(R.string.tama_btn_checklist), enabled = true, onClick = onChecklist))
        }
        if (isFrozen) {
            // Keep only Unfreeze and Menu available while game time is frozen.
        } else if (isDoingActivity) {
            add(TamaControlConfig(icon = TAMA_STOP_EMOJI, label = stringResource(R.string.tama_btn_stop), enabled = !isBusy, onClick = onStopActivity))
            add(TamaControlConfig(icon = TAMA_CHAT_EMOJI, label = stringResource(R.string.tama_btn_chat), enabled = !isBusy && !isSleeping, onClick = onChat))
        } else if (isHome) {
            if (pet != null) {
                add(TamaControlConfig(icon = "❄", label = stringResource(R.string.tama_btn_freeze), enabled = !isBusy, onClick = onFreezeToggle))
            }
            add(TamaControlConfig(icon = TAMA_FEED_EMOJI, label = stringResource(R.string.tama_btn_feed), enabled = canAct, onClick = onFeed, targetId = "tama.care", eventId = "tama.care"))
            add(TamaControlConfig(icon = "🧽", label = stringResource(R.string.tama_btn_clean), enabled = canAct, onClick = onClean))
            add(TamaControlConfig(icon = "🎾", label = stringResource(R.string.tama_btn_play), enabled = canAct, onClick = onPlay))
            add(TamaControlConfig(icon = TAMA_CHAT_EMOJI, label = stringResource(R.string.tama_btn_chat), enabled = canAct, onClick = onChat))
            add(
                if (isSleeping) {
                    TamaControlConfig(icon = TAMA_WAKE_EMOJI, label = stringResource(R.string.tama_btn_wake), enabled = pet != null && !isBusy, onClick = onSleepOrWake)
                } else {
                    TamaControlConfig(icon = TAMA_SLEEP_EMOJI, label = stringResource(R.string.tama_btn_sleep), enabled = canAct, onClick = onSleepOrWake)
                }
            )
            if (nightArenaAvailable) {
                add(TamaControlConfig(icon = "🌙", label = stringResource(R.string.tama_btn_night_arena), enabled = pet != null && !isBusy, onClick = onNightArena))
            }
            if (isSleeping) {
                add(TamaControlConfig(icon = "✨", label = stringResource(R.string.tama_btn_deep_dream_test), enabled = pet != null && !isBusy, onClick = onDebugDeepDream))
            }
        } else {
            add(TamaControlConfig(icon = LocationType.HOME.emoji, label = stringResource(R.string.tama_btn_home), enabled = canAct, onClick = onGoHome))
            when {
                currentLocationId.contains("shop", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = TAMA_BUY_EMOJI, label = stringResource(R.string.tama_btn_buy), enabled = canAct, onClick = onBuy))
                }
                currentLocationId.contains("arcade", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = TAMA_ARCADE_EMOJI, label = stringResource(R.string.tama_btn_arcade), enabled = canAct, onClick = onArcade))
                }
                currentLocationId.contains("school", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = TAMA_EDUCATION_EMOJI, label = stringResource(R.string.tama_btn_study_short), enabled = canAct && (pet?.stage?.canStudy() == true), onClick = onStudy))
                }
                currentLocationId.contains("office", ignoreCase = true) || currentLocationId.contains("work", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = "💼", label = stringResource(R.string.tama_btn_work_short), enabled = canAct && (pet?.stage?.canWork() == true), onClick = onWork))
                }
                currentLocationId.contains("boxing_ring", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = "🥊", label = stringResource(R.string.tama_btn_train), enabled = canAct && (pet?.stage?.canTrain() == true), onClick = onTrain))
                }
                currentLocationId.contains("park", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = TAMA_RELAX_EMOJI, label = stringResource(R.string.tama_btn_relax), enabled = canAct, onClick = onRelax))
                    add(TamaControlConfig(icon = "📜", label = stringResource(R.string.tama_btn_quests), enabled = canAct, onClick = onQuestBoard))
                    add(TamaControlConfig(icon = "💹", label = stringResource(R.string.tama_btn_market), enabled = canAct, onClick = onMarketBoard))
                }
                currentLocationId.contains("alchemist", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = TAMA_CHANGE_EMOJI, label = stringResource(R.string.tama_btn_change), enabled = canAct, onClick = onChange))
                }
                currentLocationId.contains("farm", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = LocationType.FARM.emoji, label = stringResource(R.string.tama_btn_farm), enabled = canAct, onClick = onFarm))
                    add(TamaControlConfig(icon = "🐄", label = stringResource(R.string.tama_btn_barn), enabled = canAct, onClick = onBarn))
                    add(TamaControlConfig(icon = "🐔", label = stringResource(R.string.tama_btn_coop), enabled = canAct, onClick = onCoop))
                    add(TamaControlConfig(icon = LocationType.SHOP.emoji, label = stringResource(R.string.tama_btn_store), enabled = canAct, onClick = onStore))
                }
                currentLocationId.contains("hospital", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = TAMA_HEAL_EMOJI, label = stringResource(R.string.tama_btn_heal), enabled = canAct, onClick = onHeal))
                }
                currentLocationId.contains("dungeon", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = LocationType.DUNGEON.emoji, label = stringResource(R.string.tama_btn_dungeon), enabled = canAct, onClick = onDungeon))
                }
                currentLocationId.contains("adventure_gate", ignoreCase = true) -> {
                    add(TamaControlConfig(icon = LocationType.ADVENTURE_GATE.emoji, label = stringResource(R.string.tama_btn_adventure_gate), enabled = canAct, onClick = onAdventureGate))
                }
            }
        }
        add(TamaControlConfig(icon = TAMA_MENU_EMOJI, label = stringResource(R.string.tama_btn_menu), enabled = true, onClick = onMenu))
    }

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Text(
            text = stringResource(R.string.soft_studio_tama_actions_hint),
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(actions.size) { index ->
                val action = actions[index]
                TamaButton(
                    icon = action.icon,
                    assetPath = action.assetPath,
                    label = action.label,
                    enabled = action.enabled,
                    onClick = {
                        action.onClick()
                        action.eventId?.let { eventId -> walkthroughTargets?.recordEvent(eventId) }
                    },
                    modifier = action.targetId?.let { Modifier.walkthroughTarget(it) } ?: Modifier
                )
            }
        }
    }
}

@Composable
fun TamaButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    assetPath: String? = null
) {
    Surface(
        modifier = modifier
            .width(if (LocalDensity.current.fontScale >= 1.3f) 144.dp else 100.dp)
            .heightIn(min = 76.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (assetPath != null) {
                TamaActionAsset(assetPath = assetPath, size = 30.dp)
            } else {
                TamaEmojiIcon(icon.orEmpty(), fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                label,
                color = LocalContentColor.current,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                softWrap = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class TamaControlConfig(
    val icon: String? = null,
    val assetPath: String? = null,
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val targetId: String? = null,
    val eventId: String? = null
)

@Composable
private fun TamaActionAsset(
    assetPath: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp
) {
    AsyncImage(
        model = "file:///android_asset/$assetPath",
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun TamaEmojiIcon(
    emoji: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp
) {
    TamaUiIcon(
        emoji = emoji,
        modifier = modifier,
        fontSize = fontSize
    )
}

@Composable
private fun TamaArtworkRevealDialog(
    artwork: TamaArtworkEntity,
    onOpenGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    val imageFile = remember(artwork.filePath) { artwork.filePath?.let(::File) }
    TamaPopupDialog(
        title = when (artwork.kind) {
            TamaArtworkKind.DREAM.name -> stringResource(R.string.tama_art_reveal_dream_title)
            TamaArtworkKind.PAINTING.name -> stringResource(R.string.tama_art_reveal_painting_title)
            else -> artwork.title
        },
        backgroundAsset = "tama/backgrounds/dream_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (artwork.kind) {
                        TamaArtworkKind.DREAM.name -> stringResource(R.string.tama_art_reveal_dream_body)
                        TamaArtworkKind.PAINTING.name -> stringResource(R.string.tama_art_reveal_painting_body)
                        else -> artwork.title
                    },
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText
                )
                if (imageFile?.exists() == true) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = artwork.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, TamaAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        },
        footerContent = {
            TextButton(onClick = onOpenGallery) {
                Text(stringResource(R.string.tama_gallery_open))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun TamaDreamAlbumRevealDialog(
    album: TamaDreamAlbumPreview,
    onOpenGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val slides = remember(album.albumId, album.story, album.artworks) {
        buildDreamSlides(context, album.story, album.artworks)
    }
    var selectedIndex by remember(album.albumId) { mutableStateOf(0) }
    val selectedSlide = slides.getOrNull(selectedIndex)
    TamaPopupDialog(
        title = stringResource(R.string.tama_art_reveal_dream_album_title),
        backgroundAsset = "tama/backgrounds/dream_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_art_reveal_dream_album_body),
                fontFamily = FontFamily.Monospace,
                color = TamaMutedText
            )
            Text(
                stringResource(R.string.tama_art_reveal_slide_counter, selectedIndex + 1, slides.size),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaAccent
            )
            Surface(
                color = TamaLight.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, TamaAccent.copy(alpha = 0.35f)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    selectedSlide?.let { slide ->
                        Text(
                            slide.title,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TamaDark
                        )
                        Text(
                            slide.body,
                            fontFamily = FontFamily.Monospace,
                            color = TamaDark,
                            fontSize = 13.sp
                        )
                        slide.artwork?.filePath?.let(::File)?.takeIf(File::exists)?.let { imageFile ->
                            AsyncImage(
                                model = imageFile,
                                contentDescription = slide.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, TamaAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { selectedIndex = (selectedIndex - 1).coerceAtLeast(0) },
                    enabled = selectedIndex > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_previous))
                }
                OutlinedButton(
                    onClick = { selectedIndex = (selectedIndex + 1).coerceAtMost(slides.lastIndex) },
                    enabled = selectedIndex < slides.lastIndex,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_next))
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        },
        footerContent = {
            TextButton(onClick = onOpenGallery) {
                Text(stringResource(R.string.tama_gallery_open))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private fun buildDreamSlides(
    context: Context,
    albumSummaryRaw: String,
    artworks: List<TamaArtworkEntity>
): List<TamaDreamSlide> {
    val summary = TamaDailyDreamManager.decodeAlbumSummary(albumSummaryRaw)
    val orderedArtworks = artworks.sortedBy { it.albumIndex }
    val introBody = summary.story.ifBlank {
        context.getString(R.string.tama_art_reveal_story_fallback)
    }
    val closingBody = summary.closing.ifBlank { introBody }
    return buildList {
        add(
            TamaDreamSlide(
                title = context.getString(R.string.tama_art_reveal_intro_title),
                body = introBody
            )
        )
        orderedArtworks.forEachIndexed { index, artwork ->
            add(
                TamaDreamSlide(
                    title = context.getString(R.string.tama_art_reveal_moment_title, index + 1),
                    body = artwork.title.ifBlank {
                        summary.momentTexts.getOrNull(index)
                            ?: context.getString(R.string.tama_art_reveal_story_fallback)
                    },
                    artwork = artwork
                )
            )
        }
        add(
            TamaDreamSlide(
                title = context.getString(R.string.tama_art_reveal_closing_title),
                body = closingBody
            )
        )
    }
}

@Composable
private fun TamaWorkDialog(
    pet: TamaPet,
    onDismiss: () -> Unit,
    onStartJob: (String) -> Unit
) {
    TamaPopupDialog(
        title = stringResource(R.string.tama_work_dialog_title),
        backgroundAsset = "tama/backgrounds/popup_generic.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_work_dialog_body, pet.educationLevel.toInt()),
                fontFamily = FontFamily.Monospace,
                color = TamaMutedText,
                fontSize = 12.sp
            )
            TamaWorkCatalog.jobs.forEach { job ->
                val unlocked = pet.educationLevel >= job.requiredEducation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (unlocked) TamaLight.copy(alpha = 0.38f) else Color.Gray.copy(alpha = 0.18f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(job.titleRes),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(
                                R.string.tama_work_job_summary,
                                stringResource(job.titleRes),
                                job.requiredEducation,
                                job.hourlyPay.toInt()
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TamaMutedText
                        )
                    }
                    TextButton(
                        onClick = { onStartJob(job.id) },
                        enabled = unlocked
                    ) {
                        Text(
                            if (unlocked) {
                                stringResource(R.string.tama_work_dialog_start)
                            } else {
                                stringResource(R.string.tama_work_dialog_locked)
                            }
                        )
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun TamaTrainingDialog(
    pet: TamaPet,
    onDismiss: () -> Unit,
    onStartTier: (String) -> Unit
) {
    TamaPopupDialog(
        title = stringResource(R.string.tama_training_dialog_title),
        backgroundAsset = "tama/backgrounds/boxing_ring.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.tama_training_dialog_body, pet.exerciseLevel.toInt()),
                    fontFamily = FontFamily.Monospace,
                    color = TamaMutedText,
                    fontSize = 12.sp
                )
                TamaTrainingCatalog.tiers.forEach { tier ->
                    val unlocked = pet.exerciseLevel >= tier.requiredExercise
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (unlocked) TamaLight.copy(alpha = 0.38f) else Color.Gray.copy(alpha = 0.18f))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(tier.titleRes),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(
                                    R.string.tama_training_tier_summary,
                                    tier.requiredExercise,
                                    tier.hourlyPay.toInt()
                                ),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TamaMutedText
                            )
                        }
                        TextButton(
                            onClick = { onStartTier(tier.id) },
                            enabled = unlocked
                        ) {
                            Text(
                                if (unlocked) {
                                    stringResource(R.string.tama_training_dialog_start)
                                } else {
                                    stringResource(R.string.tama_training_dialog_locked)
                                }
                            )
                        }
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun DecorPlacementDialog(
    decorName: String,
    onPlace: (TamaDecorSlot) -> Unit,
    onDismiss: () -> Unit
) {
    TamaPopupDialog(
        title = stringResource(R.string.tama_inventory_place_title, decorName),
        backgroundAsset = "tama/backgrounds/popup_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(
                stringResource(R.string.tama_inventory_place_desc),
                fontFamily = FontFamily.Monospace,
                color = TamaMutedText
            )
            FilledTonalButton(
                onClick = { onPlace(TamaDecorSlot.LEFT) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.tama_inventory_place_left))
            }
            FilledTonalButton(
                onClick = { onPlace(TamaDecorSlot.RIGHT) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.tama_inventory_place_right))
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TamaSettingsDialog(
    navController: NavController,
    settingsRepo: SettingsRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val normalDreamingEnabled by settingsRepo.tamaNormalDreamingEnabled.collectAsState()
    val deepDreamingEnabled by settingsRepo.tamaDeepDreamingEnabled.collectAsState()
    val deepDreamRetryCount by settingsRepo.tamaDeepDreamRetryCount.collectAsState()
    val deepDreamDesiredLanguage by settingsRepo.tamaDeepDreamDesiredLanguage.collectAsState()
    val schoolPaintingEnabled by settingsRepo.tamaSchoolPaintingEnabled.collectAsState()
    val selectedModelFilename by settingsRepo.tamaPicGenModelFilename.collectAsState()
    val selectedResolution by settingsRepo.tamaPicGenResolution.collectAsState()
    val backend by settingsRepo.tamaBackend.collectAsState()
    val tamaOllamaUrl by settingsRepo.tamaOllamaUrl.collectAsState()
    val tamaSummarizerModel by settingsRepo.tamaSummarizerModel.collectAsState()
    val tamaLlamaServerUrl by settingsRepo.tamaLlamaServerUrl.collectAsState()
    val tamaLlamaSwapUrl by settingsRepo.tamaLlamaSwapUrl.collectAsState()
    val serverModelLabel by settingsRepo.tamaLlamaServerModelLabel.collectAsState()
    val serverContextLabel by settingsRepo.tamaLlamaServerContextLabel.collectAsState()
    val serverContextTokens by settingsRepo.tamaLlamaServerContextTokens.collectAsState()
    val tamaLiteRtModelId by settingsRepo.tamaLiteRtModelId.collectAsState()
    val tamaLiteRtBackend by settingsRepo.tamaLiteRtBackend.collectAsState()
    val tamaLiteRtMtpEnabled by settingsRepo.tamaLiteRtMtpEnabled.collectAsState()
    val tamaThinkingEnabled by settingsRepo.tamaThinkingEnabled.collectAsState()
    val installedModelsFlow = remember(appContext) {
        runCatching {
            AppDatabase.getDatabase(appContext)
                .modelDao()
                .getModelsByType(ModelType.ONNX_IMAGE_GEN)
        }.getOrElse {
            flowOf(emptyList())
        }
    }
    val installedModels by installedModelsFlow.collectAsState(initial = emptyList())

    val txt2ImgModels = remember(installedModels) { installedModels.filter { it.isOnnxTxt2ImgBundle() } }
    val defaultInstalled = remember(txt2ImgModels) { txt2ImgModels.any { it.isTamaDefaultPicGenModel() } }
    val selectedModel = remember(txt2ImgModels, selectedModelFilename) {
        selectedModelFilename?.let { filename -> txt2ImgModels.firstOrNull { it.filename == filename } }
            ?: txt2ImgModels.firstOrNull { it.isTamaDefaultPicGenModel() }
    }
    var showModelMenu by remember { mutableStateOf(false) }
    var showResolutionMenu by remember { mutableStateOf(false) }

    fun persistMetadata(metadata: RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setTamaLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setTamaLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setTamaLlamaServerContextLabel(metadata.serverContextLabel)
        }
        if (SettingsRepository.requiresSelectedRemoteModel(metadata.backend) && !metadata.selectedModel.isNullOrBlank()) {
            settingsRepo.setTamaSummarizerModel(metadata.selectedModel)
        }
    }

    TamaPopupDialog(
        title = stringResource(R.string.tama_settings_title),
        backgroundAsset = "tama/backgrounds/popup_generic.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TamaToggleSetting(
                    title = stringResource(R.string.tama_settings_normal_dreaming_title),
                    description = stringResource(R.string.tama_settings_normal_dreaming_desc),
                    checked = normalDreamingEnabled,
                    onCheckedChange = settingsRepo::setTamaNormalDreamingEnabled
                )
                TamaToggleSetting(
                    title = stringResource(R.string.tama_settings_deep_dreaming_title),
                    description = stringResource(R.string.tama_settings_deep_dreaming_desc),
                    checked = deepDreamingEnabled,
                    onCheckedChange = settingsRepo::setTamaDeepDreamingEnabled
                )
                OutlinedTextField(
                    value = deepDreamDesiredLanguage,
                    onValueChange = settingsRepo::setTamaDeepDreamDesiredLanguage,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tama_settings_deep_dream_language_title)) },
                    supportingText = {
                        Text(
                            stringResource(R.string.tama_settings_deep_dream_language_desc),
                            color = TamaHelperText
                        )
                    }
                )
                DraftIntTextField(
                    value = deepDreamRetryCount,
                    onValueChange = settingsRepo::setTamaDeepDreamRetryCount,
                    valueRange = 1..Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tama_settings_deep_dream_retry_title)) },
                    supportingText = {
                        Text(
                            stringResource(R.string.tama_settings_deep_dream_retry_desc),
                            color = TamaHelperText
                        )
                    }
                )
                TamaToggleSetting(
                    title = stringResource(R.string.tama_settings_school_painting_title),
                    description = stringResource(R.string.tama_settings_school_painting_desc),
                    checked = schoolPaintingEnabled,
                    onCheckedChange = settingsRepo::setTamaSchoolPaintingEnabled
                )
                HorizontalDivider()
                RemoteSummaryBackendEditor(
                    title = stringResource(R.string.tama_settings_ai_section),
                    backend = backend,
                    onBackendChange = settingsRepo::setTamaBackend,
                    ollamaUrl = tamaOllamaUrl,
                    onOllamaUrlChange = settingsRepo::setTamaOllamaUrl,
                    llamaServerUrl = tamaLlamaServerUrl,
                    onLlamaServerUrlChange = settingsRepo::setTamaLlamaServerUrl,
                    llamaSwapUrl = tamaLlamaSwapUrl,
                    onLlamaSwapUrlChange = settingsRepo::setTamaLlamaSwapUrl,
                    ollamaModel = tamaSummarizerModel,
                    onOllamaModelSelected = settingsRepo::setTamaSummarizerModel,
                    llamaSwapModel = tamaSummarizerModel,
                    onLlamaSwapModelSelected = settingsRepo::setTamaSummarizerModel,
                    llamaServerModelLabel = serverModelLabel,
                    llamaServerContextLabel = serverContextLabel,
                    llamaServerContextTokens = serverContextTokens,
                    requestedContextForWarning = null,
                    liteRtModelId = tamaLiteRtModelId.takeIf { it > 0L },
                    onLiteRtModelSelected = settingsRepo::setTamaLiteRtModelId,
                    liteRtBackend = tamaLiteRtBackend,
                    onLiteRtBackendChange = settingsRepo::setTamaLiteRtBackend,
                    liteRtMtpEnabled = tamaLiteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = settingsRepo::setTamaLiteRtMtpEnabled,
                    liteRtThinkingEnabled = tamaThinkingEnabled,
                    onLiteRtThinkingEnabledChange = settingsRepo::setTamaThinkingEnabled,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromConfig(
                            RemoteSummaryBackendConfig(
                                context = context,
                                backend = backend,
                                baseUrl = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> tamaLlamaServerUrl.trim()
                                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> tamaLlamaSwapUrl.trim()
                                    else -> tamaOllamaUrl.trim()
                                },
                                model = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> serverModelLabel?.trim()?.ifBlank { null }
                                    else -> tamaSummarizerModel.trim().ifBlank { null }
                                },
                                liteRtModelId = tamaLiteRtModelId.takeIf { it > 0L },
                                liteRtBackend = tamaLiteRtBackend,
                                liteRtMtpEnabled = tamaLiteRtMtpEnabled,
                                timeoutMinutes = 1
                            )
                        ).fetchMetadata()
                    },
                    onMetadataLoaded = ::persistMetadata
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.tama_settings_pic_gen_section),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.tama_settings_pic_gen_hint),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaHelperText
                )
                if (!defaultInstalled) {
                    Text(
                        stringResource(R.string.tama_pic_gen_install_default_model),
                        color = Color(0xFFB3261E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                if (txt2ImgModels.isEmpty()) {
                    FilledTonalButton(onClick = { navController.navigate(Screen.OnnxModels.route) }) {
                        Text(stringResource(R.string.tama_settings_open_onnx_models))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.tama_settings_pic_model_title),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExposedDropdownMenuBox(
                            expanded = showModelMenu,
                            onExpandedChange = { showModelMenu = !showModelMenu }
                        ) {
                            OutlinedTextField(
                                value = selectedModel?.let { tamaModelLabel(it) }.orEmpty(),
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                readOnly = true,
                                label = { Text(stringResource(R.string.tama_settings_pic_model_title)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModelMenu) }
                            )
                            ExposedDropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false }
                            ) {
                                txt2ImgModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(tamaModelLabel(model)) },
                                        onClick = {
                                            settingsRepo.setTamaPicGenModelFilename(model.filename)
                                            showModelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.tama_settings_pic_resolution_title),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExposedDropdownMenuBox(
                            expanded = showResolutionMenu,
                            onExpandedChange = { showResolutionMenu = !showResolutionMenu }
                        ) {
                            OutlinedTextField(
                                value = "${selectedResolution}x${selectedResolution}",
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                readOnly = true,
                                label = { Text(stringResource(R.string.tama_settings_pic_resolution_title)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showResolutionMenu) }
                            )
                            ExposedDropdownMenu(
                                expanded = showResolutionMenu,
                                onDismissRequest = { showResolutionMenu = false }
                            ) {
                                TamaPicGenDefaults.RESOLUTION_PRESETS.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text("${preset}x${preset}") },
                                        onClick = {
                                            settingsRepo.setTamaPicGenResolution(preset)
                                            showResolutionMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    FilledTonalButton(onClick = { navController.navigate(Screen.OnnxModels.route) }) {
                        Text(stringResource(R.string.tama_settings_open_onnx_models))
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun TamaToggleSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(description, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TamaHelperText)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TamaEventLog(events: List<TamaEvent>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.tama_recent_events),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )

        events.forEach { event ->
            Text(
                text = event.toLogString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        if (events.isEmpty()) {
            Text(
                stringResource(R.string.tama_no_events_yet),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun NewPetDialog(
    onConfirm: (String, PetSpeciesLine) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedSpecies by remember { mutableStateOf(PetSpeciesLine.DRAGON) }

    TamaPopupDialog(
        title = stringResource(R.string.tama_new_pet_title),
        backgroundAsset = "tama/backgrounds/popup_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tama_egg_appeared), fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tama_name_label)) },
                    singleLine = true
                )
                Text(
                    stringResource(R.string.tama_species_picker_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                PetSpeciesLine.entries.forEach { speciesLine ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSpecies = speciesLine },
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (selectedSpecies == speciesLine) TamaLight else Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 2.dp,
                            color = if (selectedSpecies == speciesLine) TamaDark else TamaAccent
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = "file:///android_asset/" + resolvePetSpriteAssetPath(
                                    speciesLine = speciesLine,
                                    stage = GrowthStage.ADULT,
                                    state = PetSpriteState.IDLE,
                                    frameIndex = 0
                                ),
                                contentDescription = stringResource(speciesLine.displayNameRes),
                                modifier = Modifier.size(72.dp),
                                contentScale = ContentScale.Fit,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(speciesLine.displayNameRes),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selectedSpecies == speciesLine) {
                                    Text(
                                        stringResource(R.string.tama_species_selected),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TamaAccent
                                    )
                                }
                            }
                            RadioButton(
                                selected = selectedSpecies == speciesLine,
                                onClick = { selectedSpecies = speciesLine }
                            )
                        }
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name, selectedSpecies) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.tama_hatch_btn))
            }
        }
    )
}

@Composable
fun TamaMenuDialog(
    onDismiss: () -> Unit,
    onStatus: () -> Unit,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onStore: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit
) {
    TamaPopupDialog(
        title = stringResource(R.string.tama_menu_title),
        backgroundAsset = "tama/backgrounds/popup_generic.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStatus, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tama_menu_status))
                }
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tama_menu_settings))
                }
                TextButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tama_menu_gallery))
                }
                TextButton(onClick = onStore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tama_btn_store))
                }
                TextButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tama_menu_export))
                }
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tama_menu_import))
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                    Text(stringResource(R.string.tama_menu_reset))
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun PetStatusInfoValue(
    icon: String,
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    assetPath: String? = null,
    assetSize: Dp = 16.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (assetPath != null) {
            AsyncImage(
                model = "file:///android_asset/$assetPath",
                contentDescription = null,
                modifier = Modifier.size(assetSize),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        } else {
            TamaEmojiIcon(icon, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = fontWeight,
            color = TamaDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PetStatusDialog(
    pet: TamaPet,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val adventureGateRepository = remember(context) {
        AdventureGateRepository(TamaDatabase.getInstance(context))
    }
    var adventureGateProfile by remember(pet.id) { mutableStateOf<AdventureGateProfile?>(null) }
    val speciesName = remember(pet.species, pet.genetics.bodyStyle) {
        speciesDisplayName(context, pet.species, pet.genetics.bodyStyle)
    }
    val ageMinutes = ((System.currentTimeMillis() - pet.birthTimestamp) / (1000 * 60)).toInt()
    val ageText = if (ageMinutes < 60) {
        stringResource(R.string.tama_age_minutes, ageMinutes)
    } else {
        stringResource(R.string.tama_age_hours_minutes, ageMinutes / 60, ageMinutes % 60)
    }

    LaunchedEffect(pet.id) {
        adventureGateProfile = adventureGateRepository.getOrCreateProfile(pet.id)
    }

    TamaPopupDialog(
        title = pet.name,
        backgroundAsset = "tama/backgrounds/popup_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TamaEmojiIcon(if (pet.isEffectivelyMad()) "😠" else pet.mood.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        if (pet.isEffectivelyMad()) {
                            Text(stringResource(R.string.tama_status_grumpy), color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("$speciesName • ${pet.stage.displayName} • $ageText", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TamaMutedText)
                    }
                }
                // Core Stats Section
                Text(stringResource(R.string.tama_status_stats_section), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TamaAccent)
                Spacer(modifier = Modifier.height(4.dp))
                StatBarRow(stringResource(R.string.tama_status_hunger), pet.stats.hunger)
                StatBarRow(stringResource(R.string.tama_status_happy), pet.stats.happiness)
                StatBarRow(stringResource(R.string.tama_status_hygiene), pet.stats.hygiene)
                StatBarRow(stringResource(R.string.tama_status_energy), pet.stats.energy)
                StatBarRow(stringResource(R.string.tama_status_health), pet.stats.health)

                adventureGateProfile?.let { profile ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.adventure_gate_pet_status),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TamaAccent
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.adventure_gate_level_xp, profile.level, profile.xp, profile.xpToNext),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TamaMutedText
                    )
                    AdventureGatePetStatRow(
                        stringResource(R.string.adventure_gate_stat_hp),
                        "${profile.currentHp}/${profile.stats.maxHp}"
                    )
                    AdventureGatePetStatRow(
                        stringResource(R.string.adventure_gate_stat_mana),
                        "${profile.currentMana}/${profile.stats.maxMana}"
                    )
                    AdventureGatePetStatRow(
                        stringResource(R.string.adventure_gate_skill_points_short),
                        profile.skillPoints.toString()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdventureGatePetStatRow(
                            stringResource(R.string.adventure_gate_stat_attack),
                            profile.stats.attack.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        AdventureGatePetStatRow(
                            stringResource(R.string.adventure_gate_stat_magic),
                            profile.stats.magic.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdventureGatePetStatRow(
                            stringResource(R.string.adventure_gate_stat_defense),
                            profile.stats.defense.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        AdventureGatePetStatRow(
                            stringResource(R.string.adventure_gate_stat_speed),
                            profile.stats.speed.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdventureGatePetStatRow(
                            stringResource(R.string.adventure_gate_stat_accuracy),
                            profile.stats.accuracy.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        AdventureGatePetStatRow(
                            stringResource(R.string.adventure_gate_stat_evasion),
                            profile.stats.evasion.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Info Section
                Text(stringResource(R.string.tama_status_info_section), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TamaAccent)
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PetStatusInfoValue(
                        icon = TAMA_MONEY_EMOJI,
                        text = "${pet.money}",
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.Bold
                    )
                    PetStatusInfoValue(
                        icon = TAMA_EDUCATION_EMOJI,
                        text = stringResource(R.string.tama_status_education_value, pet.educationLevel.toInt()),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PetStatusInfoValue(
                        icon = TAMA_INTROSPECTION_EMOJI,
                        text = stringResource(R.string.tama_status_introspection_value, pet.introspectionLevel.toInt()),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PetStatusInfoValue(
                        icon = "",
                        assetPath = TAMA_EXERCISE_ICON_ASSET,
                        text = stringResource(R.string.tama_status_exercise_value, pet.exerciseLevel.toInt()),
                        modifier = Modifier.fillMaxWidth(),
                        assetSize = 20.dp
                    )
                }
                Text(stringResource(R.string.tama_species_label, speciesName), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TamaMutedText)
                Text("${pet.personality.name}: ${pet.personality.description}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TamaMutedText)

                Spacer(modifier = Modifier.height(12.dp))

                // Inventory Section
                Text(
                    stringResource(R.string.tama_status_inventory_section, pet.inventory.totalQuantity()),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TamaAccent
                )
                if (pet.inventory.isEmpty()) {
                    Text(stringResource(R.string.tama_status_inventory_empty), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TamaMutedText)
                } else {
                    val grouped = pet.inventory.quantityByName()
                    Text(
                        grouped.entries.joinToString { "${it.key} x${it.value}" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
fun StatBarRow(label: String, value: Float) {
    val intValue = value.toInt()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(80.dp))
        LinearProgressIndicator(
            progress = { intValue / 100f },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = when {
                intValue >= 70 -> Color(0xFF4CAF50)
                intValue >= 40 -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            },
            trackColor = Color.Gray.copy(alpha = 0.2f)
        )
        Text("${intValue}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun AdventureGatePetStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TamaMutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TamaDark,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun statusLabel(status: String): String {
    return when (status) {
        TamaArtworkStatus.QUEUED.name -> stringResource(R.string.tama_gallery_status_queued)
        TamaArtworkStatus.GENERATING.name -> stringResource(R.string.tama_gallery_status_generating)
        TamaArtworkStatus.COMPLETED.name -> stringResource(R.string.tama_gallery_status_completed)
        TamaArtworkStatus.FAILED.name -> stringResource(R.string.tama_gallery_status_failed)
        else -> status
    }
}

@Composable
private fun tamaModelLabel(model: com.example.llamadroid.data.db.ModelEntity): String {
    val entry = resolveOnnxCatalogEntry(model)
    return if (entry != null) {
        val providerLabel = when (entry.provider) {
            OnnxCatalogProvider.SDAI -> stringResource(R.string.onnx_models_provider_sdai)
            OnnxCatalogProvider.MANUXD32 -> stringResource(R.string.onnx_models_provider_manuxd32)
            OnnxCatalogProvider.SUPERTONIC -> stringResource(R.string.onnx_models_provider_supertonic)
            OnnxCatalogProvider.BACKGROUND_REMOVAL -> stringResource(R.string.onnx_models_provider_bgr)
        }
        "${entry.title} · $providerLabel"
    } else {
        model.filename
    }
}

@Composable
fun ResetConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TamaPopupDialog(
        title = title,
        backgroundAsset = "tama/backgrounds/dream_room.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Text(message, fontFamily = FontFamily.Monospace)
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text(stringResource(R.string.tama_confirm_delete_action), color = Color.White)
            }
        }
    )
}
