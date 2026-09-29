package com.example.llamadroid.harness.transfer

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessTransferDomainsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun prepareRemapsWorkspaceRecordsAndClearsPendingMutationThenMergeRetainsDestinationState() {
        val source = temporary.newFolder("source")
        val destination = temporary.newFolder("destination")
        writeWorkspaceDomain(source, "old-workspace", "/workspace/projects/source", "old-session", "old-workspace")
        writeWorkspaceDomain(destination, "destination-workspace", "/workspace/projects/destination", "destination-session", "destination-workspace")
        File(destination, "storages/workspace/providers.json").writeText("{\"provider\":\"destination-only\"}")

        HarnessTransferDomains.prepare(
            source,
            sessionIds = mapOf("old-session" to "new-session"),
            cwdMap = mapOf("/workspace/projects/source" to "/workspace/projects/imported"),
            workspaceIds = mapOf("old-workspace" to "imported-workspace"),
            referenceMapper = null,
        )
        val preparedGlobal = JSONObject(File(source, "storages/workspace/global.json").readText()).getJSONObject("record")
        assertEquals("imported-workspace", preparedGlobal.getJSONArray("workspaceIds").getString(0))
        assertFalse(preparedGlobal.has("pendingMutation"))
        val preparedRecord = JSONObject(File(source, "storages/workspace/workspaces/imported-workspace.json").readText())
            .getJSONObject("record")
        assertEquals("/workspace/projects/imported", preparedRecord.getString("path"))
        assertEquals("new-session", preparedRecord.getJSONArray("sessionIds").getString(0))
        assertFalse(File(source, "storages/workspace/workspaces/old-workspace.json").exists())

        HarnessTransferDomains.merge(source, destination)
        val mergedGlobal = JSONObject(File(destination, "storages/workspace/global.json").readText()).getJSONObject("record")
        assertEquals(
            listOf("destination-workspace", "imported-workspace"),
            (0 until mergedGlobal.getJSONArray("workspaceIds").length()).map {
                mergedGlobal.getJSONArray("workspaceIds").getString(it)
            },
        )
        assertTrue(File(destination, "storages/workspace/workspaces/imported-workspace.json").isFile)
        assertEquals("{\"provider\":\"destination-only\"}", File(destination, "storages/workspace/providers.json").readText())
        assertFalse(mergedGlobal.has("pendingMutation"))
    }

    private fun writeWorkspaceDomain(
        home: File,
        id: String,
        path: String,
        sessionId: String,
        pendingId: String,
    ) {
        val domain = File(home, "storages/workspace/workspaces").apply { mkdirs() }
        File(domain, "$id.json").writeText(JSONObject()
            .put("version", 2)
            .put("record", JSONObject()
                .put("path", path)
                .put("title", id)
                .put("sessionIds", JSONArray().put(sessionId))
                .put("createdAt", "2026-09-27T00:00:00.000Z")
                .put("updatedAt", "2026-09-27T00:00:00.000Z"))
            .toString())
        File(home, "storages/workspace/global.json").writeText(JSONObject()
            .put("version", 2)
            .put("record", JSONObject()
                .put("initialized", true)
                .put("workspaceIds", JSONArray().put(id))
                .put("archivedSessionIds", JSONArray())
                .put("pendingMutation", JSONObject().put("operation", "delete").put("workspaceId", pendingId)))
            .toString())
    }
}
