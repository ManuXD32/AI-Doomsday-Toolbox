package com.example.llamadroid.tama.ui

import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.PetSpriteState
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.mapPetActionToSpriteState
import com.example.llamadroid.tama.world.presentation.ArcadeReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionReceipt
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLease
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLeaseStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRecovery
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRecoveryStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionTerminal
import com.example.llamadroid.tama.world.presentation.ArcadeWorldActionBridge
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random
import java.util.UUID

private const val ARCade_BACKGROUND = "tama/minigames/arcade/background.png"
private const val ARCade_STAR = "tama/minigames/arcade/star.png"
private const val ARCade_COIN = "tama/minigames/arcade/coin.png"
private const val ARCade_FRUIT = "tama/minigames/arcade/fruit.png"
private const val ARCade_HEART = "tama/minigames/arcade/heart.png"
private const val ARCade_GEM = "tama/minigames/arcade/gem.png"
private const val ARCade_MEMORY_BACKGROUND = "tama/minigames/memory/background.png"

private enum class ArcadePlayerPose(val petAction: String) {
    IDLE("idle"),
    LEFT("walking"),
    RIGHT("walking"),
    CATCH("eating"),
    MISS("idle"),
    WIN("idle"),
    LOSE("sleeping")
}

private enum class ArcadeMode {
    HUB,
    CATCH,
    MEMORY
}

private enum class ArcadeFallingKind(val assetPath: String, val titleRes: Int) {
    STAR(ARCade_STAR, R.string.tama_arcade_item_star),
    COIN(ARCade_COIN, R.string.tama_arcade_item_coin),
    FRUIT(ARCade_FRUIT, R.string.tama_arcade_item_fruit),
    HEART(ARCade_HEART, R.string.tama_arcade_item_heart),
    GEM(ARCade_GEM, R.string.tama_arcade_item_gem)
}

private data class ArcadeFallingObject(
    val id: Int,
    val lane: Int,
    val kind: ArcadeFallingKind,
    val spawnAtMs: Long,
    val fallDurationMs: Long,
    val resolved: Boolean = false,
    val caught: Boolean = false
)

private data class ArcadeCatchGameState(
    val startedAtMs: Long = 0L,
    val introUntilMs: Long = 0L,
    val durationMs: Long = 20000L,
    val totalObjects: Int = 0,
    val elapsedMs: Long = 0L,
    val playerLane: Int = 1,
    val playerPose: ArcadePlayerPose = ArcadePlayerPose.IDLE,
    val poseUntilMs: Long = 0L,
    val objects: List<ArcadeFallingObject> = emptyList(),
    val catches: Int = 0,
    val misses: Int = 0,
    val finished: Boolean = false
)

private data class ArcadeResult(
    val catches: Int,
    val misses: Int,
    val totalObjects: Int,
    val score: Int,
    val coins: Int,
    val happiness: Int,
    val receiptStatus: ArcadeReceiptStatus = ArcadeReceiptStatus.SUBMITTING
)

private data class ArcadeSubmitOutcome(
    val receipt: ArcadeSessionReceipt,
    val retryable: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArcadeScreen(
    navController: NavController,
    pet: TamaPet,
    worldActionBridge: ArcadeWorldActionBridge = ArcadeWorldActionBridge.Unavailable
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var arcadeMode by rememberSaveable { mutableStateOf(ArcadeMode.HUB) }
    var catchGameState by remember { mutableStateOf(ArcadeCatchGameState()) }
    var catchResult by remember { mutableStateOf<ArcadeResult?>(null) }
    var catchRewardClaimed by remember { mutableStateOf(false) }
    var catchSessionId by rememberSaveable { mutableStateOf("") }
    var memoryGameState by remember { mutableStateOf(startMemoryGame()) }
    var memoryResult by remember { mutableStateOf<MemoryGameResult?>(null) }
    var memoryRewardClaimed by remember { mutableStateOf(false) }
    var memorySessionId by rememberSaveable { mutableStateOf("") }
    var memoryReceiptStatus by remember { mutableStateOf(ArcadeReceiptStatus.UNAVAILABLE) }
    var sessionLease by remember { mutableStateOf<ArcadeSessionLease?>(null) }
    var sessionRecoveryLoaded by remember { mutableStateOf(false) }
    var sessionRecoveryUnavailable by remember { mutableStateOf(false) }
    var sessionRecoveryAttempt by rememberSaveable { mutableStateOf(0) }
    var sessionStarting by remember { mutableStateOf(false) }
    var sessionCancelling by remember { mutableStateOf(false) }
    var tickToken by rememberSaveable { mutableStateOf(0L) }
    val walkthroughTargets = LocalWalkthroughTargets.current
    val context = LocalContext.current

    fun restoreTerminalArcadeResult(terminal: ArcadeSessionTerminal) {
        val request = terminal.request
        val receipt = terminal.receipt
        sessionLease = ArcadeSessionLease(
            petId = request.petId,
            sessionId = request.sessionId,
            gameId = request.gameId,
            status = ArcadeSessionLeaseStatus.TERMINAL
        )
        when (request.gameId) {
            "catch" -> {
                catchSessionId = request.sessionId
                catchRewardClaimed = true
                catchResult = ArcadeResult(
                    catches = request.catches,
                    misses = request.misses,
                    totalObjects = request.totalObjects,
                    score = request.score,
                    coins = receipt.coins,
                    happiness = receipt.happiness,
                    receiptStatus = receipt.status
                )
                arcadeMode = ArcadeMode.CATCH
            }
            "memory" -> {
                memorySessionId = request.sessionId
                memoryRewardClaimed = true
                memoryReceiptStatus = receipt.status
                memoryResult = MemoryGameResult(
                    pairsMatched = request.pairsMatched,
                    turnsUsed = request.turnsUsed,
                    score = request.score,
                    coins = receipt.coins,
                    happiness = receipt.happiness,
                    perfectClear = request.pairsMatched >= 8 && request.turnsUsed < 12
                )
                arcadeMode = ArcadeMode.MEMORY
            }
        }
    }

    // Recover before beginning a local game. A durable active lease keeps the
    // actor at the arcade; a committed terminal result rebuilds its summary
    // from canonical metrics after process death.
    LaunchedEffect(pet.id, sessionRecoveryAttempt) {
        sessionRecoveryLoaded = false
        val recovery = try {
            worldActionBridge.recover(pet.id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ArcadeSessionRecovery(status = ArcadeSessionRecoveryStatus.UNAVAILABLE)
        }
        sessionRecoveryUnavailable = recovery.status == ArcadeSessionRecoveryStatus.UNAVAILABLE
        sessionLease = recovery.activeLease
        recovery.terminal?.let(::restoreTerminalArcadeResult)
        if (recovery.status == ArcadeSessionRecoveryStatus.NONE) {
            sessionLease = null
        }
        sessionRecoveryLoaded = true
    }

    // Create the durable world lease before the local game timer starts. The
    // development fallback returns UNAVAILABLE and therefore keeps the game
    // blocked; it never claims a reward or changes simulation state.
    LaunchedEffect(
        arcadeMode,
        catchSessionId,
        memorySessionId,
        sessionRecoveryLoaded,
        sessionRecoveryAttempt
    ) {
        if (!sessionRecoveryLoaded || arcadeMode == ArcadeMode.HUB) return@LaunchedEffect
        val sessionId = when (arcadeMode) {
            ArcadeMode.CATCH -> catchSessionId
            ArcadeMode.MEMORY -> memorySessionId
            ArcadeMode.HUB -> ""
        }
        if (sessionId.isBlank() || sessionLease?.sessionId == sessionId ||
            sessionLease?.keepsPetAtArcade == true) return@LaunchedEffect
        val gameId = if (arcadeMode == ArcadeMode.CATCH) "catch" else "memory"
        val request = ArcadeSessionRequest(
            petId = pet.id,
            sessionId = sessionId,
            gameId = gameId,
            score = 0
        )
        sessionStarting = true
        val lease = try {
            worldActionBridge.begin(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ArcadeSessionLease(
                petId = request.petId,
                sessionId = request.sessionId,
                gameId = request.gameId,
                status = ArcadeSessionLeaseStatus.UNAVAILABLE
            )
        }
        if (sessionId == when (arcadeMode) {
                ArcadeMode.CATCH -> catchSessionId
                ArcadeMode.MEMORY -> memorySessionId
                ArcadeMode.HUB -> ""
            }) {
            sessionLease = lease
            sessionRecoveryUnavailable = lease.status == ArcadeSessionLeaseStatus.UNAVAILABLE
            sessionStarting = false
        }
    }

    val currentSessionId = when (arcadeMode) {
        ArcadeMode.CATCH -> catchSessionId
        ArcadeMode.MEMORY -> memorySessionId
        ArcadeMode.HUB -> ""
    }
    val currentLease = sessionLease?.takeIf { it.sessionId == currentSessionId }
    val localGameMayRun = currentLease?.let { arcadeSessionMayRun(it) } == true

    fun acknowledgeAnd(request: ArcadeSessionRequest, after: () -> Unit) {
        if (sessionCancelling) return
        sessionCancelling = true
        coroutineScope.launch {
            val acknowledged = try {
                worldActionBridge.acknowledge(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (acknowledged) {
                after()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.tama_arcade_session_ack_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            sessionCancelling = false
        }
    }

    fun cancelLease(lease: ArcadeSessionLease, afterCancel: (ArcadeSessionRequest) -> Unit) {
        if (sessionCancelling) return
        sessionCancelling = true
        coroutineScope.launch {
            val request = ArcadeSessionRequest(
                petId = lease.petId,
                sessionId = lease.sessionId,
                gameId = lease.gameId,
                score = 0
            )
            val receipt = try {
                worldActionBridge.cancel(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (receipt?.status?.isTerminal() == true) {
                sessionLease = lease.copy(status = ArcadeSessionLeaseStatus.TERMINAL)
                sessionCancelling = false
                afterCancel(request)
                return@launch
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.tama_arcade_session_cancel_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            sessionCancelling = false
        }
    }

    fun leaveArcade() {
        val lease = sessionLease?.takeIf { it.keepsPetAtArcade }
        if (lease == null) {
            navController.popBackStack()
            return
        }
        cancelLease(lease) { request ->
            acknowledgeAnd(request) { navController.popBackStack() }
        }
    }

    fun catchRequest(summary: ArcadeResult): ArcadeSessionRequest = ArcadeSessionRequest(
        petId = pet.id,
        sessionId = catchSessionId,
        gameId = "catch",
        score = summary.score,
        catches = summary.catches,
        misses = summary.misses,
        totalObjects = summary.totalObjects
    )

    fun memoryRequest(summary: MemoryGameResult): ArcadeSessionRequest = ArcadeSessionRequest(
        petId = pet.id,
        sessionId = memorySessionId,
        gameId = "memory",
        score = summary.score,
        totalObjects = 8,
        pairsMatched = summary.pairsMatched,
        turnsUsed = summary.turnsUsed
    )

    fun submitCatchResult(summary: ArcadeResult) {
        val request = catchRequest(summary)
        catchResult = summary.copy(coins = 0, happiness = 0, receiptStatus = ArcadeReceiptStatus.SUBMITTING)
        sessionLease = sessionLease?.copy(status = ArcadeSessionLeaseStatus.RUNNING)
        coroutineScope.launch {
            val outcome = submitArcadeSession(worldActionBridge, request)
            if (catchSessionId != request.sessionId) return@launch
            val receipt = outcome.receipt
            catchResult = summary.copy(
                coins = receipt.coins,
                happiness = receipt.happiness,
                receiptStatus = receipt.status
            )
            sessionLease = sessionLease?.copy(
                status = if (outcome.retryable) ArcadeSessionLeaseStatus.RECONCILE_REQUIRED
                else ArcadeSessionLeaseStatus.TERMINAL
            )
        }
    }

    fun submitMemoryResult(summary: MemoryGameResult) {
        val request = memoryRequest(summary)
        memoryReceiptStatus = ArcadeReceiptStatus.SUBMITTING
        memoryResult = summary.copy(coins = 0, happiness = 0)
        sessionLease = sessionLease?.copy(status = ArcadeSessionLeaseStatus.RUNNING)
        coroutineScope.launch {
            val outcome = submitArcadeSession(worldActionBridge, request)
            if (memorySessionId != request.sessionId) return@launch
            val receipt = outcome.receipt
            memoryReceiptStatus = receipt.status
            memoryResult = summary.copy(coins = receipt.coins, happiness = receipt.happiness)
            sessionLease = sessionLease?.copy(
                status = if (outcome.retryable) ArcadeSessionLeaseStatus.RECONCILE_REQUIRED
                else ArcadeSessionLeaseStatus.TERMINAL
            )
        }
    }

    fun restartCatchGame() {
        val lease = sessionLease?.takeIf { it.gameId == "catch" && it.keepsPetAtArcade }
        val start = {
            sessionLease = null
            catchRewardClaimed = false
            catchResult = null
            catchSessionId = UUID.randomUUID().toString()
            catchGameState = startCatchGame()
            tickToken = System.currentTimeMillis()
        }
        if (lease == null) start() else cancelLease(lease) { request -> acknowledgeAnd(request, start) }
    }

    fun restartMemoryGame() {
        val lease = sessionLease?.takeIf { it.gameId == "memory" && it.keepsPetAtArcade }
        val start = {
            sessionLease = null
            memoryRewardClaimed = false
            memoryResult = null
            memorySessionId = UUID.randomUUID().toString()
            memoryReceiptStatus = ArcadeReceiptStatus.SUBMITTING
            memoryGameState = startMemoryGame()
            tickToken = System.currentTimeMillis()
        }
        if (lease == null) start() else cancelLease(lease) { request -> acknowledgeAnd(request, start) }
    }

    LaunchedEffect(arcadeMode, tickToken, currentLease?.status) {
        if (arcadeMode != ArcadeMode.CATCH || !localGameMayRun) return@LaunchedEffect
        while (isActive && !catchGameState.finished) {
            delay(50L)
            val now = System.currentTimeMillis()
            catchGameState = advanceCatchGame(catchGameState, now)
        }
    }

    LaunchedEffect(arcadeMode, tickToken, currentLease?.status) {
        if (arcadeMode != ArcadeMode.MEMORY || !localGameMayRun) return@LaunchedEffect
        while (isActive && !memoryGameState.finished) {
            delay(50L)
            val now = System.currentTimeMillis()
            memoryGameState = advanceMemoryGame(memoryGameState, now)
        }
    }

    LaunchedEffect(catchGameState.finished, catchRewardClaimed, arcadeMode, currentLease?.status) {
        if (arcadeMode != ArcadeMode.CATCH || !catchGameState.finished || catchRewardClaimed ||
            !currentLease.canSubmitArcadeSession()) return@LaunchedEffect
        val summary = ArcadeResult(
            catches = catchGameState.catches,
            misses = catchGameState.misses,
            totalObjects = catchGameState.totalObjects,
            score = catchGameState.catches,
            coins = 0,
            happiness = 0
        )
        catchRewardClaimed = true
        submitCatchResult(summary)
    }

    LaunchedEffect(memoryGameState.finished, memoryRewardClaimed, arcadeMode, currentLease?.status) {
        if (arcadeMode != ArcadeMode.MEMORY || !memoryGameState.finished || memoryRewardClaimed ||
            !currentLease.canSubmitArcadeSession()) return@LaunchedEffect
        val summary = buildMemoryGameResult(memoryGameState) ?: return@LaunchedEffect
        memoryRewardClaimed = true
        submitMemoryResult(summary)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },
                title = { Text(stringResource(R.string.tama_arcade_title), fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(
                        onClick = ::leaveArcade,
                        enabled = !sessionCancelling,
                        modifier = Modifier.walkthroughTarget("back")
                    ) {
                        if (sessionCancelling) {
                            Text("…", color = TamaLight)
                        } else {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = TamaDark,
                    titleContentColor = TamaLight,
                    navigationIconContentColor = TamaLight
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(TamaBackground)
        ) {
            TamaLocationBackdrop(
                locationType = "arcade",
                modifier = Modifier
                    .fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )

            if (arcadeMode == ArcadeMode.HUB) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(TamaDark).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.tama_arcade_subtitle),
                            fontFamily = FontFamily.Monospace,
                            color = TamaLight,
                            fontSize = 12.sp
                        )
                        Text(
                            text = stringResource(R.string.tama_arcade_reward_hint),
                            fontFamily = FontFamily.Monospace,
                            color = TamaLight,
                            fontSize = 11.sp
                        )
                    }

                    sessionLease?.takeIf { it.keepsPetAtArcade }?.let {
                        ArcadeSessionRecoveryPanel(onCancel = ::leaveArcade)
                    }
                    if (sessionRecoveryUnavailable) {
                        ArcadeSessionUnavailablePanel(
                            onRetry = {
                                sessionRecoveryUnavailable = false
                                sessionRecoveryAttempt += 1
                            }
                        )
                    }
                    ArcadeHub(
                        canStartGame = sessionRecoveryLoaded && !sessionRecoveryUnavailable &&
                            sessionLease?.keepsPetAtArcade != true,
                        onPlayCatchGame = {
                            sessionLease = null
                            catchRewardClaimed = false
                            catchResult = null
                            catchSessionId = UUID.randomUUID().toString()
                            catchGameState = startCatchGame()
                            tickToken = System.currentTimeMillis()
                            arcadeMode = ArcadeMode.CATCH
                            walkthroughTargets?.recordEvent("tama.arcade")
                        },
                        onPlayMemoryGame = {
                            sessionLease = null
                            memoryRewardClaimed = false
                            memoryResult = null
                            memorySessionId = UUID.randomUUID().toString()
                            memoryReceiptStatus = ArcadeReceiptStatus.SUBMITTING
                            memoryGameState = startMemoryGame()
                            tickToken = System.currentTimeMillis()
                            arcadeMode = ArcadeMode.MEMORY
                            walkthroughTargets?.recordEvent("tama.arcade")
                        }
                    )
                }
            } else if (arcadeMode == ArcadeMode.CATCH) {
                CatchGameScreen(
                    pet = pet,
                    state = catchGameState,
                    onMoveLeft = {
                        catchGameState = catchGameState.copy(
                            playerLane = if (catchGameState.playerLane == 0) 2 else catchGameState.playerLane - 1,
                            playerPose = ArcadePlayerPose.LEFT,
                            poseUntilMs = System.currentTimeMillis() + 160L
                        )
                    },
                    onMoveRight = {
                        catchGameState = catchGameState.copy(
                            playerLane = if (catchGameState.playerLane == 2) 0 else catchGameState.playerLane + 1,
                            playerPose = ArcadePlayerPose.RIGHT,
                            poseUntilMs = System.currentTimeMillis() + 160L
                        )
                    },
                    onRestart = {
                        restartCatchGame()
                    }
                )
            } else {
                ArcadeMemoryGameScreen(
                    state = memoryGameState,
                    onMoveLeft = {
                        memoryGameState = moveMemoryCursor(memoryGameState, -1)
                    },
                    onMoveRight = {
                        memoryGameState = moveMemoryCursor(memoryGameState, 1)
                    },
                    onFlip = {
                        memoryGameState = flipMemoryCard(memoryGameState)
                    },
                    onRestart = {
                        restartMemoryGame()
                    }
                )
            }

            if (arcadeMode != ArcadeMode.HUB && !localGameMayRun) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TamaDark.copy(alpha = 0.96f))
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            sessionStarting -> stringResource(R.string.tama_arcade_session_starting)
                            sessionLease?.status == ArcadeSessionLeaseStatus.UNAVAILABLE ->
                                stringResource(R.string.tama_arcade_reward_unavailable)
                            else -> stringResource(R.string.tama_arcade_session_recovery)
                        },
                        fontFamily = FontFamily.Monospace,
                        color = TamaLight,
                        textAlign = TextAlign.Center
                    )
                    if (sessionRecoveryLoaded && sessionLease?.keepsPetAtArcade == true) {
                        TextButton(onClick = ::leaveArcade, enabled = !sessionCancelling) {
                            Text(stringResource(R.string.tama_arcade_session_cancel))
                        }
                    }
                    if (sessionLease?.status == ArcadeSessionLeaseStatus.UNAVAILABLE) {
                        TextButton(
                            onClick = {
                                sessionLease = null
                                sessionRecoveryUnavailable = false
                                sessionRecoveryAttempt += 1
                            },
                            enabled = !sessionCancelling
                        ) {
                            Text(stringResource(R.string.tama_arcade_session_retry))
                        }
                    }
                }
            }
        }
    }

    catchResult?.let { summary ->
        val request = catchRequest(summary)
        if (summary.receiptStatus == ArcadeReceiptStatus.SUCCEEDED) {
            ArcadeResultDialog(
                summary = summary,
                onPlayAgain = {
                    acknowledgeAnd(request) { restartCatchGame() }
                },
                onBackToHub = {
                    acknowledgeAnd(request) {
                        catchResult = null
                        sessionLease = null
                        arcadeMode = ArcadeMode.HUB
                    }
                },
                onDismiss = {
                    acknowledgeAnd(request) {
                        catchResult = null
                        sessionLease = null
                        arcadeMode = ArcadeMode.HUB
                    }
                }
            )
        } else {
            ArcadePendingResultPanel(
                gameId = "catch",
                status = summary.receiptStatus,
                catches = summary.catches,
                misses = summary.misses,
                totalObjects = summary.totalObjects,
                pairsMatched = 0,
                turnsUsed = 0,
                onRetry = if (summary.receiptStatus == ArcadeReceiptStatus.REJECTED) null else {
                    { submitCatchResult(summary) }
                },
                onCancel = {
                    val lease = sessionLease?.takeIf { it.keepsPetAtArcade }
                    if (lease != null) {
                        cancelLease(lease) { cancelledRequest ->
                            acknowledgeAnd(cancelledRequest) {
                                catchResult = null
                                sessionLease = null
                                arcadeMode = ArcadeMode.HUB
                            }
                        }
                    } else {
                        acknowledgeAnd(request) {
                            catchResult = null
                            sessionLease = null
                            arcadeMode = ArcadeMode.HUB
                        }
                    }
                }
            )
        }
    }

    memoryResult?.let { summary ->
        val request = memoryRequest(summary)
        if (memoryReceiptStatus == ArcadeReceiptStatus.SUCCEEDED) {
            MemoryResultDialog(
                summary = summary,
                receiptStatusLabel = stringResource(arcadeReceiptStatusRes(memoryReceiptStatus)),
                onPlayAgain = {
                    acknowledgeAnd(request) { restartMemoryGame() }
                },
                onBackToHub = {
                    acknowledgeAnd(request) {
                        memoryResult = null
                        sessionLease = null
                        arcadeMode = ArcadeMode.HUB
                    }
                }
            )
        } else {
            ArcadePendingResultPanel(
                gameId = "memory",
                status = memoryReceiptStatus,
                catches = 0,
                misses = 0,
                totalObjects = 8,
                pairsMatched = summary.pairsMatched,
                turnsUsed = summary.turnsUsed,
                onRetry = if (memoryReceiptStatus == ArcadeReceiptStatus.REJECTED) null else {
                    { submitMemoryResult(summary) }
                },
                onCancel = {
                    val lease = sessionLease?.takeIf { it.keepsPetAtArcade }
                    if (lease != null) {
                        cancelLease(lease) { cancelledRequest ->
                            acknowledgeAnd(cancelledRequest) {
                                memoryResult = null
                                sessionLease = null
                                arcadeMode = ArcadeMode.HUB
                            }
                        }
                    } else {
                        acknowledgeAnd(request) {
                            memoryResult = null
                            sessionLease = null
                            arcadeMode = ArcadeMode.HUB
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ArcadeSessionRecoveryPanel(onCancel: () -> Unit) {
    ArcadePanelCard(alpha = 0.94f) {
        Text(
            text = stringResource(R.string.tama_arcade_session_recovery),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = TamaLight
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.tama_arcade_session_cancel))
        }
    }
}

@Composable
private fun ArcadeSessionUnavailablePanel(onRetry: () -> Unit) {
    ArcadePanelCard(alpha = 0.94f) {
        Text(
            text = stringResource(R.string.tama_arcade_session_recovery_unavailable),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = TamaLight
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.tama_arcade_session_retry))
        }
    }
}

@Composable
private fun ArcadePendingResultPanel(
    gameId: String,
    status: ArcadeReceiptStatus,
    catches: Int,
    misses: Int,
    totalObjects: Int,
    pairsMatched: Int,
    turnsUsed: Int,
    onRetry: (() -> Unit)?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TamaDark.copy(alpha = 0.97f))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.tama_arcade_session_pending),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = TamaLight,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(arcadeReceiptStatusRes(status)),
            fontFamily = FontFamily.Monospace,
            color = TamaMutedText,
            textAlign = TextAlign.Center
        )
        if (gameId == "catch") {
            Text(
                text = stringResource(R.string.tama_arcade_result_summary, catches, misses, totalObjects),
                fontFamily = FontFamily.Monospace,
                color = TamaLight,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = stringResource(R.string.tama_arcade_memory_result_pairs, pairsMatched),
                fontFamily = FontFamily.Monospace,
                color = TamaLight,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.tama_arcade_memory_result_turns, turnsUsed),
                fontFamily = FontFamily.Monospace,
                color = TamaLight,
                textAlign = TextAlign.Center
            )
        }
        onRetry?.let { retry ->
            FilledTonalButton(onClick = retry, enabled = status != ArcadeReceiptStatus.SUBMITTING) {
                Text(stringResource(R.string.tama_arcade_session_retry))
            }
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.tama_arcade_session_cancel))
        }
    }
}

private fun arcadeSessionMayRun(lease: ArcadeSessionLease): Boolean = when (lease.status) {
    ArcadeSessionLeaseStatus.QUEUED,
    ArcadeSessionLeaseStatus.RUNNING -> true
    ArcadeSessionLeaseStatus.UNAVAILABLE,
    ArcadeSessionLeaseStatus.RECONCILE_REQUIRED,
    ArcadeSessionLeaseStatus.TERMINAL -> false
}

private fun ArcadeSessionLease?.canSubmitArcadeSession(): Boolean = this != null && when (status) {
    ArcadeSessionLeaseStatus.QUEUED,
    ArcadeSessionLeaseStatus.RUNNING -> true
    ArcadeSessionLeaseStatus.UNAVAILABLE,
    ArcadeSessionLeaseStatus.RECONCILE_REQUIRED,
    ArcadeSessionLeaseStatus.TERMINAL -> false
}

private fun ArcadeReceiptStatus.isTerminal(): Boolean = when (this) {
    ArcadeReceiptStatus.SUCCEEDED,
    ArcadeReceiptStatus.REJECTED,
    ArcadeReceiptStatus.FAILED -> true
    ArcadeReceiptStatus.SUBMITTING,
    ArcadeReceiptStatus.QUEUED,
    ArcadeReceiptStatus.UNAVAILABLE -> false
}

private suspend fun submitArcadeSession(
    bridge: ArcadeWorldActionBridge,
    request: ArcadeSessionRequest
): ArcadeSubmitOutcome {
    return try {
        val receipt = bridge.submit(request)
        if (receipt.sessionId == request.sessionId) {
            ArcadeSubmitOutcome(
                receipt = receipt,
                retryable = receipt.status == ArcadeReceiptStatus.SUBMITTING ||
                    receipt.status == ArcadeReceiptStatus.QUEUED ||
                    receipt.status == ArcadeReceiptStatus.FAILED ||
                    receipt.status == ArcadeReceiptStatus.UNAVAILABLE
            )
        } else {
            ArcadeSubmitOutcome(
                ArcadeSessionReceipt(request.sessionId, status = ArcadeReceiptStatus.FAILED),
                retryable = true
            )
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ArcadeSubmitOutcome(
            ArcadeSessionReceipt(request.sessionId, status = ArcadeReceiptStatus.FAILED),
            retryable = true
        )
    }
}

private fun arcadeReceiptStatusRes(status: ArcadeReceiptStatus): Int = when (status) {
    ArcadeReceiptStatus.SUBMITTING -> R.string.tama_arcade_reward_submitting
    ArcadeReceiptStatus.QUEUED -> R.string.tama_arcade_reward_queued
    ArcadeReceiptStatus.SUCCEEDED -> R.string.tama_arcade_reward_saved
    ArcadeReceiptStatus.REJECTED -> R.string.tama_arcade_reward_rejected
    ArcadeReceiptStatus.FAILED -> R.string.tama_arcade_reward_failed
    ArcadeReceiptStatus.UNAVAILABLE -> R.string.tama_arcade_reward_unavailable
}

@Composable
private fun ArcadeHub(
    canStartGame: Boolean,
    onPlayCatchGame: () -> Unit,
    onPlayMemoryGame: () -> Unit
) {
    Column(
        modifier = Modifier.walkthroughTarget("tama.arcade"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ArcadePanelCard {
            Text(
                text = stringResource(R.string.tama_arcade_game_title),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TamaLight
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tama_arcade_game_desc),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TamaLight.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            ArcadeGamePreview(ARCade_BACKGROUND)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tama_arcade_controls_hint),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaLight.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(onClick = onPlayCatchGame, enabled = canStartGame) {
                Text(stringResource(R.string.tama_arcade_play_now))
            }
        }

        ArcadePanelCard(alpha = 0.9f) {
            Text(
                text = stringResource(R.string.tama_arcade_memory_game_title),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TamaLight
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tama_arcade_memory_game_desc),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TamaLight.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            ArcadeGamePreview(
                assetPath = ARCade_MEMORY_BACKGROUND,
                previewTextRes = R.string.tama_arcade_memory_preview_text
            )
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(onClick = onPlayMemoryGame, enabled = canStartGame) {
                Text(stringResource(R.string.tama_arcade_memory_play_now))
            }
        }
    }
}

@Composable
private fun ArcadeGamePreview(
    assetPath: String,
    previewTextRes: Int = R.string.tama_arcade_preview_text
) {
    val previewText = stringResource(previewTextRes)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(2.dp, TamaLight.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
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
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f))
        )
        Text(
            text = previewText,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            color = TamaLight,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ArcadePanelCard(
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TamaDark.copy(alpha = alpha))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun CatchGameScreen(
    pet: TamaPet,
    state: ArcadeCatchGameState,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRestart: () -> Unit
) {
    var uiNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAtMs, state.introUntilMs, state.finished) {
        while (isActive && !state.finished) {
            uiNowMs = System.currentTimeMillis()
            delay(50L)
        }
    }
    val gameControlsEnabled = !state.finished && uiNowMs >= state.introUntilMs
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ArcadeBoard(
                pet = pet,
                state = state,
                nowMs = uiNowMs,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onRestart) {
                    Text(stringResource(R.string.tama_arcade_restart))
                }
                ArcadeHudBox(
                    score = state.catches,
                    timeLeftSeconds = maxOf(0L, state.durationMs - state.elapsedMs) / 1000L
                )
            }
        }

        ArcadePanelCard(
            alpha = 0.85f,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FilledTonalButton(onClick = onMoveLeft, enabled = gameControlsEnabled) {
                    Text("←")
                }
                FilledTonalButton(onClick = onMoveRight, enabled = gameControlsEnabled) {
                    Text("→")
                }
            }
        }
    }
}

@Composable
private fun ArcadeBoard(
    pet: TamaPet,
    state: ArcadeCatchGameState,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = Modifier
            .then(modifier)
            .clip(RoundedCornerShape(18.dp))
            .border(3.dp, TamaLight.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
    ) {
        val boardWidth = maxWidth
        val boardHeight = maxHeight
        val laneWidth = boardWidth / 3f
        val laneSeparatorX = listOf(laneWidth, laneWidth * 2f)
        val topPadding = 18.dp
        val fallSpan = boardHeight - 146.dp
        val spriteSize = 44.dp
        val playerSpriteSize = 104.dp
        val introActive = nowMs < state.introUntilMs

        AsyncImage(
            model = "file:///android_asset/$ARCade_BACKGROUND",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxSize()) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                }
            }

            laneSeparatorX.forEach { separator ->
                Box(
                    modifier = Modifier
                        .offset(x = separator - 1.dp, y = 0.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(TamaLight.copy(alpha = 0.38f))
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(Color.Black.copy(alpha = 0.12f))
            )

            state.objects.forEach { obj ->
                if (obj.resolved) return@forEach
                val progress = ((state.elapsedMs - obj.spawnAtMs).toFloat() / obj.fallDurationMs.toFloat())
                    .coerceIn(0f, 1f)
                if (introActive || state.elapsedMs < obj.spawnAtMs) return@forEach
                val laneLeft = laneWidth * obj.lane
                val x = laneLeft + laneWidth / 2f - spriteSize / 2f
                val y = topPadding + (fallSpan * progress)
                ArcadeObjectSprite(
                    assetPath = obj.kind.assetPath,
                    modifier = Modifier.offset(x = x, y = y).size(spriteSize)
                )
            }

            val playerX = laneWidth * state.playerLane + laneWidth / 2f - playerSpriteSize / 2f
            val playerY = boardHeight - playerSpriteSize - 24.dp
            ArcadePlayerSprite(
                pet = pet,
                pose = state.playerPose,
                modifier = Modifier.offset(x = playerX, y = playerY).size(playerSpriteSize)
            )

            if (state.playerLane in 0..2) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.26f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tama_arcade_lane_counter, state.playerLane + 1),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TamaLight
                    )
                }
            }

            if (introActive && !state.finished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.38f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tama_arcade_intro),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TamaLight
                    )
                }
            }
        }
    }
}

@Composable
private fun ArcadeObjectSprite(
    assetPath: String,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = "file:///android_asset/$assetPath",
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun ArcadePlayerSprite(
    pet: TamaPet,
    pose: ArcadePlayerPose,
    modifier: Modifier = Modifier
) {
    val speciesLine = remember(pet.species, pet.genetics.bodyStyle) {
        PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
    }
    val spriteState = remember(pose, pet.isSleeping) {
        mapPetActionToSpriteState(pose.petAction, pet.isSleeping)
    }
    TamaFrameAnimation(
        speciesLine = speciesLine,
        stage = pet.stage,
        spriteState = if (pet.stage == GrowthStage.EGG) {
            PetSpriteState.IDLE
        } else {
            spriteState
        },
        frozen = pet.cycleFrozen,
        modifier = modifier
    )
}

@Composable
private fun ArcadeHudBox(
    score: Int,
    timeLeftSeconds: Long,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(164.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, TamaLight.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.tama_arcade_hud_score, score),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaLight,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = stringResource(R.string.tama_arcade_hud_time, timeLeftSeconds),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaLight,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun ArcadeResultDialog(
    summary: ArcadeResult,
    onPlayAgain: () -> Unit,
    onBackToHub: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when {
        summary.coins >= 50 -> R.string.tama_arcade_result_perfect
        summary.coins > 0 -> R.string.tama_arcade_result_won
        else -> R.string.tama_arcade_result_lost
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(
                        R.string.tama_arcade_result_summary,
                        summary.catches,
                        summary.misses,
                        summary.totalObjects
                    ),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_result_score, summary.score),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_result_reward, summary.coins),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_result_happiness, summary.happiness),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(arcadeReceiptStatusRes(summary.receiptStatus)),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
                if (summary.receiptStatus == ArcadeReceiptStatus.SUCCEEDED && summary.coins > 0) {
                    Text(
                        stringResource(R.string.tama_arcade_result_rewarded),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TamaMutedText
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPlayAgain) {
                Text(stringResource(R.string.tama_arcade_play_again))
            }
        },
        dismissButton = {
            TextButton(onClick = onBackToHub) {
                Text(stringResource(R.string.tama_arcade_back_to_hub))
            }
        }
    )
}

private fun startCatchGame(): ArcadeCatchGameState {
    val now = System.currentTimeMillis()
    val introMs = 1000L
    val durationMs = Random.nextLong(12_200L, 14_901L)
    val objects = mutableListOf<ArcadeFallingObject>()
    var spawnCursor = 0L
    var spawnIntervalMs = Random.nextLong(900L, 1_060L)
    var index = 0
    var previousLane = 1
    var secondPreviousLane: Int? = null
    while (spawnCursor < durationMs - 820L) {
        val progress = (spawnCursor.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val fallDurationMs = (1_130f - (progress * 350f)).roundToInt().coerceIn(700, 1_130).toLong()
        if (spawnCursor + fallDurationMs >= durationMs - 120L) {
            break
        }
        val lane = chooseCatchLane(
            previousLane = previousLane,
            secondPreviousLane = secondPreviousLane,
            progress = progress
        )
        objects += ArcadeFallingObject(
            id = index,
            lane = lane,
            kind = ArcadeFallingKind.entries.random(),
            spawnAtMs = spawnCursor,
            fallDurationMs = fallDurationMs
        )
        secondPreviousLane = previousLane
        previousLane = lane
        index++
        spawnCursor += spawnIntervalMs
        spawnIntervalMs = (spawnIntervalMs * 0.93f).roundToInt().coerceIn(460, 1_060).toLong()
    }
    val totalObjects = objects.size
    return ArcadeCatchGameState(
        startedAtMs = now,
        introUntilMs = now + introMs,
        durationMs = durationMs,
        totalObjects = totalObjects,
        elapsedMs = 0L,
        playerLane = 1,
        playerPose = ArcadePlayerPose.IDLE,
        poseUntilMs = 0L,
        objects = objects,
        catches = 0,
        misses = 0,
        finished = false
    )
}

fun arcadeHappinessForCoins(coins: Int): Int = when {
    coins >= 50 -> 12
    coins > 0 -> 10
    else -> 8
}

private fun chooseCatchLane(
    previousLane: Int,
    secondPreviousLane: Int?,
    progress: Float
): Int {
    val lanes = listOf(0, 1, 2)
    val avoidOnlyPrevious = lanes.filterNot { it == previousLane }
    val avoidRecent = lanes.filterNot { it == previousLane || it == secondPreviousLane }
    val shouldForceSwitch = when {
        secondPreviousLane == null -> false
        progress < 0.35f -> false
        progress < 0.72f -> Random.nextFloat() < 0.5f
        else -> Random.nextFloat() < 0.75f
    }
    val candidates = when {
        shouldForceSwitch && avoidRecent.isNotEmpty() -> avoidRecent
        avoidOnlyPrevious.isNotEmpty() -> avoidOnlyPrevious
        else -> lanes
    }
    return candidates.random()
}

private fun advanceCatchGame(state: ArcadeCatchGameState, nowMs: Long): ArcadeCatchGameState {
    if (state.finished) return state
    if (nowMs < state.introUntilMs) {
        return state.copy(elapsedMs = 0L)
    }
    val elapsedMs = (nowMs - state.introUntilMs).coerceAtLeast(0L)
    val resolvedObjects = state.objects.map { objectState ->
        if (objectState.resolved) {
            objectState
        } else if (elapsedMs >= objectState.spawnAtMs + objectState.fallDurationMs) {
            val caught = objectState.lane == state.playerLane
            objectState.copy(resolved = true, caught = caught)
        } else {
            objectState
        }
    }

    var catches = 0
    var misses = 0
    resolvedObjects.forEach { obj ->
        if (obj.resolved) {
            if (obj.caught) catches++ else misses++
        }
    }

    val poseStillActive = nowMs < state.poseUntilMs
    val pose = if (poseStillActive) state.playerPose else ArcadePlayerPose.IDLE
    val finished = elapsedMs >= state.durationMs && resolvedObjects.all { it.resolved }
    val resolvedThisTick = resolvedObjects.zip(state.objects).any { (updated, original) ->
        !original.resolved && updated.resolved
    }
    val caughtThisTick = resolvedObjects.zip(state.objects).any { (updated, original) ->
        !original.resolved && updated.resolved && updated.caught
    }
    val nextPose = when {
        finished -> if (catches > 0) ArcadePlayerPose.WIN else ArcadePlayerPose.LOSE
        resolvedThisTick -> if (caughtThisTick) ArcadePlayerPose.CATCH else ArcadePlayerPose.MISS
        else -> pose
    }
    val nextPoseUntil = when {
        finished -> state.poseUntilMs
        resolvedThisTick -> nowMs + 220L
        else -> state.poseUntilMs
    }

    return state.copy(
        elapsedMs = elapsedMs.coerceAtMost(state.durationMs),
        objects = resolvedObjects,
        catches = catches,
        misses = misses,
        playerPose = nextPose,
        poseUntilMs = nextPoseUntil,
        finished = finished
    )
}

private fun catchGameCoins(catches: Int, totalObjects: Int): Int = when {
    catches <= 4 -> 0
    catches <= 8 -> 10
    catches <= 12 -> 20
    catches <= 16 -> 30
    catches >= totalObjects && totalObjects > 0 -> 50
    catches >= 17 -> 40
    else -> 0
}
