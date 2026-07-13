package com.example.llamadroid.ui.ai

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.service.GenerationDiagnosticsStore

private const val AI_JOB_STARTUP_SOURCE = "ai_job_startup_guard"
private const val STARTUP_GUARD_RELEASE_MS = 30_000L

class AiJobStartupGuard(private val context: Context) {
    fun <T> run(label: String, block: () -> T): T {
        val setup = begin(label)
        return try {
            block().also {
                AiJobStartupDiagnostics.record(context.applicationContext, label, "post_launch_state")
            }
        } catch (error: Throwable) {
            AiJobStartupDiagnostics.record(
                context.applicationContext,
                label,
                "launch_failed",
                "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            throw error
        } finally {
            setup.releaseLater()
        }
    }

    suspend fun <T> runSuspending(label: String, block: suspend () -> T): T {
        val setup = begin(label)
        return try {
            block().also {
                AiJobStartupDiagnostics.record(context.applicationContext, label, "post_launch_state")
            }
        } catch (error: Throwable) {
            AiJobStartupDiagnostics.record(
                context.applicationContext,
                label,
                "launch_failed",
                "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            throw error
        } finally {
            setup.releaseLater()
        }
    }

    private fun begin(label: String): StartupSetup {
        val appContext = context.applicationContext
        val activity = context.findActivity()
        GenerationDiagnosticsStore.init(appContext)
        AiJobStartupDiagnostics.record(appContext, label, "pre_launch_state")
        val releaseKeepScreenOn = activity?.let { setKeepScreenOnForStartup(it) } ?: false
        val receiver = registerStartupReceiver(appContext, label)
        return StartupSetup(appContext, activity, receiver, releaseKeepScreenOn, label)
    }

    private data class StartupSetup(
        val appContext: Context,
        val activity: Activity?,
        val receiver: BroadcastReceiver,
        val releaseKeepScreenOn: Boolean,
        val label: String
    ) {
        fun releaseLater() {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { appContext.unregisterReceiver(receiver) }
                if (releaseKeepScreenOn) {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                AiJobStartupDiagnostics.record(appContext, label, "startup_guard_released")
            }, STARTUP_GUARD_RELEASE_MS)
        }
    }

    private fun setKeepScreenOnForStartup(activity: Activity): Boolean {
        val alreadySet = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return !alreadySet
    }

    private fun registerStartupReceiver(appContext: Context, label: String): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val event = when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen_off"
                    Intent.ACTION_USER_PRESENT -> "user_present"
                    Intent.ACTION_SCREEN_ON -> "screen_on"
                    else -> "broadcast:${intent?.action.orEmpty()}"
                }
                AiJobStartupDiagnostics.record(appContext, label, event)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        return receiver
    }
}

object AiJobStartupDiagnostics {
    fun record(context: Context, label: String, event: String, extra: String? = null) {
        val appContext = context.applicationContext
        GenerationDiagnosticsStore.init(appContext)
        val snapshot = buildSnapshot(appContext)
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = AI_JOB_STARTUP_SOURCE,
            mode = label,
            event = event,
            details = listOfNotNull(snapshot, extra?.takeIf { it.isNotBlank() }).joinToString(" "),
            batteryExempt = snapshot.contains("batteryExempt=true"),
            interactive = snapshot.contains("interactive=true"),
            powerSaveMode = snapshot.contains("powerSave=true")
        )
    }

    private fun buildSnapshot(context: Context): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val batteryExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            power.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            false
        }
        val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) power.isDeviceIdleMode else false
        val keyguardLocked = keyguard.isKeyguardLocked
        val deviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keyguard.isDeviceLocked else keyguardLocked
        return "interactive=${power.isInteractive} powerSave=${power.isPowerSaveMode} " +
            "idle=$idle batteryExempt=$batteryExempt keyguardLocked=$keyguardLocked " +
            "deviceLocked=$deviceLocked batteryStatus=$batteryStatus plugged=$plugged"
    }
}

@Composable
fun rememberAiJobStartupGuard(): AiJobStartupGuard {
    val context = LocalContext.current
    return remember(context) { AiJobStartupGuard(context) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
