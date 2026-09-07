package com.example.llamadroid.ui.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.llamadroid.R

class BatteryOptimizationGateState(
    private val context: Context,
    private val powerManager: PowerManager
) {
    private var pendingAction by mutableStateOf<(() -> Unit)?>(null)
    private var isBatteryOptimizationExempt by mutableStateOf(
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    )

    val showDialog: Boolean
        get() = pendingAction != null && !isBatteryOptimizationExempt

    fun runAfterCheck(action: () -> Unit) {
        pendingAction = action
        refreshBatteryOptimizationState()
    }

    fun refreshBatteryOptimizationState(): Boolean {
        val isExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        isBatteryOptimizationExempt = isExempt
        if (isExempt) {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
        return isExempt
    }

    fun dismiss() {
        pendingAction = null
    }

    fun continueAnyway() {
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openDeviceSpecificFix() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun rememberBatteryOptimizationGateState(): BatteryOptimizationGateState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    val state = remember(context, powerManager) {
        BatteryOptimizationGateState(context.applicationContext, powerManager)
    }
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refreshBatteryOptimizationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return state
}

@Composable
fun BatteryOptimizationWarningDialog(state: BatteryOptimizationGateState) {
    if (!state.showDialog) return

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        BatteryOptimizationDialogContent(
            onOpenBatterySettings = state::openBatterySettings,
            onOpenDeviceSpecificFix = state::openDeviceSpecificFix,
            onContinueAnyway = state::continueAnyway,
            onDismiss = state::dismiss
        )
    }
}

/**
 * The bounded dialog body is kept separate so its layout and action semantics
 * can be exercised without launching the platform battery settings intent.
 */
@Composable
internal fun BatteryOptimizationDialogContent(
    onOpenBatterySettings: () -> Unit,
    onOpenDeviceSpecificFix: () -> Unit,
    onContinueAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    // The dialog owns a finite viewport. Its body may scroll, while the
    // action footer remains inside the surface and therefore remains
    // reachable on short, landscape, split-screen, and large-font windows.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        val compactActions = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
        val settingsLabel = stringResource(
            if (compactActions) {
                R.string.responsive_battery_dialog_settings_compact
            } else {
                R.string.generation_battery_dialog_settings
            }
        )
        val oemFixLabel = stringResource(
            if (compactActions) {
                R.string.responsive_battery_dialog_oem_fix_compact
            } else {
                R.string.generation_battery_dialog_oem_fix
            }
        )
        val continueLabel = stringResource(
            if (compactActions) {
                R.string.responsive_battery_dialog_continue_compact
            } else {
                R.string.generation_battery_dialog_continue
            }
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.generation_battery_dialog_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(stringResource(R.string.generation_battery_dialog_message))
                }
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.responsive_battery_dialog_actions),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (compactActions) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = onOpenBatterySettings,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    settingsLabel,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    textAlign = TextAlign.Start
                                )
                            }
                            TextButton(
                                onClick = onOpenDeviceSpecificFix,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    oemFixLabel,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = onOpenBatterySettings,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    settingsLabel,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                            TextButton(
                                onClick = onOpenDeviceSpecificFix,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    oemFixLabel,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onContinueAnyway,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                continueLabel,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3,
                                textAlign = TextAlign.Center
                            )
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                stringResource(R.string.action_cancel),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
