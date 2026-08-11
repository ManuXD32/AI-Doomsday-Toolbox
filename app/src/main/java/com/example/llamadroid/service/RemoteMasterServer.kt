package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*

/**
 * Embedded HTTPS server for remote master control.
 * Allows a remote device to browse models, switch the active model,
 * and change speculative decoding settings on a running master.
 */
class RemoteMasterServer(
    private val context: Context,
    private val port: Int,
    private val passwordHash: String, // SHA-256 hex of the password
    private val whitelist: List<String> // Empty = allow all
) {
    
    private var serverSocket: SSLServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Rate limiting: track failed auth attempts per IP
    private val failedAuthAttempts = mutableMapOf<String, MutableList<Long>>()
    private val rateLimitLock = Any()
    
    companion object {
        private const val TAG = "RemoteMasterServer"
        private const val MAX_BODY_SIZE = 1 * 1024 * 1024 // 1 MB max request body
        private const val RATE_LIMIT_MAX_FAILURES = 5
        private const val RATE_LIMIT_BLOCK_MS = 5_000L // 5 second block
        private const val RATE_LIMIT_WINDOW_MS = 60_000L // Track failures within 60s
        
        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
    
    /**
     * Start the HTTPS server
     */
    fun start() {
        if (serverJob != null) {
            DebugLog.log("[$TAG] Server already running")
            return
        }
        
        serverJob = scope.launch {
            try {
                val sslContext = createSelfSignedSSLContext()
                val sf = sslContext.serverSocketFactory
                serverSocket = sf.createServerSocket() as SSLServerSocket
                serverSocket!!.reuseAddress = true
                serverSocket!!.bind(InetSocketAddress("0.0.0.0", port))
                
                DebugLog.log("[$TAG] HTTPS server started on port $port")
                DistributedService.addRemoteLog("Remote control server started on port $port")
                
                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept() as SSLSocket
                        launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            DebugLog.log("[$TAG] Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Server error: ${e.message}")
                DistributedService.addRemoteLog("Server error: ${e.message}")
            }
        }
    }
    
    /**
     * Stop the HTTPS server
     */
    fun stop() {
        DebugLog.log("[$TAG] Stopping server")
        DistributedService.addRemoteLog("Remote control server stopped")
        try {
            serverSocket?.close()
        } catch (e: Exception) { }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }
    
    /**
     * Handle a single client connection
     */
    private suspend fun handleClient(socket: SSLSocket) {
        try {
            socket.soTimeout = 10_000 // 10s timeout
            
            val clientIp = socket.inetAddress.hostAddress ?: "unknown"
            
            // IP whitelist check
            if (whitelist.isNotEmpty() && clientIp !in whitelist) {
                DebugLog.log("[$TAG] Rejected connection from non-whitelisted IP: $clientIp")
                DistributedService.addRemoteLog("⛔ Blocked: $clientIp (not in whitelist)")
                sendResponse(socket, 403, JSONObject().put("error", "IP not whitelisted"))
                socket.close()
                return
            }
            
            // Rate limiting check
            if (isRateLimited(clientIp)) {
                DebugLog.log("[$TAG] Rate-limited IP: $clientIp")
                DistributedService.addRemoteLog("⏳ Rate-limited: $clientIp (too many failed attempts)")
                sendResponse(socket, 429, JSONObject().put("error", "Too many failed attempts. Try again shortly."))
                socket.close()
                return
            }
            
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            
            // Parse HTTP request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }
            
            val method = parts[0]
            val path = parts[1]
            
            // Parse headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colonIndex = line!!.indexOf(':')
                if (colonIndex > 0) {
                    val key = line!!.substring(0, colonIndex).trim()
                    val value = line!!.substring(colonIndex + 1).trim()
                    headers[key] = value
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }
            
            // Auth check
            val token = headers["X-Auth-Token"] ?: headers["x-auth-token"] ?: ""
            if (token != passwordHash) {
                recordFailedAuth(clientIp)
                DebugLog.log("[$TAG] Auth failed from $clientIp")
                DistributedService.addRemoteLog("🔒 Auth failed: $clientIp")
                sendResponse(socket, 401, JSONObject().put("error", "Unauthorized"))
                socket.close()
                return
            }
            
            // Read body if present
            // Body size limit check
            var body = ""
            if (contentLength > MAX_BODY_SIZE) {
                DebugLog.log("[$TAG] Request body too large from $clientIp: $contentLength bytes")
                sendResponse(socket, 413, JSONObject().put("error", "Request body too large (max ${MAX_BODY_SIZE / 1024}KB)"))
                socket.close()
                return
            }
            if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(chars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                body = String(chars, 0, totalRead)
            }
            
            DebugLog.log("[$TAG] $method $path from $clientIp")
            
            // Route request
            val response = when {
                method == "GET" && path == "/models" -> handleGetModels()
                method == "GET" && path == "/status" -> handleGetStatus()
                method == "POST" && path == "/switch" -> handleSwitch(body, clientIp)
                method == "POST" && path == "/speculative" -> handleSpeculative(body, clientIp)
                method == "POST" && path == "/download" -> handleDownload(body, clientIp)
                method == "GET" && path == "/download-progress" -> handleDownloadProgress()
                method == "POST" && path == "/delete-model" -> handleDeleteModel(body, clientIp)
                method == "POST" && path == "/cancel-download" -> handleCancelDownload(body, clientIp)
                method == "GET" && path == "/workers" -> handleGetWorkers()
                method == "POST" && path == "/add-worker" -> handleAddWorker(body, clientIp)
                method == "POST" && path == "/remove-worker" -> handleRemoveWorker(body, clientIp)
                method == "POST" && path == "/toggle-worker" -> handleToggleWorker(body, clientIp)
                method == "POST" && path == "/stop" -> handleStopServer(clientIp)
                method == "POST" && path == "/restart" -> handleRestart(body, clientIp)
                method == "GET" && path == "/logs" -> handleGetLogs()
                method == "POST" && path == "/clear-logs" -> handleClearLogs()
                else -> Pair(404, JSONObject().put("error", "Not found"))
            }
            
            sendResponse(socket, response.first, response.second)
            socket.close()
            
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Client handler error: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
        }
    }
    
    /**
     * GET /models - List available models
     */
    private suspend fun handleGetModels(): Pair<Int, JSONObject> {
        val db = AppDatabase.getDatabase(context)
        val allModels = withContext(Dispatchers.IO) {
            db.modelDao().getModelsByTypesSync(
                listOf(ModelType.LLM, ModelType.VISION, ModelType.EMBEDDING)
            )
        }
        
        val modelList = allModels.filter { model ->
            val fileExists = java.io.File(model.path).exists()
            model.isDownloaded || fileExists
        }
        
        val jsonArray = JSONArray()
        modelList.forEach { model ->
            // Re-check real file size
            val realSize = java.io.File(model.path).let { if (it.exists()) it.length() else model.sizeBytes }
            jsonArray.put(JSONObject().apply {
                put("filename", model.filename)
                put("sizeBytes", realSize)
                put("sizeFormatted", formatBytes(realSize))
                put("path", model.path)
                put("type", model.type.name)
                put("layerCount", model.layerCount)
                put("isVision", model.isVision)
            })
        }
        
        return Pair(200, JSONObject().put("models", jsonArray))
    }
    
    /**
     * GET /status - Current server status
     */
    private fun handleGetStatus(): Pair<Int, JSONObject> {
        val lastParams = DistributedService.lastRunParams.value
        val serverState = LlamaService.state.value
        
        // Disk space info
        val modelDir = com.example.llamadroid.data.model.ModelRepository(context, AppDatabase.getDatabase(context).modelDao()).getModelDir(ModelType.LLM)
        val statFs = android.os.StatFs(modelDir.absolutePath)
        val availableBytes = statFs.availableBytes
        val totalBytes = statFs.totalBytes
        
        val json = JSONObject().apply {
            put("state", serverState.javaClass.simpleName)
            put("currentModel", lastParams["modelPath"] as? String ?: "none")
            put("threads", lastParams["threads"] as? Int ?: -1)
            put("batchSize", lastParams["batchSize"] as? Int ?: 512)
            put("contextSize", lastParams["contextSize"] as? Int ?: -1)
            put("temperature", lastParams["temperature"] as? Float ?: -1f)
            
            // Disk space
            put("availableSpaceBytes", availableBytes)
            put("totalSpaceBytes", totalBytes)
            put("availableSpaceFormatted", formatBytes(availableBytes))
            put("totalSpaceFormatted", formatBytes(totalBytes))
            
            // RAM Info
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val ramTotalMB = memInfo.totalMem / (1024 * 1024)
            val ramAvailMB = memInfo.availMem / (1024 * 1024)
            val ramUsedMB = ramTotalMB - ramAvailMB
            put("ramTotalMB", ramTotalMB)
            put("ramUsedMB", ramUsedMB)
            
            // KV Cache settings
            put("kvCacheEnabled", lastParams["kvCacheEnabled"] as? Boolean ?: true)
            put("kvCacheTypeK", lastParams["kvCacheTypeK"] as? String ?: "f16")
            put("kvCacheTypeV", lastParams["kvCacheTypeV"] as? String ?: "f16")
            put("kvCacheReuse", lastParams["kvCacheReuse"] as? Int ?: 0)
            
            // Speculative decoding info
            put("speculativeEnabled", lastParams["draftModelPath"] != null)
            put("draftModel", lastParams["draftModelPath"] as? String ?: "")
            put("draftMax", lastParams["draftMax"] as? Int ?: 3)
            put("draftMin", lastParams["draftMin"] as? Int ?: 0)
            put("draftPMin", lastParams["draftPMin"] as? Float ?: 0.0f)
            put("draftThreads", lastParams["draftThreads"] as? Int ?: 4)
            put("draftThreadsBatch", lastParams["draftThreadsBatch"] as? Int ?: 4)
            
            // Advanced Settings
            put("parallel", lastParams["parallel"] as? Int ?: -1)
            put("cacheRam", lastParams["cacheRam"] as? Int ?: -1)
            put("customFlags", lastParams["customFlags"] as? String ?: "")
            put("flashAttention", lastParams["flashAttention"] as? Boolean ?: false)
            
            // Launch command
            put("lastCommand", DistributedService.customCommand.value ?: DistributedService.lastCommand.value ?: "")
        }
        
        return Pair(200, json)
    }
    
    /**
     * POST /switch - Switch to a different model
     */
    private suspend fun handleSwitch(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val modelFilename = json.getString("model")
            
            // Verify model exists
            val db = AppDatabase.getDatabase(context)
            val model = withContext(Dispatchers.IO) {
                db.modelDao().getModelByFilename(modelFilename)
            }
            
            if (model == null) {
                return Pair(404, JSONObject().put("error", "Model not found: $modelFilename"))
            }
            
            if (!java.io.File(model.path).exists()) {
                return Pair(404, JSONObject().put("error", "Model file not available: $modelFilename"))
            }
            
            DistributedService.addRemoteLog("📡 Remote switch by $clientIp → ${model.filename}")
            
            // Store new model path and parameters in last run params
            val currentParams = DistributedService.lastRunParams.value.toMutableMap()
            currentParams["modelPath"] = model.path
            
            if (json.has("contextSize")) currentParams["contextSize"] = json.getInt("contextSize")
            if (json.has("batchSize")) currentParams["batchSize"] = json.getInt("batchSize")
            if (json.has("temperature")) currentParams["temperature"] = json.getDouble("temperature").toFloat()
            if (json.has("kvCacheEnabled")) currentParams["kvCacheEnabled"] = json.getBoolean("kvCacheEnabled")
            if (json.has("kvCacheTypeK")) currentParams["kvCacheTypeK"] = json.getString("kvCacheTypeK")
            if (json.has("kvCacheTypeV")) currentParams["kvCacheTypeV"] = json.getString("kvCacheTypeV")
            if (json.has("kvCacheReuse")) currentParams["kvCacheReuse"] = json.getInt("kvCacheReuse")
            
            if (json.has("parallel")) currentParams["parallel"] = json.getInt("parallel")
            if (json.has("cacheRam")) currentParams["cacheRam"] = json.getInt("cacheRam")
            if (json.has("customFlags")) currentParams["customFlags"] = json.getString("customFlags")
            if (json.has("flashAttention")) currentParams["flashAttention"] = json.getBoolean("flashAttention")

            DistributedService._lastRunParams.value = currentParams
            
            // Send switch intent to LlamaService
            val intent = Intent(context, LlamaService::class.java).apply {
                action = LlamaService.ACTION_SWITCH_MODEL
                putExtra(LlamaService.EXTRA_MODEL_PATH, model.path)
            }
            LlamaServerLauncher.dispatchOwnedCommand(context, intent).getOrThrow()
            
            return Pair(200, JSONObject().put("status", "switching").put("model", modelFilename))
            
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Switch error: ${e.message}")
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }

    /**
     * POST /restart - Restart the server with new settings or the current ones
     */
    private suspend fun handleRestart(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val modelFilename = json.optString("model", "")
            
            val db = AppDatabase.getDatabase(context)
            val model = if (modelFilename.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    db.modelDao().getModelByFilename(modelFilename)
                }
            } else {
                null
            }
            
            if (modelFilename.isNotEmpty() && model == null) {
                return Pair(404, JSONObject().put("error", "Model not found: $modelFilename"))
            }
            
            val currentParams = DistributedService.lastRunParams.value.toMutableMap()
            
            // If the server was never started, we fall back to the requested model. If there is no model, error.
            val modelPath = model?.path ?: currentParams["modelPath"] as? String
            
            if (modelPath.isNullOrEmpty()) {
                return Pair(400, JSONObject().put("error", "No model path available to start/restart with"))
            }
            
            DistributedService.addRemoteLog("📡 Remote restart by $clientIp")
            
            currentParams["modelPath"] = modelPath
            
            if (json.has("contextSize")) currentParams["contextSize"] = json.getInt("contextSize")
            if (json.has("batchSize")) currentParams["batchSize"] = json.getInt("batchSize")
            if (json.has("temperature")) currentParams["temperature"] = json.getDouble("temperature").toFloat()
            if (json.has("kvCacheEnabled")) currentParams["kvCacheEnabled"] = json.getBoolean("kvCacheEnabled")
            if (json.has("kvCacheTypeK")) currentParams["kvCacheTypeK"] = json.getString("kvCacheTypeK")
            if (json.has("kvCacheTypeV")) currentParams["kvCacheTypeV"] = json.getString("kvCacheTypeV")
            if (json.has("kvCacheReuse")) currentParams["kvCacheReuse"] = json.getInt("kvCacheReuse")
            
            if (json.has("parallel")) currentParams["parallel"] = json.getInt("parallel")
            if (json.has("cacheRam")) currentParams["cacheRam"] = json.getInt("cacheRam")
            if (json.has("customFlags")) currentParams["customFlags"] = json.getString("customFlags")
            if (json.has("flashAttention")) currentParams["flashAttention"] = json.getBoolean("flashAttention")

            DistributedService._lastRunParams.value = currentParams
            
            val intent = Intent(context, LlamaService::class.java).apply {
                action = LlamaService.ACTION_SWITCH_MODEL
                putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            }
            LlamaServerLauncher.dispatchOwnedCommand(context, intent).getOrThrow()
            
            return Pair(200, JSONObject().put("status", "restarting").put("model", modelFilename))
            
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Restart error: ${e.message}")
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * POST /speculative - Update speculative decoding settings
     */
    private suspend fun handleSpeculative(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val enabled = json.optBoolean("enabled", false)
            
            val currentParams = DistributedService.lastRunParams.value.toMutableMap()
            
            if (enabled) {
                val draftModelFilename = json.optString("draftModel", "")
                if (draftModelFilename.isNotEmpty()) {
                    // Verify draft model exists
                    val db = AppDatabase.getDatabase(context)
                    val draftModel = withContext(Dispatchers.IO) {
                        db.modelDao().getModelByFilename(draftModelFilename)
                    }
                    if (draftModel == null || !java.io.File(draftModel.path).exists()) {
                        return Pair(404, JSONObject().put("error", "Draft model not found: $draftModelFilename"))
                    }
                    currentParams["draftModelPath"] = draftModel.path
                }
                
                if (json.has("draftMax")) currentParams["draftMax"] = json.getInt("draftMax")
                if (json.has("draftMin")) currentParams["draftMin"] = json.getInt("draftMin")
                if (json.has("draftPMin")) currentParams["draftPMin"] = json.getDouble("draftPMin").toFloat()
                if (json.has("draftThreads")) currentParams["draftThreads"] = json.getInt("draftThreads")
                if (json.has("draftThreadsBatch")) currentParams["draftThreadsBatch"] = json.getInt("draftThreadsBatch")
                
                DistributedService.addRemoteLog("📡 Remote speculative update by $clientIp: enabled, draft=$draftModelFilename")
            } else {
                currentParams.remove("draftModelPath")
                currentParams.remove("draftMax")
                currentParams.remove("draftMin")
                currentParams.remove("draftPMin")
                currentParams.remove("draftThreads")
                currentParams.remove("draftThreadsBatch")
                DistributedService.addRemoteLog("📡 Remote speculative update by $clientIp: disabled")
            }
            
            DistributedService._lastRunParams.value = currentParams
            
            // Restart server with updated params
            val modelPath = currentParams["modelPath"] as? String
            if (modelPath != null) {
                val intent = Intent(context, LlamaService::class.java).apply {
                    action = LlamaService.ACTION_SWITCH_MODEL
                    putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
                }
                LlamaServerLauncher.dispatchOwnedCommand(context, intent).getOrThrow()
            }
            
            return Pair(200, JSONObject().put("status", "updating").put("speculative", enabled))
            
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Speculative error: ${e.message}")
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * POST /download - Download a model from a HuggingFace direct URL
     * Body: {"url": "https://huggingface.co/user/repo/resolve/main/model.gguf"}
     */
    private suspend fun handleDownload(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val url = json.getString("url")
            
            // Extract filename from URL
            val filename = url.substringAfterLast("/").substringBefore("?")
            if (filename.isBlank() || !filename.contains(".")) {
                return Pair(400, JSONObject().put("error", "Invalid URL: cannot extract filename"))
            }
            
            // Check for duplicate in DB
            val db = AppDatabase.getDatabase(context)
            val existing = withContext(Dispatchers.IO) {
                db.modelDao().getModelsByTypesSync(
                    listOf(ModelType.LLM, ModelType.VISION, ModelType.EMBEDDING)
                ).find { it.filename == filename }
            }
            if (existing != null) {
                return Pair(409, JSONObject().put("error", "Model '$filename' already exists"))
            }
            
            // Check if already downloading
            if (DistributedService.getRemoteDownload(filename) != null) {
                return Pair(409, JSONObject().put("error", "Already downloading '$filename'"))
            }
            
            // Get model directory
            val modelRepo = com.example.llamadroid.data.model.ModelRepository(context, AppDatabase.getDatabase(context).modelDao())
            val modelDir = modelRepo.getModelDir(ModelType.LLM)
            val destFile = java.io.File(modelDir, filename)
            
            // Initialize tracker
            DistributedService.updateRemoteDownload(filename, DistributedService.Companion.RemoteDownloadInfo(
                filename = filename,
                progress = 0f,
                status = "downloading"
            ))
            
            DistributedService.addRemoteLog("📥 Remote download started by $clientIp: $filename")
            
            // Launch download in background
            scope.launch(Dispatchers.IO) {
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                
                try {
                    com.example.llamadroid.util.Downloader.download(url, destFile, context)
                        .collect { progress ->
                            val now = System.currentTimeMillis()
                            // Actually compute from file content-length correctly
                            val totalSize = destFile.length().takeIf { progress >= 1f } ?: run {
                                if (progress > 0f) (destFile.length() / progress).toLong() else 0L
                            }
                            val bytesNow = destFile.length()
                            
                            // Speed calculation
                            val elapsed = now - lastTime
                            val speedBps = if (elapsed > 0) {
                                ((bytesNow - lastBytes) * 1000L / elapsed)
                            } else 0L
                            
                            if (elapsed > 500) {
                                lastTime = now
                                lastBytes = bytesNow
                            }
                            
                            DistributedService.updateRemoteDownload(filename, DistributedService.Companion.RemoteDownloadInfo(
                                filename = filename,
                                progress = progress,
                                bytesDownloaded = bytesNow,
                                totalBytes = totalSize,
                                speedBps = speedBps,
                                status = if (progress >= 1f) "complete" else "downloading"
                            ))
                            
                            if (progress >= 1f) {
                                // Import to database
                                val entity = ModelEntity(
                                    filename = filename,
                                    path = destFile.absolutePath,
                                    sizeBytes = destFile.length(),
                                    type = ModelType.LLM,
                                    repoId = "remote-download",
                                    isDownloaded = true
                                )
                                db.modelDao().insertModel(entity)
                                DistributedService.addRemoteLog("✅ Download complete: $filename (${formatBytes(destFile.length())})")
                                
                                // Parse layer count if GGUF
                                try {
                                    val modelInfo = com.example.llamadroid.util.GGUFParser.readModelInfo(destFile.absolutePath)
                                    if (modelInfo != null && modelInfo.layerCount > 0) {
                                        val updated = entity.copy(layerCount = modelInfo.layerCount)
                                        db.modelDao().insertModel(updated)
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                } catch (e: Exception) {
                    DebugLog.log("[$TAG] Download failed: ${e.message}")
                    DistributedService.updateRemoteDownload(filename, DistributedService.Companion.RemoteDownloadInfo(
                        filename = filename,
                        progress = -1f,
                        status = "error"
                    ))
                    DistributedService.addRemoteLog("❌ Download failed: $filename - ${e.message}")
                    // Clean up partial file
                    if (destFile.exists()) destFile.delete()
                }
            }
            
            return Pair(200, JSONObject()
                .put("status", "downloading")
                .put("filename", filename)
            )
            
        } catch (e: Exception) {
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * GET /download-progress - Get progress of all active remote downloads
     */
    private fun handleDownloadProgress(): Pair<Int, JSONObject> {
        val downloads = DistributedService.remoteDownloads.value
        val jsonArray = JSONArray()
        
        downloads.values.forEach { info ->
            jsonArray.put(JSONObject().apply {
                put("filename", info.filename)
                put("progress", info.progress.toDouble())
                put("bytesDownloaded", info.bytesDownloaded)
                put("totalBytes", info.totalBytes)
                put("speedBps", info.speedBps)
                put("speedFormatted", "${formatBytes(info.speedBps)}/s")
                put("status", info.status)
            })
        }
        
        return Pair(200, JSONObject().put("downloads", jsonArray))
    }
    
    /**
     * POST /delete-model - Delete a model from disk and database
     * Body: {"filename": "model.gguf"}
     */
    private suspend fun handleDeleteModel(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val filename = json.getString("filename")
            
            // Find model in DB
            val db = AppDatabase.getDatabase(context)
            val model = withContext(Dispatchers.IO) {
                db.modelDao().getModelsByTypesSync(
                    listOf(ModelType.LLM, ModelType.VISION, ModelType.EMBEDDING)
                ).find { it.filename == filename }
            } ?: return Pair(404, JSONObject().put("error", "Model '$filename' not found"))
            
            // Check if model is currently active
            val currentModel = DistributedService.lastRunParams.value["modelPath"] as? String
            if (currentModel == model.path) {
                return Pair(409, JSONObject().put("error", "Cannot delete active model. Switch to a different model first."))
            }
            
            // Delete file and DB entry
            val file = java.io.File(model.path)
            val sizeStr = formatBytes(if (file.exists()) file.length() else model.sizeBytes)
            withContext(Dispatchers.IO) {
                if (file.exists()) file.delete()
                db.modelDao().deleteModel(model)
            }
            
            DistributedService.addRemoteLog("🗑️ Model deleted by $clientIp: $filename ($sizeStr)")
            
            return Pair(200, JSONObject().put("status", "deleted").put("filename", filename))
            
        } catch (e: Exception) {
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * POST /cancel-download - Cancel an active remote download
     * Body: {"filename": "model.gguf"}
     */
    private fun handleCancelDownload(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val filename = json.getString("filename")
            
            val download = DistributedService.getRemoteDownload(filename)
                ?: return Pair(404, JSONObject().put("error", "No active download for '$filename'"))
            
            com.example.llamadroid.util.Downloader.cancelDownload(filename)
            DistributedService.updateRemoteDownload(filename, download.copy(status = "cancelled"))
            DistributedService.addRemoteLog("🚫 Download cancelled by $clientIp: $filename")
            
            // Clean up after a short delay
            scope.launch {
                delay(2000)
                DistributedService.removeRemoteDownload(filename)
                // Delete partial file
                val modelRepo = com.example.llamadroid.data.model.ModelRepository(context, AppDatabase.getDatabase(context).modelDao())
                val modelDir = modelRepo.getModelDir(ModelType.LLM)
                val partialFile = java.io.File(modelDir, filename)
                if (partialFile.exists()) partialFile.delete()
            }
            
            return Pair(200, JSONObject().put("status", "cancelled").put("filename", filename))
            
        } catch (e: Exception) {
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * Format bytes to human readable string
     */
    /**
     * GET /workers - List all configured workers
     */
    private fun handleGetWorkers(): Pair<Int, JSONObject> {
        val workers = DistributedService.workers.value
        val arr = org.json.JSONArray()
        for (w in workers) {
            arr.put(JSONObject().apply {
                put("ip", w.ip)
                put("port", w.port)
                put("deviceName", w.deviceName)
                put("availableRamMB", w.availableRamMB)
                put("isEnabled", w.isEnabled)
                put("isConnected", w.isConnected)
                put("assignedProportion", w.assignedProportion ?: -1f)
            })
        }
        return Pair(200, JSONObject().put("workers", arr))
    }
    
    /**
     * POST /add-worker - Add a new worker
     */
    private fun handleAddWorker(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val ip = json.getString("ip")
            val port = json.optInt("port", 50052)
            val deviceName = json.optString("deviceName", "Remote")
            val ramMB = json.optInt("ramMB", 4096)
            
            DistributedService.addWorkerManually(ip, port, deviceName, ramMB)
            DistributedService.addRemoteLog("📡 Worker added by $clientIp: $ip:$port")
            
            return Pair(200, JSONObject().put("status", "added").put("worker", "$ip:$port"))
        } catch (e: Exception) {
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * POST /remove-worker - Remove a worker
     */
    private fun handleRemoveWorker(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val ip = json.getString("ip")
            val port = json.optInt("port", 50052)
            
            DistributedService.removeWorker(ip, port)
            DistributedService.addRemoteLog("📡 Worker removed by $clientIp: $ip:$port")
            
            return Pair(200, JSONObject().put("status", "removed").put("worker", "$ip:$port"))
        } catch (e: Exception) {
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    /**
     * POST /toggle-worker - Enable/disable a worker
     */
    private fun handleToggleWorker(body: String, clientIp: String): Pair<Int, JSONObject> {
        try {
            val json = JSONObject(body)
            val ip = json.getString("ip")
            val port = json.optInt("port", 50052)
            val enabled = json.getBoolean("enabled")
            
            val workers = DistributedService.workers.value.toMutableList()
            val idx = workers.indexOfFirst { it.ip == ip && it.port == port }
            if (idx == -1) {
                return Pair(404, JSONObject().put("error", "Worker not found: $ip:$port"))
            }
            workers[idx] = workers[idx].copy(isEnabled = enabled)
            DistributedService._workers.value = workers
            
            val action = if (enabled) "enabled" else "disabled"
            DistributedService.addRemoteLog("📡 Worker $action by $clientIp: $ip:$port")
            
            return Pair(200, JSONObject().put("status", action).put("worker", "$ip:$port"))
        } catch (e: Exception) {
            return Pair(400, JSONObject().put("error", "Invalid request: ${e.message}"))
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
    
    /**
     * Send HTTP response over SSL socket
     */
    private fun sendResponse(socket: SSLSocket, statusCode: Int, json: JSONObject) {
        try {
            val body = json.toString()
            val statusText = when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                429 -> "Too Many Requests"
                else -> "Error"
            }
            
            val writer = PrintWriter(socket.outputStream, true)
            writer.print("HTTP/1.1 $statusCode $statusText\r\n")
            writer.print("Content-Type: application/json\r\n")
            writer.print("Content-Length: ${body.toByteArray().size}\r\n")
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.print(body)
            writer.flush()
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Send response error: ${e.message}")
        }
    }
    /**
     * POST /stop - Stop the master server
     */
    private fun handleStopServer(clientIp: String): Pair<Int, JSONObject> {
        DistributedService.addRemoteLog("📡 Remote stop by $clientIp")
        
        val intent = android.content.Intent(context, com.example.llamadroid.service.LlamaService::class.java).apply {
            action = com.example.llamadroid.service.LlamaService.ACTION_STOP
        }
        LlamaServerLauncher.dispatchOwnedCommand(context, intent).getOrThrow()
        return Pair(200, JSONObject().put("status", "stopping"))
    }
    
    /**
     * Rate limiting helpers
     */
    private fun isRateLimited(ip: String): Boolean {
        synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            val attempts = failedAuthAttempts[ip] ?: return false
            // Clean old entries outside the window
            attempts.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
            if (attempts.isEmpty()) {
                failedAuthAttempts.remove(ip)
                return false
            }
            // Check if blocked: ≥ MAX_FAILURES and last failure was within BLOCK_MS ago
            return attempts.size >= RATE_LIMIT_MAX_FAILURES && (now - attempts.last()) < RATE_LIMIT_BLOCK_MS
        }
    }
    
    private fun recordFailedAuth(ip: String) {
        synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            val attempts = failedAuthAttempts.getOrPut(ip) { mutableListOf() }
            attempts.add(now)
            // Clean old entries
            attempts.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
        }
    }
    
    /**
     * GET /logs - Get the recent server logs
     */
    private fun handleGetLogs(): Pair<Int, JSONObject> {
        val logsList = com.example.llamadroid.service.LlamaService.Companion.serverLogs.value
        val jsonArray = org.json.JSONArray()
        logsList.forEach { entry ->
            jsonArray.put(JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("message", entry.message)
            })
        }
        return Pair(200, JSONObject().put("logs", jsonArray))
    }
    
    /**
     * POST /clear-logs - Clear the server logs
     */
    private fun handleClearLogs(): Pair<Int, JSONObject> {
        com.example.llamadroid.service.LlamaService.Companion.clearServerLogs()
        return Pair(200, JSONObject().put("status", "cleared"))
    }
    
    /**
     * Create a self-signed SSL context for the HTTPS server
     */
    private fun createSelfSignedSSLContext(): SSLContext {
        // Generate a self-signed certificate using KeyPairGenerator
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Create self-signed X.509 certificate using Bouncy Castle-like approach
        // Since Android doesn't have sun.security.x509, we use a simpler approach
        // with a KeyStore and a programmatic certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        
        // Use Android's built-in certificate generation
        val certGenerator = SelfSignedCertGenerator.generate(keyPair)
        keyStore.setKeyEntry("server", keyPair.private, "password".toCharArray(), arrayOf(certGenerator))
        
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "password".toCharArray())
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        
        return sslContext
    }
}

/**
 * Helper to generate a self-signed X.509 certificate on Android
 * without requiring BouncyCastle or sun.security packages
 */
object SelfSignedCertGenerator {
    fun generate(keyPair: java.security.KeyPair): X509Certificate {
        // Use Android's hidden but available API for self-signed cert generation
        // This works on Android 4.0+ via the legacy sun.security.x509 compatibility layer
        // that's actually part of the Android runtime (conscrypt)
        
        val startDate = Date()
        val endDate = Date(startDate.time + 365L * 24 * 60 * 60 * 1000) // 1 year
        
        // Use reflection to access Android's certificate builder
        try {
            // Try using android.security.keystore or legacy approach
            val builderClass = Class.forName("com.android.org.bouncycastle.x509.X509V3CertificateGenerator")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            
            val setSerialNumber = builderClass.getMethod("setSerialNumber", java.math.BigInteger::class.java)
            setSerialNumber.invoke(builder, java.math.BigInteger.valueOf(System.currentTimeMillis()))
            
            val x500Class = Class.forName("com.android.org.bouncycastle.jce.X509Principal")
            val x500Name = x500Class.getConstructor(String::class.java).newInstance("CN=RemoteMaster,O=LlamaDroid")
            
            val setIssuerDN = builderClass.getMethod("setIssuerDN", java.security.Principal::class.java)
            setIssuerDN.invoke(builder, x500Name)
            
            val setSubjectDN = builderClass.getMethod("setSubjectDN", java.security.Principal::class.java)
            setSubjectDN.invoke(builder, x500Name)
            
            val setNotBefore = builderClass.getMethod("setNotBefore", Date::class.java)
            setNotBefore.invoke(builder, startDate)
            
            val setNotAfter = builderClass.getMethod("setNotAfter", Date::class.java)
            setNotAfter.invoke(builder, endDate)
            
            val setPublicKey = builderClass.getMethod("setPublicKey", java.security.PublicKey::class.java)
            setPublicKey.invoke(builder, keyPair.public)
            
            val setSignatureAlgorithm = builderClass.getMethod("setSignatureAlgorithm", String::class.java)
            setSignatureAlgorithm.invoke(builder, "SHA256WithRSAEncryption")
            
            val generateMethod = builderClass.getMethod("generate", java.security.PrivateKey::class.java)
            return generateMethod.invoke(builder, keyPair.private) as X509Certificate
            
        } catch (e: Exception) {
            DebugLog.log("[SelfSignedCertGenerator] Reflection approach failed: ${e.message}, using programmatic DER approach")
            // Fallback: Create certificate using raw DER encoding
            return createCertificateFromDER(keyPair, startDate, endDate)
        }
    }
    
    /**
     * Fallback: Build a minimal self-signed X.509 v3 certificate from raw DER bytes.
     * This is a minimal implementation that works without BouncyCastle.
     */
    private fun createCertificateFromDER(
        keyPair: java.security.KeyPair,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        // Use Android's CertificateFactory with a programmatically built DER structure
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        
        // Build TBS (To Be Signed) certificate structure
        val tbs = buildTBSCertificate(keyPair.public, notBefore, notAfter)
        sig.update(tbs)
        val signature = sig.sign()
        
        // Wrap in full certificate structure
        val certDer = buildFullCertificate(tbs, signature)
        
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(java.io.ByteArrayInputStream(certDer)) as X509Certificate
    }
    
    private fun buildTBSCertificate(publicKey: java.security.PublicKey, notBefore: Date, notAfter: Date): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        
        // Version: v3 (2)
        baos.write(derTag(0xA0, derInteger(2)))
        // Serial number
        baos.write(derInteger(System.currentTimeMillis()))
        // Signature algorithm: SHA256withRSA (OID 1.2.840.113549.1.1.11)
        baos.write(derSequence(derOID(byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) + derNull()))
        // Issuer: CN=RemoteMaster
        baos.write(derSequence(derSet(derSequence(derOID(byteArrayOf(0x55, 0x04, 0x03)) + derUTF8String("RemoteMaster")))))
        // Validity
        baos.write(derSequence(derUTCTime(notBefore) + derUTCTime(notAfter)))
        // Subject: CN=RemoteMaster
        baos.write(derSequence(derSet(derSequence(derOID(byteArrayOf(0x55, 0x04, 0x03)) + derUTF8String("RemoteMaster")))))
        // Subject Public Key Info (use encoded form from KeyPair)
        baos.write(publicKey.encoded)
        
        return derSequence(baos.toByteArray())
    }
    
    private fun buildFullCertificate(tbsCert: ByteArray, signature: ByteArray): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(tbsCert)
        // Signature algorithm: SHA256withRSA
        baos.write(derSequence(derOID(byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) + derNull()))
        // Signature value (bit string)
        baos.write(derBitString(signature))
        return derSequence(baos.toByteArray())
    }
    
    // DER encoding helpers
    private fun derSequence(content: ByteArray): ByteArray = derTLV(0x30, content)
    private fun derSet(content: ByteArray): ByteArray = derTLV(0x31, content)
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    
    private fun derInteger(value: Long): ByteArray {
        var v = value
        val bytes = mutableListOf<Byte>()
        do {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        } while (v != 0L)
        // Ensure positive encoding
        if (bytes[0].toInt() and 0x80 != 0) {
            bytes.add(0, 0)
        }
        return derTLV(0x02, bytes.toByteArray())
    }
    
    private fun derOID(oidBytes: ByteArray): ByteArray = derTLV(0x06, oidBytes)
    
    private fun derUTF8String(s: String): ByteArray = derTLV(0x0C, s.toByteArray(Charsets.UTF_8))
    
    private fun derBitString(data: ByteArray): ByteArray {
        val content = ByteArray(data.size + 1)
        content[0] = 0 // unused bits
        System.arraycopy(data, 0, content, 1, data.size)
        return derTLV(0x03, content)
    }
    
    private fun derUTCTime(date: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return derTLV(0x17, sdf.format(date).toByteArray(Charsets.US_ASCII))
    }
    
    private fun derTag(tag: Int, content: ByteArray): ByteArray = derTLV(tag, content)
    
    private fun derTLV(tag: Int, content: ByteArray): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(tag)
        val len = content.size
        if (len < 128) {
            baos.write(len)
        } else if (len < 256) {
            baos.write(0x81)
            baos.write(len)
        } else {
            baos.write(0x82)
            baos.write(len shr 8)
            baos.write(len and 0xFF)
        }
        baos.write(content)
        return baos.toByteArray()
    }
}
