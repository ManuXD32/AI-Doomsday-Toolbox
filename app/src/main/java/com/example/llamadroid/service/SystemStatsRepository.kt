package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SystemStatsSampleEntity
import com.example.llamadroid.util.StatsExport
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class SystemStatsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getDatabase(appContext).systemStatsDao()
    private val monitor = SystemMonitor(appContext)

    fun warmUp() = monitor.warmUp()

    suspend fun sampleAndPersist(now: Long = System.currentTimeMillis()): SystemStatsSnapshot {
        val snapshot = monitor.sample(now)
        dao.insertSample(
            SystemStatsSampleEntity(
                timestampEpochMs = snapshot.timestampEpochMs,
                deviceId = snapshot.device.model,
                snapshotJson = JsonUtils.encode(snapshot)
            )
        )
        prune(now)
        return snapshot
    }

    fun sampleLive(now: Long = System.currentTimeMillis()): SystemStatsSnapshot = monitor.sample(now)

    suspend fun getSamples(fromEpochMs: Long, untilEpochMs: Long): List<SystemStatsSnapshot> =
        dao.getSamples(fromEpochMs, untilEpochMs).mapNotNull { decode(it.snapshotJson) }

    fun observeSamples(fromEpochMs: Long, untilEpochMs: Long): Flow<List<SystemStatsSampleEntity>> =
        dao.observeSamples(fromEpochMs, untilEpochMs)

    suspend fun export(fromEpochMs: Long, untilEpochMs: Long): String {
        val samples = getSamples(fromEpochMs, untilEpochMs)
        return JsonUtils.encode(StatsExport(System.currentTimeMillis(), fromEpochMs, untilEpochMs, samples))
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) {
        val cutoff = now - HISTORY_MS
        dao.deleteSamplesBefore(cutoff)
    }

    private fun decode(json: String): SystemStatsSnapshot? = runCatching { JsonUtils.decode<SystemStatsSnapshot>(json) }.getOrNull()

    companion object {
        const val HISTORY_MS = 24L * 60L * 60L * 1000L
    }
}

/** Central JSON configuration keeps exports and database payloads deterministic. */
object JsonUtils {
    @PublishedApi
    internal val json = kotlinx.serialization.json.Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(value: String): T = json.decodeFromString(value)
}

object SystemStatsCollectionManager {
    private const val PREFS = "system_stats_collection"
    private const val ENABLED = "enabled"
    private const val LAST_FAILURE = "last_failure"
    private const val LAST_SUCCESS = "last_success"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) StatsCollectionService.start(appContext) else StatsCollectionService.stop(appContext)
    }

    fun lastFailure(context: Context): String? = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(LAST_FAILURE, null)
        ?.takeIf { it.isNotBlank() }

    fun recordSampleSuccess(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(LAST_FAILURE)
            .putLong(LAST_SUCCESS, System.currentTimeMillis())
            .apply()
    }

    fun recordSampleFailure(context: Context, error: Throwable) {
        val message = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            .replace('\n', ' ')
            .take(240)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_FAILURE, message)
            .apply()
    }
}
