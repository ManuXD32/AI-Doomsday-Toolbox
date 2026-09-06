package com.example.llamadroid.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelLibraryQueuePolicyTest {
    @Test
    fun `custom source operation key preserves path identity but ignores URL presentation`() {
        val first = customDownloadOperationKey(
            "https://huggingface.co/acme/rocket/resolve/main/folder/model.gguf#download"
        )
        val second = customDownloadOperationKey(
            "https://huggingface.co/acme/rocket/resolve/main/folder/model.gguf"
        )
        val differentFile = customDownloadOperationKey(
            "https://huggingface.co/acme/rocket/resolve/main/folder/other.gguf"
        )

        assertEquals(first, second)
        assertNotEquals(first, differentFile)
    }
}
