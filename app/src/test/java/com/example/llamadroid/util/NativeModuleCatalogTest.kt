package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeModuleCatalogTest {
    @Test
    fun onlyPrimaryCpuModulesAreEligibleForAutomaticProvisioning() {
        assertTrue(
            NativeModuleCatalog.definitions
                .filter { it.mayBeAutoProvisioned }
                .all { it.backend == NativeBackendKind.CPU && it.tier != NativeCpuTier.I8MM }
        )
        assertFalse(NativeModuleCatalog.require("feature_llm_i8mm").mayBeAutoProvisioned)
        assertFalse(NativeModuleCatalog.require("feature_llm_snapdragon_opencl").mayBeAutoProvisioned)
        assertFalse(NativeModuleCatalog.require("feature_media_i8mm").mayBeAutoProvisioned)
        assertFalse(NativeModuleCatalog.require("feature_media_snapdragon_vulkan").mayBeAutoProvisioned)
        assertFalse(NativeModuleCatalog.require("feature_media_snapdragon_opencl").mayBeAutoProvisioned)
    }

    @Test
    fun catalogueCoversOnlyPublishedMediaTiers() {
        assertEquals(
            setOf(
                "feature_media_baseline",
                "feature_media_dotprod",
                "feature_media_armv9",
                "feature_media_i8mm",
                "feature_media_snapdragon_vulkan",
                "feature_media_snapdragon_opencl"
            ),
            NativeModuleCatalog.definitions
                .filter { it.family == NativeEngineFamily.MEDIA }
                .map { it.moduleName }
                .toSet()
        )
        assertNotNull(NativeModuleCatalog.require("feature_llm_i8mm"))
    }

    @Test
    fun cpuModulesRequireCompleteStaticPayloads() {
        val llm = NativeModuleCatalog.require("feature_llm_i8mm")
        assertTrue("librpc-server_i8mm.so" in llm.expectedFiles)
        assertTrue("libwhisper-cli_i8mm.so" in llm.expectedFiles)
        assertTrue("libquadtrix_trainer_i8mm.so" in llm.expectedFiles)
        assertFalse("libggml.so" in llm.expectedFiles)

        val media = NativeModuleCatalog.require("feature_media_dotprod")
        assertTrue("libsd_dotprod.so" in media.expectedFiles)
        assertTrue("libsd-rpc-server_dotprod.so" in media.expectedFiles)
        assertTrue("libwhisper-cli_dotprod.so" in media.expectedFiles)
        assertFalse("libomp.so" in media.expectedFiles)

        val mediaI8mm = NativeModuleCatalog.require("feature_media_i8mm")
        assertEquals(setOf("libsd_i8mm.so"), mediaI8mm.expectedFiles)

        val mediaOpenCl = NativeModuleCatalog.require("feature_media_snapdragon_opencl")
        assertEquals(
            setOf("libsd_snapdragon_opencl.so", "libAIDOCL.so"),
            mediaOpenCl.expectedFiles
        )
    }

    @Test
    fun knownBuiltInPayloadDoesNotNeedDynamicStaging() {
        assertTrue(NativeModuleCatalog.isBuiltInStaticPayload("libllama_server_dotprod.so"))
        assertTrue(NativeModuleCatalog.isBuiltInStaticPayload("libsd_snapdragon_vulkan.so"))
        assertFalse(NativeModuleCatalog.isBuiltInStaticPayload("libllama_server.so"))
    }
}
