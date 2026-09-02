package com.example.llamadroid.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

class DashboardViewModel(
    private val systemMonitor: SystemMonitor
    // private val llamaService: LlamaService (Using singleton/static for MVP or manual DI)
) : ViewModel() {

    /**
     * Keep the monitor cold until the route is actually visible.
     *
     * The old implementation collected in [viewModelScope]. Because the dashboard used to
     * construct the ViewModel with `remember`, every visit left another `/proc` and sysfs
     * sampler behind. Lifecycle-aware Compose collection now starts/stops this WhileSubscribed
     * flow with the dashboard route instead.
     */
    val stats: StateFlow<SystemStats> = systemMonitor.observeStats()
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = SystemStats(0, 0, 0f, 0f)
        )

    // Bind to service state. LlamaService owns the process lifecycle.
    val serverState = LlamaService.state 

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

class DashboardViewModelFactory(
    private val systemMonitor: SystemMonitor
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(systemMonitor) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
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
