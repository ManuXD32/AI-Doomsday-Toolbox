package com.example.llamadroid.harness

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore

/** A desktop action carries its verified session scope all the way into Android presentation. */
class HarnessWorkspaceActions {
    data class Request(
        val scope: HarnessSessionScope,
        val relativePath: String,
        val reveal: Boolean,
        val directory: Boolean,
        val completion: CompletableDeferred<Unit>
    )

    private val mutableRequests = MutableSharedFlow<Request>()
    val requests = mutableRequests.asSharedFlow()
    private val pending = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()
    private val admission = Semaphore(16)

    suspend fun open(scope: HarnessSessionScope, path: String, reveal: Boolean, directory: Boolean) {
        check(mutableRequests.subscriptionCount.value > 0) { "WORKSPACE_UI_UNAVAILABLE" }
        val relative = relativeWorkspaceActionPath(scope.workspace.guestPath, path)
        check(admission.tryAcquire()) { "WORKSPACE_UI_BUSY" }
        val completion = CompletableDeferred<Unit>()
        pending.add(completion)
        try {
            withTimeout(30_000) {
                mutableRequests.emit(Request(scope, relative, reveal, directory, completion))
                completion.await()
            }
        } finally { pending.remove(completion); completion.cancel(); admission.release() }
    }

    fun cancelPending() { pending.toList().forEach { it.cancel() } }
}

internal fun relativeWorkspaceActionPath(root: String, path: String): String {
    require(root.startsWith('/') && root.length > 1 && !root.endsWith('/'))
    val relative = when {
        path == root -> "."
        path.startsWith("$root/") -> path.removePrefix("$root/")
        else -> path
    }
    require(!relative.startsWith('/') && relative.length <= 4096 && '\u0000' !in relative &&
        relative.split('/').none { it == ".." }) { "WORKSPACE_PATH_OUTSIDE_SCOPE" }
    return relative.ifBlank { "." }
}
