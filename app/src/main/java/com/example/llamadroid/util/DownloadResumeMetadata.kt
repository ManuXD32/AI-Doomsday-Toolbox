package com.example.llamadroid.util

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties

/** Private companion to a partial payload. It never contains request credentials. */
internal data class DownloadResumeMetadata(
    val sourceFingerprint: String,
    val strongEtag: String,
    val totalBytes: Long
) {
    fun matches(url: String, partialBytes: Long): Boolean =
        sourceFingerprint == fingerprint(url) && isStrongEtag(strongEtag) &&
            totalBytes > 0L && partialBytes in 1..totalBytes

    companion object {
        private const val VERSION = "1"

        fun companionFile(partFile: File): File = File(partFile.parentFile, "${partFile.name}.resume")

        fun fingerprint(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun isStrongEtag(value: String?): Boolean = value != null &&
            value.length >= 2 && value.first() == '"' && value.last() == '"' &&
            !value.contains('\n') && !value.contains('\r')

        fun read(file: File): DownloadResumeMetadata? = runCatching {
            val properties = Properties()
            file.inputStream().use(properties::load)
            if (properties.getProperty("version") != VERSION) return null
            DownloadResumeMetadata(
                properties.getProperty("source") ?: return null,
                properties.getProperty("etag") ?: return null,
                properties.getProperty("total")?.toLongOrNull() ?: return null
            )
        }.getOrNull()

        fun write(file: File, metadata: DownloadResumeMetadata) {
            require(isStrongEtag(metadata.strongEtag) && metadata.totalBytes > 0L)
            val temp = File(file.parentFile, "${file.name}.tmp")
            val properties = Properties().apply {
                setProperty("version", VERSION)
                setProperty("source", metadata.sourceFingerprint)
                setProperty("etag", metadata.strongEtag)
                setProperty("total", metadata.totalBytes.toString())
            }
            FileOutputStream(temp).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(file.parentFile)
        }

        fun syncDirectory(directory: File?) {
            requireNotNull(directory) { "Download directory is missing" }
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

internal data class DownloadContentRange(val start: Long, val end: Long, val total: Long) {
    companion object {
        private val FORMAT = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun parse(value: String?): DownloadContentRange? {
            val match = FORMAT.matchEntire(value ?: return null) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() ?: return null
            return if (start <= end && end < total) DownloadContentRange(start, end, total) else null
        }
    }
}
