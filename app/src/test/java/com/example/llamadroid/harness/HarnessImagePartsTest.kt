package com.example.llamadroid.harness

import com.example.llamadroid.service.vision.ImageFallbackLogic
import com.example.llamadroid.service.vision.VisionDescriptionCache
import com.example.llamadroid.service.vision.VisionFailureMemo
import com.example.llamadroid.service.vision.VisionImageDescription
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class HarnessImagePartsTest {
    private val bytesA = byteArrayOf(1, 2, 3, 4)
    private val bytesB = byteArrayOf(9, 8, 7)

    private fun dataUrl(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun userMessage(vararg parts: JSONObject) = JSONObject().put("role", "user")
        .put("content", JSONArray().apply { parts.forEach { put(it) } })

    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun image(bytes: ByteArray) = JSONObject().put("type", "image_url")
        .put("image_url", JSONObject().put("url", dataUrl(bytes)))

    private val describer = HarnessImageParts.Describer { bytes, _ ->
        if (bytes.contentEquals(bytesA)) VisionImageDescription.Described(" a red square ")
        else VisionImageDescription.Unavailable("no vision helper is set up")
    }

    private fun parts(result: HarnessImageParts.Result, index: Int = 0): JSONArray =
        result.messages.getJSONObject(index).getJSONArray("content")

    @Test
    fun nonVisionModelGetsDescriptionText() = runBlocking {
        val messages = JSONArray().put(userMessage(text("look"), image(bytesA)))
        val result = HarnessImageParts.rewrite(messages, false, describer, null)
        val content = parts(result)
        assertEquals(2, content.length())
        assertEquals("look", content.getJSONObject(0).getString("text"))
        assertEquals(ImageFallbackLogic.descriptionBlock("a red square"), content.getJSONObject(1).getString("text"))
        assertTrue(result.unavailableReasons.isEmpty())
        // The original request is untouched.
        assertEquals("image_url", messages.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test
    fun unavailableImageGetsFixedNoteAndReason() = runBlocking {
        val messages = JSONArray().put(userMessage(image(bytesB)))
        val result = HarnessImageParts.rewrite(messages, false, describer, null)
        val sha = VisionDescriptionCache.sha256Hex(bytesB)
        assertEquals(
            "[Image ${sha.take(12)} could not be described: no vision helper is set up]",
            parts(result).getJSONObject(0).getString("text")
        )
        assertEquals(listOf("no vision helper is set up"), result.unavailableReasons)
    }

    @Test
    fun outputIsStableAcrossRuns() = runBlocking {
        val messages = JSONArray().put(userMessage(text("x"), image(bytesA), image(bytesB)))
        val first = HarnessImageParts.rewrite(messages, false, describer, null).messages.toString()
        val second = HarnessImageParts.rewrite(messages, false, describer, null).messages.toString()
        assertEquals(first, second)
    }

    @Test
    fun visionCardPassesEveryUserImageAsPath() = runBlocking {
        val stored = mutableListOf<Int>()
        val bytesC = byteArrayOf(5, 5)
        val messages = JSONArray().put(userMessage(text("look"), image(bytesA), image(bytesB), image(bytesC)))
        val result = HarnessImageParts.rewrite(messages, true, describer) { bytes, _ ->
            stored += bytes.size
            "/tmp/img-${bytes.size}.png"
        }
        val message = result.messages.getJSONObject(0)
        assertEquals("/tmp/img-4.png", message.getString(HarnessImageParts.KEY_IMAGE_PATH))
        val paths = message.getJSONArray(HarnessImageParts.KEY_IMAGE_PATHS)
        assertEquals(listOf("/tmp/img-4.png", "/tmp/img-3.png", "/tmp/img-2.png"), (0 until paths.length()).map { paths.getString(it) })
        assertEquals(listOf(4, 3, 2), stored)
        assertEquals(1, parts(result).length())
        assertEquals("look", parts(result).getJSONObject(0).getString("text"))
        assertTrue(result.unavailableReasons.isEmpty())
    }

    @Test
    fun visionCardDescribesImagesThatCannotBeStored() = runBlocking {
        val messages = JSONArray().put(userMessage(image(bytesA), image(bytesB)))
        var calls = 0
        val result = HarnessImageParts.rewrite(messages, true, describer) { _, _ -> if (calls++ == 0) "/tmp/first.png" else null }
        val message = result.messages.getJSONObject(0)
        assertEquals(1, message.getJSONArray(HarnessImageParts.KEY_IMAGE_PATHS).length())
        assertTrue(parts(result).getJSONObject(0).getString("text").contains("could not be described"))
    }

    @Test
    fun visionCardPassesNonUserImagesThroughDescriptions() = runBlocking {
        val tool = JSONObject().put("role", "tool").put("content", JSONArray().put(image(bytesA)))
        val result = HarnessImageParts.rewrite(JSONArray().put(tool), true, describer) { _, _ -> "/tmp/x" }
        assertFalse(result.messages.getJSONObject(0).has(HarnessImageParts.KEY_IMAGE_PATH))
        assertTrue(parts(result).getJSONObject(0).getString("text").contains("a red square"))
    }

    @Test
    fun plainMessagesAndRemoteUrlsAreHandled() = runBlocking {
        val plain = JSONObject().put("role", "user").put("content", "hello")
        val remote = userMessage(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://example.com/a.png")))
        val result = HarnessImageParts.rewrite(JSONArray().put(plain).put(remote), false, describer, null)
        assertEquals("hello", result.messages.getJSONObject(0).getString("content"))
        assertEquals(1, result.unavailableReasons.size)
        assertTrue(parts(result, 1).getJSONObject(0).getString("text").contains("could not be described"))
    }

    @Test
    fun cacheRoundTripsAtomically() {
        val dir = java.nio.file.Files.createTempDirectory("vision-cache").toFile()
        try {
            val sha = VisionDescriptionCache.sha256Hex(bytesA)
            assertEquals(64, sha.length)
            assertEquals(sha, VisionDescriptionCache.sha256Hex(bytesA.copyOf()))
            assertNull(VisionDescriptionCache.read(dir, sha))
            assertTrue(VisionDescriptionCache.writeAtomic(File(dir, "sub"), sha, " hello \n"))
            assertEquals("hello", VisionDescriptionCache.read(File(dir, "sub"), sha))
            assertTrue(VisionDescriptionCache.writeAtomic(File(dir, "sub"), sha, "second"))
            assertEquals("second", VisionDescriptionCache.read(File(dir, "sub"), sha))
            assertFalse(File(dir, "sub/$sha.txt.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failureMemoExpires() {
        var now = 0L
        val memo = VisionFailureMemo(ttlMs = 1000, clock = { now })
        memo.put("a", "boom")
        assertEquals("boom", memo.get("a"))
        now = 1000
        assertNull(memo.get("a"))
    }
}
