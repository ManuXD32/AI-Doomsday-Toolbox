package com.example.llamadroid.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.example.llamadroid.data.db.GenerationQueueItemEntity

/** Small, content-free samples that distinguish screen-off delays from a stopped native process. */
internal class GenerationQueuePowerDiagnostics(context: Context, private val item: GenerationQueueItemEntity) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastLoggedAt = startedAt
    private var lastStepAt = startedAt
    private var lastStep = 0
    private var lastInteractive: Boolean? = null
    private var lastThermal: Int? = null
    private var lastPowerSave: Boolean? = null

    @Synchronized fun sample(
        step: Int?,
        totalSteps: Int?,
        wakeLockHeld: Boolean?,
        foregroundActive: Boolean,
        event: String? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        val interactive = power.isInteractive
        val powerSave = power.isPowerSaveMode
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power.currentThermalStatus }.getOrNull()
        } else null
        val newStep = step != null && step > 0 && step != lastStep
        val changed = interactive != lastInteractive || thermal != lastThermal || powerSave != lastPowerSave
        if (event == null && !newStep && !changed && now - lastLoggedAt < HEARTBEAT_MS) return

        val stepGap = if (newStep) now - lastStepAt else null
        val details = buildString {
            append("itemId=").append(item.id)
            append(" elapsedMs=").append(now - startedAt)
            append(" thermal=").append(thermal ?: "unavailable")
            append(" foreground=").append(foregroundActive)
            if (step != null && totalSteps != null) {
                append(" step=").append(step).append('/').append(totalSteps)
            }
            if (stepGap != null) append(" stepGapMs=").append(stepGap)
        }
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "GenerationQueueService",
                mode = item.kind,
                event = event ?: when {
                    newStep -> "native_step"
                    changed -> "power_state_changed"
                    else -> "running_heartbeat"
                },
                details = details,
                wakeLockHeld = wakeLockHeld,
                notificationActive = foregroundActive,
                interactive = interactive,
                powerSaveMode = powerSave
            )
        }
        lastLoggedAt = now
        lastInteractive = interactive
        lastThermal = thermal
        lastPowerSave = powerSave
        if (newStep) {
            lastStep = step!!
            lastStepAt = now
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 15 * 60 * 1_000L
    }
}
