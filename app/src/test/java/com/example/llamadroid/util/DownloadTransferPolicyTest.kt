package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadTransferPolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `thousands of chunks produce bounded progress events and writes`() {
        var now = 0L
        val ui = DownloadProgressPolicy(250L) { now }
        val durable = DownloadProgressPolicy(1_000L) { now }
        var uiEvents = 0
        var writes = 0
        repeat(10_000) { index ->
            now = index.toLong()
            if (ui.shouldUpdate()) uiEvents++
            if (durable.shouldUpdate()) writes++
        }
        assertEquals(40, uiEvents)
        assertEquals(10, writes)
        assertTrue(ui.shouldUpdate(force = true))
        assertTrue(durable.shouldUpdate(force = true))
    }

    @Test fun `resume metadata requires exact source and strong validator`() {
        val partial = File(folder.root, "model.part").apply { writeText("saved") }
        val file = DownloadResumeMetadata.companionFile(partial)
        assertNull(DownloadResumeMetadata.read(file))
        val metadata = DownloadResumeMetadata(DownloadResumeMetadata.fingerprint("https://host/a"), "\"v1\"", 10L)
        DownloadResumeMetadata.write(file, metadata)
        assertTrue(DownloadResumeMetadata.read(file)!!.matches("https://host/a", 5L))
        assertFalse(metadata.matches("https://host/b", 5L))
        assertFalse(metadata.matches("https://host/a", 11L))
        assertFalse(DownloadResumeMetadata.isStrongEtag("W/\"v1\""))
        assertNull(DownloadContentRange.parse("bytes 5-3/10"))
        assertNull(DownloadContentRange.parse("bytes 5-10/10"))
        assertEquals(DownloadContentRange(5, 9, 10), DownloadContentRange.parse("bytes 5-9/10"))
    }
}
