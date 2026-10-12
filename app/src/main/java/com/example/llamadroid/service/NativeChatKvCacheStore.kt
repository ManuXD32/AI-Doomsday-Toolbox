package com.example.llamadroid.service

import android.content.Context
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
class NativeChatKvCacheStore internal constructor(private val root: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIR_NAME))

    /** Directory handed to llama-server through `--slot-save-path`. */
    val directory: File get() = root

    fun slotFileName(chatId: Long): String = slotFileName(chatId, "bin")

    private fun slotFile(chatId: Long) = File(root, slotFileName(chatId, "bin"))

    private fun metaFile(chatId: Long) = File(root, slotFileName(chatId, "json"))

    fun sizeBytes(chatId: Long): Long = slotFile(chatId).takeIf { it.isFile }?.length() ?: 0L

    fun readMeta(chatId: Long): KvCacheMeta? {
        val file = metaFile(chatId).takeIf { it.isFile } ?: return null
        return runCatching { parseMeta(file.readText()) }.getOrNull()
    }

    fun writeMeta(chatId: Long, meta: KvCacheMeta) {
        root.mkdirs()
        val target = metaFile(chatId)
        val temp = File(root, target.name + ".tmp")
        temp.writeText(encodeMeta(meta))
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    fun delete(chatId: Long) {
        slotFile(chatId).delete()
        metaFile(chatId).delete()
        File(root, slotFileName(chatId, "json") + ".tmp").delete()
    }

    /** Bytes used by every file in the cache folder, including sidecars. */
    fun totalBytes(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun clearAll() {
        root.listFiles()?.forEach { if (it.isFile) it.delete() }
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
        const val SLOT_SAVE_PATH_FLAG = "--slot-save-path"

        /** Stable, filesystem-safe stem: negative ids cannot produce a `-` collision. */
        fun fileStem(chatId: Long): String =
            if (chatId >= 0) "chat-$chatId" else "chat-n${-(chatId + 1)}"

        fun slotFileName(chatId: Long, extension: String): String = "${fileStem(chatId)}.$extension"

        internal fun parseChatId(fileName: String): Long? {
            val match = FILE_PATTERN.matchEntire(fileName) ?: return null
            val value = match.groupValues[2].toLongOrNull() ?: return null
            return if (match.groupValues[1].isEmpty()) value else -(value + 1)
        }

        private val FILE_PATTERN = Regex("""chat-(n?)(\d+)\.(?:bin|json)(?:\.tmp)?""")

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
