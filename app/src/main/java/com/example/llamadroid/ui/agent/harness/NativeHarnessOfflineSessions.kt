package com.example.llamadroid.ui.agent.harness

import kotlinx.coroutines.CancellationException

/**
 * Keeps retained Room identities useful while the shared process is stopped.
 * This helper deliberately never follows a session or sends a prompt; it only
 * selects the mapped workspace so the existing explorer can be opened.
 */
internal class NativeHarnessOfflineSessions(
    private val hooks: NativeHarnessWorkspaceHooks,
    private val state: () -> NativeHarnessUiState,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val sessionCwds: MutableMap<String, String?>,
    private val sessionTitles: MutableMap<String, String>,
    private val resolveWorkspace: suspend (String, String, String?, Boolean) -> HarnessWorkspaceUiState?,
    private val stopFollow: () -> Unit,
    private val reportFailure: suspend (String, String) -> Unit
) {
    suspend fun refresh(report: Boolean) {
        val rows = try {
            hooks.readOfflineSessions()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (report) reportFailure(
                "OFFLINE_SESSION_READ_FAILED",
                error.message ?: "Retained Harness sessions could not be read"
            )
            return
        }
        val previous = state().sessions.associateBy { it.id }
        val visibleRows = buildList {
            rows.distinctBy { it.sessionId }.forEach { row ->
                if (!hooks.isSessionRemoved(row.sessionId, row.cwd)) add(row)
            }
        }
        val mapped = visibleRows.map { row ->
            sessionCwds[row.sessionId] = row.cwd
            sessionTitles[row.sessionId] = row.title
            val old = previous[row.sessionId]
            val resolved = resolveWorkspace(row.sessionId, row.title, row.cwd, row.archived)
            HarnessSessionUiState(
                id = row.sessionId,
                title = row.title,
                projectFolder = resolved?.projectFolder ?: old?.projectFolder ?: harnessProjectFromCwd(row.cwd),
                backendLabel = resolved?.backendLabel ?: old?.backendLabel ?: "Harness",
                lastActivityLabel = old?.lastActivityLabel,
                unreadCount = old?.unreadCount ?: 0,
                isRunning = false,
                isArchived = row.archived,
                canRename = false,
                canFork = false,
                canArchive = false,
                canUnarchive = false
            )
        }
        val oldSelected = state().selectedSessionId
        val selected = oldSelected?.takeIf { id -> mapped.any { it.id == id } } ?: mapped.firstOrNull()?.id
        mutate { current ->
            current.copy(
                sessions = mapped.map { it.copy(isSelected = it.id == selected) },
                selectedSessionId = selected,
                transcript = emptyList(),
                queue = emptyList(),
                questions = emptyList(),
                approvals = emptyList(),
                plans = emptyList(),
                canLoadOlderMessages = false,
                isLoadingOlderMessages = false
            )
        }
        if (selected != null && selected != oldSelected) select(selected)
    }

    suspend fun select(sessionId: String) {
        val selected = state().sessions.firstOrNull { it.id == sessionId } ?: return
        val title = selected.title
        val archived = selected.isArchived
        val resolved = resolveWorkspace(sessionId, title, sessionCwds[sessionId], archived)
        stopFollow()
        mutate { current ->
            current.copy(
                selectedSessionId = sessionId,
                workspace = resolved ?: current.workspace.copy(
                    projectFolder = selected.projectFolder,
                    backendLabel = selected.backendLabel
                ),
                sessions = current.sessions.map { it.copy(isSelected = it.id == sessionId) },
                transcript = emptyList(),
                queue = emptyList(),
                questions = emptyList(),
                approvals = emptyList(),
                plans = emptyList(),
                goal = null,
                canLoadOlderMessages = false,
                isLoadingOlderMessages = false,
                notice = null
            )
        }
    }
}
