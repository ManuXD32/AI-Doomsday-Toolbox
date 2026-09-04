package com.example.llamadroid.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.ui.components.AppSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

internal data class BenchmarkRunKey(
    val modelPath: String,
    val runStartedAt: Long
)

internal data class BenchmarkChartRun(
    val key: BenchmarkRunKey,
    val modelName: String,
    val runName: String,
    val runStartedAt: Long,
    val results: List<BenchmarkResult>
)

private enum class BenchmarkChartType {
    GenerationByThreads,
    PromptByThreads,
    BestThreads
}

private data class BenchmarkChartSeries(
    val run: BenchmarkChartRun,
    val label: String,
    val color: Color
) {
    val results: List<BenchmarkResult> = run.results.sortedBy { it.threads }
    val bestPrompt: BenchmarkResult? = results.maxByOrNull { it.promptTokensPerSecond }
    val bestGeneration: BenchmarkResult? = results.maxByOrNull { it.genTokensPerSecond }
}

private data class BenchmarkChartText(
    val threads: String,
    val tokensPerSecond: String,
    val prompt: String,
    val generation: String,
    val bestPrompt: String,
    val bestGeneration: String,
    val noData: String
)

private val benchmarkChartPalette = listOf(
    Color(0xFF2563EB),
    Color(0xFFDC2626),
    Color(0xFF059669),
    Color(0xFFD97706),
    Color(0xFF7C3AED),
    Color(0xFF0891B2),
    Color(0xFFBE123C),
    Color(0xFF4D7C0F),
    Color(0xFF9333EA),
    Color(0xFF0F766E)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BenchmarkChartsCard(
    runs: List<BenchmarkChartRun>,
    selectedRunKeys: Set<BenchmarkRunKey>,
    onSelectVisible: () -> Unit,
    onClearSelection: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var chartType by remember { mutableStateOf(BenchmarkChartType.GenerationByThreads) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var pendingPng by remember { mutableStateOf<ByteArray?>(null) }
    val selectedRuns = remember(runs, selectedRunKeys) {
        runs.filter { it.key in selectedRunKeys }
    }
    val untitledRun = stringResource(R.string.benchmark_untitled_run)
    val series = remember(selectedRuns, untitledRun) { selectedRuns.toChartSeries(untitledRun) }
    val chartText = BenchmarkChartText(
        threads = stringResource(R.string.benchmark_threads),
        tokensPerSecond = stringResource(R.string.benchmark_chart_tokens_per_second),
        prompt = stringResource(R.string.benchmark_metric_prompt),
        generation = stringResource(R.string.benchmark_metric_generation),
        bestPrompt = stringResource(R.string.benchmark_chart_best_prompt),
        bestGeneration = stringResource(R.string.benchmark_chart_best_generation),
        noData = stringResource(R.string.benchmark_chart_no_data)
    )

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri != null && csv != null) {
            scope.launch(Dispatchers.IO) {
                val result = writeTextToUri(context, uri, csv)
                withContext(Dispatchers.Main) {
                    showExportToast(context, "CSV", result)
                }
            }
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        val png = pendingPng
        pendingPng = null
        if (uri != null && png != null) {
            scope.launch(Dispatchers.IO) {
                val result = writeBytesToUri(context, uri, png)
                withContext(Dispatchers.Main) {
                    showExportToast(context, "PNG", result)
                }
            }
        }
    }

    AppSectionCard {
        Text(
            text = stringResource(R.string.benchmark_charts_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = stringResource(R.string.benchmark_charts_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                BenchmarkChartType.GenerationByThreads,
                BenchmarkChartType.PromptByThreads,
                BenchmarkChartType.BestThreads
            ).forEach { type ->
                FilterChip(
                    selected = chartType == type,
                    onClick = { chartType = type },
                    label = { Text(stringResource(type.labelRes)) }
                )
            }
        }

        Text(
            text = stringResource(
                R.string.benchmark_chart_selected_count,
                selectedRuns.size,
                runs.size
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSelectVisible,
                enabled = runs.isNotEmpty()
            ) {
                Icon(Icons.Default.SelectAll, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.benchmark_chart_select_visible))
            }
            OutlinedButton(
                onClick = onClearSelection,
                enabled = selectedRunKeys.isNotEmpty()
            ) {
                Icon(Icons.Default.ClearAll, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.benchmark_chart_clear_selection))
            }
        }

        if (selectedRuns.isEmpty()) {
            Text(
                text = stringResource(R.string.benchmark_chart_empty_selection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val chartHeight = when (chartType) {
                BenchmarkChartType.BestThreads -> (180 + series.size * 44).coerceIn(280, 900).dp
                else -> 320.dp
            }
            BenchmarkChartCanvas(
                series = series,
                chartType = chartType,
                chartText = chartText,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
            )
            BenchmarkChartLegend(series = series)
            BenchmarkBestThreadSummary(series = series)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        pendingCsv = buildBenchmarkChartCsv(selectedRuns, chartType, untitledRun)
                        csvLauncher.launch(benchmarkExportFilename(chartType, "csv"))
                    }
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.benchmark_chart_export_csv))
                }
                OutlinedButton(
                    onClick = {
                        val pngBytes = renderBenchmarkChartPng(context, selectedRuns, chartType)
                        if (pngBytes == null) {
                            Toast.makeText(
                                context,
                                resources.getString(
                                    R.string.benchmark_export_failed,
                                    "PNG",
                                    resources.getString(R.string.benchmark_chart_no_data)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            pendingPng = pngBytes
                            pngLauncher.launch(benchmarkExportFilename(chartType, "png"))
                        }
                    }
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.benchmark_chart_export_png))
                }
            }
        }
    }
}

@Composable
private fun BenchmarkChartCanvas(
    series: List<BenchmarkChartSeries>,
    chartType: BenchmarkChartType,
    chartText: BenchmarkChartText,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val surface = MaterialTheme.colorScheme.surface

    Canvas(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(8.dp)
    ) {
        when (chartType) {
            BenchmarkChartType.GenerationByThreads -> drawThreadLineChart(
                series = series,
                metric = BenchmarkChartType.GenerationByThreads,
                chartText = chartText,
                gridColor = grid,
                labelColor = label,
                axisColor = axis,
                pointFill = surface
            )
            BenchmarkChartType.PromptByThreads -> drawThreadLineChart(
                series = series,
                metric = BenchmarkChartType.PromptByThreads,
                chartText = chartText,
                gridColor = grid,
                labelColor = label,
                axisColor = axis,
                pointFill = surface
            )
            BenchmarkChartType.BestThreads -> drawBestThreadChart(
                series = series,
                chartText = chartText,
                gridColor = grid,
                labelColor = label,
                axisColor = axis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchmarkChartLegend(series: List<BenchmarkChartSeries>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.benchmark_chart_legend),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            series.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(item.color)
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchmarkBestThreadSummary(series: List<BenchmarkChartSeries>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.benchmark_chart_best_threads_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        series.forEach { item ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.bestPrompt?.let { result ->
                            stringResource(
                                R.string.benchmark_chart_best_prompt_detail,
                                formatChartNumber(result.promptTokensPerSecond),
                                result.threads
                            )
                        } ?: stringResource(R.string.benchmark_chart_no_data),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = item.bestGeneration?.let { result ->
                            stringResource(
                                R.string.benchmark_chart_best_generation_detail,
                                formatChartNumber(result.genTokensPerSecond),
                                result.threads
                            )
                        } ?: stringResource(R.string.benchmark_chart_no_data),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawThreadLineChart(
    series: List<BenchmarkChartSeries>,
    metric: BenchmarkChartType,
    chartText: BenchmarkChartText,
    gridColor: Color,
    labelColor: Color,
    axisColor: Color,
    pointFill: Color
) {
    val values = series.flatMap { item -> item.results.map { it.chartMetric(metric) } }
    val allThreads = series.flatMap { item -> item.results.map { it.threads } }
    if (values.isEmpty() || allThreads.isEmpty()) {
        drawChartText(chartText.noData, size.width / 2f, size.height / 2f, labelColor, align = AndroidPaint.Align.CENTER)
        return
    }

    val minThread = allThreads.minOrNull() ?: 1
    val maxThread = allThreads.maxOrNull() ?: minThread
    val yMax = niceChartMax(values.maxOrNull() ?: 1f)
    val left = 58.dp.toPx()
    val top = 30.dp.toPx()
    val right = 14.dp.toPx()
    val bottom = 46.dp.toPx()
    val plotWidth = (size.width - left - right).coerceAtLeast(1f)
    val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)
    val axisBottom = top + plotHeight

    drawChartText(chartText.tokensPerSecond, left, 18.dp.toPx(), labelColor, textSize = 10.sp.toPx())

    repeat(5) { index ->
        val fraction = index / 4f
        val y = axisBottom - plotHeight * fraction
        val value = yMax * fraction
        drawLine(gridColor, Offset(left, y), Offset(left + plotWidth, y), strokeWidth = 1.dp.toPx())
        drawChartText(formatChartNumber(value), left - 6.dp.toPx(), y + 4.dp.toPx(), labelColor, textSize = 10.sp.toPx(), align = AndroidPaint.Align.RIGHT)
    }

    drawLine(axisColor, Offset(left, top), Offset(left, axisBottom), strokeWidth = 1.4.dp.toPx())
    drawLine(axisColor, Offset(left, axisBottom), Offset(left + plotWidth, axisBottom), strokeWidth = 1.4.dp.toPx())

    xAxisLabels(minThread, maxThread).forEach { thread ->
        val x = chartX(thread, minThread, maxThread, left, plotWidth)
        drawLine(gridColor.copy(alpha = 0.6f), Offset(x, top), Offset(x, axisBottom), strokeWidth = 1.dp.toPx())
        drawChartText(thread.toString(), x, size.height - 18.dp.toPx(), labelColor, textSize = 10.sp.toPx(), align = AndroidPaint.Align.CENTER)
    }
    drawChartText(chartText.threads, left + plotWidth / 2f, size.height - 4.dp.toPx(), labelColor, textSize = 10.sp.toPx(), align = AndroidPaint.Align.CENTER)

    series.forEach { item ->
        var previous: Offset? = null
        item.results.forEach { result ->
            val value = result.chartMetric(metric)
            val x = chartX(result.threads, minThread, maxThread, left, plotWidth)
            val y = axisBottom - (value / yMax).coerceIn(0f, 1f) * plotHeight
            val point = Offset(x, y)
            previous?.let { start ->
                drawLine(item.color, start, point, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(item.color, radius = 4.5.dp.toPx(), center = point)
            drawCircle(pointFill, radius = 2.dp.toPx(), center = point)
            previous = point
        }
    }
}

private fun DrawScope.drawBestThreadChart(
    series: List<BenchmarkChartSeries>,
    chartText: BenchmarkChartText,
    gridColor: Color,
    labelColor: Color,
    axisColor: Color
) {
    if (series.isEmpty()) {
        drawChartText(chartText.noData, size.width / 2f, size.height / 2f, labelColor, align = AndroidPaint.Align.CENTER)
        return
    }

    val maxPrompt = niceChartMax(series.mapNotNull { it.bestPrompt?.promptTokensPerSecond }.maxOrNull() ?: 1f)
    val maxGeneration = niceChartMax(series.mapNotNull { it.bestGeneration?.genTokensPerSecond }.maxOrNull() ?: 1f)
    val left = 104.dp.toPx()
    val top = 54.dp.toPx()
    val right = 12.dp.toPx()
    val rowHeight = 42.dp.toPx()
    val columnGap = 10.dp.toPx()
    val plotWidth = (size.width - left - right).coerceAtLeast(1f)
    val columnWidth = (plotWidth - columnGap) / 2f

    drawChartText(chartText.bestPrompt, left, 22.dp.toPx(), labelColor, textSize = 11.sp.toPx(), bold = true)
    drawChartText(chartText.bestGeneration, left + columnWidth + columnGap, 22.dp.toPx(), labelColor, textSize = 11.sp.toPx(), bold = true)
    drawLine(axisColor, Offset(left, top - 10.dp.toPx()), Offset(left + plotWidth, top - 10.dp.toPx()), strokeWidth = 1.dp.toPx())

    series.forEachIndexed { index, item ->
        val rowTop = top + index * rowHeight
        val labelY = rowTop + 18.dp.toPx()
        drawChartText(shortChartLabel(item.label, 16), 6.dp.toPx(), labelY, labelColor, textSize = 10.sp.toPx())

        item.bestPrompt?.let { result ->
            drawMetricBar(
                x = left,
                y = rowTop,
                width = columnWidth,
                value = result.promptTokensPerSecond,
                maxValue = maxPrompt,
                color = item.color,
                label = "${formatChartNumber(result.promptTokensPerSecond)} @ ${result.threads}t",
                labelColor = labelColor,
                trackColor = gridColor
            )
        }
        item.bestGeneration?.let { result ->
            drawMetricBar(
                x = left + columnWidth + columnGap,
                y = rowTop,
                width = columnWidth,
                value = result.genTokensPerSecond,
                maxValue = maxGeneration,
                color = item.color,
                label = "${formatChartNumber(result.genTokensPerSecond)} @ ${result.threads}t",
                labelColor = labelColor,
                trackColor = gridColor
            )
        }
    }
}

private fun DrawScope.drawMetricBar(
    x: Float,
    y: Float,
    width: Float,
    value: Float,
    maxValue: Float,
    color: Color,
    label: String,
    labelColor: Color,
    trackColor: Color
) {
    val barY = y + 20.dp.toPx()
    val barHeight = 12.dp.toPx()
    val radius = 6.dp.toPx()
    drawRoundRect(
        color = trackColor.copy(alpha = 0.45f),
        topLeft = Offset(x, barY),
        size = Size(width, barHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = color.copy(alpha = 0.86f),
        topLeft = Offset(x, barY),
        size = Size(width * (value / maxValue).coerceIn(0f, 1f), barHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    )
    drawChartText(label, x, barY - 3.dp.toPx(), labelColor, textSize = 9.sp.toPx())
}

private fun DrawScope.drawChartText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    textSize: Float = 11.sp.toPx(),
    align: AndroidPaint.Align = AndroidPaint.Align.LEFT,
    bold: Boolean = false
) {
    drawIntoCanvas { canvas ->
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            this.textSize = textSize
            textAlign = align
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}

private fun List<BenchmarkChartRun>.toChartSeries(untitledRun: String): List<BenchmarkChartSeries> {
    return mapIndexed { index, run ->
        BenchmarkChartSeries(
            run = run,
            label = run.displayLabel(untitledRun),
            color = benchmarkChartPalette[index % benchmarkChartPalette.size]
        )
    }
}

private fun BenchmarkChartRun.displayLabel(untitledRun: String): String {
    val runLabel = runName.ifBlank { untitledRun }
    return "$modelName · $runLabel"
}

private val BenchmarkChartType.labelRes: Int
    get() = when (this) {
        BenchmarkChartType.GenerationByThreads -> R.string.benchmark_chart_type_generation_threads
        BenchmarkChartType.PromptByThreads -> R.string.benchmark_chart_type_prompt_threads
        BenchmarkChartType.BestThreads -> R.string.benchmark_chart_type_best_threads
    }

private val BenchmarkChartType.exportKey: String
    get() = when (this) {
        BenchmarkChartType.GenerationByThreads -> "generation-by-threads"
        BenchmarkChartType.PromptByThreads -> "prompt-by-threads"
        BenchmarkChartType.BestThreads -> "best-threads"
    }

private fun BenchmarkResult.chartMetric(metric: BenchmarkChartType): Float {
    return when (metric) {
        BenchmarkChartType.GenerationByThreads -> genTokensPerSecond
        BenchmarkChartType.PromptByThreads -> promptTokensPerSecond
        BenchmarkChartType.BestThreads -> max(promptTokensPerSecond, genTokensPerSecond)
    }
}

private fun chartX(thread: Int, minThread: Int, maxThread: Int, left: Float, plotWidth: Float): Float {
    if (maxThread == minThread) {
        return left + plotWidth / 2f
    }
    return left + (thread - minThread).toFloat() / (maxThread - minThread).toFloat() * plotWidth
}

private fun xAxisLabels(minThread: Int, maxThread: Int): List<Int> {
    if (minThread == maxThread) return listOf(minThread)
    val range = minThread..maxThread
    val values = range.toList()
    return if (values.size <= 7) {
        values
    } else {
        listOf(minThread, (minThread + maxThread) / 2, maxThread).distinct()
    }
}

private fun niceChartMax(raw: Float): Float {
    val safe = raw.coerceAtLeast(1f)
    return when {
        safe <= 10f -> 10f
        safe <= 25f -> 25f
        safe <= 50f -> 50f
        safe <= 100f -> 100f
        safe <= 250f -> 250f
        safe <= 500f -> 500f
        safe <= 1000f -> 1000f
        else -> ((safe / 500f).toInt() + 1) * 500f
    }
}

private fun formatChartNumber(value: Float): String {
    return String.format(Locale.US, "%.1f", value)
}

private fun shortChartLabel(label: String, maxChars: Int): String {
    return if (label.length <= maxChars) label else label.take(maxChars - 1) + "…"
}

private fun benchmarkExportFilename(chartType: BenchmarkChartType, extension: String): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "benchmark-${chartType.exportKey}-$timestamp.$extension"
}

private fun buildBenchmarkChartCsv(
    runs: List<BenchmarkChartRun>,
    chartType: BenchmarkChartType,
    untitledRun: String
): String {
    val builder = StringBuilder()
    builder.appendCsvRow(
        listOf(
            "chart_type",
            "run_name",
            "model_name",
            "model_path",
            "run_started_at",
            "run_started_label",
            "threads",
            "prompt_tokens_per_second",
            "generation_tokens_per_second",
            "prompt_tokens",
            "generation_tokens",
            "best_prompt_threads",
            "best_generation_threads"
        )
    )
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    runs.forEach { run ->
        val bestPrompt = run.results.maxByOrNull { it.promptTokensPerSecond }
        val bestGeneration = run.results.maxByOrNull { it.genTokensPerSecond }
        run.results.sortedBy { it.threads }.forEach { result ->
            builder.appendCsvRow(
                listOf(
                    chartType.exportKey,
                    run.runName.ifBlank { untitledRun },
                    run.modelName,
                    run.key.modelPath,
                    run.runStartedAt.toString(),
                    dateFormat.format(Date(run.runStartedAt)),
                    result.threads.toString(),
                    String.format(Locale.US, "%.4f", result.promptTokensPerSecond),
                    String.format(Locale.US, "%.4f", result.genTokensPerSecond),
                    result.promptTokens.toString(),
                    result.genTokens.toString(),
                    bestPrompt?.threads?.toString().orEmpty(),
                    bestGeneration?.threads?.toString().orEmpty()
                )
            )
        }
    }
    return builder.toString()
}

private fun StringBuilder.appendCsvRow(values: List<String>) {
    append(values.joinToString(",") { value ->
        "\"${value.replace("\"", "\"\"")}\""
    })
    append('\n')
}

private fun renderBenchmarkChartPng(
    context: Context,
    runs: List<BenchmarkChartRun>,
    chartType: BenchmarkChartType
): ByteArray? {
    if (runs.isEmpty()) return null
    val series = runs.toChartSeries(context.getString(R.string.benchmark_untitled_run))
    val chartText = BenchmarkChartText(
        threads = context.getString(R.string.benchmark_threads),
        tokensPerSecond = context.getString(R.string.benchmark_chart_tokens_per_second),
        prompt = context.getString(R.string.benchmark_metric_prompt),
        generation = context.getString(R.string.benchmark_metric_generation),
        bestPrompt = context.getString(R.string.benchmark_chart_best_prompt),
        bestGeneration = context.getString(R.string.benchmark_chart_best_generation),
        noData = context.getString(R.string.benchmark_chart_no_data)
    )
    val width = 1400
    val legendRows = (series.size + 1) / 2
    val height = when (chartType) {
        BenchmarkChartType.BestThreads -> (280 + series.size * 74 + legendRows * 36).coerceAtLeast(760)
        else -> (840 + legendRows * 36).coerceAtLeast(900)
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    val titlePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(17, 24, 39)
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(75, 85, 99)
        textSize = 22f
    }
    canvas.drawText(context.getString(chartType.labelRes), 48f, 58f, titlePaint)
    canvas.drawText(context.getString(R.string.benchmark_charts_title), 48f, 92f, labelPaint)

    when (chartType) {
        BenchmarkChartType.GenerationByThreads,
        BenchmarkChartType.PromptByThreads -> drawPngLineChart(canvas, series, chartType, chartText, width)
        BenchmarkChartType.BestThreads -> drawPngBestThreadChart(canvas, series, chartText, width)
    }
    drawPngLegend(canvas, series, top = height - ((series.size + 1) / 2) * 36 - 36, width = width)

    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }
}

private fun drawPngLineChart(
    canvas: AndroidCanvas,
    series: List<BenchmarkChartSeries>,
    metric: BenchmarkChartType,
    chartText: BenchmarkChartText,
    width: Int
) {
    val values = series.flatMap { item -> item.results.map { it.chartMetric(metric) } }
    val allThreads = series.flatMap { item -> item.results.map { it.threads } }
    if (values.isEmpty() || allThreads.isEmpty()) return
    val minThread = allThreads.minOrNull() ?: 1
    val maxThread = allThreads.maxOrNull() ?: minThread
    val yMax = niceChartMax(values.maxOrNull() ?: 1f)
    val left = 118f
    val top = 150f
    val right = 52f
    val plotWidth = width - left - right
    val plotHeight = 520f
    val axisBottom = top + plotHeight
    val gridPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(209, 213, 219)
        strokeWidth = 2f
    }
    val axisPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(55, 65, 81)
        strokeWidth = 3f
    }
    val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(75, 85, 99)
        textSize = 20f
    }
    canvas.drawText(chartText.tokensPerSecond, left, top - 28f, textPaint)
    repeat(5) { index ->
        val fraction = index / 4f
        val y = axisBottom - plotHeight * fraction
        canvas.drawLine(left, y, left + plotWidth, y, gridPaint)
        textPaint.textAlign = AndroidPaint.Align.RIGHT
        canvas.drawText(formatChartNumber(yMax * fraction), left - 14f, y + 7f, textPaint)
        textPaint.textAlign = AndroidPaint.Align.LEFT
    }
    canvas.drawLine(left, top, left, axisBottom, axisPaint)
    canvas.drawLine(left, axisBottom, left + plotWidth, axisBottom, axisPaint)
    xAxisLabels(minThread, maxThread).forEach { thread ->
        val x = chartX(thread, minThread, maxThread, left, plotWidth)
        canvas.drawLine(x, top, x, axisBottom, gridPaint)
        textPaint.textAlign = AndroidPaint.Align.CENTER
        canvas.drawText(thread.toString(), x, axisBottom + 32f, textPaint)
        textPaint.textAlign = AndroidPaint.Align.LEFT
    }
    textPaint.textAlign = AndroidPaint.Align.CENTER
    canvas.drawText(chartText.threads, left + plotWidth / 2f, axisBottom + 68f, textPaint)
    textPaint.textAlign = AndroidPaint.Align.LEFT

    series.forEach { item ->
        val linePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = item.color.toArgb()
            strokeWidth = 7f
            strokeCap = AndroidPaint.Cap.ROUND
        }
        val pointPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = item.color.toArgb()
        }
        var previous: android.graphics.PointF? = null
        item.results.forEach { result ->
            val value = result.chartMetric(metric)
            val x = chartX(result.threads, minThread, maxThread, left, plotWidth)
            val y = axisBottom - (value / yMax).coerceIn(0f, 1f) * plotHeight
            previous?.let { point -> canvas.drawLine(point.x, point.y, x, y, linePaint) }
            canvas.drawCircle(x, y, 9f, pointPaint)
            previous = android.graphics.PointF(x, y)
        }
    }
}

private fun drawPngBestThreadChart(
    canvas: AndroidCanvas,
    series: List<BenchmarkChartSeries>,
    chartText: BenchmarkChartText,
    width: Int
) {
    val maxPrompt = niceChartMax(series.mapNotNull { it.bestPrompt?.promptTokensPerSecond }.maxOrNull() ?: 1f)
    val maxGeneration = niceChartMax(series.mapNotNull { it.bestGeneration?.genTokensPerSecond }.maxOrNull() ?: 1f)
    val left = 280f
    val top = 170f
    val right = 54f
    val columnGap = 28f
    val plotWidth = width - left - right
    val columnWidth = (plotWidth - columnGap) / 2f
    val rowHeight = 72f
    val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(55, 65, 81)
        textSize = 22f
    }
    val headerPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(17, 24, 39)
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(chartText.bestPrompt, left, top - 38f, headerPaint)
    canvas.drawText(chartText.bestGeneration, left + columnWidth + columnGap, top - 38f, headerPaint)
    series.forEachIndexed { index, item ->
        val rowTop = top + index * rowHeight
        canvas.drawText(shortChartLabel(item.label, 28), 48f, rowTop + 39f, textPaint)
        item.bestPrompt?.let { result ->
            drawPngMetricBar(
                canvas = canvas,
                x = left,
                y = rowTop,
                width = columnWidth,
                value = result.promptTokensPerSecond,
                maxValue = maxPrompt,
                color = item.color,
                label = "${formatChartNumber(result.promptTokensPerSecond)} t/s @ ${result.threads}t"
            )
        }
        item.bestGeneration?.let { result ->
            drawPngMetricBar(
                canvas = canvas,
                x = left + columnWidth + columnGap,
                y = rowTop,
                width = columnWidth,
                value = result.genTokensPerSecond,
                maxValue = maxGeneration,
                color = item.color,
                label = "${formatChartNumber(result.genTokensPerSecond)} t/s @ ${result.threads}t"
            )
        }
    }
}

private fun drawPngMetricBar(
    canvas: AndroidCanvas,
    x: Float,
    y: Float,
    width: Float,
    value: Float,
    maxValue: Float,
    color: Color,
    label: String
) {
    val trackPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.rgb(229, 231, 235)
    }
    val barPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
    }
    val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.rgb(75, 85, 99)
        textSize = 19f
    }
    val barY = y + 36f
    canvas.drawRoundRect(RectF(x, barY, x + width, barY + 18f), 9f, 9f, trackPaint)
    canvas.drawRoundRect(
        RectF(x, barY, x + width * (value / maxValue).coerceIn(0f, 1f), barY + 18f),
        9f,
        9f,
        barPaint
    )
    canvas.drawText(label, x, barY - 8f, textPaint)
}

private fun drawPngLegend(
    canvas: AndroidCanvas,
    series: List<BenchmarkChartSeries>,
    top: Int,
    width: Int
) {
    val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(55, 65, 81)
        textSize = 20f
    }
    val dotPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val columnWidth = (width - 96f) / 2f
    series.forEachIndexed { index, item ->
        val column = index % 2
        val row = index / 2
        val x = 48f + column * columnWidth
        val y = top + row * 36f + 20f
        dotPaint.color = item.color.toArgb()
        canvas.drawCircle(x, y - 6f, 8f, dotPaint)
        canvas.drawText(shortChartLabel(item.label, 44), x + 20f, y, textPaint)
    }
}

private fun writeTextToUri(context: Context, uri: Uri, text: String): Result<Unit> {
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        } ?: error("Unable to open destination")
    }
}

private fun writeBytesToUri(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> {
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
        } ?: error("Unable to open destination")
    }
}

private fun showExportToast(context: Context, format: String, result: Result<Unit>) {
    val message = result.fold(
        onSuccess = { context.getString(R.string.benchmark_export_success, format) },
        onFailure = { error ->
            context.getString(
                R.string.benchmark_export_failed,
                format,
                error.message ?: context.getString(R.string.benchmark_chart_no_data)
            )
        }
    )
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
