package com.example.llamadroid.service

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
    val serverRowId: Long,
    /** Uncompressed size of the `.bin`; set when the file was gzipped to `.bin.gz`. */
    val originalBytes: Long = 0L,
    /** The first-MiB sample did not shrink enough; compression is not retried. */
    val incompressible: Boolean = false
)

/** Result of one idle-compression attempt. */
enum class KvCompressionOutcome { COMPRESSED, INCOMPRESSIBLE, SKIPPED }

/**
 * Disk layout for per-chat llama-server slot files under `files/llama_kv_slots/`:
 * `chat-<id>.bin` (written by llama-server) plus `chat-<id>.json` (written by the app).
 * Idle files are gzipped to `<stem>.bin.gz`; llama-server only reads `.bin`, so [unpack] runs
 * before any restore.
 */
class NativeChatKvCacheStore internal constructor(
    private val root: File,
    /** Called with the stems of every deletion (after the files are gone); resets base counters. */
    private val onStemsDeleted: (Set<String>) -> Unit = {},
    /** Called with (uncompressed bytes, elapsed ms) after every successful unpack. */
    private val onUnpacked: (Long, Long) -> Unit = { _, _ -> }
) {

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIR_NAME),
        { stems ->
            val bases = stems.filter { KvCacheKey.namespaceOfStem(it) == KvCacheNamespace.BASE }
            if (bases.isNotEmpty()) KvBaseUsageRegistry(context.applicationContext).clearStems(bases)
        },
        { bytes, ms -> KvUnpackSpeedStore(context.applicationContext).record(bytes, ms) }
    )

    /** Directory handed to llama-server through `--slot-save-path`. */
    val directory: File get() = root

    fun slotFileName(chatId: Long): String = slotFileName(chatId, "bin")

    fun slotFileName(key: KvCacheKey): String = "${key.fileStem}.bin"

    private fun slotFile(stem: String) = File(root, "$stem.bin")

    private fun gzFile(stem: String) = File(root, "$stem.bin.gz")

    private fun metaFile(stem: String) = File(root, "$stem.json")

    fun sizeBytes(chatId: Long): Long = sizeBytes(KvCacheKey.Chat(chatId))

    /** On-disk size of the cache: the `.bin`, else its compressed `.bin.gz`. */
    fun sizeBytes(key: KvCacheKey): Long =
        (slotFile(key.fileStem).takeIf { it.isFile } ?: gzFile(key.fileStem).takeIf { it.isFile })?.length() ?: 0L

    /** True when only the gzipped copy exists and [unpack] is needed before a restore. */
    fun isCompressed(key: KvCacheKey): Boolean = isCompressedStem(key.fileStem)

    private fun isCompressedStem(stem: String) = gzFile(stem).isFile && !slotFile(stem).isFile

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
        gzFile(stem).delete()
        metaFile(stem).delete()
        File(root, "$stem.json.tmp").delete()
        File(root, "$stem.bin.gz.tmp").delete()
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

    /** Bytes saved by gzipped caches: recorded original size minus the `.bin.gz` size. */
    fun compressionSavedBytes(): Long = stems().sumOf { stem ->
        if (!isCompressedStem(stem)) return@sumOf 0L
        val original = readMetaOfStem(stem)?.originalBytes ?: 0L
        (original - gzFile(stem).length()).coerceAtLeast(0L)
    }

    /** Marks a cache as just used so the idle compressor leaves it alone. */
    fun touch(key: KvCacheKey) {
        slotFile(key.fileStem).takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
    }

    /** Drops a gzipped copy that a fresh llama-server save made stale. */
    fun removeCompressedCopy(key: KvCacheKey) {
        gzFile(key.fileStem).delete()
    }

    /**
     * Stems with an uncompressed `.bin` unused for more than [idleMs] (latest of file mtime and
     * sidecar `savedAt`) that have not been marked incompressible. [protectedStems] are skipped.
     */
    fun idleCompressionCandidates(nowMs: Long, idleMs: Long, protectedStems: Set<String>): List<String> =
        stems().filter { stem ->
            if (stem in protectedStems || KvCacheKey.namespaceOfStem(stem) == null) return@filter false
            val bin = slotFile(stem).takeIf { it.isFile && it.length() > 0L } ?: return@filter false
            val meta = readMetaOfStem(stem) ?: return@filter false
            !meta.incompressible && nowMs - maxOf(bin.lastModified(), meta.savedAt) > idleMs
        }

    /**
     * Stems with an uncompressed `.bin` that the compression policy says are due. A stem is due
     * when idle for at least [KvCompressionPolicy.requiredIdleMs] (1 h for caches that unpack in
     * about 3 s or less, else 24 h), or always when [ignoreIdle] ("Compress now"). Incompressible
     * and [protectedStems] are skipped.
     */
    fun compressionCandidates(
        nowMs: Long,
        bytesPerMs: Double,
        protectedStems: Set<String>,
        ignoreIdle: Boolean = false
    ): List<String> = stems().filter { stem ->
        if (stem in protectedStems || KvCacheKey.namespaceOfStem(stem) == null) return@filter false
        val bin = slotFile(stem).takeIf { it.isFile && it.length() > 0L } ?: return@filter false
        val meta = readMetaOfStem(stem) ?: return@filter false
        if (meta.incompressible) return@filter false
        val idleMs = nowMs - maxOf(bin.lastModified(), meta.savedAt)
        KvCompressionPolicy.isDue(idleMs, bin.length(), bytesPerMs, ignoreIdle)
    }

    /** Uncompressed size of [stem]'s `.bin`, or 0 when there is none. */
    fun binBytes(stem: String): Long = slotFile(stem).takeIf { it.isFile }?.length() ?: 0L

    /**
     * Compresses one idle `.bin` to `.bin.gz` when a first-MiB sample deflates by at least
     * [MIN_SAMPLE_SAVING_PERCENT]; otherwise the sidecar is marked incompressible. The caller
     * must hold the per-key lock and have excluded in-use stems.
     */
    fun compressStem(stem: String): KvCompressionOutcome {
        val bin = slotFile(stem).takeIf { it.isFile } ?: return KvCompressionOutcome.SKIPPED
        val meta = readMetaOfStem(stem)?.takeIf { !it.incompressible } ?: return KvCompressionOutcome.SKIPPED
        val original = bin.length()
        if (original <= 0L) return KvCompressionOutcome.SKIPPED
        val (sampleIn, sampleOut) = deflateSample(bin) ?: return KvCompressionOutcome.SKIPPED
        if (!sampleSavesEnough(sampleIn, sampleOut)) {
            writeMetaOfStem(stem, meta.copy(incompressible = true))
            return KvCompressionOutcome.INCOMPRESSIBLE
        }
        if (!canSave(original)) return KvCompressionOutcome.SKIPPED
        val gz = gzFile(stem)
        val temp = File(root, "$stem.bin.gz.tmp")
        return try {
            bin.inputStream().buffered(IO_BUFFER).use { input ->
                FastGzipOutputStream(temp.outputStream().buffered(IO_BUFFER)).use { out ->
                    input.copyTo(out, IO_BUFFER)
                }
            }
            if (temp.length() <= 0L || temp.length() >= original) {
                temp.delete()
                writeMetaOfStem(stem, meta.copy(incompressible = true))
                return KvCompressionOutcome.INCOMPRESSIBLE
            }
            if (!temp.renameTo(gz)) {
                gz.delete()
                if (!temp.renameTo(gz)) {
                    temp.delete()
                    return KvCompressionOutcome.SKIPPED
                }
            }
            writeMetaOfStem(stem, meta.copy(originalBytes = original))
            bin.delete()
            KvCompressionOutcome.COMPRESSED
        } catch (_: IOException) {
            temp.delete()
            KvCompressionOutcome.SKIPPED
        }
    }

    /**
     * Restores the llama-server readable `.bin` from `.bin.gz` (temp file + atomic rename) and
     * stamps it as just used. Returns false when it could not be unpacked; a stale `.bin.gz`
     * next to a `.bin` is simply removed.
     */
    fun unpack(key: KvCacheKey): Boolean {
        val stem = key.fileStem
        val bin = slotFile(stem)
        val gz = gzFile(stem)
        if (bin.isFile) {
            gz.delete()
            return true
        }
        if (!gz.isFile) return false
        val expected = readMetaOfStem(stem)?.originalBytes?.takeIf { it > 0L } ?: (gz.length() * 4)
        if (!canSave(expected)) return false
        val temp = File(root, "$stem.bin.tmp")
        val startedAt = System.nanoTime()
        return try {
            GZIPInputStream(gz.inputStream().buffered(IO_BUFFER), IO_BUFFER).use { input ->
                temp.outputStream().buffered(IO_BUFFER).use { out -> input.copyTo(out, IO_BUFFER) }
            }
            if (!temp.renameTo(bin)) {
                temp.delete()
                return false
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val written = bin.length()
            bin.setLastModified(System.currentTimeMillis())
            gz.delete()
            runCatching { onUnpacked(written, elapsedMs) }
            true
        } catch (_: IOException) {
            temp.delete()
            false
        }
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

        private const val IO_BUFFER = 64 * 1024

        /** Idle compression test window: only the first MiB is deflated to decide. */
        internal const val SAMPLE_BYTES = 1024 * 1024

        /** A sample must shrink by at least this much for the whole file to be gzipped. */
        internal const val MIN_SAMPLE_SAVING_PERCENT = 10

        /** Pure decision: did the sample save at least [MIN_SAMPLE_SAVING_PERCENT]? */
        internal fun sampleSavesEnough(sampleBytes: Int, compressedBytes: Int): Boolean =
            sampleBytes > 0 && (sampleBytes - compressedBytes).toLong() * 100L >= sampleBytes.toLong() * MIN_SAMPLE_SAVING_PERCENT

        /** Deflate (level 1) the first [SAMPLE_BYTES] of [file]; returns (input, output) sizes. */
        internal fun deflateSample(file: File): Pair<Int, Int>? {
            val buffer = ByteArray(SAMPLE_BYTES)
            val read = try {
                file.inputStream().use { input ->
                    var total = 0
                    while (total < buffer.size) {
                        val n = input.read(buffer, total, buffer.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
            } catch (_: IOException) {
                return null
            }
            if (read <= 0) return null
            val deflater = Deflater(Deflater.BEST_SPEED)
            return try {
                deflater.setInput(buffer, 0, read)
                deflater.finish()
                val out = ByteArray(IO_BUFFER)
                var compressed = 0
                while (!deflater.finished()) compressed += deflater.deflate(out)
                read to compressed
            } finally {
                deflater.end()
            }
        }

        /** Gzip stream at the fastest deflate level; KV state is large and the pass is background work. */
        private class FastGzipOutputStream(out: java.io.OutputStream) : GZIPOutputStream(out, IO_BUFFER) {
            init {
                def.setLevel(Deflater.BEST_SPEED)
            }
        }

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
            .put("originalBytes", meta.originalBytes)
            .put("incompressible", meta.incompressible)
            .toString()

        internal fun parseMeta(json: String): KvCacheMeta {
            val obj = JSONObject(json)
            return KvCacheMeta(
                fingerprint = obj.getString("fingerprint"),
                slotNCtx = obj.optInt("slotNCtx"),
                bytes = obj.optLong("bytes"),
                tokens = obj.optInt("tokens"),
                savedAt = obj.optLong("savedAt"),
                serverRowId = obj.optLong("serverRowId"),
                originalBytes = obj.optLong("originalBytes"),
                incompressible = obj.optBoolean("incompressible")
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
