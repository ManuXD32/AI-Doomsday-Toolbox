package com.example.llamadroid.ui.settings

import com.example.llamadroid.data.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralSettingsScreenTest {

    @Test
    fun experimentalNativeBinaryWarningAppliesOnlyToExplicitAccelerators() {
        assertFalse(isExperimentalNativeBinarySelection(SettingsRepository.NATIVE_BINARY_AUTO))
        assertFalse(isExperimentalNativeBinarySelection(SettingsRepository.NATIVE_BINARY_CPU_DOTPROD))
        assertFalse(isExperimentalNativeBinarySelection(SettingsRepository.NATIVE_BINARY_CPU_I8MM))
        assertTrue(isExperimentalNativeBinarySelection(SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL))
        assertTrue(isExperimentalNativeBinarySelection(SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN))
    }
}
