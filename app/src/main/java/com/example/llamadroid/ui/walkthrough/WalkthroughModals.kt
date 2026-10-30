package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
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

/**
 * Test hook for the JVM screenshot renderer (docs/screenshot-renderer.md): PixelCopy only reads the activity
 * window, so when this is true the dialog wrappers below compose their content inline (centred in the
 * parent box) instead of opening a dialog window. Always false in the app.
 */
internal val LocalInlineModalHost = staticCompositionLocalOf { false }

/** Inline stand-in for a Material alert dialog: container surface, icon, title, text and buttons. */
@Composable
private fun InlineAlertDialogBody(
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    shape: Shape,
    containerColor: Color,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color,
    tonalElevation: Dp
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp),
            shape = shape, color = containerColor, tonalElevation = tonalElevation
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (icon != null) androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides iconContentColor
                ) { Box(Modifier.align(Alignment.CenterHorizontally)) { icon() } }
                if (title != null) androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides titleContentColor
                ) { title() }
                if (text != null) androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides textContentColor
                ) { Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() } }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
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
    if (LocalInlineModalHost.current) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        return
    }
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
    if (LocalInlineModalHost.current) {
        InlineAlertDialogBody(
            confirmButton, modifier, dismissButton, icon, title, text, shape, containerColor,
            iconContentColor, titleContentColor, textContentColor, tonalElevation
        )
        return
    }
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
