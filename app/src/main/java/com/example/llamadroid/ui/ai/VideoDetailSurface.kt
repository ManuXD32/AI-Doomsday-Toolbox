package com.example.llamadroid.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.llamadroid.ui.components.AppTextDetailsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R

/** One bounded scrolling body; actions never compete with a second dialog footer. */
@Composable
internal fun VideoDetailSurface(
    onDismiss: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    val width = LocalWindowInfo.current.containerSize.width
    val wide = with(LocalDensity.current) { width.toDp() } >= 600.dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = if (wide) Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).fillMaxHeight(0.9f)
                else Modifier.fillMaxSize(),
            shape = if (wide) MaterialTheme.shapes.extraLarge else androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { title() }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    text()
                    HorizontalDivider()
                    confirmButton()
                    dismissButton()
                }
            }
        }
    }
}

@Composable
internal fun VideoPromptBlock(label: String, value: String) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var showFullPrompt by remember(value) { mutableStateOf(false) }
    val copyLabel = stringResource(R.string.video_detail_copy_prompt, label)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = {
                runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(label, value))
                }.onFailure {
                    android.widget.Toast.makeText(context, resources.getString(R.string.video_gen_copy_info_failed,
                        it.localizedMessage.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Icon(Icons.Default.ContentCopy, contentDescription = copyLabel) }
        }
        SelectionContainer { Text(value.take(1200), style = MaterialTheme.typography.bodyMedium) }
        if (value.length > 1200) {
            TextButton(onClick = { showFullPrompt = true }) { Text(stringResource(R.string.video_detail_full_prompt)) }
        }
    }
    if (showFullPrompt) {
        AppTextDetailsDialog(label, value, onDismiss = { showFullPrompt = false })
    }
}

/** Recorded placement stays editable instead of silently falling back to model defaults. */
@Composable
internal fun VideoReuseBackendCard(overrides: org.json.JSONObject, onChange: (org.json.JSONObject?) -> Unit) {
    com.example.llamadroid.ui.components.AppAdvancedSection(title = stringResource(R.string.video_detail_saved_device_settings)) {
        Text(stringResource(R.string.video_detail_saved_device_settings_body), style = MaterialTheme.typography.bodySmall)
        listOf(
            "reuseSdParamsBackendMode" to R.string.video_detail_params_mode,
            "reuseSdParamsBackendSpec" to R.string.video_detail_params_spec,
            "reuseSdRuntimeBackendMode" to R.string.video_detail_runtime_spec,
            "reuseMaxVramCpuGiB" to R.string.video_detail_ram_limit
        ).forEach { (key, label) ->
            OutlinedTextField(value = overrides.optString(key), onValueChange = { value ->
                onChange(org.json.JSONObject(overrides.toString()).put(key, value))
            }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true)
        }
        OutlinedButton(onClick = { onChange(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.video_detail_use_device_settings))
        }
    }
}
