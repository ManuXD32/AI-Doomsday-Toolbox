package com.example.llamadroid.service

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingMediaRequestTest {
    @Test
    fun `request body streams base64 into the envelope`() {
        val source = File.createTempFile("video-stream", ".bin")
        try {
            source.writeText("hello video")
            val body = streamingBase64JsonRequestBody(
                file = source,
                jsonPrefix = "{\"video\":",
                jsonSuffix = "}"
            )
            val sink = Buffer()

            assertEquals(-1L, body.contentLength())
            body.writeTo(sink)

            assertEquals("{\"video\":\"aGVsbG8gdmlkZW8=\"}", sink.readUtf8())
            assertTrue(source.isFile)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `marker body streams ordered frame files without materializing payload`() {
        val first = File.createTempFile("video-frame-one", ".jpg")
        val second = File.createTempFile("video-frame-two", ".jpg")
        try {
            first.writeText("one")
            second.writeText("two")
            val json = "{\"first\":\"${llamaVideoStreamMarker(0)}\",\"second\":\"${llamaVideoStreamMarker(1)}\"}"
            val body = buildStreamingJsonRequestBody(
                json = json,
                files = listOf(first.absolutePath, second.absolutePath),
                valuePrefix = "data:image/jpeg;base64,"
            )
            val sink = Buffer()

            assertEquals(-1L, body.contentLength())
            body.writeTo(sink)

            assertEquals(
                "{\"first\":\"data:image/jpeg;base64,b25l\",\"second\":\"data:image/jpeg;base64,dHdv\"}",
                sink.readUtf8().replace(" ", "")
            )
        } finally {
            first.delete()
            second.delete()
        }
    }
}
