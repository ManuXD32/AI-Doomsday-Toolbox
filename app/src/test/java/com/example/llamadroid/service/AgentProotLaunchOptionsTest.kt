package com.example.llamadroid.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProotLaunchOptionsTest {
    @Test
    fun `pinned Termux proot uses supported negative verbosity`() {
        val arguments = AgentProotLaunchOptions.forRootfs(File("/rootfs"))

        assertEquals(listOf("-v", "-1"), arguments.take(2))
        assertFalse(arguments.contains("--quiet"))
        assertFalse(arguments.contains("-q"))
        assertTrue(arguments.containsAll(listOf("-0", "--link2symlink", "-r", "/rootfs")))
    }
}
