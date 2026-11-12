package com.example.llamadroid.ui.distributed.common

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R

/** Newest lines kept in the viewer; older ones are dropped to keep the single Text bounded. */
private const val MAX_VIEWER_LINES = 1000

/**
 * Full-screen technical log viewer: monospace, scrollable both ways, follows the bottom as lines
 * arrive, with Copy and Share actions. Shared by the LLM and SD distributed screens.
 */
@Composable
fun DistributedLogViewerDialog(
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.dist_ui_common_log_copied)
    val shareTitle = stringResource(R.string.dist_r3_log_share)
    val text = lines.takeLast(MAX_VIEWER_LINES).joinToString("\n")
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    LaunchedEffect(lines.size) { vertical.scrollTo(vertical.maxValue) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.dist_ui_common_log_copy))
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = shareTitle)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dist_r3_log_close))
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vertical)
                                .horizontalScroll(horizontal)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = text.ifEmpty { stringResource(R.string.dist_ui_common_log_empty) },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
