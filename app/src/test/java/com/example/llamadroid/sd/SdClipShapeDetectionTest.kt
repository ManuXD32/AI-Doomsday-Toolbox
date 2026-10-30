package com.example.llamadroid.sd

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLIP-G vs CLIP-L classification from tensor shapes and real display names (SAF temp copies). */
class SdClipShapeDetectionTest {
    private val prefix = "text_model.encoder.layers"

    private fun clipTensors(hidden: Long, layers: Int): List<Pair<String, List<Long>>> =
        listOf(
            "text_model.embeddings.token_embedding.weight" to listOf(49408L, hidden),
            "text_model.final_layer_norm.weight" to listOf(hidden)
        ) + (0 until layers).map { "$prefix.$it.self_attn.q_proj.weight" to listOf(hidden, hidden) }

    @Test
    fun safeTensorsClipGIsDetectedByShapeUnderMeaninglessName() {
        val file = tempFile("blob-1234.safetensors")
        writeSafeTensors(file, clipTensors(1280, 32))
        val inspection = SdArtifactInspector.inspect(file)
        assertEquals(SdArtifactRole.CLIP_G, inspection.detectedRole)
        assertTrue(inspection.containsClipG)
        assertFalse(inspection.containsClipL)
        file.delete()
    }

    @Test
    fun safeTensorsClipLIsDetectedByShapeUnderMeaninglessName() {
        val file = tempFile("blob-5678.safetensors")
        writeSafeTensors(file, clipTensors(768, 12))
        val inspection = SdArtifactInspector.inspect(file)
        assertEquals(SdArtifactRole.CLIP_L, inspection.detectedRole)
        assertTrue(inspection.containsClipL)
        file.delete()
    }

    @Test
    fun shapeEvidenceOutranksMisleadingFilename() {
        val file = tempFile("clip_l.safetensors")
        writeSafeTensors(file, clipTensors(1280, 32))
        assertEquals(SdArtifactRole.CLIP_G, SdArtifactInspector.inspect(file).detectedRole)
        file.delete()
    }

    @Test
    fun ggufClipGShapeUsesReversedDimensionOrder() {
        val file = tempFile("blob-9.gguf")
        writeGguf(file, clipTensors(1280, 32).map { it.first to it.second.reversed() })
        assertEquals(SdArtifactRole.CLIP_G, SdArtifactInspector.inspect(file).detectedRole)
        file.delete()
    }

    @Test
    fun ggufClipLShapeIsDetected() {
        val file = tempFile("blob-10.gguf")
        writeGguf(file, clipTensors(768, 12).map { it.first to it.second.reversed() })
        assertEquals(SdArtifactRole.CLIP_L, SdArtifactInspector.inspect(file).detectedRole)
        file.delete()
    }

    @Test
    fun displayNameIsUsedWhenShapesAreAmbiguous() {
        val file = tempFile("tmp-copy.safetensors")
        writeSafeTensors(file, listOf(
            "text_model.encoder.layers.0.self_attn.q_proj.weight" to listOf(4L, 4L)
        ))
        val inspector = SdArtifactInspector()
        for (name in listOf("clipg.safetensors", "bigG_encoder.safetensors", "Open_CLIP_text.safetensors", "CLIP-G.safetensors")) {
            assertEquals(name, SdArtifactRole.CLIP_G, inspector.inspectWithDisplayName(file, name).detectedRole)
        }
        assertEquals(SdArtifactRole.CLIP_L, inspector.inspectWithDisplayName(file, "clip_l.safetensors").detectedRole)
        file.delete()
    }

    @Test
    fun genericOnlyEvidenceIsLowConfidence() {
        val file = tempFile("tmp-generic.safetensors")
        writeSafeTensors(file, listOf(
            "text_model.encoder.layers.0.self_attn.q_proj.weight" to listOf(4L, 4L)
        ))
        val inspection = SdArtifactInspector.inspect(file)
        assertEquals(SdArtifactRole.CLIP_L, inspection.detectedRole)
        assertEquals(SdInspectionConfidence.LOW, inspection.confidence)
        file.delete()
    }

    private fun tempFile(name: String): File = Files.createTempFile("sd-clip-shape-", name).toFile()

    private fun writeSafeTensors(file: File, tensors: List<Pair<String, List<Long>>>) {
        val descriptors = tensors.joinToString(",") { (name, shape) ->
            "\"$name\":{\"dtype\":\"F32\",\"shape\":[${shape.joinToString(",")}],\"data_offsets\":[0,4]}"
        }
        val header = "{$descriptors}"
        val bytes = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.toByteArray().size.toLong()).array())
            write(header.toByteArray())
        }
        file.writeBytes(bytes.toByteArray())
    }

    private fun writeGguf(file: File, tensors: List<Pair<String, List<Long>>>) {
        val bytes = ByteArrayOutputStream()
        fun u32(value: Long) = bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
        fun i64(value: Long) = bytes.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        fun string(value: String) {
            val raw = value.toByteArray()
            i64(raw.size.toLong())
            bytes.write(raw)
        }
        bytes.write("GGUF".toByteArray())
        u32(3)
        i64(tensors.size.toLong())
        i64(1)
        string("general.architecture")
        u32(8)
        string("clip")
        tensors.forEach { (name, dims) ->
            string(name)
            u32(dims.size.toLong())
            dims.forEach { i64(it) }
            u32(0)
            i64(0)
        }
        file.writeBytes(bytes.toByteArray())
    }
}
