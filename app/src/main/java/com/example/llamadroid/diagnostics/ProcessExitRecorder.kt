package com.example.llamadroid.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.example.llamadroid.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Persists Android's historical process-exit reasons (metadata only) so a background stop
 * that happened while the app was dead can be explained on the next start.
 */
object ProcessExitRecorder {
    private const val PREFS = "background_survival"
    private const val KEY_RECORDS = "exit_records"
    private const val KEY_LAST_SEEN = "exit_last_seen"
    private const val RUNTIME_SUFFIX = ":llama_runtime"

    /** Reads Android's exit history on a background thread and stores records not seen before. */
    fun recordAsync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        Thread({ runCatching { record(app) } }, "process-exit-recorder").start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun record(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val pkg = context.packageName
        val candidates = am.getHistoricalProcessExitReasons(pkg, 0, 16)
            .filter { it.processName == pkg || it.processName == pkg + RUNTIME_SUFFIX }
            .map {
                ProcessExitRecord(
                    processName = it.processName,
                    reason = it.reason,
                    status = it.status,
                    description = ProcessExitLogic.boundDescription(it.description),
                    timestamp = it.timestamp,
                    importance = it.importance,
                    pssKb = it.pss,
                    rssKb = it.rss,
                )
            }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        val fresh = ProcessExitLogic.newSince(candidates, lastSeen)
        if (fresh.isEmpty()) return
        val merged = ProcessExitLogic.merge(load(context), fresh)
        prefs.edit {
            putString(KEY_RECORDS, encode(merged))
            putLong(KEY_LAST_SEEN, ProcessExitLogic.newestTimestamp(fresh, lastSeen))
        }
    }

    /** Stored records, newest first. */
    fun load(context: Context): List<ProcessExitRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ProcessExitRecord(
                    processName = o.optString("p"),
                    reason = o.optInt("r"),
                    status = o.optInt("s"),
                    description = o.optString("d"),
                    timestamp = o.optLong("t"),
                    importance = o.optInt("i"),
                    pssKb = o.optLong("pss"),
                    rssKb = o.optLong("rss"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(records: List<ProcessExitRecord>): String {
        val array = JSONArray()
        records.forEach {
            array.put(
                JSONObject()
                    .put("p", it.processName).put("r", it.reason).put("s", it.status)
                    .put("d", it.description).put("t", it.timestamp).put("i", it.importance)
                    .put("pss", it.pssKb).put("rss", it.rssKb),
            )
        }
        return array.toString()
    }

    @StringRes
    private fun reasonText(category: ProcessExitCategory): Int = when (category) {
        ProcessExitCategory.LOW_MEMORY -> R.string.background_stop_reason_low_memory
        ProcessExitCategory.EXCESSIVE_RESOURCE_USAGE -> R.string.background_stop_reason_excessive
        ProcessExitCategory.USER_REQUESTED -> R.string.background_stop_reason_user
        ProcessExitCategory.SIGNALED -> R.string.background_stop_reason_signaled
        ProcessExitCategory.ANR -> R.string.background_stop_reason_anr
        ProcessExitCategory.CRASH -> R.string.background_stop_reason_crash
        ProcessExitCategory.CRASH_NATIVE -> R.string.background_stop_reason_native_crash
        ProcessExitCategory.DEPENDENCY_DIED -> R.string.background_stop_reason_dependency
        ProcessExitCategory.FREEZER -> R.string.background_stop_reason_freezer
        ProcessExitCategory.FGS_TIMEOUT -> R.string.background_stop_reason_fgs_timeout
        ProcessExitCategory.PERMISSION_CHANGE -> R.string.background_stop_reason_permission
        ProcessExitCategory.OTHER -> R.string.background_stop_reason_other
    }

    /** "Last background stop: <reason> at <time> (<process>)" for one record. */
    fun describe(context: Context, record: ProcessExitRecord): String {
        val reason = context.getString(
            reasonText(ProcessExitLogic.categorize(record.reason, record.description)),
        )
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(record.timestamp))
        val process = context.getString(
            if (record.processName.endsWith(RUNTIME_SUFFIX)) R.string.background_stop_process_runtime
            else R.string.background_stop_process_main,
        )
        return context.getString(R.string.background_stop_last, reason, time, process)
    }

    /** One diagnostic line (with the technical code) or null when nothing was recorded. */
    fun summary(context: Context): String? {
        val latest = load(context).firstOrNull() ?: return null
        return describe(context, latest) + " [" + ProcessExitLogic.reasonName(latest.reason) +
            " status=" + latest.status + " rss=" + latest.rssKb + "KB]"
    }
}
