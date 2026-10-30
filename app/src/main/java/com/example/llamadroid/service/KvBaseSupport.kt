package com.example.llamadroid.service

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** State of a saved cache file as seen by the coordinator. */
internal enum class KvFileStatus { VALID, NO_FILE, FINGERPRINT_MISMATCH }

/** Why a base was not restored; shown in "Prepare caches" as the last miss. */
enum class KvBaseMissReason {
    NO_FILE, FINGERPRINT_MISMATCH, PREFIX_CHANGED, RESTORE_FAILED
}

/** Why "Prepare" or a background base build did not produce a base. */
enum class KvBaseFailure {
    UNSUPPORTED_ENGINE,
    NOT_LOCAL,
    SERVER_UNREACHABLE,
    SLOT_SAVE_DISABLED,
    NO_FREE_SLOT,
    NO_PREFIX,
    BUILD_IN_PROGRESS,
    REQUEST_FAILED,
    LOW_DISK,
    SAVE_FAILED
}

/** Outcome of [NativeChatKvCacheCoordinator.prepareBase]. */
sealed class KvBasePrepareResult {
    /** The base file exists and is valid; [reused] is true when an existing valid base was kept. */
    data class Ready(val bytes: Long, val reused: Boolean) : KvBasePrepareResult()
    data class Failed(val reason: KvBaseFailure) : KvBasePrepareResult()
}

/** Result of one explicit slot save. */
internal enum class KvSaveResult { OK, LOW_DISK, FAILED }

/**
 * Pure pre-flight check for a base build. Returns the failure to report, or null when the build
 * may proceed. [slotSupport] is the cached `--slot-save-path` probe (null = server did not answer).
 */
internal fun classifyBasePrepareStart(
    isLlamaServerEngine: Boolean,
    isLoopback: Boolean,
    slotSupport: Boolean?,
    hasSlots: Boolean
): KvBaseFailure? = when {
    !isLlamaServerEngine -> KvBaseFailure.UNSUPPORTED_ENGINE
    !isLoopback -> KvBaseFailure.NOT_LOCAL
    slotSupport == null -> KvBaseFailure.SERVER_UNREACHABLE
    slotSupport == false -> KvBaseFailure.SLOT_SAVE_DISABLED
    !hasSlots -> KvBaseFailure.NO_FREE_SLOT
    else -> null
}

/**
 * Background rebuild decision. A base is built only when the hint carries a builder, asks for it,
 * the file is missing or invalid, no build of it is running, and the last attempt is older than
 * [cooldownMs]. A valid base is never rebuilt (so it is never overwritten).
 */
internal fun shouldBuildBase(
    capture: Boolean,
    hasBuilder: Boolean,
    fileStatus: KvFileStatus,
    buildInFlight: Boolean,
    msSinceLastAttempt: Long?,
    cooldownMs: Long = BASE_BUILD_COOLDOWN_MS
): Boolean = capture && hasBuilder && fileStatus != KvFileStatus.VALID && !buildInFlight &&
    (msSinceLastAttempt == null || msSinceLastAttempt >= cooldownMs)

internal const val BASE_BUILD_COOLDOWN_MS = 5L * 60 * 1000

/** What a base-hinted request does after it ran. */
internal enum class KvBaseAction { NONE, LEGACY_CAPTURE, BUILD }

/** Miss reason for a missing/invalid base file; [previousStemDiffers] means the prefix changed. */
internal fun missReasonFor(status: KvFileStatus, previousStemDiffers: Boolean): KvBaseMissReason? = when (status) {
    KvFileStatus.VALID -> null
    KvFileStatus.FINGERPRINT_MISMATCH -> KvBaseMissReason.FINGERPRINT_MISMATCH
    KvFileStatus.NO_FILE ->
        if (previousStemDiffers) KvBaseMissReason.PREFIX_CHANGED else KvBaseMissReason.NO_FILE
}

/** Metadata-only usage of one base cache (never prompt text). */
data class KvBaseUsage(
    val uses: Int = 0,
    val lastUsedAt: Long = 0L,
    val misses: Int = 0,
    val lastMissReason: KvBaseMissReason? = null,
    val lastMissAt: Long = 0L,
    val builtAt: Long = 0L
) {
    fun withUse(now: Long) = copy(uses = uses + 1, lastUsedAt = now)
    fun withMiss(reason: KvBaseMissReason, now: Long) =
        copy(misses = misses + 1, lastMissReason = reason, lastMissAt = now)
    fun withBuilt(now: Long) = copy(builtAt = now)

    fun encode(): String = JSONObject()
        .put("uses", uses).put("lastUsedAt", lastUsedAt).put("misses", misses)
        .put("lastMiss", lastMissReason?.name ?: "").put("lastMissAt", lastMissAt)
        .put("builtAt", builtAt).toString()

    companion object {
        fun parse(json: String?): KvBaseUsage {
            if (json.isNullOrBlank()) return KvBaseUsage()
            return runCatching {
                val o = JSONObject(json)
                KvBaseUsage(
                    uses = o.optInt("uses"), lastUsedAt = o.optLong("lastUsedAt"),
                    misses = o.optInt("misses"),
                    lastMissReason = runCatching { KvBaseMissReason.valueOf(o.optString("lastMiss")) }.getOrNull(),
                    lastMissAt = o.optLong("lastMissAt"), builtAt = o.optLong("builtAt")
                )
            }.getOrDefault(KvBaseUsage())
        }
    }
}

/** Sums usages of several bases; the latest miss reason wins. */
internal fun combineBaseUsage(usages: List<KvBaseUsage>): KvBaseUsage {
    val lastMiss = usages.filter { it.lastMissReason != null }.maxByOrNull { it.lastMissAt }
    return KvBaseUsage(
        uses = usages.sumOf { it.uses },
        lastUsedAt = usages.maxOfOrNull { it.lastUsedAt } ?: 0L,
        misses = usages.sumOf { it.misses },
        lastMissReason = lastMiss?.lastMissReason,
        lastMissAt = lastMiss?.lastMissAt ?: 0L,
        builtAt = usages.maxOfOrNull { it.builtAt } ?: 0L
    )
}

/**
 * Registry keys (`usage:<stem>` and `last:<identity>` -> stem) that point at a base stem with no
 * file on disk. Pure so it can be unit-tested.
 */
internal fun staleRegistryKeys(entries: Map<String, *>, existingStems: Set<String>): List<String> =
    entries.mapNotNull { (key, value) ->
        // Only counters of bases that existed (built or restored) and whose file is now gone. Entries
        // that only record misses of a never-built base, and the `last:` identity entries used to tell
        // a changed prefix from a first use, stay.
        val existed = (value as? String)?.let { KvBaseUsage.parse(it) }?.let { it.uses > 0 || it.builtAt > 0L } == true
        if (key.startsWith("usage:") && key.removePrefix("usage:") !in existingStems && existed) key else null
    }

/**
 * Small SharedPreferences-backed registry of base-cache decisions, so the "Prepare caches" sheet
 * can prove reuse ("used N times, last miss: ..."). Stores counters and enum names only.
 */
class KvBaseUsageRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: KvCacheKey.Base): KvBaseUsage = KvBaseUsage.parse(prefs.getString(USAGE + key.fileStem, null))

    /**
     * Combined usage of every chat base on record. With [existingStems] (the stems that have files
     * on disk) entries of deleted bases are pruned first so the totals match the files.
     */
    fun chatTotal(existingStems: Set<String>? = null): KvBaseUsage {
        if (existingStems != null) pruneMissing(existingStems)
        return chatTotalOfAll()
    }

    private fun chatTotalOfAll(): KvBaseUsage = combineBaseUsage(
        prefs.all.filterKeys { it.startsWith(USAGE + "base-chat-") }.values.map { KvBaseUsage.parse(it as? String) }
    )

    fun recordUse(key: KvCacheKey.Base, now: Long = System.currentTimeMillis()) = update(key) { it.withUse(now) }

    fun recordMiss(key: KvCacheKey.Base, reason: KvBaseMissReason, now: Long = System.currentTimeMillis()) =
        update(key) { it.withMiss(reason, now) }

    fun recordBuilt(key: KvCacheKey.Base, now: Long = System.currentTimeMillis()) = update(key) { it.withBuilt(now) }

    fun clear(key: KvCacheKey.Base) = clearStems(listOf(key.fileStem))

    /** Drops the counters of deleted base [stems] and the `last:` identity entries that point at them. */
    fun clearStems(stems: Collection<String>) {
        if (stems.isEmpty()) return
        val set = stems.toSet()
        synchronized(LOCK) {
            val identities = prefs.all.filter { (k, v) -> k.startsWith(LAST) && (v as? String) in set }.keys
            prefs.edit {
                set.forEach { remove(USAGE + it) }
                identities.forEach { remove(it) }
            }
        }
    }

    /** Removes counters and identities of bases that no longer have files ([existingStems]). */
    fun pruneMissing(existingStems: Set<String>) {
        synchronized(LOCK) {
            val stale = staleRegistryKeys(prefs.all, existingStems)
            if (stale.isNotEmpty()) prefs.edit { stale.forEach { remove(it) } }
        }
    }

    /**
     * Remembers the base [key] last used for this server+model identity and returns the previous
     * stem, so a miss caused by a changed prefix can be told apart from a first use.
     */
    fun noteRequest(key: KvCacheKey.Base): String? {
        val identity = LAST + key.prefixHash.substringBeforeLast('|', "").hashCode()
        val previous = prefs.getString(identity, null)
        if (previous != key.fileStem) prefs.edit { putString(identity, key.fileStem) }
        return previous
    }

    private fun update(key: KvCacheKey.Base, change: (KvBaseUsage) -> KvBaseUsage) {
        synchronized(LOCK) {
            val current = KvBaseUsage.parse(prefs.getString(USAGE + key.fileStem, null))
            prefs.edit { putString(USAGE + key.fileStem, change(current).encode()) }
        }
    }

    private companion object {
        const val PREFS = "kv_base_usage"
        const val USAGE = "usage:"
        const val LAST = "last:"
        val LOCK = Any()
    }
}
