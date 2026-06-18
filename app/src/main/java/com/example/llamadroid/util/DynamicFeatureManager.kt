package com.example.llamadroid.util

import android.content.Context
import android.util.Log
import com.example.llamadroid.BuildConfig
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Manages Dynamic Feature Modules.
 * Handles checking status and requesting installation for 'fast-follow' or 'on-demand' modules.
 */
object DynamicFeatureManager {
    private const val TAG = "DynamicFeatureManager"
    private val BUNDLED_MODULE_SENTINELS = listOf(
        "llama_server",
        "ffmpeg",
        "kiwix-serve"
    )
    
    // Feature Upscaler (Shared)
    const val MODULE_UPSCALER = "feature_upscaler"
    const val MODULE_LLM_SNAPDRAGON_OPENCL = "feature_llm_snapdragon_opencl"

    private val _optionalModuleStates = MutableStateFlow<Map<String, AcceleratorModuleState>>(emptyMap())
    val optionalModuleStates = _optionalModuleStates.asStateFlow()

    // CPU-Specific Module Prefixes
    private const val MODULE_LLM_PREFIX = "feature_llm_"
    private const val MODULE_KIWIX_PREFIX = "feature_kiwix_"
    private const val MODULE_MEDIA_PREFIX = "feature_media_"

    /**
     * Get the list of expected modules for this device based on CPU tier.
     */
    fun getExpectedModules(): List<String> {
        // CpuFeatures.getBestTier() returns "armv9", "dotprod", or "baseline"
        // But we need to be careful - this is a native call. Ensure specific native lib is loaded?
        // CpuFeatures loads its own lib.
        val tier = try {
            CpuFeatures.getBestTier() // e.g. "armv9", "baseline"
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native lib not loaded yet, defaulting to baseline", e)
            "baseline"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting tier, defaulting to baseline", e)
            "baseline"
        }

        return listOf(
            "${MODULE_LLM_PREFIX}$tier",
            "${MODULE_KIWIX_PREFIX}$tier",
            "${MODULE_MEDIA_PREFIX}$tier",
            MODULE_UPSCALER
        )
    }

    fun getOptionalAcceleratorModules(): List<String> = DeviceAcceleration.optionalModulesForDevice()

    private fun getTierFallbacks(): List<String> {
        val tier = try {
            CpuFeatures.getBestTier()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native lib not loaded yet, defaulting to baseline", e)
            "baseline"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting tier, defaulting to baseline", e)
            "baseline"
        }

        return when (tier) {
            "armv9" -> listOf("armv9", "dotprod", "baseline")
            "dotprod" -> listOf("dotprod", "baseline")
            else -> listOf("baseline")
        }
    }

    private fun hasBundledNativeLibraries(context: Context): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        if (!nativeLibDir.exists()) {
            Log.e(TAG, "Fat APK native library dir does not exist: ${nativeLibDir.absolutePath}")
            return false
        }

        val tiersToTry = getTierFallbacks()
        val missing = mutableListOf<String>()

        BUNDLED_MODULE_SENTINELS.forEach { binaryName ->
            val hasBinary = tiersToTry.any { tier ->
                File(nativeLibDir, "lib${binaryName}_${tier}.so").exists()
            }
            if (!hasBinary) {
                missing += binaryName
            }
        }

        val hasUpscalerLib = File(nativeLibDir, "libncnn.so").exists()
        if (!hasUpscalerLib) {
            missing += "ncnn"
        }

        if (missing.isNotEmpty()) {
            Log.e(
                TAG,
                "Fat APK is missing bundled native components: $missing in ${nativeLibDir.absolutePath}"
            )
            return false
        }

        return true
    }

    /**
     * Check if a dynamic feature module is installed.
     */
    fun isModuleInstalled(context: Context, moduleName: String): Boolean {
        if (BuildConfig.IS_FAT_APK_BUILD) {
            return hasBundledNativeLibraries(context)
        }
        val manager = SplitInstallManagerFactory.create(context)
        return manager.installedModules.contains(moduleName)
    }

    /**
     * Check if the Native Libs module is ready.
     * Checks if ALL critical features for THIS DEVICE are installed.
     */
    fun isNativeLibsReady(context: Context): Boolean {
        if (BuildConfig.IS_FAT_APK_BUILD) {
            return hasBundledNativeLibraries(context)
        }

        val expected = getExpectedModules()
        val allInstalled = expected.all { isModuleInstalled(context, it) }
        
        if (!allInstalled) {
            // Log what's missing
            val manager = SplitInstallManagerFactory.create(context)
            val installed = manager.installedModules
            val missing = expected.filter { !installed.contains(it) }
            Log.d(TAG, "Missing modules: $missing (Expected: $expected, Installed: $installed)")
        }
        
        return allInstalled
    }

    /**
     * Monitor installation progress of a module.
     * For fast-follow, this might just confirm it's installed or waiting.
     */
    fun monitorModuleInstallation(context: Context, moduleName: String): Flow<Int> = callbackFlow {
        if (BuildConfig.IS_FAT_APK_BUILD) {
            if (hasBundledNativeLibraries(context)) {
                trySend(SplitInstallSessionStatus.INSTALLED)
            } else {
                trySend(SplitInstallSessionStatus.FAILED)
            }
            close()
            return@callbackFlow
        }

        val manager = SplitInstallManagerFactory.create(context)

        // If already installed, emit success immediately
        if (manager.installedModules.contains(moduleName)) {
            trySend(SplitInstallSessionStatus.INSTALLED)
            close()
            return@callbackFlow
        }

        lateinit var listener: SplitInstallStateUpdatedListener
        listener = SplitInstallStateUpdatedListener { state ->
            if (state.moduleNames().contains(moduleName)) {
                trySend(state.status())
                
                if (state.status() == SplitInstallSessionStatus.INSTALLED || 
                    state.status() == SplitInstallSessionStatus.FAILED ||
                    state.status() == SplitInstallSessionStatus.CANCELED) {
                    close()
                }
            }
        }

        manager.registerListener(listener)
        
        // Also explicitly request install just in case (idempotent for fast-follow usually)
        val request = SplitInstallRequest.newBuilder()
            .addModule(moduleName)
            .build()
            
        manager.startInstall(request)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start install for $moduleName", e)
                // Don't close flow here, let listener handle or user retry
            }

        awaitClose {
            manager.unregisterListener(listener)
        }
    }

    fun installAllFeatures(context: Context) {
        if (BuildConfig.IS_FAT_APK_BUILD) {
            if (hasBundledNativeLibraries(context)) {
                Log.i(TAG, "Fat APK build detected; skipping Play Feature Delivery requests.")
            } else {
                Log.e(TAG, "Fat APK build detected but bundled native libraries are missing.")
            }
            return
        }

        val manager = SplitInstallManagerFactory.create(context)
        val modulesToInstall = mutableListOf<String>()
        val expectedModules = getExpectedModules()
        val optionalAcceleratorModules = getOptionalAcceleratorModules()
        
        (expectedModules + optionalAcceleratorModules).distinct().forEach { module ->
            if (!manager.installedModules.contains(module)) {
                modulesToInstall.add(module)
            }
        }
        
        if (modulesToInstall.isEmpty()) {
            Log.i(TAG, "All expected modules are already installed.")
            return
        }
        
        val requestBuilder = SplitInstallRequest.newBuilder()
        modulesToInstall.forEach { requestBuilder.addModule(it) }
        
        Log.i(TAG, "Requesting installation for: $modulesToInstall")
        
        manager.startInstall(requestBuilder.build())
            .addOnSuccessListener { session -> Log.i(TAG, "Installation session started: $session") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to request modules", e) }
    }

    fun installOptionalAccelerators(context: Context) {
        if (!DeviceAcceleration.isSnapdragonCompatible()) {
            return
        }
        if (BuildConfig.IS_FAT_APK_BUILD) {
            val bundled = hasBundledNativeLibraries(context)
            updateOptionalStates(
                getOptionalAcceleratorModules().associateWith {
                    AcceleratorModuleState(
                        moduleName = it,
                        status = if (bundled) AccelerationStatus.CPU_FALLBACK else AccelerationStatus.FAILED,
                        errorMessage = if (bundled) null else "Fat APK native libraries are incomplete."
                    )
                }
            )
            return
        }

        val manager = SplitInstallManagerFactory.create(context)
        val optionalModules = getOptionalAcceleratorModules()
        val missing = optionalModules.filter { !manager.installedModules.contains(it) }
        val installedStates = optionalModules
            .filter { it !in missing }
            .associateWith { AcceleratorModuleState(it, AccelerationStatus.CPU_FALLBACK, progress = 100) }
        if (installedStates.isNotEmpty()) updateOptionalStates(installedStates)

        if (missing.isEmpty()) {
            Log.i(TAG, "Optional accelerator modules are already installed.")
            return
        }

        updateOptionalStates(
            missing.associateWith { AcceleratorModuleState(it, AccelerationStatus.SUPPORTED_NOT_INSTALLED, progress = 0) }
        )

        lateinit var listener: SplitInstallStateUpdatedListener
        listener = SplitInstallStateUpdatedListener { state ->
            val relevantModules = state.moduleNames().filter { it in missing }
            if (relevantModules.isEmpty()) return@SplitInstallStateUpdatedListener
            val progress = if (state.totalBytesToDownload() > 0L) {
                ((state.bytesDownloaded() * 100L) / state.totalBytesToDownload()).toInt().coerceIn(0, 100)
            } else {
                null
            }
            val status = when (state.status()) {
                SplitInstallSessionStatus.DOWNLOADING,
                SplitInstallSessionStatus.INSTALLING,
                SplitInstallSessionStatus.PENDING,
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> AccelerationStatus.INSTALLING
                SplitInstallSessionStatus.INSTALLED -> AccelerationStatus.CPU_FALLBACK
                SplitInstallSessionStatus.FAILED,
                SplitInstallSessionStatus.CANCELED,
                SplitInstallSessionStatus.CANCELING -> AccelerationStatus.FAILED
                else -> AccelerationStatus.INSTALLING
            }
            updateOptionalStates(
                relevantModules.associateWith {
                    AcceleratorModuleState(
                        moduleName = it,
                        status = status,
                        progress = progress,
                        errorMessage = if (status == AccelerationStatus.FAILED) "Install status ${state.status()}" else null
                    )
                }
            )
            if (status == AccelerationStatus.CPU_FALLBACK || status == AccelerationStatus.FAILED) {
                runCatching { manager.unregisterListener(listener) }
            }
        }
        manager.registerListener(listener)

        val requestBuilder = SplitInstallRequest.newBuilder()
        missing.forEach { requestBuilder.addModule(it) }
        Log.i(TAG, "Requesting optional accelerator modules: $missing")
        manager.startInstall(requestBuilder.build())
            .addOnSuccessListener { sessionId ->
                Log.i(TAG, "Optional accelerator install session started: $sessionId")
                updateOptionalStates(
                    missing.associateWith { AcceleratorModuleState(it, AccelerationStatus.INSTALLING, progress = 0) }
                )
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to request optional accelerator modules", error)
                updateOptionalStates(
                    missing.associateWith {
                        AcceleratorModuleState(
                            moduleName = it,
                            status = AccelerationStatus.FAILED,
                            errorMessage = error.message
                        )
                    }
                )
                runCatching { manager.unregisterListener(listener) }
            }
    }

    private fun updateOptionalStates(states: Map<String, AcceleratorModuleState>) {
        _optionalModuleStates.value = _optionalModuleStates.value.toMutableMap().apply {
            putAll(states)
        }
    }

    fun installModule(context: Context, moduleName: String) {
        if (BuildConfig.IS_FAT_APK_BUILD) {
            if (hasBundledNativeLibraries(context)) {
                Log.i(TAG, "Fat APK build detected; module $moduleName is treated as bundled.")
            } else {
                Log.e(TAG, "Fat APK build detected but bundled native libraries are missing for $moduleName.")
            }
            return
        }

        val manager = SplitInstallManagerFactory.create(context)
        if (manager.installedModules.contains(moduleName)) {
            Log.i(TAG, "Module already installed: $moduleName")
            return
        }

        val request = SplitInstallRequest.newBuilder()
            .addModule(moduleName)
            .build()

        Log.i(TAG, "Requesting installation for module: $moduleName")
        manager.startInstall(request)
            .addOnSuccessListener { session -> Log.i(TAG, "Module install session started for $moduleName: $session") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to request module $moduleName", e) }
    }
}
