package com.example.llamadroid.harness

import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The identity returned by `adt.prepareWorkspace` before a Harness session is created.
 *
 * The Android workspace id and the DSH workspace id intentionally remain separate.  The former
 * owns local files and Room rows; the latter is the grouping key used by the native WebUI.  A
 * display title is metadata only and must never be used to resolve a workspace.
 */
internal data class HarnessPreparedWorkspace(
    val workspaceId: String,
    val title: String,
    val guestPath: String,
    val associationStatus: String,
)

/** A unique DSH group may be referenced by Room aliases only for one exact project path. */
internal fun harnessAliasesMayShareGroup(
    first: HarnessWorkspaceEntity,
    second: HarnessWorkspaceEntity,
): Boolean = HarnessProjectManagementRules.sameWorkspace(first, second) &&
    normalizeHarnessGuestPath(first.guestPath) == normalizeHarnessGuestPath(second.guestPath)

/** Unregistered legacy Sessions may start in a project subfolder; canonical groups require equality. */
internal fun harnessSessionPathMatchesProject(
    sessionPath: String,
    projectPath: String,
    exact: Boolean,
): Boolean {
    val session = normalizeHarnessGuestPath(sessionPath)
    val project = normalizeHarnessGuestPath(projectPath)
    return session == project || (!exact && session.startsWith("$project/"))
}

/**
 * True when a new session cwd is a candidate for app-managed local project import.
 * This is a routing hint only: the repository still validates the folder and full path.
 * Malformed descendants such as `/workspace/projects/../other` remain candidates so the
 * repository can report its existing integrity error instead of hiding it.
 */
internal fun isManagedProjectGuestPath(path: String): Boolean {
    val root = "/workspace/projects/"
    return path.startsWith(root) && path.length > root.length
}

/**
 * Import a session workspace only when the session is already indexed, the cwd matches a
 * registered workspace, or the cwd is a candidate app-managed local project path.
 */
internal fun shouldImportHarnessSessionWorkspace(
    sessionAlreadyIndexed: Boolean,
    cwdMatchesRegisteredWorkspace: Boolean,
    cwd: String?,
): Boolean = sessionAlreadyIndexed || cwdMatchesRegisteredWorkspace ||
    cwd?.trim()?.let(::isManagedProjectGuestPath) == true

/** Native creation requires a verified DSH group; never fall back to an ungrouped cwd. */
internal fun harnessSessionCreateLocation(
    guestPath: String,
    registered: HarnessPreparedWorkspace,
): JsonObject = buildJsonObject {
    val normalizedPath = normalizeHarnessGuestPath(guestPath)
    require(registered.guestPath == normalizedPath) { "WORKSPACE_IDENTITY_MISMATCH" }
    require(registered.workspaceId.isNotBlank()) { "WORKSPACE_IDENTITY_INVALID" }
    put("workspaceId", registered.workspaceId)
}

/**
 * Accepts both the current direct response and the short-lived envelope variants used by older
 * Harness payloads.  Keeping this tolerant at the boundary lets the rest of the app use one
 * contract while still rejecting an incomplete identity instead of silently grouping by title.
 */
internal fun parseHarnessPreparedWorkspace(value: JsonElement): HarnessPreparedWorkspace? {
    val candidates = buildList {
        value.jsonObjectOrNull()?.let(::add)
        value.jsonObjectOrNull()?.objectValue("workspace")?.let(::add)
        value.jsonObjectOrNull()?.objectValue("result")?.let(::add)
        value.jsonObjectOrNull()?.objectValue("value")?.let(::add)
        value.jsonObjectOrNull()?.objectValue("data")?.let(::add)
    }.distinct()
    val source = candidates.firstOrNull { candidate ->
        candidate.stringValue("workspaceId", "id") != null &&
            candidate.stringValue("guestPath", "path", "cwd") != null
    } ?: return null
    val id = source.stringValue("workspaceId", "id")?.trim().orEmpty()
    val guestPath = source.stringValue("guestPath", "path", "cwd")?.trim().orEmpty()
    if (id.isBlank() || guestPath.isBlank()) return null
    val normalizedPath = runCatching { normalizeHarnessGuestPath(guestPath) }.getOrNull()
        ?: return null
    return HarnessPreparedWorkspace(
        workspaceId = id,
        title = source.stringValue("title", "name").orEmpty().trim(),
        guestPath = normalizedPath,
        associationStatus = source.stringValue("associationStatus", "association", "status")
            ?.trim().orEmpty().ifBlank { "associated" },
    )
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(String::isNotBlank)
}

/** Canonical guest path form used for exact project identity matching. */
internal fun normalizeHarnessGuestPath(path: String): String {
    val raw = path.trim()
    require(raw.startsWith('/')) { "WORKSPACE_PATH_NOT_ABSOLUTE" }
    val parts = ArrayDeque<String>()
    raw.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            // Workspace identity comes from a remote/runtime boundary.  Do not
            // normalize parent components here: accepting them would make a
            // stale response appear to identify a different managed project.
            ".." -> error("WORKSPACE_PATH_OUTSIDE_SCOPE")
            else -> {
                require('\u0000' !in part) { "WORKSPACE_PATH_INVALID" }
                parts += part
            }
        }
    }
    return "/" + parts.joinToString("/")
}

/**
 * Deduplicates follow/control events shared by native and WebUI clients.  DSH sequence numbers
 * are session-scoped; a reconnect may repeat a baseline, so snapshots advance the cursor while
 * duplicate event frames are ignored.  The helper is deliberately content-free.
 */
internal class HarnessSessionEventSequencer {
    private val cursors = mutableMapOf<String, Long>()

    @Synchronized
    fun reset(sessionId: String? = null) {
        if (sessionId == null) cursors.clear() else cursors.remove(sessionId)
    }

    @Synchronized
    fun accept(sessionId: String, sequence: Long?): Boolean {
        if (sessionId.isBlank() || sequence == null || sequence < 0L) return true
        val previous = cursors[sessionId]
        if (previous != null && sequence <= previous) return false
        cursors[sessionId] = sequence
        return true
    }

    /** Advance a reconnect baseline without treating the snapshot as a new event. */
    @Synchronized
    fun advance(sessionId: String, sequence: Long?) {
        if (sessionId.isBlank() || sequence == null || sequence < 0L) return
        val previous = cursors[sessionId]
        if (previous == null || sequence > previous) cursors[sessionId] = sequence
    }

    @Synchronized
    fun cursor(sessionId: String): Long? = cursors[sessionId]
}

/** Ignore an older group frame after a newer DSH workspace update was observed. */
internal class HarnessWorkspaceGroupRevisionGuard {
    private val latestByGroup = ConcurrentHashMap<String, Long>()

    fun accept(groupId: String, updatedAt: String?): Boolean {
        if (groupId.isBlank()) return false
        val timestamp = updatedAt?.let { raw ->
            raw.toLongOrNull() ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        } ?: return true
        var accepted = false
        latestByGroup.compute(groupId) { _, previous ->
            accepted = previous == null || timestamp >= previous
            if (accepted) timestamp else previous
        }
        return accepted
    }
}
