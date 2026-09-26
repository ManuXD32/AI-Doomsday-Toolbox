package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonParser
import okio.Buffer
import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatRequest
import java.io.File

class LlamaMultimodalPayloadTest {

    @Test
    fun `text only content stays a string`() {
        val content = buildNativeLlamaUserContent("hello world")

        assertTrue(content is String)
        assertEquals("hello world", content)
    }

    @Test
    fun `attachment only content serializes without text part`() {
        val audioFile = tempFile(".ogg", byteArrayOf(7, 8, 9))

        val content = buildNativeLlamaUserContent(
            userMessage = "",
            audioPath = audioFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(1, parts.size)

        val audioPart = parts[0] as Map<*, *>
        assertEquals("input_audio", audioPart["type"])
        val inputAudio = audioPart["input_audio"] as Map<*, *>
        assertEquals("mp3", inputAudio["format"])
        assertEquals("BwgJ", inputAudio["data"])
    }

    @Test
    fun `audio attachment serializes as llama input audio data`() {
        val audioFile = tempFile(".wav", byteArrayOf(1, 2, 3, 4))

        val content = buildNativeLlamaUserContent(
            userMessage = "transcribe this",
            audioPath = audioFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(2, parts.size)
        assertEquals("text", (parts[0] as Map<*, *>)["type"])

        val audioPart = parts[1] as Map<*, *>
        assertEquals("input_audio", audioPart["type"])
        val inputAudio = audioPart["input_audio"] as Map<*, *>
        assertEquals("wav", inputAudio["format"])
        assertEquals("AQIDBA==", inputAudio["data"])
    }

    @Test
    fun `image and audio attachments preserve order`() {
        val imageFile = tempFile(".jpg", byteArrayOf(9, 8, 7))
        val audioFile = tempFile(".m4a", byteArrayOf(4, 5, 6))

        val content = buildNativeLlamaUserContent(
            userMessage = "look and listen",
            imagePath = imageFile.absolutePath,
            audioPath = audioFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(3, parts.size)

        assertEquals("text", (parts[0] as Map<*, *>)["type"])
        assertEquals("image_url", (parts[1] as Map<*, *>)["type"])
        assertEquals("input_audio", (parts[2] as Map<*, *>)["type"])

        val imageUrl = ((parts[1] as Map<*, *>)["image_url"] as Map<*, *>)["url"] as String
        val inputAudio = (parts[2] as Map<*, *>)["input_audio"] as Map<*, *>
        assertTrue(imageUrl.startsWith("data:image/jpeg;base64,"))
        assertEquals("mp3", inputAudio["format"])
        assertEquals("BAUG", inputAudio["data"])
    }

    @Test
    fun `video attachment uses local file reference without base64 or format field`() {
        val videoFile = tempFile(".mp4", byteArrayOf(1, 2, 3, 4))

        val content = buildNativeLlamaUserContent(
            userMessage = "describe this clip",
            videoPath = videoFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(2, parts.size)
        assertEquals("text", (parts[0] as Map<*, *>) ["type"])
        val videoPart = parts[1] as Map<*, *>
        assertEquals("input_video", videoPart["type"])
        val inputVideo = videoPart["input_video"] as Map<*, *>
        assertEquals(localLlamaVideoFileUrl(videoFile.absolutePath), inputVideo["data"])
        assertTrue("format" !in inputVideo)
        assertTrue((inputVideo["data"] as String).startsWith("file://"))
    }

    @Test
    fun `video payload can carry separately extracted audio`() {
        val videoFile = tempFile(".mp4", byteArrayOf(1, 2, 3, 4))
        val audioFile = tempFile(".wav", byteArrayOf(5, 6, 7, 8))

        val content = buildNativeLlamaUserContent(
            userMessage = "watch and listen",
            videoPath = videoFile.absolutePath,
            videoData = "file://${videoFile.name}",
            additionalAudioPaths = listOf(audioFile.absolutePath)
        )

        val parts = content as List<*>
        assertEquals(3, parts.size)
        assertEquals("text", (parts[0] as Map<*, *>) ["type"])
        assertEquals("input_video", (parts[1] as Map<*, *>) ["type"])
        assertEquals("input_audio", (parts[2] as Map<*, *>) ["type"])
        val inputAudio = (parts[2] as Map<*, *>) ["input_audio"] as Map<*, *>
        assertEquals("wav", inputAudio["format"])
        assertEquals("BQYHCA==", inputAudio["data"])
    }

    @Test
    fun `remote video request streams bytes into marker without local path`() {
        val videoFile = tempFile(".mp4", byteArrayOf(1, 2, 3, 4))
        val marker = llamaVideoStreamMarker(0)
        val request = LlamaChatRequest(
            model = "video-model",
            messages = listOf(
                LlamaChatMessage(
                    role = "user",
                    content = listOf(
                        mapOf(
                            "type" to "input_video",
                            "input_video" to mapOf("data" to marker)
                        )
                    )
                )
            )
        )

        val body = buildStreamingLlamaChatRequestBody(request, listOf(videoFile.absolutePath))
        val buffer = Buffer()
        body.writeTo(buffer)
        val json = buffer.readUtf8()

        assertTrue("AQIDBA==" in json)
        assertTrue(marker !in json)
        assertTrue(videoFile.absolutePath !in json)
        assertTrue("input_video" in json)
        val parsedData = JsonParser.parseString(json)
            .asJsonObject["messages"].asJsonArray[0]
            .asJsonObject["content"].asJsonArray[0]
            .asJsonObject["input_video"].asJsonObject["data"].asString
        assertEquals("AQIDBA==", parsedData)
    }

    @Test
    fun `long video payload uses disclosed sampled frames without original input video`() {
        val videoFile = tempFile(".mp4", byteArrayOf(1, 2, 3, 4))
        val marker = llamaVideoStreamMarker(0)

        val content = buildNativeLlamaUserContent(
            userMessage = "what changes over time",
            videoPath = videoFile.absolutePath,
            videoFrameData = listOf(marker),
            videoSamplingDisclosure = "Sampled 1 frame uniformly",
            includeVideo = false
        )

        val parts = content as List<*>
        assertEquals(3, parts.size)
        assertEquals("text", (parts[0] as Map<*, *>) ["type"])
        assertEquals("text", (parts[1] as Map<*, *>) ["type"])
        assertEquals("Sampled 1 frame uniformly", (parts[1] as Map<*, *>) ["text"])
        assertEquals("image_url", (parts[2] as Map<*, *>) ["type"])
        assertTrue(parts.none { (it as Map<*, *>) ["type"] == "input_video" })
        assertTrue(videoFile.absolutePath !in parts.toString())
    }

    @Test
    fun `bounded video observation is injected as text while original video stays out`() {
        val videoFile = tempFile(".mp4", byteArrayOf(1, 2, 3, 4))

        val content = buildNativeLlamaUserContent(
            userMessage = "what happened",
            videoPath = videoFile.absolutePath,
            videoObservation = "Observed: a person enters the room.",
            includeVideo = false
        )

        val parts = content as List<*>
        assertEquals(2, parts.size)
        assertEquals("text", (parts[0] as Map<*, *>) ["type"])
        assertEquals(
            "Observed: a person enters the room.",
            (parts[1] as Map<*, *>) ["text"]
        )
        assertTrue(parts.none { (it as Map<*, *>) ["type"] == "input_video" })
    }

    @Test
    fun `transcribed audio message keeps text and skips audio payload`() {
        val message = LlamaMessageEntity(
            id = 1,
            chatId = 99,
            role = "user",
            content = "Question\n\nThis is the transcription of an audio sent by the user: Hello there",
            audioPath = "/tmp/audio.m4a"
        )

        val content = message.toNativeLlamaContent()

        assertTrue(content is String)
        assertEquals(message.content, content)
    }

    private fun tempFile(extension: String, bytes: ByteArray): File {
        val file = File.createTempFile("llama-multimodal", extension)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }
}
