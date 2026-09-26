package com.example.llamadroid.service

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class AgentLocalWorkspaceSupportTest {
    private lateinit var temporaryRoot: File

    @Before
    fun setUpWorkspace() {
        temporaryRoot = Files.createTempDirectory("agent-local-workspace").toFile()
    }

    @After
    fun tearDownWorkspace() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun pythonDefaultsToConsoleAndWebDefaultsToWeb() {
        val python = AgentRunConfigParser.parse(
            """{"version":1,"runtime":"python","entrypoint":"main.py"}"""
        )
        val web = AgentRunConfigParser.parse(
            """{"version":1,"runtime":"web","entrypoint":"index.html"}"""
        )

        assertEquals(AgentLocalRuntimeType.PYTHON, python.runtime)
        assertEquals(AgentRunUiMode.CONSOLE, python.uiMode)
        assertEquals(AgentLocalRuntimeType.WEB, web.runtime)
        assertEquals(AgentRunUiMode.WEB, web.uiMode)
    }

    @Test
    fun pythonWebManifestIsRejectedWithStableMismatchError() {
        assertMismatch(
            """{"version":1,"runtime":"python","entrypoint":"main.py","ui":"web"}""",
            "RUN_CONFIG_RUNTIME_UI_MISMATCH: runtime python requires ui console."
        )
    }

    @Test
    fun webConsoleManifestIsRejectedWithStableMismatchError() {
        assertMismatch(
            """{"version":1,"runtime":"web","entrypoint":"index.html","ui":"console"}""",
            "RUN_CONFIG_RUNTIME_UI_MISMATCH: runtime web requires ui web."
        )
    }

    @Test
    fun modelRunManifestWriteIsValidatedBeforeCommit() {
        assertTrue(
            validateAgentRunManifestWrite(
                ".adt/run.json",
                """{"version":1,"runtime":"python","entrypoint":"main.py","ui":"console"}"""
            ).isSuccess
        )
        val invalid = validateAgentRunManifestWrite(
            "./.adt/run.json",
            """{"name":"legacy","command":"python main.py"}"""
        )
        assertTrue(invalid.isFailure)
        assertTrue(invalid.exceptionOrNull()?.message.orEmpty().startsWith("RUN_CONFIG_INVALID:"))
        val implicitDefaults = validateAgentRunManifestWrite(
            ".adt/run.json",
            """{"runtime":"web","entrypoint":"index.html"}"""
        )
        assertTrue(implicitDefaults.isFailure)
        assertTrue(implicitDefaults.exceptionOrNull()?.message.orEmpty().contains("version is required"))
        assertTrue(validateAgentRunManifestWrite("README.md", "not json").isSuccess)
    }

    @Test
    fun greenfieldDetectionIgnoresBrainAndOnlyAnEmptyAdtDirectory() {
        File(temporaryRoot, "brain").mkdirs()
        File(temporaryRoot, ".adt").mkdirs()
        File(temporaryRoot, "brain/summary.md").writeText("runtime metadata")

        assertTrue(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(temporaryRoot))

        File(temporaryRoot, ".adt/run.json").writeText("{}")
        assertFalse(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(temporaryRoot))

        File(temporaryRoot, "src/main.py").apply {
            parentFile?.mkdirs()
            writeText("print('user source')")
        }
        assertFalse(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(temporaryRoot))
    }

    @Test
    fun greenfieldDetectionTreatsUserDirectoriesAndMetadataFilesAsProjectState() {
        File(temporaryRoot, "src").mkdirs()
        assertFalse(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(temporaryRoot))

        temporaryRoot.deleteRecursively()
        temporaryRoot.mkdirs()
        File(temporaryRoot, "brain").writeText("user file")
        assertFalse(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(temporaryRoot))
    }

    @Test
    fun missingWorkspaceIsGreenfieldButUnreadableShapeFailsClosed() {
        val missing = File(temporaryRoot, "missing")
        assertTrue(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(missing))

        val fileRoot = File(temporaryRoot, "not-a-directory")
        fileRoot.writeText("not a workspace")
        assertFalse(AgentLocalWorkspaceSupport.isGreenfieldWorkspace(fileRoot))
    }

    private fun assertMismatch(raw: String, expected: String) {
        try {
            AgentRunConfigParser.parse(raw)
            fail("Expected runtime/ui mismatch")
        } catch (error: IllegalArgumentException) {
            assertEquals(expected, error.message)
        }
    }
}
