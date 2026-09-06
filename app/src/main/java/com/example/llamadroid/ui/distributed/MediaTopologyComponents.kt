package com.example.llamadroid.ui.distributed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Fields stack independently so an endpoint never gets squeezed beside an intrinsic-width label. */
@Composable
fun MediaTopologyNode(title: String, status: String, fields: List<Pair<String, String>>) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString((listOf(title, status) + fields.map { "${it.first}: ${it.second}" }).joinToString("\n")))
                }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.media_topology_copy)) }
            }
            Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            fields.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
fun MediaTopologyConnector() {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(color, 3.dp.toPx(), Offset(size.width / 2, size.height / 2))
    }
}

@Composable
fun <T> ResponsiveTopologyNodes(items: List<T>, content: @Composable (T) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp && fontScale < 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
