package com.example.llamadroid.ui.agent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessPluginFilterTest {
    @Test
    fun emptyQueryKeepsAllPlugins() {
        val plugins = listOf(
            HarnessPluginUi(id = "alpha", name = "Alpha", summary = "Tools"),
            HarnessPluginUi(id = "beta", name = "Beta", summary = "Files"),
        )

        assertEquals(plugins, filterHarnessPlugins(plugins, "  "))
    }

    @Test
    fun pluginSearchMatchesBundleAndDiagnosticsCaseInsensitively() {
        val plugins = listOf(
            HarnessPluginUi(
                id = "dsh-runtime",
                name = "Runtime",
                summary = "Managed runtime",
                bundleName = "@deepseek-ai/dsh-runtime",
            ),
            HarnessPluginUi(
                id = "files",
                name = "Files",
                summary = "Workspace files",
                errorCode = "IMPORT_FAILED",
            ),
        )

        assertEquals("dsh-runtime", filterHarnessPlugins(plugins, "DEEPSEEK").single().id)
        assertEquals("files", filterHarnessPlugins(plugins, "import_failed").single().id)
        assertTrue(filterHarnessPlugins(plugins, "missing").isEmpty())
    }

    @Test
    fun inventorySearchMatchesModulePhaseAndPatch() {
        val inventory = listOf(
            HarnessPluginInventoryUi("entry-a", "workspace-files", true, phase = "active", patchId = "files-v2"),
            HarnessPluginInventoryUi("entry-b", "web-ui", false, phase = "failed", patchId = "web-v1"),
        )

        assertEquals("entry-a", filterHarnessPluginInventory(inventory, "FILES-V2").single().entryId)
        assertEquals("entry-b", filterHarnessPluginInventory(inventory, "FAILED").single().entryId)
    }
}
