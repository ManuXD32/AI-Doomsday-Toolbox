package com.example.llamadroid.service

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatWorkspaceSupportTest {
    @Test
    fun mutationLimitIsBounded() {
        assertTrue(NativeChatWorkspaceSupport.MAX_MUTATION_CHARS <= 16_384)
    }

    @Test
    fun localPatchRejectsPathEscapeBeforeFileAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentLocalPatchSupport.apply("--- a/../secret\n+++ b/../secret\n@@ -1 +1 @@\n-a\n+b") { "a\n" }
        }
    }
}
