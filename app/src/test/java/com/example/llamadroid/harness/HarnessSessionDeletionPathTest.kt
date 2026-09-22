package com.example.llamadroid.harness

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class HarnessSessionDeletionPathTest {
    @Test fun rejectsTraversalAndSymlinkedParents() {
        val root = Files.createTempDirectory("harness-delete-test").toFile()
        val outside = Files.createTempDirectory("harness-delete-outside").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { checkedSessionDeletionPath(root, "../outside") }
            assertThrows(IllegalArgumentException::class.java) { checkedSessionDeletionPath(root, "/root/.dsh") }
            Files.createSymbolicLink(root.resolve("linked").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { checkedSessionDeletionPath(root, "linked/session") }
            assertEquals(root.resolve("sessions/project/thread"), checkedSessionDeletionPath(root, "sessions/project/thread"))
        } finally { Files.deleteIfExists(root.resolve("linked").toPath()); root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun durableNotificationIdentityIncludesSessionAndKind() {
        assertEquals(attentionNotificationKey("a", "event", "question"), attentionNotificationKey("a", "event", "question"))
        assertNotEquals(attentionNotificationKey("a", "event", "question"), attentionNotificationKey("b", "event", "question"))
        assertNotEquals(attentionNotificationKey("a", "event", "question"), attentionNotificationKey("a", "event", "plan"))
    }

    @Test fun onlyThePinnedDerivedSearchIndexCanBePurged() {
        val header = ByteArray(100)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(header)
        val fields = java.nio.ByteBuffer.wrap(header)
        fields.putInt(68, 1146308689); fields.putInt(60, 8)
        assertTrue(isHarnessDerivedIndexHeader(header))
        fields.putInt(68, 0)
        assertFalse(isHarnessDerivedIndexHeader(header))
        assertFalse(isHarnessDerivedIndexHeader(ByteArray(12)))
    }
}
