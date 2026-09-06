package com.example.llamadroid.tama.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

private const val MEMORY_BACKGROUND = "tama/minigames/memory/background.png"
private const val MEMORY_CARD_BACK = "tama/minigames/memory/card_back.png"
private const val MEMORY_ICON_STAR = "tama/minigames/memory/icon_star.png"
private const val MEMORY_ICON_HEART = "tama/minigames/memory/icon_heart.png"
private const val MEMORY_ICON_FRUIT = "tama/minigames/memory/icon_fruit.png"
private const val MEMORY_ICON_COIN = "tama/minigames/memory/icon_coin.png"
private const val MEMORY_ICON_GEM = "tama/minigames/memory/icon_gem.png"
private const val MEMORY_ICON_MOON = "tama/minigames/memory/icon_moon.png"
private const val MEMORY_ICON_FLOWER = "tama/minigames/memory/icon_flower.png"
private const val MEMORY_ICON_SHELL = "tama/minigames/memory/icon_shell.png"
private const val MEMORY_MASCOT_IDLE = "tama/minigames/memory/mascot_idle.png"
private const val MEMORY_MASCOT_THINKING = "tama/minigames/memory/mascot_thinking.png"
private const val MEMORY_MASCOT_HAPPY = "tama/minigames/memory/mascot_happy.png"
private const val MEMORY_MASCOT_SAD = "tama/minigames/memory/mascot_sad.png"
private const val MEMORY_SPARKLE = MEMORY_ICON_STAR

private const val MEMORY_GRID_COLUMNS = 4
private const val MEMORY_GRID_ROWS = 4
private const val MEMORY_TOTAL_TURNS = 12
private const val MEMORY_INTRO_MS = 1000L
private const val MEMORY_MATCH_REVEAL_MS = 220L
private const val MEMORY_MISMATCH_REVEAL_MS = 650L
private const val MEMORY_FEEDBACK_MS = 260L

enum class MemoryCardKind(val assetPath: String, val labelRes: Int) {
    STAR(MEMORY_ICON_STAR, R.string.tama_arcade_memory_icon_star),
    HEART(MEMORY_ICON_HEART, R.string.tama_arcade_memory_icon_heart),
    FRUIT(MEMORY_ICON_FRUIT, R.string.tama_arcade_memory_icon_fruit),
    COIN(MEMORY_ICON_COIN, R.string.tama_arcade_memory_icon_coin),
    GEM(MEMORY_ICON_GEM, R.string.tama_arcade_memory_icon_gem),
    MOON(MEMORY_ICON_MOON, R.string.tama_arcade_memory_icon_moon),
    FLOWER(MEMORY_ICON_FLOWER, R.string.tama_arcade_memory_icon_flower),
    SHELL(MEMORY_ICON_SHELL, R.string.tama_arcade_memory_icon_shell)
}

enum class MemoryTurnFeedback {
    NONE,
    MATCH,
    MISMATCH
}

enum class MemoryMascotPose(val assetPath: String) {
    IDLE(MEMORY_MASCOT_IDLE),
    THINKING(MEMORY_MASCOT_THINKING),
    HAPPY(MEMORY_MASCOT_HAPPY),
    SAD(MEMORY_MASCOT_SAD),
    CHEER(MEMORY_MASCOT_HAPPY)
}

data class MemoryCard(
    val id: Int,
    val kind: MemoryCardKind,
    val faceUp: Boolean = false,
    val matched: Boolean = false
)

data class MemoryGameState(
    val cards: List<MemoryCard>,
    val cursorIndex: Int = 0,
    val firstSelectionIndex: Int? = null,
    val secondSelectionIndex: Int? = null,
    val turnsUsed: Int = 0,
    val pairsMatched: Int = 0,
    val score: Int = 0,
    val maxTurns: Int = MEMORY_TOTAL_TURNS,
    val introUntilMs: Long = 0L,
    val resolveAtMs: Long? = null,
    val feedbackStartedAtMs: Long = 0L,
    val feedbackUntilMs: Long = 0L,
    val lastFeedback: MemoryTurnFeedback = MemoryTurnFeedback.NONE,
    val finished: Boolean = false
) {
    val totalPairs: Int get() = cards.size / 2
    val turnsRemaining: Int get() = (maxTurns - turnsUsed).coerceAtLeast(0)
}

data class MemoryGameResult(
    val pairsMatched: Int,
    val turnsUsed: Int,
    val score: Int,
    val coins: Int,
    val happiness: Int,
    val perfectClear: Boolean
)

fun startMemoryGame(
    nowMs: Long = System.currentTimeMillis(),
    seed: Long = Random.nextLong()
): MemoryGameState {
    val deck = buildList {
        repeat(2) {
            add(MemoryCardKind.STAR)
            add(MemoryCardKind.HEART)
            add(MemoryCardKind.FRUIT)
            add(MemoryCardKind.COIN)
            add(MemoryCardKind.GEM)
            add(MemoryCardKind.MOON)
            add(MemoryCardKind.FLOWER)
            add(MemoryCardKind.SHELL)
        }
    }.shuffled(Random(seed))

    val cards = deck.mapIndexed { index, kind ->
        MemoryCard(id = index, kind = kind)
    }

    return MemoryGameState(
        cards = cards,
        introUntilMs = nowMs + MEMORY_INTRO_MS
    )
}

fun moveMemoryCursor(state: MemoryGameState, delta: Int): MemoryGameState {
    if (!state.canAcceptInput(System.currentTimeMillis())) return state
    val cardCount = state.cards.size
    if (cardCount == 0) return state
    val nextIndex = ((state.cursorIndex + delta) % cardCount + cardCount) % cardCount
    return state.copy(cursorIndex = nextIndex)
}

fun flipMemoryCard(state: MemoryGameState, nowMs: Long = System.currentTimeMillis()): MemoryGameState {
    if (!state.canAcceptInput(nowMs)) return state
    val selectedIndex = state.cursorIndex
    val selectedCard = state.cards.getOrNull(selectedIndex) ?: return state
    if (selectedCard.faceUp || selectedCard.matched) return state

    val firstSelection = state.firstSelectionIndex
    return when {
        firstSelection == null -> state.copy(
            cards = state.cards.updated(selectedIndex) { it.copy(faceUp = true) },
            firstSelectionIndex = selectedIndex,
            lastFeedback = MemoryTurnFeedback.NONE
        )

        firstSelection == selectedIndex -> state

        else -> {
            val secondSelection = selectedIndex
            val isMatch = state.cards[firstSelection].kind == selectedCard.kind
            val resolveDelay = if (isMatch) MEMORY_MATCH_REVEAL_MS else MEMORY_MISMATCH_REVEAL_MS
            state.copy(
                cards = state.cards.updated(secondSelection) { it.copy(faceUp = true) },
                secondSelectionIndex = secondSelection,
                resolveAtMs = nowMs + resolveDelay,
                lastFeedback = MemoryTurnFeedback.NONE
            )
        }
    }
}

fun advanceMemoryGame(state: MemoryGameState, nowMs: Long = System.currentTimeMillis()): MemoryGameState {
    if (state.finished) return state

    if (state.resolveAtMs == null) {
        return state.copy(
            finished = state.pairsMatched >= state.totalPairs || state.turnsUsed >= state.maxTurns
        )
    }

    if (nowMs < state.resolveAtMs) return state

    val firstIndex = state.firstSelectionIndex ?: return state.copy(resolveAtMs = null)
    val secondIndex = state.secondSelectionIndex ?: return state.copy(resolveAtMs = null)
    val firstCard = state.cards[firstIndex]
    val secondCard = state.cards[secondIndex]
    val isMatch = firstCard.kind == secondCard.kind
    val updatedCards = if (isMatch) {
        state.cards.updated(firstIndex) { it.copy(faceUp = true, matched = true) }
            .updated(secondIndex) { it.copy(faceUp = true, matched = true) }
    } else {
        state.cards.updated(firstIndex) { it.copy(faceUp = false) }
            .updated(secondIndex) { it.copy(faceUp = false) }
    }
    val nextPairsMatched = if (isMatch) state.pairsMatched + 1 else state.pairsMatched
    val nextTurnsUsed = state.turnsUsed + 1
    val nextScore = nextPairsMatched * 10
    val feedback = if (isMatch) MemoryTurnFeedback.MATCH else MemoryTurnFeedback.MISMATCH
    val finished = nextPairsMatched >= state.totalPairs || nextTurnsUsed >= state.maxTurns
    return state.copy(
        cards = updatedCards,
        firstSelectionIndex = null,
        secondSelectionIndex = null,
        turnsUsed = nextTurnsUsed,
        pairsMatched = nextPairsMatched,
        score = nextScore,
        resolveAtMs = null,
        feedbackStartedAtMs = nowMs,
        feedbackUntilMs = nowMs + MEMORY_FEEDBACK_MS,
        lastFeedback = feedback,
        finished = finished
    )
}

fun buildMemoryGameResult(state: MemoryGameState): MemoryGameResult? {
    if (!state.finished) return null
    val perfectClear = state.pairsMatched >= state.totalPairs && state.turnsUsed < state.maxTurns
    return MemoryGameResult(
        pairsMatched = state.pairsMatched,
        turnsUsed = state.turnsUsed,
        score = state.score,
        coins = memoryGameCoins(state.pairsMatched, state.turnsUsed, state.maxTurns),
        happiness = memoryGameHappiness(state.pairsMatched, perfectClear),
        perfectClear = perfectClear
    )
}

fun memoryGameCoins(pairsMatched: Int, turnsUsed: Int, maxTurns: Int = MEMORY_TOTAL_TURNS): Int = when {
    pairsMatched <= 0 -> 0
    pairsMatched >= 8 && turnsUsed < maxTurns -> 50
    else -> (pairsMatched.coerceAtMost(8) * 5).coerceAtMost(40)
}

fun memoryGameHappiness(pairsMatched: Int, perfectClear: Boolean): Int = when {
    perfectClear -> 12
    pairsMatched > 0 -> 10
    else -> 8
}

private fun MemoryGameState.canAcceptInput(nowMs: Long): Boolean =
    !finished && nowMs >= introUntilMs && (resolveAtMs == null || nowMs >= resolveAtMs)

private fun <T> List<T>.updated(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) transform(item) else item }

@Composable
fun ArcadeMemoryGameScreen(
    state: MemoryGameState,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onFlip: () -> Unit,
    onRestart: () -> Unit
) {
    var uiNowMs by remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(state.introUntilMs, state.resolveAtMs, state.feedbackUntilMs, state.finished) {
        while (isActive && !state.finished) {
            uiNowMs = System.currentTimeMillis()
            delay(50L)
        }
    }
    val nowMs = uiNowMs
    val controlsEnabled = state.canAcceptInput(nowMs)
    val selectedIndices = remember(state.firstSelectionIndex, state.secondSelectionIndex) {
        setOfNotNull(state.firstSelectionIndex, state.secondSelectionIndex)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        val cardSpacing = 8.dp
        val availableWidth = maxWidth - 12.dp
        val availableHeight = maxHeight - 220.dp
        val widthForCards = (availableWidth - cardSpacing * (MEMORY_GRID_COLUMNS - 1)) / MEMORY_GRID_COLUMNS.toFloat()
        val heightForCards = (availableHeight - cardSpacing * (MEMORY_GRID_ROWS - 1)) / MEMORY_GRID_ROWS.toFloat()
        val cardSize = widthForCards.coerceAtMost(heightForCards).coerceIn(50.dp, 88.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, TamaLight.copy(alpha = 0.32f), RoundedCornerShape(20.dp))
        ) {
            AsyncImage(
                model = "file:///android_asset/$MEMORY_BACKGROUND",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.14f))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MemoryMascotSprite(
                            pose = remember(state, nowMs) { memoryMascotPose(state, nowMs) },
                            modifier = Modifier.size(68.dp)
                        )
                        MemoryTurnsBadge(turnsLeft = state.turnsRemaining)
                    }

                    Column(
                        modifier = Modifier.align(Alignment.TopEnd),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(onClick = onRestart) {
                            Text(stringResource(R.string.tama_arcade_restart))
                        }
                        MemoryHudBox(score = state.score)
                    }

                    if (!state.finished && nowMs < state.introUntilMs) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tama_arcade_memory_intro),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = TamaLight,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    MemoryBoard(
                        state = state,
                        nowMs = nowMs,
                        cardSize = cardSize,
                        cardSpacing = cardSpacing,
                        selectedIndices = selectedIndices
                    )
                }

                MemoryPanelCard(
                    alpha = 0.88f,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(onClick = onMoveLeft, enabled = controlsEnabled) {
                            Text("←")
                        }
                        FilledTonalButton(onClick = onFlip, enabled = controlsEnabled) {
                            Text(stringResource(R.string.tama_arcade_memory_flip))
                        }
                        FilledTonalButton(onClick = onMoveRight, enabled = controlsEnabled) {
                            Text("→")
                        }
                    }
                }
            }
        }

        if (!state.finished && nowMs >= state.feedbackStartedAtMs && nowMs < state.feedbackUntilMs) {
            MemoryFeedbackOverlay(
                state = state
            )
        }
    }
}

@Composable
private fun MemoryPanelCard(
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
private fun MemoryBoard(
    state: MemoryGameState,
    nowMs: Long,
    cardSize: Dp,
    cardSpacing: Dp,
    selectedIndices: Set<Int>,
    modifier: Modifier = Modifier
) {
    val rows = remember(state.cards) {
        state.cards.chunked(MEMORY_GRID_COLUMNS)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(cardSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEachIndexed { columnIndex, card ->
                    val index = rowIndex * MEMORY_GRID_COLUMNS + columnIndex
                    MemoryCardTile(
                        card = card,
                        isSelected = state.cursorIndex == index,
                        cardSize = cardSize,
                        isFeedbackTarget = index in selectedIndices,
                        feedback = state.lastFeedback,
                        feedbackActive = nowMs < state.feedbackUntilMs
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCardTile(
    card: MemoryCard,
    isSelected: Boolean,
    cardSize: Dp,
    isFeedbackTarget: Boolean,
    feedback: MemoryTurnFeedback,
    feedbackActive: Boolean
) {
    val faceUpTarget = card.faceUp || card.matched
    val faceProgress by animateFloatAsState(
        targetValue = if (faceUpTarget) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "memory_card_flip"
    )
    val selectedPulse by animateFloatAsState(
        targetValue = if (isSelected && !faceUpTarget && !card.matched) 1f else 0.58f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "memory_selected_pulse"
    )
    val density = LocalDensity.current
    val rotation = 180f * (1f - faceProgress)
    val wobble = memoryWobble(
        active = feedbackActive && isFeedbackTarget && feedback == MemoryTurnFeedback.MISMATCH,
        matched = feedbackActive && isFeedbackTarget && feedback == MemoryTurnFeedback.MATCH
    )

    Box(
        modifier = Modifier
            .size(cardSize)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density.density
                translationX = wobble
                scaleX = if (isSelected && !card.matched) 1f + ((selectedPulse - 0.58f) * 0.2f) else 1f
                scaleY = if (isSelected && !card.matched) 1f + ((selectedPulse - 0.58f) * 0.2f) else 1f
            }
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 5.dp else 1.dp,
                color = if (isSelected) Color(0xFFFFF176) else Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        if (isSelected && !card.matched) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFF176).copy(alpha = 0.24f))
            )
        }
        if (faceProgress < 0.5f) {
            AsyncImage(
                model = "file:///android_asset/$MEMORY_CARD_BACK",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF5EFD9))
                    .border(2.dp, Color(0xFF8C6B37), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                AsyncImage(
                    model = "file:///android_asset/${card.kind.assetPath}",
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(cardSize * 0.56f),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                if (card.matched) {
                    AsyncImage(
                        model = "file:///android_asset/$MEMORY_SPARKLE",
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(cardSize * 0.24f),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
            }
        }

        if (isSelected && !card.matched) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 3.dp,
                        color = Color(0xFFFFF176).copy(alpha = 0.98f),
                        shape = RoundedCornerShape(12.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFFF176))
            )
        }
    }
}

@Composable
private fun MemoryHudBox(
    score: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, TamaLight.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.tama_arcade_memory_hud_score, score),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaLight,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun MemoryTurnsBadge(
    turnsLeft: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.44f))
            .border(1.dp, TamaLight.copy(alpha = 0.24f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.tama_arcade_memory_hud_turns, turnsLeft),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TamaLight,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun MemoryMascotSprite(
    pose: MemoryMascotPose,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = "file:///android_asset/${pose.assetPath}",
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun MemoryFeedbackOverlay(
    state: MemoryGameState
) {
    val nowMs = System.currentTimeMillis()
    if (nowMs >= state.feedbackUntilMs || state.lastFeedback == MemoryTurnFeedback.NONE) return
    val pose = when (state.lastFeedback) {
        MemoryTurnFeedback.MATCH -> MemoryMascotPose.HAPPY
        MemoryTurnFeedback.MISMATCH -> MemoryMascotPose.SAD
        else -> MemoryMascotPose.IDLE
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomStart
    ) {
        AsyncImage(
            model = "file:///android_asset/${pose.assetPath}",
            contentDescription = null,
            modifier = Modifier
                .padding(start = 18.dp, bottom = 112.dp)
                .size(84.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
    }
}

@Composable
fun MemoryResultDialog(
    summary: MemoryGameResult,
    onPlayAgain: () -> Unit,
    onBackToHub: () -> Unit
) {
    val title = when {
        summary.perfectClear -> R.string.tama_arcade_memory_result_perfect
        summary.coins > 0 -> R.string.tama_arcade_memory_result_won
        else -> R.string.tama_arcade_memory_result_lost
    }
    AlertDialog(
        onDismissRequest = onBackToHub,
        title = {
            Text(
                stringResource(title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.tama_arcade_memory_result_pairs, summary.pairsMatched),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_memory_result_turns, summary.turnsUsed),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_memory_result_score, summary.score),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_memory_result_reward, summary.coins),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.tama_arcade_result_happiness, summary.happiness),
                    fontFamily = FontFamily.Monospace
                )
                if (summary.perfectClear) {
                    Text(
                        stringResource(R.string.tama_arcade_memory_result_perfect_hint),
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

private fun memoryMascotPose(state: MemoryGameState, nowMs: Long): MemoryMascotPose {
    if (state.finished) {
        val result = buildMemoryGameResult(state)
        return when {
            result?.perfectClear == true -> MemoryMascotPose.CHEER
            result?.coins ?: 0 > 0 -> MemoryMascotPose.HAPPY
            else -> MemoryMascotPose.SAD
        }
    }
    if (state.lastFeedback != MemoryTurnFeedback.NONE && nowMs < state.feedbackUntilMs) {
        return when (state.lastFeedback) {
            MemoryTurnFeedback.MATCH -> MemoryMascotPose.HAPPY
            MemoryTurnFeedback.MISMATCH -> MemoryMascotPose.SAD
            MemoryTurnFeedback.NONE -> MemoryMascotPose.IDLE
        }
    }
    return when {
        nowMs < state.introUntilMs -> MemoryMascotPose.THINKING
        state.firstSelectionIndex != null -> MemoryMascotPose.THINKING
        else -> MemoryMascotPose.IDLE
    }
}

@Composable
private fun memoryWobble(active: Boolean, matched: Boolean): Float {
    if (!active && !matched) return 0f
    val transition = rememberInfiniteTransition(label = "memory_feedback_wobble")
    val wobble by transition.animateFloat(
        initialValue = if (matched) -2.5f else -4f,
        targetValue = if (matched) 2.5f else 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (matched) 180 else 70, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "memory_feedback_wobble_value"
    )
    return wobble
}
