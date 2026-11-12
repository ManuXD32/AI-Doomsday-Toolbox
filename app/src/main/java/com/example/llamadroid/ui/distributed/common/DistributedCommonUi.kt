package com.example.llamadroid.ui.distributed.common

/*
 * Shared building blocks for the distributed screens (W21a LLM main device, W21b helper device,
 * W21c media). Owned by W21a; the other parts only read it. Strings live in
 * res/values{,-es}/dist_ui_v2.xml (prefix dist_ui_common_).
 *
 * Public composables:
 *  - DistributedHowItWorksStrip   3 numbered steps shown at the top of every distributed screen.
 *  - DistributedStatusPanel       AppStatePanel plus optional extra rows (RTT, warnings, spinner).
 *  - DistributedHelperListItem    One helper device row: name, address, memory, RTT, advert, actions.
 *  - DistributedSearchingState    "Searching for helpers…" card with a spinner (NSD running).
 *  - DistributedNoHelpersState    "No helpers yet" card with add / secondary actions.
 *  - DistributedWarningRow        Plain-language plan warning with an optional one-tap action.
 *  - DistributedTechnicalLogSection  Collapsed, height-bounded, selectable log with copy.
 *  - DistributedInfoCard          Info card with a title and plain paragraphs (Wi-Fi/VPN/security notes).
 */

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.DistributedPerfText
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel

/** "How it works": a short strip of numbered steps. Pass 3 already-localized sentences. */
@Composable
fun DistributedHowItWorksStrip(
    steps: List<String>,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.dist_ui_common_how_title)
) {
    AppSectionCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Readable status for the whole screen. [extra] renders below the panel (RTT summary, warnings, a
 * spinner while searching). The panel itself is the shared [AppStatePanel].
 */
@Composable
fun DistributedStatusPanel(
    kind: AppStateKind,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppStatePanel(
            kind = kind,
            title = title,
            message = message,
            actionLabel = actionLabel,
            onAction = onAction
        )
        extra?.invoke(this)
    }
}

/**
 * One helper (worker) device row. All text is supplied by the caller except the RTT, which is
 * formatted with [DistributedPerfText.rttLabel]. [trailing] holds the row actions (add, edit, ...).
 */
@Composable
fun DistributedHelperListItem(
    name: String,
    address: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    advertLabel: String? = null,
    rttMs: Long? = null,
    showRtt: Boolean = false,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** Extra content under the helper's text, e.g. the Test connection action. */
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        highlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val alpha = if (enabled) 1f else 0.55f
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!advertLabel.isNullOrBlank()) {
                    Text(
                        text = advertLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showRtt) {
                    Text(
                        text = DistributedPerfText.rttLabel(context, rttMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                footer?.invoke(this)
            }
            trailing?.invoke(this)
        }
    }
}

/** Empty state while network discovery is running and nothing has answered yet. */
@Composable
fun DistributedSearchingState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.dist_ui_common_searching_title),
    message: String = stringResource(R.string.dist_ui_common_searching_message)
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "No helpers yet" with a primary add action and an optional secondary one (scan QR, etc.). */
@Composable
fun DistributedNoHelpersState(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.dist_ui_common_no_helpers_title),
    message: String = stringResource(R.string.dist_ui_common_no_helpers_message),
    addLabel: String = stringResource(R.string.dist_ui_common_add_manually),
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text(addLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A plain-language plan warning with an optional one-tap fix. */
@Composable
fun DistributedWarningRow(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                    Text(actionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Raw log lines inside a collapsed "Technical log" section. Height is bounded (it scrolls inside)
 * so the section never pushes the page; the page itself stays scrollable.
 */
@Composable
fun DistributedTechnicalLogSection(
    lines: List<String>,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.dist_ui_common_log_title),
    maxHeightDp: Int = 220,
    reveal: Boolean = false,
    /** When set, a button opens the full-screen log viewer. */
    onOpenViewer: (() -> Unit)? = null,
    /** When set, a Delete button (with confirmation) clears the log through this callback. */
    onClear: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.dist_ui_common_log_copied)
    val clearedMessage = stringResource(R.string.dist_r7_log_cleared)
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear && onClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dist_r7_log_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                    Toast.makeText(context, clearedMessage, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.dist_r7_log_clear_confirm), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(android.R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }
    AppAdvancedSection(title = title, modifier = modifier, revealContent = reveal) {
        Text(
            text = stringResource(R.string.dist_ui_common_log_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.dist_ui_common_log_empty),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onOpenViewer != null) {
                    IconButton(onClick = onOpenViewer) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.dist_ui_status_show_log)
                        )
                    }
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.dist_ui_common_log_copy)
                    )
                }
                if (onClear != null) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.dist_r7_log_clear)
                        )
                    }
                }
            }
            val scroll = rememberScrollState()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = AppChromeDefaults.CompactShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxHeightDp.dp)
                            .verticalScroll(scroll)
                            .padding(10.dp)
                    ) {
                        // Only the tail is composed: logs can hold hundreds of lines.
                        lines.takeLast(200).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Info card with a title and plain paragraphs, used for network and security notes. */
@Composable
fun DistributedInfoCard(
    title: String,
    paragraphs: List<String>,
    modifier: Modifier = Modifier
) {
    AppSectionCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                AppSectionTitle(title = title, modifier = Modifier.weight(1f))
            }
            paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
