package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.currentLiteRtDeviceTargetInfo
import com.google.gson.Gson
import java.io.File

data class LiteRtBackendDoctorResult(
    val modelId: Long,
    val modelName: String,
    val filename: String,
    val modelTarget: String?,
    val backend: String,
    val success: Boolean,
    val phase: String,
    val detail: String,
    val deviceInfo: String,
    val deviceTargets: List<String>,
    val nativeLibraries: List<String>,
    val processExit: String?,
    val startedAt: Long,
    val durationMs: Long,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val tokensPerSecond: Double = 0.0
) {
    companion object {
        fun create(
            context: Context,
            model: LiteRtModelEntity,
            backend: String,
            success: Boolean,
            phase: String,
            detail: String,
            processExit: String?,
            startedAt: Long,
            durationMs: Long,
            stats: LiteRtLmChatStats? = null
        ): LiteRtBackendDoctorResult {
            val device = currentLiteRtDeviceTargetInfo()
            return LiteRtBackendDoctorResult(
                modelId = model.id,
                modelName = model.displayName,
                filename = model.filename,
                modelTarget = null,
                backend = backend,
                success = success,
                phase = phase,
                detail = detail,
                deviceInfo = device.rawLabel,
                deviceTargets = device.normalizedTargets.toList(),
                nativeLibraries = liteRtNativeLibraryInventory(context),
                processExit = processExit,
                startedAt = startedAt,
                durationMs = durationMs,
                promptTokens = stats?.promptTokens ?: 0,
                completionTokens = stats?.completionTokens ?: 0,
                tokensPerSecond = stats?.tokensPerSecond ?: 0.0
            )
        }
    }
}

object LiteRtBackendDoctorStore {
    private const val PREFS = "litert_backend_doctor"
    private const val KEY_PREFIX = "result_"
    private val gson = Gson()

    fun save(context: Context, result: LiteRtBackendDoctorResult) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(result.modelId, result.backend), gson.toJson(result))
            .apply()
    }

    fun loadLatest(context: Context, modelId: Long): List<LiteRtBackendDoctorResult> {
        val prefix = KEY_PREFIX + modelId + "_"
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
            .mapNotNull { (key, value) ->
                if (!key.startsWith(prefix)) return@mapNotNull null
                (value as? String)?.let { json ->
                    runCatching { gson.fromJson(json, LiteRtBackendDoctorResult::class.java) }.getOrNull()
                }
            }
            .sortedByDescending { it.startedAt }
    }

    private fun key(modelId: Long, backend: String): String = "$KEY_PREFIX${modelId}_${backend.lowercase()}"
}

fun liteRtNativeLibraryInventory(context: Context): List<String> {
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val expected = listOf(
            "libLiteRt.so",
            "liblitertlm_jni.so",
            "libLiteRtClGlAccelerator.so"
        )
    return expected.map { name ->
        "$name=${if (File(nativeDir, name).isFile) "present" else "missing"}"
    }
}
