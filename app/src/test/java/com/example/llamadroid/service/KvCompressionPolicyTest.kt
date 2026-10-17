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

class KvCompressionPolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = KvCompressionPolicy.FAST_IDLE_MS
    private val day = KvCompressionPolicy.SLOW_IDLE_MS
    private val mb = 1024L * 1024

    @Test
    fun `small cache is due after one hour with the default speed`() {
        val speed = KvCompressionPolicy.DEFAULT_BYTES_PER_MS
        assertTrue(KvCompressionPolicy.isDue(hour, 200 * mb, speed))
        assertFalse(KvCompressionPolicy.isDue(hour - 1, 200 * mb, speed))
    }

    @Test
    fun `cache that unpacks slower than three seconds waits a day`() {
        val speed = KvCompressionPolicy.DEFAULT_BYTES_PER_MS // 150 MB/s -> 450 MB is the 3 s limit
        assertEquals(3_000L, KvCompressionPolicy.estimateUnpackMs(450_000_000L, speed))
        assertEquals(hour, KvCompressionPolicy.requiredIdleMs(450_000_000L, speed))
        assertEquals(day, KvCompressionPolicy.requiredIdleMs(451_000_000L, speed))
        assertFalse(KvCompressionPolicy.isDue(2 * hour, 600 * mb, speed))
        assertTrue(KvCompressionPolicy.isDue(day, 600 * mb, speed))
    }

    @Test
    fun `measured speed changes the estimate`() {
        val slow = 50_000.0 // 50 MB/s
        assertEquals(day, KvCompressionPolicy.requiredIdleMs(200 * mb, slow))
        val fast = 400_000.0
        assertEquals(hour, KvCompressionPolicy.requiredIdleMs(1_000_000_000L, fast))
    }

    @Test
    fun `compress now ignores idle time`() {
        assertTrue(KvCompressionPolicy.isDue(0L, 5_000 * mb, 1.0, ignoreIdle = true))
    }

    @Test
    fun `speed average starts at the first sample then smooths`() {
        val first = KvCompressionPolicy.updatedSpeed(null, 100 * mb, 1_000L)!!
        assertEquals(100.0 * mb / 1_000.0, first, 0.001)
        val second = KvCompressionPolicy.updatedSpeed(first, 200 * mb, 1_000L)!!
        val sample = 200.0 * mb / 1_000.0
        assertEquals(first + KvCompressionPolicy.EMA_ALPHA * (sample - first), second, 0.001)
    }

    @Test
    fun `tiny or zero-time samples are ignored`() {
        assertNull(KvCompressionPolicy.updatedSpeed(null, 1 * mb, 100L))
        assertEquals(5.0, KvCompressionPolicy.updatedSpeed(5.0, 100 * mb, 0L)!!, 0.0)
    }

    private fun store(
        onDeleted: (Set<String>) -> Unit = {},
        onUnpacked: (Long, Long) -> Unit = { _, _ -> }
    ) = NativeChatKvCacheStore(File(tmp.root, NativeChatKvCacheStore.DIR_NAME), onDeleted, onUnpacked)

    private fun seed(store: NativeChatKvCacheStore, key: KvCacheKey, bytes: ByteArray, savedAt: Long) {
        store.directory.mkdirs()
        File(store.directory, store.slotFileName(key)).apply { writeBytes(bytes); setLastModified(savedAt) }
        store.writeMeta(key, KvCacheMeta("fp", 4096, 0, 10, savedAt, 1L))
    }

    private fun compressible() = ByteArray(3 * 1024 * 1024) { (it % 7).toByte() }

    @Test
    fun `candidates follow the one hour rule and the compress now override`() {
        val store = store()
        val key = KvCacheKey.Chat(1)
        seed(store, key, compressible(), savedAt = 1_000L)
        val speed = KvCompressionPolicy.DEFAULT_BYTES_PER_MS
        assertTrue(store.compressionCandidates(1_000L + hour - 1, speed, emptySet()).isEmpty())
        assertEquals(listOf(key.fileStem), store.compressionCandidates(1_000L + hour + 1, speed, emptySet()))
        assertEquals(listOf(key.fileStem), store.compressionCandidates(1_001L, speed, emptySet(), ignoreIdle = true))
        assertTrue(store.compressionCandidates(1_001L, speed, setOf(key.fileStem), ignoreIdle = true).isEmpty())
    }

    @Test
    fun `sample rule still marks random data incompressible under compress now`() {
        val store = store()
        val key = KvCacheKey.Chat(2)
        seed(store, key, ByteArray(2 * 1024 * 1024).also { Random(1).nextBytes(it) }, savedAt = 1_000L)
        assertEquals(KvCompressionOutcome.INCOMPRESSIBLE, store.compressStem(key.fileStem))
        assertTrue(store.compressionCandidates(9_999_999L, 150_000.0, emptySet(), ignoreIdle = true).isEmpty())
    }

    @Test
    fun `unpack reports measured bytes`() {
        var reported = -1L
        val store = store(onUnpacked = { bytes, _ -> reported = bytes })
        val key = KvCacheKey.Chat(3)
        val data = compressible()
        seed(store, key, data, savedAt = 1_000L)
        assertEquals(KvCompressionOutcome.COMPRESSED, store.compressStem(key.fileStem))
        assertTrue(store.unpack(key))
        assertEquals(data.size.toLong(), reported)
    }

    @Test
    fun `every delete path reports the deleted stems`() {
        val deleted = mutableListOf<Set<String>>()
        val store = store(onDeleted = { deleted += it })
        val base = KvCacheKey.Base("chat", "p1")
        val base2 = KvCacheKey.Base("chat", "p2")
        val chat = KvCacheKey.Chat(5)
        fun fill() {
            seed(store, base, ByteArray(10), 1_000L)
            seed(store, base2, ByteArray(10), 2_000L)
            seed(store, chat, ByteArray(10), 3_000L)
        }
        fill()
        store.delete(base)
        assertEquals(setOf(base.fileStem), deleted.last())

        store.deleteNamespace(KvCacheNamespace.BASE)
        assertEquals(setOf(base2.fileStem), deleted.last())

        fill()
        store.enforceBudget(1L, protectedStems = setOf(chat.fileStem), namespaces = setOf(KvCacheNamespace.BASE))
        assertEquals(setOf(base.fileStem, base2.fileStem), deleted.last())

        fill()
        store.clearAll()
        assertEquals(setOf(base.fileStem, base2.fileStem, chat.fileStem), deleted.last())
        assertTrue(store.existingStems().isEmpty())
    }

    @Test
    fun `stale registry keys are the ones without files`() {
        val entries = mapOf(
            "usage:base-chat-aaaa" to KvBaseUsage(uses = 2).encode(),
            "usage:base-chat-bbbb" to KvBaseUsage(uses = 1).encode(),
            "usage:base-chat-cccc" to KvBaseUsage(builtAt = 5L).encode(),
            // Never built: only misses are recorded, so it is kept for the miss reason.
            "usage:base-chat-dddd" to KvBaseUsage(misses = 3).encode(),
            "last:123" to "base-chat-aaaa",
            "last:456" to "base-chat-gone"
        )
        val stale = staleRegistryKeys(entries, setOf("base-chat-aaaa"))
        assertEquals(setOf("usage:base-chat-bbbb", "usage:base-chat-cccc"), stale.toSet())
    }

    @Test
    fun `settled slot means not processing`() {
        assertTrue(isSlotSettled(KvSlotFact(0, 4096, 3, 10, isProcessing = false)))
        assertTrue(isSlotSettled(KvSlotFact(0, 4096, 3, 10)))
        assertFalse(isSlotSettled(KvSlotFact(0, 4096, 3, 10, isProcessing = true)))
        assertFalse(isSlotSettled(null))
    }
}
