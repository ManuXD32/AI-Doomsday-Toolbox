package com.example.llamadroid.service

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

/**
 * Classifies the Android 12+ foreground-service start denial without loading the API 31
 * exception type on older devices.
 */
internal fun isForegroundServiceStartNotAllowed(error: Throwable): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return ForegroundServiceStartApi31.isStartNotAllowed(error)
}

@RequiresApi(Build.VERSION_CODES.S)
private object ForegroundServiceStartApi31 {
    @JvmStatic
    @DoNotInline
    fun isStartNotAllowed(error: Throwable): Boolean =
        error is ForegroundServiceStartNotAllowedException
}
