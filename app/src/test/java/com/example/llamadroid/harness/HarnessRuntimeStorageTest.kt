package com.example.llamadroid.harness

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessRuntimeStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unreadableGuestDirectoryReproducesOldFailureButDoesNotAbortSizeEstimate() {
        val root = temporary.newFolder("guest").toPath()
        Files.write(root.resolve("readable"), ByteArray(19))
        val locked = Files.createDirectory(root.resolve("locked"))
        Files.write(locked.resolve("private"), ByteArray(31))
        val permissions = Files.getPosixFilePermissions(locked)
        try {
            Files.setPosixFilePermissions(locked, emptySet())
            assumeFalse("The host must enforce directory permissions", Files.isReadable(locked))
            assertThrows(AccessDeniedException::class.java) {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {})
            }
            assertEquals(19L, estimateHarnessStorage(listOf(root.toFile())))
        } finally {
            Files.setPosixFilePermissions(locked, permissions)
        }
    }

    @Test fun guestLinksNeverCountOutsideFilesOrFollowCycles() {
        val root = temporary.newFolder("guest").toPath()
        val outside = temporary.newFolder("outside").toPath()
        Files.write(root.resolve("own"), ByteArray(7))
        Files.write(outside.resolve("secret"), ByteArray(101))
        Files.createSymbolicLink(root.resolve("outside"), outside)
        Files.createSymbolicLink(root.resolve("loop"), root)
        Files.createSymbolicLink(root.resolve("missing"), root.resolve("absent"))
        assertEquals(7L, estimateHarnessStorage(listOf(root.toFile())))
    }

    @Test fun vanishedRootsDoNotPreventCountingRemainingRoots() {
        val root = temporary.newFolder("remaining")
        root.resolve("file").writeBytes(ByteArray(13))
        assertEquals(13L, estimateHarnessStorage(listOf(temporary.root.resolve("vanished"), root)))
    }
}
