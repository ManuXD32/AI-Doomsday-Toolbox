package com.example.llamadroid.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.ServerState
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
                    val url = java.net.URL("http://127.0.0.1:8080/health")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 1000
                    connection.readTimeout = 1000
                    connection.requestMethod = "GET"
                    
                    val code = connection.responseCode
                    if (code == 200) {
                        // Silently update state - no logging to avoid spam
                        com.example.llamadroid.service.LlamaService.Companion.updateState(ServerState.Running(8080))
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
            val intent = android.content.Intent(context, LlamaService::class.java).apply {
                action = LlamaService.ACTION_START
                // If modelPath is null, service should handle it (e.g., use default or show error)
                putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath ?: "")
                putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)

                // Pass global speculative decoding settings
                if (settingsRepo.speculativeEnabled.value) {
                    val speculativeMode = settingsRepo.speculativeMode.value
                    val shouldPassDraftModel =
                        speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE ||
                            (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && settingsRepo.mtpUseDraftModel.value)
                    if (shouldPassDraftModel) {
                        putExtra(LlamaService.EXTRA_DRAFT_MODEL_PATH, settingsRepo.draftModelPath.value)
                    }
                    putExtra(LlamaService.EXTRA_DRAFT_MAX, settingsRepo.draftMaxTokens.value)
                    putExtra(LlamaService.EXTRA_DRAFT_MIN, settingsRepo.draftMinTokens.value)
                    putExtra(LlamaService.EXTRA_DRAFT_P_MIN, settingsRepo.draftPMin.value)
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
            DebugLog.log("Dashboard: Intent sent")
        } catch (e: Exception) {
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
