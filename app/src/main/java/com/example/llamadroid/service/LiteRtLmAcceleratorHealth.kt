package com.example.llamadroid.service

import android.content.Context
import android.os.Build
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.util.DebugLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object LiteRtLmAcceleratorHealth {
    private const val PREFS_NAME = "litert_lm_accelerator_health"
    private const val GPU_TIMESTAMP_PREFIX = "gpu_timestamp_"
    private const val GPU_DETAIL_PREFIX = "gpu_detail_"
    private const val GPU_QUARANTINE_TTL_MS = 14L * 24L * 60L * 60L * 1000L

    private val gpuCrashes = ConcurrentHashMap<String, AcceleratorCrash>()

    fun isGpuQuarantined(context: Context, model: LiteRtModelEntity): Boolean =
        gpuCrash(context, model) != null

    fun recordGpuCrash(context: Context, model: LiteRtModelEntity, detail: String) {
        val crash = AcceleratorCrash(
            timestamp = System.currentTimeMillis(),
            detail = detail.take(400)
        )
        val key = gpuKey(model)
        gpuCrashes[key] = crash
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("$GPU_TIMESTAMP_PREFIX$key", crash.timestamp)
            .putString("$GPU_DETAIL_PREFIX$key", crash.detail)
            .apply()
        DebugLog.log(
            "LiteRT GPU quarantined for this app version: " +
                "model=${model.displayName} detail=${crash.detail}"
        )
    }

    fun clearGpuCrash(context: Context, model: LiteRtModelEntity) {
        val key = gpuKey(model)
        gpuCrashes.remove(key)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$GPU_TIMESTAMP_PREFIX$key")
            .remove("$GPU_DETAIL_PREFIX$key")
            .apply()
        DebugLog.log("LiteRT GPU quarantine cleared for this app version: model=${model.displayName}")
    }

    fun gpuCrashDetail(context: Context, model: LiteRtModelEntity): String? =
        gpuCrash(context, model)?.detail

    private fun gpuCrash(context: Context, model: LiteRtModelEntity): AcceleratorCrash? {
        val key = gpuKey(model)
        gpuCrashes[key]?.takeUnless { it.isExpired() }?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestampKey = "$GPU_TIMESTAMP_PREFIX$key"
        val detailKey = "$GPU_DETAIL_PREFIX$key"
        val timestamp = prefs.getLong(timestampKey, 0L)
        val detail = prefs.getString(detailKey, null)
        if (timestamp <= 0L || detail.isNullOrBlank()) return null
        val crash = AcceleratorCrash(timestamp = timestamp, detail = detail)
        if (crash.isExpired()) {
            prefs.edit()
                .remove(timestampKey)
                .remove(detailKey)
                .apply()
            gpuCrashes.remove(key)
            return null
        }
        gpuCrashes[key] = crash
        return crash
    }

    private fun gpuKey(model: LiteRtModelEntity): String = listOf(
        "gpu",
        BuildConfig.VERSION_CODE.toString(),
        model.id.toString(),
        model.path,
        Build.MANUFACTURER,
        Build.MODEL,
        Build.DEVICE,
        Build.PRODUCT,
        Build.HARDWARE,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else "",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
    ).joinToString("|").sha256()

    private data class AcceleratorCrash(
        val timestamp: Long,
        val detail: String
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > GPU_QUARANTINE_TTL_MS
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
