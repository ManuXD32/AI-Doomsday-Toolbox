package com.example.llamadroid.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        LlamaServerLauncher.start(context, modelPath)
    }

    fun stopServer(context: Context) {
        LlamaServerLauncher.stop(context)
    }
}

internal fun dashboardHealthPort(configuredPort: Int, state: ServerState): Int =
    (state as? ServerState.Running)?.port ?: configuredPort
