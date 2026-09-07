package com.example.llamadroid.ui.models

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FullHfRepositoryBrowserTest {
    @Test
    fun selectedFileUrlPreservesNestedSegmentsAndEncodesFileCharacters() {
        val url = hfSelectedFileUrl(
            repositoryId = "acme/rocket",
            revision = "0123456789abcdef",
            relativePath = "weights/mobile model#1.safetensors"
        ).toHttpUrl()

        assertEquals(
            listOf("acme", "rocket", "resolve", "0123456789abcdef", "weights", "mobile model#1.safetensors"),
            url.pathSegments
        )
        assertEquals(
            "/acme/rocket/resolve/0123456789abcdef/weights/mobile%20model%231.safetensors",
            url.encodedPath
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectedFileUrlRejectsTraversalSegments() {
        hfSelectedFileUrl("acme/rocket", "main", "weights/../config.json")
    }
}
