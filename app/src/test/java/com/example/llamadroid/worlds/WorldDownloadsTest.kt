package com.example.llamadroid.worlds

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorldDownloadsTest {
    @Test fun allowsOnlyKnownExtensions() {
        listOf("a.glb", "a.GLTF", "a.png", "a.html", "a.obj", "a.json").forEach { assertNotNull(it, WorldDownloads.sanitizeName(it)) }
        listOf("a.exe", "a.apk", "a.js", "noext", ".glb", "").forEach { assertNull(it, WorldDownloads.sanitizeName(it)) }
    }

    @Test fun stripsPathsAndUnsafeCharacters() {
        assertEquals("evil.glb", WorldDownloads.sanitizeName("../../etc/evil.glb"))
        assertEquals("a_b.glb", WorldDownloads.sanitizeName("a:b.glb"))
        assertEquals("c.glb", WorldDownloads.sanitizeName("C:\\x\\c.glb"))
        assertEquals("rose.glb", WorldDownloads.sanitizeName("rose.GLB").let { it!!.lowercase() })
    }

    @Test fun decodesAndRejectsBadPayloads() {
        val bytes = "glTF".toByteArray()
        val ok = WorldDownloads.accept("x.glb", "model/gltf-binary", Base64.getEncoder().encodeToString(bytes))!!
        assertArrayEquals(bytes, ok.bytes)
        assertNull(WorldDownloads.accept("x.glb", "", "not base64!!"))
        assertNull(WorldDownloads.accept("x.exe", "", Base64.getEncoder().encodeToString(bytes)))
        // Same size rule as MAX_BYTES, checked with a small cap so the test does not allocate 50 MB.
        assertNull(WorldDownloads.decode("A".repeat(((1_000L / 3 + 2) * 4).toInt()), maxBytes = 1_000L))
        assertArrayEquals(bytes, WorldDownloads.decode(Base64.getEncoder().encodeToString(bytes), maxBytes = 1_000L))
    }
}
