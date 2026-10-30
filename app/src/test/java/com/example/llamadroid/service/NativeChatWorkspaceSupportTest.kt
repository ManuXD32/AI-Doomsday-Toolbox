package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatWorkspaceSupportTest {
    @Test
    fun mutationLimitIsBounded() {
        assertTrue(NativeChatWorkspaceSupport.MAX_MUTATION_CHARS <= 16_384)
    }

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun uniqueFileAddsCounterBeforeExtension() {
        val dir = tmp.newFolder("att")
        assertEquals("report.zip", NativeChatWorkspaceSupport.uniqueFile(dir, "report.zip").name)
        java.io.File(dir, "report.zip").writeText("x")
        assertEquals("report-2.zip", NativeChatWorkspaceSupport.uniqueFile(dir, "report.zip").name)
        java.io.File(dir, "report-2.zip").writeText("x")
        assertEquals("report-3.zip", NativeChatWorkspaceSupport.uniqueFile(dir, "report.zip").name)
        java.io.File(dir, "noext").writeText("x")
        assertEquals("noext-2", NativeChatWorkspaceSupport.uniqueFile(dir, "noext").name)
    }

    @Test
    fun localPatchRejectsPathEscapeBeforeFileAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentLocalPatchSupport.apply("--- a/../secret\n+++ b/../secret\n@@ -1 +1 @@\n-a\n+b") { "a\n" }
        }
    }
}
