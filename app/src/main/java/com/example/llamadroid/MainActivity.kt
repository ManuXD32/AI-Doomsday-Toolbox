package com.example.llamadroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.coroutineScope
import com.example.llamadroid.util.AppVersionCodeCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AiRuntimeJobStore
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.DatasetForegroundService
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.util.UpscalerAssetPackSupport
import com.example.llamadroid.util.getParcelableExtraCompat
import com.example.llamadroid.ui.navigation.ExternalRouteResolver
import com.example.llamadroid.ui.navigation.ExternalRouteResolution

/**
 * Shared file data from intent
 */
data class SharedFileData(
    val uri: Uri,
    val mimeType: String
)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_ROUTE = "extra_open_route"
        private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    }
    
    // Share intent data
    private val sharedFileData = mutableStateOf<SharedFileData?>(null)
    private val pendingNavigationRoute = mutableStateOf<ExternalRouteResolution>(
        ExternalRouteResolution.NoRoute
    )
    private val supportPromptAllowed = mutableStateOf(false)
    private val isDeployingBinaries = mutableStateOf(true)
    private val deploymentStatusId = mutableStateOf(R.string.deployment_adjusting)
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result - notifications will work if granted
    }
    
    /**
     * Override to apply locale setting to the Activity context
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LlamaApplication.updateLocale(newBase))
    }

    private fun reconcileAgentJobsAfterCrash(hadActiveGeneration: Boolean) {
        if (!hadActiveGeneration) return
        lifecycle.coroutineScope.launch(Dispatchers.IO) {
            val appContext = applicationContext
            runCatching {
                val db = AppDatabase.getDatabase(appContext)
                db.aiRuntimeJobDao()
                    .getActiveJobs()
                    .filter { it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT }
                    .forEach { job ->
                        db.aiRuntimeJobDao().updateState(
                            jobId = job.jobId,
                            status = AiRuntimeJobStore.STATUS_RECOVERING,
                            checkpointJson = job.checkpointJson,
                            progressText = getString(R.string.agent_status_interrupted),
                            errorMessage = getString(R.string.agent_resume_reason_interrupted),
                            updatedAt = System.currentTimeMillis()
                        )
                        job.conversationId?.let { conversationId ->
                            db.agentChatDao().updateResumeState(
                                conversationId,
                                AgentService.RESUME_STATE_INTERRUPTED,
                                getString(R.string.agent_resume_reason_interrupted)
                            )
                        }
                    }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val preferences = getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        val currentVersionCode = appVersionCode()
        val previousVersionCode = preferences.getLong(KEY_LAST_SEEN_VERSION_CODE, 0L)
        val postUpdateLaunch = AppStartupDeploymentPolicy.shouldSkipDeploymentAfterUpdate(
            previousVersionCode = previousVersionCode,
            currentVersionCode = currentVersionCode
        )

        isDeployingBinaries.value = false

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start binary deployment lazily so the app can render immediately after updates.
        lifecycle.coroutineScope.launch(Dispatchers.IO) {
            val repo = com.example.llamadroid.data.binary.BinaryRepository(this@MainActivity)
            val shouldDeferProvisioning = postUpdateLaunch
            val delayMs = if (shouldDeferProvisioning) 10_000L else 0L

            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "main_activity",
                event = "startup_provisioning_scheduled",
                details = "defer=$shouldDeferProvisioning delayMs=$delayMs previousVersionCode=$previousVersionCode currentVersionCode=$currentVersionCode"
            )

            if (delayMs > 0L) {
                delay(delayMs)
            }

            runCatching {
                UpscalerAssetPackSupport.cleanupRetainedPackIfPresent(this@MainActivity)
                    .onFailure { error ->
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = "main_activity",
                            event = "startup_upscaler_pack_cleanup_failed",
                            details = "${error.javaClass.simpleName}: ${error.message}"
                        )
                    }

                val binariesReady = repo.deployAllBinaries()
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "main_activity",
                    event = "startup_provisioning_checked",
                    details = "binariesReady=$binariesReady assetPacksDeferred=true"
                )

                if (BuildConfig.IS_FAT_APK_BUILD && !binariesReady) {
                    android.util.Log.e(
                        "MainActivity",
                        "Fat APK build is missing bundled components. binariesReady=$binariesReady"
                    )
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = "main_activity",
                        event = "startup_provisioning_missing_components",
                        details = "binariesReady=$binariesReady"
                    )
                    return@runCatching
                }

                if (!binariesReady) {
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = "main_activity",
                        event = "startup_feature_install_requested",
                        details = "reason=binaries_missing"
                    )
                    com.example.llamadroid.util.DynamicFeatureManager.installAllFeatures(this@MainActivity)
                }

                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "main_activity",
                    event = "startup_provisioning_finished",
                    details = "defer=$shouldDeferProvisioning optional_modules=manual_only"
                )
            }.onFailure { error ->
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "main_activity",
                    event = "startup_provisioning_failed",
                    details = "${error.javaClass.simpleName}: ${error.message}"
                )
                android.util.Log.e("MainActivity", "Deferred startup provisioning failed", error)
            }

            preferences.edit().putLong(KEY_LAST_SEEN_VERSION_CODE, currentVersionCode).apply()
        }
        
        // Handle share intent
        supportPromptAllowed.value = isNormalAppLaunch(intent)
        handleShareIntent(intent)
        pendingNavigationRoute.value = extractNavigationRoute(intent)

        GenerationDiagnosticsStore.consumePendingRelaunchWarning()?.let { exitSnapshot ->
            reconcileAgentJobsAfterCrash(exitSnapshot.hadActiveGeneration)
            Toast.makeText(
                this,
                getString(R.string.generation_diag_relaunch_warning),
                Toast.LENGTH_LONG
            ).show()
        }
        
        setContent {
            val appearanceSettings = remember { SettingsRepository(applicationContext) }
            val themeMode = appearanceSettings.themeMode.collectAsState().value
            val dynamicColor = appearanceSettings.dynamicColor.collectAsState().value
            LlamaDroidTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LlamaApp(
                        allowDailySupportPrompt = supportPromptAllowed.value,
                        sharedFileData = sharedFileData.value,
                        onSharedFileHandled = { sharedFileData.value = null },
                        pendingNavigationRoute = pendingNavigationRoute.value,
                        onNavigationHandled = {
                            pendingNavigationRoute.value = ExternalRouteResolution.NoRoute
                        }
                    )
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        supportPromptAllowed.value = isNormalAppLaunch(intent)
        handleShareIntent(intent)
        pendingNavigationRoute.value = extractNavigationRoute(intent)
    }

    override fun onStart() {
        super.onStart()
        DatasetForegroundService.requestResume(this)
        lifecycle.coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                TamaNotificationScheduler.retryPendingDeepDreamForActivePet(this@MainActivity)
            }.onFailure { error ->
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "main_activity",
                    event = "deep_dream_pending_retry_failed",
                    details = "${error.javaClass.simpleName}: ${error.message}"
                )
            }
        }
    }
    
    private fun isNormalAppLaunch(intent: Intent?): Boolean =
        (intent?.action == null || intent.action == Intent.ACTION_MAIN) &&
            intent?.hasExtra(EXTRA_OPEN_ROUTE) != true

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
            val mimeType = intent.type ?: ""
            
            if (uri != null && mimeType.isNotEmpty()) {
                sharedFileData.value = SharedFileData(uri, mimeType)
            }
        }
    }

    private fun extractNavigationRoute(intent: Intent?): ExternalRouteResolution {
        // External launchers (widgets, notifications and old shortcuts) can outlive the
        // destination they were created for. Resolve at the Activity boundary so malformed or
        // stale values never reach NavController.navigate().
        if (intent?.hasExtra(EXTRA_OPEN_ROUTE) != true) {
            return ExternalRouteResolution.NoRoute
        }
        val rawRoute = runCatching { intent.getStringExtra(EXTRA_OPEN_ROUTE) }
            .getOrElse { return ExternalRouteResolution.Rejected }
        return ExternalRouteResolver.resolve(rawRoute)
    }

    private fun appVersionCode(): Long {
        return AppVersionCodeCompat.read(packageManager.getPackageInfo(packageName, 0))
    }

}
