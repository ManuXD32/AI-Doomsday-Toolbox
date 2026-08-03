package com.example.llamadroid.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.FileProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.LogEntry
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.GenerationBreadcrumb
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.GenerationExitSnapshot
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private enum class LogTab { APP, GENERATION_DIAGNOSTICS, RPC }

@Composable
fun LogsScreen(navController: NavController) {
    val appLogs by DebugLog.logs.collectAsState()
    val rpcLogs by DistributedService.rpcLogs.collectAsState()
    val generationBreadcrumbs by GenerationDiagnosticsStore.recentBreadcrumbs.collectAsState()
    val latestExitSnapshot by GenerationDiagnosticsStore.latestExitSnapshot.collectAsState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val context = LocalContext.current
    val appListState = rememberLazyListState()
    val rpcListState = rememberLazyListState()
    val generationDiagnosticsAvailable = latestExitSnapshot != null || generationBreadcrumbs.isNotEmpty()
    
    var selectedTab by remember { mutableStateOf(LogTab.APP) }
    var autoOpenedDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            DebugLog.refreshFromDisk()
            delay(1000)
        }
    }

    LaunchedEffect(generationDiagnosticsAvailable, appLogs.isEmpty()) {
        if (!autoOpenedDiagnostics && generationDiagnosticsAvailable && appLogs.isEmpty()) {
            selectedTab = LogTab.GENERATION_DIAGNOSTICS
            autoOpenedDiagnostics = true
        }
    }

    AppPageBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            AppContentColumn(
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 0.dp,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppPageHeader(
                    eyebrow = "DEBUG",
                    title = stringResource(R.string.logs_title),
                    subtitle = stringResource(R.string.settings_debug_desc)
                )
            }
        
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.padding(horizontal = 20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            indicator = { _ -> }
        ) {
            Tab(
                selected = selectedTab == LogTab.APP,
                onClick = { selectedTab = LogTab.APP },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📱 " + stringResource(R.string.logs_tab_app))
                        if (appLogs.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Badge { Text("${appLogs.size}") }
                        }
                    }
                }
            )
            Tab(
                selected = selectedTab == LogTab.GENERATION_DIAGNOSTICS,
                onClick = { selectedTab = LogTab.GENERATION_DIAGNOSTICS },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧪 " + stringResource(R.string.logs_tab_generation_diag))
                        if (generationDiagnosticsAvailable) {
                            Spacer(Modifier.width(4.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ) {
                                Text("${generationBreadcrumbs.size + if (latestExitSnapshot != null) 1 else 0}")
                            }
                        }
                    }
                }
            )
            Tab(
                selected = selectedTab == LogTab.RPC,
                onClick = { selectedTab = LogTab.RPC },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌐 " + stringResource(R.string.logs_tab_rpc))
                        if (rpcLogs.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Badge(
                                containerColor = Color(0xFF00FF41),
                                contentColor = Color.Black
                            ) { Text("${rpcLogs.size}") }
                        }
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Action buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val canCopyOrClear = when (selectedTab) {
                LogTab.APP -> appLogs.isNotEmpty()
                LogTab.GENERATION_DIAGNOSTICS -> generationDiagnosticsAvailable
                LogTab.RPC -> rpcLogs.isNotEmpty()
            }
            val logText = when (selectedTab) {
                LogTab.APP -> buildAppLogExport(appLogs, dateFormat)
                LogTab.GENERATION_DIAGNOSTICS -> buildDiagnosticsExport(
                    context = context,
                    exitSnapshot = latestExitSnapshot,
                    breadcrumbs = GenerationDiagnosticsStore.loadAllStoredBreadcrumbs(),
                    dateFormat = dateFormat
                )
                LogTab.RPC -> rpcLogs.joinToString("\n")
            }
            
            FilledTonalButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.logs_clip_label), logText))
                    Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                },
                enabled = canCopyOrClear,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logs_copy))
            }

            if (selectedTab == LogTab.GENERATION_DIAGNOSTICS) {
                FilledTonalButton(
                    onClick = {
                        shareDiagnosticsExport(
                            context = context,
                            exportText = logText
                        )
                    },
                    enabled = generationDiagnosticsAvailable,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.logs_share_full))
                }
            }
            
            FilledTonalButton(
                onClick = {
                    when (selectedTab) {
                        LogTab.APP -> DebugLog.clear()
                        LogTab.GENERATION_DIAGNOSTICS -> GenerationDiagnosticsStore.clearPersistedDiagnostics()
                        LogTab.RPC -> DistributedService.clearRpcLogs()
                    }
                },
                enabled = canCopyOrClear,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logs_clear))
            }

            if (selectedTab == LogTab.GENERATION_DIAGNOSTICS && generationDiagnosticsAvailable) {
                Text(
                    text = stringResource(R.string.logs_generation_diag_share_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Log content based on selected tab
        Box(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                LogTab.APP -> AppLogsContent(
                    logs = appLogs,
                    listState = appListState,
                    dateFormat = dateFormat
                )
                LogTab.GENERATION_DIAGNOSTICS -> GenerationDiagnosticsContent(
                    exitSnapshot = latestExitSnapshot,
                    breadcrumbs = generationBreadcrumbs,
                    dateFormat = dateFormat
                )
                LogTab.RPC -> RpcLogsContent(rpcLogs, rpcListState)
            }
        }
        }
    }
}

@Composable
private fun AppLogsContent(
    logs: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    dateFormat: SimpleDateFormat
) {
    if (logs.isEmpty()) {
        EmptyLogsPlaceholder(stringResource(R.string.logs_no_app), stringResource(R.string.logs_no_app_desc))
    } else {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = true
            ) {
                items(logs.reversed()) { entry ->
                    val isError = entry.message.contains("ERROR", ignoreCase = true) ||
                        entry.message.contains("FAILED", ignoreCase = true) ||
                        entry.message.contains("CRASH", ignoreCase = true)
                    val isServer = entry.message.startsWith("Server:")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isError) Color(0xFF3D2020) else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = dateFormat.format(Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = Color(0xFF6A9955),
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = when {
                                isError -> Color(0xFFF48771)
                                isServer -> Color(0xFF9CDCFE)
                                else -> Color(0xFFD4D4D4)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerationDiagnosticsContent(
    exitSnapshot: GenerationExitSnapshot?,
    breadcrumbs: List<GenerationBreadcrumb>,
    dateFormat: SimpleDateFormat
) {
    if (exitSnapshot == null && breadcrumbs.isEmpty()) {
        EmptyLogsPlaceholder(
            stringResource(R.string.logs_generation_diag_title),
            stringResource(R.string.logs_no_generation_diag_desc)
        )
    } else {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            )
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.logs_generation_diag_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (exitSnapshot?.hadActiveGeneration == true) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.generation_diag_relaunch_warning),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (exitSnapshot != null) {
                    exitSnapshot.appVersion?.let { version ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_app_version, version),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(
                                R.string.logs_generation_diag_reason,
                                exitSnapshot.reasonLabel,
                                exitSnapshot.reasonCode
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    item {
                        Text(
                            text = stringResource(
                                R.string.logs_generation_diag_process,
                                exitSnapshot.processName ?: stringResource(R.string.logs_generation_diag_unknown),
                                exitSnapshot.pid?.toString() ?: stringResource(R.string.logs_generation_diag_unknown),
                                exitSnapshot.status,
                                exitSnapshot.importance
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (exitSnapshot.processPssKb != null || exitSnapshot.processRssKb != null) {
                        item {
                            Text(
                                text = stringResource(
                                    R.string.logs_generation_diag_process_memory,
                                    exitSnapshot.processPssKb?.toString() ?: "—",
                                    exitSnapshot.processRssKb?.toString() ?: "—"
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(
                                R.string.logs_generation_diag_time,
                                dateFormat.format(Date(exitSnapshot.timestamp))
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    exitSnapshot.sessionSummary?.let { sessionSummary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_session, sessionSummary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    exitSnapshot.description?.let { description ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_description, description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    exitSnapshot.traceSnippet?.takeIf { it.isNotBlank() }?.let { traceSnippet ->
                        item {
                            Text(
                                text = stringResource(
                                    R.string.logs_generation_diag_trace,
                                    traceSnippet.take(600)
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.tombstoneSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_tombstone, summary),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.traceSha256?.let { traceHash ->
                        item {
                            Text(
                                text = stringResource(
                                    R.string.logs_generation_diag_trace_identity,
                                    exitSnapshot.traceFormat ?: stringResource(R.string.logs_generation_diag_unknown),
                                    exitSnapshot.traceByteCount ?: 0,
                                    traceHash
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.deviceSummary?.let { summary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_device, summary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    exitSnapshot.memorySummary?.let { summary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_memory, summary),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.activeSessionDetails.takeIf { it.isNotEmpty() }?.let { sessions ->
                        item {
                            Text(
                                text = stringResource(
                                    R.string.logs_generation_diag_session_details,
                                    sessions.joinToString("\n")
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.correlationSummary?.let { summary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_correlation, summary),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.agentJournalSummary?.let { summary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_agent_journal, summary),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    exitSnapshot.relatedProcessSummary?.let { summary ->
                        item {
                            Text(
                                text = stringResource(R.string.logs_generation_diag_related_processes, summary),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }

                if (breadcrumbs.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                    }
                    item {
                        Text(
                            text = stringResource(R.string.logs_generation_diag_recent),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(breadcrumbs.reversed()) { breadcrumb ->
                        Text(
                            text = buildBreadcrumbLine(breadcrumb, dateFormat),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RpcLogsContent(
    logs: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    if (logs.isEmpty()) {
        EmptyLogsPlaceholder(
            stringResource(R.string.logs_no_rpc),
            stringResource(R.string.logs_no_rpc_desc)
        )
    } else {
        // Hacker-style terminal card
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column {
                // Terminal header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Fake window buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF5F56)))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFBD2E)))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF27CA40)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.logs_terminal_header),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color(0xFF888888)
                    )
                }
                
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    reverseLayout = true
                ) {
                    items(logs.reversed()) { line ->
                        val isTimestamp = line.startsWith("[")
                        val isError = line.contains("error", ignoreCase = true) || 
                                      line.contains("failed", ignoreCase = true)
                        val isConnection = line.contains("connection", ignoreCase = true) ||
                                           line.contains("accepted", ignoreCase = true)
                        val isHeader = line.startsWith("===")
                        
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = when {
                                isHeader -> Color(0xFF00FFFF) // Cyan for headers
                                isError -> Color(0xFFFF4444)  // Red for errors
                                isConnection -> Color(0xFF00FF41) // Matrix green
                                isTimestamp && line.contains("]") -> Color(0xFF00FF41).copy(alpha = 0.8f)
                                else -> Color(0xFF00FF41).copy(alpha = 0.7f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogsPlaceholder(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun buildAppLogExport(
    logs: List<LogEntry>,
    dateFormat: SimpleDateFormat
): String = logs.joinToString("\n") { "[${dateFormat.format(Date(it.timestamp))}] ${it.message}" }

private fun shareDiagnosticsExport(
    context: Context,
    exportText: String
) {
    runCatching {
        val exportDir = File(context.cacheDir, "log_exports").apply { mkdirs() }
        exportDir.listFiles()?.filter {
            System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L
        }?.forEach { it.delete() }
        val timestamp = System.currentTimeMillis()
        val traceFile = GenerationDiagnosticsStore.latestTraceFileForExport()
        val exportFile = if (traceFile != null) {
            File(exportDir, "generation_diagnostics_$timestamp.zip").also { zipFile ->
                ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("generation_diagnostics.txt"))
                    zip.write(exportText.toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("last_exit_trace.bin"))
                    traceFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } else {
            File(exportDir, "generation_diagnostics_$timestamp.txt").also {
                it.writeText(exportText)
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (traceFile != null) "application/zip" else "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.logs_generation_diag_title))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.action_share)))
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(R.string.logs_share_failed, error.message ?: context.getString(R.string.error_generic)),
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun buildDiagnosticsExport(
    context: Context,
    exitSnapshot: GenerationExitSnapshot?,
    breadcrumbs: List<GenerationBreadcrumb>,
    dateFormat: SimpleDateFormat
): String {
    if (exitSnapshot == null && breadcrumbs.isEmpty()) return ""

    val lines = mutableListOf<String>()
    lines += context.getString(R.string.logs_generation_diag_title)
    exitSnapshot?.let { snapshot ->
        snapshot.appVersion?.let {
            lines += context.getString(R.string.logs_generation_diag_app_version, it)
        }
        lines += context.getString(
            R.string.logs_generation_diag_reason,
            snapshot.reasonLabel,
            snapshot.reasonCode
        )
        lines += context.getString(
            R.string.logs_generation_diag_time,
            dateFormat.format(Date(snapshot.timestamp))
        )
        lines += context.getString(
            R.string.logs_generation_diag_process,
            snapshot.processName ?: context.getString(R.string.logs_generation_diag_unknown),
            snapshot.pid?.toString() ?: context.getString(R.string.logs_generation_diag_unknown),
            snapshot.status,
            snapshot.importance
        )
        if (snapshot.processPssKb != null || snapshot.processRssKb != null) {
            lines += context.getString(
                R.string.logs_generation_diag_process_memory,
                snapshot.processPssKb?.toString() ?: "—",
                snapshot.processRssKb?.toString() ?: "—"
            )
        }
        snapshot.sessionSummary?.let {
            lines += context.getString(R.string.logs_generation_diag_session, it)
        }
        snapshot.description?.let {
            lines += context.getString(R.string.logs_generation_diag_description, it)
        }
        snapshot.traceSnippet?.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(R.string.logs_generation_diag_trace, it)
        }
        snapshot.tombstoneSummary?.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(R.string.logs_generation_diag_tombstone, it)
        }
        snapshot.traceSha256?.let {
            lines += context.getString(
                R.string.logs_generation_diag_trace_identity,
                snapshot.traceFormat ?: context.getString(R.string.logs_generation_diag_unknown),
                snapshot.traceByteCount ?: 0,
                it
            )
        }
        snapshot.deviceSummary?.let {
            lines += context.getString(R.string.logs_generation_diag_device, it)
        }
        snapshot.memorySummary?.let {
            lines += context.getString(R.string.logs_generation_diag_memory, it)
        }
        snapshot.activeSessionDetails.takeIf { it.isNotEmpty() }?.let {
            lines += context.getString(R.string.logs_generation_diag_session_details, it.joinToString("\n"))
        }
        snapshot.correlationSummary?.let {
            lines += context.getString(R.string.logs_generation_diag_correlation, it)
        }
        snapshot.agentJournalSummary?.let {
            lines += context.getString(R.string.logs_generation_diag_agent_journal, it)
        }
        snapshot.relatedProcessSummary?.let {
            lines += context.getString(R.string.logs_generation_diag_related_processes, it)
        }
    }
    if (breadcrumbs.isNotEmpty()) {
        lines += context.getString(R.string.logs_generation_diag_recent)
        lines += breadcrumbs.map { buildBreadcrumbLine(it, dateFormat) }
    }
    return lines.joinToString("\n")
}

private fun buildBreadcrumbLine(
    breadcrumb: GenerationBreadcrumb,
    dateFormat: SimpleDateFormat
): String {
    return buildString {
        append("[${dateFormat.format(Date(breadcrumb.timestamp))}] ")
        append(breadcrumb.source)
        breadcrumb.mode?.let { append(" $it") }
        append(" ${breadcrumb.event}")
        breadcrumb.phase?.let { append(" phase=$it") }
        breadcrumb.details?.let { append(" $it") }
    }
}
