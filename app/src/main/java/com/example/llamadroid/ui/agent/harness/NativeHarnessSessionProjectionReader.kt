package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Reads the upstream session and Workspace projections once per refresh pass.
 *
 * The Room rows are only an app presentation mirror. A single reader pass uses
 * the authoritative session list and one Workspace baseline, then serves all
 * session lookups from that immutable snapshot. This keeps a list refresh from
 * opening one Workspace stream for every row.
 */
internal class NativeHarnessSessionProjectionReader {
    private val lock = Mutex()
    private var cachedClient: HarnessClient? = null
    private var cachedSnapshot: Snapshot? = null

    suspend fun read(client: HarnessClient?, sessionId: String): NativeHarnessSessionProjection? {
        if (client == null || sessionId.isBlank()) return null
        return lock.withLock {
            if (cachedClient !== client || cachedSnapshot == null) {
                cachedClient = client
                cachedSnapshot = loadSnapshot(client)
            }
            cachedSnapshot?.projection(sessionId)
        }
    }

    /** Begin a new authoritative pass after a native or WebUI mutation. */
    suspend fun invalidate() = lock.withLock {
        cachedClient = null
        cachedSnapshot = null
    }

    private suspend fun loadSnapshot(client: HarnessClient): Snapshot? {
        val sessions = readAllSessionRows(client)
        val archived = readArchivedSessionIds(client)
        if (sessions == null && archived == null) return null

        val projections = sessions.orEmpty().mapNotNull { row ->
            val id = row.string("sessionId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to NativeHarnessSessionProjection(
                title = row.authoritativeTitle(),
                archived = row.boolean("archived")
            )
        }.toMap()
        return Snapshot(projections, archived)
    }

    /**
     * Alpha2 currently returns every visible summary in one response. Keep the
     * continuation handling here because the Android client also supports
     * deployments that expose a bounded page and `nextCursor`.
     */
    private suspend fun readAllSessionRows(client: HarnessClient): List<JsonObject>? {
        val rows = linkedMapOf<String, JsonObject>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_SESSION_LIST_PAGES) {
            val result = try {
                client.listSessions(cursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return null
            }
            val value = when (result) {
                is HarnessRpcResult.Failure -> return null
                is HarnessRpcResult.Success -> result.value.jsonObjectOrNull() ?: return null
            }
            value.objectArray("items").forEach { row ->
                row.string("sessionId")?.takeIf { it.isNotBlank() }?.let { rows[it] = row }
            }
            val next = value.string("nextCursor")?.takeIf { it.isNotBlank() }
                ?: return rows.values.toList()
            if (!seenCursors.add(next)) return rows.values.toList()
            cursor = next
        }
        return rows.values.toList()
    }

    private suspend fun readArchivedSessionIds(client: HarnessClient): Set<String>? {
        val frame = try {
            client.stream("workspace", "follow").first { item ->
                item.jsonObjectOrNull()?.string("type") == "baseline"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        return frame.objectValue("value")?.stringArray("archivedSessionIds")?.toSet()
    }

    private data class Snapshot(
        val sessions: Map<String, NativeHarnessSessionProjection>,
        val archivedSessionIds: Set<String>?
    ) {
        fun projection(sessionId: String): NativeHarnessSessionProjection? {
            val listed = sessions[sessionId]
            if (listed != null) {
                return listed.copy(
                    archived = archivedSessionIds?.let { sessionId in it } ?: listed.archived
                )
            }
            return if (archivedSessionIds?.contains(sessionId) == true) {
                NativeHarnessSessionProjection(archived = true)
            } else {
                null
            }
        }
    }

    private companion object {
        const val MAX_SESSION_LIST_PAGES = 64
    }
}

private fun JsonObject.authoritativeTitle(): String? =
    string("title")
        ?.takeIf { it.isNotBlank() }
        ?: objectValue("projections")
            ?.objectValue("values")
            ?.string("title")
            ?.takeIf { it.isNotBlank() }
