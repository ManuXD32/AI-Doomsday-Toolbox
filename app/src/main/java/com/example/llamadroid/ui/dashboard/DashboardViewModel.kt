package com.example.llamadroid.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.ui.ai.AiJobStartupDiagnostics
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.llamadroid.util.DebugLog

class DashboardViewModel(
    private val systemMonitor: SystemMonitor
    // private val llamaService: LlamaService (Using singleton/static for MVP or manual DI)
) : ViewModel() {

    private val _stats = MutableStateFlow(SystemStats(0, 0, 0f, 0f))
    val stats = _stats.asStateFlow()
    private val settingsRepo by lazy {
        SettingsRepository(LlamaApplication.instance.applicationContext)
    }
    
    // In real app, bind to service. For now assume we poll or observe static singleton
    // Bind to service state
    val serverState = LlamaService.state 
    
    init {
        viewModelScope.launch {
            systemMonitor.observeStats().collect {
                _stats.value = it
            }
        }
        startPolling()
    }
    
    private fun startPolling() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                try {
                    val port = dashboardHealthPort(settingsRepo.serverPort.value, LlamaService.state.value)
                    val url = java.net.URL("http://127.0.0.1:$port/health")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 1000
                    connection.readTimeout = 1000
                    connection.requestMethod = "GET"
                    
                    val code = connection.responseCode
                    if (code == 200) {
                        // Silently update state - no logging to avoid spam
                        LlamaService.updateState(ServerState.Running(port))
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    // Server unreachable - don't log to avoid spam
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    fun startServer(context: Context, modelPath: String? = null) {
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(context)
        try {
            DebugLog.log("Dashboard: Starting server...")
            AiJobStartupDiagnostics.record(context, "llama_server_start", "pre_launch_state")
            val serverHost = if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1"
            val serverPort = settingsRepo.serverPort.value
            val intent = android.content.Intent(context, LlamaService::class.java).apply {
                action = LlamaService.ACTION_START
                // If modelPath is null, service should handle it (e.g., use default or show error)
                putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath ?: "")
                putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
                putExtra(LlamaService.EXTRA_HOST, serverHost)
                putExtra(LlamaService.EXTRA_PORT, serverPort)

                // Pass global speculative decoding settings
                if (settingsRepo.speculativeEnabled.value) {
                    val speculativeMode = settingsRepo.speculativeMode.value
                    val shouldPassDraftModel =
                        speculativeMode.requiresDraftModel ||
                            (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && settingsRepo.mtpUseDraftModel.value)
                    if (shouldPassDraftModel) {
                        putExtra(LlamaService.EXTRA_DRAFT_MODEL_PATH, settingsRepo.draftModelPath.value)
                    }
                    putExtra(LlamaService.EXTRA_DRAFT_MAX, settingsRepo.draftMaxTokens.value)
                    putExtra(LlamaService.EXTRA_DRAFT_MIN, settingsRepo.draftMinTokens.value)
                    putExtra(LlamaService.EXTRA_DRAFT_P_MIN, settingsRepo.draftPMin.value)
                    putExtra(LlamaService.EXTRA_DRAFT_THREADS, settingsRepo.draftThreads.value)
                    putExtra(LlamaService.EXTRA_DRAFT_THREADS_BATCH, settingsRepo.draftThreadsBatch.value)
                }

                // Pass global flash attention setting
                putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
                
                // Pass custom flags and loaded command ID
                putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
                putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
                val loadedCmdId = settingsRepo.loadedCommandId.value
                if (loadedCmdId != -1L) {
                    // Just pass the ID as string so the service or UI knows what was loaded, 
                    // or just pass it as a generic tracking property if needed.
                    // For now, custom flags are what matters to the engine.
                }
            }
            context.startForegroundService(intent)
            AiJobStartupDiagnostics.record(context, "llama_server_start", "post_launch_state")
            DebugLog.log("Dashboard: Intent sent")
        } catch (e: Exception) {
            AiJobStartupDiagnostics.record(
                context,
                "llama_server_start",
                "launch_failed",
                "error=${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            )
            DebugLog.log("Dashboard: startServer FAILED: ${e.message}")
        }
    }

    fun stopServer(context: Context) {
        val intent = android.content.Intent(context, LlamaService::class.java).apply {
            action = "STOP"
        }
        context.startService(intent)
    }
}

internal fun dashboardHealthPort(configuredPort: Int, state: ServerState): Int =
    (state as? ServerState.Running)?.port ?: configuredPort
