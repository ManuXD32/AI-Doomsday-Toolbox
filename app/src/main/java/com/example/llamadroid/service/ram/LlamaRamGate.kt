package com.example.llamadroid.service.ram

import android.app.ActivityManager
import android.content.Context
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.util.NativeProcessCleanup
import java.io.File

/** Free-memory reading used by [LlamaRamGate.decide]. */
data class RamSnapshot(val availBytes: Long, val lowMemory: Boolean, val reclaimableCacheBytes: Long)

/** Maps launch profiles to estimator inputs and applies the estimator to the live device state. */
class LlamaRamGate(context: Context) {
    private val app = context.applicationContext

    /** Blocking (reads the GGUF header and file sizes); call from an IO dispatcher. */
    fun estimate(profile: LlamaServerLaunchProfile): LlamaRamEstimate {
        val model = File(profile.modelPath)
        val shape = GgufModelShapeReader.read(model)
        return LlamaRamEstimator.estimate(inputsFor(profile, model.length(), mmprojBytesFor(profile), shape))
    }

    fun snapshot(excludePort: Int? = null): RamSnapshot {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return RamSnapshot(0L, true, 0L)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        // Mapped pages of other loaded models are in MemAvailable but in use: subtract RssFile only.
        val avail = (info.availMem - otherLlamaResidentFileBytes(excludePort)).coerceAtLeast(0L)
        return RamSnapshot(avail, info.lowMemory, readInactiveFileBytes())
    }

    fun decide(estimate: LlamaRamEstimate, snapshot: RamSnapshot = snapshot()): LlamaRamDecision =
        LlamaRamEstimator.decide(estimate, snapshot.availBytes, snapshot.lowMemory, snapshot.reclaimableCacheBytes)

    /** Sum of `RssFile` of same-UID llama-server processes, skipping the one listening on [excludePort]. */
    fun otherLlamaResidentFileBytes(excludePort: Int?, procRoot: File = File("/proc")): Long = runCatching {
        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()
        val excluded = excludePort
            ?.let { NativeProcessCleanup.findSameUidLlamaServers(procRoot, myPid, myUid, it) }
            ?.map { it.pid }?.toSet().orEmpty()
        NativeProcessCleanup.findSameUidLlamaServers(procRoot, myPid, myUid)
            .filter { it.pid !in excluded }
            .sumOf { File(procRoot, "${it.pid}/status").takeIf(File::canRead)?.readText()?.let { parseRssFileBytes(it) } ?: 0L }
    }.getOrDefault(0L)

    private fun readInactiveFileBytes(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Inactive(file):") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) ?: 0L
        }
    }.getOrDefault(0L)

    companion object {
        /** Parses the `RssFile:` line (kB) of /proc/<pid>/status into bytes; 0 when absent. */
        fun parseRssFileBytes(statusText: String): Long =
            statusText.lineSequence().firstOrNull { it.startsWith("RssFile:") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) ?: 0L

        /** Parses the `RssAnon:` line (kB) of /proc/<pid>/status into bytes; 0 when absent. */
        fun parseRssAnonBytes(statusText: String): Long =
            statusText.lineSequence().firstOrNull { it.startsWith("RssAnon:") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) ?: 0L

        /** RssFile + RssAnon of [pid]; 0 when the process or its status file is unreadable. */
        fun residentBytesOfPid(pid: Int, procRoot: File = File("/proc")): Long = runCatching {
            val text = File(procRoot, "$pid/status").takeIf(File::canRead)?.readText() ?: return@runCatching 0L
            parseRssFileBytes(text) + parseRssAnonBytes(text)
        }.getOrDefault(0L)

        /** `--mlock`, `--no-mmap` and direct-io keep the whole file resident. */
        fun fullyResident(profile: LlamaServerLaunchProfile): Boolean {
            val mode = profile.resolvedLoadMode()
            val flags = profile.customFlags.orEmpty()
            return mode == LlamaLoadMode.NONE || mode == LlamaLoadMode.MLOCK ||
                mode == LlamaLoadMode.MMAP_MLOCK || mode == LlamaLoadMode.DIO ||
                Regex("""(^|\s)--(mlock|no-mmap)(\s|$)""").containsMatchIn(flags)
        }

        fun inputsFor(
            profile: LlamaServerLaunchProfile,
            modelBytes: Long,
            mmprojBytes: Long,
            shape: LlamaModelShape?
        ): LlamaRamInputs = LlamaRamInputs(
            modelFileBytes = modelBytes,
            mmprojBytes = mmprojBytes,
            totalContext = profile.contextSize,
            slots = (profile.parallel ?: 1).coerceAtLeast(1),
            physicalBatch = profile.physicalBatchSize ?: profile.batchSize,
            kvTypeK = profile.kvCacheTypeK,
            kvTypeV = profile.kvCacheTypeV,
            fullyResident = fullyResident(profile),
            swaFull = profile.swaFull,
            shape = shape
        )

        private fun mmprojBytesFor(profile: LlamaServerLaunchProfile): Long =
            if (profile.visionEnabled || profile.videoEnabled) {
                profile.mmprojPath?.takeIf { it.isNotBlank() }?.let { File(it).length() } ?: 0L
            } else {
                0L
            }
    }
}
