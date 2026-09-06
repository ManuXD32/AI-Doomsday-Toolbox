package com.example.llamadroid.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelLibrarySplitBundleTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun discoversOnlyExistingSiblingsFromTheDeclaredSplitGroup() {
        val selected = temporary.newFile("qwen-00002-of-00003.gguf")
        temporary.newFile("qwen-00001-of-00003.gguf")
        temporary.newFile("qwen-00003-of-00003.gguf")
        temporary.newFile("qwen-00001-of-00004.gguf")
        temporary.newFile("unrelated.gguf")

        val parts = listExistingSplitBundleParts(selected.path, selected.name)

        assertEquals(listOf(0, 1, 2), parts.map { it.partIndex })
        assertEquals(listOf(3, 3, 3), parts.map { it.partCount })
        assertEquals(setOf("qwen.gguf"), parts.map { it.partGroup }.toSet())
        assertFalse(parts.any { it.relativePath.contains("00004") })
        assertFalse(parts.any { it.relativePath == "unrelated.gguf" })
    }

    @Test
    fun keepsDeclaredCountWhenAShardIsStillMissing() {
        val selected = temporary.newFile("model-00002-of-00003.gguf")
        val parts = listExistingSplitBundleParts(selected.path, selected.name)

        assertEquals(listOf(1), parts.map { it.partIndex })
        assertEquals(listOf(3), parts.map { it.partCount })
    }
}
