package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsPathsTest {
    private fun assertInvalid(raw: String) {
        try {
            FsPaths.normalize(raw)
            throw AssertionError("expected INVALID_PATH for '$raw'")
        } catch (e: FsException) {
            assertEquals(FsErrorCode.INVALID_PATH, e.code)
        }
    }

    @Test fun normalizesRootAndSlashes() {
        assertEquals("", FsPaths.normalize(""))
        assertEquals("", FsPaths.normalize("/"))
        assertEquals("a/b", FsPaths.normalize("/a/b/"))
        assertEquals("a/b.txt", FsPaths.normalize("a/b.txt"))
    }

    @Test fun rejectsBadSegments() {
        listOf("a//b", "//", "a/./b", "..", "a/../b", "a\\b", "a\u0000b", "a/" + "x".repeat(256)).forEach(::assertInvalid)
        // 128 two-byte chars = 256 bytes
        assertInvalid("é".repeat(128))
        assertEquals("é".repeat(127), FsPaths.normalize("é".repeat(127)))
    }

    @Test fun appliesNfc() {
        assertEquals("é", FsPaths.normalize("é"))
    }

    @Test fun decodesOnce() {
        assertEquals("a b/c", FsPaths.decodeAndNormalize("a%20b/c"))
        assertEquals("100%25", FsPaths.decodeAndNormalize("100%2525"))
        assertEquals("a+b", FsPaths.decodeAndNormalize("a+b"))
        assertEquals("a%2e", FsPaths.decodeAndNormalize("a%252e"))
    }

    @Test fun decodedTraversalRejected() {
        try {
            FsPaths.decodeAndNormalize("a/%2e%2e/b")
            throw AssertionError("expected failure")
        } catch (e: FsException) {
            assertEquals(FsErrorCode.INVALID_PATH, e.code)
        }
        try {
            FsPaths.decodeAndNormalize("a%2")
            throw AssertionError("expected failure")
        } catch (e: FsException) {
            assertEquals(FsErrorCode.INVALID_PATH, e.code)
        }
    }

    @Test fun joinParentName() {
        assertEquals("b", FsPaths.join("", "b"))
        assertEquals("a/b", FsPaths.join("a", "b"))
        assertEquals("a", FsPaths.parent("a/b"))
        assertEquals("", FsPaths.parent("b"))
        assertEquals("b", FsPaths.name("a/b"))
    }

    @Test fun conflictNaming() {
        val taken = setOf("a.txt", "a (1).txt", "dir", "x.tar.gz", ".env")
        assertEquals("a (2).txt", FsPaths.conflictName("a.txt") { it in taken })
        assertEquals("free.txt", FsPaths.conflictName("free.txt") { it in taken })
        assertEquals("dir (1)", FsPaths.conflictName("dir") { it in taken })
        assertEquals("x.tar (1).gz", FsPaths.conflictName("x.tar.gz") { it in taken })
        assertEquals(".env (1)", FsPaths.conflictName(".env") { it in taken })
    }

    @Test fun attachmentsAndHidden() {
        assertEquals(".attachments.doc.md", FsPaths.attachmentsDirFor("doc.md"))
        assertEquals("notes/.attachments.doc.md", FsPaths.attachmentsDirFor("notes/doc.md"))
        assertTrue(FsPaths.isHiddenName(".filestation-trash"))
        assertTrue(FsPaths.isHiddenName(".attachments.x"))
        assertTrue(FsPaths.isHiddenName(".a.txt.fsupload-abc12345"))
        assertTrue(!FsPaths.isHiddenName("normal.txt"))
    }
}
