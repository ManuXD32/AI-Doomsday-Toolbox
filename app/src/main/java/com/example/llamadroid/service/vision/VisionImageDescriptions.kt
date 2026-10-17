package com.example.llamadroid.service.vision

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Outcome of describing one image with the vision helper. */
sealed class VisionImageDescription {
    data class Described(val text: String) : VisionImageDescription()
    /** [reason] is a short English, model-facing reason (no helper set up, not enough RAM, failure...). */
    data class Unavailable(val reason: String) : VisionImageDescription()
}

/** Pure, file-only parts of the description cache (unit tested with a temp directory). */
object VisionDescriptionCache {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** First 12 hex characters, used as the image id in model-facing notes. */
    fun shortId(sha: String): String = sha.take(12)

    fun file(dir: File, sha: String): File = File(dir, "$sha.txt")

    fun read(dir: File, sha: String): String? =
        runCatching { file(dir, sha).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    /** Writes through a temp file and a rename so a reader never sees a half-written description. */
    fun writeAtomic(dir: File, sha: String, text: String): Boolean = runCatching {
        dir.mkdirs()
        val target = file(dir, sha)
        val temp = File(dir, "$sha.txt.tmp")
        temp.writeText(text.trim())
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target))
        }
        true
    }.getOrDefault(false)
}

/** In-memory memo of recent failures, so a failing image is not retried on every turn. */
class VisionFailureMemo(private val ttlMs: Long = DEFAULT_TTL_MS, private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private data class Entry(val reason: String, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(sha: String): String? {
        val entry = entries[sha] ?: return null
        if (clock() >= entry.expiresAt) { entries.remove(sha); return null }
        return entry.reason
    }

    fun put(sha: String, reason: String) {
        if (entries.size > MAX_ENTRIES) entries.clear()
        entries[sha] = Entry(reason, clock() + ttlMs)
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        private const val MAX_ENTRIES = 256
    }
}

/**
 * Describes arbitrary images (not tied to a chat message) with the vision helper, caching the text by
 * the image's SHA-256 in app-private files so the same image always gets the same text (stable
 * prompts for KV prefix reuse) and is described only once. Used by the DeepSeek harness bridge for
 * non-vision models and by the chat attachment reader. Only hashes and reasons are ever logged.
 */
object VisionImageDescriptions {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val CACHE_DIR = "vision_descriptions"

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failures = VisionFailureMemo()

    suspend fun describe(context: Context, bytes: ByteArray, mime: String): VisionImageDescription {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
            return VisionImageDescription.Unavailable("the image is empty or larger than 16 MiB")
        }
        val dir = File(context.applicationContext.filesDir, CACHE_DIR)
        val sha = withContext(Dispatchers.Default) { VisionDescriptionCache.sha256Hex(bytes) }
        VisionDescriptionCache.read(dir, sha)?.let { return VisionImageDescription.Described(it) }
        failures.get(sha)?.let { return VisionImageDescription.Unavailable(it) }
        return locks.computeIfAbsent(sha) { Mutex() }.withLock {
            // A concurrent caller may have finished while this one waited.
            VisionDescriptionCache.read(dir, sha)?.let { return@withLock VisionImageDescription.Described(it) }
            failures.get(sha)?.let { return@withLock VisionImageDescription.Unavailable(it) }
            val safeMime = mime.takeIf { it.startsWith("image/") && it.all { c -> c.isLetterOrDigit() || c in "/+.-" } } ?: "image/jpeg"
            val dataUrl = withContext(Dispatchers.Default) {
                "data:$safeMime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
            }
            when (val outcome = VisionDescriptionService.describeDataUrls(context, listOf(dataUrl))) {
                is VisionDescriptionService.DataUrlOutcome.Unavailable -> {
                    failures.put(sha, outcome.reason)
                    VisionImageDescription.Unavailable(outcome.reason)
                }
                is VisionDescriptionService.DataUrlOutcome.Done -> {
                    val text = outcome.texts.firstOrNull()
                    if (text == null) {
                        failures.put(sha, VisionDescriptionService.REASON_FAILED)
                        VisionImageDescription.Unavailable(VisionDescriptionService.REASON_FAILED)
                    } else {
                        withContext(Dispatchers.IO) { VisionDescriptionCache.writeAtomic(dir, sha, text) }
                        VisionImageDescription.Described(text.trim())
                    }
                }
            }
        }
    }
}
