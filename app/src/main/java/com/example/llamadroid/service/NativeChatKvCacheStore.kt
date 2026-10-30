package com.example.llamadroid.service

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Facts that decide whether a saved llama-server slot file can be loaded back into the
 * running server. Everything here is configuration or file metadata; never chat content.
 */
data class KvCacheFingerprintInput(
    val modelPath: String,
    val modelSizeBytes: Long,
    val modelModifiedMs: Long,
    /** Per-slot context from `/props` (`default_generation_settings.n_ctx`). */
    val slotNCtx: Int,
    val kvTypeK: String,
    val kvTypeV: String,
    val mmprojPath: String?,
    val loras: List<String>,
    val binary: String,
    /** Hashed before it enters the fingerprint; the template text is never persisted. */
    val chatTemplate: String
)

/** Metadata sidecar written next to a slot file. Holds no prompt or message text. */
data class KvCacheMeta(
    val fingerprint: String,
    val slotNCtx: Int,
    val bytes: Long,
    val tokens: Int,
    val savedAt: Long,
    val serverRowId: Long
)

/**
 * Disk layout for per-chat llama-server slot files under `files/llama_kv_slots/`:
 * `chat-<id>.bin` (written by llama-server) plus `chat-<id>.json` (written by the app).
 */
class NativeChatKvCacheStore internal constructor(
    private val root: File,
    /** Called with the stems of every deletion (after the files are gone); resets base counters. */
    private val onStemsDeleted: (Set<String>) -> Unit = {}
) {

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIR_NAME),
        { stems ->
            val bases = stems.filter { KvCacheKey.namespaceOfStem(it) == KvCacheNamespace.BASE }
            if (bases.isNotEmpty()) KvBaseUsageRegistry(context.applicationContext).clearStems(bases)
        }
    )

    /** Directory handed to llama-server through `--slot-save-path`. */
    val directory: File get() = root

    fun slotFileName(chatId: Long): String = slotFileName(chatId, "bin")

    fun slotFileName(key: KvCacheKey): String = "${key.fileStem}.bin"

    private fun slotFile(stem: String) = File(root, "$stem.bin")

    private fun metaFile(stem: String) = File(root, "$stem.json")

    fun sizeBytes(chatId: Long): Long = sizeBytes(KvCacheKey.Chat(chatId))

    /** On-disk size of the cache's `.bin`. */
    fun sizeBytes(key: KvCacheKey): Long = slotFile(key.fileStem).takeIf { it.isFile }?.length() ?: 0L

    fun readMeta(chatId: Long): KvCacheMeta? = readMeta(KvCacheKey.Chat(chatId))

    fun readMeta(key: KvCacheKey): KvCacheMeta? = readMetaOfStem(key.fileStem)

    private fun readMetaOfStem(stem: String): KvCacheMeta? {
        val file = metaFile(stem).takeIf { it.isFile } ?: return null
        return runCatching { parseMeta(file.readText()) }.getOrNull()
    }

    fun writeMeta(chatId: Long, meta: KvCacheMeta) = writeMeta(KvCacheKey.Chat(chatId), meta)

    fun writeMeta(key: KvCacheKey, meta: KvCacheMeta) = writeMetaOfStem(key.fileStem, meta)

    private fun writeMetaOfStem(stem: String, meta: KvCacheMeta) {
        root.mkdirs()
        val target = metaFile(stem)
        val temp = File(root, target.name + ".tmp")
        temp.writeText(encodeMeta(meta))
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    fun delete(chatId: Long) = delete(KvCacheKey.Chat(chatId))

    fun delete(key: KvCacheKey) = deleteStem(key.fileStem)

    private fun deleteStem(stem: String) {
        deleteStemFiles(stem)
        notifyDeleted(setOf(stem))
    }

    private fun deleteStemFiles(stem: String) {
        slotFile(stem).delete()
        File(root, "$stem.bin.gz").delete()
        metaFile(stem).delete()
        File(root, "$stem.json.tmp").delete()
        File(root, "$stem.bin.gz.tmp").delete()
        File(root, "$stem.gz.tmp").delete()
        File(root, "$stem.bin.tmp").delete()
    }

    /** Single hook for every delete path: base counters reset and in-memory holders drop the stems. */
    private fun notifyDeleted(stems: Set<String>) {
        if (stems.isEmpty()) return
        runCatching { onStemsDeleted(stems) }
        deletionListeners.forEach { listener -> runCatching { listener(stems) } }
    }

    /** Stems that currently have any file in the cache folder (used to prune stale counters). */
    fun existingStems(): Set<String> = stems()

    /** Deletes every cache of one namespace (chat, delegate or harness). */
    fun deleteNamespace(namespace: KvCacheNamespace) {
        deleteStems(stems().filter { KvCacheKey.namespaceOfStem(it) == namespace })
    }

    private fun deleteStems(targets: Collection<String>) {
        targets.forEach(::deleteStemFiles)
        notifyDeleted(targets.toSet())
    }

    /** Deletes every harness cache that belongs to [runtimeId]. */
    fun deleteHarnessRuntime(runtimeId: String) {
        val prefix = KvCacheKey.harnessRuntimeStemPrefix(runtimeId)
        deleteStems(stems().filter { it.startsWith(prefix) })
    }

    /** Bytes used by one harness runtime's caches, including sidecars. */
    fun harnessRuntimeBytes(runtimeId: String): Long {
        val prefix = KvCacheKey.harnessRuntimeStemPrefix(runtimeId)
        return stemBytes().filterKeys { it.startsWith(prefix) }.values.sum()
    }

    /** Bytes per namespace; every namespace is present, zero when empty. */
    fun breakdown(): Map<KvCacheNamespace, Long> {
        val result = KvCacheNamespace.values().associateWithTo(linkedMapOf()) { 0L }
        stemBytes().forEach { (stem, bytes) ->
            KvCacheKey.namespaceOfStem(stem)?.let { result[it] = (result[it] ?: 0L) + bytes }
        }
        return result
    }

    /** Marks a cache as just used (sets its modification time). */
    fun touch(key: KvCacheKey) {
        slotFile(key.fileStem).takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
    }

    /**
     * Cleans up the removed gzip compression: deletes every stem that has a `.bin.gz` or `.gz.tmp`
     * but no `.bin` (unrestorable) through the normal delete path, and removes leftover
     * `.gz`/`.gz.tmp` files next to a `.bin`. Returns the number of stems deleted.
     */
    fun sweepLegacyCompression(): Int {
        val files = root.listFiles()?.filter { it.isFile }.orEmpty()
        fun isGz(name: String) = name.endsWith(".gz") || name.endsWith(".gz.tmp")
        val gzStems = files.filter { isGz(it.name) }.map { it.name.substringBefore('.') }.toSet()
        val orphans = gzStems.filter { !slotFile(it).isFile }
        if (orphans.isNotEmpty()) deleteStems(orphans)
        files.filter { isGz(it.name) && it.exists() }.forEach { it.delete() }
        return orphans.size
    }

    /** Bytes used by every file in the cache folder, including sidecars. */
    fun totalBytes(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun clearAll() {
        val deleted = stems()
        root.listFiles()?.forEach { if (it.isFile) it.delete() }
        notifyDeleted(deleted)
    }

    /**
     * False when saving [expectedBytes] more would leave less than [MIN_FREE_BYTES] free.
     * Deliberately uses `usableSpace` rather than `StorageManager.getAllocatableBytes`: an optional
     * KV cache must never make the system clear other apps' cached data to make room.
     */
    @SuppressLint("UsableSpace")
    fun canSave(expectedBytes: Long): Boolean {
        val probe = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() } ?: return false
        return hasRoomToSave(probe.usableSpace, expectedBytes)
    }

    /**
     * Deletes the least recently saved caches (sidecar `savedAt`, else file mtime) until the
     * folder fits [budgetBytes]. Stems in [protectedStems] are never deleted. [namespaces]
     * limits both the counted size and the candidates. Returns the freed bytes.
     */
    fun enforceBudget(
        budgetBytes: Long,
        protectedStems: Set<String>,
        namespaces: Set<KvCacheNamespace>? = null
    ): Long {
        val entries = stemBytes().mapNotNull { (stem, bytes) ->
            val namespace = KvCacheKey.namespaceOfStem(stem) ?: return@mapNotNull null
            if (namespaces != null && namespace !in namespaces) return@mapNotNull null
            val savedAt = readMetaOfStem(stem)?.savedAt?.takeIf { it > 0 }
                ?: slotFile(stem).takeIf { it.isFile }?.lastModified()
                ?: metaFile(stem).lastModified()
            Triple(stem, bytes, savedAt)
        }
        var total = entries.sumOf { it.second }
        var freed = 0L
        val evicted = mutableListOf<String>()
        for ((stem, bytes, _) in entries.sortedBy { it.third }) {
            if (total <= budgetBytes) break
            if (stem in protectedStems) continue
            deleteStemFiles(stem)
            evicted += stem
            total -= bytes
            freed += bytes
        }
        notifyDeleted(evicted.toSet())
        return freed
    }

    private fun stems(): Set<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name.substringBefore('.') }?.toSet().orEmpty()

    private fun stemBytes(): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        root.listFiles()?.filter { it.isFile }?.forEach {
            val stem = it.name.substringBefore('.')
            result[stem] = (result[stem] ?: 0L) + it.length()
        }
        return result
    }

    /** Chat ids that currently have a slot file or a sidecar on disk. */
    fun listChats(): List<Long> =
        root.listFiles()
            ?.mapNotNull { parseChatId(it.name) }
            ?.distinct()
            ?.sorted()
            .orEmpty()

    companion object {
        const val DIR_NAME = "llama_kv_slots"

        /** Process-wide listeners told which stems were deleted (the coordinator drops its holders). */
        private val deletionListeners = java.util.concurrent.CopyOnWriteArrayList<(Set<String>) -> Unit>()

        private const val SWEEP_PREFS = "kv_cache_legacy_sweep"
        private const val SWEEP_KEY = "gzip_sweep_done"
        private const val LEGACY_SPEED_PREFS = "kv_cache_unpack_speed"
        private val sweepStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /** One-time (per install) cleanup of the removed gzip compression; see [sweepLegacyCompression]. */
        fun runLegacySweepOnce(context: Context) {
            if (!sweepStarted.compareAndSet(false, true)) return
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(SWEEP_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(SWEEP_KEY, false)) return
            runCatching {
                NativeChatKvCacheStore(app).sweepLegacyCompression()
                app.deleteSharedPreferences(LEGACY_SPEED_PREFS)
                prefs.edit { putBoolean(SWEEP_KEY, true) }
            }
        }

        internal fun addDeletionListener(listener: (Set<String>) -> Unit) {
            deletionListeners.add(listener)
        }
        const val SLOT_SAVE_PATH_FLAG = "--slot-save-path"

        /** Saving is skipped when it would leave less free space than this. */
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        internal fun hasRoomToSave(usableBytes: Long, expectedBytes: Long): Boolean =
            usableBytes >= MIN_FREE_BYTES + expectedBytes.coerceAtLeast(0L)

        /** Stable, filesystem-safe stem: negative ids cannot produce a `-` collision. */
        fun fileStem(chatId: Long): String =
            if (chatId >= 0) "chat-$chatId" else "chat-n${-(chatId + 1)}"

        fun slotFileName(chatId: Long, extension: String): String = "${fileStem(chatId)}.$extension"

        internal fun parseChatId(fileName: String): Long? {
            val match = FILE_PATTERN.matchEntire(fileName) ?: return null
            val value = match.groupValues[2].toLongOrNull() ?: return null
            return if (match.groupValues[1].isEmpty()) value else -(value + 1)
        }

        private val FILE_PATTERN = Regex("""chat-(n?)(\d+)\.(?:bin(?:\.gz)?|json)(?:\.tmp)?""")

        /**
         * Mirrors llama.cpp's `fs_validate_filename` closely enough to guarantee the server
         * accepts the generated names: no separators, wildcards, control characters or dots-only.
         */
        fun isValidSlotFilename(name: String): Boolean {
            if (name.isEmpty() || name.length > 255) return false
            if (name == "." || name == "..") return false
            return name.none { c ->
                c.code <= 0x1F || c.code == 0x7F || c.code in 0x80..0x9F ||
                    c in "/\\:*?\"<>|" || c == '\uFFFD' || c == '\uFEFF'
            }
        }

        /** SHA-256 over every field that can invalidate a saved KV state. */
        fun fingerprint(input: KvCacheFingerprintInput): String {
            val text = buildString {
                append("v1")
                append('\u001f').append(input.modelPath)
                append('\u001f').append(input.modelSizeBytes)
                append('\u001f').append(input.modelModifiedMs)
                append('\u001f').append(input.slotNCtx)
                append('\u001f').append(input.kvTypeK)
                append('\u001f').append(input.kvTypeV)
                append('\u001f').append(input.mmprojPath.orEmpty())
                append('\u001f').append(input.loras.joinToString("\u001e"))
                append('\u001f').append(input.binary)
                append('\u001f').append(sha256Hex(input.chatTemplate))
            }
            return sha256Hex(text)
        }

        /** Builds the input from the persisted launch profile plus facts read from `/props`. */
        fun fingerprintInput(
            profile: LlamaServerLaunchProfile?,
            propsModelPath: String?,
            slotNCtx: Int,
            chatTemplate: String
        ): KvCacheFingerprintInput {
            val modelPath = propsModelPath?.takeIf { it.isNotBlank() } ?: profile?.modelPath.orEmpty()
            val modelFile = File(modelPath).takeIf { modelPath.isNotBlank() }
            val loras = profile?.loras?.map { "${it.path}@${it.strength}" }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(profile?.loraPath?.takeIf { it.isNotBlank() })
            return KvCacheFingerprintInput(
                modelPath = modelPath,
                modelSizeBytes = modelFile?.takeIf { it.isFile }?.length() ?: -1L,
                modelModifiedMs = modelFile?.takeIf { it.isFile }?.lastModified() ?: -1L,
                slotNCtx = slotNCtx,
                kvTypeK = profile?.kvCacheTypeK.orEmpty(),
                kvTypeV = profile?.kvCacheTypeV.orEmpty(),
                mmprojPath = profile?.mmprojPath?.takeIf { it.isNotBlank() },
                loras = loras,
                binary = profile?.nativeBinarySelection.orEmpty(),
                chatTemplate = chatTemplate
            )
        }

        internal fun encodeMeta(meta: KvCacheMeta): String = JSONObject()
            .put("fingerprint", meta.fingerprint)
            .put("slotNCtx", meta.slotNCtx)
            .put("bytes", meta.bytes)
            .put("tokens", meta.tokens)
            .put("savedAt", meta.savedAt)
            .put("serverRowId", meta.serverRowId)
            .toString()

        internal fun parseMeta(json: String): KvCacheMeta {
            val obj = JSONObject(json)
            return KvCacheMeta(
                fingerprint = obj.getString("fingerprint"),
                slotNCtx = obj.optInt("slotNCtx"),
                bytes = obj.optLong("bytes"),
                tokens = obj.optInt("tokens"),
                savedAt = obj.optLong("savedAt"),
                serverRowId = obj.optLong("serverRowId")
            )
        }

        /**
         * Appends `--slot-save-path <filesDir>/llama_kv_slots` to a llama-server command unless
         * the command already carries the flag (custom flags or template) or targets a
         * different native binary. Creates the directory because llama-server refuses to start
         * when the path is not an existing directory.
         */
        fun withSlotSavePath(args: List<String>, filesDir: File): List<String> {
            if (args.isEmpty()) return args
            val binaryName = args.first().substringAfterLast('/').lowercase()
            if (NON_SERVER_BINARY_MARKERS.any { it in binaryName }) return args
            if (args.any { it == SLOT_SAVE_PATH_FLAG || it.startsWith("$SLOT_SAVE_PATH_FLAG=") }) return args
            val dir = File(filesDir, DIR_NAME)
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return args
            return args + listOf(SLOT_SAVE_PATH_FLAG, dir.absolutePath)
        }

        private val NON_SERVER_BINARY_MARKERS = listOf("rpc", "bench", "tts", "whisper", "stable", "sd-cli")
    }
}
