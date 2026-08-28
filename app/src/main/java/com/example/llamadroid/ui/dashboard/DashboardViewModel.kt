package com.example.llamadroid.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    // In real app, bind to service. For now assume we poll or observe static singleton
    // Bind to service state
    val serverState = LlamaService.state 
    
    init {
        viewModelScope.launch {
            systemMonitor.observeStats().collect {
                _stats.value = it
            }
        }
    }

    fun startServer(context: Context, modelPath: String? = null) {
        LlamaServerLauncher.start(context, modelPath)
    }

    fun stopServer(context: Context) {
        LlamaServerLauncher.stop(context)
    }

    fun recoverServer(context: Context) {
        LlamaServerLauncher.recover(context)
    }
}

internal fun dashboardHealthPort(configuredPort: Int, state: ServerState): Int =
    (state as? ServerState.Running)?.port ?: configuredPort

/** The dashboard observes runtime state; an HTTP response is never allowed to promote it. */
internal fun dashboardStateAfterHealthProbe(state: ServerState, healthCode: Int): ServerState = state

internal fun dashboardCanStartServer(state: ServerState): Boolean =
    state is ServerState.Stopped || state is ServerState.Error

internal fun dashboardCanStopServer(state: ServerState): Boolean =
    state is ServerState.Starting || state is ServerState.Loading || state is ServerState.Running

internal fun dashboardCanRecoverServer(state: ServerState): Boolean = state is ServerState.Error
