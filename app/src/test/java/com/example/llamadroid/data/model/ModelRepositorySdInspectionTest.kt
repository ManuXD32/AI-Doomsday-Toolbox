package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.SdArtifactFormat
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdInspectionConfidence
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.effectiveSdCompatProfiles
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.sd.resolveSdCompatProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRepositorySdInspectionTest {
    @Test
    fun highConfidenceStandaloneArtifactCannotBeImportedAsFullCheckpoint() {
        val inspection = inspection(
            family = SdModelFamily.SD3,
            role = SdArtifactRole.STANDALONE_DIFFUSION,
            layout = SdMainLayout.STANDALONE_DIFFUSION,
            confidence = SdInspectionConfidence.HIGH
        )

        val result = ModelRepository.validateSdArtifactInspection(
            configuredType = ModelType.SD_CHECKPOINT,
            inspection = inspection,
            configuredFamily = SdModelFamily.SD3.storedValue
        )

        assertFalse(result.isSuccess)
        assertEquals(SdArtifactValidationCode.ROLE_CONTRADICTION, result.exceptionOrNull()?.let {
            (it as SdArtifactValidationException).code
        })
    }

    @Test
    fun genericDiffusionImportAcceptsProvenFullModelLayout() {
        val inspection = inspection(
            family = SdModelFamily.SD3,
            role = SdArtifactRole.FULL_MODEL,
            layout = SdMainLayout.FULL_MODEL,
            confidence = SdInspectionConfidence.HIGH
        )

        val result = ModelRepository.validateSdArtifactInspection(
            configuredType = ModelType.SD_DIFFUSION,
            inspection = inspection,
            configuredFamily = SdModelFamily.SD3.storedValue
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun lowConfidenceEvidenceRemainsManuallyConfigurable() {
        val inspection = inspection(
            family = null,
            role = null,
            layout = SdMainLayout.UNKNOWN,
            confidence = SdInspectionConfidence.UNKNOWN
        )

        val result = ModelRepository.validateSdArtifactInspection(
            configuredType = ModelType.SD_DIFFUSION,
            inspection = inspection,
            configuredFamily = SdModelFamily.FLUX_1.storedValue
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun malformedSafeTensorsIsBlockedBeforePersistence() {
        val inspection = inspection(
            family = null,
            role = null,
            layout = SdMainLayout.UNKNOWN,
            confidence = SdInspectionConfidence.UNKNOWN,
            warnings = listOf("SafeTensors header is truncated")
        )

        val result = ModelRepository.validateSdArtifactInspection(
            configuredType = ModelType.SD_DIFFUSION,
            inspection = inspection
        )

        assertFalse(result.isSuccess)
        assertEquals(SdArtifactValidationCode.INVALID_ARTIFACT, (result.exceptionOrNull() as SdArtifactValidationException).code)
    }

    @Test
    fun highConfidenceWrongFamilyVaeIsBlocked() {
        val result = ModelRepository.validateSdArtifactInspection(
            configuredType = ModelType.SD_VAE,
            inspection = inspection(
                family = SdModelFamily.CHECKPOINT,
                role = SdArtifactRole.VAE,
                layout = SdMainLayout.COMPONENT,
                confidence = SdInspectionConfidence.HIGH
            ),
            configuredFamily = SdModelFamily.SD3.storedValue
        )

        assertFalse(result.isSuccess)
        assertEquals(
            SdArtifactValidationCode.FAMILY_CONTRADICTION,
            (result.exceptionOrNull() as SdArtifactValidationException).code
        )
    }

    @Test
    fun inferredIpAdapterProfileIsNarrowedToSdxl() {
        val inferred = inferSdFamily(
            ModelType.SD_IP_ADAPTER,
            "h94/IP-Adapter",
            "ip-adapter-plus_sdxl.safetensors"
        )
        assertEquals(
            "checkpoint:sdxl",
            resolveSdCompatProfiles(
                type = ModelType.SD_IP_ADAPTER,
                explicitProfiles = null,
                family = inferred.first,
                variant = inferred.second
            )
        )
    }

    @Test
    fun inferredIpAdapterProfileIsNarrowedToSd1() {
        val inferred = inferSdFamily(
            ModelType.SD_IP_ADAPTER,
            "h94/IP-Adapter",
            "ip-adapter_sd15.safetensors"
        )
        assertEquals(
            "checkpoint:sd1",
            resolveSdCompatProfiles(
                type = ModelType.SD_IP_ADAPTER,
                explicitProfiles = null,
                family = inferred.first,
                variant = inferred.second
            )
        )
    }

    @Test
    fun inferredClipVisionProfileIsNarrowedToSd1() {
        val inferred = inferSdFamily(
            ModelType.SD_CLIP_VISION,
            "h94/IP-Adapter",
            "clip-vision_sd-1-5.safetensors"
        )

        assertEquals(
            "checkpoint:sd1",
            resolveSdCompatProfiles(
                type = ModelType.SD_CLIP_VISION,
                explicitProfiles = null,
                family = inferred.first,
                variant = inferred.second
            )
        )
    }

    @Test
    fun explicitNonLegacyIpAdapterProfilesRemainAuthoritative() {
        assertEquals(
            "checkpoint:sd1,checkpoint:sdxl,custom",
            resolveSdCompatProfiles(
                type = ModelType.SD_IP_ADAPTER,
                explicitProfiles = "checkpoint:sd1,checkpoint:sdxl,custom",
                family = SdModelFamily.CHECKPOINT,
                variant = "sdxl"
            )
        )
    }

    @Test
    fun ambiguousIpAdapterProfileKeepsBroadDefaults() {
        assertEquals(
            "checkpoint:sd1,checkpoint:sdxl",
            resolveSdCompatProfiles(
                type = ModelType.SD_IP_ADAPTER,
                explicitProfiles = null,
                family = SdModelFamily.CHECKPOINT,
                variant = null
            )
        )
    }

    @Test
    fun legacyBroadIpAdapterProfileIsNarrowedOnlyWithExactEvidence() {
        assertEquals(
            "checkpoint:sdxl",
            resolveSdCompatProfiles(
                type = ModelType.SD_IP_ADAPTER,
                explicitProfiles = "checkpoint:sdxl, checkpoint:sd1",
                family = SdModelFamily.CHECKPOINT,
                variant = "sdxl"
            )
        )
    }

    @Test
    fun staleBroadIpAdapterRowIsNarrowedFromSdxlFilename() {
        val model = ModelEntity(
            filename = "ip-adapter-plus_sdxl.safetensors",
            path = "/models/ip-adapter-plus_sdxl.safetensors",
            sizeBytes = 1L,
            type = ModelType.SD_IP_ADAPTER,
            repoId = "h94/IP-Adapter",
            sdCompatProfiles = "checkpoint:sd1,checkpoint:sdxl"
        )

        assertEquals(setOf("checkpoint:sdxl"), model.effectiveSdCompatProfiles())
    }

    @Test
    fun staleBroadIpAdapterRowIsNarrowedFromSd1Filename() {
        val model = ModelEntity(
            filename = "ip-adapter_sd15.safetensors",
            path = "/models/ip-adapter_sd15.safetensors",
            sizeBytes = 1L,
            type = ModelType.SD_IP_ADAPTER,
            repoId = "h94/IP-Adapter",
            sdCompatProfiles = "checkpoint:sd1,checkpoint:sdxl"
        )

        assertEquals(setOf("checkpoint:sd1"), model.effectiveSdCompatProfiles())
    }

    @Test
    fun nativeAdetailerAndUpscalerUseTheirSpecializedValidators() {
        assertFalse(ModelType.SD_ADETAILER.isStableDiffusionArtifact())
        assertFalse(ModelType.SD_UPSCALER.isStableDiffusionArtifact())
        assertTrue(ModelType.SD_CHECKPOINT.isStableDiffusionArtifact())
    }

    private fun inspection(
        family: SdModelFamily?,
        role: SdArtifactRole?,
        layout: SdMainLayout,
        confidence: SdInspectionConfidence,
        warnings: List<String> = emptyList()
    ) = SdArtifactInspection(
        format = SdArtifactFormat.SAFETENSORS,
        detectedFamily = family,
        detectedRole = role,
        tensorCount = 2,
        confidence = confidence,
        warnings = warnings,
        mainLayout = layout,
        fileSizeBytes = 2,
        modifiedAtMillis = 1
    )
}
