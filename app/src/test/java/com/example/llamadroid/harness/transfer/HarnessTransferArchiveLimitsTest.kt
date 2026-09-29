package com.example.llamadroid.harness.transfer

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessTransferArchiveLimitsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun oversizedLinkPayloadIsRejectedFromManifestBeforeContentIsRead() {
        // A compressed link entry must not turn its small manifest target into an
        // arbitrarily large allocation during inspection or staging.
        val payload = ByteArray(32 * 1024) { 'a'.code.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val manifest = JSONObject()
            .put("format", TransferManifest.FORMAT)
            .put("formatVersion", TransferManifest.CURRENT_FORMAT_VERSION)
            .put("runtimeId", "test-runtime")
            .put("mode", "PORTABLE")
            .put("createdAtEpochMs", 1)
            .put("encrypted", false)
            .put("entries", JSONArray().put(JSONObject()
                .put("path", "projects/link")
                .put("kind", "workspace")
                .put("sizeBytes", payload.size)
                .put("sha256", digest)
                .put("symlink", true)
                .put("linkTarget", "readme")))
            .put("sessions", JSONArray())
            .put("metadataNames", JSONArray())
        val archive = temporary.newFile("oversized-link.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(TransferManifest.MANIFEST_PATH))
            output.write(manifest.toString().toByteArray(Charsets.UTF_8))
            output.closeEntry()
            output.putNextEntry(ZipEntry("projects/link"))
            output.write(payload)
            output.closeEntry()
        }
        val failure = runCatching { HarnessTransferArchive.inspect(archive) }.exceptionOrNull()
        assertEquals("TRANSFER_SYMLINK_SIZE_INVALID", failure?.message)
    }
}
