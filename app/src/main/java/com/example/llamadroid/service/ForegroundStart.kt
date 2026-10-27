package com.example.llamadroid.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog

/**
 * Foreground-service type bits, equal to the `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` constants
 * (pinned by `ForegroundStartTest`). Call sites use these instead of the framework fields, which are
 * API 29+ (MICROPHONE 30, SPECIAL_USE 34, MEDIA_PROCESSING 35) while minSdk is 26: below API 29
 * [ForegroundStart.start] ignores the mask, and [ForegroundStart.effectiveTypes] maps the newer bits.
 */
object ForegroundServiceTypes {
    const val DATA_SYNC = 1
    const val MEDIA_PLAYBACK = 2
    const val MICROPHONE = 128
    const val MEDIA_PROCESSING = 8192
    const val SPECIAL_USE = 1 shl 30
}

/**
 * One guarded entry point for `startForeground` on targetSdk 36.
 *
 * It requests explicit foreground-service types (never the "all manifest types" 2-argument form),
 * drops types Android would reject (microphone without RECORD_AUDIO, mediaProcessing below
 * API 35, anything the manifest entry does not declare) and turns the start-time exceptions into a
 * typed [Result.Failed] so the caller can `stopSelf()` and show a readable notification.
 */
object ForegroundStart {

    enum class Reason {
        /** RECORD_AUDIO is not granted, so the microphone type cannot be used. */
        MICROPHONE_PERMISSION,

        /** Android refused the start because the app is in the background. */
        BACKGROUND_NOT_ALLOWED,

        /** The requested types are not declared by the manifest entry or are invalid. */
        INVALID_TYPE,

        /** Any other SecurityException. */
        SECURITY
    }

    sealed class Result {
        data class Started(val types: Int) : Result()
        data class Failed(val reason: Reason, val cause: Throwable? = null) : Result()
    }

    /**
     * Calls startForeground with the effective types. Never throws for the documented denial
     * exceptions. [requestedTypes] is a [ServiceInfo] `FOREGROUND_SERVICE_TYPE_*` mask.
     */
    fun start(service: Service, id: Int, notification: Notification, requestedTypes: Int): Result {
        val sdk = Build.VERSION.SDK_INT
        val micGranted = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val needsMic = requestedTypes and ForegroundServiceTypes.MICROPHONE != 0
        val types = effectiveTypes(requestedTypes, sdk, micGranted, declaredTypes(service))
        if (types == 0) {
            val reason = if (needsMic && !micGranted) Reason.MICROPHONE_PERMISSION else Reason.INVALID_TYPE
            return Result.Failed(reason)
        }
        return try {
            ServiceCompat.startForeground(service, id, notification, types)
            Result.Started(types)
        } catch (error: Exception) {
            var reason = classifyFailure(error) ?: throw error
            if (reason == Reason.SECURITY && needsMic) {
                // The microphone type is denied while RECORD_AUDIO is granted only when the app is
                // not in a state that may use it, i.e. it is in the background.
                reason = if (micGranted) Reason.BACKGROUND_NOT_ALLOWED else Reason.MICROPHONE_PERMISSION
            }
            DebugLog.log("ForegroundStart: ${service.javaClass.simpleName} startForeground failed: ${error.javaClass.simpleName}")
            Result.Failed(reason, error)
        }
    }

    /**
     * Like [start] but for services that cannot work without the microphone: if the microphone
     * type would be dropped the start fails instead of running without it.
     */
    fun startRequiringMicrophone(service: Service, id: Int, notification: Notification, requestedTypes: Int): Result {
        val micGranted = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) return Result.Failed(Reason.MICROPHONE_PERMISSION)
        return start(service, id, notification, requestedTypes or ForegroundServiceTypes.MICROPHONE)
    }

    /**
     * True when the app is visible to the user right now, the state in which Android lets a
     * microphone foreground service start. A merely running foreground service does not count.
     */
    fun isAppVisibleForMicrophone(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        return isVisibleImportance(state.importance)
    }

    internal fun isVisibleImportance(importance: Int): Boolean =
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
            importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    /** Readable, localized explanation for a failed start of [featureName]. */
    fun failureMessage(context: Context, reason: Reason, featureName: String): String = when (reason) {
        Reason.MICROPHONE_PERMISSION -> context.getString(R.string.fgs_start_failed_mic_permission, featureName)
        Reason.BACKGROUND_NOT_ALLOWED -> context.getString(R.string.fgs_start_failed_background, featureName)
        Reason.INVALID_TYPE, Reason.SECURITY -> context.getString(R.string.fgs_start_failed_generic, featureName)
    }

    private fun declaredTypes(service: Service): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.packageManager.getServiceInfo(
                    ComponentName(service, service.javaClass),
                    PackageManager.ComponentInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                service.packageManager.getServiceInfo(ComponentName(service, service.javaClass), 0)
            }
            info.foregroundServiceType
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pure type-mask computation. Below API 29 explicit types do not exist and [requested] is
     * returned unchanged. From API 29: mediaProcessing becomes dataSync below API 35, microphone is
     * dropped without RECORD_AUDIO, and bits the manifest does not declare are removed
     * ([manifestTypes] null means unknown, so no manifest filtering). A result of 0 means nothing
     * can be started.
     */
    internal fun effectiveTypes(requested: Int, sdkInt: Int, recordAudioGranted: Boolean, manifestTypes: Int?): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return requested
        var types = requested
        if (sdkInt < 35 && types and ForegroundServiceTypes.MEDIA_PROCESSING != 0) {
            types = (types and ForegroundServiceTypes.MEDIA_PROCESSING.inv()) or
                ForegroundServiceTypes.DATA_SYNC
        }
        if (!recordAudioGranted) types = types and ForegroundServiceTypes.MICROPHONE.inv()
        if (manifestTypes != null && manifestTypes != 0) types = types and manifestTypes
        return types
    }

    /** Maps a start exception to a [Reason] by class name (API-safe), or null when unrelated. */
    internal fun classifyFailure(error: Throwable): Reason? {
        var current: Class<*>? = error.javaClass
        while (current != null) {
            when (current.simpleName) {
                "ForegroundServiceStartNotAllowedException" -> return Reason.BACKGROUND_NOT_ALLOWED
                "InvalidForegroundServiceTypeException",
                "MissingForegroundServiceTypeException" -> return Reason.INVALID_TYPE
            }
            current = current.superclass
        }
        if (error is SecurityException) return Reason.SECURITY
        return null
    }
}
