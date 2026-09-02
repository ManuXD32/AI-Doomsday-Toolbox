package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdParamsBackendProfileTest {
    @Test
    fun `active run modules exclude unused optional components`() {
        val modules = SdActiveRunComponents(
            diffusion = true,
            textEncoders = true,
            vae = true,
            controlNet = false,
            upscaler = false,
            detector = false
        ).paramsModules()

        assertEquals(
            setOf(SdParamsModule.DIFFUSION, SdParamsModule.TE, SdParamsModule.VAE),
            modules
        )
    }

    @Test
    fun `upscale run exposes only its loaded upscaler`() {
        assertEquals(
            setOf(SdParamsModule.UPSCALER),
            SdActiveRunComponents(upscaler = true).paramsModules()
        )
    }
    @Test
    fun normalPresetEmitsNoFlag() {
        val profile = resolveSdParamsBackendProfile("auto")
        assertTrue(profile.assignments.isEmpty())
        assertEquals(null, profile.cliValue)
        assertEquals("auto", profile.storedValue)
    }

    @Test
    fun legacyDiskValueRemainsReadableWhenNewSpecIsAuto() {
        val profile = resolveSdParamsBackendProfile("auto", legacyMode = "disk")
        assertEquals("disk", profile.cliValue)
        assertEquals(SdParamsModule.entries.toSet(), profile.assignments.keys)
    }

    @Test
    fun textEncoderAliasesCollapseIntoOneTeGroup() {
        val profile = resolveSdParamsBackendProfile("clip_l=disk,clip_g=auto,t5xxl=disk")
        assertEquals("te=disk", profile.cliValue)
        assertTrue(profile.warnings.any { it.contains("te", ignoreCase = true) })
    }

    @Test
    fun mixedAssignmentsAreCanonicalAndOrdered() {
        val profile = resolveSdParamsBackendProfile("vae=disk,diffusion=disk,te=disk")
        assertEquals("diffusion=disk,te=disk,vae=disk", profile.cliValue)
        assertEquals("diffusion=disk,te=disk,vae=disk", profile.storedValue)
    }

    @Test
    fun unsupportedModulesAreDroppedWithWarning() {
        val profile = resolveSdParamsBackendProfile("bogus=disk,vae=disk")
        assertEquals("vae=disk", profile.cliValue)
        assertTrue(profile.warnings.any { it.contains("bogus") })
    }

    @Test
    fun artifactProjectionKeepsOnlyRelevantModule() {
        val vae = ModelEntity(
            filename = "vae.safetensors",
            path = "/models/vae.safetensors",
            sizeBytes = 1,
            type = ModelType.SD_VAE,
            repoId = "local",
            sdParamsBackendSpec = "diffusion=disk,vae=disk,te=disk"
        )
        assertEquals("vae=disk", resolveSdParamsBackendProfile(
            vae.sdParamsBackendSpec,
            vae.sdParamsBackendMode
        ).forArtifact(vae).storedValue)
    }

    @Test
    fun selectedEncoderConflictResolvesToDisk() {
        val clipL = ModelEntity(
            filename = "clip_l.gguf",
            path = "/models/clip_l.gguf",
            sizeBytes = 1,
            type = ModelType.SD_CLIP_L,
            repoId = "local",
            sdParamsBackendSpec = "te=auto"
        )
        val clipG = clipL.copy(
            filename = "clip_g.gguf",
            path = "/models/clip_g.gguf",
            type = ModelType.SD_CLIP_G,
            sdParamsBackendSpec = "te=disk"
        )
        val merged = resolveSdParamsBackendProfileForArtifacts(listOf(clipL, clipG))
        assertEquals("te=disk", merged.storedValue)
        assertTrue(merged.warnings.any { it.contains("te", ignoreCase = true) })
    }
}
