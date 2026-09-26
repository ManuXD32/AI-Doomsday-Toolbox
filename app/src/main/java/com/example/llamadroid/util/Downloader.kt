package com.example.llamadroid.util

import android.content.Context
import android.os.PowerManager
import com.example.llamadroid.R
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        val metadataFile = DownloadResumeMetadata.companionFile(partFile)
        val progressPolicy = DownloadProgressPolicy(250L)

        fun verificationFailure(reason: String): IOException {
            DebugLog.log("Downloader: response validation failed: $reason")
            return IOException(context?.getString(R.string.download_verification_failed)
                ?: "Download response could not be verified")
        }

        fun discardPartial() {
            var deleted = false
            if (partFile.exists()) {
                if (!partFile.delete()) throw verificationFailure("cannot discard partial")
                deleted = true
            }
            if (metadataFile.exists()) {
                if (!metadataFile.delete()) throw verificationFailure("cannot discard metadata")
                deleted = true
            }
            if (deleted) DownloadResumeMetadata.syncDirectory(partFile.parentFile)
        }
        var partialDurable = true
        
        try {
            wakeLock?.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS)
            DebugLog.log("Downloader: Starting download of $url")

            var attempt = 0
            var protocolRestarts = 0
            var completed = false
            var lastError: IOException? = null
            if (partFile.exists() &&
                DownloadResumeMetadata.read(metadataFile)?.matches(url, partFile.length()) != true
            ) discardPartial()
            progressPolicy.shouldUpdate(force = true)
            emit(if (partFile.length() > 0L) DownloadProgressHolder.INDETERMINATE else 0f)

            while (attempt < MAX_DOWNLOAD_ATTEMPTS && !completed) {
                coroutineContext.ensureActive()
                val saved = DownloadResumeMetadata.read(metadataFile)
                val resumeFrom = if (saved?.matches(url, partFile.length()) == true) partFile.length() else 0L
                if (partFile.exists() && resumeFrom == 0L) discardPartial()
                val requestBuilder = Request.Builder().url(url).header("Accept-Encoding", "identity")
                bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                if (resumeFrom > 0L) {
                    requestBuilder.header("Range", "bytes=$resumeFrom-")
                    requestBuilder.header("If-Range", saved!!.strongEtag)
                }
                val call = client.newCall(requestBuilder.build())
                activeDownloads[downloadId] = call

                try {
                    call.execute().use { response ->
                        if (response.code == 416 && resumeFrom > 0L) throw RestartFreshDownload("Range rejected")
                        if (response.code !in listOf(200, 206)) {
                            throw IOException("Download failed: $url (${response.code})")
                        }
                        val body = response.body ?: throw verificationFailure("empty body")
                        if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
                            throw verificationFailure("unexpected content encoding")
                        }
                        val ranged = response.code == 206
                        val range = if (ranged) DownloadContentRange.parse(response.header("Content-Range")) else null
                        if (ranged && (resumeFrom == 0L || saved == null || range == null ||
                                range.start != resumeFrom || range.total != saved.totalBytes ||
                                response.header("ETag") != saved.strongEtag ||
                                (body.contentLength() >= 0L && body.contentLength() != range.end - range.start + 1L))
                        ) throw RestartFreshDownload("Unverified partial response")
                        if (!ranged && resumeFrom > 0L) discardPartial()
                        val totalBytes = if (ranged) range!!.total else body.contentLength()
                        if (!ranged) {
                            val etag = response.header("ETag")
                            if (DownloadResumeMetadata.isStrongEtag(etag) && totalBytes > 0L) {
                                DownloadResumeMetadata.write(metadataFile, DownloadResumeMetadata(
                                    DownloadResumeMetadata.fingerprint(url), etag!!, totalBytes
                                ))
                            } else if (metadataFile.exists() && !metadataFile.delete()) {
                                throw verificationFailure("cannot remove unusable metadata")
                            }
                        }
                        var totalRead = if (ranged) resumeFrom else 0L
                        var responseRead = 0L
                        FileOutputStream(partFile, ranged).use { output ->
                            partialDurable = false
                            try {
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        coroutineContext.ensureActive()
                                        if (call.isCanceled()) throw CancellationException("Download cancelled")
                                        output.write(buffer, 0, count)
                                        responseRead += count
                                        totalRead += count
                                        if (totalBytes > 0L && totalRead > totalBytes) {
                                            throw RestartFreshDownload("Response exceeded declared total")
                                        }
                                        if (progressPolicy.shouldUpdate()) {
                                            emit(if (totalBytes > 0L)
                                                (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.999f)
                                                else DownloadProgressHolder.INDETERMINATE)
                                        }
                                    }
                                }
                            } finally {
                                partialDurable = runCatching { output.fd.sync() }.isSuccess
                            }
                        }
                        if (!partialDurable) throw verificationFailure("cannot sync partial payload")
                        val expected = if (ranged) range!!.end - range.start + 1L else totalBytes
                        if (expected >= 0L && responseRead != expected) {
                            throw verificationFailure("incomplete response")
                        }
                        if (totalBytes > 0L && totalRead < totalBytes) {
                            if (!ranged) throw verificationFailure("incomplete full response")
                            attempt = 0
                            return@use
                        }
                        Files.move(partFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        DownloadResumeMetadata.syncDirectory(destFile.parentFile)
                        if (metadataFile.exists() && !metadataFile.delete()) {
                            DebugLog.log("Downloader: could not remove completed resume metadata")
                        }
                        completed = true
                        emit(1f)
                        DebugLog.log("Downloader: Completed download of ${destFile.name}")
                    }
                } catch (e: RestartFreshDownload) {
                    discardPartial()
                    protocolRestarts += 1
                    if (protocolRestarts > MAX_PROTOCOL_RESTARTS) {
                        throw verificationFailure("repeated invalid range response")
                    }
                    DebugLog.log("Downloader: restarting unverifiable partial for ${destFile.name}")
                } catch (e: IOException) {
                    if (call.isCanceled()) {
                        throw CancellationException("Download cancelled")
                    }
                    if (!partialDurable) discardPartial()
                    lastError = e
                    attempt += 1
                    DebugLog.log("Downloader: I/O retry $attempt/$MAX_DOWNLOAD_ATTEMPTS for ${destFile.name}")
                    if (attempt >= MAX_DOWNLOAD_ATTEMPTS) throw e
                } finally {
                    activeDownloads.remove(downloadId, call)
                }
            }
            if (!completed) throw lastError ?: verificationFailure("incomplete download")
        } catch (cancelled: CancellationException) {
            if (!preservePartialOnCancel ||
                !partialDurable ||
                DownloadResumeMetadata.read(metadataFile)?.matches(url, partFile.length()) != true
            ) discardPartial()
            throw cancelled
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
    private const val MAX_PROTOCOL_RESTARTS = 2
    private const val DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1_000L

    private class RestartFreshDownload(message: String) : IOException(message)
}
