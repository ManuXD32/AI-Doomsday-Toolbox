package com.example.llamadroid.tama.game

import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.compaction.ConversationCompactor
import com.example.llamadroid.service.vision.ImageFallbackLogic

/** Pet chat context meter: tokens in the prompt (plus reply once known) against the server window. */
data class TamaContextUsage(val usedTokens: Int, val windowTokens: Int) {
    /** 0..100, rounded down; 0 when the window is unknown. */
    val percent: Int
        get() = if (windowTokens <= 0) 0 else (usedTokens.toLong() * 100L / windowTokens).toInt().coerceIn(0, 100)
}

internal const val TAMA_DEFAULT_MAX_OUTPUT_TOKENS = 1024
/** Reasoning models spend tokens on thinking before the short pet reply. */
internal const val TAMA_THINKING_MAX_OUTPUT_TOKENS = 4096

/** Anchored history window sizes tried in order when the prompt does not fit (blocks of 10 each). */
internal val TAMA_WINDOW_LEVELS = listOf(TAMA_HISTORY_WINDOW_MAX, 20, 10)

/** Fewest history messages kept when the window is shrunk. */
internal const val TAMA_MIN_HISTORY_MESSAGES = 6

/** Max reply tokens: [TAMA_DEFAULT_MAX_OUTPUT_TOKENS], never more than a quarter of a known window. */
internal fun tamaOutputCap(windowTokens: Int, configured: Int = TAMA_DEFAULT_MAX_OUTPUT_TOKENS): Int {
    val base = configured.takeIf { it > 0 } ?: TAMA_DEFAULT_MAX_OUTPUT_TOKENS
    return if (windowTokens > 0) base.coerceAtMost((windowTokens / 4).coerceAtLeast(1)) else base
}

/** Reuses native chat's compaction gate: true when the prompt would eat the usable window. */
internal fun tamaShouldCompact(estimatedPromptTokens: Int, windowTokens: Int, outputCap: Int): Boolean =
    ConversationCompactor.shouldCompact(
        estimatedPromptTokens,
        windowTokens,
        ConversationCompactor.outputReserve(windowTokens, outputCap)
    )

/** The next smaller window level, or [current] when already at the smallest. */
internal fun nextTamaWindowMax(current: Int): Int =
    TAMA_WINDOW_LEVELS.firstOrNull { it < current } ?: TAMA_WINDOW_LEVELS.last()

/** [anchoredTamaHistoryWindow] that never keeps fewer than [TAMA_MIN_HISTORY_MESSAGES] (when available). */
internal fun <T> anchoredTamaHistoryWindowMin(history: List<T>, max: Int): List<T> {
    val window = anchoredTamaHistoryWindow(history, max)
    return if (window.size < TAMA_MIN_HISTORY_MESSAGES) history.takeLast(TAMA_MIN_HISTORY_MESSAGES) else window
}

/**
 * Picks the largest window level `<= startMax` for which [fits] is true; the smallest level when
 * none fits. Shrinking happens in whole levels so consecutive turns keep a stable prefix.
 */
internal suspend fun fitTamaWindowMax(startMax: Int, fits: suspend (Int) -> Boolean): Int {
    val candidates = TAMA_WINDOW_LEVELS.filter { it <= startMax }.ifEmpty { listOf(TAMA_WINDOW_LEVELS.last()) }
    for (max in candidates) if (fits(max)) return max
    return candidates.last()
}

/** Tokens the server reports for the turn: prompt + completion, else the total. */
internal fun tamaUsedTokens(usage: OllamaService.ChatUsage?): Int? {
    usage ?: return null
    val prompt = usage.promptTokens
    val completion = usage.completionTokens
    return when {
        prompt != null -> prompt + (completion ?: 0)
        usage.totalTokens != null -> usage.totalTokens
        else -> null
    }
}

/** One image attached to an outgoing message. */
internal sealed interface TamaImageRef {
    data class Path(val path: String) : TamaImageRef
    data class Base64(val data: String) : TamaImageRef
}

internal const val TAMA_IMAGE_UNDESCRIBED_NOTE = "[An image was attached but could not be described.]"
internal const val TAMA_IMAGE_OMITTED_NOTE = "[An older image was omitted to save context.]"

private fun OllamaService.ChatMessage.imageRefs(): List<TamaImageRef> {
    val paths = allImagePaths()
    return if (paths.isNotEmpty()) paths.map { TamaImageRef.Path(it) }
    else images.orEmpty().filter { it.isNotBlank() }.map { TamaImageRef.Base64(it) }
}

/**
 * For a server that cannot see images: removes every image field and appends a description block
 * (or a short bracketed note when [describe] has no text or the helper is not [configured]).
 * Only the newest [maxImages] images are described; older ones get an omitted note. Messages
 * without images are returned unchanged. [describe] returns null when no description exists.
 */
internal suspend fun mapTamaImages(
    messages: List<OllamaService.ChatMessage>,
    configured: Boolean,
    describe: suspend (TamaImageRef) -> String?,
    maxImages: Int = ImageFallbackLogic.MAX_IMAGES_PER_TURN
): List<OllamaService.ChatMessage> {
    val parts = HashMap<Int, List<String>>()
    var seen = 0
    for (index in messages.indices.reversed()) {
        val refs = messages[index].imageRefs()
        if (refs.isEmpty()) continue
        parts[index] = refs.map { ref ->
            if (seen++ >= maxImages) {
                TAMA_IMAGE_OMITTED_NOTE
            } else {
                val text = if (configured) describe(ref)?.trim()?.takeIf { it.isNotEmpty() } else null
                if (text != null) ImageFallbackLogic.descriptionBlock(text) else TAMA_IMAGE_UNDESCRIBED_NOTE
            }
        }
    }
    return messages.mapIndexed { index, message ->
        val added = parts[index]
        if (added == null && message.images.isNullOrEmpty() && message.allImagePaths().isEmpty()) message
        else message.copy(
            content = (listOf(message.content) + added.orEmpty()).joinToString("\n\n").trim(),
            images = null,
            imagePath = null,
            imagePaths = emptyList()
        )
    }
}
