package com.example.llamadroid.data.easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EasyModelNamesTest {
    @Test fun curatedTitleDropsCompleteSuffix() {
        assertEquals("Gemma 4 E2B", EasyModelNames.friendlyName("x.gguf", "Gemma 4 E2B complete"))
        assertEquals("Gemma 4 E2B", EasyModelNames.friendlyName("x.gguf", "Gemma 4 E2B completo"))
        assertEquals("Qwen 3.5 2B", EasyModelNames.friendlyName("x.gguf", "Qwen 3.5 2B"))
    }

    @Test fun fallsBackToFilenameWithoutExtension() {
        assertEquals("my-model-Q4", EasyModelNames.friendlyName("/data/models/my-model-Q4.gguf", null))
        assertEquals("my-model", EasyModelNames.friendlyName("my-model.gguf", "  "))
        assertEquals("", EasyModelNames.friendlyName(null, null))
    }

    @Test fun curatedLookupMatchesPrefixedBundleFile() {
        assertNotNull(EasyModelNames.curatedTitleRes("Gemma4-E2B-gemma-4-E2B-it-Q4_K_M.gguf"))
        assertNull(EasyModelNames.curatedTitleRes("unknown-model.gguf"))
        assertNull(EasyModelNames.curatedTitleRes(null))
    }
}
