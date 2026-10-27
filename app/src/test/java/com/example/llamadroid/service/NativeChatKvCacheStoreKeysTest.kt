package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeChatKvCacheStoreKeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = NativeChatKvCacheStore(File(tmp.root, NativeChatKvCacheStore.DIR_NAME))

    private fun write(store: NativeChatKvCacheStore, key: KvCacheKey, bytes: Int, savedAt: Long) {
        store.directory.mkdirs()
        File(store.directory, store.slotFileName(key)).writeBytes(ByteArray(bytes))
        store.writeMeta(key, KvCacheMeta("fp", 4096, bytes.toLong(), 1, savedAt, 1L))
    }

    private fun sidecarBytes(store: NativeChatKvCacheStore, key: KvCacheKey) =
        File(store.directory, "${key.fileStem}.json").length()

    @Test
    fun `key based api round trips for every namespace`() {
        val store = store()
        val keys = listOf(KvCacheKey.Chat(3), KvCacheKey.Delegate(4), KvCacheKey.Harness("rt", "session-1"))
        keys.forEachIndexed { index, key -> write(store, key, 100 * (index + 1), 10L) }
        keys.forEachIndexed { index, key ->
            assertEquals(100L * (index + 1), store.sizeBytes(key))
            assertEquals("fp", store.readMeta(key)?.fingerprint)
        }
        store.delete(keys[1])
        assertEquals(0L, store.sizeBytes(keys[1]))
        assertEquals(null, store.readMeta(keys[1]))
    }

    @Test
    fun `chat overloads delegate to the chat key`() {
        val store = store()
        write(store, KvCacheKey.Chat(9), 10, 1L)
        assertEquals(10L, store.sizeBytes(9L))
        assertEquals("fp", store.readMeta(9L)?.fingerprint)
        assertEquals("chat-9.bin", store.slotFileName(9L))
    }

    @Test
    fun `breakdown sums per namespace and lists empty ones`() {
        val store = store()
        write(store, KvCacheKey.Chat(1), 100, 1L)
        write(store, KvCacheKey.Harness("rt", "s"), 300, 1L)
        val breakdown = store.breakdown()
        assertEquals(KvCacheNamespace.values().toSet(), breakdown.keys)
        assertEquals(0L, breakdown[KvCacheNamespace.DELEGATE])
        assertTrue(breakdown.getValue(KvCacheNamespace.HARNESS) >= 300L)
        assertEquals(store.totalBytes(), breakdown.values.sum())
    }

    @Test
    fun `deleteNamespace and deleteHarnessRuntime only touch their files`() {
        val store = store()
        write(store, KvCacheKey.Chat(1), 10, 1L)
        write(store, KvCacheKey.Delegate(1), 10, 1L)
        write(store, KvCacheKey.Harness("rt-a", "s1"), 10, 1L)
        write(store, KvCacheKey.Harness("rt-a", "s2"), 10, 1L)
        write(store, KvCacheKey.Harness("rt-b", "s1"), 10, 1L)
        store.deleteHarnessRuntime("rt-a")
        assertEquals(0L, store.sizeBytes(KvCacheKey.Harness("rt-a", "s1")))
        assertEquals(0L, store.sizeBytes(KvCacheKey.Harness("rt-a", "s2")))
        assertEquals(10L, store.sizeBytes(KvCacheKey.Harness("rt-b", "s1")))
        store.deleteNamespace(KvCacheNamespace.DELEGATE)
        assertEquals(0L, store.sizeBytes(KvCacheKey.Delegate(1)))
        assertEquals(10L, store.sizeBytes(KvCacheKey.Chat(1)))
        store.deleteNamespace(KvCacheNamespace.HARNESS)
        assertEquals(0L, store.breakdown().getValue(KvCacheNamespace.HARNESS))
    }

    @Test
    fun `harness runtime bytes counts only that runtime`() {
        val store = store()
        write(store, KvCacheKey.Harness("rt-a", "s1"), 50, 1L)
        write(store, KvCacheKey.Harness("rt-b", "s1"), 70, 1L)
        val a = store.harnessRuntimeBytes("rt-a")
        assertEquals(50L + sidecarBytes(store, KvCacheKey.Harness("rt-a", "s1")), a)
    }

    @Test
    fun `budget evicts oldest first and never protected stems`() {
        val store = store()
        val oldest = KvCacheKey.Chat(1)
        val middle = KvCacheKey.Delegate(2)
        val newest = KvCacheKey.Chat(3)
        write(store, oldest, 1000, 10L)
        write(store, middle, 1000, 20L)
        write(store, newest, 1000, 30L)
        val freed = store.enforceBudget(2500L, setOf(oldest.fileStem))
        assertTrue(freed > 0)
        assertEquals(1000L, store.sizeBytes(oldest))
        assertEquals(0L, store.sizeBytes(middle))
        assertEquals(1000L, store.sizeBytes(newest))
    }

    @Test
    fun `budget namespace filter leaves other namespaces alone`() {
        val store = store()
        write(store, KvCacheKey.Chat(1), 1000, 10L)
        write(store, KvCacheKey.Harness("rt", "s"), 1000, 20L)
        store.enforceBudget(0L, emptySet(), setOf(KvCacheNamespace.HARNESS))
        assertEquals(1000L, store.sizeBytes(KvCacheKey.Chat(1)))
        assertEquals(0L, store.sizeBytes(KvCacheKey.Harness("rt", "s")))
    }

    @Test
    fun `budget under the limit deletes nothing`() {
        val store = store()
        write(store, KvCacheKey.Chat(1), 100, 10L)
        assertEquals(0L, store.enforceBudget(1_000_000L, emptySet()))
        assertEquals(100L, store.sizeBytes(KvCacheKey.Chat(1)))
    }

    @Test
    fun `free space guard needs two GiB plus the expected file`() {
        val gib = 1024L * 1024 * 1024
        assertFalse(NativeChatKvCacheStore.hasRoomToSave(2 * gib - 1, 0))
        assertTrue(NativeChatKvCacheStore.hasRoomToSave(2 * gib, 0))
        assertFalse(NativeChatKvCacheStore.hasRoomToSave(2 * gib + 99, 100))
        assertTrue(NativeChatKvCacheStore.hasRoomToSave(2 * gib + 100, 100))
    }

    @Test
    fun `listChats stays chat only`() {
        val store = store()
        write(store, KvCacheKey.Chat(5), 10, 1L)
        write(store, KvCacheKey.Delegate(5), 10, 1L)
        write(store, KvCacheKey.Harness("rt", "s"), 10, 1L)
        assertEquals(listOf(5L), store.listChats())
    }

    @Test
    fun `pet key has hashed stem, namespace and parses back`() {
        val key = KvCacheKey.Pet("pet-123")
        assertTrue(Regex("pet-[0-9a-f]{16}").matches(key.fileStem))
        assertFalse(key.fileStem.contains("123"))
        assertEquals(key.fileStem, KvCacheKey.Pet("pet-123").fileStem)
        assertFalse(key.fileStem == KvCacheKey.Pet("pet-124").fileStem)
        assertEquals(KvCacheNamespace.PET, key.namespace)
        assertEquals(KvCacheNamespace.PET, KvCacheKey.namespaceOfStem(key.fileStem))
        assertEquals(null, KvCacheKey.namespaceOfStem("pet-xyz"))
    }

    @Test
    fun `pet caches count in breakdown and enforce with chat budget set`() {
        val store = store()
        val pet = KvCacheKey.Pet("p1")
        write(store, pet, 300, 5L)
        write(store, KvCacheKey.Chat(1), 100, 6L)
        assertEquals(300L + sidecarBytes(store, pet), store.breakdown()[KvCacheNamespace.PET])
        store.enforceBudget(
            1L,
            emptySet(),
            setOf(KvCacheNamespace.CHAT, KvCacheNamespace.DELEGATE, KvCacheNamespace.PET)
        )
        assertEquals(0L, store.sizeBytes(pet))
        assertEquals(0L, store.sizeBytes(KvCacheKey.Chat(1)))
    }
}
