package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.llamadroid.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

internal data class NativeHarnessProjectReviewLabels(
    val title: String,
    val refresh: String,
    val deliverables: String,
    val changes: String,
    val noChanges: String,
    val noDeliverables: String,
    val diff: String,
    val read: String,
    val open: String,
    val reveal: String,
    val workflows: String,
    val schedules: String,
    val todos: String,
    val noItems: String,
    val pending: String,
    val inProgress: String,
    val completed: String,
    val running: String,
    val failed: String,
    val cancelled: String,
    val interrupted: String,
    val scheduled: String,
    val overdue: String,
    val frequencyOnce: String,
    val frequencyEvery: String,
    val dayOne: String,
    val dayOther: String,
    val hourOne: String,
    val hourOther: String,
    val minuteOne: String,
    val minuteOther: String,
    val secondOne: String,
    val secondOther: String,
    val relativeNow: String,
    val relativeFuture: String,
    val relativeOverdue: String,
    val binaryFile: String,
    val oversizedFile: String,
    val missingPhase: String,
    val previous: String,
    val next: String,
)

@Composable
private fun projectReviewLabels() = NativeHarnessProjectReviewLabels(
    title = stringResource(R.string.harness_review_title),
    refresh = stringResource(R.string.harness_review_refresh),
    deliverables = stringResource(R.string.harness_review_deliverables),
    changes = stringResource(R.string.harness_review_changes),
    noChanges = stringResource(R.string.harness_review_no_changes),
    noDeliverables = stringResource(R.string.harness_review_no_deliverables),
    diff = stringResource(R.string.harness_review_diff),
    read = stringResource(R.string.harness_review_read),
    open = stringResource(R.string.harness_review_open),
    reveal = stringResource(R.string.harness_review_reveal),
    workflows = stringResource(R.string.harness_review_workflows),
    schedules = stringResource(R.string.harness_review_schedules),
    todos = stringResource(R.string.harness_review_todos),
    noItems = stringResource(R.string.harness_review_no_items),
    pending = stringResource(R.string.harness_review_pending),
    inProgress = stringResource(R.string.harness_review_in_progress),
    completed = stringResource(R.string.harness_review_completed),
    running = stringResource(R.string.harness_review_running),
    failed = stringResource(R.string.harness_review_failed),
    cancelled = stringResource(R.string.harness_review_cancelled),
    interrupted = stringResource(R.string.harness_review_interrupted),
    scheduled = stringResource(R.string.harness_review_scheduled),
    overdue = stringResource(R.string.harness_review_overdue),
    frequencyOnce = stringResource(R.string.harness_review_frequency_once),
    frequencyEvery = stringResource(R.string.harness_review_frequency_every),
    dayOne = stringResource(R.string.harness_review_unit_day_one),
    dayOther = stringResource(R.string.harness_review_unit_day_other),
    hourOne = stringResource(R.string.harness_review_unit_hour_one),
    hourOther = stringResource(R.string.harness_review_unit_hour_other),
    minuteOne = stringResource(R.string.harness_review_unit_minute_one),
    minuteOther = stringResource(R.string.harness_review_unit_minute_other),
    secondOne = stringResource(R.string.harness_review_unit_second_one),
    secondOther = stringResource(R.string.harness_review_unit_second_other),
    relativeNow = stringResource(R.string.harness_review_relative_now),
    relativeFuture = stringResource(R.string.harness_review_relative_future),
    relativeOverdue = stringResource(R.string.harness_review_relative_overdue),
    binaryFile = stringResource(R.string.harness_review_binary_file),
    oversizedFile = stringResource(R.string.harness_review_oversized_file),
    missingPhase = stringResource(R.string.harness_review_missing_phase),
    previous = stringResource(R.string.harness_history_previous_page),
    next = stringResource(R.string.harness_history_next_page),
)

internal sealed interface NativeHarnessProjectReviewUiAction {
    data object Refresh : NativeHarnessProjectReviewUiAction
    data class Diff(val index: Int) : NativeHarnessProjectReviewUiAction
    data class Read(val path: String, val offset: Int = 1) : NativeHarnessProjectReviewUiAction
    data class OpenChanged(val index: Int) : NativeHarnessProjectReviewUiAction
    data class OpenPresented(val sequence: Long, val turn: Int, val index: Int, val action: NativeHarnessProjectFileAction) : NativeHarnessProjectReviewUiAction
}

@Composable
internal fun NativeHarnessProjectReviewPanel(
    state: NativeHarnessProjectReviewState,
    onAction: (NativeHarnessProjectReviewUiAction) -> Unit,
    modifier: Modifier = Modifier,
    labels: NativeHarnessProjectReviewLabels = projectReviewLabels(),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 760.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onAction(NativeHarnessProjectReviewUiAction.Refresh) }) { Text(labels.refresh) }
            }
            state.errorCode?.let {
                Text(stringResource(R.string.harness_runtime_failure), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        NativeHarnessPresentedCard(state.presentedFiles, labels, onAction)
        NativeHarnessChangesCard(state, labels, onAction)
        NativeHarnessWorkflowCard(state.workflows, labels)
        NativeHarnessTodoCard(state.todos, labels)
        NativeHarnessScheduleCard(state.schedules, labels)
    }
}

@Composable
private fun NativeHarnessPresentedCard(
    files: List<NativeHarnessPresentedFile>,
    labels: NativeHarnessProjectReviewLabels,
    onAction: (NativeHarnessProjectReviewUiAction) -> Unit,
) {
    val pageState = remember(files) { mutableStateOf(0) }
    val page = nativeHarnessReviewPage(files, pageState.value)
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(labels.deliverables, style = MaterialTheme.typography.titleSmall)
        if (files.isEmpty()) Text(labels.noDeliverables, style = MaterialTheme.typography.bodySmall)
        page.items.forEach { file ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(file.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                file.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { onAction(NativeHarnessProjectReviewUiAction.Read(file.path)) }) { Text(labels.read) }
                    Button(onClick = {
                        onAction(NativeHarnessProjectReviewUiAction.OpenPresented(file.sequence, file.turn, file.index, NativeHarnessProjectFileAction.OPEN))
                    }) { Text(labels.open) }
                    OutlinedButton(onClick = {
                        onAction(NativeHarnessProjectReviewUiAction.OpenPresented(file.sequence, file.turn, file.index, NativeHarnessProjectFileAction.REVEAL))
                    }) { Text(labels.reveal) }
                }
            }
        }
        NativeHarnessPager(page.pageIndex, page.pageCount, labels) { pageState.value = it }
    }
}

@Composable
private fun NativeHarnessChangesCard(
    state: NativeHarnessProjectReviewState,
    labels: NativeHarnessProjectReviewLabels,
    onAction: (NativeHarnessProjectReviewUiAction) -> Unit,
) {
    val summary = state.changes
    val pageState = remember(summary?.coordinates, summary?.files) { mutableStateOf(0) }
    val page = nativeHarnessReviewPage(summary?.files.orEmpty(), pageState.value)
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(labels.changes, style = MaterialTheme.typography.titleSmall)
        if (summary == null || summary.files.isEmpty()) {
            Text(labels.noChanges, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("${summary.total} · +${summary.added} / -${summary.deleted}", style = MaterialTheme.typography.bodySmall)
            val firstIndex = page.pageIndex * NativeHarnessProjectReviewWire.LIST_PAGE_SIZE
            page.items.forEachIndexed { pageIndex, file ->
                val index = firstIndex + pageIndex
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(file.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(file.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(onClick = { onAction(NativeHarnessProjectReviewUiAction.Diff(index)) }) { Text(labels.diff) }
                        OutlinedButton(onClick = { onAction(NativeHarnessProjectReviewUiAction.Read(file.path)) }) { Text(labels.read) }
                        Button(onClick = { onAction(NativeHarnessProjectReviewUiAction.OpenChanged(index)) }) { Text(labels.open) }
                    }
                }
            }
            NativeHarnessPager(page.pageIndex, page.pageCount, labels) { pageState.value = it }
        }
        state.selectedDiff?.let { diff -> NativeHarnessDiffBody(diff, labels) }
        state.filePage?.let { page ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(page.path, style = MaterialTheme.typography.labelMedium)
            Text(page.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            val lineCount = page.lines.coerceAtLeast(1)
            if (page.offset > 1 || !page.eof) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        enabled = page.offset > 1,
                        onClick = {
                            onAction(NativeHarnessProjectReviewUiAction.Read(
                                page.path,
                                (page.offset - lineCount).coerceAtLeast(1),
                            ))
                        },
                    ) { Text(labels.previous) }
                    if (!page.eof) {
                        Button(onClick = {
                            onAction(NativeHarnessProjectReviewUiAction.Read(page.path, page.offset + lineCount))
                        }) { Text(labels.next) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeHarnessDiffBody(diff: NativeHarnessFileDiff, labels: NativeHarnessProjectReviewLabels) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    when (diff) {
        is NativeHarnessFileDiff.Binary -> Text("${diff.display} · ${labels.binaryFile}")
        is NativeHarnessFileDiff.Oversized -> Text("${diff.display} · ${labels.oversizedFile}")
        is NativeHarnessFileDiff.Text -> {
            val pageState = remember(diff.path, diff.display, diff.hunks) { mutableStateOf(0) }
            val page = nativeHarnessDiffPage(diff, pageState.value)
            Text(page.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            NativeHarnessPager(page.pageIndex, page.pageCount, labels) { pageState.value = it }
        }
    }
}

@Composable
private fun NativeHarnessWorkflowCard(runs: List<NativeHarnessWorkflowRun>, labels: NativeHarnessProjectReviewLabels) {
    val runPageState = remember(runs) { mutableStateOf(0) }
    val runPage = nativeHarnessReviewPage(runs, runPageState.value)
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(labels.workflows, style = MaterialTheme.typography.titleSmall)
        if (runs.isEmpty()) Text(labels.noItems, style = MaterialTheme.typography.bodySmall)
        runPage.items.forEach { run ->
            Text("${run.name} · ${workflowStatusLabel(run.status, labels)}", style = MaterialTheme.typography.bodyMedium)
            val phasePageState = remember(run.runId, run.phases) { mutableStateOf(0) }
            val phasePage = nativeHarnessReviewPage(run.phases, phasePageState.value)
            phasePage.items.forEach { phase ->
                Text(if (phase.phase == null) "  · ${labels.missingPhase}" else "  · ${phase.phase}", style = MaterialTheme.typography.labelMedium)
                val memberPageState = remember(run.runId, phase.key, phase.members) { mutableStateOf(0) }
                val memberPage = nativeHarnessReviewPage(phase.members, memberPageState.value)
                memberPage.items.forEach { member ->
                    Text("    ${member.label} · ${workflowStatusLabel(member.status, labels)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                NativeHarnessPager(memberPage.pageIndex, memberPage.pageCount, labels) { memberPageState.value = it }
            }
            NativeHarnessPager(phasePage.pageIndex, phasePage.pageCount, labels) { phasePageState.value = it }
        }
        NativeHarnessPager(runPage.pageIndex, runPage.pageCount, labels) { runPageState.value = it }
    }
}

@Composable
private fun NativeHarnessTodoCard(todos: List<NativeHarnessTodoItem>?, labels: NativeHarnessProjectReviewLabels) {
    val pageState = remember(todos) { mutableStateOf(0) }
    val page = nativeHarnessReviewPage(todos.orEmpty(), pageState.value)
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(labels.todos, style = MaterialTheme.typography.titleSmall)
        if (todos == null || todos.isEmpty()) Text(labels.noItems, style = MaterialTheme.typography.bodySmall)
        page.items.forEach { todo ->
            Text("${todoStatusLabel(todo.status, labels)} · ${todo.content}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        NativeHarnessPager(page.pageIndex, page.pageCount, labels) { pageState.value = it }
    }
}

@Composable
private fun NativeHarnessScheduleCard(records: List<NativeHarnessScheduleRecord>, labels: NativeHarnessProjectReviewLabels) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = produceState(initialValue = System.currentTimeMillis(), key1 = lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }.value
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val sorted = remember(records, now) {
        records.sortedWith(compareBy<NativeHarnessScheduleRecord> {
            if (Instant.parse(it.scheduledAt).toEpochMilli() <= now) 0 else 1
        }.thenBy { it.scheduledAt })
    }
    val pageState = remember(records) { mutableStateOf(0) }
    val page = nativeHarnessReviewPage(sorted, pageState.value)
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Text(labels.schedules, style = MaterialTheme.typography.titleSmall)
        if (records.isEmpty()) Text(labels.noItems, style = MaterialTheme.typography.bodySmall)
        page.items.forEach { record ->
            val timing = nativeHarnessScheduleTiming(record.scheduledAt, now, locale = locale)
            val frequency = nativeHarnessScheduleFrequency(record)?.let { value ->
                val unit = scheduleUnitLabel(value.unit, value.value, labels)
                String.format(locale, labels.frequencyEvery, value.value, unit)
            } ?: labels.frequencyOnce
            val relative = when {
                timing.relativeValue == null -> labels.relativeNow
                timing.overdue -> String.format(
                    locale,
                    labels.relativeOverdue,
                    timing.relativeValue,
                    scheduleUnitLabel(timing.relativeUnit!!, timing.relativeValue, labels),
                )
                else -> String.format(
                    locale,
                    labels.relativeFuture,
                    timing.relativeValue,
                    scheduleUnitLabel(timing.relativeUnit!!, timing.relativeValue, labels),
                )
            }
            Text("${if (timing.overdue) labels.overdue else labels.scheduled} · ${record.prompt}", maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("$frequency · ${timing.localTime} · $relative", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
        NativeHarnessPager(page.pageIndex, page.pageCount, labels) { pageState.value = it }
    }
}

private fun scheduleUnitLabel(
    unit: NativeHarnessScheduleUnit,
    value: Long,
    labels: NativeHarnessProjectReviewLabels,
): String = when (unit) {
    NativeHarnessScheduleUnit.DAY -> if (value == 1L) labels.dayOne else labels.dayOther
    NativeHarnessScheduleUnit.HOUR -> if (value == 1L) labels.hourOne else labels.hourOther
    NativeHarnessScheduleUnit.MINUTE -> if (value == 1L) labels.minuteOne else labels.minuteOther
    NativeHarnessScheduleUnit.SECOND -> if (value == 1L) labels.secondOne else labels.secondOther
}

@Composable
private fun NativeHarnessPager(
    pageIndex: Int,
    pageCount: Int,
    labels: NativeHarnessProjectReviewLabels,
    onPage: (Int) -> Unit,
) {
    if (pageCount <= 1) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(enabled = pageIndex > 0, onClick = { onPage(pageIndex - 1) }) { Text(labels.previous) }
        Text("${pageIndex + 1}/$pageCount", modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp))
        Button(enabled = pageIndex + 1 < pageCount, onClick = { onPage(pageIndex + 1) }) { Text(labels.next) }
    }
}

private fun workflowStatusLabel(status: NativeHarnessWorkflowStatus, labels: NativeHarnessProjectReviewLabels): String = when (status) {
    NativeHarnessWorkflowStatus.RUNNING -> labels.running
    NativeHarnessWorkflowStatus.COMPLETED -> labels.completed
    NativeHarnessWorkflowStatus.FAILED -> labels.failed
    NativeHarnessWorkflowStatus.CANCELLED -> labels.cancelled
    NativeHarnessWorkflowStatus.INTERRUPTED -> labels.interrupted
}

private fun todoStatusLabel(status: NativeHarnessTodoStatus, labels: NativeHarnessProjectReviewLabels): String = when (status) {
    NativeHarnessTodoStatus.PENDING -> labels.pending
    NativeHarnessTodoStatus.IN_PROGRESS -> labels.inProgress
    NativeHarnessTodoStatus.COMPLETED -> labels.completed
}
