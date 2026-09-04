package com.example.llamadroid.util

import android.content.Context
import android.util.Log
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages on-demand asset pack downloads using Play Asset Delivery.
 * Asset packs contain native binaries that are too large for the base APK.
 */
object AssetPackManagerUtil {
    private const val TAG = "AssetPackManager"
    
    // Directory where extracted binaries are stored
    private const val BINARIES_DIR = "asset_binaries"
    
    /**
     * Available asset packs containing native binaries
     */
    enum class AssetPack(
        val packName: String,
        val displayNameRes: Int,
        val dependencies: List<AssetPack> = emptyList()
    ) {
        // Obsolete packs (moved to feature_native_libs):
        // LLAMA, VIDEO, WHISPER, IMAGEGEN, KIWIX
        
        UPSCALER("asset_upscaler", R.string.feature_upscaler_title);
        
        companion object {
            val ALL = entries.toList()
        }
    }
    
    /**
     * Installation state for tracking progress
     */
    sealed class InstallState {
        data object Pending : InstallState()
        data class Downloading(val progress: Int) : InstallState()
        data object Extracting : InstallState()
        data object Completed : InstallState()
        data class Failed(val error: String) : InstallState()
    }
    
    private fun getAssetPackManager(context: Context): AssetPackManager {
        return AssetPackManagerFactory.getInstance(context)
    }

    private fun isFatApkBuild(): Boolean = BuildConfig.IS_FAT_APK_BUILD

    private fun hasBundledPackAssets(context: Context, pack: AssetPack): Boolean {
        return when (pack) {
            AssetPack.UPSCALER -> {
                try {
                    !context.assets.list("upscaler_models").isNullOrEmpty()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to inspect bundled assets for ${pack.packName}", e)
                    false
                }
            }
        }
    }

    private fun missingBundledPackMessage(pack: AssetPack): String {
        return "Fat APK is missing bundled assets for ${pack.packName}"
    }
    
    /**
     * Get the directory where extracted binaries are stored
     */
    fun getBinariesDir(context: Context): File {
        return File(context.filesDir, BINARIES_DIR).also {
            if (!it.exists()) it.mkdirs()
        }
    }
    
    /**
     * Check if a specific asset pack's binaries are ready to use.
     * This checks:
     * 1. If assets were extracted from Play Asset Delivery (marker file exists or direct check)
     * 2. If assets are bundled directly in the APK (fat APK build - nativeLibraryDir or assets/native)
     */
    fun isReady(context: Context, pack: AssetPack): Boolean {
        val binDir = getBinariesDir(context)
        val markerFile = File(binDir, ".${pack.packName}_extracted")
        
        // Check if already extracted via Play Asset Delivery
        if (markerFile.exists()) {
            return true
        }

        if (isFatApkBuild()) {
            val isBundled = hasBundledPackAssets(context, pack)
            if (!isBundled) {
                Log.e(TAG, missingBundledPackMessage(pack))
            }
            return isBundled
        }
        
        return false
    }
    
    /**
     * Extract assets bundled directly in APK to the binaries directory
     */
    private fun extractBundledAssetsIfNeeded(context: Context) {
        val binDir = getBinariesDir(context)
        val masterMarker = File(binDir, ".bundled_assets_extracted")
        
        if (masterMarker.exists()) {
            return // Already extracted
        }
        
        try {
            // Extract native binaries
            extractAssetDirectory(context, "native", binDir)
            
            // Create markers for all packs since fat APK has everything
            AssetPack.ALL.forEach { pack ->
                File(binDir, ".${pack.packName}_extracted").createNewFile()
            }
            masterMarker.createNewFile()
            
            Log.i(TAG, "Bundled assets extracted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bundled assets", e)
        }
    }
    
    /**
     * Recursively extract an asset directory to destination
     */
    private fun extractAssetDirectory(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val assets = assetManager.list(assetPath) ?: return
        
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        
        for (filename in assets) {
            val assetFilePath = "$assetPath/$filename"
            val destFile = File(destDir, filename)
            
            // Check if it's a directory by trying to list its contents
            val subAssets = assetManager.list(assetFilePath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                // It's a directory, recurse
                extractAssetDirectory(context, assetFilePath, destFile)
            } else {
                // It's a file, extract it
                if (!destFile.exists()) {
                    try {
                        assetManager.open(assetFilePath).use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Set permissions for executables
                        if (filename.endsWith(".so")) {
                            destFile.setReadable(true, true)
                            destFile.setExecutable(true, true)
                            destFile.setWritable(true, true)
                            
                            // Also try Runtime.exec chmod as fallback
                            try {
                                Runtime.getRuntime().exec(arrayOf("chmod", "700", destFile.absolutePath)).waitFor()
                            } catch (e: Exception) {
                                Log.w(TAG, "chmod failed for ${destFile.name}: ${e.message}")
                            }
                        }
                        
                        Log.d(TAG, "Extracted: $assetFilePath -> ${destFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract $assetFilePath: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Check if ALL asset packs are ready
     */
    fun areAllPacksReady(context: Context): Boolean {
        return AssetPack.ALL.all { isReady(context, it) }
    }
    
    /**
     * Get the path to a specific binary from an asset pack
     */
    fun getBinaryPath(context: Context, binaryName: String): String? {
        // First check native lib dir (base app binaries like llama)
        val nativeLibPath = File(context.applicationInfo.nativeLibraryDir, binaryName)
        if (nativeLibPath.exists()) {
            return nativeLibPath.absolutePath
        }
        
        // Then check asset pack extraction dir
        val assetPath = File(getBinariesDir(context), binaryName)
        if (assetPath.exists()) {
            return assetPath.absolutePath
        }
        
        return null
    }
    
    /**
     * Download ALL asset packs at once (called on first launch)
     */
    fun downloadAllPacks(context: Context): Flow<InstallState> = callbackFlow {
        if (isFatApkBuild()) {
            val missingPack = AssetPack.ALL.firstOrNull { !hasBundledPackAssets(context, it) }
            if (missingPack == null) {
                trySend(InstallState.Completed)
            } else {
                trySend(InstallState.Failed(missingBundledPackMessage(missingPack)))
            }
            close()
            return@callbackFlow
        }

        val manager = getAssetPackManager(context)
        val allPackNames = AssetPack.ALL.map { it.packName }.toSet()
        
        // Track state of each pack
        val packStates = mutableMapOf<String, AssetPackState>()
        
        trySend(InstallState.Pending)
        
        val listener = AssetPackStateUpdateListener { state ->
            if (state.name() !in allPackNames) return@AssetPackStateUpdateListener
            
            packStates[state.name()] = state
            
            val totalBytes = packStates.values.sumOf { it.totalBytesToDownload() }
            val downloadedBytes = packStates.values.sumOf { it.bytesDownloaded() }
            
            // Check if any pack failed
            val failedPack = packStates.values.find { it.status() == AssetPackStatus.FAILED }
            if (failedPack != null) {
                trySend(InstallState.Failed("Pack ${failedPack.name()} failed with error: ${failedPack.errorCode()}"))
                return@AssetPackStateUpdateListener
            }
            
            // Check if all packs are completed
            val allCompleted = allPackNames.all { packName ->
                packStates[packName]?.status() == AssetPackStatus.COMPLETED
            }
            
            if (allCompleted) {
                // Extract all binaries synchronously
                trySend(InstallState.Extracting)
                try {
                    AssetPack.ALL.forEach { pack ->
                        extractAssetPackSync(context, manager, pack)
                    }
                    trySend(InstallState.Completed)
                    close()
                } catch (e: Exception) {
                    trySend(InstallState.Failed("Extraction failed: ${e.message}"))
                    close()
                }
            } else if (totalBytes > 0) {
                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                trySend(InstallState.Downloading(progress))
            }
        }
        
        manager.registerListener(listener)
        
        try {
            // Start fetch for all packs
            manager.fetch(allPackNames.toList())
        } catch (e: Exception) {
            trySend(InstallState.Failed("Failed to start download: ${e.message}"))
            close()
        }
        
        awaitClose {
            manager.unregisterListener(listener)
        }
    }
    
    /**
     * Download a single asset pack (and its dependencies)
     */
    fun downloadPack(context: Context, pack: AssetPack): Flow<InstallState> = callbackFlow {
        if (isFatApkBuild()) {
            if (hasBundledPackAssets(context, pack)) {
                trySend(InstallState.Completed)
            } else {
                trySend(InstallState.Failed(missingBundledPackMessage(pack)))
            }
            close()
            return@callbackFlow
        }

        val manager = getAssetPackManager(context)
        
        // Collect all packs needed (including dependencies)
        val packsNeeded = mutableListOf<AssetPack>()
        fun collectDependencies(p: AssetPack) {
            p.dependencies.forEach { dep ->
                if (!isReady(context, dep) && dep !in packsNeeded) {
                    collectDependencies(dep)
                    packsNeeded.add(dep)
                }
            }
        }
        collectDependencies(pack)
        if (!isReady(context, pack)) {
            packsNeeded.add(pack)
        }
        
        if (packsNeeded.isEmpty()) {
            trySend(InstallState.Completed)
            close()
            return@callbackFlow
        }
        
        val packNames = packsNeeded.map { it.packName }.toSet()
        val packStates = mutableMapOf<String, AssetPackState>()
        
        trySend(InstallState.Pending)
        
        val listener = AssetPackStateUpdateListener { state ->
            if (state.name() !in packNames) return@AssetPackStateUpdateListener
            
            packStates[state.name()] = state
            
            val totalBytes = packStates.values.sumOf { it.totalBytesToDownload() }
            val downloadedBytes = packStates.values.sumOf { it.bytesDownloaded() }
            
            val failedPack = packStates.values.find { it.status() == AssetPackStatus.FAILED }
            if (failedPack != null) {
                trySend(InstallState.Failed("Pack ${failedPack.name()} failed: ${failedPack.errorCode()}"))
                return@AssetPackStateUpdateListener
            }
            
            val allCompleted = packNames.all { packName ->
                packStates[packName]?.status() == AssetPackStatus.COMPLETED
            }
            
            if (allCompleted) {
                trySend(InstallState.Extracting)
                try {
                    packsNeeded.forEach { p ->
                        extractAssetPackSync(context, manager, p)
                    }
                    trySend(InstallState.Completed)
                    close()
                } catch (e: Exception) {
                    trySend(InstallState.Failed("Extraction failed: ${e.message}"))
                    close()
                }
            } else if (totalBytes > 0) {
                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                trySend(InstallState.Downloading(progress))
            }
        }
        
        manager.registerListener(listener)
        
        try {
            manager.fetch(packNames.toList())
        } catch (e: Exception) {
            trySend(InstallState.Failed("Failed to start download: ${e.message}"))
            close()
        }
        
        awaitClose {
            manager.unregisterListener(listener)
        }
    }
    
    /**
     * Get the path to the extracted components of an asset pack.
     * Use this to access non-binary assets like models.
     */
    fun getExtractedPath(context: Context, pack: AssetPack): String? {
        if (isFatApkBuild()) {
            val extractedDir = File(getBinariesDir(context), pack.packName)
            return extractedDir.absolutePath.takeIf { extractedDir.exists() }
        }

        val manager = getAssetPackManager(context)
        val packLocation = manager.getPackLocation(pack.packName)
        return packLocation?.assetsPath()
    }

    /**
     * Extract binaries from an asset pack to the executable directory (synchronous)
     */
    private fun extractAssetPackSync(
        context: Context, 
        manager: AssetPackManager, 
        pack: AssetPack
    ) {
        val packLocation = manager.getPackLocation(pack.packName)
        if (packLocation == null) {
            Log.w(TAG, "Pack location not found for ${pack.packName}")
            return
        }
        
        val assetsPath = packLocation.assetsPath()
        val binDir = getBinariesDir(context)
        if (assetsPath == null) {
            Log.w(TAG, "Asset path is null for ${pack.packName}")
            File(binDir, ".${pack.packName}_extracted").createNewFile()
            return
        }
        
        // Extract everything relative to assets root
        // For binaries, we don't need them here anymore (they are in feature module).
        // But for models (Upscaler), we need them in filesDir to be accessible?
        // Actually, if models are in assets, we can access them via AssetManager directly IF they are valid assets.
        // But Play Asset Delivery "fast-follow" or "on-demand" packs might need extraction if we need File objects (e.g. for C++ libraries).
        // RealESRGAN ncnn expects model paths. Using filesDir is safer.
        
        val nativeDir = File(assetsPath) // Extract everything from the pack's assets
        
        if (!nativeDir.exists()) {
             Log.w(TAG, "Asset directory not found: ${nativeDir.absolutePath}")
             // Mark as extracted to avoid loops
             File(binDir, ".${pack.packName}_extracted").createNewFile()
             return
        }
        
        // Copy all files recursively
        nativeDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                // Determine relative path
                val relativePath = file.relativeTo(nativeDir).path
                val destFile = File(binDir, relativePath)
                
                destFile.parentFile?.mkdirs()
                file.copyTo(destFile, overwrite = true)
                
                // If it happens to be executable (unlikely now), set perms
                if (file.extension == "so" || file.canExecute()) {
                    destFile.setExecutable(true)
                }
                Log.d(TAG, "Extracted: $relativePath")
            }
        }
        
        // Create marker file
        File(binDir, ".${pack.packName}_extracted").createNewFile()
        
        Log.i(TAG, "Asset pack ${pack.packName} extracted successfully")
    }
    
    /**
     * Extract binaries from an asset pack to the executable directory (async)
     */
    suspend fun extractAssetPack(
        context: Context,
        pack: AssetPack
    ) = withContext(Dispatchers.IO) {
        val manager = getAssetPackManager(context)
        extractAssetPackSync(context, manager, pack)
    }
    
    /**
     * Get the total estimated size of all asset packs in MB
     */
    fun getTotalSizeMB(): Int {
        // Approximate sizes based on the binaries
        return 550 // ~550MB total for all packs
    }
}
