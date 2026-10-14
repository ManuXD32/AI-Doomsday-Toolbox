package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class NativeChatKvCacheCompressionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = NativeChatKvCacheStore(File(tmp.root, NativeChatKvCacheStore.DIR_NAME))

    private val key = KvCacheKey.Chat(7)
    private val day = 24L * 60 * 60 * 1000

    private fun meta(savedAt: Long = 1_000L) = KvCacheMeta("fp", 4096, 0, 10, savedAt, 1L)

    private fun seed(store: NativeChatKvCacheStore, bytes: ByteArray, k: KvCacheKey = key, savedAt: Long = 1_000L) {
        store.directory.mkdirs()
        File(store.directory, store.slotFileName(k)).apply {
            writeBytes(bytes)
            setLastModified(savedAt)
        }
        store.writeMeta(k, meta(savedAt))
    }

    private fun compressible() = ByteArray(3 * 1024 * 1024) { (it % 7).toByte() }

    private fun random() = ByteArray(2 * 1024 * 1024).also { Random(1).nextBytes(it) }

    @Test
    fun `sample decision needs ten percent saving`() {
        assertTrue(NativeChatKvCacheStore.sampleSavesEnough(1000, 900))
        assertFalse(NativeChatKvCacheStore.sampleSavesEnough(1000, 901))
        assertFalse(NativeChatKvCacheStore.sampleSavesEnough(0, 0))
    }

    @Test
    fun `compress then unpack round trips and records savings`() {
        val store = store()
        val data = compressible()
        seed(store, data)
        val stem = key.fileStem
        assertEquals(listOf(stem), store.idleCompressionCandidates(2 * day, day, emptySet()))
        assertEquals(KvCompressionOutcome.COMPRESSED, store.compressStem(stem))
        assertTrue(store.isCompressed(key))
        assertFalse(File(store.directory, "$stem.bin").exists())
        assertTrue(store.sizeBytes(key) in 1 until data.size.toLong())
        assertTrue(store.compressionSavedBytes() > 0L)
        assertEquals(store.totalBytes(), store.breakdown().values.sum())
        assertTrue(store.unpack(key))
        assertFalse(store.isCompressed(key))
        assertTrue(data.contentEquals(File(store.directory, "$stem.bin").readBytes()))
        assertEquals(0L, store.compressionSavedBytes())
        assertNull(store.directory.listFiles()?.firstOrNull { it.name.endsWith(".tmp") })
    }

    @Test
    fun `incompressible file is flagged once and left alone`() {
        val store = store()
        seed(store, random())
        assertEquals(KvCompressionOutcome.INCOMPRESSIBLE, store.compressStem(key.fileStem))
        assertTrue(store.readMeta(key)!!.incompressible)
        assertTrue(File(store.directory, "${key.fileStem}.bin").exists())
        assertTrue(store.idleCompressionCandidates(2 * day, day, emptySet()).isEmpty())
    }

    @Test
    fun `recent and protected caches are not candidates`() {
        val store = store()
        seed(store, compressible(), savedAt = day)
        assertTrue(store.idleCompressionCandidates(day + 1_000L, day, emptySet()).isEmpty())
        assertTrue(store.idleCompressionCandidates(3 * day, day, setOf(key.fileStem)).isEmpty())
        assertEquals(1, store.idleCompressionCandidates(3 * day, day, emptySet()).size)
    }

    @Test
    fun `stems and chat parsing accept gz files`() {
        assertEquals(7L, NativeChatKvCacheStore.parseChatId("chat-7.bin.gz"))
        assertEquals(-5L, NativeChatKvCacheStore.parseChatId("chat-n4.bin.gz.tmp"))
        val store = store()
        seed(store, compressible())
        store.compressStem(key.fileStem)
        assertEquals(listOf(7L), store.listChats())
        assertEquals(KvCacheNamespace.CHAT, KvCacheKey.namespaceOfStem("chat-7"))
        store.delete(key)
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun `meta flags survive encode and decode and default for old sidecars`() {
        val encoded = NativeChatKvCacheStore.encodeMeta(meta().copy(originalBytes = 99, incompressible = true))
        val parsed = NativeChatKvCacheStore.parseMeta(encoded)
        assertEquals(99L, parsed.originalBytes)
        assertTrue(parsed.incompressible)
        val old = NativeChatKvCacheStore.parseMeta("""{"fingerprint":"x"}""")
        assertFalse(old.incompressible)
        assertEquals(0L, old.originalBytes)
    }

    @Test
    fun `base decisions`() {
        assertTrue(shouldRestoreBase(hasHint = true, ownStatePresent = false, baseFileValid = true))
        assertFalse(shouldRestoreBase(hasHint = true, ownStatePresent = true, baseFileValid = true))
        assertFalse(shouldRestoreBase(hasHint = true, ownStatePresent = false, baseFileValid = false))
        assertFalse(shouldRestoreBase(hasHint = false, ownStatePresent = false, baseFileValid = true))
        assertTrue(shouldCaptureBase(capture = true, baseFileValid = false, ownStatePresent = false))
        assertFalse(shouldCaptureBase(capture = true, baseFileValid = true, ownStatePresent = false))
        assertFalse(shouldCaptureBase(capture = true, baseFileValid = false, ownStatePresent = true))
        assertFalse(shouldCaptureBase(capture = false, baseFileValid = false, ownStatePresent = false))
    }

    @Test
    fun `base key stem is in the base namespace and prefix hash depends on tools and system`() {
        val key = NativeChatBasePrefix.key("http://127.0.0.1:8080", "m", "sys", emptyList())
        assertEquals(KvCacheNamespace.BASE, KvCacheKey.namespaceOfStem(key.fileStem))
        val tool = AgentTool("t", "d", mapOf("a" to "b"))
        assertTrue(key != NativeChatBasePrefix.key("http://127.0.0.1:8080", "m", "sys", listOf(tool)))
        assertTrue(key != NativeChatBasePrefix.key("http://127.0.0.1:8080", "m", "sys2", emptyList()))
        assertEquals(key, NativeChatBasePrefix.key("http://127.0.0.1:8080", "m", "sys", emptyList()))
    }
}
