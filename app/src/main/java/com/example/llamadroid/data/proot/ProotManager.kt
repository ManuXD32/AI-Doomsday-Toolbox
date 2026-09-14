package com.example.llamadroid.data.proot

import android.content.Context
import android.os.Build
import com.example.llamadroid.service.SDInstallState
import com.example.llamadroid.service.SDStateHolder
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Manages proot environments for running Linux distributions on Android.
 * Used to run AUTOMATIC1111 Stable Diffusion WebUI.
 */
class ProotManager(private val context: Context) {
    
    companion object {
        // Official Ubuntu cloud images WSL rootfs for arm64
        private const val UBUNTU_ROOTFS_URL = "https://cloud-images.ubuntu.com/wsl/jammy/current/ubuntu-jammy-wsl-arm64-ubuntu22.04lts.rootfs.tar.gz"
        private const val ROOTFS_SIZE_ESTIMATE = 500_000_000L // ~500MB extracted
        private const val A1111_SIZE_ESTIMATE = 5_000_000_000L // ~5GB with venv
        
    }
    
    private val prootDir = File(context.filesDir, "proot")
    private val rootfsDir = File(prootDir, "rootfs")
    private val downloadDir = File(prootDir, "downloads")
    
    /**
     * Check if A1111 environment is installed
     */
    fun isInstalled(): Boolean {
        val webUIDir = File(rootfsDir, "home/auto/stable-diffusion-webui")
        val webUIScript = File(webUIDir, "webui.sh")
        return webUIScript.exists()
    }
    
    /**
     * Check if rootfs is extracted
     */
    fun isRootfsReady(): Boolean {
        val binDir = File(rootfsDir, "bin")
        return binDir.exists() && binDir.listFiles()?.isNotEmpty() == true
    }
    
    /**
     * Get estimated storage required
     */
    fun getRequiredStorage(): Long {
        return ROOTFS_SIZE_ESTIMATE + A1111_SIZE_ESTIMATE
    }
    
    /**
     * Get current storage usage
     */
    fun getCurrentUsage(): Long {
        return if (prootDir.exists()) {
            prootDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else 0L
    }
    
    /**
     * Download Ubuntu rootfs tarball
     */
    suspend fun downloadRootfs(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            downloadDir.mkdirs()
            val tarFile = File(downloadDir, "ubuntu-rootfs.tar.gz")
            
            if (tarFile.exists()) {
                DebugLog.log("ProotManager: Rootfs already downloaded")
                onProgress(1f)
                return@withContext true
            }
            
            DebugLog.log("ProotManager: Downloading rootfs from $UBUNTU_ROOTFS_URL")
            
            val connection = URL(UBUNTU_ROOTFS_URL).openConnection()
            val totalSize = connection.contentLengthLong
            var downloadedSize = 0L
            
            connection.getInputStream().use { input ->
                FileOutputStream(tarFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead
                        if (totalSize > 0) {
                            onProgress(downloadedSize.toFloat() / totalSize)
                        }
                    }
                }
            }
            
            DebugLog.log("ProotManager: Rootfs downloaded (${tarFile.length()} bytes)")
            return@withContext true
        } catch (e: Exception) {
            DebugLog.log("ProotManager: Download failed: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Extract rootfs tarball using Java-based extraction.
     * This handles hardlinks by copying files (Android doesn't allow hardlinks).
     */
    suspend fun extractRootfs(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val tarFile = File(downloadDir, "ubuntu-rootfs.tar.gz")
            if (!tarFile.exists()) {
                DebugLog.log("ProotManager: Rootfs tarball not found")
                return@withContext false
            }
            
            rootfsDir.mkdirs()
            DebugLog.log("ProotManager: Extracting rootfs to ${rootfsDir.absolutePath}")
            DebugLog.log("ProotManager: Tarball size: ${tarFile.length()} bytes")
            
            // Use Apache Commons Compress for proper tar handling
            val totalBytes = tarFile.length()
            var processedBytes = 0L
            
            // Track hardlinks - map from link name to source file path
            val hardlinkSources = mutableMapOf<String, File>()
            
            java.io.FileInputStream(tarFile).use { fis ->
                java.util.zip.GZIPInputStream(fis).use { gzis ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzis).use { tais ->
                        var entry = tais.nextEntry
                        var fileCount = 0
                        
                        while (entry != null) {
                            val outFile = File(rootfsDir, entry.name)
                            
                            when {
                                // Skip device files (Android can't create these)
                                entry.isBlockDevice || entry.isCharacterDevice -> {
                                    DebugLog.log("ProotManager: Skipping device file: ${entry.name}")
                                }
                                // Directory
                                entry.isDirectory -> {
                                    outFile.mkdirs()
                                }
                                // Symlink
                                entry.isSymbolicLink -> {
                                    outFile.parentFile?.mkdirs()
                                    // Create as file with symlink content (proot handles this)
                                    try {
                                        java.nio.file.Files.createSymbolicLink(
                                            outFile.toPath(),
                                            java.nio.file.Paths.get(entry.linkName)
                                        )
                                    } catch (e: Exception) {
                                        // If symlink fails, write a marker file
                                        outFile.writeText("SYMLINK:${entry.linkName}")
                                    }
                                }
                                // Hardlink - copy from source instead of linking
                                entry.isLink -> {
                                    val sourceFile = hardlinkSources[entry.linkName]
                                        ?: File(rootfsDir, entry.linkName)
                                    outFile.parentFile?.mkdirs()
                                    if (sourceFile.exists()) {
                                        sourceFile.copyTo(outFile, overwrite = true)
                                    } else {
                                        DebugLog.log("ProotManager: Hardlink source not found: ${entry.linkName}")
                                    }
                                }
                                // Regular file
                                entry.isFile -> {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        tais.copyTo(fos)
                                    }
                                    // Track as potential hardlink source
                                    hardlinkSources[entry.name] = outFile
                                    
                                    // Set executable bit if needed
                                    if (entry.mode and 0x49 != 0) { // 0111 in octal for execute bits
                                        outFile.setExecutable(true, false)
                                    }
                                }
                            }
                            
                            fileCount++
                            // Update progress roughly (can't track exact bytes in tar stream)
                            if (fileCount % 500 == 0) {
                                processedBytes += 5_000_000 // Rough estimate
                                onProgress(minOf(processedBytes.toFloat() / totalBytes / 2, 0.95f))
                            }
                            
                            entry = tais.nextEntry
                        }
                        
                        DebugLog.log("ProotManager: Extracted $fileCount files")
                    }
                }
            }
            
            onProgress(1f)
            DebugLog.log("ProotManager: Rootfs extracted successfully")
            
            // Verify extraction
            val binDir = File(rootfsDir, "bin")
            if (!binDir.exists() || binDir.listFiles()?.isEmpty() != false) {
                DebugLog.log("ProotManager: Extraction may have failed - /bin is empty or missing")
                return@withContext false
            }
            
            // Clean up tarball to save space
            tarFile.delete()
            return@withContext true
            
        } catch (e: Exception) {
            DebugLog.log("ProotManager: Extraction error: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }
    
    /**
     * Execute a command in the proot environment
     * Note: The proot binary is bundled as a native library (libproot.so) and can be
     * executed directly from applicationInfo.nativeLibraryDir
     */
    suspend fun executeCommand(command: String, user: String = "root", onOutput: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        try {
            val prootBinary = getProotBinary()
            if (prootBinary == null) {
                DebugLog.log("ProotManager: Proot binary not found!")
                return@withContext -1
            }
            
            // Build proot command arguments with Android-compatible flags
            val args = mutableListOf<String>()
            args.add(prootBinary.absolutePath)
            args.add("-0")  // Fake root (run as uid 0)
            args.add("--link2symlink")  // Required for Android - convert hardlinks to symlinks
            args.add("-r")
            args.add(rootfsDir.absolutePath)
            
            // Bind essential Android directories
            args.add("-b")
            args.add("/dev")
            args.add("-b")
            args.add("/proc")
            args.add("-b")
            args.add("/sys")
            args.add("-b")
            args.add("${context.filesDir}:/android/data")
            
            // Working directory
            args.add("-w")
            args.add(if (user == "root") "/root" else "/home/$user")
            
            // Use the shell directly from rootfs - Ubuntu has it at /bin/sh
            args.add("/bin/sh")
            args.add("-c")
            args.add(command)
            
            DebugLog.log("ProotManager: Executing: $command")
            DebugLog.log("ProotManager: Full command: ${args.joinToString(" ")}")
            
            // Set up environment for proot
            val env = mutableMapOf<String, String>()
            
            // Executables must come from the signed APK native-library directory. Existing
            // legacy SD data remains in place, but it is never an executable source.
            val termuxLoader = File(prootDir, "termux-proot/loader")
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val bundledLoader = File(nativeLibDir, "libproot_loader.so")
            
            when {
                bundledLoader.exists() -> {
                    env["PROOT_LOADER"] = bundledLoader.absolutePath
                    DebugLog.log("ProotManager: Using bundled PROOT_LOADER=${bundledLoader.absolutePath}")
                }
                termuxLoader.exists() -> {
                    // Compatibility only for an installation created by an older release.
                    env["PROOT_LOADER"] = termuxLoader.absolutePath
                    DebugLog.log("ProotManager: Using preserved legacy loader=${termuxLoader.absolutePath}")
                }
            }
            
            // Critical: Disable seccomp filtering which causes issues on Android 10+
            env["PROOT_NO_SECCOMP"] = "1"
            
            // Set PROOT_TMP_DIR to app's cache directory for temp files
            val tmpDir = File(context.cacheDir, "proot-tmp")
            tmpDir.mkdirs()
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
            
            DebugLog.log("ProotManager: Running proot: ${prootBinary.absolutePath}")
            
            // Direct execution - proot is now in native lib dir which Android allows
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
            
            // Apply environment variables
            pb.environment().putAll(env)
            
            // Set PATH and LD_LIBRARY_PATH
            val currentPath = pb.environment()["PATH"] ?: ""
            pb.environment()["PATH"] = "$currentPath:/system/bin:/system/xbin"
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            
            val process = pb.start()
            
            // Read output
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    onOutput(line)
                    DebugLog.log("Proot: $line")
                }
            }
            
            val exitCode = process.waitFor()
            DebugLog.log("ProotManager: Command exited with code $exitCode")
            return@withContext exitCode
        } catch (e: Exception) {
            DebugLog.log("ProotManager: Command error: ${e.message}")
            e.printStackTrace()
            return@withContext -1
        }
    }
    
    /** Get the signed packaged PRoot binary, preserving old installations only as fallback. */
    private fun getProotBinary(): File? {
        AgentProotNativeBinaryProvider.locate(context, AgentProotNativeBinaryProvider.Binary.PROOT)?.let {
            DebugLog.log("ProotManager: Using signed packaged PRoot at ${it.absolutePath}")
            return it
        }
        
        // Compatibility only: never create or refresh these writable executables.
        val termuxProot = File(prootDir, "termux-proot/proot")
        if (termuxProot.exists() && termuxProot.canExecute()) return termuxProot
        val legacyProot = File(prootDir, "proot")
        if (legacyProot.exists() && legacyProot.canExecute()) {
            DebugLog.log("ProotManager: Found legacy proot at ${legacyProot.absolutePath}")
            return legacyProot
        }
        
        DebugLog.log("ProotManager: Signed packaged PRoot binary is unavailable")
        return null
    }
    
    /**
     * Compatibility entry point retained for the legacy SD setup flow. It no longer downloads
     * or extracts executable packages: Android target-29+ only executes the signed APK artifact.
     */
    suspend fun downloadProot(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onProgress(1f)
        getProotBinary() != null
    }
    
    /**
     * Extract data.tar.xz from .deb file (ar archive format)
     */
    private fun extractDataFromDeb(debFile: File, outDir: File): File? {
        try {
            // .deb is an ar archive. We need to find data.tar.xz or data.tar.gz
            java.io.FileInputStream(debFile).use { fis ->
                // Read ar magic (8 bytes)
                val magic = ByteArray(8)
                fis.read(magic)
                val magicStr = String(magic)
                if (!magicStr.startsWith("!<arch>")) {
                    DebugLog.log("ProotManager: Not a valid ar archive")
                    return null
                }
                
                // Read each ar entry
                while (fis.available() > 0) {
                    // ar header: 60 bytes
                    val header = ByteArray(60)
                    val headerRead = fis.read(header)
                    if (headerRead < 60) break
                    
                    val headerStr = String(header)
                    val filename = headerStr.substring(0, 16).trim()
                    val sizeStr = headerStr.substring(48, 58).trim()
                    val size = sizeStr.toLongOrNull() ?: 0L
                    
                    if (filename.startsWith("data.tar")) {
                        // Found data archive
                        val dataFile = File(outDir, filename.replace("/", ""))
                        FileOutputStream(dataFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = fis.read(buffer, 0, toRead)
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                        DebugLog.log("ProotManager: Extracted $filename (${dataFile.length()} bytes)")
                        return dataFile
                    } else {
                        // Skip this entry
                        fis.skip(size)
                    }
                    
                    // ar entries are 2-byte aligned
                    if (size % 2 == 1L) {
                        fis.skip(1)
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("ProotManager: Error extracting deb: ${e.message}")
        }
        return null
    }
    
    /**
     * Extract proot binary and loader from data.tar.xz
     */
    private fun extractProotFromData(dataFile: File, outDir: File): Boolean {
        try {
            // Detect compression type from filename
            val isXz = dataFile.name.endsWith(".xz")
            val isGz = dataFile.name.endsWith(".gz") || dataFile.name.endsWith(".zst")
            
            val inputStream: java.io.InputStream = java.io.FileInputStream(dataFile)
            val decompressedStream = if (isXz) {
                // Use Apache Commons Compress for XZ
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(inputStream)
            } else if (isGz) {
                java.util.zip.GZIPInputStream(inputStream)
            } else {
                inputStream
            }
            
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(decompressedStream).use { tais ->
                var entry = tais.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Look for proot binary and loader
                    if (name.endsWith("/proot") || name == "proot" || 
                        name.contains("data/data/com.termux/files/usr/bin/proot")) {
                        val outFile = File(outDir, "proot")
                        FileOutputStream(outFile).use { fos ->
                            tais.copyTo(fos)
                        }
                        DebugLog.log("ProotManager: Extracted proot binary")
                    } else if (name.contains("loader") || name.contains("proot-loader")) {
                        val outFile = File(outDir, "loader")
                        FileOutputStream(outFile).use { fos ->
                            tais.copyTo(fos)
                        }
                        DebugLog.log("ProotManager: Extracted loader binary")
                    }
                    entry = tais.nextEntry
                }
            }
            
            val proot = File(outDir, "proot")
            return proot.exists() && proot.length() > 0
            
        } catch (e: Exception) {
            DebugLog.log("ProotManager: Error extracting data tar: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Check if proot binary is available
     */
    fun isProotAvailable(): Boolean = getProotBinary() != null
    
    /**
     * Delete the entire proot environment
     */
    suspend fun deleteEnvironment() = withContext(Dispatchers.IO) {
        if (prootDir.exists()) {
            prootDir.deleteRecursively()
            DebugLog.log("ProotManager: Environment deleted")
        }
        SDStateHolder.updateInstallState(SDInstallState.NotInstalled)
    }
}
