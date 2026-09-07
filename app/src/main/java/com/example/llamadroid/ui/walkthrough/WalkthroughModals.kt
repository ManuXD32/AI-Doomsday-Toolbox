package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal data class WalkthroughPresentation(
    val state: WalkthroughState,
    val registry: WalkthroughTargets,
    val route: String?,
    val open: (String) -> Unit
) {
    val featureActive: Boolean get() = state.session?.chapterId?.startsWith("feature:") == true
}
internal val LocalWalkthroughPresentation = staticCompositionLocalOf<WalkthroughPresentation?> { null }

/** Only the foremost feature modal hosts guidance; its window also owns highlight coordinates. */
@Composable
private fun rememberModalPresentation(): WalkthroughPresentation? {
    val presentation = LocalWalkthroughPresentation.current ?: return null
    val owner = remember { Any() }
    val registry = presentation.registry
    DisposableEffect(registry, presentation.featureActive) {
        if (presentation.featureActive) registry.modalOwners.add(owner)
        onDispose { registry.modalOwners.remove(owner) }
    }
    return presentation.takeIf { it.featureActive && registry.modalOwners.lastOrNull() === owner }
}

@Composable
private fun ModalBody(presentation: WalkthroughPresentation?, screenHeight: Dp, content: @Composable () -> Unit) {
    // A wrapping dialog's measured window height depends on this body. Feeding that height
    // back into its own maximum (or the coach's compact mode) can keep layout oscillating.
    val body: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.heightIn(max = screenHeight)) {
            val availableHeight = maxHeight.value
            Column {
                Box(Modifier.weight(1f, fill = false)) { content() }
                if (presentation != null) WalkthroughCoach(
                    presentation.state, presentation.registry, presentation.route, presentation.open,
                    availableHeightDp = availableHeight
                )
            }
        }
    }
    val registry = LocalWalkthroughPresentation.current?.registry
    if (registry != null) WalkthroughHighlight(registry, expand = false, content = body) else body()
}

/** Drop-in platform dialog retaining the original content, callbacks and window properties. */
@Composable
internal fun WalkthroughDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val presentation = rememberModalPresentation()
    val parentHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        ModalBody(presentation, parentHeight, content)
    }
}

/** Material decisions retain their original buttons while the modal body can host the coach. */
@Composable
internal fun WalkthroughAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    val presentation = rememberModalPresentation()
    val parentHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier, dismissButton = dismissButton, icon = icon, title = title,
        text = if (text != null || presentation != null) ({ ModalBody(presentation, parentHeight) { text?.invoke() } }) else null,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = tonalElevation, properties = properties
    )
}
