package com.example.llamadroid.harness

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.data.db.HarnessRuntimeEntity
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeDiagnosticUi
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeStatus
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeUiState
import com.example.llamadroid.ui.agent.harness.HarnessWebUiState
import com.example.llamadroid.harness.runtime.HarnessEndpoint

/** A managed endpoint is published only after the authenticated readiness handshake. */
internal fun HarnessEndpoint?.toWebUiPresentation(): HarnessWebUiState = HarnessWebUiState(
    available = this != null,
    endpointLabel = this?.origin,
    authenticated = this != null,
)

internal fun HarnessRuntimeEntity?.toRuntimePresentation(
    context: Context,
    appError: String? = null,
    journal: List<HarnessRuntimeDiagnostic> = emptyList(),
    startingInApp: Boolean = false,
): HarnessRuntimeUiState = runtimePresentation(this, appError, journal, context::getString, startingInApp)

/** Pure projection keeps startup failures distinct from graceful-stop timeouts. */
internal fun runtimePresentation(
    record: HarnessRuntimeEntity?,
    appError: String?,
    journal: List<HarnessRuntimeDiagnostic>,
    text: (Int) -> String,
    startingInApp: Boolean = false,
): HarnessRuntimeUiState {
    var status = when (record?.state) {
        "STARTING" -> HarnessRuntimeStatus.STARTING
        "RUNNING" -> HarnessRuntimeStatus.RUNNING
        "STOP_REQUESTED", "FORCE_STOPPING" -> HarnessRuntimeStatus.STOPPING
        "INTERRUPTED" -> HarnessRuntimeStatus.INTERRUPTED
        "FAILED" -> HarnessRuntimeStatus.ERROR
        else -> HarnessRuntimeStatus.STOPPED
    }
    // Preparation can fail before a native owner/Room runtime row exists.
    if (startingInApp && appError == null && status !in ACTIVE_STATES) status = HarnessRuntimeStatus.STARTING
    else if (appError != null && appError !in CLEANUP_CODES && status !in ACTIVE_STATES) {
        status = HarnessRuntimeStatus.ERROR
    }
    val failed = status == HarnessRuntimeStatus.ERROR || status == HarnessRuntimeStatus.INTERRUPTED
    val code = (appError ?: record?.errorCode)?.takeIf { failed || status == HarnessRuntimeStatus.STOPPING }
        ?.let(::harnessDiagnosticErrorClass)
    val phase = journal.lastOrNull { it.phase != null && it.generation == record?.generation }?.phase
    return HarnessRuntimeUiState(
        status = status,
        detail = if (failed || code != null) text(runtimeFailureText(code)) else null,
        canStart = status in setOf(HarnessRuntimeStatus.STOPPED, HarnessRuntimeStatus.ERROR, HarnessRuntimeStatus.INTERRUPTED),
        canStop = status == HarnessRuntimeStatus.STARTING || status == HarnessRuntimeStatus.RUNNING,
        canForceStop = record != null && (status != HarnessRuntimeStatus.STOPPED || record.brokerPid != null),
        phaseLabel = phase?.takeIf { status == HarnessRuntimeStatus.STARTING || failed }?.let { text(runtimeEventText(it)) },
        errorCode = code,
        connectionLabel = journal.lastOrNull { it.event == "websocket" }?.outcome
            ?.takeIf { status == HarnessRuntimeStatus.RUNNING }?.let { outcome ->
                text(if (outcome == "open" || outcome == "success") R.string.harness_connection_connected
                    else if (outcome == "retrying" || outcome == "opening") R.string.harness_connection_connecting
                    else R.string.harness_connection_interrupted)
            },
        diagnostics = journal.takeLast(100).map { row ->
            HarnessRuntimeDiagnosticUi(
                id = row.id.toString(), timestampMs = row.timestampMs,
                eventLabel = text(runtimeEventText(if (row.event == "phase") row.phase ?: "phase" else row.event)),
                stateLabel = row.state?.let { text(runtimeStateText(it)) },
                errorCode = row.errorCode, durationMs = row.durationMs, exitCode = row.exitCode,
                connectionMetadata = runtimeConnectionMetadata(row, text),
            )
        },
        diagnosticsLastUpdatedMs = journal.lastOrNull()?.timestampMs,
    )
}

/** Only fixed metadata enters the copy buffer; process output is never retained. */
internal fun runtimeDiagnosticsText(rows: List<HarnessRuntimeDiagnostic>): String = rows.joinToString("\n") { row ->
    buildString {
        append(java.time.Instant.ofEpochMilli(row.timestampMs)).append(' ').append(row.event)
        row.phase?.let { append(" phase=").append(it) }
        row.state?.let { append(" state=").append(it) }
        row.errorCode?.let { append(" error=").append(it) }
        row.durationMs?.let { append(" durationMs=").append(it) }
        row.exitCode?.let { append(" exitCode=").append(it) }
        row.transport?.let { append(" transport=").append(it) }
        row.operation?.let { append(" operation=").append(it) }
        row.outcome?.let { append(" outcome=").append(it) }
        row.httpStatus?.let { append(" httpStatus=").append(it) }
        row.closeCode?.let { append(" closeCode=").append(it) }
        row.attempt?.let { append(" attempt=").append(it) }
        row.activeStreams?.let { append(" activeStreams=").append(it) }
        row.coalescedCount?.let { append(" coalesced=").append(it) }
        row.webBoot.forEach { (key, value) -> append(" boot.").append(key).append('=').append(value) }
    }
}

private fun runtimeConnectionMetadata(row: HarnessRuntimeDiagnostic, text: (Int) -> String): String? = listOfNotNull(
    row.transport, row.operation, row.outcome?.let { outcome -> text(when (outcome) {
        "success", "completed", "recovered", "ready" -> R.string.harness_diagnostic_success
        "open" -> R.string.harness_diagnostic_connected
        "opening", "retrying", "waiting", "started" -> R.string.harness_diagnostic_connecting
        "closed", "cancelled" -> R.string.harness_diagnostic_closed
        else -> R.string.harness_diagnostic_failed
    }) },
    row.httpStatus?.let { "HTTP $it" }, row.closeCode?.let { "WS $it" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

@StringRes
internal fun runtimeFailureText(code: String?): Int = when (code) {
    "HARNESS_STOP_TIMEOUT", "HARNESS_GRACEFUL_STOP_TIMEOUT", "HARNESS_STOP_HOOK_TIMEOUT" -> R.string.harness_runtime_stop_timeout
    "HARNESS_NOT_READY", "HARNESS_READY_RECEIPT_MISSING" -> R.string.harness_start_readiness_failed
    "HARNESS_AUTH_FAILED", "HARNESS_READY_RECEIPT_INVALID", "HARNESS_READY_RECEIPT_STALE",
    "HARNESS_AUTH_COOKIE_MISSING", "HARNESS_AUTH_BOOTSTRAP_REJECTED", "HARNESS_RPC_REJECTED" -> R.string.harness_start_auth_failed
    "HARNESS_PERMISSION_DENIED", "HARNESS_SECURITYEXCEPTION" -> R.string.harness_start_permission_failed
    "HARNESS_ARCHITECTURE_MISMATCH" -> R.string.harness_start_architecture_failed
    "HARNESS_EXECUTABLE_MISSING", "HARNESS_NATIVE_UNAVAILABLE", "HARNESS_NATIVE_MODULE_UNAVAILABLE",
    "HARNESS_PAYLOAD_MISSING", "HARNESS_PAYLOAD_INVALID" -> R.string.harness_start_payload_failed
    "HARNESS_PROOT_START_FAILED" -> R.string.harness_start_proot_failed
    "HARNESS_BROKER_OWNER_UNAVAILABLE", "HARNESS_BROKER_LIMIT_FAILED", "HARNESS_BROKER_SUPERVISION_FAILED",
    "HARNESS_BROKER_PID_MISSING", "HARNESS_BROKER_IDENTITY_MISSING", "HARNESS_BROKER_EXITED" -> R.string.harness_start_broker_failed
    "HARNESS_NODE_START_FAILED" -> R.string.harness_start_node_failed
    "HARNESS_PORT_IN_USE" -> R.string.harness_start_port_failed
    "HARNESS_MEMORY_LIMIT", "HARNESS_OUTOFMEMORYERROR", "HARNESS_PROCESS_KILLED" -> R.string.harness_start_memory_failed
    "HARNESS_PROCESS_CRASHED", "HARNESS_PROCESS_EXITED", "HARNESS_PROCESS_LOST",
    "HARNESS_PROCESS_TERMINATED" -> R.string.harness_start_process_failed
    "HARNESS_CLEANUP_PENDING", "HARNESS_FORCE_STOP_INCOMPLETE", "HARNESS_RECOVERY_CLEANUP_INCOMPLETE" -> R.string.harness_runtime_cleanup_pending
    else -> R.string.harness_start_failed_detail
}

@StringRes
private fun runtimeStateText(state: String): Int = when (state) {
    "STARTING" -> R.string.harness_status_starting
    "RUNNING" -> R.string.harness_status_running
    "STOP_REQUESTED", "FORCE_STOPPING" -> R.string.harness_status_stopping
    "FAILED" -> R.string.harness_status_error
    "INTERRUPTED" -> R.string.harness_status_interrupted
    else -> R.string.harness_status_stopped
}

@StringRes
private fun runtimeEventText(event: String): Int = when (event) {
    "rpc" -> R.string.harness_event_rpc
    "websocket" -> R.string.harness_event_websocket
    "webview" -> R.string.harness_event_webview
    "native_failure" -> R.string.harness_event_native_failure
    "native_recovered" -> R.string.harness_event_native_recovered
    "app_start_requested", "start_requested" -> R.string.harness_event_start_requested
    "environment_preparing" -> R.string.harness_event_environment_preparing
    "environment_ready" -> R.string.harness_event_environment_ready
    "payload_preparing" -> R.string.harness_event_payload_preparing
    "payload_ready" -> R.string.harness_event_payload_ready
    "bridge_preparing" -> R.string.harness_event_bridge_preparing
    "bridge_ready" -> R.string.harness_event_bridge_ready
    "process_launching" -> R.string.harness_event_process_launching
    "process_launched", "process_identity_ready" -> R.string.harness_event_process_launched
    "readiness_waiting" -> R.string.harness_event_readiness_waiting
    "readiness_ready", "started" -> R.string.harness_event_ready
    "startup_output" -> R.string.harness_event_startup_output
    "process_exited" -> R.string.harness_event_process_exited
    "app_start_failed", "start_failed", "app_operation_failed" -> R.string.harness_event_failed
    "recovered" -> R.string.harness_event_recovered
    "stopped" -> R.string.harness_event_stopped
    "force_stopped" -> R.string.harness_event_force_stopped
    "stop_timed_out", "stop_hook_timed_out" -> R.string.harness_event_stop_timeout
    "force_stop_incomplete", "stop_hook_failed" -> R.string.harness_event_cleanup_failed
    "journal_unreadable" -> R.string.harness_event_journal_unreadable
    "journal_write_failed" -> R.string.harness_event_journal_write_failed
    else -> R.string.harness_event_phase
}

private val ACTIVE_STATES = setOf(HarnessRuntimeStatus.STARTING, HarnessRuntimeStatus.RUNNING, HarnessRuntimeStatus.STOPPING)
private val CLEANUP_CODES = setOf("SSH_CLEANUP_PENDING", "INFERENCE_CLEANUP_PENDING")
