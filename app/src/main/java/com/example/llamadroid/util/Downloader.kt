package com.example.llamadroid.util

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Downloader {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
    
    // Track active downloads by filename for cancellation
    private val activeDownloads = ConcurrentHashMap<String, Call>()
    
    fun download(
        url: String,
        destFile: File,
        context: Context? = null,
        bearerToken: String? = null
    ): Flow<Float> = flow {
        // Acquire WakeLock to prevent CPU sleep during download
        val wakeLock = context?.let {
            val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaDroid:DownloadWakeLock")
        }
        
        val downloadId = destFile.name
        
        try {
            wakeLock?.acquire()
            DebugLog.log("Downloader: Starting download of $url")
            
            val requestBuilder = Request.Builder().url(url)
            bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
                requestBuilder.header("Authorization", "Bearer $token")
            }
            val request = requestBuilder.build()
            val call = client.newCall(request)
            activeDownloads[downloadId] = call
            
            val response = call.execute()
            
            if (!response.isSuccessful) throw Exception("Download failed: $url (${response.code})")
            
            val body = response.body ?: throw Exception("Empty body")
            val totalBytes = body.contentLength()
            val inputStream: InputStream = body.byteStream()
            
            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            
            emit(0f)
            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // Check if cancelled
                    if (call.isCanceled()) {
                        DebugLog.log("Downloader: Download cancelled for ${destFile.name}")
                        destFile.delete()
                        throw Exception("Download cancelled")
                    }
                    
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        emit(totalRead.toFloat() / totalBytes.toFloat())
                    }
                }
                outputStream.flush()
                emit(1f)
                DebugLog.log("Downloader: Completed download of ${destFile.name}")
            } finally {
                inputStream.close()
                outputStream.close()
                body.close()
            }
        } catch (e: Exception) {
            DebugLog.log("Downloader: ERROR - ${e.message}")
            throw e
        } finally {
            activeDownloads.remove(downloadId)
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Download to a provided OutputStream (for SAF support)
     * Use this when downloading to user-selected folders via SAF
     */
    fun downloadToStream(
        url: String, 
        outputStream: java.io.OutputStream,
        downloadId: String,
        context: Context? = null
    ): Flow<Float> = flow {
        val wakeLock = context?.let {
            val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaDroid:DownloadWakeLock")
        }
        
        try {
            wakeLock?.acquire()
            DebugLog.log("Downloader: Starting SAF download of $url")
            
            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            activeDownloads[downloadId] = call
            
            val response = call.execute()
            
            if (!response.isSuccessful) throw Exception("Download failed: $url (${response.code})")
            
            val body = response.body ?: throw Exception("Empty body")
            val totalBytes = body.contentLength()
            val inputStream: InputStream = body.byteStream()
            
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            
            emit(0f)
            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (call.isCanceled()) {
                        DebugLog.log("Downloader: Download cancelled for $downloadId")
                        throw Exception("Download cancelled")
                    }
                    
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        emit(totalRead.toFloat() / totalBytes.toFloat())
                    }
                }
                outputStream.flush()
                emit(1f)
                DebugLog.log("Downloader: Completed SAF download $downloadId")
            } finally {
                inputStream.close()
                outputStream.close()
                body.close()
            }
        } catch (e: Exception) {
            DebugLog.log("Downloader: SAF ERROR - ${e.message}")
            throw e
        } finally {
            activeDownloads.remove(downloadId)
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Cancel an active download by filename
     */
    fun cancelDownload(filename: String) {
        activeDownloads[filename]?.let { call ->
            DebugLog.log("Downloader: Cancelling download of $filename")
            call.cancel()
            activeDownloads.remove(filename)
        }
    }
    
    /**
     * Cancel all active downloads
     */
    fun cancelAllDownloads() {
        activeDownloads.forEach { (filename, call) ->
            DebugLog.log("Downloader: Cancelling download of $filename")
            call.cancel()
        }
        activeDownloads.clear()
    }
    
    /**
     * Check if a download is currently active
     */
    fun isDownloading(filename: String): Boolean {
        return activeDownloads.containsKey(filename)
    }
}
