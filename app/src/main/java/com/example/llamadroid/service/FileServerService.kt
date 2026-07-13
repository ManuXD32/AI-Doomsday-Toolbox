package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.util.WakeLockManager

/**
 * Service that hosts an HTTP file server for sharing files from a user-selected folder.
 * Uses NanoHTTPD for lightweight HTTP serving.
 */
class FileServerService : Service() {
    
    companion object {
        private const val TAG = "FileServerService"
        const val DEFAULT_PORT = AIConstants.Ports.FILE_SERVER
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var server: FileServer? = null
    private var currentPort = DEFAULT_PORT
    private var folderUri: Uri? = null
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private var notificationTaskId: Int? = null
    
    private val _serverUrls = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // (interfaceName, url)
    val serverUrls: StateFlow<List<Pair<String, String>>> = _serverUrls
    
    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.FILE_SERVER,
            "File Server"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        return START_STICKY
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
     * Start the file server with the specified folder and port.
     */
    fun startServer(folderUri: Uri, port: Int = DEFAULT_PORT) {
        Log.i(TAG, "startServer called with folder: $folderUri")
        
        if (_isRunning.value) {
            Log.w(TAG, "Server already running")
            return
        }
        
        this.folderUri = folderUri
        
        serviceScope.launch {
            try {
                currentPort = port
                val allIps = getAllLocalIpAddresses()
                
                if (allIps.isEmpty()) {
                    Log.e(TAG, "No network interfaces found")
                    withContext(Dispatchers.Main) {
                        updateNotification("Error: No network connection")
                    }
                    return@launch
                }
                
                server = FileServer(this@FileServerService, folderUri, port).apply {
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                
                _isRunning.value = true
                _serverUrls.value = allIps.map { (ifName, ip) -> Pair(ifName, "http://$ip:$port") }
                WakeLockManager.acquire(this@FileServerService, "FileServerService")
                
                Log.i(TAG, "File server started on ${allIps.size} interfaces")
                
                withContext(Dispatchers.Main) {
                    val primaryUrl = _serverUrls.value.firstOrNull()?.second ?: "unknown"
                    updateNotification("Sharing files at $primaryUrl")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}", e)
                _isRunning.value = false
                _serverUrls.value = emptyList()
                withContext(Dispatchers.Main) {
                    updateNotification("Error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop the file server.
     */
    fun stopServer() {
        Log.i(TAG, "Stopping file server")
        server?.stop()
        server = null
        _isRunning.value = false
        _serverUrls.value = emptyList()
        folderUri = null
        WakeLockManager.release("FileServerService")
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
    }
    
    private fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val ips = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ip ->
                            // Skip link-local addresses (169.254.x.x)
                            if (!ip.startsWith("169.254")) {
                                val friendlyName = when {
                                    iface.name.startsWith("wlan") -> "WiFi"
                                    iface.name.startsWith("eth") -> "Ethernet"
                                    iface.name.startsWith("tun") -> "VPN"
                                    iface.name.startsWith("rmnet") -> "Mobile"
                                    else -> iface.name
                                }
                                ips.add(Pair(friendlyName, ip))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP addresses", e)
        }
        return ips
    }
    
    private fun updateNotification(message: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 1f, message)
        }
    }
    
    /**
     * Internal HTTP server for serving files.
     */
    private inner class FileServer(
        private val context: Context,
        private val folderUri: Uri,
        port: Int
    ) : NanoHTTPD(port) {
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.trimStart('/')
            @Suppress("DEPRECATION")
            val params = session.parms ?: emptyMap()
            Log.d(TAG, "Request: $uri, params: $params")
            
            return try {
                if (session.method == Method.POST && uri == "__upload") {
                    return uploadToDirectory(session)
                }

                // Handle ZIP download request
                if (params["download"] == "zip") {
                    return serveZipDownload(uri)
                }
                
                if (uri.isEmpty() || uri == "/") {
                    serveDirectoryListing(folderUri, "")
                } else {
                    serveFileOrDirectory(uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving: $uri", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }
        
        private fun serveDirectoryListing(dirUri: Uri, path: String): Response {
            val docFile = DocumentFile.fromTreeUri(context, dirUri) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            
            val targetDir = if (path.isEmpty()) {
                docFile
            } else {
                navigateToPath(docFile, path) ?: 
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Path not found: $path")
            }
            
            val html = buildDirectoryPage(targetDir, path)
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun buildDirectoryPage(targetDir: DocumentFile, path: String): String {
            val title = htmlEscape(targetDir.name ?: "Files")
            val currentPath = htmlEscape(path.ifEmpty { "/" })
            val parentPath = path.substringBeforeLast("/", "")
            val parentHref = if (parentPath.isBlank()) "/" else "/${encodePath(parentPath)}"
            val downloadPath = if (path.isEmpty()) "" else encodePath(path)
            val contentRows = buildString {
                targetDir.listFiles()
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase(Locale.getDefault()) }))
                    .forEach { file ->
                        val name = file.name ?: "unknown"
                        val filePath = if (path.isEmpty()) name else "$path/$name"
                        val encodedPath = encodePath(filePath)
                        if (file.isDirectory) {
                            append(
                                """
                                <a class="entry folder" href="/$encodedPath">
                                  <span class="entry-main">📁 ${htmlEscape(name)}</span>
                                  <span class="entry-meta">Folder / Carpeta</span>
                                </a>
                                """.trimIndent()
                            )
                        } else {
                            append(
                                """
                                <a class="entry file" href="/$encodedPath">
                                  <span class="entry-main">📄 ${htmlEscape(name)}</span>
                                  <span class="entry-meta">${htmlEscape(FormatUtils.formatFileSize(file.length()))}</span>
                                </a>
                                """.trimIndent()
                            )
                        }
                    }
            }
            val emptyState = if (contentRows.isBlank()) {
                "<p class=\"empty\">No files yet / Aun no hay archivos</p>"
            } else {
                contentRows
            }

            return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>📂 $title</title>
                  <style>
                    :root {
                      color-scheme: dark;
                      --bg: #101728;
                      --panel: rgba(20, 30, 52, 0.92);
                      --line: rgba(150, 181, 255, 0.18);
                      --text: #edf3ff;
                      --muted: #a9b8d6;
                      --accent: #63d2ff;
                      --accent-2: #77f7c1;
                      --folder: #7ed8ff;
                      --file: #ff8bc0;
                      --danger: #ff7b7b;
                      --shadow: 0 18px 40px rgba(0, 0, 0, 0.28);
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                      background:
                        radial-gradient(circle at top left, rgba(99, 210, 255, 0.14), transparent 34%),
                        radial-gradient(circle at top right, rgba(119, 247, 193, 0.16), transparent 30%),
                        linear-gradient(180deg, #0b1220 0%, var(--bg) 100%);
                      color: var(--text);
                      min-height: 100vh;
                    }
                    .shell {
                      width: min(960px, 100%);
                      margin: 0 auto;
                      padding: 18px;
                    }
                    .card {
                      margin-bottom: 14px;
                      padding: 18px;
                      border-radius: 18px;
                      background: var(--panel);
                      border: 1px solid var(--line);
                      box-shadow: var(--shadow);
                    }
                    h1, h2, p { margin: 0; }
                    .eyebrow {
                      color: var(--accent-2);
                      font-size: 0.78rem;
                      font-weight: 800;
                      text-transform: uppercase;
                      letter-spacing: 0.08em;
                      margin-bottom: 8px;
                    }
                    .hero h1 {
                      font-size: clamp(1.6rem, 4vw, 2.6rem);
                      margin-bottom: 10px;
                    }
                    .hero p, .helper, .entry-meta, .stats, .empty {
                      color: var(--muted);
                    }
                    .actions, .pickers {
                      display: flex;
                      gap: 10px;
                      flex-wrap: wrap;
                    }
                    .actions { margin-top: 16px; }
                    .button, button {
                      appearance: none;
                      min-height: 46px;
                      padding: 12px 16px;
                      border-radius: 12px;
                      border: 0;
                      font: inherit;
                      font-weight: 700;
                      cursor: pointer;
                      text-decoration: none;
                    }
                    .button.primary, button.primary {
                      background: linear-gradient(135deg, var(--accent), var(--accent-2));
                      color: #0b1220;
                    }
                    .button.secondary, button.secondary {
                      background: rgba(255, 255, 255, 0.06);
                      color: var(--text);
                      border: 1px solid var(--line);
                    }
                    button:disabled {
                      opacity: 0.6;
                      cursor: wait;
                    }
                    .upload-head {
                      display: grid;
                      gap: 8px;
                      margin-bottom: 16px;
                    }
                    .pickers {
                      margin-bottom: 12px;
                      align-items: center;
                    }
                    .picker-menu {
                      position: relative;
                    }
                    .plus-button {
                      width: 52px;
                      min-width: 52px;
                      padding: 0;
                      font-size: 1.5rem;
                      line-height: 1;
                    }
                    .picker-popover {
                      position: absolute;
                      top: calc(100% + 10px);
                      left: 0;
                      z-index: 5;
                      min-width: 260px;
                      padding: 8px;
                      border-radius: 14px;
                      border: 1px solid var(--line);
                      background: rgba(13, 21, 38, 0.98);
                      box-shadow: var(--shadow);
                    }
                    .picker-option {
                      width: 100%;
                      justify-content: flex-start;
                      text-align: left;
                      background: transparent;
                      color: var(--text);
                      border: 1px solid transparent;
                    }
                    .picker-option:hover,
                    .picker-option:focus-visible {
                      border-color: var(--line);
                      background: rgba(255, 255, 255, 0.05);
                    }
                    .status-box {
                      display: grid;
                      gap: 10px;
                      padding: 14px;
                      background: rgba(255, 255, 255, 0.04);
                      border: 1px solid var(--line);
                      border-radius: 14px;
                    }
                    .status-row {
                      display: flex;
                      justify-content: space-between;
                      gap: 12px;
                      flex-wrap: wrap;
                    }
                    .progress-track {
                      width: 100%;
                      height: 12px;
                      border-radius: 999px;
                      background: rgba(255, 255, 255, 0.08);
                      overflow: hidden;
                    }
                    .progress-bar {
                      width: 0%;
                      height: 100%;
                      border-radius: inherit;
                      background: linear-gradient(90deg, var(--accent), var(--accent-2));
                      transition: width 0.15s ease;
                    }
                    .listing-head {
                      margin-bottom: 14px;
                    }
                    .list {
                      display: grid;
                      gap: 10px;
                    }
                    .entry {
                      display: flex;
                      justify-content: space-between;
                      align-items: center;
                      gap: 12px;
                      padding: 14px 16px;
                      border-radius: 14px;
                      text-decoration: none;
                      border: 1px solid var(--line);
                      background: rgba(255, 255, 255, 0.04);
                    }
                    .entry-main {
                      color: var(--text);
                      word-break: break-word;
                    }
                    .folder .entry-main { color: var(--folder); }
                    .file .entry-main { color: var(--file); }
                    .hidden { display: none !important; }
                    .message-ok { color: var(--accent-2); }
                    .message-error { color: var(--danger); }
                    @media (max-width: 640px) {
                      .shell { padding: 12px; }
                      .card { padding: 16px; }
                      .status-row, .entry { flex-direction: column; align-items: flex-start; }
                      .picker-popover {
                        left: 0;
                        right: auto;
                        min-width: min(260px, calc(100vw - 48px));
                      }
                    }
                  </style>
                </head>
                <body data-current-path="${htmlEscape(path)}">
                  <div class="shell">
                    <section class="card hero">
                      <p class="eyebrow">File Server / Servidor de archivos</p>
                      <h1>📂 $title</h1>
                      <p>Current folder / Carpeta actual: <strong>$currentPath</strong></p>
                      <div class="actions">
                        ${if (path.isNotEmpty()) "<a class=\"button secondary\" href=\"$parentHref\">⬅ Back / Volver</a>" else ""}
                        <a class="button primary" href="/$downloadPath?download=zip">📦 Download as ZIP / Descargar como ZIP</a>
                      </div>
                    </section>

                    <section class="card">
                      <div class="upload-head">
                        <p class="eyebrow">Upload / Subida</p>
                        <h2>Upload files or folders / Subir archivos o carpetas</h2>
                        <p class="helper">Pick files or a whole folder, then upload everything into this open directory. / Elige archivos o una carpeta completa y sube todo dentro de esta carpeta abierta.</p>
                      </div>
                      <div class="pickers">
                        <div class="picker-menu">
                          <button class="secondary plus-button" type="button" id="openPickerMenu" aria-expanded="false" aria-controls="pickerMenu">+</button>
                          <div class="picker-popover hidden" id="pickerMenu">
                            <button class="picker-option" type="button" id="pickFiles">Choose files / Elegir archivos</button>
                            <button class="picker-option" type="button" id="pickFolder">Choose folder / Elegir carpeta</button>
                          </div>
                        </div>
                        <button class="primary" type="button" id="startUpload" disabled>Start upload / Iniciar subida</button>
                      </div>
                      <input class="hidden" id="fileInput" type="file" multiple>
                      <input class="hidden" id="folderInput" type="file" webkitdirectory directory multiple>
                      <div class="status-box">
                        <div class="status-row">
                          <strong id="selectionLabel">No files selected / No hay archivos seleccionados</strong>
                          <span class="stats" id="selectionStats">0 B</span>
                        </div>
                        <div class="progress-track"><div class="progress-bar" id="progressBar"></div></div>
                        <div class="status-row">
                          <span id="progressText">Waiting to upload / Esperando para subir</span>
                          <span class="stats" id="speedText">0 B/s</span>
                        </div>
                        <div class="status-row">
                          <span class="stats" id="etaText">Time left / Tiempo restante: --</span>
                          <span class="stats" id="resultText"></span>
                        </div>
                      </div>
                    </section>

                    <section class="card">
                      <div class="listing-head">
                        <p class="eyebrow">Contents / Contenido</p>
                        <h2>Files and folders / Archivos y carpetas</h2>
                      </div>
                      <div class="list">
                        $emptyState
                      </div>
                    </section>
                  </div>

                  <script>
                    const fileInput = document.getElementById('fileInput');
                    const folderInput = document.getElementById('folderInput');
                    const openPickerMenuButton = document.getElementById('openPickerMenu');
                    const pickerMenu = document.getElementById('pickerMenu');
                    const pickFilesButton = document.getElementById('pickFiles');
                    const pickFolderButton = document.getElementById('pickFolder');
                    const startUploadButton = document.getElementById('startUpload');
                    const selectionLabel = document.getElementById('selectionLabel');
                    const selectionStats = document.getElementById('selectionStats');
                    const progressBar = document.getElementById('progressBar');
                    const progressText = document.getElementById('progressText');
                    const speedText = document.getElementById('speedText');
                    const etaText = document.getElementById('etaText');
                    const resultText = document.getElementById('resultText');
                    const currentPath = document.body.dataset.currentPath || '';
                    let selectedEntries = [];
                    let selectionKind = 'files';

                    function formatBytes(bytes) {
                      if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
                      const units = ['B', 'KB', 'MB', 'GB', 'TB'];
                      let value = bytes;
                      let index = 0;
                      while (value >= 1024 && index < units.length - 1) {
                        value /= 1024;
                        index += 1;
                      }
                      const decimals = index === 0 ? 0 : (value >= 10 ? 1 : 2);
                      return value.toFixed(decimals) + ' ' + units[index];
                    }

                    function formatSeconds(seconds) {
                      if (!Number.isFinite(seconds) || seconds < 0) return '--';
                      const rounded = Math.max(0, Math.round(seconds));
                      const hours = Math.floor(rounded / 3600);
                      const minutes = Math.floor((rounded % 3600) / 60);
                      const secs = rounded % 60;
                      if (hours > 0) return hours + 'h ' + minutes + 'm';
                      if (minutes > 0) return minutes + 'm ' + secs + 's';
                      return secs + 's';
                    }

                    function normalizeSeparators(value) {
                      return String(value || '').replace(/\\\\/g, '/');
                    }

                    function anchorFolderEntries(entries) {
                      const rootCandidates = entries
                        .map((entry) => normalizeSeparators(entry.relativePath).split('/').filter(Boolean)[0] || '')
                        .filter(Boolean);
                      const uniqueRoots = Array.from(new Set(rootCandidates));
                      if (uniqueRoots.length !== 1) {
                        return entries;
                      }
                      const rootName = uniqueRoots[0];
                      return entries.map((entry) => {
                        const parts = normalizeSeparators(entry.relativePath).split('/').filter(Boolean);
                        const rest = parts[0] === rootName ? parts.slice(1) : parts;
                        const anchoredPath = [rootName, ...rest].join('/');
                        return { file: entry.file, relativePath: anchoredPath };
                      });
                    }

                    function filesToEntries(fileList, kind) {
                      const entries = Array.from(fileList || []).map((file) => ({
                        file,
                        relativePath: (file.webkitRelativePath && file.webkitRelativePath.trim()) || file.name
                      }));
                      return kind === 'folder' ? anchorFolderEntries(entries) : entries;
                    }

                    function updateSelection(entries, kind) {
                      selectedEntries = entries;
                      selectionKind = kind;
                      const totalBytes = entries.reduce((sum, entry) => sum + (entry.file.size || 0), 0);
                      selectionLabel.textContent = entries.length
                        ? entries.length + (kind === 'folder'
                            ? ' folder item(s) ready / elemento(s) de carpeta listos'
                            : ' file(s) ready / archivo(s) listos')
                        : 'No files selected / No hay archivos seleccionados';
                      selectionStats.textContent = formatBytes(totalBytes);
                      startUploadButton.disabled = entries.length === 0;
                      progressBar.style.width = '0%';
                      progressText.textContent = entries.length
                        ? 'Ready to upload / Listo para subir'
                        : 'Waiting to upload / Esperando para subir';
                      speedText.textContent = '0 B/s';
                      etaText.textContent = 'Time left / Tiempo restante: --';
                      resultText.textContent = '';
                      resultText.className = 'stats';
                    }

                    function closePickerMenu() {
                      pickerMenu.classList.add('hidden');
                      openPickerMenuButton.setAttribute('aria-expanded', 'false');
                    }

                    function openPickerMenu() {
                      pickerMenu.classList.remove('hidden');
                      openPickerMenuButton.setAttribute('aria-expanded', 'true');
                    }

                    function togglePickerMenu() {
                      if (pickerMenu.classList.contains('hidden')) {
                        openPickerMenu();
                      } else {
                        closePickerMenu();
                      }
                    }

                    openPickerMenuButton.addEventListener('click', togglePickerMenu);
                    pickFilesButton.addEventListener('click', () => fileInput.click());
                    pickFolderButton.addEventListener('click', () => folderInput.click());
                    pickFilesButton.addEventListener('click', closePickerMenu);
                    pickFolderButton.addEventListener('click', closePickerMenu);
                    fileInput.addEventListener('change', () => updateSelection(filesToEntries(fileInput.files, 'files'), 'files'));
                    folderInput.addEventListener('change', () => updateSelection(filesToEntries(folderInput.files, 'folder'), 'folder'));
                    document.addEventListener('click', (event) => {
                      if (!pickerMenu.contains(event.target) && !openPickerMenuButton.contains(event.target)) {
                        closePickerMenu();
                      }
                    });
                    document.addEventListener('keydown', (event) => {
                      if (event.key === 'Escape') {
                        closePickerMenu();
                      }
                    });

                    startUploadButton.addEventListener('click', () => {
                      if (!selectedEntries.length) return;
                      const formData = new FormData();
                      formData.append('targetPath', currentPath);
                      formData.append('uploadCount', String(selectedEntries.length));
                      formData.append('selectionKind', selectionKind);
                      selectedEntries.forEach((entry, index) => {
                        formData.append('file_' + index, entry.file, entry.file.name);
                        formData.append('relativePath_' + index, entry.relativePath);
                      });

                      const xhr = new XMLHttpRequest();
                      const startedAt = Date.now();
                      startUploadButton.disabled = true;
                      openPickerMenuButton.disabled = true;
                      pickFilesButton.disabled = true;
                      pickFolderButton.disabled = true;
                      closePickerMenu();
                      progressText.textContent = 'Preparing upload / Preparando subida';
                      resultText.textContent = '';
                      resultText.className = 'stats';

                      xhr.upload.addEventListener('progress', (event) => {
                        if (!event.lengthComputable) {
                          progressText.textContent = 'Uploading... / Subiendo...';
                          return;
                        }
                        const elapsedSeconds = Math.max((Date.now() - startedAt) / 1000, 0.001);
                        const speed = event.loaded / elapsedSeconds;
                        const remaining = Math.max(event.total - event.loaded, 0);
                        const eta = speed > 0 ? remaining / speed : Infinity;
                        const percent = Math.min(100, (event.loaded / event.total) * 100);
                        progressBar.style.width = percent.toFixed(1) + '%';
                        progressText.textContent = percent.toFixed(1) + '% • ' + formatBytes(event.loaded) + ' / ' + formatBytes(event.total);
                        speedText.textContent = formatBytes(speed) + '/s';
                        etaText.textContent = 'Time left / Tiempo restante: ' + formatSeconds(eta);
                      });

                      xhr.addEventListener('load', () => {
                        openPickerMenuButton.disabled = false;
                        pickFilesButton.disabled = false;
                        pickFolderButton.disabled = false;
                        let payload = null;
                        try {
                          payload = JSON.parse(xhr.responseText || '{}');
                        } catch (error) {
                          payload = null;
                        }
                        if (xhr.status >= 200 && xhr.status < 300 && payload && payload.ok) {
                          progressBar.style.width = '100%';
                          progressText.textContent = 'Upload complete / Subida completada';
                          etaText.textContent = 'Time left / Tiempo restante: 0s';
                          resultText.textContent = (payload.uploadedCount || selectedEntries.length) + ' item(s) uploaded / elemento(s) subidos';
                          resultText.className = 'stats message-ok';
                          setTimeout(() => window.location.reload(), 700);
                        } else {
                          const message = payload && payload.error ? payload.error : 'Upload failed / Subida fallida';
                          progressText.textContent = 'Upload failed / Subida fallida';
                          resultText.textContent = message;
                          resultText.className = 'stats message-error';
                          startUploadButton.disabled = false;
                        }
                      });

                      xhr.addEventListener('error', () => {
                        openPickerMenuButton.disabled = false;
                        pickFilesButton.disabled = false;
                        pickFolderButton.disabled = false;
                        progressText.textContent = 'Upload failed / Subida fallida';
                        resultText.textContent = 'Network error during upload / Error de red durante la subida';
                        resultText.className = 'stats message-error';
                        startUploadButton.disabled = false;
                      });

                      xhr.addEventListener('abort', () => {
                        openPickerMenuButton.disabled = false;
                        pickFilesButton.disabled = false;
                        pickFolderButton.disabled = false;
                        progressText.textContent = 'Upload cancelled / Subida cancelada';
                        resultText.textContent = 'Transfer stopped / Transferencia detenida';
                        resultText.className = 'stats message-error';
                        startUploadButton.disabled = false;
                      });

                      xhr.open('POST', '/__upload');
                      xhr.send(formData);
                    });
                  </script>
                </body>
                </html>
            """.trimIndent()
        }
        
        private fun serveFileOrDirectory(path: String): Response {
            val docFile = DocumentFile.fromTreeUri(context, folderUri) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            
            val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
            val target = navigateToPath(docFile, decodedPath) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $path")
            
            return if (target.isDirectory) {
                serveDirectoryListing(folderUri, decodedPath)
            } else {
                serveFile(target)
            }
        }
        
        private fun navigateToPath(root: DocumentFile, path: String): DocumentFile? {
            if (path.isEmpty()) return root
            
            var current = root
            for (segment in path.split("/")) {
                if (segment.isEmpty()) continue
                current = current.listFiles().find { it.name == segment } ?: return null
            }
            return current
        }
        
        private fun serveFile(file: DocumentFile): Response {
            val uri = file.uri
            val mimeType = file.type ?: "application/octet-stream"
            val length = file.length()
            
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Cannot open file")
            
            return newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, length)
        }

        
        private fun serveZipDownload(path: String): Response {
            val docFile = DocumentFile.fromTreeUri(context, folderUri) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            
            val decodedPath = if (path.isNotEmpty()) java.net.URLDecoder.decode(path, "UTF-8") else ""
            val targetDir = if (decodedPath.isEmpty()) docFile else navigateToPath(docFile, decodedPath)
            
            if (targetDir == null || !targetDir.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found")
            }
            
            val zipName = (targetDir.name ?: "files") + ".zip"
            
            // Use piped streams to stream ZIP on the fly
            val pipedIn = PipedInputStream()
            val pipedOut = PipedOutputStream(pipedIn)
            
            thread {
                try {
                    ZipOutputStream(pipedOut).use { zos ->
                        addFolderToZip(zos, targetDir, "")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating ZIP", e)
                } finally {
                    try { pipedOut.close() } catch (e: Exception) {}
                }
            }
            
            val response = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
            response.addHeader("Content-Disposition", "attachment; filename=\"$zipName\"")
            return response
        }

        private fun uploadToDirectory(session: IHTTPSession): Response {
            val root = DocumentFile.fromTreeUri(context, folderUri)
                ?: return jsonResponse(Response.Status.NOT_FOUND, false, "Folder not found")

            val files = HashMap<String, String>()
            session.parseBody(files)

            val targetPath = session.parameters["targetPath"]?.firstOrNull().orEmpty()
            val selectionKind = session.parameters["selectionKind"]?.firstOrNull().orEmpty()
            val normalizedTargetPath = sanitizeRelativePath(targetPath)
                ?: return jsonResponse(Response.Status.BAD_REQUEST, false, "Invalid target path")
            val targetDir = ensureDirectory(root, normalizedTargetPath)
                ?: return jsonResponse(Response.Status.INTERNAL_ERROR, false, "Could not open target folder")

            val uploadParts = files.keys
                .filter { it.startsWith("file_") && !files[it].isNullOrBlank() }
                .sortedBy { it.removePrefix("file_").toIntOrNull() ?: Int.MAX_VALUE }

            if (uploadParts.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, false, "No files uploaded")
            }

            val normalizedRelativePaths = uploadParts.mapNotNull { partName ->
                val index = partName.removePrefix("file_")
                val requestedRelativePath = session.parameters["relativePath_$index"]?.firstOrNull()
                    ?: session.parameters[partName]?.firstOrNull()
                requestedRelativePath?.let { sanitizeRelativePath(it) }
            }
            val anchoredFolderRoot = if (selectionKind == "folder") {
                detectCommonTopLevelFolder(normalizedRelativePaths)
            } else {
                null
            }

            var uploadedCount = 0
            uploadParts.forEach { partName ->
                val tempFilePath = files[partName].orEmpty()
                if (tempFilePath.isBlank()) return@forEach
                val tempFile = java.io.File(tempFilePath)
                if (!tempFile.exists()) return@forEach

                val index = partName.removePrefix("file_")
                val fallbackName = session.parameters[partName]?.firstOrNull().orEmpty()
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { "upload_$index" }
                val requestedRelativePath = session.parameters["relativePath_$index"]?.firstOrNull()
                    ?: fallbackName
                val relativePath = sanitizeRelativePath(requestedRelativePath)
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, false, "Invalid file path: $requestedRelativePath")
                val resolvedRelativePath = if (!anchoredFolderRoot.isNullOrBlank()) {
                    ensureAnchoredFolderPath(relativePath, anchoredFolderRoot)
                } else {
                    relativePath
                }
                val segments = resolvedRelativePath.split('/').filter { it.isNotBlank() }
                val fileName = segments.lastOrNull()
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, false, "Missing file name")
                val parentPath = segments.dropLast(1).joinToString("/")
                val destinationDir = ensureDirectory(targetDir, parentPath)
                    ?: return jsonResponse(Response.Status.INTERNAL_ERROR, false, "Could not create folder for $fileName")
                writeToDocumentFile(destinationDir, fileName, tempFile)
                uploadedCount += 1
            }

            return jsonResponse(
                Response.Status.OK,
                true,
                extras = mapOf(
                    "uploadedCount" to uploadedCount,
                    "targetPath" to normalizedTargetPath
                )
            )
        }

        private fun writeToDocumentFile(parentDir: DocumentFile, fileName: String, source: java.io.File) {
            parentDir.findFile(fileName)?.let { existing ->
                if (existing.isDirectory) {
                    throw IllegalStateException("$fileName is already a folder")
                }
                existing.delete()
            }
            val target = parentDir.createFile(mimeTypeForName(fileName), fileName)
                ?: throw IllegalStateException("Could not create $fileName")
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                    output.flushSafely()
                }
            } ?: throw IllegalStateException("Could not write $fileName")
        }

        private fun ensureDirectory(root: DocumentFile, relativePath: String): DocumentFile? {
            if (relativePath.isBlank()) return root
            var current = root
            relativePath.split('/').filter { it.isNotBlank() }.forEach { rawSegment ->
                val segment = sanitizeName(rawSegment) ?: return null
                val existing = current.findFile(segment)
                current = when {
                    existing == null -> current.createDirectory(segment)
                    existing.isDirectory -> existing
                    else -> return null
                } ?: return null
            }
            return current
        }

        private fun detectCommonTopLevelFolder(relativePaths: List<String>): String? {
            val rootNames = relativePaths
                .mapNotNull { path ->
                    path.split('/').filter { it.isNotBlank() }.firstOrNull()
                }
                .distinct()
            return if (rootNames.size == 1) rootNames.first() else null
        }

        private fun ensureAnchoredFolderPath(relativePath: String, rootFolderName: String): String {
            val segments = relativePath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return rootFolderName
            return if (segments.first() == rootFolderName) {
                segments.joinToString("/")
            } else {
                listOf(rootFolderName, *segments.toTypedArray()).joinToString("/")
            }
        }

        private fun sanitizeRelativePath(path: String): String? {
            val normalized = path.replace('\\', '/').trim().removePrefix("/")
            if (normalized.isBlank()) return ""
            val segments = normalized.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return ""
            val sanitized = mutableListOf<String>()
            segments.forEach { segment ->
                if (segment == "." || segment == "..") return null
                sanitized += sanitizeName(segment) ?: return null
            }
            return sanitized.joinToString("/")
        }

        private fun sanitizeName(name: String): String? {
            val cleaned = buildString {
                name.forEach { ch ->
                    if (ch != '/' && ch != '\\' && !ch.isISOControl()) {
                        append(ch)
                    }
                }
            }.trim()
            return cleaned.takeIf { it.isNotBlank() }
        }

        private fun mimeTypeForName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
            return when (ext) {
                "txt", "md", "log", "csv", "json", "xml", "yaml", "yml" -> "text/plain"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
        }

        private fun jsonResponse(
            status: Response.Status,
            ok: Boolean,
            error: String? = null,
            extras: Map<String, Any?> = emptyMap()
        ): Response {
            val json = buildString {
                append("{\"ok\":")
                append(if (ok) "true" else "false")
                if (!error.isNullOrBlank()) {
                    append(",\"error\":")
                    append(jsonQuoted(error))
                }
                extras.forEach { (key, value) ->
                    append(',')
                    append(jsonQuoted(key))
                    append(':')
                    append(
                        when (value) {
                            null -> "null"
                            is Number, is Boolean -> value.toString()
                            else -> jsonQuoted(value.toString())
                        }
                    )
                }
                append('}')
            }
            return newFixedLengthResponse(status, "application/json; charset=utf-8", json)
        }

        private fun jsonQuoted(value: String): String =
            "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\""

        private fun htmlEscape(value: String): String =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

        private fun encodePath(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun OutputStream.flushSafely() {
            runCatching { flush() }
        }
        
        private fun addFolderToZip(zos: ZipOutputStream, folder: DocumentFile, basePath: String) {
            folder.listFiles().forEach { file ->
                val entryPath = if (basePath.isEmpty()) (file.name ?: "unknown") else "$basePath/${file.name ?: "unknown"}"
                
                if (file.isDirectory) {
                    addFolderToZip(zos, file, entryPath)
                } else {
                    try {
                        zos.putNextEntry(ZipEntry(entryPath))
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding file to ZIP: $entryPath", e)
                    }
                }
            }
        }
    }
}
