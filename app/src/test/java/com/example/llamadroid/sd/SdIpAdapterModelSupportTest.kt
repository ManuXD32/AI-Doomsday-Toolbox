package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdIpAdapterModelSupportTest {

    @Test
    fun `only sd1 and sdxl checkpoint variants advertise ip adapter`() {
        assertTrue(resolveSdFamilySpec(SdModelFamily.CHECKPOINT, "sd1").supportsIpAdapter)
        assertTrue(resolveSdFamilySpec(SdModelFamily.CHECKPOINT, "sdxl").supportsIpAdapter)
        assertFalse(resolveSdFamilySpec(SdModelFamily.CHECKPOINT, "sd2").supportsIpAdapter)
        assertFalse(resolveSdFamilySpec(SdModelFamily.FLUX_1, null).supportsIpAdapter)
    }

    @Test
    fun `ip adapter assets map to dedicated optional component roles`() {
        assertEquals(
            SdComponentRole.CLIP_VISION,
            SdComponentRole.fromModelType(ModelType.SD_CLIP_VISION)
        )
        assertEquals(
            SdComponentRole.IP_ADAPTER,
            SdComponentRole.fromModelType(ModelType.SD_IP_ADAPTER)
        )

        val spec = resolveSdFamilySpec(SdModelFamily.CHECKPOINT, "sd1")
        assertTrue(spec.supportsIpAdapter)
        assertFalse(SdComponentRole.CLIP_VISION in spec.requiredRoles)
        assertFalse(SdComponentRole.IP_ADAPTER in spec.requiredRoles)
        assertFalse(SdComponentRole.CLIP_VISION in spec.optionalRoles)
        assertFalse(SdComponentRole.IP_ADAPTER in spec.optionalRoles)
    }

    @Test
    fun `new model compatibility defaults distinguish sd15 and sdxl`() {
        val expected = setOf("checkpoint:sd1", "checkpoint:sdxl")
        assertTrue(defaultCompatProfilesFor(ModelType.SD_CLIP_VISION).containsAll(expected))
        assertEquals(expected, defaultCompatProfilesFor(ModelType.SD_IP_ADAPTER))
    }
}
