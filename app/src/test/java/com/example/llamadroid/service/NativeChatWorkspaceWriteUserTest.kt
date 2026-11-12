package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NativeChatWorkspaceWriteUserTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun saveAboveToolLimitSucceedsAndReplacesAtomically() {
        val big = "x".repeat(100_000)
        NativeChatWorkspaceSupport.writeUser(context, 41L, "docs/big.txt", big)
        NativeChatWorkspaceSupport.writeUser(context, 41L, "docs/big.txt", big + "y")
        val file = NativeChatWorkspaceSupport.resolve(context, 41L, "docs/big.txt")
        assertEquals(big + "y", file.readText())
        assertEquals(listOf("big.txt"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun rejectsOverTwoMiBAndPathEscape() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeChatWorkspaceSupport.writeUser(context, 42L, "a.txt", "x".repeat(NativeChatWorkspaceSupport.MAX_USER_WRITE_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeChatWorkspaceSupport.writeUser(context, 42L, "../escape.txt", "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeChatWorkspaceSupport.writeUser(context, 42L, "", "x")
        }
    }
}
