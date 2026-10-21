package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeChatKvCacheStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = KvCacheFingerprintInput(
        modelPath = "/models/a.gguf",
        modelSizeBytes = 100L,
        modelModifiedMs = 5L,
        slotNCtx = 8192,
        kvTypeK = "f16",
        kvTypeV = "f16",
        mmprojPath = null,
        loras = emptyList(),
        binary = "baseline",
        chatTemplate = "{{ template }}"
    )

    private fun store() = NativeChatKvCacheStore(File(tmp.root, NativeChatKvCacheStore.DIR_NAME))

    @Test
    fun `fingerprint is stable and sensitive to every input`() {
        val fp = NativeChatKvCacheStore.fingerprint(base)
        assertEquals(fp, NativeChatKvCacheStore.fingerprint(base.copy()))
        val variants = listOf(
            base.copy(modelPath = "/models/b.gguf"),
            base.copy(modelSizeBytes = 101L),
            base.copy(modelModifiedMs = 6L),
            base.copy(slotNCtx = 4096),
            base.copy(kvTypeK = "q8_0"),
            base.copy(kvTypeV = "q8_0"),
            base.copy(mmprojPath = "/models/mm.gguf"),
            base.copy(loras = listOf("/l.gguf@1.0")),
            base.copy(binary = "dotprod"),
            base.copy(chatTemplate = "{{ other }}")
        )
        variants.forEach { assertNotEquals(fp, NativeChatKvCacheStore.fingerprint(it)) }
    }

    @Test
    fun `sidecar round trip keeps metadata only`() {
        val store = store()
        val meta = KvCacheMeta("abc", 8192, 1234L, 99, 777L, 42L)
        store.writeMeta(7L, meta)
        assertEquals(meta, store.readMeta(7L))
        val text = File(store.directory, "chat-7.json").readText()
        assertEquals(setOf("fingerprint", "slotNCtx", "bytes", "tokens", "savedAt", "serverRowId"),
            org.json.JSONObject(text).keys().asSequence().toSet())
        assertNull(store.readMeta(8L))
    }

    @Test
    fun `size delete total clear and list cover bin and sidecar`() {
        val store = store()
        store.directory.mkdirs()
        File(store.directory, "chat-1.bin").writeBytes(ByteArray(10))
        store.writeMeta(1L, KvCacheMeta("f", 1, 10, 1, 1, 1))
        File(store.directory, "chat-2.bin").writeBytes(ByteArray(5))
        assertEquals(10L, store.sizeBytes(1L))
        assertEquals(listOf(1L, 2L), store.listChats())
        assertTrue(store.totalBytes() >= 15L)
        store.delete(1L)
        assertEquals(0L, store.sizeBytes(1L))
        assertNull(store.readMeta(1L))
        assertEquals(listOf(2L), store.listChats())
        store.clearAll()
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun `generated filenames satisfy llama server validation`() {
        listOf(0L, 1L, 99999999999L, -1L, -5L).forEach { id ->
            assertTrue(NativeChatKvCacheStore.isValidSlotFilename(NativeChatKvCacheStore.slotFileName(id, "bin")))
        }
        assertNotEquals(NativeChatKvCacheStore.fileStem(-1L), NativeChatKvCacheStore.fileStem(1L))
        assertEquals(-5L, NativeChatKvCacheStore.parseChatId("chat-n4.bin"))
        assertFalse(NativeChatKvCacheStore.isValidSlotFilename("../x"))
        assertFalse(NativeChatKvCacheStore.isValidSlotFilename("a/b"))
        assertFalse(NativeChatKvCacheStore.isValidSlotFilename(""))
        assertFalse(NativeChatKvCacheStore.isValidSlotFilename("a:b"))
        assertFalse(NativeChatKvCacheStore.isValidSlotFilename("x".repeat(256)))
    }

    @Test
    fun `slot save path is added once for llama server only`() {
        val files = tmp.newFolder("files")
        val args = listOf("/lib/libllama_server_baseline.so", "--port", "8080")
        val out = NativeChatKvCacheStore.withSlotSavePath(args, files)
        assertEquals(1, out.count { it == "--slot-save-path" })
        val dir = File(files, "llama_kv_slots")
        assertEquals(dir.absolutePath, out[out.indexOf("--slot-save-path") + 1])
        assertTrue(dir.isDirectory)
        assertEquals(out, NativeChatKvCacheStore.withSlotSavePath(out, files))
    }

    @Test
    fun `existing slot save path flag from user or template is respected`() {
        val files = tmp.newFolder("files2")
        val custom = listOf("/lib/libllama_server.so", "--slot-save-path", "/custom")
        assertEquals(custom, NativeChatKvCacheStore.withSlotSavePath(custom, files))
        val eq = listOf("/lib/libllama_server.so", "--slot-save-path=/custom")
        assertEquals(eq, NativeChatKvCacheStore.withSlotSavePath(eq, files))
    }

    @Test
    fun `other native binaries do not get the flag`() {
        val files = tmp.newFolder("files3")
        listOf("libllama_rpc_server.so", "libllama_bench.so", "libtts_server.so").forEach {
            val args = listOf("/lib/$it", "--port", "1")
            assertEquals(args, NativeChatKvCacheStore.withSlotSavePath(args, files))
        }
    }

    @Test
    fun `eligibility mapping reports a reason for each blocker`() {
        fun e(engine: Boolean = true, loopback: Boolean = true, on: Boolean = true, slots: Boolean? = null) =
            kvCacheEligibility(engine, loopback, on, slots)
        assertEquals(KvCacheEligibility.ELIGIBLE, e())
        assertEquals(KvCacheEligibility.ELIGIBLE, e(slots = true))
        assertEquals(KvCacheEligibility.DISABLED, e(on = false))
        assertEquals(KvCacheEligibility.REMOTE_SERVER, e(loopback = false))
        assertEquals(KvCacheEligibility.UNSUPPORTED_ENGINE, e(engine = false, loopback = false))
        assertEquals(KvCacheEligibility.SERVER_NEEDS_RESTART, e(slots = false))
        assertTrue(LlamaServerEntity(name = "x", host = "h", port = 1, engine = LlamaServerEntity.ENGINE_OLLAMA)
            .isLlamaServerEngine().not())
    }

    @Test
    fun `props and slots parsing extracts server facts`() {
        val props = parseKvServerProps(
            """{"model_path":"/m.gguf","total_slots":2,"chat_template":"t","default_generation_settings":{"n_ctx":4096}}"""
        )!!
        assertEquals(4096, props.slotNCtx)
        assertEquals(2, props.totalSlots)
        assertEquals("/m.gguf", props.modelPath)
        val slots = parseKvSlots("""[{"id":0,"n_ctx":4096},{"id":1,"n_ctx":4096,"id_task":7,"n_prompt_tokens":12}]""")
        assertEquals(listOf(null, 7), slots.map { it.idTask })
        assertEquals(12, slots[1].nPromptTokens)
    }

    @Test
    fun `kv slot folder is not part of the portable in-app backup`() {
        assertFalse(
            com.example.llamadroid.data.db.DatabaseBackupManager.PORTABLE_MEDIA_ROOTS
                .contains(NativeChatKvCacheStore.DIR_NAME)
        )
    }

    @Test
    fun `every delete path reports the deleted stems`() {
        val deleted = mutableListOf<Set<String>>()
        val store = NativeChatKvCacheStore(File(tmp.root, NativeChatKvCacheStore.DIR_NAME), { deleted += it })
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

    private fun seed(store: NativeChatKvCacheStore, key: KvCacheKey, bytes: ByteArray, savedAt: Long) {
        store.directory.mkdirs()
        File(store.directory, store.slotFileName(key)).apply { writeBytes(bytes); setLastModified(savedAt) }
        store.writeMeta(key, KvCacheMeta("fp", 4096, 0, 10, savedAt, 1L))
    }

    @Test
    fun `legacy gzip sweep deletes orphan gz stems and leftovers next to a bin`() {
        val deleted = mutableListOf<Set<String>>()
        val store = NativeChatKvCacheStore(File(tmp.root, NativeChatKvCacheStore.DIR_NAME), { deleted += it })
        store.directory.mkdirs()
        fun file(name: String, size: Int = 4) = File(store.directory, name).writeBytes(ByteArray(size))
        // Orphan: only the gzipped copy and its sidecar remain.
        file("chat-1.bin.gz"); file("chat-1.json")
        // Orphan: only an interrupted gzip temp.
        file("chat-2.bin.gz.tmp")
        // Healthy cache with a stale gz copy and a temp next to it.
        seed(store, KvCacheKey.Chat(3), ByteArray(10), 1_000L)
        file("chat-3.bin.gz"); file("chat-3.gz.tmp")
        // Untouched healthy cache.
        seed(store, KvCacheKey.Chat(4), ByteArray(10), 1_000L)

        assertEquals(2, store.sweepLegacyCompression())

        assertEquals(setOf("chat-1", "chat-2"), deleted.single())
        assertEquals(setOf("chat-3.bin", "chat-3.json", "chat-4.bin", "chat-4.json"),
            store.directory.listFiles()!!.map { it.name }.toSet())
        assertEquals(0, store.sweepLegacyCompression())
    }
}
