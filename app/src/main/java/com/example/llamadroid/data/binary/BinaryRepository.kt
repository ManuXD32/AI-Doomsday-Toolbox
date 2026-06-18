package com.example.llamadroid.data.binary

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.CpuFeatures
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.DynamicFeatureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Repository for managing native binaries with CPU tier support.
 * 
 * Binaries are stored with tier suffixes: libname_baseline.so, libname_dotprod.so, libname_armv9.so
 * At runtime, the best available tier is selected based on CPU features.
 */
class BinaryRepository(private val context: Context) {
    companion object {
        private const val TAG = "BinaryRepository"
        
        // Required files for llama.cpp server (for custom binary upload screen)
        val REQUIRED_FILES = listOf(
            "llama-server" to "libllama_server.so",
            "libllama.so" to "libllama.so",
            "libggml.so" to "libggml.so",
            "libggml-base.so" to "libggml-base.so",
            "libggml-cpu.so" to "libggml-cpu.so",
            "libmtmd.so" to "libmtmd.so"
        )
        
        // Binary names (without lib prefix and tier suffix)
        private val TIERED_BINARIES = listOf(
            "ffmpeg",
            "ffprobe",
            "whisper-cli",
            "llama_server",
            "llama-bench",
            "mtmd",
            "sd",
            "kiwix-serve",
            "kiwix-manage",
            "quadtrix_trainer"
        )
        
        // Preference keys
        private const val PREFS_NAME = "llamadroid_settings"
        private const val KEY_PREFERRED_TIER = "preferred_cpu_tier"

        // Required shared libraries (not tiered, always same version)
        val SHARED_LIBS = listOf(
            "libllama.so",
            "libllama.so.0.so",
            "libggml.so",
            "libggml.so.0.so",
            "libggml-base.so",
            "libggml-base.so.0.so",
            "libggml-cpu.so",
            "libggml-cpu.so.0.so",
            "libwhisper.so.1.so"
        )

        internal fun buildBinarySearchTiers(selectedTier: String, deviceTier: String): List<String> {
            val preferred = tiersForSelectionStatic(selectedTier)
            val deviceFallbacks = tiersForSelectionStatic(deviceTier)
            return (preferred + deviceFallbacks).distinct()
        }

        private fun tiersForSelectionStatic(tier: String): List<String> = when (tier) {
            "armv9" -> listOf("armv9", "dotprod", "baseline")
            "dotprod" -> listOf("dotprod", "baseline")
            else -> listOf("baseline")
        }

        internal fun acceleratorLibNames(
            name: String,
            accelerationMode: String = SettingsRepository.ACCELERATION_AUTO
        ): List<String> {
            val normalizedMode = SettingsRepository.normalizeAccelerationMode(accelerationMode)
            if (normalizedMode == SettingsRepository.ACCELERATION_CPU) return emptyList()

            return when (name) {
                "llama_server" -> when (normalizedMode) {
                    SettingsRepository.ACCELERATION_GPU -> listOf("libllama_server_snapdragon_opencl.so")
                    else -> listOf("libllama_server_snapdragon_opencl.so")
                }
                "llama-bench" -> when (normalizedMode) {
                    SettingsRepository.ACCELERATION_GPU -> listOf("libllama-bench_snapdragon_opencl.so")
                    else -> listOf("libllama-bench_snapdragon_opencl.so")
                }
                "sd" -> emptyList()
                else -> emptyList()
            }
        }
    }
    
    private var cachedTier: String? = null
    private val settingsRepository by lazy { SettingsRepository(context) }
    
    /**
     * Get the current CPU tier (cached).
     */
    fun getTier(): String {
        // Check for user override
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val override = prefs.getString(KEY_PREFERRED_TIER, "auto")
        
        if (override != null && override != "auto") {
            Log.i(TAG, "Using forced CPU tier: $override")
            return override
        }

        if (cachedTier == null) {
            cachedTier = CpuFeatures.getTier()
            Log.i(TAG, "Detected CPU tier: $cachedTier")
        }
        return cachedTier!!
    }

    private fun tiersForSelection(tier: String): List<String> = when (tier) {
        "armv9" -> listOf("armv9", "dotprod", "baseline")
        "dotprod" -> listOf("dotprod", "baseline")
        else -> listOf("baseline")
    }

    private fun nativeLibraryCandidateDirs(): List<File> {
        val primary = File(context.applicationInfo.nativeLibraryDir)
        val dirs = linkedSetOf<File>()
        dirs += primary

        val packageRoot = primary.parentFile?.parentFile
        if (packageRoot != null && packageRoot.exists()) {
            runCatching {
                packageRoot.walkTopDown()
                    .maxDepth(4)
                    .filter { it.isDirectory }
                    .filter { dir ->
                        dir == primary ||
                            dir.name in setOf("arm64", "arm64-v8a") ||
                            dir.listFiles()?.any { it.extension == "so" } == true
                    }
                    .forEach { dirs += it }
            }
        }

        context.applicationInfo.splitSourceDirs
            ?.mapNotNull { File(it).parentFile }
            ?.forEach { splitParent ->
                listOf(
                    File(splitParent, "lib/${CpuFeatures.getArch()}"),
                    File(splitParent, "lib/arm64"),
                    File(splitParent, "lib/arm64-v8a")
                ).filter { it.exists() }.forEach { dirs += it }
            }

        return dirs.filter { it.exists() }
    }

    private fun findAcceleratorBinaries(acceleratorNames: List<String>): List<File> {
        val results = linkedMapOf<String, File>()

        fun addFromDir(dir: File) {
            for (libName in acceleratorNames) {
                val file = File(dir, libName)
                if (file.exists()) {
                    results.putIfAbsent(file.absolutePath, file)
                }
            }
        }

        nativeLibraryCandidateDirs().forEach(::addFromDir)

        listOf(
            "com.example.llamadroid.feature.llm.snapdragon.opencl"
        ).forEach { pkgName ->
            runCatching {
                val featureContext = context.createPackageContext(pkgName, 0)
                addFromDir(File(featureContext.applicationInfo.nativeLibraryDir))
            }
        }

        listOf(
            "feature_llm_snapdragon_opencl"
        ).forEach { splitName ->
            val splitDir = File(context.filesDir.parent, "split_$splitName")
            if (splitDir.exists()) {
                listOf(CpuFeatures.getArch(), "arm64", "arm64-v8a")
                    .map { File(splitDir, "lib/$it") }
                    .filter { it.exists() }
                    .forEach(::addFromDir)
            }
        }

        return results.values.toList()
    }

    private fun canUseDeployedBinExecutables(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Get path to a tiered binary, with fallback to lower tiers.
     * 
     * @param name Binary name without "lib" prefix (e.g., "ffmpeg", "llama-bench")
     * @return File path to the binary, or null if not found
     */
    /**
     * Get path to a tiered binary, with fallback to lower tiers.
     * 
     * @param name Binary name without "lib" prefix (e.g., "ffmpeg", "llama-bench")
     * @return File path to the binary, or null if not found
     */
    /**
     * Get path to a tiered binary, with fallback to lower tiers.
     * 
     * @param name Binary name without "lib" prefix (e.g., "ffmpeg", "llama-bench")
     * @return File path to the binary, or null if not found
     */
    fun getTieredBinary(name: String): File? {
        return getTieredBinary(name, getTier())
    }

    fun getCpuTieredBinary(name: String): File? {
        val deviceTier = getTier()
        val tiersToTry = buildBinarySearchTiers(deviceTier, deviceTier)
        return findTieredBinary(name, deviceTier, tiersToTry, allowAccelerator = false)
    }

    fun getAcceleratorBinaries(name: String): List<File> {
        if (!DeviceAcceleration.isSnapdragonCompatible()) return emptyList()
        val acceleratorNames = acceleratorLibNames(name, accelerationModeFor(name))
        if (acceleratorNames.isEmpty()) return emptyList()
        return findAcceleratorBinaries(acceleratorNames)
    }

    private fun accelerationModeFor(name: String): String = when (name) {
        "llama_server",
        "llama-bench" -> settingsRepository.llmAccelerationMode.value
        "sd" -> settingsRepository.stableDiffusionAccelerationMode.value
        else -> SettingsRepository.ACCELERATION_AUTO
    }

    private fun getTieredBinary(name: String, selectedTier: String): File? {
        val deviceTier = getTier()
        val tiersToTry = buildBinarySearchTiers(selectedTier, deviceTier)
        return findTieredBinary(name, selectedTier, tiersToTry)
    }

    private fun findTieredBinary(
        name: String,
        selectedTier: String,
        tiersToTry: List<String>,
        allowAccelerator: Boolean = true
    ): File? {
        if (!DynamicFeatureManager.isNativeLibsReady(context)) {
            Log.w(TAG, "Native libs modules not fully ready yet; probing available paths for $name anyway")
        }
        
        val nativeLibDirs = nativeLibraryCandidateDirs()
        val accelerationMode = accelerationModeFor(name)
        val acceleratorNames = if (allowAccelerator && DeviceAcceleration.isSnapdragonCompatible()) {
            acceleratorLibNames(name, accelerationMode)
        } else {
            emptyList()
        }
        DebugLog.log(
            "$TAG: Resolving $name allowAccelerator=$allowAccelerator accelerationMode=$accelerationMode modules=" +
                "${DynamicFeatureManager.getOptionalAcceleratorModules().associateWith { DynamicFeatureManager.isModuleInstalled(context, it) }} dirs=" +
                nativeLibDirs.joinToString { it.absolutePath }
        )
        
        // Strategy 1: Check installed native lib dirs. Android 10+ restricts W^X, so
        // native payloads must be executed from package/split paths when possible.
        if (acceleratorNames.isNotEmpty()) {
            for (dir in nativeLibDirs) {
                for (libName in acceleratorNames) {
                    val file = File(dir, libName)
                    if (file.exists()) {
                        DebugLog.log("$TAG: Found accelerator $name at ${file.absolutePath}")
                        return file
                    }
                }
            }
        }

        for (dir in nativeLibDirs) {
            for (tryTier in tiersToTry) {
                val libName = "lib${name}_${tryTier}.so"
                val file = File(dir, libName)

                if (file.exists()) {
                    DebugLog.log("$TAG: Found $name at ${file.absolutePath} (tier: $tryTier)")
                    return file
                }
            }
        }
        
        // Strategy 2: Check Feature Module Contexts (Split APKs)
        // On some devices, splits have their own nativeLibraryDir. We must execute from THERE.
        val featureSearchTiers = tiersToTry
        val featurePackages = buildList {
            if (acceleratorNames.isNotEmpty()) {
                add("com.example.llamadroid.feature.llm.snapdragon.opencl")
            }
            featureSearchTiers.forEach { tier ->
                add("com.example.llamadroid.feature.llm.$tier")
                add("com.example.llamadroid.feature.media.$tier")
                add("com.example.llamadroid.feature.kiwix.$tier")
            }
            add("com.example.llamadroid.feature.upscaler")
        }.distinct()

        for (pkgName in featurePackages) {
            try {
                val featureContext = context.createPackageContext(pkgName, 0)
                val featureLibDir = File(featureContext.applicationInfo.nativeLibraryDir)
                
                if (featureLibDir.exists()) {
                    if (acceleratorNames.isNotEmpty()) {
                        for (libName in acceleratorNames) {
                            val sourceFile = File(featureLibDir, libName)
                            if (sourceFile.exists()) {
                                DebugLog.log("$TAG: Found accelerator $name in feature dir at ${sourceFile.absolutePath}")
                                return sourceFile
                            }
                        }
                    }
                    for (tryTier in tiersToTry) {
                        val libName = "lib${name}_${tryTier}.so"
                        val sourceFile = File(featureLibDir, libName)
                        
                        if (sourceFile.exists()) {
                            // EXECUTE DIRECTLY from feature lib dir. Do NOT copy.
                            DebugLog.log("$TAG: Found $name in feature dir at ${sourceFile.absolutePath}")
                            return sourceFile
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore package not found
            }
        }
        
        // Strategy 3: Check Legacy Split Directories (Backup for older Android versions)
        try {
            val splitDirs = buildList {
                if (acceleratorNames.isNotEmpty()) {
                    add("feature_llm_snapdragon_opencl")
                }
                featureSearchTiers.forEach { tier ->
                    add("feature_llm_$tier")
                    add("feature_kiwix_$tier")
                    add("feature_media_$tier")
                }
            }.distinct()

            for (splitName in splitDirs) {
                val splitDir = File(context.filesDir.parent, "split_$splitName")
                if (splitDir.exists()) {
                    val abis = listOf(CpuFeatures.getArch(), "arm64", "arm64-v8a")
                    val searchDirs = abis.map { File(splitDir, "lib/$it") }

                    for (dir in searchDirs) {
                        if (dir.exists()) {
                            if (acceleratorNames.isNotEmpty()) {
                                for (libName in acceleratorNames) {
                                    val file = File(dir, libName)
                                    if (file.exists()) {
                                        DebugLog.log("$TAG: Found accelerator $name in split dir at ${file.absolutePath}")
                                        return file
                                    }
                                }
                            }
                            for (tryTier in tiersToTry) {
                                val libName = "lib${name}_${tryTier}.so"
                                val file = File(dir, libName)
                                if (file.exists()) {
                                    DebugLog.log("$TAG: Found $name in split dir at ${file.absolutePath}")
                                    return file
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check split dir", e)
        }
        
        // Strategy 4: Fallback to deployed 'bin' dir only on old Android releases.
        // Android 10+ enforces W^X for app-data executables, so copied payloads in
        // files/bin are useful as diagnostics but not valid process candidates.
        val deployedBinDir = File(context.filesDir, "bin")
        if (acceleratorNames.isNotEmpty() && deployedBinDir.exists()) {
            DebugLog.log("$TAG: Skipping app-data accelerator copies in ${deployedBinDir.absolutePath}; Android cannot execute these reliably.")
        }
        if (!canUseDeployedBinExecutables()) {
            if (deployedBinDir.exists()) {
                DebugLog.log("$TAG: Skipping deployed app-data binaries in ${deployedBinDir.absolutePath} on Android ${Build.VERSION.SDK_INT}.")
            }
            Log.w(TAG, "Binary not found: $name (selected tier: $selectedTier, tried tiers: $tiersToTry)")
            return null
        }
        for (tryTier in tiersToTry) {
            val libName = "lib${name}_${tryTier}.so"
            val file = File(deployedBinDir, libName)
            if (file.exists()) {
                 DebugLog.log("$TAG: Found $name in deployed dir at ${file.absolutePath} (May fail with Permission Denied)")
                return file
            }
        }

        Log.w(TAG, "Binary not found: $name (selected tier: $selectedTier, tried tiers: $tiersToTry)")
        return null
    }

    /**
     * Get the llama-server executable (tiered).
     */
    suspend fun getExecutable(): File? = withContext(Dispatchers.IO) {
        // First check for user-uploaded binary
        val customBinDir = File(context.filesDir, "binaries")
        val customServer = File(customBinDir, "libllama_server.so")
        
        if (customServer.exists() && customServer.canExecute()) {
            DebugLog.log("$TAG: Using custom binary: ${customServer.absolutePath}")
            return@withContext customServer
        }
        
        // Use tiered binary
        return@withContext getTieredBinary("llama_server")
    }

    suspend fun getCpuExecutable(): File? = withContext(Dispatchers.IO) {
        getCpuTieredBinary("llama_server")
    }

    /**
     * Get the library directory path - needed for LD_LIBRARY_PATH
     */
    fun getLibraryDir(): String {
        val paths = mutableListOf<String>()
        val customBinDir = File(context.filesDir, "binaries") // User uploaded
        if (customBinDir.exists() && customBinDir.listFiles()?.isNotEmpty() == true) {
            paths.add(customBinDir.absolutePath)
        }
        
        // Add system/split native lib dirs (preferred for package libraries)
        nativeLibraryCandidateDirs().forEach { paths.add(it.absolutePath) }
        
        // Add centralized asset binaries directory (Fallback)
        val assetBinDir = com.example.llamadroid.util.AssetPackManagerUtil.getBinariesDir(context)
        if (assetBinDir.exists()) {
            paths.add(assetBinDir.absolutePath)
        }

        // Add deployed bin dir (Primary execution env)
        val deployedBinDir = File(context.filesDir, "bin")
        if (deployedBinDir.exists()) {
            paths.add(0, deployedBinDir.absolutePath) // Prepend to prefer it
        }
        
        return paths.joinToString(":")
    }
    
    /**
     * Get ffmpeg binary (tiered).
     */
    fun getFFmpegBinary(): File? = getTieredBinary("ffmpeg")
    
    /**
     * Get ffprobe binary (tiered).
     */
    fun getFFprobeBinary(): File? = getTieredBinary("ffprobe")

    /**
     * Get whisper-cli binary (tiered).
     */
    fun getWhisperCliBinary(): File? = getTieredBinary("whisper-cli")
    
    /**
     * Get llama-server binary (tiered).
     */
    fun getLlamaServerBinary(): File? = getTieredBinary("llama_server")
    
    /**
     * Get mtmd (multimodal) binary (tiered).
     */
    fun getMtmdBinary(): File? = getTieredBinary("mtmd")
    
    /**
     * Get stable-diffusion binary (tiered).
     */
    fun getSdBinary(): File? = getTieredBinary("sd")

    fun getCpuSdBinary(): File? = getCpuTieredBinary("sd")
    
    /**
     * Get llama-bench binary (tiered) for benchmarking.
     */
    fun getLlamaBenchBinary(): File? = getTieredBinary("llama-bench")

    /**
     * Get kiwix-serve binary (for serving ZIM files).
     * Note: kiwix binaries may not be tiered yet - fall back to non-tiered if needed
     */
    fun getKiwixServeBinary(): File? = getTieredBinary("kiwix-serve") 
        ?: File(context.applicationInfo.nativeLibraryDir, "libkiwix-serve.so").takeIf { it.exists() }
    
    /**
     * Get kiwix-manage binary (for managing ZIM libraries).
     */
    fun getKiwixManageBinary(): File? = getTieredBinary("kiwix-manage") 
        ?: File(context.applicationInfo.nativeLibraryDir, "libkiwix-manage.so").takeIf { it.exists() }
    
    /**
     * Gets the llama-embedding executable from the native library directory.
     */
    suspend fun getEmbeddingExecutable(): File? = withContext(Dispatchers.IO) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val embeddingFile = File(nativeLibDir, "libllama_embedding.so")
        
        if (embeddingFile.exists()) {
            return@withContext embeddingFile
        }
        return@withContext null
    }
    
    fun getLocalVersion(): String? {
        val customBinDir = File(context.filesDir, "binaries")
        val customServer = File(customBinDir, "libllama_server.so")
        if (customServer.exists()) return "Custom"
        
        val serverFile = getTieredBinary("llama_server")
        if (serverFile == null) return null
        return if (DeviceAcceleration.isAcceleratorBinary(serverFile)) {
            "Bundled Snapdragon"
        } else {
            "Bundled (${getTier()})"
        }
    }
    
    /**
     * Check if custom binaries are installed
     */
    fun hasCustomBinaries(): Boolean {
        val customBinDir = File(context.filesDir, "binaries")
        val customServer = File(customBinDir, "libllama_server.so")
        return customServer.exists()
    }
    
    /**
     * Check availability of all tiered binaries.
     */
    fun checkBinaries(): Map<String, Boolean> {
        return TIERED_BINARIES.associateWith { name ->
            getTieredBinary(name) != null
        }
    }
    
    /**
     * Log all binary paths for debugging.
     */
    fun logBinaryPaths() {
        Log.i(TAG, "CPU Tier: ${getTier()}")
        Log.i(TAG, "Native lib dir: ${context.applicationInfo.nativeLibraryDir}")
        
        TIERED_BINARIES.forEach { name ->
            val file = getTieredBinary(name)
            if (file != null) {
                Log.i(TAG, "  $name: ${file.absolutePath}")
            } else {
                Log.w(TAG, "  $name: NOT FOUND")
            }
        }
    }
    
    /**
     * Install a custom binary file from a Uri
     */
    suspend fun installBinaryFromUri(uri: Uri, targetName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val customBinDir = File(context.filesDir, "binaries")
            customBinDir.mkdirs()
            
            val destFile = File(customBinDir, targetName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Make executable
            destFile.setExecutable(true, false)
            
            DebugLog.log("$TAG: Installed custom binary: $targetName (${destFile.length()} bytes)")
            return@withContext true
        } catch (e: Exception) {
            DebugLog.log("$TAG: Failed to install $targetName: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Delete all custom binaries
     */
    suspend fun deleteCustomBinaries() = withContext(Dispatchers.IO) {
        val customBinDir = File(context.filesDir, "binaries")
        if (customBinDir.exists()) {
            customBinDir.deleteRecursively()
            DebugLog.log("$TAG: Deleted custom binaries")
        }
    }

        /**
     * EXTRACT only the NEEDED binaries to filesDir/bin.
     * Selects the best tier for the current device and ignores others.
     * Prioritizes Native Library Directory (OS extracted) for reliability.
     */
    suspend fun deployAllBinaries(): Boolean = withContext(Dispatchers.IO) {
        if (!DynamicFeatureManager.isNativeLibsReady(context)) return@withContext false

        val deployedBinDir = File(context.filesDir, "bin")
        if (!deployedBinDir.exists()) deployedBinDir.mkdirs()
        
        val deviceTier = getTier()
        // Tiers preference: Device Tier -> ... -> Baseline
        val tiersToTry = when (deviceTier) {
            "armv9" -> listOf("armv9", "dotprod", "baseline")
            "dotprod" -> listOf("dotprod", "baseline")
            else -> listOf("baseline")
        }

        Log.i(TAG, "Deploying binaries for tier: $deviceTier (fallback: $tiersToTry)")

        // Track active binaries
        val activeBinaries = mutableSetOf<String>()

        // 1. Try Native Library Dir (OS Extracted) - PRIORITY for useLegacyPackaging=true
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        if (nativeDir.exists()) {
             Log.d(TAG, "Scanning nativeLibraryDir: ${nativeDir.absolutePath}")
             val deployed = scanAndCopy(nativeDir, deployedBinDir, tiersToTry)
             activeBinaries.addAll(deployed)
        }

        // 2. Try Feature Contexts (for split installs)
        val fromFeatures = legacyDeploy(deployedBinDir, tiersToTry)
        activeBinaries.addAll(fromFeatures)

        // 3. Fallback: APK Extraction (If nothing found or partial)
        if (activeBinaries.isEmpty() || !areCriticalBinariesPresent(activeBinaries)) {
            Log.w(TAG, "Binaries missing from native/legacy paths. Attempting APK extraction...")
            val splitDirs = context.applicationInfo.splitSourceDirs
            val apkPaths = (splitDirs ?: emptyArray()).toMutableList()
            if (context.applicationInfo.sourceDir != null) {
                apkPaths.add(context.applicationInfo.sourceDir)
            }
            
            apkPaths.forEach { apkPath ->
                try {
                    val deployed = extractFromApk(File(apkPath), deployedBinDir, tiersToTry)
                    if (deployed.isNotEmpty()) {
                        activeBinaries.addAll(deployed)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract from $apkPath", e)
                }
            }
        }
        
        // 4. Cleanup
        if (activeBinaries.isNotEmpty()) {
            deployedBinDir.listFiles()?.forEach { file ->
                if (file.extension == "so") {
                    if (!activeBinaries.contains(file.name)) {
                         // Check if it's a custom binary? (Usually in 'binaries' dir, not 'bin')
                         // But be safe and delete tiered mismatches.
                        if (file.delete()) {
                            Log.v(TAG, "Deleted unused/stale binary: ${file.name}")
                        }
                    }
                }
            }
        }
        
        Log.i(TAG, "Smart binary deployment complete. Active: $activeBinaries")
        return@withContext activeBinaries.isNotEmpty()
    }
    
    // Check if critical binaries are present
    private fun areCriticalBinariesPresent(binaries: Set<String>): Boolean {
        // Just check for a few key ones to decide if we need fallback
        return binaries.any { it.startsWith("libllama") } &&
               binaries.any { it.startsWith("libffmpeg") }
    }

    private fun scanAndCopy(sourceDir: File, destDir: File, tiers: List<String>): List<String> {
        val deployedFiles = mutableListOf<String>()
        val files = sourceDir.listFiles()?.filter { it.extension == "so" } ?: emptyList()
        if (files.isEmpty()) return emptyList()

        // Group by binary name
        val fileGroups = files.groupBy { file ->
            val filename = file.name
            var key = filename
            if (filename.startsWith("lib") && filename.endsWith(".so")) {
                val bareName = filename.substring(3, filename.length - 3)
                val parts = bareName.split("_")
                if (parts.size > 1) {
                    val potentialTier = parts.last()
                    if (potentialTier in listOf("baseline", "dotprod", "armv9")) {
                        key = bareName.substringBeforeLast("_")
                    }
                }
            }
            key
        }

        fileGroups.forEach { (_, groupFiles) ->
            val isTiered = groupFiles.any { f -> 
                val n = f.name
                n.contains("_baseline.so") || n.contains("_dotprod.so") || n.contains("_armv9.so")
            }

            val bestFile = if (isTiered) {
                tiers.firstNotNullOfOrNull { tier ->
                    groupFiles.find { it.name.endsWith("_$tier.so") }
                }
            } else {
                groupFiles.firstOrNull()
            } ?: return@forEach

            val destFile = File(destDir, bestFile.name)
            try {
                // Only copy if size differs or missing (simple check)
                // Or if we want to ensure executable bit?
                // Files in nativeLibraryDir are not writable/executable by us usually?
                // We copy to filesDir to make them executable if needed (though shared libs usually don't need +x unless executed directly)
                // Binaries like ffmpeg DO need +x.
                if (!destFile.exists() || destFile.length() != bestFile.length()) {
                    bestFile.copyTo(destFile, overwrite = true)
                    destFile.setExecutable(true)
                    Log.v(TAG, "Copied ${bestFile.name} from ${sourceDir.absolutePath}")
                }
                deployedFiles.add(bestFile.name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy ${bestFile.name}", e)
            }
        }
        return deployedFiles
    }

    /**
     * Extract binaries from APK and return list of filenames deployed.
     */
    private fun extractFromApk(apkFile: File, destDir: File, tiers: List<String>): List<String> {
        val deployedFiles = mutableListOf<String>()
        val zip = java.util.zip.ZipFile(apkFile)
        try {
            val entries = zip.entries()
            val soEntries = mutableListOf<java.util.zip.ZipEntry>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".so")) {
                    soEntries.add(entry)
                }
            }

            // Group by binary name
            val fileGroups = soEntries.groupBy { entry ->
                val filename = File(entry.name).name 
                var key = filename
                
                if (filename.startsWith("lib") && filename.endsWith(".so")) {
                    val bareName = filename.substring(3, filename.length - 3)
                    val parts = bareName.split("_")
                    if (parts.size > 1) {
                        val potentialTier = parts.last()
                         if (potentialTier in listOf("baseline", "dotprod", "armv9")) {
                            key = bareName.substringBeforeLast("_")
                        }
                    }
                }
                key
            }

            fileGroups.forEach { (_, groupEntries) ->
                val isTiered = groupEntries.any { e ->
                    val n = File(e.name).name
                    n.contains("_baseline.so") || n.contains("_dotprod.so") || n.contains("_armv9.so")
                }

                val bestEntry = if (isTiered) {
                    tiers.firstNotNullOfOrNull { tier ->
                        groupEntries.find { File(it.name).name.endsWith("_$tier.so") }
                    }
                } else {
                    groupEntries.firstOrNull()
                } ?: return@forEach

                val filename = File(bestEntry.name).name
                val destFile = File(destDir, filename)

                if (!destFile.exists() || destFile.length() != bestEntry.size) {
                    zip.getInputStream(bestEntry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.setExecutable(true)
                    Log.v(TAG, "Extracted $filename")
                }
                deployedFiles.add(filename)
            }

        } finally {
            zip.close()
        }
        return deployedFiles
    }

    /**
     * Fallback deploy using PackageContext (if APK extraction fails).
     */
    private fun legacyDeploy(destDir: File, tiers: List<String>): List<String> {
         val deployedFiles = mutableListOf<String>()
         
         val currentTier = getTier()
         val featurePackages = listOf(
            "com.example.llamadroid.feature.llm.$currentTier",
            "com.example.llamadroid.feature.media.$currentTier",
            "com.example.llamadroid.feature.kiwix.$currentTier",
            "com.example.llamadroid.feature.upscaler"
        )
        
        featurePackages.forEach { pkgName ->
             try {
                val featureContext = context.createPackageContext(pkgName, 0)
                val featureLibDir = File(featureContext.applicationInfo.nativeLibraryDir)
                
                if (featureLibDir.exists() && featureLibDir.isDirectory) {
                    val files = featureLibDir.listFiles()?.filter { it.extension == "so" } ?: emptyList()
                    
                    // Grouping Logic (Same as APK)
                    val fileGroups = files.groupBy { file ->
                        val name = file.name
                        var key = name
                        if (name.startsWith("lib") && name.endsWith(".so")) {
                            val bareName = name.substring(3, name.length - 3)
                            val parts = bareName.split("_")
                            if (parts.size > 1) {
                                val potentialTier = parts.last()
                                if (potentialTier in listOf("baseline", "dotprod", "armv9")) {
                                    key = bareName.substringBeforeLast("_")
                                }
                            }
                        }
                        key
                    }
                    
                    fileGroups.forEach groupLoop@ { (_, groupFiles) ->
                        val isTiered = groupFiles.any { f ->
                            val n = f.name
                            n.contains("_baseline.so") || n.contains("_dotprod.so") || n.contains("_armv9.so")
                        }

                        val bestFile = if (isTiered) {
                            tiers.firstNotNullOfOrNull { tier ->
                                groupFiles.find { it.name.endsWith("_$tier.so") }
                            }
                        } else {
                            groupFiles.firstOrNull()
                        } ?: return@groupLoop

                        val destFile = File(destDir, bestFile.name)
                        try {
                            if (!destFile.exists() || destFile.length() != bestFile.length()) {
                                bestFile.copyTo(destFile, overwrite = true)
                                destFile.setExecutable(true)
                            }
                            deployedFiles.add(bestFile.name)
                        } catch (e: Exception) {
                            Log.e(TAG, "Legacy copy failed for ${bestFile.name}", e)
                        }
                    }
                }
             } catch (e: Exception) {
                 // Ignore
             }
        }
        return deployedFiles
    }

}
