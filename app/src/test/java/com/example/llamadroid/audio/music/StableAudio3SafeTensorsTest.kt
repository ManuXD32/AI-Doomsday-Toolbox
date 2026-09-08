package com.example.llamadroid.audio.music

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StableAudio3SafeTensorsTest {
    @Test
    fun nativeAdapterHeaderIsDecodedWithoutLoadingPickleFormats() {
        val file = File.createTempFile("stable-audio-lora-", ".safetensors")
        try {
            writeAdapter(file)
            val parsed = StableAudio3SafeTensors.parse(file.absolutePath, "digest")
            assertEquals("lora", parsed.adapterType)
            assertEquals(1, parsed.layers.size)
            assertEquals(listOf(1, 2), parsed.layers.getValue("layer").parameters.getValue("lora_A").shape.toList())
            assertEquals(listOf(2, 1), parsed.layers.getValue("layer").parameters.getValue("lora_B").shape.toList())
            assertTrue(parsed.scaling > 0f)
            assertThrows(IllegalArgumentException::class.java) {
                StableAudio3SafeTensors.parse(file.absolutePath, "digest", maxDecodedBytes = 8L)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun pickleBackedAdapterIsRejectedByExtension() {
        val file = File.createTempFile("stable-audio-lora-", ".ckpt")
        try {
            file.writeText("not a model")
            assertThrows(IllegalArgumentException::class.java) {
                StableAudio3SafeTensors.resolveAdapter(file.absolutePath)
            }
        } finally {
            file.delete()
        }
    }

    private fun writeAdapter(file: File) {
        val aName = "layer.parametrizations.weight.0.lora_A"
        val bName = "layer.parametrizations.weight.0.lora_B"
        val aBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(2f).array()
        val bBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(3f).putFloat(4f).array()
        val header = JSONObject().apply {
            put("__metadata__", JSONObject().put("lora_config", JSONObject().put("rank", 1).toString()))
            put(aName, JSONObject().apply {
                put("dtype", "F32")
                put("shape", JSONArray().put(1).put(2))
                put("data_offsets", JSONArray().put(0).put(aBytes.size))
            })
            put(bName, JSONObject().apply {
                put("dtype", "F32")
                put("shape", JSONArray().put(2).put(1))
                put("data_offsets", JSONArray().put(aBytes.size).put(aBytes.size + bBytes.size))
            })
        }.toString().toByteArray(Charsets.UTF_8)
        file.outputStream().use { output ->
            output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array())
            output.write(header)
            output.write(aBytes)
            output.write(bBytes)
        }
    }
}
