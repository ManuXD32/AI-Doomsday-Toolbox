package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

internal fun remainingTourImePx(fullHeight: Int, bottom: Int, ime: Int): Int =
    if (bottom <= 0) 0 else (ime - (fullHeight - bottom).coerceAtLeast(0)).coerceAtLeast(0)

/** Match the existing composer inset contract on both adjustResize and edge-to-edge windows. */
@Composable
internal fun WalkthroughInsetHost(active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    var bottom by remember { mutableIntStateOf(0) }
    val fullHeight = maxOf(view.rootView.height, view.resources.displayMetrics.heightPixels)
    val remaining = if (active) remainingTourImePx(fullHeight, bottom, WindowInsets.ime.getBottom(density)) else 0
    Column(Modifier.onGloballyPositioned { bottom = (it.positionInWindow().y + it.size.height).roundToInt() }) {
        content()
        if (remaining > 0) Spacer(Modifier.height(with(density) { remaining.toDp() }))
    }
}
