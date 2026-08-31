package com.example.llamadroid.sd

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdArtifactInspectorTest {
    @Test
    fun safeTensorsSd3FullModel_isDetectedWithoutReadingPayload() {
        val file = tempFile("full.safetensors")
        writeSafeTensors(file, listOf(
            "model.diffusion_model.joint_blocks.0.x_block.attn.weight",
            "first_stage_model.encoder.conv_in.weight",
            "first_stage_model.decoder.conv_out.weight",
            "clip_l.text_model.encoder.layers.0.weight",
            "clip_g.text_model.encoder.layers.0.weight",
            "t5xxl.encoder.block.0.layer.0.weight"
        ))

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactFormat.SAFETENSORS, inspection.format)
        assertEquals(inspection.toString(), SdModelFamily.SD3, inspection.detectedFamily)
        assertEquals(SdArtifactRole.FULL_MODEL, inspection.detectedRole)
        assertEquals(SdMainLayout.FULL_MODEL, inspection.artifactLayout)
        assertTrue(inspection.containsDiffusion)
        assertTrue(inspection.containsVae)
        assertTrue(inspection.headerValid)
        assertTrue(inspection.isStructurallyUsable)
        assertFalse(inspection.headerFingerprint.isNullOrBlank())
        file.delete()
    }

    @Test
    fun safeTensorsSd3Diffusion_isNotPromotedToFullModel() {
        val file = tempFile("sd3.5_large_fp8.safetensors")
        writeSafeTensors(file, listOf(
            "model.diffusion_model.x_embedder.weight",
            "model.diffusion_model.joint_blocks.0.x_block.attn.weight"
        ))

        val inspection = SdArtifactInspector.inspect(file, SdArtifactRole.STANDALONE_DIFFUSION)

        assertEquals(inspection.toString(), SdModelFamily.SD3, inspection.detectedFamily)
        assertEquals(SdArtifactRole.STANDALONE_DIFFUSION, inspection.detectedRole)
        assertEquals(SdMainLayout.STANDALONE_DIFFUSION, inspection.artifactLayout)
        assertFalse(inspection.containsVae)
        assertEquals(SdArtifactRole.STANDALONE_DIFFUSION, inspection.configuredRole)
        file.delete()
    }

    @Test
    fun safeTensorsBundledSd3EncodersAreFullModelEvidenceEvenWithoutVae() {
        val file = tempFile("sd3-bundled.safetensors")
        writeSafeTensors(file, listOf(
            "model.diffusion_model.joint_blocks.0.x_block.attn.weight",
            "clip_l.text_model.encoder.layers.0.weight",
            "t5xxl.encoder.block.0.layer.0.weight"
        ))

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactRole.FULL_MODEL, inspection.detectedRole)
        assertEquals(SdMainLayout.FULL_MODEL, inspection.artifactLayout)
        assertFalse(inspection.containsVae)
        file.delete()
    }

    @Test
    fun unknownSafeTensors_remainsUnresolved() {
        val file = tempFile("weights.safetensors")
        writeSafeTensors(file, listOf("unrelated.layer.weight"))

        val inspection = SdArtifactInspector.inspect(file)

        assertNull(inspection.detectedFamily)
        assertNull(inspection.detectedRole)
        assertEquals(SdMainLayout.UNKNOWN, inspection.artifactLayout)
        file.delete()
    }

    @Test
    fun malformedSafeTensors_isRejectedWithoutThrowing() {
        val file = tempFile("bad.safetensors")
        file.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(64L).array())

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactFormat.SAFETENSORS, inspection.format)
        assertFalse(inspection.headerValid)
        assertFalse(inspection.isStructurallyUsable)
        assertTrue(inspection.warnings.isNotEmpty())
        file.delete()
    }

    @Test
    fun ggufDescriptorsAreParsedButPayloadIsNeverNeeded() {
        val file = tempFile("flux.gguf")
        writeGguf(file, architecture = "flux", tensorName = "double_blocks.0.img_attn.weight")

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactFormat.GGUF, inspection.format)
        assertEquals(inspection.toString(), SdModelFamily.FLUX_1, inspection.detectedFamily)
        assertEquals(SdArtifactRole.STANDALONE_DIFFUSION, inspection.detectedRole)
        assertTrue(inspection.isStructurallyUsable)
        file.delete()
    }

    @Test
    fun classicSdAndSdxlTensorLayouts_areDetectedAsFullCheckpoints() {
        listOf("sd-v1-5.safetensors", "sdxl-base.safetensors").forEach { filename ->
            val file = tempFile(filename)
            writeSafeTensors(file, listOf(
                "model.diffusion_model.input_blocks.0.0.weight",
                "first_stage_model.encoder.conv_in.weight"
            ))

            val inspection = SdArtifactInspector.inspect(file)

            assertEquals(inspection.toString(), SdModelFamily.CHECKPOINT, inspection.detectedFamily)
            assertEquals(SdArtifactRole.FULL_MODEL, inspection.detectedRole)
            assertEquals(SdMainLayout.FULL_MODEL, inspection.artifactLayout)
            assertEquals(SdInspectionConfidence.HIGH, inspection.confidence)
            file.delete()
        }
    }

    @Test
    fun componentArtifacts_areDetectedByTensorRole() {
        val cases = listOf(
            "vae.safetensors" to Pair("first_stage_model.decoder.conv_out.weight", SdArtifactRole.VAE),
            "clip_l.safetensors" to Pair("clip_l.transformer.resblocks.0.attn.weight", SdArtifactRole.CLIP_L),
            "clip_g.safetensors" to Pair("conditioner.embedders.1.transformer.resblocks.0.attn.weight", SdArtifactRole.CLIP_G),
            "t5xxl.safetensors" to Pair("t5xxl.encoder.block.0.layer.0.weight", SdArtifactRole.T5XXL)
        )

        cases.forEach { (filename, evidence) ->
            val file = tempFile(filename)
            writeSafeTensors(file, listOf(evidence.first))

            val inspection = SdArtifactInspector.inspect(file)

            assertEquals(inspection.toString(), evidence.second, inspection.detectedRole)
            assertEquals(SdMainLayout.COMPONENT, inspection.artifactLayout)
            assertTrue(inspection.isStructurallyUsable)
            file.delete()
        }
    }

    @Test
    fun excessiveSafeTensorsHeader_isRejectedBeforeAllocation() {
        val file = tempFile("excessive.safetensors")
        file.writeBytes(
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(SdArtifactInspector.MAX_SAFETENSORS_HEADER_BYTES + 1L)
                .array()
        )

        val inspection = SdArtifactInspector.inspect(file)

        assertFalse(inspection.headerValid)
        assertFalse(inspection.isStructurallyUsable)
        assertTrue(inspection.warnings.any { it.contains("32 MiB") })
        file.delete()
    }

    @Test
    fun zeroTensorSafeTensors_isNotStructurallyUsable() {
        val file = tempFile("empty.safetensors")
        writeSafeTensors(file, emptyList())

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(0L, inspection.tensorCount)
        assertFalse(inspection.isStructurallyUsable)
        assertTrue(inspection.warnings.any { it.contains("no tensor descriptors") })
        file.delete()
    }

    @Test
    fun truncatedGguf_isRejectedWithoutThrowing() {
        val file = tempFile("truncated.gguf")
        file.writeBytes("GGUF".toByteArray())

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactFormat.GGUF, inspection.format)
        assertFalse(inspection.headerValid)
        assertFalse(inspection.isStructurallyUsable)
        assertTrue(inspection.warnings.isNotEmpty())
        file.delete()
    }

    @Test
    fun safeTensorsCanBeRecognizedWhenExtensionIsBin() {
        val file = tempFile("weights.bin")
        writeSafeTensors(file, listOf(
            "model.diffusion_model.input_blocks.0.0.weight",
            "first_stage_model.decoder.conv_out.weight"
        ))

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactFormat.SAFETENSORS, inspection.format)
        assertEquals(SdArtifactRole.FULL_MODEL, inspection.detectedRole)
        file.delete()
    }

    private fun tempFile(name: String): File = Files.createTempFile("sd-inspector-", name).toFile()

    private fun writeSafeTensors(file: File, tensorNames: List<String>) {
        val descriptors = tensorNames.joinToString(",") { name ->
            "\"$name\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}"
        }
        val header = "{$descriptors}"
        val bytes = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.toByteArray().size.toLong()).array())
            write(header.toByteArray())
        }
        file.writeBytes(bytes.toByteArray())
    }

    private fun writeGguf(file: File, architecture: String, tensorName: String) {
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
        i64(1) // tensor count
        i64(1) // metadata count
        string("general.architecture")
        u32(8) // GGUF_TYPE_STRING
        string(architecture)
        string(tensorName)
        u32(0) // dimensions
        u32(0) // F32
        i64(0) // payload offset
        file.writeBytes(bytes.toByteArray())
    }
}
