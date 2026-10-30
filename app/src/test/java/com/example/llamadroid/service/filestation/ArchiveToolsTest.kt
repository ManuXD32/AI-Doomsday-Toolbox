package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.tools.ArchiveLimits
import com.example.llamadroid.service.filestation.tools.ArchiveTools
import com.example.llamadroid.service.filestation.tools.TaskControl
import com.example.llamadroid.service.filestation.tools.VolumeItem
import com.example.llamadroid.service.filestation.volume.ConflictPolicy
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ArchiveToolsTest {
    private lateinit var root: File
    private lateinit var src: LocalFileVolume
    private lateinit var dst: LocalFileVolume
    private lateinit var dstDir: File
    private val ctl = TaskControl()
    private val tools = ArchiveTools()

    @Before fun setUp() {
        root = Files.createTempDirectory("fs-arch").toFile()
        val a = File(root, "a").also { it.mkdirs() }
        dstDir = File(root, "b").also { it.mkdirs() }
        src = LocalFileVolume("a", "A", a)
        dst = LocalFileVolume("b", "B", dstDir)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun put(rel: String, bytes: ByteArray): File =
        File(File(root, "a"), rel).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }

    private fun extract(name: String, t: ArchiveTools = tools, policy: ConflictPolicy = ConflictPolicy.RENAME,
                        pw: CharArray? = null, control: TaskControl = ctl) =
        t.extract(VolumeItem(src, name), dst, "", policy, pw, File(root, "staging"), control)

    private fun assertRejected(name: String, code: FsErrorCode = FsErrorCode.INVALID_PATH) {
        try {
            extract(name)
            fail("expected rejection of $name")
        } catch (e: FsException) {
            assertEquals(code, e.code)
        }
        assertEquals("no temp leftovers", emptyList<String>(), dstDir.list()!!.toList())
        assertFalse(File(root, "x").exists())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    @Test fun zipRoundTripThroughVolumes() {
        val big = ByteArray(300_000).also { Random(7).nextBytes(it) }
        put("docs/x.txt", "hello".toByteArray())
        put("docs/sub/y.bin", big)
        File(root, "a/docs/empty").mkdirs()
        put("single.txt", "one".toByteArray())
        val items = listOf(VolumeItem(src, "docs"), VolumeItem(src, "single.txt"))
        val path = tools.createZipToVolume(items, dst, "", "out.zip", 6, ConflictPolicy.RENAME, ctl)
        assertEquals("out.zip", path)
        assertEquals("only the zip remains", listOf("out.zip"), dstDir.list()!!.toList())
        // second create renames, skip returns null
        assertEquals("out (1).zip", tools.createZipToVolume(items, dst, "", "out.zip", 6, ConflictPolicy.RENAME, ctl))
        assertNull(tools.createZipToVolume(items, dst, "", "out.zip", 6, ConflictPolicy.SKIP, ctl))

        val progress = ArrayList<Float>()
        val r = tools.extract(VolumeItem(dst, "out.zip"), dst, "", ConflictPolicy.RENAME, null, null,
            TaskControl(onProgress = { f, _ -> progress.add(f) }))!!
        assertEquals("out", r.path)
        assertEquals(3, r.files)
        assertEquals("hello", File(dstDir, "out/docs/x.txt").readText())
        assertArrayEquals(big, File(dstDir, "out/docs/sub/y.bin").readBytes())
        assertTrue(File(dstDir, "out/docs/empty").isDirectory)
        assertEquals("one", File(dstDir, "out/single.txt").readText())
        assertTrue(progress.isNotEmpty() && progress.last() == 1f)
        // extracting again keeps the first folder
        assertEquals("out (1)", tools.extract(VolumeItem(dst, "out.zip"), dst, "", ConflictPolicy.RENAME, null, null, ctl)!!.path)
        assertNull(tools.extract(VolumeItem(dst, "out.zip"), dst, "", ConflictPolicy.SKIP, null, null, ctl))
    }

    @Test fun zipSlipAbsoluteAndDriveEntriesAreRejected() {
        put("evil1.zip", zipOf("ok.txt" to "ok", "../x" to "evil"))
        assertRejected("evil1.zip")
        put("evil2.zip", zipOf("/abs.txt" to "evil"))
        assertRejected("evil2.zip")
        put("evil3.zip", zipOf("C:\\x" to "evil"))
        assertRejected("evil3.zip")
        put("evil4.zip", zipOf("C:/x" to "evil"))
        assertRejected("evil4.zip")
        put("evil5.zip", zipOf("a/../../x" to "evil"))
        assertRejected("evil5.zip")
    }

    private fun tarBytes(fill: (TarArchiveOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(bos).use { fill(it); it.finish() }
        return bos.toByteArray()
    }

    private fun TarArchiveOutputStream.file(name: String, text: String) {
        val e = TarArchiveEntry(name)
        val b = text.toByteArray()
        e.size = b.size.toLong()
        putArchiveEntry(e); write(b); closeArchiveEntry()
    }

    private val sample: (TarArchiveOutputStream) -> Unit = { t ->
        val d = TarArchiveEntry("./dir/"); t.putArchiveEntry(d); t.closeArchiveEntry()
        t.file("./dir/a.txt", "alpha")
        val link = TarArchiveEntry("dir/link", TarArchiveEntry.LF_SYMLINK)
        link.linkName = "/etc/passwd"
        t.putArchiveEntry(link); t.closeArchiveEntry()
        val hard = TarArchiveEntry("dir/hard", TarArchiveEntry.LF_LINK)
        hard.linkName = "dir/a.txt"
        t.putArchiveEntry(hard); t.closeArchiveEntry()
        t.file("b.txt", "beta")
    }

    private fun checkSample(r: com.example.llamadroid.service.filestation.tools.ExtractResult?, folder: String) {
        r!!
        assertEquals(folder, r.path)
        assertEquals("alpha", File(dstDir, "$folder/dir/a.txt").readText())
        assertEquals("beta", File(dstDir, "$folder/b.txt").readText())
        assertFalse(java.nio.file.Files.exists(File(dstDir, "$folder/dir/link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(dstDir, "$folder/dir/hard").exists())
        assertEquals(2, r.skipped)
        assertEquals(2, r.files)
    }

    @Test fun tarSkipsSymlinksAndHardlinks() {
        put("t.tar", tarBytes(sample))
        checkSample(extract("t.tar"), "t")
    }

    @Test fun tarGzAndTarXzAndTgz() {
        val raw = tarBytes(sample)
        val gz = ByteArrayOutputStream().also { o -> GzipCompressorOutputStream(o).use { it.write(raw) } }.toByteArray()
        val xz = ByteArrayOutputStream().also { o -> XZCompressorOutputStream(o).use { it.write(raw) } }.toByteArray()
        put("g.tar.gz", gz); put("h.tgz", gz); put("x.tar.xz", xz)
        checkSample(extract("g.tar.gz"), "g")
        checkSample(extract("h.tgz"), "h")
        checkSample(extract("x.tar.xz"), "x")
    }

    @Test fun tarTraversalRejected() {
        put("bad.tar", tarBytes { it.file("../x", "evil") })
        assertRejected("bad.tar")
    }

    @Test fun sevenZipFixture() {
        val tmpA = File(root, "tmpA.txt").also { it.writeText("seven-a") }
        val tmpB = File(root, "tmpB.txt").also { it.writeText("seven-b") }
        val target = File(File(root, "a"), "s.7z")
        SevenZOutputFile(target).use { z ->
            for ((f, n) in listOf(tmpA to "d/a.txt", tmpB to "b.txt")) {
                z.putArchiveEntry(z.createArchiveEntry(f, n))
                z.write(f.readBytes())
                z.closeArchiveEntry()
            }
            z.finish()
        }
        val r = extract("s.7z")!!
        assertEquals("s", r.path)
        assertEquals("seven-a", File(dstDir, "s/d/a.txt").readText())
        assertEquals("seven-b", File(dstDir, "s/b.txt").readText())
        assertTrue("staging cleaned", File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun sevenZipTraversalRejected() {
        val tmp = File(root, "tmpE.txt").also { it.writeText("evil") }
        val target = File(File(root, "a"), "e.7z")
        SevenZOutputFile(target).use { z ->
            z.putArchiveEntry(z.createArchiveEntry(tmp, "../x"))
            z.write(tmp.readBytes()); z.closeArchiveEntry(); z.finish()
        }
        assertRejected("e.7z")
    }

    @Test fun entryCountLimit() {
        put("many.zip", zipOf(*(1..5).map { "f$it.txt" to "x" }.toTypedArray()))
        val small = ArchiveTools(ArchiveLimits(maxEntries = 3))
        try { extract("many.zip", small); fail() } catch (e: FsException) { assertEquals(FsErrorCode.UNSUPPORTED, e.code) }
        assertEquals(emptyList<String>(), dstDir.list()!!.toList())
        assertEquals(5, extract("many.zip")!!.files)
    }

    @Test fun expansionLimitStopsBombs() {
        put("bomb.zip", zipOf("zeros.bin" to "\u0000".repeat(2_000_000)))
        val strict = ArchiveTools(ArchiveLimits(ratioFloorBytes = 0))
        try { extract("bomb.zip", strict); fail() } catch (e: FsException) { assertEquals(FsErrorCode.UNSUPPORTED, e.code) }
        assertEquals("temp removed", emptyList<String>(), dstDir.list()!!.toList())
        // the default 1 GiB floor lets small, very compressible archives through
        assertEquals(1, extract("bomb.zip")!!.files)
    }

    @Test fun aesPasswordZip() {
        val plain = File(root, "secret.txt").also { it.writeText("classified") }
        val zipFile = File(File(root, "a"), "p.zip")
        ZipFile(zipFile, "pw123".toCharArray()).addFile(plain, ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
        })
        try { extract("p.zip"); fail("no password") } catch (e: FsException) { assertEquals(FsErrorCode.UNSUPPORTED, e.code) }
        try { extract("p.zip", pw = "wrong".toCharArray()); fail("wrong password") } catch (e: FsException) { assertEquals(FsErrorCode.UNSUPPORTED, e.code) }
        assertEquals(emptyList<String>(), dstDir.list()!!.toList())
        extract("p.zip", pw = "pw123".toCharArray())
        assertEquals("classified", File(dstDir, "p/secret.txt").readText())
    }

    @Test fun cancelRemovesTempFolder() {
        put("c.zip", zipOf("a.txt" to "a", "b.txt" to "b"))
        var n = 0
        try { extract("c.zip", control = TaskControl(isCancelled = { ++n > 3 })); fail() }
        catch (e: FsException) { assertEquals("cancelled", e.message) }
        assertEquals(emptyList<String>(), dstDir.list()!!.toList())
    }

    @Test fun unsupportedTypeAndFolderSource() {
        put("n.rar", byteArrayOf(1, 2, 3))
        try { extract("n.rar"); fail() } catch (e: FsException) { assertEquals(FsErrorCode.UNSUPPORTED, e.code) }
        put("garbage.zip", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        try { extract("garbage.zip") ; fail() } catch (e: FsException) { assertEquals(FsErrorCode.UNSUPPORTED, e.code) }
        assertEquals(emptyList<String>(), dstDir.list()!!.toList())
    }

    @Test fun zipOfVolumeRootSkipsTrash() {
        put("f/keep.txt", "k".toByteArray())
        put(".filestation-trash/x/old.txt", "o".toByteArray())
        val zipPath = tools.createZipToVolume(listOf(VolumeItem(src, "")), dst, "", "all.zip", 6, ConflictPolicy.RENAME, ctl)!!
        val names = java.util.zip.ZipFile(File(dstDir, zipPath)).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.contains("a/f/keep.txt"))
        assertTrue(names.none { it.contains("trash") })
    }
}
