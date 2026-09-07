package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentUriDisplayNameTest {

    @Test
    fun `uses a recognizable path segment as fallback`() {
        assertEquals(
            "qa-functional-document.pdf",
            DocumentUriDisplayName.fallbackNameFromPathSegment(
                "qa-functional-document.pdf",
                "Selected PDF"
            )
        )
    }

    @Test
    fun `does not expose opaque provider token as fallback`() {
        assertEquals(
            "Selected PDF",
            DocumentUriDisplayName.fallbackNameFromPathSegment(
                "msf:84",
                "Selected PDF"
            )
        )
    }

    @Test
    fun `does not expose a numeric provider row id`() {
        assertEquals(
            "Selected PDF",
            DocumentUriDisplayName.fallbackNameFromPathSegment(
                "84",
                "Selected PDF"
            )
        )
    }

    @Test
    fun `decodes a unicode filename fallback`() {
        assertEquals(
            "informe final ñ.pdf",
            DocumentUriDisplayName.fallbackNameFromPathSegment(
                "informe final ñ.pdf",
                "Selected PDF"
            )
        )
    }

    @Test
    fun `falls back for an empty or missing path`() {
        assertEquals(
            "Selected PDF",
            DocumentUriDisplayName.fallbackNameFromPathSegment(null, "Selected PDF")
        )
    }
}
