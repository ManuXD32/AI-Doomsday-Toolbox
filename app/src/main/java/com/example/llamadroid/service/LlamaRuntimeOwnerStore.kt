package com.example.llamadroid.service

import android.content.Context

/** Minimal durable identity for the native child owned by the isolated llama runtime. */
internal data class LlamaRuntimeOwnerRecord(
    val pid: Int,
    val port: Int,
    val lifecycleGeneration: Long,
    val processStartTimeTicks: Long
)

internal fun llamaRuntimeOwnerRecordIsValid(record: LlamaRuntimeOwnerRecord): Boolean =
    record.pid > 0 &&
        record.port in 1..65535 &&
        record.lifecycleGeneration > 0L &&
        record.processStartTimeTicks > 0L

internal data class LlamaRuntimeOwnerRecovery(
    val recordedPid: Int?,
    val recordedPort: Int?,
    val matchedRecordedOwner: Boolean,
    val cleanedProcessCount: Int
)

/**
 * Persists only process identity metadata. It intentionally contains no model path, arguments,
 * prompt, output, or user content.
 */
internal object LlamaRuntimeOwnerStore {
    private const val PREFS = "llama_runtime_owner"
    private const val KEY_PID = "pid"
    private const val KEY_PORT = "port"
    private const val KEY_LIFECYCLE_GENERATION = "lifecycle_generation"
    private const val KEY_PROCESS_START_TICKS = "process_start_ticks"

    fun save(context: Context, record: LlamaRuntimeOwnerRecord) {
        if (!llamaRuntimeOwnerRecordIsValid(record)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PID, record.pid)
            .putInt(KEY_PORT, record.port)
            .putLong(KEY_LIFECYCLE_GENERATION, record.lifecycleGeneration)
            .putLong(KEY_PROCESS_START_TICKS, record.processStartTimeTicks)
            .commit()
    }

    fun load(context: Context): LlamaRuntimeOwnerRecord? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val record = LlamaRuntimeOwnerRecord(
            pid = prefs.getInt(KEY_PID, -1),
            port = prefs.getInt(KEY_PORT, -1),
            lifecycleGeneration = prefs.getLong(KEY_LIFECYCLE_GENERATION, 0L),
            processStartTimeTicks = prefs.getLong(KEY_PROCESS_START_TICKS, 0L)
        )
        return record.takeIf(::llamaRuntimeOwnerRecordIsValid)
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
