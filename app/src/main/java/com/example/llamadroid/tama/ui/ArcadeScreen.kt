package com.example.llamadroid.tama.ui

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.PetSpriteState
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.resolvePetSpriteAssetPath
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.ui.navigation.Screen
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

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
    val happiness: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArcadeScreen(
    navController: NavController,
    gameEngine: TamaGameEngine,
    pet: TamaPet
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var arcadeMode by rememberSaveable { mutableStateOf(ArcadeMode.HUB) }
    var catchGameState by remember { mutableStateOf(ArcadeCatchGameState()) }
    var catchResult by remember { mutableStateOf<ArcadeResult?>(null) }
    var catchRewardClaimed by remember { mutableStateOf(false) }
    var memoryGameState by remember { mutableStateOf(startMemoryGame()) }
    var memoryResult by remember { mutableStateOf<MemoryGameResult?>(null) }
    var memoryRewardClaimed by remember { mutableStateOf(false) }
    var tickToken by rememberSaveable { mutableStateOf(0L) }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(arcadeMode, tickToken) {
        if (arcadeMode != ArcadeMode.CATCH) return@LaunchedEffect
        while (isActive && !catchGameState.finished) {
            delay(50L)
            val now = System.currentTimeMillis()
            catchGameState = advanceCatchGame(catchGameState, now)
        }
    }

    LaunchedEffect(arcadeMode, tickToken) {
        if (arcadeMode != ArcadeMode.MEMORY) return@LaunchedEffect
        while (isActive && !memoryGameState.finished) {
            delay(50L)
            val now = System.currentTimeMillis()
            memoryGameState = advanceMemoryGame(memoryGameState, now)
        }
    }

    LaunchedEffect(catchGameState.finished, catchRewardClaimed, arcadeMode) {
        if (arcadeMode != ArcadeMode.CATCH || !catchGameState.finished || catchRewardClaimed) return@LaunchedEffect
        val reward = catchGameCoins(catchGameState.catches, catchGameState.totalObjects)
        val summary = ArcadeResult(
            catches = catchGameState.catches,
            misses = catchGameState.misses,
            totalObjects = catchGameState.totalObjects,
            score = catchGameState.catches,
            coins = reward,
            happiness = arcadeHappinessForCoins(reward)
        )
        catchRewardClaimed = true
        catchResult = summary
        coroutineScope.launch {
            gameEngine.awardMoney(
                reward.toLong(),
                resources.getString(R.string.tama_event_arcade_reward, reward, resources.getString(R.string.tama_arcade_game_title))
            )
            gameEngine.awardHappiness(
                summary.happiness.toFloat(),
                resources.getString(R.string.tama_event_arcade_happiness_reward, summary.happiness, resources.getString(R.string.tama_arcade_game_title))
            )
        }
    }

    LaunchedEffect(memoryGameState.finished, memoryRewardClaimed, arcadeMode) {
        if (arcadeMode != ArcadeMode.MEMORY || !memoryGameState.finished || memoryRewardClaimed) return@LaunchedEffect
        val summary = buildMemoryGameResult(memoryGameState) ?: return@LaunchedEffect
        memoryRewardClaimed = true
        memoryResult = summary
        coroutineScope.launch {
            gameEngine.awardMoney(
                summary.coins.toLong(),
                resources.getString(R.string.tama_event_arcade_reward, summary.coins, resources.getString(R.string.tama_arcade_memory_game_title))
            )
            gameEngine.awardHappiness(
                summary.happiness.toFloat(),
                resources.getString(R.string.tama_event_arcade_happiness_reward, summary.happiness, resources.getString(R.string.tama_arcade_memory_game_title))
            )
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tama_arcade_title), fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
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

                    ArcadeHub(
                        onPlayCatchGame = {
                            catchRewardClaimed = false
                            catchResult = null
                            catchGameState = startCatchGame()
                            tickToken = System.currentTimeMillis()
                            arcadeMode = ArcadeMode.CATCH
                        },
                        onPlayMemoryGame = {
                            memoryRewardClaimed = false
                            memoryResult = null
                            memoryGameState = startMemoryGame()
                            tickToken = System.currentTimeMillis()
                            arcadeMode = ArcadeMode.MEMORY
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
                        catchRewardClaimed = false
                        catchResult = null
                        catchGameState = startCatchGame()
                        tickToken = System.currentTimeMillis()
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
                        memoryRewardClaimed = false
                        memoryResult = null
                        memoryGameState = startMemoryGame()
                        tickToken = System.currentTimeMillis()
                    }
                )
            }
        }
    }

    catchResult?.let { summary ->
        ArcadeResultDialog(
            summary = summary,
            onPlayAgain = {
                catchRewardClaimed = false
                catchResult = null
                catchGameState = startCatchGame()
                tickToken = System.currentTimeMillis()
                arcadeMode = ArcadeMode.CATCH
            },
            onBackToHub = {
                catchResult = null
                arcadeMode = ArcadeMode.HUB
            },
            onDismiss = { catchResult = null }
        )
    }

    memoryResult?.let { summary ->
        MemoryResultDialog(
            summary = summary,
            onPlayAgain = {
                memoryRewardClaimed = false
                memoryResult = null
                memoryGameState = startMemoryGame()
                tickToken = System.currentTimeMillis()
                arcadeMode = ArcadeMode.MEMORY
            },
            onBackToHub = {
                memoryResult = null
                arcadeMode = ArcadeMode.HUB
            }
        )
    }
}

@Composable
private fun ArcadeHub(
    onPlayCatchGame: () -> Unit,
    onPlayMemoryGame: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            FilledTonalButton(onClick = onPlayCatchGame) {
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
            FilledTonalButton(onClick = onPlayMemoryGame) {
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
    modifier: Modifier = Modifier
) {
    val speciesLine = remember(pet.species, pet.genetics.bodyStyle) {
        PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
    }
    val assetPath = remember(speciesLine, pet.stage) {
        resolvePetSpriteAssetPath(
            speciesLine = speciesLine,
            stage = pet.stage,
            state = PetSpriteState.IDLE,
            frameIndex = 0
        )
    }
    AsyncImage(
        model = "file:///android_asset/$assetPath",
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
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
                if (summary.coins > 0) {
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
