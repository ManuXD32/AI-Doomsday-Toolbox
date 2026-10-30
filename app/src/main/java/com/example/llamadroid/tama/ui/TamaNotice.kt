package com.example.llamadroid.tama.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import kotlinx.coroutines.delay

/** One transient message shown by [TamaNoticeHost]. [id] changes with every [TamaNoticeState.show]. */
data class TamaNotice(val id: Long, val message: String, val isError: Boolean)

const val TAMA_NOTICE_MILLIS = 2_500L
const val TAMA_NOTICE_ERROR_MILLIS = 4_000L

/**
 * Pure notice logic (no Compose UI, JVM-testable): the current notice, auto-hide durations and the
 * host registration stack. A new notice replaces the old one (new id, so timers restart). Only the
 * top-most registered host draws, so a notice raised while a Dialog is open shows inside it.
 */
@Stable
class TamaNoticeState {
    private var nextId = 1L
    private val hostStack = mutableStateListOf<Any>()

    var current: TamaNotice? by mutableStateOf(null)
        private set

    fun show(message: String, isError: Boolean = false) {
        if (message.isBlank()) return
        current = TamaNotice(nextId++, message, isError)
    }

    fun dismiss() {
        current = null
    }

    /** Dismisses only if [id] is still the current notice (stale timers must not close a newer one). */
    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }

    fun registerHost(token: Any) {
        hostStack.remove(token)
        hostStack.add(token)
    }

    fun unregisterHost(token: Any) {
        hostStack.remove(token)
    }

    fun isTopHost(token: Any): Boolean = hostStack.lastOrNull() === token

    companion object {
        fun autoHideMillis(isError: Boolean): Long = if (isError) TAMA_NOTICE_ERROR_MILLIS else TAMA_NOTICE_MILLIS
    }
}

/** Default is a detached state, so previews and non-Tama routes never crash (notices just have no host). */
val LocalTamaNotice = staticCompositionLocalOf { TamaNoticeState() }

@Composable
fun rememberTamaNotice(): TamaNoticeState = LocalTamaNotice.current

/**
 * Top-start overlay for the current notice. Draws only when [state] has a notice and this host is
 * the top-most registered one; never intercepts touches outside its own surface.
 */
@Composable
fun TamaNoticeHost(state: TamaNoticeState, modifier: Modifier = Modifier) {
    val token = remember { Any() }
    DisposableEffect(state, token) {
        state.registerHost(token)
        onDispose { state.unregisterHost(token) }
    }
    val notice = state.current
    val active = notice != null && state.isTopHost(token)
    LaunchedEffect(notice?.id) {
        val shown = notice ?: return@LaunchedEffect
        delay(TamaNoticeState.autoHideMillis(shown.isError))
        state.dismiss(shown.id)
    }
    val context = LocalContext.current
    val reduced = remember { farmReducedMotion(context) }
    // Keep the last notice while animating out.
    var lastShown by remember { mutableStateOf<TamaNotice?>(null) }
    if (notice != null) lastShown = notice
    val display = notice ?: lastShown
    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(8.dp),
        contentAlignment = Alignment.TopStart
    ) {
        AnimatedVisibility(
            visible = active,
            enter = if (reduced) fadeIn(tween(0)) else fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 2 },
            exit = if (reduced) fadeOut(tween(0)) else fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 2 }
        ) {
            if (display != null) TamaNoticeCard(display, onClose = { state.dismiss(display.id) })
        }
    }
}

@Composable
private fun TamaNoticeCard(notice: TamaNotice, onClose: () -> Unit) {
    val scheme = tamaLcdDarkScheme
    val container = if (notice.isError) scheme.errorContainer else scheme.surface
    val content = if (notice.isError) scheme.onErrorContainer else scheme.onSurface
    Surface(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, content.copy(alpha = 0.35f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                notice.message,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.tama_notice_close),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Drop into Tama Dialog / AlertDialog content so notices raised while it is open draw on top of it. */
@Composable
fun TamaDialogNoticeHost() {
    TamaNoticeHost(rememberTamaNotice())
}
