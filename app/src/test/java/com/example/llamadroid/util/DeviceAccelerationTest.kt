package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DeviceAccelerationTest {
    @Test
    fun isAcceleratorBinary_detectsSpecializedPayloads() {
        assertEquals(true, DeviceAcceleration.isAcceleratorBinary(File("libllama_server_snapdragon_opencl.so")))
        assertEquals(false, DeviceAcceleration.isAcceleratorBinary(File("libsd_snapdragon_vulkan.so")))
        assertEquals(false, DeviceAcceleration.isAcceleratorBinary(File("libllama_server_dotprod.so")))
    }

    @Test
    fun resolveReadinessStatus_separatesInstallAndRuntimeStates() {
        assertEquals(
            AccelerationStatus.UNSUPPORTED,
            DeviceAcceleration.resolveReadinessStatus(
                isCompatible = false,
                installed = false,
                active = false,
                moduleStates = emptyList()
            )
        )
        assertEquals(
            AccelerationStatus.SUPPORTED_NOT_INSTALLED,
            DeviceAcceleration.resolveReadinessStatus(
                isCompatible = true,
                installed = false,
                active = false,
                moduleStates = listOf(
                    AcceleratorModuleState("feature_llm_snapdragon_opencl", AccelerationStatus.SUPPORTED_NOT_INSTALLED)
                )
            )
        )
        assertEquals(
            AccelerationStatus.INSTALLING,
            DeviceAcceleration.resolveReadinessStatus(
                isCompatible = true,
                installed = false,
                active = false,
                moduleStates = listOf(
                    AcceleratorModuleState("feature_llm_snapdragon_opencl", AccelerationStatus.INSTALLING)
                )
            )
        )
        assertEquals(
            AccelerationStatus.CPU_FALLBACK,
            DeviceAcceleration.resolveReadinessStatus(
                isCompatible = true,
                installed = true,
                active = false,
                moduleStates = emptyList()
            )
        )
        assertEquals(
            AccelerationStatus.ACTIVE,
            DeviceAcceleration.resolveReadinessStatus(
                isCompatible = true,
                installed = true,
                active = true,
                moduleStates = emptyList()
            )
        )
    }

}
