package com.example.llamadroid.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.example.llamadroid.R
import java.io.InputStream

/**
 * API-neutral data captured from [ApplicationExitInfo]. Keeping the framework type out of the
 * diagnostics store means API 26-29 can load that store without resolving an API-30 class.
 */
internal data class ApplicationExitRecord(
    val timestamp: Long,
    val reason: Int,
    val reasonLabel: String,
    val isOtherReason: Boolean,
    val status: Int,
    val importance: Int,
    val processName: String?,
    val pid: Int,
    val pss: Long,
    val rss: Long,
    val description: String?,
    private val traceProvider: (() -> InputStream?)?
) {
    fun openTrace(): InputStream? = traceProvider?.invoke()
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
internal fun isApplicationExitInfoAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * Reads the bounded history only on API 30+, where ApplicationExitInfo and its accessors exist.
 * Keeping this in a separate implementation class prevents older Android runtimes from resolving
 * the API-30 framework type while loading the API-neutral diagnostics facade.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object ApplicationExitInfoApi30 {
    @JvmStatic
    @DoNotInline
    fun readRecords(
        context: Context,
        activityManager: ActivityManager,
        maxCount: Int
    ): List<ApplicationExitRecord> =
        activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, maxCount)
            .map { info ->
                ApplicationExitRecord(
                    timestamp = info.timestamp,
                    reason = info.reason,
                    reasonLabel = reasonLabel(context, info.reason),
                    isOtherReason = info.reason == ApplicationExitInfo.REASON_OTHER,
                    status = info.status,
                    importance = info.importance,
                    processName = info.processName,
                    pid = info.pid,
                    pss = info.pss,
                    rss = info.rss,
                    description = info.description,
                    traceProvider = { info.traceInputStream }
                )
            }

    private fun reasonLabel(context: Context, reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_ANR -> context.getString(R.string.logs_exit_reason_anr)
            ApplicationExitInfo.REASON_CRASH -> context.getString(R.string.logs_exit_reason_crash)
            ApplicationExitInfo.REASON_CRASH_NATIVE -> context.getString(R.string.logs_exit_reason_native_crash)
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> context.getString(R.string.logs_exit_reason_dependency_died)
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> context.getString(R.string.logs_exit_reason_excessive_resource_usage)
            ApplicationExitInfo.REASON_EXIT_SELF -> context.getString(R.string.logs_exit_reason_exit_self)
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> context.getString(R.string.logs_exit_reason_initialization_failure)
            ApplicationExitInfo.REASON_LOW_MEMORY -> context.getString(R.string.logs_exit_reason_low_memory)
            ApplicationExitInfo.REASON_OTHER -> context.getString(R.string.logs_exit_reason_other)
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> context.getString(R.string.logs_exit_reason_package_updated)
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> context.getString(R.string.logs_exit_reason_permission_change)
            ApplicationExitInfo.REASON_SIGNALED -> context.getString(R.string.logs_exit_reason_signaled)
            ApplicationExitInfo.REASON_USER_REQUESTED -> context.getString(R.string.logs_exit_reason_user_requested)
            ApplicationExitInfo.REASON_USER_STOPPED -> context.getString(R.string.logs_exit_reason_user_stopped)
            else -> context.getString(R.string.logs_exit_reason_unknown, reason)
        }
    }
