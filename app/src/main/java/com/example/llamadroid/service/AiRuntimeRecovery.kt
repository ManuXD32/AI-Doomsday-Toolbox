package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AiRuntimeRecovery {
    private const val SOURCE = "ai_runtime_boot_receiver"

    fun dispatch(
        context: Context,
        action: String?,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onFinished: (() -> Unit)? = null,
        recover: suspend (Context) -> Unit = ::performRecovery
    ) {
        if (!isRelevantAction(action)) return

        val appContext = context.applicationContext
        recordBreadcrumbSafely(
            event = "receiver_dispatch_requested",
            phase = action,
            details = "async=true"
        )
        scope.launch {
            try {
                recordBreadcrumbSafely(
                    event = "receiver_started",
                    phase = action,
                    details = "async=true"
                )
                recover(appContext)
                recordBreadcrumbSafely(
                    event = "receiver_finished",
                    phase = action,
                    details = "async=true"
                )
            } catch (throwable: Throwable) {
                val message = "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
                DebugLog.log("[AiRuntimeRecovery] Receiver failed: $message")
                recordBreadcrumbSafely(
                    event = "receiver_failed",
                    phase = action,
                    details = message
                )
            } finally {
                onFinished?.invoke()
            }
        }
    }

    suspend fun performRecovery(context: Context) {
        val staleJobs = AiRuntimeJobStore.markStaleActiveJobsTerminal(context)
        val recoverableJobs = AiRuntimeJobStore.getRecoverableJobs(context)
        val hasRecoverableRuntimeJobs = recoverableJobs.isNotEmpty()
        recordBreadcrumbSafely(
            event = "receiver_recovery_state",
            details = "hasRecoverableRuntimeJobs=$hasRecoverableRuntimeJobs stalePruned=${staleJobs.size} recoverable=${recoverableJobs.size}"
        )
        TamaNotificationScheduler.scheduleAll(context)
        val recoveryAction = resolveAiRuntimeBootRecoveryAction(recoverableJobs)
        if (recoveryAction.shouldShowManualResumeNotification && recoveryAction.manualResumeRoute != null) {
            UnifiedNotificationManager.showAiRuntimeRecoveryNotification(
                recoverableCount = recoveryAction.recoverableCount,
                route = recoveryAction.manualResumeRoute
            )
        }
    }

    fun isRelevantAction(action: String?): Boolean {
        return action == Intent.ACTION_BOOT_COMPLETED
    }

    private fun recordBreadcrumbSafely(
        event: String,
        phase: String? = null,
        details: String? = null
    ) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = SOURCE,
                mode = null,
                event = event,
                phase = phase,
                details = details
            )
        }
    }
}

internal data class AiRuntimeBootRecoveryAction(
    val recoverableCount: Int,
    val manualResumeRoute: String?,
    val foregroundServiceAction: String?
) {
    val shouldShowManualResumeNotification: Boolean
        get() = manualResumeRoute != null && recoverableCount > 0
}

internal fun resolveAiRuntimeBootRecoveryAction(
    recoverableJobs: List<AiRuntimeJobEntity>
): AiRuntimeBootRecoveryAction {
    val route = when {
        recoverableJobs.isEmpty() -> null
        recoverableJobs.all { it.type == AiRuntimeJobStore.TYPE_DATASET_PIPELINE } -> Screen.Dataset.route
        else -> Screen.Agent.route
    }
    return AiRuntimeBootRecoveryAction(
        recoverableCount = recoverableJobs.size,
        manualResumeRoute = route,
        foregroundServiceAction = null
    )
}
