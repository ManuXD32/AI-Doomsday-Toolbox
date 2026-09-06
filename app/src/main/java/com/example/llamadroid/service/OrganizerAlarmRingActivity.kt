package com.example.llamadroid.service

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OrganizerAlarmRingActivity : ComponentActivity() {
    private var alarmId: Long = -1L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LlamaApplication.updateLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        prepareAlarmWindow()

        setContent {
            val appearanceSettings = remember { SettingsRepository(applicationContext) }
            val themeMode by appearanceSettings.themeMode.collectAsState()
            val dynamicColor by appearanceSettings.dynamicColor.collectAsState()
            LlamaDroidTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                var alarm by remember { mutableStateOf<OrganizerAlarmEntity?>(null) }
                LaunchedEffect(alarmId) {
                    alarm = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(applicationContext).organizerDao().getAlarmById(alarmId)
                    }
                    alarm?.let { OrganizerAlarmRinger.start(applicationContext, it.id, it.soundEnabled) }
                }
                OrganizerAlarmRingScreen(
                    alarm = alarm,
                    onDismiss = { dismissAlarm() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
    }

    private fun dismissAlarm() {
        OrganizerAlarmRingingService.dismiss(applicationContext, alarmId)
        OrganizerAlarmRinger.stop(alarmId)
        UnifiedNotificationManager.cancelOrganizerAlarmNotification(alarmId)
        finishAndRemoveTask()
    }

    private fun prepareAlarmWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    companion object {
        private const val EXTRA_ALARM_ID = "extra_organizer_alarm_id"
        private const val REQUEST_CODE_BASE = 540_000

        fun start(context: Context, alarmId: Long) {
            runCatching {
                context.startActivity(buildIntent(context, alarmId))
            }.onFailure { error ->
                DebugLog.log("[OrganizerAlarm] ring activity start failed id=$alarmId: ${error.message}")
            }
        }

        fun pendingIntent(context: Context, alarmId: Long): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_BASE + alarmId.hashCode(),
                buildIntent(context, alarmId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun buildIntent(context: Context, alarmId: Long): Intent =
            Intent(context, OrganizerAlarmRingActivity::class.java).apply {
                action = "com.example.llamadroid.organizer.ALARM_RING"
                putExtra(EXTRA_ALARM_ID, alarmId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}

@Composable
private fun OrganizerAlarmRingScreen(
    alarm: OrganizerAlarmEntity?,
    onDismiss: () -> Unit
) {
    val defaultAlarmTitle = androidx.compose.ui.res.stringResource(R.string.organizer_alarm_notification_title)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.organizer_alarm_ringing_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = alarm?.title?.takeIf { it.isNotBlank() } ?: defaultAlarmTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                alarm?.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.organizer_alarm_dismiss))
                }
            }
        }
    }
}

internal object OrganizerAlarmRinger {
    private var currentAlarmId: Long = -1L
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context, alarmId: Long, soundEnabled: Boolean) {
        if (currentAlarmId == alarmId && (ringtone?.isPlaying == true || vibrator != null)) return
        stop()
        currentAlarmId = alarmId
        val appContext = context.applicationContext
        startVibration(appContext)
        if (soundEnabled) startSound(appContext)
    }

    fun stop(alarmId: Long? = null) {
        if (alarmId != null && currentAlarmId != -1L && currentAlarmId != alarmId) return
        runCatching {
            ringtone?.takeIf { it.isPlaying }?.stop()
        }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        currentAlarmId = -1L
    }

    private fun startSound(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        }.onFailure { error ->
            DebugLog.log("[OrganizerAlarm] ring sound failed: ${error.message}")
        }
    }

    private fun startVibration(context: Context) {
        runCatching {
            val pattern = longArrayOf(0L, 700L, 350L, 700L)
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val current = vibrator ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                current.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                current.vibrate(pattern, 0)
            }
        }.onFailure { error ->
            DebugLog.log("[OrganizerAlarm] ring vibration failed: ${error.message}")
        }
    }
}
