package com.example.llamadroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.proot.ProotManager
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*

/**
 * Background service for installing and managing the A1111 Stable Diffusion environment.
 */
class SDEnvironmentService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "sd_environment_channel"
        
        private const val ACTION_INSTALL = "com.example.llamadroid.action.INSTALL_A1111"
        private const val ACTION_UNINSTALL = "com.example.llamadroid.action.UNINSTALL_A1111"
        private const val ACTION_START_SERVER = "com.example.llamadroid.action.START_SD_SERVER"
        private const val ACTION_STOP_SERVER = "com.example.llamadroid.action.STOP_SD_SERVER"
        
        fun startInstall(context: Context) {
            val intent = Intent(context, SDEnvironmentService::class.java).apply {
                action = ACTION_INSTALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun uninstall(context: Context) {
            val intent = Intent(context, SDEnvironmentService::class.java).apply {
                action = ACTION_UNINSTALL
            }
            context.startService(intent)
        }
        
        fun startServer(context: Context) {
            val intent = Intent(context, SDEnvironmentService::class.java).apply {
                action = ACTION_START_SERVER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopServer(context: Context) {
            val intent = Intent(context, SDEnvironmentService::class.java).apply {
                action = ACTION_STOP_SERVER
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prootManager: ProotManager
    private var serverJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prootManager = ProotManager(applicationContext)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INSTALL -> startInstallation()
            ACTION_UNINSTALL -> uninstallEnvironment()
            ACTION_START_SERVER -> startSDServer()
            ACTION_STOP_SERVER -> stopSDServer()
        }
        return START_NOT_STICKY
    }
    
    private fun startInstallation() {
        val notification = createNotification("Installing Stable Diffusion...")
        startForeground(NOTIFICATION_ID, notification)
        
        serviceScope.launch {
            try {
                // Step 1: Download proot binary FIRST
                SDStateHolder.updateInstallState(SDInstallState.Downloading(0f))
                updateNotification("Downloading proot binary...")
                
                val prootSuccess = prootManager.downloadProot { progress ->
                    SDStateHolder.updateInstallState(SDInstallState.Downloading(progress * 0.1f)) // 0-10%
                }
                
                if (!prootSuccess) {
                    SDStateHolder.updateInstallState(SDInstallState.Error("Failed to download proot binary"))
                    stopSelf()
                    return@launch
                }
                
                // Step 2: Download rootfs
                updateNotification("Downloading Ubuntu rootfs...")
                
                val downloadSuccess = prootManager.downloadRootfs { progress ->
                    SDStateHolder.updateInstallState(SDInstallState.Downloading(0.1f + progress * 0.5f)) // 10-60%
                }
                
                if (!downloadSuccess) {
                    SDStateHolder.updateInstallState(SDInstallState.Error("Failed to download rootfs"))
                    stopSelf()
                    return@launch
                }
                
                // Step 3: Extract rootfs
                SDStateHolder.updateInstallState(SDInstallState.Extracting(0f))
                updateNotification("Extracting filesystem...")
                
                val extractSuccess = prootManager.extractRootfs { progress ->
                    SDStateHolder.updateInstallState(SDInstallState.Extracting(progress))
                }
                
                if (!extractSuccess) {
                    SDStateHolder.updateInstallState(SDInstallState.Error("Failed to extract rootfs"))
                    stopSelf()
                    return@launch
                }
                
                // Step 4: Install dependencies
                SDStateHolder.updateInstallState(SDInstallState.InstallingDeps("Updating packages..."))
                updateNotification("Installing dependencies...")
                
                val depCommands = listOf(
                    "apt update" to "Updating package lists...",
                    "apt upgrade -y" to "Upgrading packages...",
                    "apt install -y software-properties-common" to "Installing software-properties-common...",
                    "yes | add-apt-repository ppa:deadsnakes/ppa" to "Adding Python PPA...",
                    "apt update" to "Updating package lists...",
                    "apt install -y wget git python3.11 python3.11-venv libgl1 libglib2.0-0 gcc python3.11-dev" to "Installing Python and dependencies..."
                )
                
                for ((cmd, desc) in depCommands) {
                    SDStateHolder.updateInstallState(SDInstallState.InstallingDeps(desc))
                    val result = prootManager.executeCommand(cmd)
                    if (result != 0) {
                        DebugLog.log("SDEnv: Command failed: $cmd (exit $result)")
                        // Continue anyway, some commands may fail non-fatally
                    }
                }
                
                // Step 4: Create user and clone A1111
                SDStateHolder.updateInstallState(SDInstallState.CloningRepo(0f, "Creating user..."))
                updateNotification("Cloning A1111 repository...")
                
                prootManager.executeCommand("useradd -m -p '' auto --shell /bin/bash")
                
                SDStateHolder.updateInstallState(SDInstallState.CloningRepo(0.3f, "Cloning repository..."))
                val cloneResult = prootManager.executeCommand(
                    "git clone https://github.com/ManuXD32/stable-diffusion-webui.git /home/auto/stable-diffusion-webui",
                    user = "auto"
                )
                
                if (cloneResult != 0) {
                    SDStateHolder.updateInstallState(SDInstallState.Error("Failed to clone A1111 repository"))
                    stopSelf()
                    return@launch
                }
                
                // Step 5: Configure webui-user.sh
                SDStateHolder.updateInstallState(SDInstallState.SettingUpVenv(0.5f, "Configuring A1111..."))
                updateNotification("Configuring A1111...")
                
                val configCmd = """
                    sed -i 's/#python_cmd="python3"/python_cmd="python3.11"/; s/#export COMMANDLINE_ARGS=""/export COMMANDLINE_ARGS="--listen --port 7865 --api --use-cpu all --precision full --no-half --skip-torch-cuda-test --skip-load-model-at-start --enable-insecure-extension-access"/' /home/auto/stable-diffusion-webui/webui-user.sh
                """.trimIndent()
                prootManager.executeCommand(configCmd)
                
                // Make webui.sh executable
                prootManager.executeCommand("chmod +x /home/auto/stable-diffusion-webui/webui.sh")
                
                SDStateHolder.updateInstallState(SDInstallState.SettingUpVenv(1f, "Setup complete!"))
                
                // Done!
                SDStateHolder.updateInstallState(SDInstallState.Installed)
                updateNotification("Stable Diffusion installed!")
                DebugLog.log("SDEnv: Installation complete")
                
                delay(2000)
                stopSelf()
                
            } catch (e: Exception) {
                DebugLog.log("SDEnv: Installation error: ${e.message}")
                SDStateHolder.updateInstallState(SDInstallState.Error(e.message ?: "Unknown error"))
                stopSelf()
            }
        }
    }
    
    private fun uninstallEnvironment() {
        serviceScope.launch {
            prootManager.deleteEnvironment()
            stopSelf()
        }
    }
    
    private fun startSDServer() {
        val notification = createNotification("Starting Stable Diffusion server...")
        startForeground(NOTIFICATION_ID, notification)
        
        SDStateHolder.updateServerState(SDServerState.Starting)
        
        serverJob = serviceScope.launch {
            try {
                updateNotification("Running Stable Diffusion server...")
                SDStateHolder.updateServerState(SDServerState.Running(7865))
                
                val result = prootManager.executeCommand(
                    "cd /home/auto/stable-diffusion-webui && ./webui.sh",
                    user = "auto"
                ) { line ->
                    DebugLog.log("Server: $line")
                }
                
                DebugLog.log("SDEnv: Server exited with code $result")
                SDStateHolder.updateServerState(SDServerState.Stopped)
                
            } catch (e: CancellationException) {
                DebugLog.log("SDEnv: Server stopped by user")
                SDStateHolder.updateServerState(SDServerState.Stopped)
            } catch (e: Exception) {
                DebugLog.log("SDEnv: Server error: ${e.message}")
                SDStateHolder.updateServerState(SDServerState.Error(e.message ?: "Unknown error"))
            }
        }
    }
    
    private fun stopSDServer() {
        serverJob?.cancel()
        // Kill any running processes
        serviceScope.launch {
            prootManager.executeCommand("pkill -f webui.sh")
            prootManager.executeCommand("pkill -f python")
            SDStateHolder.updateServerState(SDServerState.Stopped)
            stopSelf()
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sd_environment_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sd_environment_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
