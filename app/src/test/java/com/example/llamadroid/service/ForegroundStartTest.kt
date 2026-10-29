package com.example.llamadroid.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartTest {

    @Test
    fun localTypeBitsMatchTheFramework() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ForegroundServiceTypes.DATA_SYNC)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, ForegroundServiceTypes.MEDIA_PLAYBACK)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ForegroundServiceTypes.MICROPHONE)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, ForegroundServiceTypes.MEDIA_PROCESSING)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, ForegroundServiceTypes.SPECIAL_USE)
    }
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val playback = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    private val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val mediaProcessing = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING

    @Test
    fun belowQ_requestIsUnchanged() {
        assertEquals(mic or playback, ForegroundStart.effectiveTypes(mic or playback, 28, false, null))
    }

    @Test
    fun microphoneDroppedWithoutPermission() {
        assertEquals(playback, ForegroundStart.effectiveTypes(mic or playback, 34, false, mic or playback))
        assertEquals(0, ForegroundStart.effectiveTypes(mic, 34, false, mic or playback))
    }

    @Test
    fun microphoneKeptWithPermission() {
        assertEquals(mic or playback, ForegroundStart.effectiveTypes(mic or playback, 36, true, mic or playback))
    }

    @Test
    fun mediaProcessingMapsToDataSyncBelow35() {
        val manifest = mediaProcessing or dataSync
        assertEquals(dataSync, ForegroundStart.effectiveTypes(mediaProcessing, 34, true, manifest))
        assertEquals(mediaProcessing, ForegroundStart.effectiveTypes(mediaProcessing, 35, true, manifest))
    }

    @Test
    fun typesMissingFromManifestAreDropped() {
        assertEquals(dataSync, ForegroundStart.effectiveTypes(dataSync or mediaProcessing, 35, true, dataSync))
        assertEquals(0, ForegroundStart.effectiveTypes(mic, 35, true, dataSync))
    }

    @Test
    fun unknownManifestTypesDoNotFilter() {
        assertEquals(mic, ForegroundStart.effectiveTypes(mic, 35, true, null))
        assertEquals(mic, ForegroundStart.effectiveTypes(mic, 35, true, 0))
    }

    @Test
    fun classifiesExceptionsByName() {
        assertEquals(
            ForegroundStart.Reason.SECURITY,
            ForegroundStart.classifyFailure(SecurityException("Starting FGS with type microphone"))
        )
        assertNull(ForegroundStart.classifyFailure(IllegalStateException("x")))
        assertEquals(
            ForegroundStart.Reason.BACKGROUND_NOT_ALLOWED,
            ForegroundStart.classifyFailure(ForegroundServiceStartNotAllowedException())
        )
        assertEquals(
            ForegroundStart.Reason.INVALID_TYPE,
            ForegroundStart.classifyFailure(InvalidForegroundServiceTypeException())
        )
    }

    @Test
    fun retriesMediaProcessingWithDataSyncOnlyWhenDeclared() {
        assertEquals(dataSync, ForegroundStart.retryTypes(mediaProcessing, mediaProcessing or dataSync))
        assertEquals(dataSync or playback, ForegroundStart.retryTypes(mediaProcessing or playback, mediaProcessing or dataSync))
        assertNull(ForegroundStart.retryTypes(mediaProcessing, mediaProcessing))
        assertNull(ForegroundStart.retryTypes(mediaProcessing, null))
        assertNull(ForegroundStart.retryTypes(dataSync, mediaProcessing or dataSync))
        assertNull(ForegroundStart.retryTypes(mic, mic or dataSync))
    }

    @Test
    fun visibleImportanceExcludesForegroundServiceOnly() {
        assertTrue(ForegroundStart.isVisibleImportance(100))
        assertTrue(ForegroundStart.isVisibleImportance(200))
        assertFalse(ForegroundStart.isVisibleImportance(125))
        assertFalse(ForegroundStart.isVisibleImportance(300))
    }
}

// Same simple names as the framework classes; classification is by simple name.
private class ForegroundServiceStartNotAllowedException : IllegalStateException()
private class InvalidForegroundServiceTypeException : RuntimeException()
