package com.example.llamadroid.harness

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.util.zip.GZIPOutputStream

class HarnessRootfsFileStoreTest {
    @Test
    fun createsEditsRenamesAndDeletesInsideRoot() = withRootfs { root, store ->
        store.createFolder("/work").getOrThrow()
        store.createFile("/work/note.txt").getOrThrow()
        store.writeText("/work/note.txt", "hello").getOrThrow()
        assertEquals("hello", store.readText("/work/note.txt").getOrThrow())

        store.rename("/work/note.txt", "/work/renamed.txt").getOrThrow()
        assertTrue(root.resolve("work/renamed.txt").toFile().isFile)
        store.delete("/work").getOrThrow()
        assertFalse(root.resolve("work").toFile().exists())
    }

    @Test
    fun rejectsTraversalAndExternalSymlinkButReadsInternalSymlink() = runBlocking {
        val root = Files.createTempDirectory("rootfs-store")
        val outside = Files.createTempDirectory("rootfs-outside")
        try {
            Files.createDirectories(root.resolve("usr/share"))
            Files.write(root.resolve("usr/share/info.txt"), "inside".toByteArray())
            Files.createSymbolicLink(root.resolve("share"), root.resolve("usr/share"))
            Files.write(outside.resolve("secret.txt"), "outside".toByteArray())
            Files.createSymbolicLink(root.resolve("escape"), outside)
            val store = HarnessRootfsFileStore(root)

            assertEquals("inside", store.readText("/share/info.txt").getOrThrow())
            assertTrue(store.readText("/../secret.txt").isFailure)
            assertTrue(store.readText("/escape/secret.txt").isFailure)
            assertTrue(store.writeText("/escape/new.txt", "blocked").isFailure)
            assertFalse(outside.resolve("new.txt").toFile().exists())
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun exportsDirectoryAsNonEmptyTarGzipWithoutFollowingSymlinks() = withRootfs { root, store ->
        Files.createDirectories(root.resolve("project"))
        Files.write(root.resolve("project/main.txt"), "payload".toByteArray())
        Files.createSymbolicLink(root.resolve("project/loop"), root.resolve("project"))
        val output = ByteArrayOutputStream()

        store.exportArchive("/project", output).getOrThrow()

        val bytes = output.toByteArray()
        assertTrue(bytes.size > 16)
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun copiesAndMovesFilesAndDirectoriesWithoutFollowingSymlinks() = withRootfs { root, store ->
        Files.createDirectories(root.resolve("source/nested"))
        Files.write(root.resolve("source/nested/value.txt"), "value".toByteArray())
        Files.createDirectories(root.resolve("destination"))

        store.copy("/source", "/destination/source-copy").getOrThrow()
        assertEquals("value", String(Files.readAllBytes(root.resolve("destination/source-copy/nested/value.txt"))))
        store.move("/destination/source-copy/nested/value.txt", "/destination/moved.txt").getOrThrow()
        assertTrue(Files.isRegularFile(root.resolve("destination/moved.txt")))

        Files.createSymbolicLink(root.resolve("linked-source"), root.resolve("source"))
        assertTrue(store.copy("/linked-source", "/destination/linked-copy").isFailure)
        assertTrue(store.exportArchive("/linked-source", ByteArrayOutputStream()).isFailure)
    }

    @Test
    fun resolvesMountedProjectsWhilePreservingGuestPaths() = runBlocking {
        val root = Files.createTempDirectory("rootfs-store")
        val projects = Files.createTempDirectory("rootfs-projects")
        try {
            Files.createDirectories(projects.resolve("prueba2"))
            Files.write(projects.resolve("prueba2/main.c"), "int main() {}".toByteArray())
            val store = HarnessRootfsFileStore(
                listOf(
                    GuestMount("/", root),
                    GuestMount("/workspace/projects", projects),
                )
            )

            assertEquals("workspace", store.listDirectory("/").getOrThrow().single().name)
            assertEquals("projects", store.listDirectory("/workspace").getOrThrow().single().name)
            val entries = store.listDirectory("/workspace/projects").getOrThrow()
            assertEquals("/workspace/projects/prueba2", entries.single().path)
            assertEquals(
                "int main() {}",
                store.readText("/workspace/projects/prueba2/main.c").getOrThrow()
            )
            store.createFile("/workspace/projects/prueba2/README.md").getOrThrow()
            store.writeText("/workspace/projects/prueba2/README.md", "mounted").getOrThrow()
            assertTrue(Files.isRegularFile(projects.resolve("prueba2/README.md")))
        } finally {
            root.toFile().deleteRecursively()
            projects.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsExternalSymlinkFromMountedProject() = runBlocking {
        val root = Files.createTempDirectory("rootfs-store")
        val projects = Files.createTempDirectory("rootfs-projects")
        val outside = Files.createTempDirectory("rootfs-outside")
        try {
            Files.write(outside.resolve("secret.txt"), "outside".toByteArray())
            Files.createSymbolicLink(projects.resolve("escape"), outside)
            val store = HarnessRootfsFileStore(
                listOf(GuestMount("/", root), GuestMount("/workspace/projects", projects))
            )
            assertTrue(store.readText("/workspace/projects/escape/secret.txt").isFailure)
            assertTrue(store.writeText("/workspace/projects/escape/new.txt", "blocked").isFailure)
            assertFalse(Files.exists(outside.resolve("new.txt")))
        } finally {
            root.toFile().deleteRecursively()
            projects.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun compressesAndExtractsTarGzipWithAtomicDestinationCommit() = withRootfs { root, store ->
        Files.createDirectories(root.resolve("payload/nested"))
        Files.write(root.resolve("payload/nested/value.txt"), "payload".toByteArray())

        store.compressToArchive("/payload", "/payload.tar.gz").getOrThrow()
        assertTrue(Files.isRegularFile(root.resolve("payload.tar.gz")))
        store.extractArchive("/payload.tar.gz", "/restored").getOrThrow()

        assertEquals("payload", String(Files.readAllBytes(root.resolve("restored/payload/nested/value.txt"))))
        assertTrue(store.extractArchive("/payload.tar.gz", "/restored").isFailure)
        assertFalse(Files.exists(root.resolve("restored/restored"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun rejectsUnsafeArchivePathsLinksAndSpecialEntries() = withRootfs { root, store ->
        writeTarGzip(root.resolve("unsafe-path.tar.gz"), TarArchiveEntry("../escape.txt"))
        assertTrue(store.extractArchive("/unsafe-path.tar.gz", "/out").isFailure)
        assertFalse(Files.exists(root.resolve("escape.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS))

        writeTarGzip(
            root.resolve("unsafe-link.tar.gz"),
            TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "../../escape" },
        )
        assertTrue(store.extractArchive("/unsafe-link.tar.gz", "/out-link").isFailure)

        writeTarGzip(
            root.resolve("unsafe-device.tar.gz"),
            TarArchiveEntry("device", TarArchiveEntry.LF_CHR),
        )
        assertTrue(store.extractArchive("/unsafe-device.tar.gz", "/out-device").isFailure)
    }

    private fun writeTarGzip(archive: java.nio.file.Path, entry: TarArchiveEntry) {
        val content = if (entry.isFile) "unsafe".toByteArray() else ByteArray(0)
        if (entry.isFile) entry.size = content.size.toLong()
        Files.newOutputStream(archive).use { output ->
            TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
                tar.putArchiveEntry(entry)
                if (content.isNotEmpty()) tar.write(content)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun withRootfs(block: suspend (java.nio.file.Path, HarnessRootfsFileStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("rootfs-store")
        try {
            block(root, HarnessRootfsFileStore(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
