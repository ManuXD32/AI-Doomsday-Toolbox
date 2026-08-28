package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomBinaryPackageManagerTest {
    @Test
    fun `selection IDs are stable and reject unsafe values`() {
        assertEquals(
            "my-llama-build",
            CustomBinaryPackageManager.selectionId(" custom:my-llama-build ")
        )
        assertNull(CustomBinaryPackageManager.selectionId("custom:../escape"))
        assertNull(CustomBinaryPackageManager.selectionId("cpu_armv9"))
    }

    @Test
    fun `archive paths normalize separators and reject traversal`() {
        assertEquals(
            "lib/arm64-v8a/libllama_server.so",
            CustomBinaryPackageManager.normalizeArchivePath(
                "lib\\arm64-v8a\\libllama_server.so"
            )
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CustomBinaryPackageManager.normalizeArchivePath("../lib/server.so")
        }
    }
}
