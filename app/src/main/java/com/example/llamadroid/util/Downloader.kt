package com.example.llamadroid.util

import android.content.Context
import android.os.PowerManager
import com.example.llamadroid.data.model.DownloadProgressHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Downloader {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
    
    // Track active downloads by exact task id for cancellation.
    private val activeDownloads = ConcurrentHashMap<String, Call>()
    
    fun download(
        url: String,
        destFile: File,
        context: Context? = null,
        bearerToken: String? = null,
        downloadId: String = destFile.absolutePath,
        /** Keep the resumable staging file when a stage-only artifact is cancelled. */
        preservePartialOnCancel: Boolean = false
    ): Flow<Float> = flow {
        // Acquire WakeLock to prevent CPU sleep during download
        val wakeLock = context?.let {
            val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaDroid:DownloadWakeLock")
        }
        
        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        
        try {
            wakeLock?.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS)
            DebugLog.log("Downloader: Starting download of $url")

            var attempt = 0
            var completed = false
            var lastError: Exception? = null
            emit(if (partFile.length() > 0L) DownloadProgressHolder.INDETERMINATE else 0f)

            while (attempt < MAX_DOWNLOAD_ATTEMPTS && !completed) {
                coroutineContext.ensureActive()
                val resumeFrom = partFile.length().coerceAtLeast(0L)
                val requestBuilder = Request.Builder().url(url)
                bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                if (resumeFrom > 0L) {
                    requestBuilder.header("Range", "bytes=$resumeFrom-")
                }
                val call = client.newCall(requestBuilder.build())
                activeDownloads[downloadId] = call

                try {
                    val response = call.execute()
                    if (resumeFrom > 0L && response.code == 200) {
                        partFile.delete()
                    }
                    if (!response.isSuccessful || (resumeFrom > 0L && response.code != 206 && response.code != 200)) {
                        throw Exception("Download failed: $url (${response.code})")
                    }
                    val body = response.body ?: throw Exception("Empty body")
                    val responseResumeFrom = if (resumeFrom > 0L && response.code == 206) resumeFrom else 0L
                    val remainingBytes = body.contentLength()
                    val totalBytes = if (remainingBytes > 0L) responseResumeFrom + remainingBytes else -1L
                    val inputStream: InputStream = body.byteStream()
                    val outputStream = FileOutputStream(partFile, responseResumeFrom > 0L)
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead = responseResumeFrom

                    if (totalBytes <= 0L) {
                        emit(DownloadProgressHolder.INDETERMINATE)
                    } else {
                        emit((totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                    }
                    try {
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            coroutineContext.ensureActive()
                            if (call.isCanceled()) {
                                DebugLog.log("Downloader: Download cancelled for ${destFile.name}")
                                if (!preservePartialOnCancel) {
                                    partFile.delete()
                                    destFile.delete()
                                }
                                throw CancellationException("Download cancelled")
                            }

                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                emit((totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            } else {
                                emit(DownloadProgressHolder.INDETERMINATE)
                            }
                        }
                        outputStream.flush()
                    } finally {
                        inputStream.close()
                        outputStream.close()
                        body.close()
                        response.close()
                    }
                    if (destFile.exists()) destFile.delete()
                    if (!partFile.renameTo(destFile)) {
                        partFile.inputStream().use { input ->
                            FileOutputStream(destFile).use { output -> input.copyTo(output) }
                        }
                        partFile.delete()
                    }
                    emit(1f)
                    completed = true
                    DebugLog.log("Downloader: Completed download of ${destFile.name}")
                } catch (e: InterruptedIOException) {
                    if (call.isCanceled()) {
                        // OkHttp reports cancellation as an interrupted I/O
                        // operation while a response is being read. It is a
                        // terminal control path, never a transient retry.
                        throw CancellationException("Download cancelled")
                    }
                    lastError = e
                    attempt += 1
                    DebugLog.log("Downloader: idle/read timeout for ${destFile.name}; retry $attempt/$MAX_DOWNLOAD_ATTEMPTS")
                    if (attempt >= MAX_DOWNLOAD_ATTEMPTS) throw e
                } catch (e: Exception) {
                    lastError = e
                    throw e
                } finally {
                    activeDownloads.remove(downloadId, call)
                }
            }
            if (!completed) throw lastError ?: Exception("Download did not complete")
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
            wakeLock?.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS)
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

    private const val MAX_DOWNLOAD_ATTEMPTS = 4
    private const val DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1_000L
}
