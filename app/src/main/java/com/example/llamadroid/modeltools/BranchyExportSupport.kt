package com.example.llamadroid.modeltools

import java.io.File
import java.util.Locale

/** Pure path/name helpers for exporting a Branchy run to GGUF. */
object BranchyExportSupport {
    val TYPES = listOf("auto", "f16", "q8_0", "q4_0")

    /** Mirrors the training service layout: stage 0 lives in the run dir, later stages in `stage<N+1>`. */
    fun stageDir(runDir: File, stage: Int): File = if (stage <= 0) runDir else File(runDir, "stage${stage + 1}")

    /** The checkpoint named by `checkpoints/latest` of [stage] or the closest earlier stage. */
    fun latestCheckpoint(runDir: File, stage: Int): File? {
        for (s in stage downTo 0) {
            val dir = File(stageDir(runDir, s), "checkpoints")
            val name = runCatching { File(dir, "latest").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            File(dir, name.substringAfterLast('/')).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    fun safeName(runName: String): String =
        runName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "branchy" }.take(60)

    /** `<runDir>/export/<name>-s<step>-<type>.gguf`; the step keeps re-exports of later checkpoints side by side. */
    fun outputFile(runDir: File, runName: String, step: Long, type: String): File =
        File(File(runDir, "export"), "${safeName(runName)}-s$step-${type.lowercase(Locale.ROOT)}.gguf")
}
