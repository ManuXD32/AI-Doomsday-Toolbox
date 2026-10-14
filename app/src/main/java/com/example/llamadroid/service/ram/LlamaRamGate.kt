package com.example.llamadroid.service.ram

import android.app.ActivityManager
import android.content.Context
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaServerLaunchProfile
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

    fun snapshot(): RamSnapshot {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return RamSnapshot(0L, true, 0L)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return RamSnapshot(info.availMem, info.lowMemory, readInactiveFileBytes())
    }

    fun decide(estimate: LlamaRamEstimate, snapshot: RamSnapshot = snapshot()): LlamaRamDecision =
        LlamaRamEstimator.decide(estimate, snapshot.availBytes, snapshot.lowMemory, snapshot.reclaimableCacheBytes)

    private fun readInactiveFileBytes(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Inactive(file):") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) ?: 0L
        }
    }.getOrDefault(0L)

    companion object {
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
