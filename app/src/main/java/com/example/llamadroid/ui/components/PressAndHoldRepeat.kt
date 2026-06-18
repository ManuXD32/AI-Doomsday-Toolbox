package com.example.llamadroid.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PressAndHoldRepeatState internal constructor() {
    internal var suppressNextClick by mutableStateOf(false)

    fun handleClick(onClick: () -> Unit) {
        if (suppressNextClick) {
            suppressNextClick = false
        } else {
            onClick()
        }
    }
}

@Composable
fun rememberPressAndHoldRepeatState(): PressAndHoldRepeatState = remember {
    PressAndHoldRepeatState()
}

fun Modifier.pressAndHoldRepeat(
    state: PressAndHoldRepeatState,
    enabled: Boolean = true,
    repeatIntervalMillis: Long = 90L,
    onRepeat: () -> Boolean
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis.toLong()

    pointerInput(state, currentEnabled, longPressTimeoutMillis, repeatIntervalMillis) {
        if (!currentEnabled) return@pointerInput

        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            state.suppressNextClick = false

            val repeatJob = scope.launch {
                delay(longPressTimeoutMillis)
                state.suppressNextClick = true
                if (!currentOnRepeat()) return@launch
                while (isActive) {
                    delay(repeatIntervalMillis)
                    if (!currentOnRepeat()) return@launch
                }
            }

            val up = waitForUpOrCancellation()
            repeatJob.cancel()
            if (up == null) {
                state.suppressNextClick = false
            }
        }
    }
}
