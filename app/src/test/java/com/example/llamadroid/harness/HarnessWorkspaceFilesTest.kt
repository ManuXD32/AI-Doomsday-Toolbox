package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessWorkspaceFilesTest {
    @Test fun legacyLocalExplorerUsesItsDisplayedRoot() {
        val relative = HarnessWorkspaceFiles.Companion::relativeExplorerPath
        assertEquals("main.c", relative("/local_workspace/C_primes_IV/main.c", "/local_workspace/C_primes_IV", "/workspace/projects/C_primes_IV"))
        assertEquals(".", relative("/local_workspace/C_primes_IV", "/local_workspace/C_primes_IV", "/workspace/projects/C_primes_IV"))
        assertThrows(IllegalArgumentException::class.java) {
            relative("/local_workspace/another/main.c", "/local_workspace/C_primes_IV", "/workspace/projects/C_primes_IV")
        }
    }

    @Test fun displayAndGuestPathsResolveOnlyWithinCapturedProject() {
        val relative = HarnessWorkspaceFiles.Companion::relativeExplorerPath
        assertEquals("image.png", relative("/workspace/alpha/image.png", "/workspace/alpha", "/workspace/projects/alpha"))
        assertEquals("src/main.py", relative("/workspace/projects/alpha/src/main.py", "/workspace/alpha", "/workspace/projects/alpha"))
        for (path in listOf("/workspace/beta/image.png", "/workspace/alpha-other/file", "../beta/file", "/workspace/alpha/../beta/file")) {
            assertThrows(IllegalArgumentException::class.java) { relative(path, "/workspace/alpha", "/workspace/projects/alpha") }
        }
    }
}
