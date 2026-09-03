package com.example.llamadroid.data.model

import kotlin.math.roundToLong

/**
 * A single file's byte observations used to calculate a curated-bundle
 * progress snapshot.
 *
 * [declaredBytes] is the catalog value and is therefore the denominator for
 * every observation. The remaining fields intentionally accept observations
 * from different layers: Room task state, the filesystem `.part` artifact,
 * and the live progress flow. The calculator combines them without depending
 * on Android, Room, Compose, or a particular downloader implementation.
 */
data class BundleProgressEntry(
    val key: String,
    val declaredBytes: Long,
    val installed: Boolean = false,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val active: Boolean = false,
    val persistedTaskBytes: Long? = null,
    val partBytes: Long? = null,
    val liveFraction: Float? = null
) {
    init {
        require(declaredBytes >= 0L) { "declaredBytes must not be negative" }
    }

    /** Compatibility/readability alias for callers that call this task state. */
    val taskBytes: Long?
        get() = persistedTaskBytes

    /** Compatibility/readability alias for callers that expose a part length. */
    val partLength: Long?
        get() = partBytes

    /** Compatibility/readability alias for callers that expose live progress. */
    val liveProgress: Float?
        get() = liveFraction
}

/**
 * Byte-weighted progress for one curated bundle.
 *
 * [bytesByFile] is retained so a later snapshot can enforce monotonicity per
 * file while still allowing a cancelled file to return to its persisted
 * partial state. It is part of the snapshot rather than hidden mutable state,
 * keeping [calculateBundleProgressSnapshot] pure and straightforward to test.
 */
data class BundleProgressSnapshot(
    val totalBytes: Long,
    val downloadedBytes: Long,
    val remainingBytes: Long,
    val progress: Float,
    val completedFileCount: Int,
    val fileCount: Int,
    val hasActiveDownloads: Boolean,
    val bytesByFile: Map<String, Long>,
    val hasIndeterminateLiveTask: Boolean = false
) {
    /** Alternate name used by byte-oriented callers. */
    val completedBytes: Long
        get() = downloadedBytes

    val percentage: Int
        get() = (progress * 100f).roundToLong().toInt().coerceIn(0, 100)

    val isComplete: Boolean
        get() = fileCount > 0 && completedFileCount == fileCount

    val fraction: Float
        get() = progress

    val remaining: Long
        get() = remainingBytes

    val isIndeterminate: Boolean
        get() = hasIndeterminateLiveTask

    val isDeterminate: Boolean
        get() = !hasIndeterminateLiveTask
}

/** Stateless calculator for byte-weighted curated-bundle progress. */
object BundleProgressSnapshotCalculator {
    /**
     * Calculates a snapshot from the best available observation for every
     * file. A non-cancelled file never moves backwards within a session when
     * [previousSnapshot] is supplied. Set [resetToPersisted] after cancelling
     * a whole bundle to discard the live values and start from persisted bytes.
     */
    fun calculate(
        entries: List<BundleProgressEntry>,
        previousSnapshot: BundleProgressSnapshot? = null,
        resetToPersisted: Boolean = false
    ): BundleProgressSnapshot {
        val bytesByFile = linkedMapOf<String, Long>()
        var totalBytes = 0L
        var downloadedBytes = 0L
        var completedFileCount = 0
        var hasActiveDownloads = false
        var hasIndeterminateLiveTask = false

        entries.forEach { entry ->
            totalBytes = saturatingAdd(totalBytes, entry.declaredBytes)
            val observedBytes = observedBytes(
                entry,
                includeLive = !resetToPersisted && !entry.cancelled
            )
            val resetEntry = resetToPersisted || entry.cancelled
            val priorBytes = previousSnapshot
                ?.bytesByFile
                ?.get(entry.key)
                ?.takeIf { it in 0L..entry.declaredBytes }
            val resolvedBytes = if (resetEntry) {
                observedBytes
            } else {
                maxOf(observedBytes, priorBytes ?: 0L)
            }.coerceIn(0L, entry.declaredBytes)

            // Catalog keys are unique, but keeping the last value here makes
            // this pure projection resilient to malformed caller input.
            bytesByFile[entry.key] = resolvedBytes
            downloadedBytes = saturatingAdd(downloadedBytes, resolvedBytes)

            val fileComplete = entry.installed || entry.completed ||
                resolvedBytes >= entry.declaredBytes
            if (fileComplete) completedFileCount += 1

            val active = entry.active && !entry.cancelled && !fileComplete
            hasActiveDownloads = hasActiveDownloads || active
            if (active && !hasUsableLiveObservation(entry)) {
                hasIndeterminateLiveTask = true
            }
        }

        val boundedDownloadedBytes = downloadedBytes.coerceIn(0L, totalBytes)
        val remainingBytes = (totalBytes - boundedDownloadedBytes).coerceAtLeast(0L)
        val progress = if (totalBytes > 0L) {
            (boundedDownloadedBytes.toDouble() / totalBytes.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        } else {
            0f
        }

        return BundleProgressSnapshot(
            totalBytes = totalBytes,
            downloadedBytes = boundedDownloadedBytes,
            remainingBytes = remainingBytes,
            progress = progress,
            completedFileCount = completedFileCount,
            fileCount = entries.size,
            hasActiveDownloads = hasActiveDownloads,
            bytesByFile = bytesByFile,
            hasIndeterminateLiveTask = hasIndeterminateLiveTask
        )
    }

    private fun observedBytes(
        entry: BundleProgressEntry,
        includeLive: Boolean
    ): Long {
        if (entry.installed || entry.completed) return entry.declaredBytes

        val candidates = buildList {
            validBytes(entry.persistedTaskBytes, entry.declaredBytes)?.let(::add)
            validBytes(entry.partBytes, entry.declaredBytes)?.let(::add)
            if (includeLive) {
                liveBytes(entry.liveFraction, entry.declaredBytes)?.let(::add)
            }
        }
        return candidates.maxOrNull() ?: 0L
    }

    private fun hasUsableLiveObservation(entry: BundleProgressEntry): Boolean =
        liveBytes(entry.liveFraction, entry.declaredBytes) != null

    private fun validBytes(value: Long?, declaredBytes: Long): Long? =
        value?.takeIf { it in 0L..declaredBytes }

    private fun liveBytes(value: Float?, declaredBytes: Long): Long? {
        val fraction = value ?: return null
        if (!fraction.isFinite() || fraction !in 0f..1f) return null
        return (declaredBytes.toDouble() * fraction.toDouble())
            .roundToLong()
            .coerceIn(0L, declaredBytes)
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) {
            Long.MAX_VALUE
        } else {
            first + second
        }
}

/** Pure top-level entry point for callers that do not need the calculator object. */
fun calculateBundleProgressSnapshot(
    entries: List<BundleProgressEntry>,
    previousSnapshot: BundleProgressSnapshot? = null,
    resetToPersisted: Boolean = false
): BundleProgressSnapshot = BundleProgressSnapshotCalculator.calculate(
    entries = entries,
    previousSnapshot = previousSnapshot,
    resetToPersisted = resetToPersisted
)
