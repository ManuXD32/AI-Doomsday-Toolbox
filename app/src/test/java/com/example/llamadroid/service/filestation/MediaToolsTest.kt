package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.tools.MediaTools
import com.example.llamadroid.service.filestation.tools.PageRanges
import com.example.llamadroid.service.filestation.volume.FsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaToolsTest {
    @Test fun `all contract presets exist`() {
        val expected = listOf("mp3-128", "mp3-192", "aac-128", "opus-96", "flac", "wav", "mp4-720p", "mp4-480p", "webm-480p", "gif")
        assertEquals(expected, MediaTools.PRESET_IDS)
    }

    @Test fun `convert args per preset`() {
        val mp3 = MediaTools.convertArgs("/in/a.wav", "/out/a.mp3", "mp3-192")
        assertEquals(listOf("-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats", "-i", "/in/a.wav"), mp3.take(8))
        assertEquals(listOf("-vn", "-c:a", "libmp3lame", "-b:a", "192k", "/out/a.mp3"), mp3.drop(8))
        val mp4 = MediaTools.convertArgs("/in/a.mov", "/out/a.mp4", "mp4-720p")
        assertTrue(mp4.containsAll(listOf("scale=-2:720", "libx264", "+faststart")))
        assertTrue(MediaTools.convertArgs("/i", "/o.webm", "webm-480p").contains("libvpx-vp9"))
        assertTrue(MediaTools.convertArgs("/i", "/o.gif", "gif").contains("-an"))
        assertTrue(MediaTools.convertArgs("/i", "/o.opus", "opus-96").contains("libopus"))
        assertEquals("pcm_s16le", MediaTools.convertArgs("/i", "/o.wav", "wav")[MediaTools.convertArgs("/i", "/o.wav", "wav").indexOf("-c:a") + 1])
        assertEquals("mp4", MediaTools.preset("mp4-480p").ext)
        assertEquals("m4a", MediaTools.preset("aac-128").ext)
    }

    @Test fun `unknown preset and unsafe paths are rejected`() {
        assertFails { MediaTools.preset("nope") }
        assertFails { MediaTools.convertArgs("-i", "/out", "flac") }
        assertFails { MediaTools.convertArgs("/in", "relative.mp3", "flac") }
        assertFails { MediaTools.convertArgs("/in\u0000", "/out", "flac") }
    }

    @Test fun `trim args copy and preset modes`() {
        val copy = MediaTools.trimArgs("/in.mp4", "/out.mp4", 5.0, 12.5)
        assertEquals(listOf("-ss", "5.000", "-to", "12.500", "-i", "/in.mp4", "-c", "copy", "-avoid_negative_ts", "make_zero", "/out.mp4"), copy.drop(6))
        val re = MediaTools.trimArgs("/in.mp4", "/out.mp3", 0.0, 3.0, "mp3-128")
        assertTrue(re.contains("libmp3lame"))
        assertEquals("-ss", re[6])
    }

    @Test fun `trim validation`() {
        assertFails { MediaTools.validateTrim(-1.0, 4.0) }
        assertFails { MediaTools.validateTrim(5.0, 5.0) }
        assertFails { MediaTools.validateTrim(6.0, 2.0) }
        assertFails { MediaTools.validateTrim(0.0, Double.NaN) }
        assertFails { MediaTools.validateTrim(0.0, Double.POSITIVE_INFINITY) }
        MediaTools.validateTrim(0.0, 0.5)
    }

    @Test fun `progress parser uses stderr duration`() {
        val p = MediaTools.ProgressParser()
        p.feedStderr("  Duration: 00:01:40.00, start: 0.000000, bitrate: 128 kb/s")
        assertEquals(100.0, p.durationSec!!, 0.001)
        assertNull(p.feedProgress("frame=10"))
        assertEquals(0.25f, p.feedProgress("out_time_us=25000000")!!, 0.001f)
        assertNull(p.feedProgress("out_time_ms=25000000"))
        assertEquals(0.5f, p.feedProgress("out_time=00:00:50.000000")!!, 0.001f)
        assertNull(p.feedProgress("out_time=N/A"))
        assertEquals(0.99f, p.feedProgress("out_time_us=500000000")!!, 0.001f)
        assertEquals(1f, p.feedProgress("progress=end")!!, 0f)
        assertTrue(p.finished)
    }

    @Test fun `progress needs duration and override wins`() {
        val noDuration = MediaTools.ProgressParser()
        noDuration.feedStderr("  Duration: N/A, bitrate: N/A")
        assertNull(noDuration.feedProgress("out_time_us=1000000"))
        val trim = MediaTools.ProgressParser(10.0)
        trim.feedStderr("Duration: 01:00:00.00, start")
        assertEquals(10.0, trim.durationSec!!, 0.0)
        assertEquals(0.3f, trim.feedProgress("out_time_us=3000000")!!, 0.001f)
        assertNotNull(MediaTools.parseClock("01:02:03.5"))
        assertEquals(3723.5, MediaTools.parseClock("01:02:03.5")!!, 0.0001)
    }

    @Test fun `page ranges`() {
        val r = PageRanges.parse("1-3, 5", 10)
        assertEquals(listOf("1-3", "5"), r.map { it.label })
        assertEquals(3, r[0].pageCount)
        assertFails { PageRanges.parse("0-2", 10) }
        assertFails { PageRanges.parse("3-2", 10) }
        assertFails { PageRanges.parse("9-11", 10) }
        assertFails { PageRanges.parse("a", 10) }
        assertFails { PageRanges.parse("1-2-3", 10) }
        assertFails { PageRanges.parse("", 10) }
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("expected FsException") } catch (e: FsException) { /* ok */ }
    }
}
