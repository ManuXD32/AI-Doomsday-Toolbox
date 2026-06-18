package com.example.llamadroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for extracting and managing tier-specific native binaries.
 * 
 * Binaries are stored in jniLibs as:
 *   lib<name>_baseline.so
 *   lib<name>_dotprod.so
 *   lib<name>_armv9.so
 * 
 * At runtime, this class extracts the correct tier binary to the app's
 * native lib directory and returns the path.
 */
object BinaryLoader {
    private const val TAG = "BinaryLoader"
    
    // Binary names (without lib prefix and tier suffix)
    private val BINARIES = listOf(
        "ffmpeg",
        "ffprobe",
        "whisper-cli",
        "llama_server",
        "mtmd",
        "sd"
    )
    
    private var tier: String? = null
    private val binaryPaths = mutableMapOf<String, String>()
    
    /**
     * Initialize the binary loader with CPU detection.
     * Call this early in app startup.
     */
    fun init(context: Context) {
        tier = CpuFeatures.getTier()
        Log.i(TAG, "Initialized with tier: $tier")
        
        // Pre-extract all binaries
        BINARIES.forEach { name ->
            extractBinary(context, name)
        }
    }
    
    /**
     * Get the path to a specific binary.
     * Returns null if the binary is not available.
     */
    fun getBinaryPath(context: Context, name: String): String? {
        // Check cache first
        binaryPaths[name]?.let { return it }
        
        // Try to extract
        return extractBinary(context, name)
    }
    
    /**
     * Extract a tier-specific binary from jniLibs to filesDir.
     */
    private fun extractBinary(context: Context, name: String): String? {
        val currentTier = tier ?: CpuFeatures.getTier().also { tier = it }
        
        // Try tiers from best to worst (fallback chain)
        val tiersToTry = when (currentTier) {
            "armv9" -> listOf("armv9", "dotprod", "baseline")
            "dotprod" -> listOf("dotprod", "baseline")
            else -> listOf("baseline")
        }
        
        for (tryTier in tiersToTry) {
            val libName = "lib${name}_${tryTier}.so"
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val sourcePath = File(nativeLibDir, libName)
            
            if (sourcePath.exists()) {
                // Binary is already in the correct location
                val path = sourcePath.absolutePath
                binaryPaths[name] = path
                Log.i(TAG, "Found $name binary at: $path (tier: $tryTier)")
                return path
            }
        }
        
        Log.w(TAG, "Binary not found: $name (tried tiers: $tiersToTry)")
        return null
    }
    
    /**
     * Get the current tier.
     */
    fun getCurrentTier(): String {
        return tier ?: CpuFeatures.getTier().also { tier = it }
    }
    
    /**
     * Check if all required binaries are available.
     */
    fun checkBinaries(context: Context): Map<String, Boolean> {
        return BINARIES.associateWith { name ->
            getBinaryPath(context, name) != null
        }
    }
}
