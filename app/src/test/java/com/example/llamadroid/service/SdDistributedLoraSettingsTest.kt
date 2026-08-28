package com.example.llamadroid.service

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.toJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class SdDistributedLoraSettingsTest {
    @Test
    fun `distributed settings round trip preserves ordered image and Wan stacks`() {
        val source = SdDistributedMasterSettingsEntity(
            imageLoraEnabled = true,
            imageLoraPath = "/legacy/ignored.safetensors",
            imageLorasJson = listOf(
                SdLoraSpec("/loras/image-a.safetensors", 0.3f),
                SdLoraSpec("/loras/image-b.safetensors", 0.9f)
            ).toJsonArray().toString(),
            videoLorasJson = listOf(SdLoraSpec("/loras/video.safetensors", 0.6f)).toJsonArray().toString(),
            videoHighNoiseLorasJson = listOf(SdLoraSpec("/loras/high.safetensors", 1.1f, highNoiseOnly = true)).toJsonArray().toString(),
            videoLoraApplyMode = "at_runtime"
        )
        val restored = settingsFromJson(settingsToJson(source))
        assertEquals(source.imageLoras(), restored.imageLoras())
        assertEquals(source.videoLoras(), restored.videoLoras())
        assertEquals(source.videoHighNoiseLoras(), restored.videoHighNoiseLoras())
        assertEquals("at_runtime", restored.videoLoraApplyMode)
    }

    @Test
    fun `legacy distributed image lora is mapped into ordered stack`() {
        val restored = settingsFromJson(
            """{"imageLoraEnabled":true,"imageLoraPath":"/legacy/style.safetensors","loraStrength":"0.55"}"""
        )
        assertEquals(listOf(SdLoraSpec("/legacy/style.safetensors", 0.55f)), restored.imageLoras())
    }

    @Test
    fun `visible legacy image fields take precedence over stale json stack`() {
        val settings = SdDistributedMasterSettingsEntity(
            imageLoraEnabled = true,
            imageLoraPath = "/visible/style.safetensors",
            loraStrength = "0.65",
            imageLorasJson = listOf(
                SdLoraSpec("/stale/style.safetensors", 0.2f),
                SdLoraSpec("/stale/second.safetensors", 0.4f)
            ).toJsonArray().toString()
        )
        assertEquals(
            listOf(
                SdLoraSpec("/visible/style.safetensors", 0.65f),
                SdLoraSpec("/stale/style.safetensors", 0.2f),
                SdLoraSpec("/stale/second.safetensors", 0.4f)
            ),
            settings.imageLoras()
        )
    }

    @Test
    fun `disabled visible switch suppresses stale json stack`() {
        val settings = SdDistributedMasterSettingsEntity(
            imageLoraEnabled = false,
            imageLorasJson = listOf(SdLoraSpec("/stale/style.safetensors")).toJsonArray().toString()
        )
        assertEquals(emptyList<SdLoraSpec>(), settings.imageLoras())
    }
}
