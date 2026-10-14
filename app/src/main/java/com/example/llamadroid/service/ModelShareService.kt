package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DecimalFormat

/**
 * Service that hosts an HTTP server for sharing AI models with other devices.
 */
class ModelShareService : Service() {
    
    companion object {
        private const val TAG = "ModelShareService"
        const val DEFAULT_PORT = 8085
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var server: ModelFileServer? = null
    private var currentPort = DEFAULT_PORT
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private var notificationTaskId: Int? = null
    
    // Multiple URLs for each network interface
    private val _serverUrls = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // (interfaceName, url)
    val serverUrls: StateFlow<List<Pair<String, String>>> = _serverUrls
    
    private val _activeDownloads = MutableStateFlow(0)
    val activeDownloads: StateFlow<Int> = _activeDownloads
    
    inner class LocalBinder : Binder() {
        fun getService(): ModelShareService = this@ModelShareService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.MODEL_SHARE,
            "Model Sharing"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        return START_STICKY
    }
    
    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        stopServer()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }
    
    /**
     * Start the HTTP server on the specified port.
     */
    fun startServer(port: Int = DEFAULT_PORT) {
        Log.i(TAG, "startServer called, currently running: ${_isRunning.value}")
        
        if (_isRunning.value) {
            Log.w(TAG, "Server already running")
            return
        }
        
        serviceScope.launch {
            try {
                currentPort = port
                Log.i(TAG, "Getting all network interfaces...")
                val allIps = getAllLocalIpAddresses()
                
                if (allIps.isEmpty()) {
                    Log.e(TAG, "No network interfaces found - are you connected to WiFi?")
                    withContext(Dispatchers.Main) {
                        updateNotification("Error: No network connection")
                    }
                    return@launch
                }
                
                Log.i(TAG, "Found ${allIps.size} network interfaces, starting server on port $port...")
                
                server = ModelFileServer(this@ModelShareService, port).apply {
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                
                _isRunning.value = true
                _serverUrls.value = allIps.map { (ifName, ip) -> 
                    Pair(ifName, "http://$ip:$port")
                }
                
                Log.i(TAG, "Server started successfully on ${allIps.size} interfaces")
                allIps.forEach { (ifName, ip) -> 
                    Log.i(TAG, "  - $ifName: http://$ip:$port")
                }
                
                withContext(Dispatchers.Main) {
                    val primaryUrl = _serverUrls.value.firstOrNull()?.second ?: "unknown"
                    updateNotification("Sharing at $primaryUrl${if (allIps.size > 1) " (+${allIps.size - 1} more)" else ""}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}", e)
                e.printStackTrace()
                _isRunning.value = false
                _serverUrls.value = emptyList()
                withContext(Dispatchers.Main) {
                    updateNotification("Error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop the HTTP server.
     */
    fun stopServer() {
        server?.stop()
        server = null
        _isRunning.value = false
        _serverUrls.value = emptyList()
        Log.i(TAG, "Server stopped")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
    }
    
    /**
     * Get all local IP addresses from all network interfaces.
     * Returns list of (interfaceName, ipAddress) pairs.
     */
    private fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // Only IPv4, non-loopback addresses
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        // Skip link-local addresses (169.254.x.x)
                        if (ip != null && !ip.startsWith("169.254")) {
                            // Use friendly name for common interfaces
                            val friendlyName = when {
                                networkInterface.name.startsWith("wlan") -> "WiFi"
                                networkInterface.name.startsWith("eth") -> "Ethernet"
                                networkInterface.name.startsWith("tun") -> "VPN"
                                networkInterface.name.startsWith("rmnet") -> "Mobile"
                                else -> networkInterface.name
                            }
                            result.add(Pair(friendlyName, ip))
                            Log.i(TAG, "Found interface: $friendlyName ($ip)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IPs: ${e.message}", e)
        }
        
        return result
    }
    
    private fun updateNotification(message: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 1f, message)
        }
    }
    
    /**
     * Internal HTTP server for serving model files.
     */
    private inner class ModelFileServer(
        private val context: Context,
        port: Int
    ) : NanoHTTPD(port) {
        
        private val db = AppDatabase.getDatabase(context)
        private val df = DecimalFormat("#.##")
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "Request: $uri")
            
            return when {
                uri == "/" || uri == "/index.html" -> serveModelList()
                uri.startsWith("/download/") -> serveModelFile(uri.removePrefix("/download/"))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
        
        private fun serveModelList(): Response {
            val models = runBlocking { 
                db.modelDao().getAllModels().first().filter { 
                    java.io.File(it.path).exists()
                }
            }
            
            val html = buildString {
                appendLine("<!DOCTYPE html><html><head>")
                appendLine("<meta charset='UTF-8'>")
                appendLine("<meta name='viewport' content='width=device-width, initial-scale=1'>")
                appendLine("<title>LlamaDroid Models</title>")
                appendLine("<style>")
                appendLine("body { font-family: -apple-system, sans-serif; padding: 20px; background: #1a1a1a; color: #fff; }")
                appendLine("h1 { color: #4CAF50; }")
                appendLine(".model { background: #2d2d2d; padding: 15px; margin: 10px 0; border-radius: 8px; }")
                appendLine(".model a { color: #64B5F6; text-decoration: none; font-size: 18px; }")
                appendLine(".model .size { color: #888; font-size: 14px; }")
                appendLine(".model .type { color: #FFA726; font-size: 12px; margin-top: 5px; }")
                appendLine("</style></head><body>")
                appendLine("<h1>📲 LlamaDroid Models</h1>")
                appendLine("<p>Available for download:</p>")
                
                if (models.isEmpty()) {
                    appendLine("<p>No models available.</p>")
                } else {
                    models.forEach { model ->
                        val sizeMB = model.sizeBytes / (1024.0 * 1024.0)
                        val sizeStr = if (sizeMB >= 1024) {
                            "${df.format(sizeMB / 1024)} GB"
                        } else {
                            "${df.format(sizeMB)} MB"
                        }
                        
                        appendLine("<div class='model'>")
                        appendLine("<a href='/download/${model.filename}'>${model.filename}</a>")
                        appendLine("<div class='size'>$sizeStr</div>")
                        appendLine("<div class='type'>${model.type}</div>")
                        appendLine("</div>")
                    }
                }
                
                appendLine("</body></html>")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        private fun serveModelFile(filename: String): Response {
            val model = runBlocking {
                db.modelDao().getModelByFilename(filename)
            }
            
            if (model == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Model not found")
            }
            
            val file = File(model.path)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
            
            Log.i(TAG, "Serving: ${model.filename} (${file.length()} bytes)")
            _activeDownloads.value++
            
            try {
                val inputStream = FileInputStream(file)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/octet-stream",
                    inputStream,
                    file.length()
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"${model.filename}\"")
                return response
            } finally {
                // Note: NanoHTTPD will close the stream after transfer
                serviceScope.launch {
                    delay(1000) // Give time for download to start
                    _activeDownloads.value = (_activeDownloads.value - 1).coerceAtLeast(0)
                }
            }
        }
    }
}
