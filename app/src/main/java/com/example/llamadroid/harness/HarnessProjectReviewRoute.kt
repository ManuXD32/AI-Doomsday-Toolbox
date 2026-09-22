package com.example.llamadroid.harness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewController
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewCoordinates
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewPanel
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewTransport
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewUiAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Shares both the authenticated transport and the selected session with the canonical screen. */
@Composable
internal fun HarnessProjectReviewRoute(runtime: HarnessAppRuntime, sessionId: String?) {
    val scope = rememberCoroutineScope()
    val endpoint by runtime.endpoint.collectAsState()
    val selected by rememberUpdatedState(sessionId.takeIf { endpoint != null })
    val transport = remember(runtime) { HarnessProjectReviewTransport { runtime.client } }
    val controller = remember(runtime) {
        NativeHarnessProjectReviewController(scope, { runtime.client }, { selected }, { transport })
    }
    val state by controller.state.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    var streamFailed by remember(sessionId, endpoint) { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(sessionId, endpoint, retry) {
        streamFailed = false
        controller.selectSession(sessionId.takeIf { endpoint != null })
        if (sessionId == null || endpoint == null) return@LaunchedEffect
        controller.refreshNow()
        try {
            runtime.client?.stream("session", "control", policy = HarnessStreamPolicy.SessionFollow)?.collect {
                controller.applyProjectionFrame(it)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            streamFailed = true
            runtime.diagnostics.event(sessionId, "project_review", "INTERRUPTED")
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (streamFailed) {
            Text(stringResource(R.string.harness_runtime_failure))
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.harness_runtime_retry)) }
        }
        // Hide the previous session immediately while the next scoped snapshot is loading.
        NativeHarnessProjectReviewPanel(
            state = if (state.agentId == sessionId) state else com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewState(),
            modifier = Modifier.weight(1f),
            onAction = { action ->
                val capturedSession = state.agentId
                val capturedChanges = state.changes?.coordinates
                scope.launch {
                if (capturedSession != selected) return@launch
                when (action) {
                    NativeHarnessProjectReviewUiAction.Refresh -> { retry++ }
                    is NativeHarnessProjectReviewUiAction.Read -> controller.readWorkspaceFile(action.path, action.offset)
                    is NativeHarnessProjectReviewUiAction.Diff -> capturedChanges?.let { controller.loadDiff(it, action.index) }
                    is NativeHarnessProjectReviewUiAction.OpenChanged -> capturedChanges?.let { controller.openChangedFile(it, action.index) }
                    is NativeHarnessProjectReviewUiAction.OpenPresented -> capturedSession?.let {
                        controller.openPresentedFile(NativeHarnessProjectReviewCoordinates(it, action.sequence, action.turn), action.index, action.action)
                    }
                }
            } },
        )
    }
}

internal class HarnessProjectReviewTransport(private val client: () -> HarnessClient?) : NativeHarnessProjectReviewTransport {
    override suspend fun get(path: String, query: Map<String, String>): JsonElement =
        requireNotNull(client()).fetchJson(path, query).valueOrThrow()

    override suspend fun post(path: String, query: Map<String, String>, body: JsonObject): JsonElement =
        requireNotNull(client()).fetchJson(path, query, body).valueOrThrow()

    override suspend fun readWorkspaceFile(sessionId: String, path: String, offset: Int, limit: Int): JsonElement =
        requireNotNull(client()).call("workspaceFiles", "read", buildJsonObject {
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            putJsonObject("range") { put("offset", offset); put("limit", limit) }
        }, HarnessCallPolicy.SafeRead).valueOrThrow()

    private fun HarnessRpcResult.valueOrThrow(): JsonElement = when (this) {
        is HarnessRpcResult.Success -> value
        is HarnessRpcResult.Failure -> throw IllegalStateException(error.code)
    }
}
