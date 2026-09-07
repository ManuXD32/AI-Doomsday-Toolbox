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
    fun genericClipGTensorNamesUseClipGFilenameEvidence() {
        val file = tempFile("clip_g.safetensors")
        writeSafeTensors(file, listOf("text_model.encoder.layers.0.self_attn.q_proj.weight"))

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactRole.CLIP_G, inspection.detectedRole)
        assertTrue(inspection.containsClipG)
        assertFalse(inspection.containsClipL)
        file.delete()
    }

    @Test
    fun t5GgufWithQuantizedBlockNamesIsNotClassifiedAsGenericLlm() {
        val file = tempFile("pru-t5-v1_1-xxl-encoder-Q4_K_M.gguf")
        writeGguf(file, architecture = "t5", tensorName = "blk.0.attn.q.weight")

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactRole.T5XXL, inspection.detectedRole)
        assertTrue(inspection.containsT5xxl)
        assertFalse(inspection.containsLlm)
        file.delete()
    }

    @Test
    fun sd3FullModelReportsAllInternalComponentsFromTensorPaths() {
        val file = tempFile("sd3_medium_incl_clips_t5xxlfp8.safetensors")
        writeSafeTensors(file, listOf(
            "model.diffusion_model.joint_blocks.0.x_block.attn.qkv.weight",
            "first_stage_model.encoder.down.0.block.0.conv1.weight",
            "text_encoders.0.transformer.text_model.embeddings.token_embedding.weight",
            "text_encoders.1.transformer.text_model.embeddings.token_embedding.weight",
            "text_encoders.2.transformer.encoder.block.0.layer.0.SelfAttention.q.weight"
        ))

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdModelFamily.SD3, inspection.detectedFamily)
        assertEquals(SdArtifactRole.FULL_MODEL, inspection.detectedRole)
        assertTrue(inspection.containsDiffusion)
        assertTrue(inspection.containsVae)
        assertTrue(inspection.containsClipL)
        assertTrue(inspection.containsClipG)
        assertTrue(inspection.containsT5xxl)
        assertFalse(inspection.containsLlm)
        file.delete()
    }

    @Test
    fun textEncoderLoraIsNotMisclassifiedAsClipL() {
        val file = tempFile("sdxl-text-encoder-lora.safetensors")
        writeSafeTensors(file, listOf(
            "lora_te1_text_model_encoder_layers_0_self_attn_q_proj.lora_down.weight",
            "lora_te1_text_model_encoder_layers_0_self_attn_q_proj.lora_up.weight"
        ))

        val inspection = SdArtifactInspector.inspect(file)

        assertEquals(SdArtifactRole.LORA, inspection.detectedRole)
        assertEquals(SdMainLayout.COMPONENT, inspection.artifactLayout)
        assertFalse(inspection.containsClipL)
        assertFalse(inspection.containsDiffusion)
        assertFalse(inspection.containsLlm)
        file.delete()
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

    @Test
    fun nativeVideoSignaturesResolveFamilyWithoutFilenameHints() {
        val cases = listOf(
            "arbitrary.safetensors" to (
                "model.diffusion_model.blocks.0.cross_attn.norm_k.weight" to SdModelFamily.WAN
            ),
            "random.safetensors" to (
                "model.diffusion_model.txt_in.individual_token_refiner.blocks.0.adaLN_modulation.1.weight" to
                    SdModelFamily.HUNYUAN_VIDEO
            ),
            "weights.safetensors" to (
                "model.diffusion_model.patch_embedder.weight" to SdModelFamily.LINGBOT_VIDEO
            ),
            "component.safetensors" to (
                "model.diffusion_model.adaln_single.emb.timestep_embedder.linear_1.bias" to
                    SdModelFamily.LTX_VIDEO
            ),
            "model.safetensors" to (
                "model.diffusion_model.input_blocks.8.0.time_mixer.mix_factor" to SdModelFamily.SVD
            )
        )

        cases.forEach { (filename, evidence) ->
            val file = tempFile(filename)
            writeSafeTensors(file, listOf(evidence.first))
            val inspection = SdArtifactInspector.inspect(file)

            assertEquals(evidence.second, inspection.detectedFamily)
            assertTrue(inspection.detectedVideoFamily != null)
            assertTrue(inspection.confidence == SdInspectionConfidence.HIGH)
            assertTrue(inspection.detectedRole != null)
            file.delete()
        }
    }

    @Test
    fun videoVariantComesFromCapturedMetadataAndRoundTrips() {
        val file = tempFile("neutral.safetensors")
        writeSafeTensors(file, listOf("model.diffusion_model.blocks.0.cross_attn.norm_k.weight"))
        // SafeTensors metadata is intentionally not synthesized by the helper;
        // a persisted summary still verifies that the variant is durable.
        val original = SdArtifactInspection(
            format = SdArtifactFormat.SAFETENSORS,
            detectedFamily = SdModelFamily.WAN,
            detectedRole = SdArtifactRole.STANDALONE_DIFFUSION,
            detectedVariant = "wan2_2_ti2v",
            containsDiffusion = true,
            tensorCount = 1L,
            confidence = SdInspectionConfidence.HIGH
        )
        val restored = SdArtifactInspection.fromJson(original.toJson())

        assertEquals("wan2_2_ti2v", restored?.detectedVariant)
        assertEquals(SdVideoFamily.WAN, restored?.detectedVideoFamily)
        file.delete()
    }

    @Test
    fun videoFilenameAloneDoesNotClassifyUnrelatedTensorHeader() {
        val file = tempFile("wan2.2.safetensors")
        writeSafeTensors(file, listOf("unrelated.layer.weight"))

        val inspection = SdArtifactInspector.inspect(file)

        assertNull(inspection.detectedFamily)
        assertNull(inspection.detectedVideoFamily)
        assertNull(inspection.detectedRole)
        file.delete()
    }

    @Test
    fun videoCompanionSignaturesResolveAppendedArtifactRoles() {
        val cases = listOf(
            "audio_vae.encoder.conv.weight" to SdArtifactRole.AUDIO_VAE,
            "video_embeddings_connector.transformer_1d_blocks.0.attn1.to_q.weight" to SdArtifactRole.EMBEDDINGS_CONNECTORS,
            "model.diffusion_model.motion_module.temporal_transformer.weight" to SdArtifactRole.MOTION_MODULE
        )
        cases.forEach { (tensor, role) ->
            val file = tempFile("component.safetensors")
            writeSafeTensors(file, listOf(tensor))
            assertEquals(role, SdArtifactInspector.inspect(file).detectedRole)
            file.delete()
        }
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
