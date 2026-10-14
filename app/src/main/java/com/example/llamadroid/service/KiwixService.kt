package com.example.llamadroid.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Service for running kiwix-serve to serve ZIM files over HTTP.
 * Provides a WebView-accessible endpoint for browsing offline content.
 */
class KiwixService : Service() {
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var kiwixProcess: Process? = null
    private var notificationTaskId: Int? = null
    private lateinit var settingsRepo: SettingsRepository
    
    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _serverPort = MutableStateFlow(AIConstants.Ports.KIWIX)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()
    
    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()
    
    private val _loadedZims = MutableStateFlow<List<String>>(emptyList())
    val loadedZims: StateFlow<List<String>> = _loadedZims.asStateFlow()
    
    inner class LocalBinder : Binder() {
        fun getService(): KiwixService = this@KiwixService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.FILE_SERVER,
            "Kiwix Server"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        
        return START_NOT_STICKY
    }
    
    /**
     * Start kiwix-serve with the given ZIM files
     */
    fun startServer(zimPaths: List<String>, port: Int = AIConstants.Ports.KIWIX) {
        if (_isRunning.value) {
            DebugLog.log("[KIWIX] Server already running")
            return
        }
        
        serviceScope.launch {
            try {
                // Acquire wake lock for long-running server
                WakeLockManager.acquire(applicationContext, "KiwixService")
                
                val validZims = zimPaths.filter { File(it).exists() }
                if (validZims.isEmpty()) {
                    DebugLog.log("[KIWIX] No valid ZIM files provided")
                    WakeLockManager.release("KiwixService")
                    return@launch
                }
                
                _serverPort.value = port
                _loadedZims.value = validZims
                
                // Get kiwix-serve binary
                val binaryRepo = BinaryRepository(applicationContext)
                val kiwixServe = binaryRepo.getKiwixServeBinary()
                
                if (kiwixServe == null || !kiwixServe.exists()) {
                    DebugLog.log("[KIWIX] kiwix-serve binary not found")
                    updateNotification("Binary not found")
                    WakeLockManager.release("KiwixService")
                    return@launch
                }
                
                // Check if LAN access is enabled
                // kiwix-serve: use --address=all for all interfaces, or omit for localhost only
                val remoteAccess = settingsRepo.kiwixRemoteAccess.value
                
                // Create library XML file using kiwix-manage
                val libraryFile = File(applicationContext.filesDir, "library.xml")
                val kiwixManage = binaryRepo.getKiwixManageBinary()
                
                if (kiwixManage != null && kiwixManage.exists()) {
                    // Delete old library file
                    libraryFile.delete()
                    
                    // Add each ZIM to library
                    for (zimPath in validZims) {
                        val manageArgs = listOf(
                            kiwixManage.absolutePath,
                            libraryFile.absolutePath,
                            "add",
                            zimPath
                        )
                        DebugLog.log("[KIWIX] Adding ZIM to library: $zimPath")
                        val process = ProcessBuilder(manageArgs)
                            .redirectErrorStream(true)
                            .start()
                        process.waitFor()
                        
                        // Log output
                        process.inputStream.bufferedReader().readText().let {
                            if (it.isNotBlank()) DebugLog.log("[KIWIX] kiwix-manage: $it")
                        }
                    }
                }
                
                // Build serve command - always bind to all interfaces for LAN access
                val args = mutableListOf(
                    kiwixServe.absolutePath,
                    "--port=$port",
                    "--address=all",  // Always allow LAN access
                    "--threads=4",
                    "--verbose"
                )
                
                // Use library mode if library was created
                if (libraryFile.exists()) {
                    args.add("--library")
                    args.add(libraryFile.absolutePath)
                    DebugLog.log("[KIWIX] Using library mode: ${libraryFile.absolutePath}")
                } else {
                    // Fallback to direct ZIM paths
                    args.addAll(validZims)
                }
                
                DebugLog.log("[KIWIX] Starting server: ${args.joinToString(" ")}")
                
                val pb = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .directory(kiwixServe.parentFile)
                
                // Set library path
                pb.environment()["LD_LIBRARY_PATH"] = binaryRepo.getLibraryDir()
                
                kiwixProcess = pb.start()
                _isRunning.value = true
                
                // Server URL - always use localhost for user access
                _serverUrl.value = "http://127.0.0.1:$port"
                
                val accessType = if (remoteAccess) "LAN" else "local"
                updateNotification("Running on port $port ($accessType) - ${validZims.size} ZIMs")
                
                // Read output in background
                val reader = kiwixProcess!!.inputStream.bufferedReader()
                var line: String? = reader.readLine()
                while (line != null && _isRunning.value) {
                    DebugLog.log("[KIWIX] $line")
                    line = reader.readLine()
                }
                
                // Process ended
                _isRunning.value = false
                _serverUrl.value = null
                DebugLog.log("[KIWIX] Server stopped")
                updateNotification("Stopped")
                WakeLockManager.release("KiwixService")
                
            } catch (e: Exception) {
                DebugLog.log("[KIWIX] Error starting server: ${e.message}")
                _isRunning.value = false
                _serverUrl.value = null
                updateNotification("Error: ${e.message}")
                WakeLockManager.release("KiwixService")
            }
        }
    }
    
    /**
     * Stop the kiwix-serve process
     */
    fun stopServer() {
        kiwixProcess?.destroy()
        kiwixProcess = null
        _isRunning.value = false
        _serverUrl.value = null
        _loadedZims.value = emptyList()
        DebugLog.log("[KIWIX] Server stopped")
        updateNotification("Stopped")
        WakeLockManager.release("KiwixService")
    }
    
    private fun updateNotification(text: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 0f, text)
        }
    }
    
    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }
    
    companion object {
        const val DEFAULT_PORT = AIConstants.Ports.KIWIX
    }
}
