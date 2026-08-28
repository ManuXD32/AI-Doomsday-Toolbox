package com.example.llamadroid.service

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object RemoteAgentProtection {
    private val refCount = AtomicInteger(0)
    private val externalForegroundCount = AtomicInteger(0)
    private val foregroundStartedByProtection = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopLock = Any()
    private var pendingForegroundStop: Runnable? = null

    private const val FOREGROUND_STOP_GRACE_MS = 2_500L

    fun isRemoteUrl(baseUrl: String): Boolean {
        return try {
            val host = URI(baseUrl.trim()).host?.lowercase().orEmpty()
            host.isNotBlank() &&
                host != "localhost" &&
                host != "127.0.0.1" &&
                host != "::1" &&
                host != "0.0.0.0"
        } catch (_: Exception) {
            false
        }
    }

    suspend fun <T> withProtection(baseUrl: String, status: String, block: suspend () -> T): T {
        if (!isRemoteUrl(baseUrl)) return block()

        acquire(status)
        return try {
            block()
        } finally {
            release()
        }
    }

    suspend fun <T> withExistingForeground(owner: String, block: suspend () -> T): T {
        val retained = externalForegroundCount.incrementAndGet()
        DebugLog.log("[RemoteAgentProtection] existing foreground retained owner=$owner count=$retained")
        recordBreadcrumb("existing_foreground_retained", "owner=$owner count=$retained")
        return try {
            block()
        } finally {
            val remaining = externalForegroundCount.decrementAndGet().coerceAtLeast(0)
            externalForegroundCount.set(remaining)
            DebugLog.log("[RemoteAgentProtection] existing foreground released owner=$owner count=$remaining")
            recordBreadcrumb("existing_foreground_released", "owner=$owner count=$remaining")
            if (
                remaining == 0 &&
                refCount.get() == 0 &&
                foregroundStartedByProtection.get() &&
                !AgentService.isLoading.value
            ) {
                scheduleForegroundStop(LlamaApplication.instance)
            }
        }
    }

    private fun acquire(status: String) {
        val context = LlamaApplication.instance
        cancelPendingForegroundStop("acquire")
        if (refCount.incrementAndGet() == 1) {
            if (externalForegroundCount.get() > 0 || AgentForegroundService.isServiceRunning()) {
                DebugLog.log("[RemoteAgentProtection] using existing foreground for remote call")
                recordBreadcrumb("using_existing_foreground", commonDetails("remote=true status=${status.take(80)}"))
                WakeLockManager.acquire(context, "RemoteAgentProtection")
                WakeLockManager.acquireWifiLock(context, "RemoteAgentProtection")
                return
            }
            foregroundStartedByProtection.set(true)
            recordBreadcrumb("starting_foreground", commonDetails("remote=true status=${status.take(80)}"))
            AgentForegroundService.start(context, status)
            WakeLockManager.acquire(context, "RemoteAgentProtection")
            WakeLockManager.acquireWifiLock(context, "RemoteAgentProtection")
        } else if (externalForegroundCount.get() == 0) {
            recordBreadcrumb("foreground_status_update", commonDetails("status=${status.take(80)}"))
            AgentForegroundService.updateStatus(context, status)
        }
    }

    private fun release() {
        val context = LlamaApplication.instance
        val remaining = refCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            refCount.set(0)
            WakeLockManager.release("RemoteAgentProtection")
            WakeLockManager.releaseWifiLock("RemoteAgentProtection")
            if (
                foregroundStartedByProtection.get() &&
                externalForegroundCount.get() == 0 &&
                !AgentService.isLoading.value
            ) {
                scheduleForegroundStop(context)
            }
        }
    }

    private fun scheduleForegroundStop(context: android.content.Context) {
        synchronized(stopLock) {
            pendingForegroundStop?.let(mainHandler::removeCallbacks)
            val runnable = object : Runnable {
                override fun run() {
                    synchronized(stopLock) {
                        if (pendingForegroundStop !== this) return
                        pendingForegroundStop = null
                    }
                    if (
                        refCount.get() == 0 &&
                        externalForegroundCount.get() == 0 &&
                        !AgentService.isLoading.value &&
                        foregroundStartedByProtection.compareAndSet(true, false)
                    ) {
                        recordBreadcrumb("foreground_stop_after_grace", commonDetails("delayMs=$FOREGROUND_STOP_GRACE_MS"))
                        AgentForegroundService.stop(context)
                    } else {
                        recordBreadcrumb("foreground_stop_skipped_after_grace", commonDetails("delayMs=$FOREGROUND_STOP_GRACE_MS"))
                    }
                }
            }
            pendingForegroundStop = runnable
            recordBreadcrumb("foreground_stop_scheduled", commonDetails("delayMs=$FOREGROUND_STOP_GRACE_MS"))
            mainHandler.postDelayed(runnable, FOREGROUND_STOP_GRACE_MS)
        }
    }

    private fun cancelPendingForegroundStop(reason: String) {
        synchronized(stopLock) {
            val pending = pendingForegroundStop ?: return
            mainHandler.removeCallbacks(pending)
            pendingForegroundStop = null
            recordBreadcrumb("foreground_stop_cancelled", commonDetails("reason=$reason"))
        }
    }

    private fun commonDetails(prefix: String): String =
        "$prefix ref=${refCount.get()} external=${externalForegroundCount.get()} " +
            "agentForeground=${AgentForegroundService.isServiceRunning()} loading=${AgentService.isLoading.value} " +
            "pid=${Process.myPid()} active=${GenerationDiagnosticsStore.activeSessionSummaryForBreadcrumb()}"

    private fun recordBreadcrumb(event: String, details: String) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "remote_agent_protection",
                event = event,
                details = details
            )
        }
    }
}
