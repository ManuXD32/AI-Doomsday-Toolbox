package com.example.llamadroid.data.model.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLibraryErrorMappingTest {
    @Test fun `maps common HF responses to recoverable localized codes`() {
        assertEquals(ModelLibraryErrorCode.AUTHENTICATION_REQUIRED, modelLibraryHttpErrorCode(401))
        assertEquals(ModelLibraryErrorCode.SOURCE_NOT_FOUND, modelLibraryHttpErrorCode(404))
        assertEquals(ModelLibraryErrorCode.RATE_LIMITED, modelLibraryHttpErrorCode(429))
        assertEquals(ModelLibraryErrorCode.NETWORK_FAILURE, modelLibraryHttpErrorCode(503))
        assertEquals(ModelLibraryErrorCode.REQUEST_TIMEOUT, modelLibraryHttpErrorCode(504))
    }

    @Test fun `unknown failures are internal errors instead of invalid URLs or HTTP failures`() {
        assertEquals(ModelLibraryErrorCode.INTERNAL_ERROR, modelLibraryErrorCode(IllegalStateException("server")))
    }
    @Test fun `parsing and revision failures are distinguished without logging content`() {
        assertEquals(ModelLibraryErrorCode.RESPONSE_PARSING,
            modelLibraryErrorCode(kotlinx.serialization.SerializationException("private body")))
        assertEquals(ModelLibraryErrorCode.REVISION_NOT_FOUND,
            modelLibraryErrorCode(HuggingFaceHttpException(404, "private URL", "revision")))
        val metadata = modelLibraryFailureMetadata(IllegalStateException("secret token and private body"), "folder")
        org.junit.Assert.assertFalse(metadata.contains("secret"))
        org.junit.Assert.assertTrue(metadata.contains("INTERNAL_ERROR"))
    }
}
