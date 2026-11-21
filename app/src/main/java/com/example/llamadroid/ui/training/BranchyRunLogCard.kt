package com.example.llamadroid.ui.training

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyRunLog
import com.example.llamadroid.ui.components.AppSectionCard
import java.io.File

/** Puts [text] on the clipboard and (Android 12 and below, which show nothing themselves) toasts "Copied". */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.branchy_r5d_copied), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Collapsed "Run log" card. Expanding loads the bounded, redacted log off the main thread ([loader] defaults to
 * [BranchyRunLog.load]); Refresh reloads it and Copy puts the whole text on the clipboard.
 */
@Composable
internal fun BranchyRunLogCard(
    runDir: File,
    modifier: Modifier = Modifier,
    loader: suspend () -> String = { BranchyRunLog.load(runDir) }
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, reloadKey) {
        if (expanded) {
            loading = true
            text = loader()
            loading = false
        }
    }
    AppSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.branchy_r5d_runlog_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(if (expanded) R.string.branchy_r5d_runlog_hide else R.string.branchy_r5d_runlog_show),
                    maxLines = 1
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = when {
                        loading && text.isEmpty() -> stringResource(R.string.branchy_r5d_runlog_loading)
                        text.isEmpty() -> stringResource(R.string.branchy_r5d_runlog_empty)
                        else -> text
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { reloadKey += 1 },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.branchy_r5d_runlog_refresh), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                OutlinedButton(
                    onClick = { if (text.isNotEmpty()) copyToClipboard(context, "Branchy run log", text) },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.action_copy), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
