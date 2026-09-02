package com.example.llamadroid.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.service.SystemStatsCollectionManager
import com.example.llamadroid.service.SystemStatsRepository
import com.example.llamadroid.util.CpuCoreStats
import com.example.llamadroid.util.StatsExport
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStatsSnapshot
import com.example.llamadroid.ui.components.AppScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.max

private data class StatsWindow(val hours: Int, val label: Int)
private data class ChartPoint(val timestampEpochMs: Long, val value: Float)

private val statsWindows = listOf(
    StatsWindow(0, R.string.stats_window_15m),
    StatsWindow(1, R.string.stats_window_1h),
    StatsWindow(2, R.string.stats_window_2h),
    StatsWindow(6, R.string.stats_window_6h),
    StatsWindow(12, R.string.stats_window_12h),
    StatsWindow(24, R.string.stats_window_24h)
)

@Composable
fun StatsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { SystemStatsRepository(context) }
    val monitor = remember { SystemMonitor(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var collectionEnabled by remember { mutableStateOf(SystemStatsCollectionManager.isEnabled(context)) }
    var collectorFailure by remember { mutableStateOf(SystemStatsCollectionManager.lastFailure(context)) }
    var selectedWindow by rememberSaveable { mutableStateOf(1) }
    var samples by remember { mutableStateOf<List<SystemStatsSnapshot>>(emptyList()) }
    var liveSnapshot by remember { mutableStateOf<SystemStatsSnapshot?>(null) }
    var refreshToken by remember { mutableLongStateOf(0L) }
    var exportRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val range = exportRange ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val json = repository.export(range.first, range.second)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            }
        }
    }

    LaunchedEffect(selectedWindow, collectionEnabled, refreshToken, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.IO) { monitor.warmUp() }
            delay(1_000L)
            while (currentCoroutineContext().isActive) {
                val until = System.currentTimeMillis()
                val duration = if (selectedWindow == 0) 15L * 60L * 1000L else selectedWindow.toLong() * 60L * 60L * 1000L
                val from = until - duration
                samples = withContext(Dispatchers.IO) { repository.getSamples(from, until) }
                liveSnapshot = withContext(Dispatchers.IO) { monitor.sample() }
                collectorFailure = SystemStatsCollectionManager.lastFailure(context)
                delay(if (collectionEnabled) 60_000L else 5_000L)
            }
        }
    }

    val current = liveSnapshot ?: samples.lastOrNull()
    val rangeEnd = System.currentTimeMillis()
    val rangeStart = rangeEnd - if (selectedWindow == 0) 15L * 60L * 1000L else selectedWindow.toLong() * 60L * 60L * 1000L

    AppScreenScaffold(
        title = stringResource(R.string.stats_title),
        subtitle = stringResource(R.string.stats_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = {
                exportRange = rangeStart to rangeEnd
                documentLauncher.launch("system-stats-${rangeEnd}.json")
            }) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.stats_export))
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.stats_collection_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.stats_collection_desc), style = MaterialTheme.typography.bodySmall)
                            collectorFailure?.let { failure ->
                                Text(
                                    stringResource(R.string.stats_collection_failure, failure),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2
                                )
                            }
                        }
                        Switch(checked = collectionEnabled, onCheckedChange = {
                            collectionEnabled = it
                            SystemStatsCollectionManager.setEnabled(context, it)
                            refreshToken++
                        })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statsWindows.forEachIndexed { index, window ->
                        FilterChip(
                            selected = selectedWindow == index,
                            onClick = { selectedWindow = index },
                            label = { Text(stringResource(window.label)) }
                        )
                    }
                }
            }
            item { CurrentStatsCard(current) }
            item { StatsChartCard(stringResource(R.string.stats_chart_cpu), samples.mapNotNull { it.cpu.totalUsagePercent?.let { value -> ChartPoint(it.timestampEpochMs, value) } }, Color(0xFF36A9E1)) }
            item { StatsChartCard(stringResource(R.string.stats_chart_memory), samples.mapNotNull { it.memory.usagePercent?.let { value -> ChartPoint(it.timestampEpochMs, value) } }, Color(0xFF68D391)) }
            item { StatsChartCard(stringResource(R.string.stats_chart_gpu), samples.mapNotNull { it.gpu.loadPercent?.let { value -> ChartPoint(it.timestampEpochMs, value) } }, Color(0xFFD990FF)) }
            item { StatsChartCard(stringResource(R.string.stats_chart_battery), samples.mapNotNull { it.battery.levelPercent?.toFloat()?.let { value -> ChartPoint(it.timestampEpochMs, value) } }, Color(0xFFFFC857)) }
            item {
                StatsChartCard(
                    stringResource(R.string.stats_chart_temperature),
                    samples.mapNotNull { sample ->
                        sample.temperatures.mapNotNull { it.celsius }.maxOrNull()?.let { value ->
                            ChartPoint(sample.timestampEpochMs, value)
                        }
                    },
                    Color(0xFFFF8F5A)
                )
            }
            item {
                StatsChartCard(
                    stringResource(R.string.stats_chart_swap),
                    samples.mapNotNull { sample ->
                        val used = sample.swap.usedBytes
                        val total = sample.swap.totalBytes
                        if (used != null && total != null && total > 0L) {
                            ChartPoint(sample.timestampEpochMs, used.toFloat() / total * 100f)
                        } else null
                    },
                    Color(0xFFFF79C6)
                )
            }
            item { CoreChartCard(samples, current) }
            item { TemperatureCard(current) }
            item { GpuCard(current) }
            item { BatteryCard(current) }
            item { MemoryCard(current) }
            item { SwapCard(current) }
            item { CapabilityCard(current) }
        }
    }
}

@Composable
private fun CurrentStatsCard(snapshot: SystemStatsSnapshot?) {
    StatsCollapsibleCard(stringResource(R.string.stats_current_title), snapshot?.device?.model ?: stringResource(R.string.stats_unavailable)) {
        if (snapshot == null) {
            Text(stringResource(R.string.stats_no_samples))
        } else {
            MetricRow(stringResource(R.string.stats_cpu), formatPercent(snapshot.cpu.totalUsagePercent))
            MetricRow(stringResource(R.string.stats_ram), formatPercent(snapshot.memory.usagePercent))
            MetricRow(stringResource(R.string.stats_gpu), formatPercent(snapshot.gpu.loadPercent))
            MetricRow(stringResource(R.string.stats_battery), snapshot.battery.levelPercent?.let { "$it%" } ?: stringResource(R.string.stats_unavailable))
            MetricRow(stringResource(R.string.stats_uptime), snapshot.uptimeSeconds?.let(::formatUptime) ?: stringResource(R.string.stats_unavailable))
        }
    }
}

@Composable
private fun CoreChartCard(samples: List<SystemStatsSnapshot>, current: SystemStatsSnapshot?) {
    val cores = (current?.cpu?.cores.orEmpty().ifEmpty { samples.lastOrNull()?.cpu?.cores.orEmpty() })
        .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
    StatsCollapsibleCard(
        stringResource(R.string.stats_chart_cores),
        if (cores.isEmpty()) stringResource(R.string.stats_unavailable) else cores.size.toString()
    ) {
        if (cores.isEmpty()) Text(stringResource(R.string.stats_unavailable))
        else cores.forEach { core ->
            MetricRow(
                core.name,
                listOfNotNull(
                    core.usagePercent?.let { formatPercent(it) },
                    core.frequencyMHz?.let { "$it MHz" }
                ).joinToString(" · ").ifBlank { stringResource(R.string.stats_unavailable) }
            )
        }
    }
}

@Composable
private fun TemperatureCard(snapshot: SystemStatsSnapshot?) {
    StatsCollapsibleCard(stringResource(R.string.stats_temperatures), snapshot?.temperatures?.size?.toString() ?: "0") {
        MetricRow(
            stringResource(R.string.stats_thermal_status),
            snapshot?.thermalStatus?.let { stringResource(R.string.stats_thermal_status_value, it) }
                ?: stringResource(R.string.stats_unavailable)
        )
        snapshot?.temperatures?.take(24)?.forEach { temperature ->
            MetricRow(temperature.name, temperature.celsius?.let { "%.1f °C".format(it) } ?: stringResource(R.string.stats_unavailable))
        } ?: Text(stringResource(R.string.stats_unavailable))
    }
}

@Composable
private fun BatteryCard(snapshot: SystemStatsSnapshot?) {
    val battery = snapshot?.battery
    StatsCollapsibleCard(stringResource(R.string.stats_battery), battery?.status ?: stringResource(R.string.stats_unavailable)) {
        MetricRow(stringResource(R.string.stats_level), battery?.levelPercent?.let { "$it%" } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_temperature), battery?.temperatureCelsius?.let { "%.1f °C".format(it) } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_current), battery?.currentMilliAmps?.let { "%.0f mA".format(it) } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_voltage), battery?.voltageVolts?.let { "%.3f V".format(it) } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_power), battery?.powerWatts?.let { "%.2f W".format(it) } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_health), battery?.health ?: stringResource(R.string.stats_unavailable))
    }
}

@Composable
private fun GpuCard(snapshot: SystemStatsSnapshot?) {
    val gpu = snapshot?.gpu
    StatsCollapsibleCard(stringResource(R.string.stats_gpu_details), gpu?.name ?: stringResource(R.string.stats_unavailable)) {
        MetricRow(stringResource(R.string.stats_gpu), formatPercent(gpu?.loadPercent))
        MetricRow(stringResource(R.string.stats_clock), gpu?.clockMHz?.let { "$it MHz" } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_max_clock), gpu?.maxClockMHz?.let { "$it MHz" } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_gpu_temperature), gpu?.temperatureCelsius?.let { "%.1f °C".format(it) } ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_gpu_memory), gpu?.memoryBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_governor), gpu?.governor ?: stringResource(R.string.stats_unavailable))
    }
}

@Composable
private fun CapabilityCard(snapshot: SystemStatsSnapshot?) {
    StatsCollapsibleCard(stringResource(R.string.stats_capabilities), stringResource(R.string.stats_sources_summary)) {
        val capabilities = listOf(
            stringResource(R.string.stats_cpu) to snapshot?.cpu?.availability,
            stringResource(R.string.stats_memory) to snapshot?.memory?.availability,
            stringResource(R.string.stats_gpu) to snapshot?.gpu?.availability,
            stringResource(R.string.stats_battery) to snapshot?.battery?.availability,
            stringResource(R.string.stats_swap) to snapshot?.swap?.availability,
            stringResource(R.string.stats_thermal_status) to snapshot?.thermalStatusAvailability
        )
        capabilities.forEach { (label, availability) ->
            MetricRow(
                label,
                when {
                    availability == null -> stringResource(R.string.stats_unavailable)
                    availability.available -> availability.source ?: stringResource(R.string.stats_available)
                    else -> availability.reason ?: stringResource(R.string.stats_unavailable)
                }
            )
        }
    }
}

@Composable
private fun MemoryCard(snapshot: SystemStatsSnapshot?) {
    val memory = snapshot?.memory
    StatsCollapsibleCard(stringResource(R.string.stats_memory), memory?.totalBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable)) {
        MetricRow(stringResource(R.string.stats_used), memory?.usedBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_available), memory?.availableBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_cache), memory?.cacheBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_anonymous), memory?.anonymousBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_slab), memory?.slabBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
    }
}

@Composable
private fun SwapCard(snapshot: SystemStatsSnapshot?) {
    val swap = snapshot?.swap
    StatsCollapsibleCard(stringResource(R.string.stats_swap), swap?.availability?.source ?: stringResource(R.string.stats_unavailable)) {
        MetricRow(stringResource(R.string.stats_swap_used), swap?.usedBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_swap_total), swap?.totalBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_zram_original), swap?.zramOriginalBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
        MetricRow(stringResource(R.string.stats_zram_compressed), swap?.zramCompressedBytes?.let(::formatBytes) ?: stringResource(R.string.stats_unavailable))
    }
}

@Composable
private fun StatsChartCard(title: String, points: List<ChartPoint>, color: Color) {
    val orderedPoints = remember(points) {
        points.filter { it.value.isFinite() }
            .sortedBy { it.timestampEpochMs }
            .fold(mutableListOf<ChartPoint>()) { result, point ->
            if (result.lastOrNull()?.timestampEpochMs != point.timestampEpochMs) result += point
            result
            }.toList()
    }
    StatsCollapsibleCard(title, orderedPoints.lastOrNull()?.let { "%.1f".format(it.value) } ?: stringResource(R.string.stats_unavailable)) {
        if (orderedPoints.isEmpty()) {
            Text(stringResource(R.string.stats_no_samples))
        } else {
            val scroll = rememberScrollState()
            val chartWidth = max(360, (orderedPoints.size - 1) * 28 + 32).dp
            Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                Canvas(Modifier.width(chartWidth).height(150.dp).padding(4.dp)) {
                    val values = orderedPoints.map { it.value }
                    val rawMin = values.minOrNull() ?: 0f
                    val rawMax = values.maxOrNull() ?: 1f
                    val rawSpan = rawMax - rawMin
                    val padding = if (rawSpan > 0.001f) rawSpan * 0.1f else 1f
                    val minValue = rawMin - padding
                    val maxValue = rawMax + padding
                    val span = (maxValue - minValue).coerceAtLeast(0.001f)
                    val firstTimestamp = orderedPoints.first().timestampEpochMs
                    val lastTimestamp = orderedPoints.last().timestampEpochMs
                    val timestampSpan = (lastTimestamp - firstTimestamp).coerceAtLeast(1L).toDouble()
                    val path = Path()
                    orderedPoints.forEachIndexed { index, point ->
                        // Use sample time, not list position. This preserves
                        // gaps and prevents a long history from collapsing into
                        // a center line when samples arrive at uneven intervals.
                        val x = if (orderedPoints.size == 1) {
                            size.width / 2f
                        } else {
                            ((point.timestampEpochMs - firstTimestamp).toDouble() / timestampSpan * size.width)
                                .toFloat()
                                .coerceIn(0f, size.width)
                        }
                        val y = size.height - ((point.value - minValue) / span * size.height)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        drawCircle(color, radius = 3.5f, center = Offset(x, y))
                    }
                    drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                    drawPath(path, color, style = Stroke(width = 3f))
                }
            }
            if (orderedPoints.size == 1) Text(stringResource(R.string.stats_chart_waiting), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatsCollapsibleCard(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

private fun formatPercent(value: Float?): String = value?.let { "%.1f%%".format(it) } ?: "—"
private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.2f GiB".format(value.toDouble() / (1024L * 1024L * 1024L))
    value >= 1024L * 1024L -> "%.1f MiB".format(value.toDouble() / (1024L * 1024L))
    else -> "%.0f KiB".format(value.toDouble() / 1024.0)
}
private fun formatUptime(seconds: Long): String = "${seconds / 86400}d ${(seconds % 86400) / 3600}h ${(seconds % 3600) / 60}m"
