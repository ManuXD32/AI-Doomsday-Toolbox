package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ZimEntity
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
 * Service that hosts an HTTP server for sharing ZIM files with other devices.
 * Similar to ModelShareService but for ZIM files (offline Wikipedia, etc.)
 */
class ZimShareService : Service() {
    
    companion object {
        private const val TAG = "ZimShareService"
        const val DEFAULT_PORT = 8087
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var server: ZimFileServer? = null
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
        fun getService(): ZimShareService = this@ZimShareService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.ZIM_SHARE,
            "ZIM File Sharing"
        )
        val started = ForegroundStart.start(this, taskId, notification, ForegroundServiceTypes.DATA_SYNC)
        if (started is ForegroundStart.Result.Failed) {
            UnifiedNotificationManager.failTask(taskId, ForegroundStart.failureMessage(this, started.reason, "ZIM File Sharing"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notificationTaskId = taskId
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
                
                val interfaces = getAllLocalIpAddresses()
                if (interfaces.isEmpty()) {
                    Log.e(TAG, "No network interfaces found")
                    return@launch
                }
                
                Log.i(TAG, "Starting ZIM file server on port $port...")
                server = ZimFileServer(applicationContext, port)
                server?.start()
                
                _isRunning.value = true
                _serverUrls.value = interfaces.map { (name, ip) -> name to "http://$ip:$port" }
                
                Log.i(TAG, "ZIM share server started. URLs: ${_serverUrls.value}")
                updateNotification("Sharing ZIM files on port $port")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}", e)
                stopServer()
            }
        }
    }
    
    /**
     * Stop the HTTP server.
     */
    fun stopServer() {
        Log.i(TAG, "Stopping ZIM share server...")
        
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
        
        server = null
        _isRunning.value = false
        _serverUrls.value = emptyList()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        
        Log.i(TAG, "Server stopped")
    }
    
    /**
     * Get all local IP addresses from all network interfaces.
     */
    private fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("169.254")) {
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
     * Internal HTTP server for serving ZIM files.
     */
    private inner class ZimFileServer(
        private val context: Context,
        port: Int
    ) : NanoHTTPD(port) {
        
        private val db = AppDatabase.getDatabase(context)
        private val df = DecimalFormat("#.##")
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "Request: $uri")
            
            return when {
                uri == "/" || uri == "/index.html" -> serveZimList()
                uri.startsWith("/download/") -> serveZimFile(uri.removePrefix("/download/"))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
        
        private fun serveZimList(): Response {
            val zims = runBlocking { 
                db.zimDao().getAllZims().first().filter { 
                    java.io.File(it.path).exists()
                }
            }
            
            val html = buildString {
                appendLine("<!DOCTYPE html><html><head>")
                appendLine("<meta charset='UTF-8'>")
                appendLine("<meta name='viewport' content='width=device-width, initial-scale=1'>")
                appendLine("<title>Doomsday AI Toolbox - ZIM Files</title>")
                appendLine("<style>")
                appendLine("body { font-family: -apple-system, sans-serif; padding: 20px; background: #1a1a1a; color: #fff; }")
                appendLine("h1 { color: #4CAF50; }")
                appendLine(".zim { background: #2d2d2d; padding: 15px; margin: 10px 0; border-radius: 8px; }")
                appendLine(".zim a { color: #64B5F6; text-decoration: none; font-size: 18px; }")
                appendLine(".zim a:hover { text-decoration: underline; }")
                appendLine(".zim .size { color: #888; font-size: 14px; margin-top: 5px; }")
                appendLine(".zim .desc { color: #aaa; font-size: 13px; margin-top: 5px; }")
                appendLine(".zim .lang { color: #FFA726; font-size: 12px; margin-top: 5px; }")
                appendLine("</style></head><body>")
                appendLine("<h1>📚 ZIM Library</h1>")
                appendLine("<p>Offline Wikipedia & Knowledge Files - Click to download:</p>")
                
                if (zims.isEmpty()) {
                    appendLine("<p>No ZIM files available.</p>")
                } else {
                    zims.forEach { zim ->
                        val sizeMB = zim.sizeBytes / (1024.0 * 1024.0)
                        val sizeStr = if (sizeMB >= 1024) {
                            "${df.format(sizeMB / 1024)} GB"
                        } else {
                            "${df.format(sizeMB)} MB"
                        }
                        
                        appendLine("<div class='zim'>")
                        appendLine("<a href='/download/${zim.filename}'>${zim.title}</a>")
                        appendLine("<div class='size'>📦 $sizeStr</div>")
                        if (zim.description.isNotEmpty()) {
                            appendLine("<div class='desc'>${zim.description.take(100)}${if (zim.description.length > 100) "..." else ""}</div>")
                        }
                        appendLine("<div class='lang'>🌐 ${zim.language.uppercase()}</div>")
                        appendLine("</div>")
                    }
                }
                
                appendLine("<hr style='border-color: #333; margin-top: 30px;'>")
                appendLine("<p style='color: #666; font-size: 12px;'>Doomsday AI Toolbox - ZIM File Server</p>")
                appendLine("</body></html>")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        private fun serveZimFile(filename: String): Response {
            val zim = runBlocking {
                db.zimDao().getAllZims().first().find { it.filename == filename }
            }
            
            if (zim == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "ZIM not found")
            }
            
            val file = File(zim.path)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
            
            Log.i(TAG, "Serving: ${zim.filename} (${file.length()} bytes)")
            _activeDownloads.value++
            
            try {
                val inputStream = FileInputStream(file)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/octet-stream",
                    inputStream,
                    file.length()
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"${zim.filename}\"")
                return response
            } finally {
                serviceScope.launch {
                    delay(1000)
                    _activeDownloads.value = (_activeDownloads.value - 1).coerceAtLeast(0)
                }
            }
        }
    }
}
