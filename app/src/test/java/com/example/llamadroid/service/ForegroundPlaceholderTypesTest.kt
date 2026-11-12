package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundPlaceholderTypesTest {
    private val special = ForegroundServiceTypes.SPECIAL_USE
    private val data = ForegroundServiceTypes.DATA_SYNC
    private val playback = ForegroundServiceTypes.MEDIA_PLAYBACK
    private val mic = ForegroundServiceTypes.MICROPHONE

    @Test
    fun unknownOrOldSdkUsesTypelessForm() {
        assertEquals(listOf(0), ForegroundStart.placeholderTypes(28, special))
        assertEquals(listOf(0), ForegroundStart.placeholderTypes(34, null))
        assertEquals(listOf(0), ForegroundStart.placeholderTypes(34, 0))
    }

    @Test
    fun prefersSpecialUseOnApi34ThenDataSync() {
        assertEquals(listOf(special, data), ForegroundStart.placeholderTypes(34, special or data))
        assertEquals(listOf(data, special), ForegroundStart.placeholderTypes(30, special or data))
    }

    @Test
    fun onlyDeclaredTypesAreUsedAndMicrophoneNever() {
        assertEquals(listOf(playback), ForegroundStart.placeholderTypes(35, mic or playback))
        assertEquals(listOf(0), ForegroundStart.placeholderTypes(35, mic))
    }
}
