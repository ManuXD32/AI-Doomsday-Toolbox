package com.example.llamadroid.media

import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdGeneratedImageMetadata
import com.example.llamadroid.service.countSdRpcHelpers
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedMediaDetailsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `helper count ignores blanks and separators`() {
        assertEquals(0, countSdRpcHelpers(""))
        assertEquals(0, countSdRpcHelpers("  "))
        assertEquals(2, countSdRpcHelpers("10.0.0.2:50052, 10.0.0.3:50052"))
        assertEquals(1, countSdRpcHelpers("10.0.0.2:50052"))
    }

    @Test
    fun `enabled without servers is not distributed`() {
        assertNull(generatedMediaUsedHelpers(null))
        assertFalse(generatedMediaUsedHelpers(SdDistributedRuntimeConfig(enabled = true, rpcServers = ""))!!)
        assertFalse(generatedMediaUsedHelpers(SdDistributedRuntimeConfig(enabled = false, rpcServers = "a:1"))!!)
        assertTrue(generatedMediaUsedHelpers(SdDistributedRuntimeConfig(enabled = true, rpcServers = "a:1"))!!)
    }

    @Test
    fun `metadata json records usage and compression and old json reads defaults`() {
        val runtime = SdDistributedRuntimeConfig(
            enabled = true,
            rpcServers = "a:1,b:2",
            compressLayerTransfers = true
        )
        val config = SDConfig(
            modelPath = "/models/sd15.safetensors",
            modelFamily = "checkpoint",
            modelVariant = "sd1",
            prompt = "p",
            outputPath = "/tmp/out.png",
            distributedRuntime = runtime
        )
        val json = SdGeneratedImageMetadata.fromConfig(config, File("/tmp/out.png"), 10L).toJson()
        val text = json.toString()
        assertTrue(text.contains("\"distributedUsed\":true"))
        assertTrue(text.contains("\"rpcHelperCount\":2"))
        val restored = SdGeneratedImageMetadata.fromJson(json)
        assertTrue(restored.distributedRuntime.compressLayerTransfers)
        assertEquals(runtime.rpcServers, restored.distributedRuntime.rpcServers)

        val old = SdGeneratedImageMetadata.fromJson(
            org.json.JSONObject("""{"distributedEnabled":true,"distributedRpcServers":"a:1"}""")
        )
        assertFalse(old.distributedRuntime.compressLayerTransfers)
        assertTrue(generatedMediaUsedHelpers(old.distributedRuntime)!!)
    }

    @Test
    fun `gallery location prefers distributedUsed over enabled flag`() {
        val filesDir = temporaryFolder.newFolder("files")
        val dir = File(filesDir, "sd_output/txt2img").apply { mkdirs() }
        val enabledNoServers = File(dir, "a.png").apply { writeText("x") }
        File(dir, "a.json").writeText("""{"distributedEnabled":true,"distributedUsed":false,"createdAt":1,"prompt":"a"}""")
        val used = File(dir, "b.png").apply { writeText("x") }
        File(dir, "b.json").writeText("""{"distributedEnabled":true,"distributedUsed":true,"createdAt":2,"prompt":"b"}""")

        val items = MediaGalleryIndex.scan(filesDir)
        assertEquals(MediaGalleryLocation.LOCAL, items.single { it.file.name == enabledNoServers.name }.location)
        assertEquals(MediaGalleryLocation.DISTRIBUTED, items.single { it.file.name == used.name }.location)
    }
}
