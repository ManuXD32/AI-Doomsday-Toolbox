package com.example.llamadroid.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File

/**
 * Utility to resolve SAF (Storage Access Framework) URIs to real file paths.
 * This enables native binaries to access files on SD cards and external storage.
 */
object FilePathResolver {
    
    /**
     * Try to resolve a SAF URI to a real file path that native code can access.
     * Returns null if the path cannot be resolved or is not accessible.
     * 
     * Works for:
     * - Primary external storage (internal SD)
     * - Secondary external storage (removable SD card)
     * - USB OTG storage
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        DebugLog.log("[FilePathResolver] Resolving URI: $uri")
        
        // Handle content:// URIs
        if (uri.scheme == "content") {
            return resolveContentUri(context, uri)
        }
        
        // Handle file:// URIs directly
        if (uri.scheme == "file") {
            return uri.path
        }
        
        return null
    }

    fun getPathFromTreeUri(context: Context, uri: Uri): String? {
        DebugLog.log("[FilePathResolver] Resolving tree URI: $uri")
        return try {
            if (!DocumentsContract.isTreeUri(uri)) {
                return getPathFromUri(context, uri)
            }
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size != 2) return null
            val storageId = split[0]
            val relativePath = split[1]

            if (storageId == "primary") {
                val path = "${android.os.Environment.getExternalStorageDirectory()}/$relativePath"
                if (File(path).exists()) return path
            }

            val sdCardPaths = getExternalStoragePaths(context)
            for (sdPath in sdCardPaths) {
                val fullPath = "$sdPath/$relativePath"
                if (File(fullPath).exists()) return fullPath

                val altPath = "/storage/$storageId/$relativePath"
                if (File(altPath).exists()) return altPath
            }
            null
        } catch (e: Exception) {
            DebugLog.log("[FilePathResolver] Error resolving tree URI: ${e.message}")
            null
        }
    }
    
    private fun resolveContentUri(context: Context, uri: Uri): String? {
        try {
            // Check if it's a document URI
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val authority = uri.authority
                
                DebugLog.log("[FilePathResolver] Document URI - authority: $authority, docId: $docId")
                
                when (authority) {
                    // Primary external storage (download manager, etc.)
                    "com.android.providers.downloads.documents" -> {
                        // Format: raw:/path or id
                        if (docId.startsWith("raw:")) {
                            return docId.substringAfter("raw:")
                        }
                    }
                    
                    // External storage provider (internal + SD cards)
                    "com.android.externalstorage.documents" -> {
                        val split = docId.split(":")
                        if (split.size == 2) {
                            val storageId = split[0]
                            val relativePath = split[1]
                            
                            // Primary storage
                            if (storageId == "primary") {
                                val path = "${android.os.Environment.getExternalStorageDirectory()}/$relativePath"
                                DebugLog.log("[FilePathResolver] Checking primary storage path: $path")
                                val file = File(path)
                                DebugLog.log("[FilePathResolver] exists=${file.exists()}, canRead=${file.canRead()}")
                                if (file.exists()) {
                                    // Return the path - caller will verify readability
                                    return path
                                }
                            }
                            
                            // Secondary storage (SD card)
                            // Try common SD card mount points
                            val sdCardPaths = getExternalStoragePaths(context)
                            for (sdPath in sdCardPaths) {
                                // Check by volume ID
                                val fullPath = "$sdPath/$relativePath"
                                if (File(fullPath).exists()) {
                                    DebugLog.log("[FilePathResolver] SD card path: $fullPath")
                                    return fullPath
                                }
                                
                                // Check with storage ID as subfolder
                                val altPath = "/storage/$storageId/$relativePath"
                                if (File(altPath).exists()) {
                                    DebugLog.log("[FilePathResolver] Storage path with ID: $altPath")
                                    return altPath
                                }
                            }
                        }
                    }
                    
                    // Media documents
                    "com.android.providers.media.documents" -> {
                        // Try to get the data column
                        return getDataColumn(context, uri)
                    }
                }
            }
            
            // Fallback: try to read _data column directly
            return getDataColumn(context, uri)
            
        } catch (e: Exception) {
            DebugLog.log("[FilePathResolver] Error resolving URI: ${e.message}")
        }
        
        return null
    }
    
    private fun getDataColumn(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex("_data")
                    if (columnIndex >= 0) {
                        val path = cursor.getString(columnIndex)
                        if (!path.isNullOrEmpty() && File(path).exists()) {
                            DebugLog.log("[FilePathResolver] Data column path: $path")
                            return path
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("[FilePathResolver] Error reading data column: ${e.message}")
        }
        return null
    }
    
    /**
     * Get list of external storage paths including SD cards.
     */
    @SuppressLint("SdCardPath") // Retained only as a legacy vendor-mount fallback after platform paths.
    private fun getExternalStoragePaths(context: Context): List<String> {
        val paths = mutableListOf<String>()
        
        // Get all external storage directories
        context.getExternalFilesDirs(null).forEach { file ->
            file?.let {
                // Extract mount point from app-specific path
                // e.g., /storage/XXXX-XXXX/Android/data/... -> /storage/XXXX-XXXX
                val path = it.absolutePath
                val androidIndex = path.indexOf("/Android/")
                if (androidIndex > 0) {
                    paths.add(path.substring(0, androidIndex))
                }
            }
        }
        
        // Also check common SD card paths
        listOf(
            "/storage/sdcard1",
            "/storage/extSdCard",
            "/storage/external_SD",
            "/mnt/extSdCard",
            "/mnt/sdcard/external_sd"
        ).forEach { path ->
            if (File(path).exists()) {
                paths.add(path)
            }
        }
        
        return paths.distinct()
    }
    
    /**
     * Check if a path is accessible by native code (readable file exists).
     * Note: On Android 10+, canRead() may return false even for readable files.
     * We return true if the file exists, as llama-server will verify actual readability.
     */
    fun isPathAccessible(path: String): Boolean {
        return try {
            val file = File(path)
            val exists = file.exists()
            val canRead = file.canRead()
            DebugLog.log("[FilePathResolver] isPathAccessible($path): exists=$exists, canRead=$canRead")
            // Return true if file exists - native code will verify actual access
            exists
        } catch (e: Exception) {
            DebugLog.log("[FilePathResolver] isPathAccessible exception: ${e.message}")
            false
        }
    }
}
