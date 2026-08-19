package com.example.llamadroid.service

import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.toJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import org.json.JSONObject

class SdMetadataLoraRoundTripTest {
    @Test
    fun `image metadata keeps ordered base and detail LoRAs`() {
        val config = SDConfig(
            modelPath = "/models/sd15.safetensors",
            modelFamily = "checkpoint",
            modelVariant = "sd1",
            prompt = "portrait",
            outputPath = "/tmp/out.png",
            workflowPresetId = "workflow-face-fast",
            workflowBundleId = "workflow-face-fast",
            workflowRevision = "workflow-sd15-q4-face@fcce3d7f",
            loras = listOf(
                SdLoraSpec("/loras/base-a.safetensors", 0.5f),
                SdLoraSpec("/loras/base-b.safetensors", 0.8f)
            ),
            loraApplyMode = SdLoraApplyMode.IMMEDIATELY,
            adetailer = SdADetailerConfig(
                modelPath = "/models/face.safetensors",
                loras = listOf(SdLoraSpec("/loras/detail.safetensors", 0.7f))
            )
        )
        val restored = SdGeneratedImageMetadata.fromJson(
            SdGeneratedImageMetadata.fromConfig(config, File("/tmp/out.png"), 10L).toJson()
        )
        assertEquals(config.loras, restored.loras)
        assertEquals(config.adetailer?.loras, restored.adetailerLoras)
        assertEquals(SdLoraApplyMode.IMMEDIATELY.cliName, restored.loraApplyMode)
        assertEquals(config.workflowPresetId, restored.workflowPresetId)
        assertEquals(config.workflowBundleId, restored.workflowBundleId)
        assertEquals(config.workflowRevision, restored.workflowRevision)
    }

    @Test
    fun `video metadata keeps regular and Wan high noise item order`() {
        val regular = listOf(SdLoraSpec("/loras/video-a.safetensors", 0.4f))
        val highNoise = listOf(SdLoraSpec("/loras/video-high.safetensors", 1.1f, highNoiseOnly = true))
        val restored = GeneratedVideoMetadata.fromJson(
            JSONObject()
                .put("loras", regular.toJsonArray())
                .put("highNoiseLoras", highNoise.toJsonArray())
                .put("loraApplyMode", SdLoraApplyMode.AT_RUNTIME.cliName)
        )
        assertEquals(regular, restored.loras)
        assertEquals(highNoise, restored.highNoiseLoras)
        assertEquals(SdLoraApplyMode.AT_RUNTIME.cliName, restored.loraApplyMode)
    }
}
