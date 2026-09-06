package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

internal class WalkthroughTargets {
    data class Target(val owner: Any, val bounds: Rect)
    val targets = mutableStateMapOf<String, Target>()
    var requestedId by mutableStateOf<String?>(null)
    var drawerOpen by mutableStateOf(false)
    var active by mutableStateOf(false)
    var retryKey by mutableIntStateOf(0)
    val events = mutableStateMapOf<String, Int>()
    val modalOwners = mutableStateListOf<Any>()
    fun recordEvent(id: String) {
        if (active) events[id] = (events[id] ?: 0) + 1
    }
    internal val scrollOwners = mutableMapOf<String, Pair<Any, suspend () -> Unit>>()
}

/** Register an owning lazy list even when its target item has not been composed yet. */
@Composable
internal fun WalkthroughScrollOwner(targetIds: Set<String>, scrollToTarget: suspend (String) -> Unit) {
    val registry = LocalWalkthroughTargets.current ?: return
    val owner = remember { Any() }
    val scroll by rememberUpdatedState(scrollToTarget)
    DisposableEffect(registry, targetIds) {
        targetIds.forEach { id -> registry.scrollOwners[id] = owner to { scroll(id) } }
        onDispose { targetIds.forEach { id ->
            if (registry.scrollOwners[id]?.first === owner) registry.scrollOwners.remove(id)
        } }
    }
    LaunchedEffect(registry.requestedId, registry.retryKey, registry.active) {
        val id = registry.requestedId
        if (registry.active && id in targetIds && id != null) scroll(id)
    }
}

/** Presentation state is available during first composition, before measured targets are committed. */
internal val LocalWalkthroughActive = staticCompositionLocalOf { false }

internal val LocalWalkthroughTargets = staticCompositionLocalOf<WalkthroughTargets?> { null }
internal val WalkthroughFocusedTarget = SemanticsPropertyKey<Boolean>("WalkthroughFocusedTarget")

/** Registers the real control, without replacing its click handler or accessibility action. */
internal fun Modifier.walkthroughTarget(id: String): Modifier = composed {
    val registry = LocalWalkthroughTargets.current
    if (registry == null || !registry.active) return@composed this
    val owner = remember { Any() }
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(registry, id) {
        onDispose { if (registry.targets[id]?.owner === owner) registry.targets.remove(id) }
    }
    LaunchedEffect(registry.requestedId, registry.retryKey, id) {
        if (registry.requestedId == id) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    this.semantics { this[WalkthroughFocusedTarget] = registry.requestedId == id }
        .bringIntoViewRequester(requester).onGloballyPositioned {
        if (it.isAttached) registry.targets[id] = WalkthroughTargets.Target(owner, it.boundsInWindow())
    }
}

/** A non-intercepting drawing layer. Insets and scroll are reflected in measured window bounds. */
@Composable
internal fun WalkthroughHighlight(registry: WalkthroughTargets, expand: Boolean = true, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val color = MaterialTheme.colorScheme.primary
    val bounds = registry.requestedId?.let { registry.targets[it]?.bounds }
    Box((if (expand) Modifier.fillMaxSize() else Modifier).onGloballyPositioned { origin = it.positionInWindow() }) {
        content()
        if ((!expand || registry.modalOwners.isEmpty()) && bounds != null && bounds.width > 0f && bounds.height > 0f) Canvas(Modifier.matchParentSize()) {
            val margin = 3.dp.toPx()
            drawRoundRect(color, bounds.topLeft - origin - Offset(margin, margin),
                Size(bounds.width + margin * 2, bounds.height + margin * 2),
                CornerRadius(16.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
    }
}
