package com.example.llamadroid.audio.music

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Exercises the complete disposable FP32 patch path with the schema fixture. */
@RunWith(RobolectricTestRunner::class)
class StableAudio3LoraMergeTest {
    @Test
    fun preparePatchesDisposableCopyAndPreservesBase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "stable-audio-merge-test-${System.nanoTime()}")
            .apply { mkdirs() }
        val base = File(root, "dit_fp32.tflite")
        val adapter = File(root, "adapter.safetensors")
        try {
            javaClass.classLoader!!.getResourceAsStream("audio/music/lora-fc-fp32.tflite")!!
                .use { input -> base.outputStream().use { output -> input.copyTo(output) } }
            val original = base.readBytes()
            writeAdapter(adapter)

            val request = StableAudio3Request(
                kind = StableAudio3Kind.MUSIC,
                operation = StableAudio3Operation.GENERATE,
                components = StableAudio3Components(
                    tokenizer = StableAudio3ComponentRef("tokenizer.model"),
                    textEncoder = StableAudio3ComponentRef("text_encoder.tflite"),
                    dit = StableAudio3ComponentRef(base.absolutePath),
                    codecDecoder = StableAudio3ComponentRef("decoder.tflite")
                ),
                prompt = "test",
                loras = listOf(StableAudio3Lora(adapter.absolutePath, strength = 0.5f)),
                outputPath = File(root, "output.wav").absolutePath
            )

            val prepared = StableAudio3LoraMerge.prepare(context, request)

            assertTrue(prepared.loras.isEmpty())
            assertNotEquals(base.canonicalPath, prepared.components.dit.path)
            assertTrue(File(prepared.components.dit.path).isFile)
            assertEquals(File(prepared.components.dit.path).length(), prepared.components.dit.sizeBytes)
            assertEquals(prepared.components.dit.sha256, StableAudio3SafeTensors.sha256(File(prepared.components.dit.path)))
            assertArrayEquals(original, base.readBytes())

            val merged = StableAudio3TfliteWeights.discover(File(prepared.components.dit.path)).single()
            val values = ByteBuffer.wrap(
                File(prepared.components.dit.path).readBytes(),
                merged.offset.toInt(),
                merged.byteSize
            ).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1.5f, values.float, 0f)
            assertEquals(2f, values.float, 0f)
            assertEquals(3f, values.float, 0f)
            assertEquals(5f, values.float, 0f)
            assertEquals(5f, values.float, 0f)
            assertEquals(6f, values.float, 0f)
            assertEquals(prepared.components.dit.path, StableAudio3LoraMerge.prepare(context, request).components.dit.path)
            val stacked = StableAudio3LoraMerge.prepare(context, request.copy(loras = request.loras + request.loras))
            val stackedWeight = StableAudio3TfliteWeights.discover(File(stacked.components.dit.path)).single()
            val stackedValues = ByteBuffer.wrap(File(stacked.components.dit.path).readBytes(),
                stackedWeight.offset.toInt(), stackedWeight.byteSize).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2f, stackedValues.float, 0f)
            assertArrayEquals(original, base.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeAdapter(file: File) {
        val aName = "transformer.project_in.parametrizations.weight.0.lora_A"
        val bName = "transformer.project_in.parametrizations.weight.0.lora_B"
        val aBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(0f).putFloat(0f).array()
        val bBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(2f).array()
        val header = JSONObject().apply {
            put("__metadata__", JSONObject().put(
                "lora_config", JSONObject().put("rank", 1).toString()
            ))
            put(aName, tensor("F32", intArrayOf(1, 3), 0, aBytes.size))
            put(bName, tensor("F32", intArrayOf(2, 1), aBytes.size, aBytes.size + bBytes.size))
        }.toString().toByteArray(Charsets.UTF_8)
        file.outputStream().use { output ->
            output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array())
            output.write(header)
            output.write(aBytes)
            output.write(bBytes)
        }
    }

    private fun tensor(dtype: String, shape: IntArray, start: Int, end: Int): JSONObject = JSONObject().apply {
        put("dtype", dtype)
        put("shape", JSONArray().apply { shape.forEach(::put) })
        put("data_offsets", JSONArray().put(start).put(end))
    }
}
