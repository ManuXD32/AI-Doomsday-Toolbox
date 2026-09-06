package com.example.llamadroid.data.model.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSourceDownloadAuthPolicyTest {
    @Test
    fun `direct HTTPS retry never carries an old or supplied HF token`() {
        assertNull(
            bearerTokenForSource(
                kind = ModelSourceKind.HTTPS,
                requestedToken = "hf_supplied_secret",
                persistedToken = "hf_old_secret"
            )
        )
    }

    @Test
    fun `HF retry prefers the current token and falls back to persisted token`() {
        assertEquals(
            "hf_current",
            bearerTokenForSource(
                kind = ModelSourceKind.HUGGING_FACE_FILE,
                requestedToken = "  hf_current  ",
                persistedToken = "hf_old"
            )
        )
        assertEquals(
            "hf_old",
            bearerTokenForSource(
                kind = ModelSourceKind.HUGGING_FACE_REPOSITORY,
                requestedToken = "  ",
                persistedToken = " hf_old "
            )
        )
    }
}
