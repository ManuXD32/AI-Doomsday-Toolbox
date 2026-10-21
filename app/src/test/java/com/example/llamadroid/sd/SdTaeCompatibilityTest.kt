package com.example.llamadroid.sd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SdTaeCompatibilityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun header(name: String = "decoder.1.weight", channels: Int = 16) = JSONObject(
        """{"__metadata__":{"format":"pt"},"$name":{"dtype":"F16","shape":[256,$channels,3,3],"data_offsets":[0,0]}}"""
    )

    private fun safetensors(headerJson: String): File {
        val bytes = headerJson.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        var len = bytes.size.toLong()
        repeat(8) { out.write((len and 0xFF).toInt()); len = len shr 8 }
        out.write(bytes)
        return temp.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    private val wan21 = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "2.1")
    private val lingbot = SdVideoFamilyProfiles.resolve(SdVideoFamily.LINGBOT_VIDEO)
    private val ti2v = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "2.2_ti2v_5b")
    private val a14b = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "2.2_t2v_a14b")
    private val ltx = SdVideoFamilyProfiles.resolve(SdVideoFamily.LTX_VIDEO)

    @Test
    fun headerParsing() {
        assertEquals(16, SdTaeCompatibility.latentChannelsFromHeader(header()))
        assertEquals(48, SdTaeCompatibility.latentChannelsFromHeader(header(channels = 48)))
        assertEquals(16, SdTaeCompatibility.latentChannelsFromHeader(header("taehv.decoder.1.weight")))
        assertNull(SdTaeCompatibility.latentChannelsFromHeader(header("decoder.2.weight")))
    }

    @Test
    fun fileReading() {
        val good = safetensors(header().toString())
        assertEquals(16, SdTaeCompatibility.readLatentChannels(good))
        assertNull(SdTaeCompatibility.readLatentChannels(safetensors("""{"x":{"shape":[1]}}""")))
        assertNull(SdTaeCompatibility.readLatentChannels(temp.newFile().also { it.writeBytes(byteArrayOf(1, 2)) }))
        assertNull(SdTaeCompatibility.readLatentChannels(temp.newFile().also { it.writeBytes(ByteArray(64) { 0x7F }) }))
        assertNull(SdTaeCompatibility.readLatentChannels(File(temp.root, "missing")))
    }

    @Test
    fun checks() {
        assertEquals(SdTaeCheck.Ok, SdTaeCompatibility.check(lingbot, 16))
        assertEquals(SdTaeCheck.Ok, SdTaeCompatibility.check(a14b, 16))
        assertEquals(SdTaeCheck.Mismatch(48, 16), SdTaeCompatibility.check(ti2v, 16))
        assertEquals(SdTaeCheck.NotSupported, SdTaeCompatibility.check(ltx, 16))
        assertEquals(SdTaeCheck.Unknown, SdTaeCompatibility.check(lingbot, null))
    }

    @Test
    fun prerequisitesEnforceTae() {
        val ti2vComponents = SdVideoComponentPaths(
            diffusionModelPath = "d", t5xxlPath = "t", taePath = "tae", taeLatentChannels = 16
        )
        val bad = ti2v.prerequisites(ti2vComponents, SdVideoInputs(), SdVideoWorkflow.TEXT_TO_VIDEO)
        assertEquals(SdTaeCheck.Mismatch(48, 16), bad.taeMismatch)
        assertFalse(bad.isSatisfied)

        val lingComponents = SdVideoFamilyProfiles.LINGBOT_DENSE_1_3B_COMPONENTS.copy(taeLatentChannels = 16)
        val ok = lingbot.prerequisites(lingComponents, SdVideoInputs(), SdVideoWorkflow.TEXT_TO_VIDEO)
        assertTrue(ok.toString(), ok.isSatisfied)
    }

    @Test
    fun inspectorWanTokensResolveToWan22Profiles() {
        // SdArtifactInspector writes these tokens for detected Wan 2.2 models.
        val inspectedTi2v = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "wan2_2_ti2v")
        assertEquals(SdVideoDecoderCompatibility.TAEHV_WAN22, inspectedTi2v.decoderCompatibility)
        assertTrue(SdVideoWorkflow.IMAGE_TO_VIDEO in inspectedTi2v.supportedWorkflows)
        val inspectedA14b = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "wan2_2")
        assertEquals(a14b.requiredComponentGroups, inspectedA14b.requiredComponentGroups)
        assertTrue(SdVideoFamilyProfiles.wanGenerationKnown("wan2_1"))
        assertFalse(SdVideoFamilyProfiles.wanGenerationKnown(null))
        assertFalse(SdVideoFamilyProfiles.wanGenerationKnown("my wan"))
    }

    @Test
    fun wanWithoutVersionAcceptsEitherWanTae() {
        val unknownWan = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN)
        assertEquals(SdTaeCheck.Ok, SdTaeCompatibility.check(unknownWan, 16))
        assertEquals(SdTaeCheck.Ok, SdTaeCompatibility.check(unknownWan, 48))
        assertEquals(SdTaeCheck.Mismatch(16, 32), SdTaeCompatibility.check(unknownWan, 32))
        assertEquals(SdTaeCheck.Mismatch(16, 48), SdTaeCompatibility.check(wan21, 48))
    }
}
