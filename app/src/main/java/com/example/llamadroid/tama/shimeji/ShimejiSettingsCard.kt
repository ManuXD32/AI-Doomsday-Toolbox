package com.example.llamadroid.tama.shimeji

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.llamadroid.R
import com.example.llamadroid.ui.overlay.rememberOverlayPermissionRequest
import com.example.llamadroid.tama.ui.TamaHelperText
import com.example.llamadroid.tama.ui.tamaSwitchColors

/**
 * "Shimeji mode" block of the Tama settings: desktop-pet toggle, size and activity level.
 * Enabling without the overlay permission explains it first, opens the system screen and starts
 * the overlay when the user comes back with the permission granted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShimejiSettingsCard(
    modifier: Modifier = Modifier,
    isAway: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(ShimejiSettings.isEnabled(context)) }
    var size by remember { mutableStateOf(ShimejiSettings.size(context)) }
    var activity by remember { mutableStateOf(ShimejiSettings.activity(context)) }
    val requestOverlayPermission = rememberOverlayPermissionRequest(R.string.tama_shimeji_permission_body) {
        ShimejiSettings.setEnabled(context, true)
        ShimejiOverlayService.start(context)
        enabled = true
    }

    // Live flag: the care menu and the notification Stop action turn it off from the service.
    DisposableEffect(context) {
        val prefs = ShimejiSettings.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ShimejiSettings.KEY_ENABLED) enabled = ShimejiSettings.isEnabled(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            // The service turns the setting off by itself when the permission is revoked.
            enabled = ShimejiSettings.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tama_shimeji_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.tama_shimeji_desc),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaHelperText
                )
            }
            Switch(
                checked = enabled && !isAway,
                enabled = !isAway,
                colors = tamaSwitchColors(),
                onCheckedChange = { wantsOn ->
                    if (!wantsOn) {
                        ShimejiSettings.setEnabled(context, false)
                        ShimejiOverlayService.stop(context)
                        enabled = false
                    } else {
                        requestOverlayPermission()
                    }
                }
            )
        }
        if (isAway) {
            Text(
                stringResource(R.string.tama_shimeji_away_note),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TamaHelperText
            )
        }

        Text(
            stringResource(R.string.tama_shimeji_size_title),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        val sizeLabels = listOf(
            ShimejiSize.SMALL to R.string.tama_shimeji_size_small,
            ShimejiSize.MEDIUM to R.string.tama_shimeji_size_medium,
            ShimejiSize.LARGE to R.string.tama_shimeji_size_large
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            sizeLabels.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = size == value,
                    onClick = {
                        size = value
                        ShimejiSettings.setSize(context, value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = sizeLabels.size)
                ) {
                    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Text(
            stringResource(R.string.tama_shimeji_activity_title),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        val activityLabels = listOf(
            ShimejiActivity.CALM to R.string.tama_shimeji_activity_calm,
            ShimejiActivity.NORMAL to R.string.tama_shimeji_activity_normal,
            ShimejiActivity.PLAYFUL to R.string.tama_shimeji_activity_playful
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            activityLabels.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = activity == value,
                    onClick = {
                        activity = value
                        ShimejiSettings.setActivity(context, value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = activityLabels.size)
                ) {
                    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Text(
            stringResource(R.string.tama_shimeji_hint),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = TamaHelperText
        )
    }
}
