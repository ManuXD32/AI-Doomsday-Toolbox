package com.example.llamadroid.ui.easy

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class EasyModelRemovalTest {
    private fun row(name: String, size: Long = 10L) =
        ModelEntity(filename = name, path = "/models/$name", sizeBytes = size, type = ModelType.LLM, repoId = "repo")

    @Test fun `files used by another installed bundle are kept`() {
        val own = listOf(row("main.gguf"), row("shared-mmproj.gguf"))
        val result = selectEasyRemovalModels(own, setOf("/models/shared-mmproj.gguf"))
        assertEquals(listOf("main.gguf"), result.map { it.filename })
    }

    @Test fun `nothing protected removes every file`() {
        val own = listOf(row("a.gguf"), row("b.gguf"))
        assertEquals(own, selectEasyRemovalModels(own, emptySet()))
    }

    @Test fun `duplicate paths collapse to one deletion`() {
        val a = row("a.gguf")
        assertEquals(listOf(a), selectEasyRemovalModels(listOf(a, a.copy(filename = "alias")), emptySet()).take(1))
        assertEquals(1, selectEasyRemovalModels(listOf(a, a), emptySet()).size)
    }

    @Test fun `freed bytes sums the planned rows`() {
        val request = EasyRemovalRequest("x", listOf(row("a", 5L), row("b", 7L)), null)
        assertEquals(12L, request.freedBytes)
    }
}
