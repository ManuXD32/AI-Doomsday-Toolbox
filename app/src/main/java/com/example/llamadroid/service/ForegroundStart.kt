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

        /** Android refused a microphone start because the app is in the background. */
        MICROPHONE_BACKGROUND,

        /** Any other SecurityException. */
        SECURITY
    }

    sealed class Result {
        data class Started(val types: Int) : Result()
        data class Failed(val reason: Reason, val cause: Throwable? = null) : Result() {
            /** Exception message (or class name) for user-visible and diagnostic detail, if any. */
            val detail: String? get() = cause?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName }
        }
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
        val declared = declaredTypes(service)
        val types = effectiveTypes(requestedTypes, sdk, micGranted, declared)
        if (types == 0) {
            val reason = if (needsMic && !micGranted) Reason.MICROPHONE_PERMISSION else Reason.INVALID_TYPE
            return Result.Failed(reason)
        }
        return try {
            ServiceCompat.startForeground(service, id, notification, types)
            Result.Started(types)
        } catch (error: Exception) {
            var reason = classifyFailure(error) ?: throw error
            if (needsMic && (reason == Reason.SECURITY || reason == Reason.BACKGROUND_NOT_ALLOWED)) {
                // The microphone type is denied while RECORD_AUDIO is granted only when the app is
                // not in a state that may use it, i.e. it is in the background.
                reason = if (micGranted) Reason.MICROPHONE_BACKGROUND else Reason.MICROPHONE_PERMISSION
            }
            val serviceName = service.javaClass.simpleName
            DebugLog.log("ForegroundStart: $serviceName startForeground(types=$types) failed: ${describe(error)}")
            val fallback = retryTypes(types, declared)
            if (fallback != null) {
                // E.g. mediaProcessing is refused in freeform/floating windows or after its time
                // limit while dataSync, also declared by the manifest, is allowed.
                try {
                    ServiceCompat.startForeground(service, id, notification, fallback)
                    DebugLog.log("ForegroundStart: $serviceName retried with dataSync after ${reason.name}")
                    return Result.Started(fallback)
                } catch (retryError: Exception) {
                    DebugLog.log("ForegroundStart: $serviceName dataSync retry failed: ${describe(retryError)}")
                }
            }
            Result.Failed(reason, error)
        }
    }

    private fun describe(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}"

    /**
     * Pure retry decision: when a start that included mediaProcessing failed, returns the mask with
     * mediaProcessing replaced by dataSync, but only if the manifest entry declares dataSync
     * ([declaredTypes] null/0 means unknown, so no retry). Null means do not retry.
     */
    internal fun retryTypes(attempted: Int, declaredTypes: Int?): Int? {
        if (attempted and ForegroundServiceTypes.MEDIA_PROCESSING == 0) return null
        if (declaredTypes == null || declaredTypes and ForegroundServiceTypes.DATA_SYNC == 0) return null
        return (attempted and ForegroundServiceTypes.MEDIA_PROCESSING.inv()) or ForegroundServiceTypes.DATA_SYNC
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
    fun failureMessage(context: Context, reason: Reason, featureName: String, detail: String? = null): String = when (reason) {
        Reason.MICROPHONE_PERMISSION -> context.getString(R.string.fgs_start_failed_mic_permission, featureName)
        Reason.MICROPHONE_BACKGROUND -> context.getString(R.string.fgs_start_failed_background, featureName)
        Reason.BACKGROUND_NOT_ALLOWED -> context.getString(R.string.fgs_start_failed_generic, featureName)
        Reason.INVALID_TYPE, Reason.SECURITY -> context.getString(
            R.string.fgs_start_failed_type,
            featureName,
            detail?.takeIf { it.isNotBlank() } ?: reason.name
        )
    }

    fun failureMessage(context: Context, failed: Result.Failed, featureName: String): String =
        failureMessage(context, failed.reason, featureName, failed.detail)

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
