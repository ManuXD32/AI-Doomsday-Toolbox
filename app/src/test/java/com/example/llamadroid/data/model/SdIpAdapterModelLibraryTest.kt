package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdIpAdapterModelLibraryTest {

    @Test
    fun `ip adapter model types use separate managed directories`() {
        assertTrue(ModelLibraryManager.usesManagedExternalCanonicalStorage(ModelType.SD_CLIP_VISION))
        assertTrue(ModelLibraryManager.usesManagedExternalCanonicalStorage(ModelType.SD_IP_ADAPTER))
        assertEquals("sd/clip_vision", ModelLibraryManager.relativeDirFor(ModelType.SD_CLIP_VISION))
        assertEquals("sd/ip_adapter", ModelLibraryManager.relativeDirFor(ModelType.SD_IP_ADAPTER))
    }
}
